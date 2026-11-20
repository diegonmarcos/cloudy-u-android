package com.diegonmarcos.cloudkeyboard.grabber

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
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

/**
 * #1825 the event filter, pure (GrabberAutoTest). Why the service never fired: with canRetrieveWindowContent=false Android hands the
 * service events WITHOUT a source node, and a browser/WebView selection event carries no text of its own (the range is on the node),
 * so [read] found nothing; and the service stayed silent while its second switch (KEY_A11Y) was off although the user had just enabled
 * the service. [read] now takes the event's text first, then the source node's text and selection range.
 */
object SelectionEvent {
    enum class Outcome(val label: String) {
        ACCEPTED("selection accepted, waiting for it to be steady"),
        SWITCH_OFF("ignored: the Grabber on selection switch is off"),
        OWN_APP("ignored: selection in Cloud Keyboard itself"),
        PASSWORD("ignored: password field"),
        NO_SELECTION("ignored: collapsed or no readable text"),
        FILTERED("ignored: too short, too long or no letters"),
    }

    class Decision(val outcome: Outcome, val text: String?)

    /** The selected text: from the event's own text and range, else from the source node's text and selection range (ignores start == end). */
    fun read(eventText: CharSequence?, from: Int, to: Int, nodeText: CharSequence?, nodeStart: Int, nodeEnd: Int): String? =
        SelectionTrigger.selected(eventText, from, to) ?: SelectionTrigger.selected(nodeText, nodeStart, nodeEnd)

    fun decide(switchOn: Boolean, pkg: String, ownPkg: String, password: Boolean, selected: String?, trigger: SelectionTrigger): Decision = when {
        !switchOn -> Decision(Outcome.SWITCH_OFF, null)
        pkg == ownPkg -> Decision(Outcome.OWN_APP, null)
        password -> Decision(Outcome.PASSWORD, null)
        selected == null -> Decision(Outcome.NO_SELECTION, null)
        else -> trigger.accept(selected, pkg, ownPkg, false).let { if (it == null) Decision(Outcome.FILTERED, null) else Decision(Outcome.ACCEPTED, it) }
    }
}

/** #1825 how the panel is opened from the background, pure: the ordered steps tried (GrabberAutoTest). */
object GrabberLaunch {
    enum class Step { OVERLAY_THEN_START, START, NOTIFICATION }

    /**
     * From the accessibility service on Android 10+ a start can be silently dropped by the background-activity-launch rules (14+ is
     * stricter), so the start is made while a 1px TYPE_ACCESSIBILITY_OVERLAY window is up (the app then has a visible window), then a plain
     * start, and when the panel never came up a notification that opens it. The keyboard path has a window of its own: a plain start.
     */
    fun plan(sdk: Int, fromAccessibility: Boolean): List<Step> =
        if (fromAccessibility && sdk >= 29) listOf(Step.OVERLAY_THEN_START, Step.START, Step.NOTIFICATION) else listOf(Step.START)

    /** The panel must have come up within this long after the start, else the start is counted as blocked. */
    const val CONFIRM_MS = 1500L

    fun result(step: Step, opened: Boolean, error: String?): String = when {
        error != null -> "failed (${step.name.lowercase()}): $error"
        step == Step.NOTIFICATION -> "blocked, notification posted"
        opened -> "opened (${step.name.lowercase()})"
        else -> "start sent (${step.name.lowercase()}), panel not confirmed"
    }
}

/** #1825 what the Grabber page shows about the accessibility path: service, last event, last launch. Stored in prefs (service and UI share one process). */
object GrabberDiag {
    private const val P = "grabber_diag"
    class Snapshot(val connectedAt: Long, val eventAt: Long, val eventPkg: String, val eventOutcome: String, val launchAt: Long, val launchResult: String)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun connected(ctx: Context) = prefs(ctx).edit().putLong("connected_at", System.currentTimeMillis()).apply()
    fun event(ctx: Context, pkg: String, outcome: String) =
        prefs(ctx).edit().putLong("event_at", System.currentTimeMillis()).putString("event_pkg", pkg).putString("event_outcome", outcome).apply()
    fun launch(ctx: Context, result: String) = prefs(ctx).edit().putLong("launch_at", System.currentTimeMillis()).putString("launch_result", result).apply()
    @Volatile var openedAt = 0L

    fun snapshot(ctx: Context): Snapshot = prefs(ctx).let {
        Snapshot(it.getLong("connected_at", 0), it.getLong("event_at", 0), it.getString("event_pkg", "").orEmpty(), it.getString("event_outcome", "").orEmpty(),
            it.getLong("launch_at", 0), it.getString("launch_result", "").orEmpty())
    }

    /** "12:03:41 · com.android.chrome · selection accepted..." or the never-seen line. */
    fun eventLine(s: Snapshot, clock: (Long) -> String): String =
        if (s.eventAt == 0L) "none yet: select text in another app" else "${clock(s.eventAt)} · ${s.eventPkg.ifEmpty { "?" }} · ${s.eventOutcome}"

