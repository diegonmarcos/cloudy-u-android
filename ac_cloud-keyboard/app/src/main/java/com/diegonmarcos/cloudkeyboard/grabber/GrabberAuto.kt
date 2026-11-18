package com.diegonmarcos.cloudkeyboard.grabber

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityEvent

/*
 * #1793 "Grabber on selection". Android never starts an app because text was selected: the selection menu entry (#1754,
 * ACTION_PROCESS_TEXT) needs a tap. Two opt-in ways open the Grabber panel by themselves, each behind its own Configs switch,
 * both OFF by default:
 *
 *  - ACCESSIBILITY: [GrabberSelectionService] listens to one event type, TYPE_VIEW_TEXT_SELECTION_CHANGED, in every app; the
 *    user turns the service on in Android's Accessibility settings (the switch's button deep-links there).
 *  - KEYBOARD: where Cloud Keyboard is the input method, LatinIME.onUpdateSelection already reports the field's selection
 *    (GrabberLink), so no permission at all.
 *
 * The decision is [SelectionTrigger] (pure, GrabberAutoTest): a real selection of 2 to 2000 characters with a letter or digit,
 * not in a password field, not in this app, steady for [SelectionTrigger.debounceMs], and not the selection that just opened.
 */

/** The pure trigger. Time is passed in, so the debounce is a unit test. */
class SelectionTrigger(
    val debounceMs: Long = DEBOUNCE_MS,
    val minChars: Int = 2,
    val maxChars: Int = 2000,
    val repeatGapMs: Long = 10_000,
) {
    data class Candidate(val text: String, val pkg: String, val at: Long)

    private var pending: Candidate? = null
    private var lastFired: Candidate? = null

    companion object {
        const val DEBOUNCE_MS = 900L

        /** The selected part of [text] for a [start], [end] selection in either direction; null when collapsed or out of range. */
        fun selected(text: CharSequence?, start: Int, end: Int): String? {
            if (text == null) return null
            val a = minOf(start, end); val b = maxOf(start, end)
            if (a < 0 || a == b || b > text.length) return null
            return text.subSequence(a, b).toString()
        }

        /** An input type that hides what is typed (password, PIN): never grabbed. */
        fun isPasswordInput(inputType: Int): Boolean {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val v = inputType and InputType.TYPE_MASK_VARIATION
            return (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) || (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
    }

    /** The text worth grabbing, or null: the filter before the debounce. */
    fun accept(sel: String?, pkg: String, ownPkg: String, password: Boolean): String? {
        if (sel == null || password || pkg == ownPkg) return null
        val t = sel.trim()
        if (t.length < minChars || t.length > maxChars) return null
        if (t.none { it.isLetterOrDigit() }) return null
        return t
    }

    /** A selection event. [text] null (collapsed, filtered) cancels what was pending. Returns when to look again, or null. */
    fun offer(text: String?, pkg: String, now: Long): Long? {
        if (text == null) { pending = null; return null }
        pending = Candidate(text, pkg, now)
        return now + debounceMs
    }

    /** At a look: the text to open now, or null (a newer event came, nothing pending, or it is the selection that just opened). */
    fun due(now: Long): String? {
        val p = pending ?: return null
        if (now - p.at < debounceMs) return null
        pending = null
        val l = lastFired
        if (l != null && l.text == p.text && l.pkg == p.pkg && now - l.at < repeatGapMs) return null
        lastFired = Candidate(p.text, p.pkg, now)
        return p.text
    }

    fun pendingText(): String? = pending?.text
}

/** The switches, the open call and the IME path. */
object GrabberAuto {
    const val PREFS = "grabber"
    const val KEY_A11Y = "on_selection_accessibility"
    const val KEY_IME = "on_selection_keyboard"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun a11yOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_A11Y, false)
    fun imeOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_IME, false)
    fun setA11y(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_A11Y, on).apply()
    fun setIme(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_IME, on).apply()

    /** The real state of the service in Android (Settings.Secure), not the switch: the page shows both. */
    fun serviceEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, GrabberSelectionService::class.java)
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Open the Grabber panel over whatever is on screen, with [text] (read-only: an automatic open never writes into the field). */
    fun open(ctx: Context, text: String, source: String) {
        val i = Intent(ctx, GrabberActivity::class.java)
            .setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            .putExtra(GrabberActivity.EXTRA_SOURCE, source)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { ctx.startActivity(i) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val imeTrigger = SelectionTrigger()
    private val imeLook = Runnable { imeFire() }
    @Volatile private var imeCtx: Context? = null

    /** GrabberLink.selectionHook: the keyboard saw a non-empty selection [start, end) in its field. */
    fun onImeSelection(ime: InputMethodService, start: Int, end: Int) {
        if (!imeOn(ime)) return
        val info = ime.currentInputEditorInfo
        val pkg = info?.packageName.orEmpty()
        val password = info != null && SelectionTrigger.isPasswordInput(info.inputType)
        val sel = if (password || pkg == ime.packageName) null
            else runCatching { ime.currentInputConnection?.getSelectedText(0)?.toString() }.getOrNull()
        val t = imeTrigger.accept(sel, pkg, ime.packageName, password)
        main.removeCallbacks(imeLook)
        val at = imeTrigger.offer(t, pkg, SystemClock.uptimeMillis()) ?: return
        imeCtx = ime.applicationContext
        main.postAtTime(imeLook, at)
    }

    private fun imeFire() {
        val ctx = imeCtx ?: return
        val text = imeTrigger.due(SystemClock.uptimeMillis()) ?: return
        if (imeOn(ctx)) open(ctx, text, "keyboard")
    }
}

/**
 * The opt-in accessibility service. Its configuration (res/xml/grabber_accessibility.xml) subscribes to text-selection events
 * only and does not retrieve window content: what it reads is the event itself (the text of the view, the selection range, the
 * package). Nothing is kept or sent by the service; the panel it opens works like the selection-menu entry.
 */
class GrabberSelectionService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val trigger = SelectionTrigger()
    private val look = Runnable { trigger.due(SystemClock.uptimeMillis())?.let { if (GrabberAuto.a11yOn(this)) GrabberAuto.open(this, it, "accessibility") } }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        if (!GrabberAuto.a11yOn(this)) return
        val pkg = e.packageName?.toString().orEmpty()
        val text = e.text?.firstOrNull()
        val sel = SelectionTrigger.selected(text, e.fromIndex, e.toIndex)
        val t = trigger.accept(sel, pkg, packageName, e.isPassword)
        handler.removeCallbacks(look)
        val at = trigger.offer(t, pkg, SystemClock.uptimeMillis()) ?: return
        handler.postAtTime(look, at)
    }

    override fun onInterrupt() { handler.removeCallbacks(look) }

    override fun onDestroy() { handler.removeCallbacks(look); super.onDestroy() }
}