    fun launchLine(s: Snapshot, clock: (Long) -> String): String =
        if (s.launchAt == 0L) "none yet" else "${clock(s.launchAt)} · ${s.launchResult}"
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

    private fun panelIntent(ctx: Context, text: String, source: String) = Intent(ctx, GrabberActivity::class.java)
        .setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        .putExtra(GrabberActivity.EXTRA_SOURCE, source)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /**
     * Open the Grabber panel over whatever is on screen, with [text] (read-only: an automatic open never writes into the field). The steps
     * are [GrabberLaunch.plan]; the result of the last launch is kept for the Grabber page ([GrabberDiag]).
     */
    fun open(ctx: Context, text: String, source: String, service: AccessibilityService? = null) {
        val steps = GrabberLaunch.plan(Build.VERSION.SDK_INT, service != null)
        val i = panelIntent(ctx, text, source)
        val sentAt = SystemClock.uptimeMillis()
        for (step in steps) {
            when (step) {
                GrabberLaunch.Step.OVERLAY_THEN_START -> {
                    val err = withOverlay(service!!) { runCatching { ctx.startActivity(i) }.exceptionOrNull()?.message }
                    if (err != null) { GrabberDiag.launch(ctx, GrabberLaunch.result(step, false, err)); continue }
                }
                GrabberLaunch.Step.START -> {
                    val err = runCatching { ctx.startActivity(i) }.exceptionOrNull()?.message
                    if (err != null) { GrabberDiag.launch(ctx, GrabberLaunch.result(step, false, err)); continue }
                }
                GrabberLaunch.Step.NOTIFICATION -> continue
            }
            GrabberDiag.launch(ctx, GrabberLaunch.result(step, false, null))
            // a start Android dropped raises no error: look whether the panel came up, else post the notification
            main.postDelayed({
                if (GrabberDiag.openedAt >= sentAt) GrabberDiag.launch(ctx, GrabberLaunch.result(step, true, null))
                else if (steps.contains(GrabberLaunch.Step.NOTIFICATION)) { notifyOpen(ctx, i); GrabberDiag.launch(ctx, GrabberLaunch.result(GrabberLaunch.Step.NOTIFICATION, false, null)) }
            }, GrabberLaunch.CONFIRM_MS)
            return
        }
        if (steps.contains(GrabberLaunch.Step.NOTIFICATION)) { notifyOpen(ctx, i); GrabberDiag.launch(ctx, GrabberLaunch.result(GrabberLaunch.Step.NOTIFICATION, false, null)) }
    }

    /** Runs [block] while a 1px accessibility overlay window is attached: the app then has a visible window, which Android's background-start rules allow. */
    private fun withOverlay(svc: AccessibilityService, block: () -> String?): String? {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = View(svc)
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
        val added = runCatching { wm.addView(v, lp) }.isSuccess
        val err = block()
        if (added) main.postDelayed({ runCatching { wm.removeView(v) } }, GrabberLaunch.CONFIRM_MS + 500)
        return err
    }

    private fun notifyOpen(ctx: Context, i: Intent) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel("grabber_open", "Grabber on selection", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = android.app.PendingIntent.getActivity(ctx, 18251, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, "grabber_open").setContentTitle("Grabber").setContentText("Tap to look up the selected text")
            .setSmallIcon(android.R.drawable.ic_menu_search).setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60_000).build()
        runCatching { nm.notify(18251, n) }
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
 * only; #1825 it must retrieve window content (else events carry no source node and a browser selection is unreadable). What it reads
 * is the event (the text of the view, the selection range, the package) and the source node's text and selection range. Nothing is
 * kept or sent by the service; the panel it opens works like the selection-menu entry.
 */
class GrabberSelectionService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val trigger = SelectionTrigger()
    private val look = Runnable { trigger.due(SystemClock.uptimeMillis())?.let { if (GrabberAuto.a11yOn(this)) GrabberAuto.open(this, it, "accessibility", this) } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        GrabberDiag.connected(this)
        // #1825 enabling the service in Android is the opt-in: the second switch starts on unless the user already set it
        val p = getSharedPreferences(GrabberAuto.PREFS, Context.MODE_PRIVATE)
        if (!p.contains(GrabberAuto.KEY_A11Y)) GrabberAuto.setA11y(this, true)
    }

    /** #1829 the deep node search runs here, never on the main thread (each child read is an IPC to the app). */
    private val worker by lazy { android.os.HandlerThread("grabber-selection").apply { start() } }
    private val workHandler by lazy { Handler(worker.looper) }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        val pkg = e.packageName?.toString().orEmpty()
        // Everything the event carries is read NOW: the event is recycled when this returns. The source node is our own copy.
        val src: AccessibilityNodeInfo? = runCatching { e.source }.getOrNull()
        val eventText = e.text?.firstOrNull { !it.isNullOrEmpty() }
        val from = e.fromIndex
        val to = e.toIndex
        val password = e.isPassword || src?.isPassword == true
        // Fast path: the event's own text and range, or the source node's (an editable field, a plain TextView).
        val quick = SelectionEvent.read(eventText, from, to, src?.text, src?.textSelectionStart ?: -1, src?.textSelectionEnd ?: -1)
        if (quick != null || src == null || password) {
            @Suppress("DEPRECATION") runCatching { src?.recycle() }
            settle(pkg, password, quick, "")
            return
        }
        // #1829 a browser (Chrome, Samsung Internet, Firefox, any WebView) reports a page selection on the web root, while the range
        // sits on a text node far down the page's tree: the old walk (6 levels, 150 nodes, main thread) almost never reached it.
        workHandler.post {
            val found = SelectionNodeSearch.find(src)
            val sel = found.node?.let { n -> SelectionTrigger.selected(n.text, n.textSelectionStart, n.textSelectionEnd) }
            @Suppress("DEPRECATION") runCatching { if (found.node !== src) found.node?.recycle(); src.recycle() }
            handler.post { settle(pkg, false, sel, " (searched ${found.visited} nodes, depth ${found.depth})") }
        }
    }

    /** The decision, the diagnostics line and the steady-selection timer, on the main thread. */
    private fun settle(pkg: String, password: Boolean, sel: String?, searchNote: String) {
        val d = SelectionEvent.decide(GrabberAuto.a11yOn(this), pkg, packageName, password, sel, trigger)
        GrabberDiag.event(this, pkg, d.outcome.label + searchNote)
        if (!GrabberAuto.a11yOn(this)) return
        handler.removeCallbacks(look)
        val at = trigger.offer(d.text, pkg, SystemClock.uptimeMillis()) ?: return
        handler.postAtTime(look, at)
    }

    override fun onInterrupt() { handler.removeCallbacks(look) }

    override fun onDestroy() {
        handler.removeCallbacks(look)
        runCatching { worker.quitSafely() }
        super.onDestroy()
    }
}

/**
 * #1829 where a page's text selection lives: breadth-first under the event's source, only nodes visible on screen (a selection is
 * always on screen), up to [MAX_NODES] nodes, [MAX_DEPTH] levels and [MAX_MILLIS] ms. The first node with a real range
 * (start >= 0, start != end) wins. [Tree] abstracts the node so the walk is tested on the JVM (SelectionNodeSearchTest).
 */
object SelectionNodeSearch {
    const val MAX_NODES = 3000
    const val MAX_DEPTH = 80
    const val MAX_MILLIS = 400L

    interface Tree<N> {
        fun children(n: N): List<N>
        fun hasSelection(n: N): Boolean
        fun visible(n: N): Boolean
        fun release(n: N) {}
    }

    class Found<N>(val node: N?, val visited: Int, val depth: Int)

    fun <N> find(root: N, tree: Tree<N>, now: () -> Long = SystemClock::uptimeMillis): Found<N> {
        if (tree.hasSelection(root)) return Found(root, 1, 0)
        val deadline = now() + MAX_MILLIS
        var visited = 1
        var level = listOf(root)
        var depth = 0
        while (level.isNotEmpty() && depth < MAX_DEPTH) {
            depth++
            val next = ArrayList<N>()
            for (n in level) {
                for (c in tree.children(n)) {
                    if (visited++ >= MAX_NODES || now() > deadline) {
                        next.forEach { if (it !== root) tree.release(it) }
                        return Found(null, visited, depth)
                    }
                    if (!tree.visible(c)) { tree.release(c); continue }
                    if (tree.hasSelection(c)) {
                        next.forEach { if (it !== c) tree.release(it) }
                        return Found(c, visited, depth)
                    }
                    next += c
                }
                if (n !== root) tree.release(n)
            }
            level = next
        }
        level.forEach { if (it !== root) tree.release(it) }
        return Found(null, visited, depth)
    }

    /** The accessibility tree as a [Tree]. */
    private val NODES = object : Tree<AccessibilityNodeInfo> {
        override fun children(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = (0 until n.childCount).mapNotNull { runCatching { n.getChild(it) }.getOrNull() }
        override fun hasSelection(n: AccessibilityNodeInfo): Boolean = n.textSelectionStart >= 0 && n.textSelectionStart != n.textSelectionEnd
        override fun visible(n: AccessibilityNodeInfo): Boolean = n.isVisibleToUser
        @Suppress("DEPRECATION") override fun release(n: AccessibilityNodeInfo) { runCatching { n.recycle() } }
    }

    fun find(root: AccessibilityNodeInfo): Found<AccessibilityNodeInfo> = find(root, NODES)
}
