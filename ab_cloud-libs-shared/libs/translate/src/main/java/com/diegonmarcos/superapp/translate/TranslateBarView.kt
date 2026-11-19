package com.diegonmarcos.superapp.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import java.util.Locale

/**
 * Editing actions the host IME routes into a bar's own text box while it is open.
 *
 * The bar cannot host a focusable EditText (see [TranslateInputView]), so the
 * keyboard's existing navigation and clipboard toolbar keys are translated into
 * these by LatinIME instead of being applied to the app's field. Declared here
 * rather than taken from the keyboard's KeyCode so that libs:translate keeps no
 * dependency on libs:keyboard — the mapping lives on the keyboard side.
 */
enum class TranslateEdit {
    LEFT, RIGHT, WORD_LEFT, WORD_RIGHT, LINE_START, LINE_END,
    SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE
}

/**
 * Translate bar, hosted INSIDE the keyboard frame (LatinIME adds it above the
 * suggestion strip; onComputeInsets reserves its height so the host app
 * reflows up):
 *
 *   ┌───────────────────────────────────────────┐
 *   │ [AUTO (EN) ▾]   ⇄   [PT ▾]              ✕  │  language chips (tap = picker, recents first)
 *   │ type here…                                 │  input buffer (keys are routed here by LatinIME)
 *   │ tradução ao vivo                           │  live preview / status line
 *   │ [Insert] [Replace] [Copy] [Clear]          │  actions — Enter = the primary one (setting)
 *   └───────────────────────────────────────────┘
 *
 * Input model: while the bar is open, LatinIME routes printable keys, backspace,
 * the editing keys and the caret-slide GESTURES into [editor] — the shared
 * [TextBoxEditor] the enhance bar's box uses too; nothing touches the app field
 * until the user applies (Insert at cursor / Replace selection-or-field / Copy).
 *
 * Live commit (on by default) additionally streams the translation into the app
 * field as you type. Two buffers then exist — [editor] and the host's field — and
 * [Output] is the rule that keeps them from fighting: the bar's buffer is always
 * the user's, the bar owns exactly ONE span of the host field, it owns that span
 * as a composing region so the platform tracks it, and it gives the span up the
 * moment the host says otherwise. [onHostOutputDropped] carries the argument for
 * why that arrangement cannot loop.
 *
 * EMOJI ACROSS THE ROUND TRIP: THEY PASS THROUGH, AND THAT IS A DECISION.
 * A translation provider may drop, duplicate, reorder or re-render the emoji in a
 * sentence, because emoji are not language and nothing in a translation model is
 * asked to preserve them. So the text coming back differs from the text that went
 * out in ways that have nothing to do with translation quality. Three answers were
 * possible and two lose:
 *
 *   - Treat an emoji-only difference as "the source changed" and re-sync. This is
 *     the one that produces the flaky box: it makes a difference the provider will
 *     reintroduce on the very next request into a reason to make another request.
 *     There is deliberately NO comparison anywhere here between what was sent and
 *     what came back — [onChanged]'s only equality test is source against source,
 *     to drop a reply that a later keystroke has already made stale.
 *   - Extract the emoji before sending and re-insert them after. The known failure
 *     is that the placeholder tokens get translated or mangled themselves, and
 *     there is a second one specific to translation: a target language with a
 *     different word order has no defined position to put an emoji back into. It
 *     would also mean changing what the translation call sends, which is not this
 *     layer's to change.
 *   - Pass them through untouched, and make the box arithmetic correct enough that
 *     an emoji in the SOURCE survives being typed, moved past and deleted. That is
 *     what [TextBoxEditor] is for, and it is what this bar does.
 *
 * Settings + recent pairs: [TranslatePrefs]. Engine: [Translator].
 * Lives in libs:translate; the cloud-keyboard tree (libs/keyboard) hosts it in LatinIME.
 */
class TranslateBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : KitImeComposeView(context, attrs), ImeTextBox {

    /** Supplies the current InputConnection (the IME's getCurrentInputConnection). */
    fun interface IcProvider { fun get(): InputConnection? }

    companion object {
        private val DEFAULT_LANGS = TranslatePrefs.FALLBACK_LANGS
        /** Pinned above the full alphabetical list in the picker, in this order. */
        private val MOST_USED = listOf("en", "es", "de", "pt", "fr", "ko", "ja")
        /** Height of the input box, in lines; it scrolls past this rather than clipping. */
        private const val INPUT_LINES = 3
        private const val DEBOUNCE_MS = 300L
        private const val SLOW_MS = 8000L
        private const val AUTO = Translator.AUTO
    }

    // No colour of its own: the host hands in the KEYBOARD's palette ([setPalette]).
    // The fixed near-black slab that used to sit here is what made the bar a black box
    // on a light keyboard — see [TranslatePalette].
    private var colours by mutableStateOf(TranslatePalette.DARK)
    /** Ceiling the host sets so the bar never outgrows the keys under it; 0 = none yet. */
    private var maxBarHeight by mutableIntStateOf(0)

    private var icp: IcProvider? = null
    private var onClose: Runnable? = null
    private var keyboardLang = "en"
    private var fromTag by mutableStateOf(AUTO)
    private var toTag by mutableStateOf("en")
    private var detectedTag by mutableStateOf<String?>(null)
    private var translated: String? = null

    /**
     * Who owns the translation the bar has put into the host app's field.
     *
     * The bar's own [editor] is never in question — nothing outside this class
     * writes to it, so the SOURCE text is always the user's. What needs an owner is
     * the OUTPUT: the span of the host field holding the last translation, which the
     * bar has to be able to revise on the next keystroke without either deleting
     * words it never wrote or leaving a second copy of its own behind.
     */
    private enum class Output {
        /** Nothing of the bar's is in the field, or what was there is the app's for good. */
        NONE,

        /**
         * The translation is in the field as a COMPOSING region. That is the
         * platform's own primitive for "provisional text I intend to revise": it
         * tracks the span across the host app's edits, [InputConnection.setComposingText]
         * replaces exactly it, and the host leaves it alone instead of running its
         * own autocorrect over half a translation.
         */
        OWNED,

        /**
         * The host dropped the composing region — the user tapped into the app, or
         * the app rewrote its own field. What the bar wrote is the app's text now,
         * and revising it would mean guessing; both guesses are wrong. Deleting eats
         * words the bar never wrote. Appending leaves the duplicate the owner
         * reported. So the bar stops writing and hands back Insert/Replace.
         *
         * Terminal for the session: nothing moves out of LOST except a fresh
         * [onShown]. That is what bounds the re-sync engine — see [onHostOutputDropped].
         */
        LOST,
    }

    // The user's source text, its caret and its selection — the ONE buffer, shared
    // with the enhance bar's box rather than copied into it. Every grapheme question
    // (where a caret may stand, what one backspace removes, where a word ends) is
    // answered in there, so a fix made for this bar is a fix the other bar already has.
    private val editor = TextBoxEditor()
    private var output = Output.NONE     // live-commit mode: who owns the text in the app field
    // Who owns the KEYS — a separate question from who owns the text, and it has to stay
    // separate: Output.LOST is terminal for the session, while the keys can come back to
    // the box the moment the user taps it again. See [KeyHandoff].
    private val keys = KeyFocus(KeyOwner.INPUT)
    private var liveCommit = TranslatePrefs.DEFAULT_LIVE_COMMIT
    private var applyMode by mutableStateOf(TranslatePrefs.DEFAULT_APPLY_MODE)

    // What the bar shows besides the chips.
    private var previewText by mutableStateOf("")
    /** True while [previewText] holds a status line rather than a translation. */
    private var showingStatus by mutableStateOf(true)
    private var insertVisible by mutableStateOf(true)   // Insert/Replace hide while live commit writes the field
    private var inputTick by mutableIntStateOf(0)       // bumped to re-apply [editor] onto [inputView]
    private var picker by mutableStateOf<Picker?>(null)
    private var menu by mutableStateOf<List<MenuItem>?>(null)

    /** One row of the long-press edit menu. */
    private class MenuItem(val label: String, val run: () -> Unit)

    // The input box stays the View it always was — its grapheme-aware tap / double-tap / drag /
    // long-press handling (and the enhance bar, which shares it) is not worth re-deriving — hosted
    // in the Compose bar through AndroidView.
    private val inputView = TranslateInputView(context).apply {
        textSize = 16f; setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
    }
    // Capped and SCROLLABLE, not capped by maxLines: the bar must stay short
    // enough to leave the keys visible, but a maxLines cap makes everything past
    // the last line unreachable — the caret walks off the box and the user is
    // editing text they cannot see. INPUT_LINES is a window, not a limit.
    private val inputScroll = CappedScrollView(context, inputView.lineHeight * INPUT_LINES + dp(12)).apply {
        isFillViewport = true
        addView(inputView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // #1818 the OUTPUT box: editable, framed by the shared panel, with its own editor (the same TextBoxEditor).
    private val outEditor = TextBoxEditor()
    private var outputTick by mutableIntStateOf(0)
    private val outputView = TranslateInputView(context).apply {
        textSize = 16f; setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
    }
    private val outputScroll = CappedScrollView(context, outputView.lineHeight * INPUT_LINES + dp(12)).apply {
        isFillViewport = true
        addView(outputView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    /** The editor the keys act on: the output box while it owns them, else the input box. */
    private val active: TextBoxEditor get() = if (keys.owner == KeyOwner.OUTPUT) outEditor else editor
    private var source by mutableStateOf(InputSource.OWN)
    private var statusIsError by mutableStateOf(false)
    private var busy by mutableStateOf(false)

    /** The shared panel logic (#1818): sources, Replace, Paste, Copy, Auto Replace and the key hand-over. */
    private val controller = WriterPanelController(
        WriterMode.TRANSLATE,
        FieldOpsAppField { icp?.get()?.let { InputConnectionOps(it) } },
        SystemClip(context, "translation"),
        object : BoxText { override fun get() = editor.text; override fun set(text: String) { editor.replaceAll(text); onChanged() } },
        object : BoxText { override fun get() = outEditor.text; override fun set(text: String) { outEditor.replaceAll(text); renderOutput() } },
        keys,
        { input, done ->
            Translator.liveTranslate(context, input, fromTag, toTag, keyboardLang) { r ->
                val det = r.detected
                if (fromTag == AUTO && det != null && det != "und") detectedTag = det
                val out = r.text
                if (out == null) done(Result.failure(IllegalStateException(r.error ?: context.getString(R.string.translate_failed, context.getString(R.string.translate_reason_unknown)))))
                else { translated = out; done(Result.success(out)) }
            }
        },
    )

    private val ui = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var slow: Runnable? = null

    init {
        // Opaque from the first frame, even before the host's palette arrives: a bar that
        // draws nothing is the IME window showing through, which is black.
        setBackgroundColor(colours.background)
        attachInputTouch()
        outputView.attachEditing(outEditor, onClaim = { controller.onOutputBoxTapped(); renderInput(); renderOutput() },
            onChange = ::renderOutput, onMenu = ::showEditMenu)
    }

    /**
     * The keyboard theme's colours, from the host. Called on every open and whenever the
     * input view is rebuilt (a day/night switch rebuilds it), so the bar follows the keys.
     */
    fun setPalette(p: TranslatePalette) {
        if (p == colours) return
        colours = p
        setBackgroundColor(p.background)
    }

    /**
     * The tallest the bar may be — the host passes the height of the keys, so the bar
     * never takes more of the screen than the keyboard itself. The input box is the part
     * that gives: it is a scroller already, so shrinking its window loses nothing.
     */
    fun setMaxHeight(px: Int) {
        if (px == maxBarHeight) return
        maxBarHeight = px
    }

    override fun palette(): KitPalette = KitPalette.fromArgb(
        surface = colours.surface,
        surfaceSelected = colours.chip,
        textPrimary = colours.text,
        textSecondary = colours.muted,
        accent = colours.accent,
        hairline = colours.outline,
        tileInk = colours.onAccent,
    )

    /** Wire the bar to the IME. [keyboardLang] = active subtype language (detection fallback + default target). */
    fun bind(provider: IcProvider, keyboardLang: String, onCloseAction: Runnable) {
        icp = provider
        onClose = onCloseAction
        this.keyboardLang = keyboardLang.ifEmpty { "en" }
    }

    /** Called by LatinIME each time the bar is shown — fresh session, settings re-read. */
    fun onShown() {
        editor.reset(); outEditor.reset(); controller.reset(); source = controller.source; busy = false; output = Output.NONE; translated = null; detectedTag = null
        liveCommit = TranslatePrefs.liveCommit(context)
        applyMode = TranslatePrefs.applyMode(context)
        val langs = toLangs()
        // Order of precedence for the opening pair: an explicitly pinned default
        // target wins, then the last pair actually used (translating twice in a row
        // is the common case), then the keyboard's own language.
        val pinned = TranslatePrefs.defaultTarget(context).takeIf { langs.contains(it) }
        val recent = TranslatePrefs.recentPairs(context).firstOrNull { langs.contains(it.second) }
        toTag = pinned ?: recent?.second ?: keyboardLang.takeIf { langs.contains(it) } ?: "en"
        fromTag = when {
            TranslatePrefs.autoDetect(context) -> AUTO
            pinned == null && recent != null && (recent.first == AUTO || langs.contains(recent.first)) -> recent.first
            else -> keyboardLang.takeIf { langs.contains(it) } ?: AUTO
        }
        if (fromTag == toTag) {
            toTag = TranslatePrefs.recentPairs(context).firstOrNull { it.second != fromTag && langs.contains(it.second) }?.second
                ?: DEFAULT_LANGS.firstOrNull { it != fromTag && langs.contains(it) } ?: toTag
        }
        // Live-commit writes straight into the field — Insert/Replace would double it.
        insertVisible = !liveCommit
        renderInput()
        // Say it on open, not after the first keystroke: with no engine every key
        // would otherwise "Translating…" for 300 ms and then fail, one key at a time.
        val client = TranslateEngines.client
        showStatus(when {
            client == null -> context.getString(R.string.translate_no_engine_registered)
            !client.isConnected() -> Translator.notConnected(context)
            else -> ""
        })
    }

    // ── key routing entry points (called from LatinIME.onEvent) ──────────────
    /**
     * The bar's box owns the keys while the bar is open AND the user has not tapped into
     * the host application's own field. That second clause is #355 part 2: the panel keeps
     * the keys while the user is working in the panel, and gives them up the moment they
     * touch the field underneath. [KeyHandoff] is the whole of that decision.
     */
    override fun consumesKeys() = visibility == View.VISIBLE && keys.consumesKeys

    override fun appendCodePoint(cp: Int) {
        if (cp == '\n'.code) { apply(applyMode); return }   // Enter = primary action
        active.insert(String(Character.toChars(cp)))        // at the caret, replacing any selection
        changedActive()
    }

    override fun backspace() {
        active.deleteBackward()
        changedActive()
    }

    /**
     * The space bar's cursor slide and the backspace swipe, landing in the box instead
     * of in the application behind it. Nothing is re-translated: this moves a caret.
     */
    override fun moveCaret(steps: Int, select: Boolean) {
        active.moveCaret(steps, select)
        renderInput(); renderOutput()
    }

    /**
     * Editing keys the IME hands over while the bar is open (see [TranslateEdit]).
     *
     * ALWAYS consumed, and the return type is kept only so the caller reads as a
     * chain. These keys are routed here solely because the bar is open, so letting
     * one fall through applies it to the app's field BEHIND the bar — invisible to
     * a user who is looking at the bar, and for CUT or SELECT_ALL destructive of
     * text the bar never owned. An empty buffer means the action does nothing; it
     * does not mean somebody else should do it instead.
     */
    override fun onEdit(action: TranslateEdit): Boolean {
        when (action) {
            TranslateEdit.LEFT -> active.stepCaret(-1)
            TranslateEdit.RIGHT -> active.stepCaret(1)
            TranslateEdit.WORD_LEFT -> active.setCaret(active.wordStart(active.selLo()))
            TranslateEdit.WORD_RIGHT -> active.setCaret(active.wordEnd(active.selHi()))
            TranslateEdit.LINE_START -> active.setCaret(0)
            TranslateEdit.LINE_END -> active.setCaret(active.length)
            TranslateEdit.SELECT_ALL -> active.selectAll()
            TranslateEdit.SELECT_WORD -> active.selectWordAt(active.selLo())
            TranslateEdit.COPY -> copySelection()
            TranslateEdit.CUT -> cutSelection()
            TranslateEdit.PASTE -> paste()
        }
        renderInput(); renderOutput()
        return true
    }

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Paste at the caret, replacing the selection — the "full control to paste" affordance. */
    private fun paste() {
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (text.isEmpty()) { toast(context.getString(R.string.translate_bar_clipboard_empty)); return }
        active.insert(text)
        changedActive()
    }

    private fun copySelection() {
        val text = active.selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("translate", text))
        toast(if (active.hasSelection()) "Selection copied" else "Copied")
    }

    private fun cutSelection() {
        val text = active.selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("translate", text))
        // No selection means cut EVERYTHING, which is the destructive one — so it goes
        // through clear() and is undoable, rather than emptying the buffer in place.
        if (!active.deleteSelection()) active.clear()
        changedActive()
    }

    private fun attachInputTouch() =
        // onClaim was empty for as long as the bar never gave the keys up. Now that a tap
        // in the host field releases them, the same touch that the enhance bar has always
        // used to TAKE the keys is the way back — symmetric, and no gesture to learn.
        inputView.attachEditing(editor, onClaim = { controller.onInputBoxTapped(); source = controller.source; renderInput() }, onChange = ::renderInput, onMenu = ::showEditMenu)

    private fun showEditMenu() {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Paste") { paste() })
        if (active.hasSelection()) {
            items.add(MenuItem("Cut") { cutSelection() })
            items.add(MenuItem("Copy selection") { copySelection() })
        }
        if (active.selLo() != 0 || active.selHi() != active.length)
            items.add(MenuItem("Select all") { active.selectAll(); renderInput() })
        // Only offered when there is something to put back, so the menu never promises
        // an undo that does nothing.
        if (active.canUndo()) items.add(MenuItem("Undo") { active.undo(); changedActive() })
        items.add(MenuItem("Clear") { clear() })
        menu = items
    }

    /** An edit by key: the input box re-translates, the output box (the user's touch-up) does not. */
    private fun changedActive() { if (active === editor) { controller.onInputEdited(); source = controller.source; onChanged() } else renderOutput() }

    private fun renderOutput() { outputTick++ }

    private fun onChanged() {
        renderInput()
        pending?.let { ui.removeCallbacks(it) }
        slow?.let { ui.removeCallbacks(it) }
        translated = null
        val text = editor.text
        if (text.isBlank()) { showStatus(""); outEditor.clear(); renderOutput(); if (liveCommit) pushOutput(""); return }
        val job = Runnable {
            showStatus(context.getString(R.string.translate_bar_working))
            val slowJob = Runnable { showStatus(context.getString(R.string.translate_bar_slow)) }
            slow = slowJob; ui.postDelayed(slowJob, SLOW_MS)
            Translator.liveTranslate(context, text, fromTag, toTag, keyboardLang) { r ->
                if (editor.text != text) return@liveTranslate   // stale
                slow?.let { ui.removeCallbacks(it) }
                onResult(r)
            }
        }
        pending = job
        ui.postDelayed(job, DEBOUNCE_MS)
    }

    private fun onResult(r: Translator.Result) {
        val out = r.text
        if (out == null) { translated = null; showStatus(r.error ?: context.getString(R.string.translate_failed, context.getString(R.string.translate_reason_unknown))); return }
        translated = out
        val det = r.detected
        if (fromTag == AUTO && det != null && det != "und") detectedTag = det
        outEditor.replaceAll(out); renderOutput()
        showStatus("")
        if (liveCommit) pushOutput(out)
    }

    // ── actions ──────────────────────────────────────────────────────────────
    private fun apply(mode: String) {
        val out = outEditor.text.ifBlank { null }
        if (out == null) { if (editor.isNotEmpty) toast(context.getString(R.string.translate_bar_wait)); return }
        // The field is gone (the host closed it, or focus moved) and the bar cannot
        // write anywhere. Saying so is the whole difference between a chip that is
        // busy and a chip that is broken: the translation is still here, above.
        val ic = icp?.get() ?: run { toast(context.getString(R.string.translate_bar_no_field)); return }
        if (output == Output.OWNED) {
            // Already in the field as our composing region — finishing it is the
            // whole apply. Committing again is what produced the second copy.
            releaseOutput(ic, keep = true)
        } else {
            // The shared panel's Replace / Paste: announces its own write to the key focus (see KeyFocus).
            val done = if (mode == TranslatePrefs.APPLY_REPLACE) controller.replace() else controller.paste()
            if (done != Outcome.OK) { toast(context.getString(R.string.translate_bar_no_field)); return }
        }
        afterApplied()
    }

    /** What an apply leaves behind: the pair is remembered, the source and the output are spent (Undo in the menu brings the source back). */
    private fun afterApplied() {
        TranslatePrefs.pushRecentPair(context, if (fromTag == AUTO) (detectedTag ?: AUTO) else fromTag, toTag)
        pending?.let { ui.removeCallbacks(it) }
        // Undoable: the source the user typed is gone from the box the moment they
        // apply, and Undo in the long-press menu is how they get it back.
        editor.clear(); outEditor.clear(); translated = null
        renderInput(); renderOutput(); showStatus("")
    }

    /** The shared panel's Generate: translate the input box now (no debounce), into the output box. */
    private fun generateNow() {
        pending?.let { ui.removeCallbacks(it) }
        busy = true
        showStatus(context.getString(R.string.translate_bar_working))
        controller.generate { o -> busy = false; report(o) }
    }

    /** Auto Replace: translate, then replace the app box with the result, in one tap. */
    private fun autoReplace() {
        pending?.let { ui.removeCallbacks(it) }
        // a live-commit composing region would be selected over and replaced: take it out first
        icp?.get()?.let { releaseOutput(it, keep = false) }
        busy = true
        showStatus(context.getString(R.string.translate_bar_working))
        controller.autoReplace { o -> busy = false; if (o == Outcome.OK) afterApplied() else report(o) }
    }

    private fun report(o: Outcome) {
        statusIsError = o != Outcome.OK
        when (o) {
            Outcome.OK -> showStatus("")
            Outcome.EMPTY_INPUT -> showStatus(context.getString(R.string.translate_bar_type_first))
            Outcome.EMPTY_OUTPUT -> showStatus(context.getString(R.string.translate_bar_wait))
            Outcome.NO_FIELD -> showStatus(context.getString(R.string.translate_bar_no_field))
            Outcome.NOT_APPLICABLE -> showStatus("")
            Outcome.FAILED -> showStatus(controller.lastError?.message ?: context.getString(R.string.translate_failed, context.getString(R.string.translate_reason_unknown)))
        }
    }

    private fun copy() {
        val o = controller.copy()
        if (o == Outcome.OK) toast(context.getString(R.string.translate_bar_copied))
        else toast(if (editor.isNotEmpty) context.getString(R.string.translate_bar_wait) else context.getString(R.string.translate_bar_type_first))
    }

    /** The source buttons: Type / Clipboard / App box. */
    private fun takeSource(s: InputSource) {
        val o = controller.selectSource(s)
        source = controller.source
        renderInput()
        if (o == Outcome.EMPTY_INPUT) toast(context.getString(if (s == InputSource.CLIPBOARD) R.string.translate_bar_clipboard_empty else R.string.translate_bar_type_first))
        else if (o == Outcome.NO_FIELD) toast(context.getString(R.string.translate_bar_no_field))
    }

    private fun clear() { editor.clear(); outEditor.clear(); renderOutput(); onChanged() }

    private fun swap() {
        val f = if (fromTag == AUTO) (detectedTag
            ?: run { toast(context.getString(R.string.translate_bar_no_detection)); return })
            else fromTag
        fromTag = toTag; toTag = f; detectedTag = null
        onChanged()
    }

    /**
     * Live-commit mode: put [out] in the field as the bar's composing region,
     * replacing whatever the bar put there before.
     *
     * One InputConnection call, because the composing region IS the platform's
     * re-sync primitive — it survives the host reflowing its own text, and
     * setComposingText replaces it atomically with no character count to get wrong.
     *
     * The version this replaces retracted by length and then committed, and when it
     * could not CONFIRM the retract it committed anyway: the old translation stayed
     * and a new one landed after it. That is the text appearing twice, and then
     * three times, that the owner reported.
     */
    private fun pushOutput(out: String) {
        if (output == Output.LOST) return      // not the bar's to revise any more
        val ic = icp?.get() ?: return
        if (out.isEmpty()) { releaseOutput(ic, keep = false); return }
        output = Output.OWNED
        ic.setComposingText(out, 1)
    }

    /**
     * End the composing region. [keep] true leaves the text in the field as the app's
     * own — the user applied it, or the bar is closing on top of it; false takes it
     * back out, which is what an emptied buffer means.
     */
    private fun releaseOutput(ic: InputConnection, keep: Boolean) {
        if (output != Output.OWNED) { output = Output.NONE; return }
        // NONE *before* the calls, and this order is load-bearing. finishComposingText
        // makes the host report a composing span of -1, which is the same signal a host
        // takeover sends; releasing while still OWNED would have the bar read its own
        // hand-over as the app stealing the text and go LOST for no reason.
        output = Output.NONE
        // finishComposingText reports a composing span of -1, which is character for
        // character what a host tap reports. Announced so the echo is spent against this
        // write instead of reading as the user tapping into the app — otherwise emptying
        // the box (backspace to blank -> pushOutput("") -> here) would hand the keys back
        // mid-edit, which is the one failure that makes the feature unusable.
        keys.onSelfWrite()
        ic.beginBatchEdit()
        if (!keep) ic.setComposingText("", 1)
        ic.finishComposingText()
        ic.endBatchEdit()
    }

    /**
     * The host field reported that the bar's composing region is gone — LatinIME
     * passes a composing span of -1 for exactly this. The user tapped into the app,
     * or the app rewrote its own text; either way what the bar wrote is the app's now.
     *
     * WHY THE UPDATE LOOP TERMINATES. This handler is the only thing in the bar that
     * reacts to the host field, and it neither writes to the field nor touches
     * [editor] — so it cannot cause the update it is reacting to, which is the loop a
     * naive listener falls into. The bar's own writes report a span of >= 0 and never
     * reach the body at all; its own [releaseOutput] does report -1, but sets NONE
     * first, so the guard below returns. That leaves a host-originated drop as the
     * only way in, and OWNED -> LOST as the only transition: one-way, and terminal
     * until the next [onShown]. The handler therefore does work at most ONCE per
     * session, and after it nothing writes to the field at all.
     */
    fun onHostOutputDropped() {
        // Is this the host, or the bar's own write coming back? Asked FIRST and asked in
        // one place, because both answers below depend on it. The old code asked it as a
        // side effect of `output != Output.OWNED`, which is only a valid proxy inside
        // live-commit mode — see [KeyHandoff] for why that could not simply be reused.
        //
        // #355 part 2 rides on the true branch: the user tapped into the application's own
        // field, so onSelectionChange has ALREADY handed the keys back. It does that
        // whatever `output` says — with live commit off the bar owns nothing in the field
        // and the tap still has to release — and tapping the bar's box takes them again
        // (onClaim, below). Everything past this point is the older, narrower concern of
        // who owns the TEXT, and is unchanged.
        val wasHeld = keys.consumesKeys
        if (!keys.onHostSelectionChange()) return
        // Only on the TRANSITION. Once the keys are with the app, every word the user
        // commits there lands here again (a committed word is a composing span of -1), and
        // re-laying out the box on each of them would be a redraw per keystroke for a
        // caret that is already gone.
        if (wasHeld) renderInput()
        if (output != Output.OWNED) return

        output = Output.LOST
        // There still has to be a way to get the translation out, and live commit
        // is no longer one of them.
        insertVisible = true
        showStatus(context.getString(R.string.translate_bar_taken_over))
    }

    /**
     * The bar is closing. A composing region left behind would leave the host app's
     * text provisional and underlined for a field nothing is watching any more, so it
     * is handed over as the app's own on the way out.
     */
    fun onHidden() {
        pending?.let { ui.removeCallbacks(it) }
        slow?.let { ui.removeCallbacks(it) }
        icp?.get()?.let { releaseOutput(it, keep = true) }
    }

    // ── rendering ────────────────────────────────────────────────────────────
    /** Re-apply [editor]'s state onto the hosted input view (see [applyInput]). */
    private fun renderInput() { inputTick++ }

    private fun applyInput() {
        inputTick // read: a bump re-runs the AndroidView update that calls this
        val p = colours
        inputView.caretColor = p.caret
        if (editor.isEmpty) {
            inputView.text = context.getString(R.string.translate_bar_hint)
            inputView.setTextColor(p.hint)
            inputView.setTypeface(null, Typeface.ITALIC)
            inputView.caret = -1
            return
        }
        inputView.setTextColor(p.text)
        inputView.setTypeface(null, Typeface.NORMAL)
        // A copy, not the live StringBuilder: the TextView would otherwise render a
        // buffer that keeps mutating underneath its layout.
        if (editor.hasSelection()) {
            inputView.text = SpannableString(editor.text).apply {
                setSpan(BackgroundColorSpan(p.selection), editor.selLo(), editor.selHi(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            inputView.caret = -1          // a range and an insertion point are mutually exclusive
        } else {
            inputView.text = editor.text
            // No caret while the host field holds the keys. A blinking caret in a box that
            // the next keystroke will not reach is the box lying about where typing goes —
            // the enhance bar's output box has drawn it conditionally for the same reason.
            inputView.caret = if (keys.owner == KeyOwner.INPUT) editor.selStart else -1
        }
        // Posted: the layout still describes the text set BEFORE this call, and
        // scrolling against a stale layout lands on the wrong line.
        inputView.post { inputView.revealCaret() }
    }

    /** Re-apply [outEditor] onto the hosted output view. */
    private fun applyOutput() {
        outputTick // read: a bump re-runs the AndroidView update that calls this
        val p = colours
        outputView.caretColor = p.caret
        if (outEditor.isEmpty) {
            outputView.text = context.getString(R.string.writer_panel_output)
            outputView.setTextColor(p.hint)
            outputView.setTypeface(null, Typeface.ITALIC)
            outputView.caret = -1
            return
        }
        outputView.setTextColor(p.text)
        outputView.setTypeface(null, Typeface.NORMAL)
        if (outEditor.hasSelection()) {
            outputView.text = SpannableString(outEditor.text).apply {
                setSpan(BackgroundColorSpan(p.selection), outEditor.selLo(), outEditor.selHi(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            outputView.caret = -1
        } else {
            outputView.text = outEditor.text
            outputView.caret = if (keys.owner == KeyOwner.OUTPUT) outEditor.selStart else -1
        }
        outputView.post { outputView.revealCaret() }
    }

    private fun showStatus(msg: String) {
        showingStatus = true
        previewText = msg
        if (msg.isEmpty()) statusIsError = false
    }

    // ── language picker ──────────────────────────────────────────────────────
    // Every language the engine can translate — computed fresh on each read: the
    // AIDL client binds asynchronously, so a cached snapshot taken before the bind
    // would freeze the list at the fallback forever.
    private fun toLangs(): List<String> =
        (TranslateEngines.client?.supportedLanguages()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LANGS)
            .sortedBy { Locale(it).displayLanguage }

    /** One picker row. Typed rather than positional so headers can never be off-by-one. */
    private sealed class LangRow {
        class Header(val label: String) : LangRow()
        /** A remembered from→to pair; picking it sets both sides at once. */
        class RecentPair(val from: String, val to: String) : LangRow()
        class Language(val code: String) : LangRow()
    }

    private class Picker(val isFrom: Boolean, val rows: List<LangRow>)

    /**
     * Tap a chip → picker, in three sections: Recent pairs (both sides at once),
     * Most used, then every language. Headers are real rows but not clickable, and the
     * click handler reads the row it was given instead of doing index arithmetic against
     * the section sizes — the arithmetic is what broke as soon as headers existed.
     */
    private fun showLangMenu(isFrom: Boolean) {
        val langs = toLangs()
        val recents = TranslatePrefs.recentPairs(context)
        val rows = ArrayList<LangRow>()
        if (recents.isNotEmpty()) {
            rows.add(LangRow.Header("Recent"))
            recents.forEach { rows.add(LangRow.RecentPair(it.first, it.second)) }
        }
        rows.add(LangRow.Header("Most used"))
        if (isFrom) rows.add(LangRow.Language(AUTO))
        MOST_USED.filter { langs.contains(it) }.forEach { rows.add(LangRow.Language(it)) }
        rows.add(LangRow.Header("All languages"))
        langs.forEach { rows.add(LangRow.Language(it)) }
        picker = Picker(isFrom, rows)
    }

    private fun pick(p: Picker, row: LangRow) {
        when (row) {
            is LangRow.Header -> return
            is LangRow.RecentPair -> { fromTag = row.from; toTag = row.to }
            is LangRow.Language -> if (p.isFrom) fromTag = row.code else toTag = row.code
        }
        detectedTag = null
        picker = null
        onChanged()
    }

    private fun langName(code: String): String =
        Locale(code).displayLanguage.let { n -> if (n.equals(code, ignoreCase = true)) code.uppercase() else "$n  ($code)" }

    // ── the bar ──────────────────────────────────────────────────────────────
    @Composable
    override fun Body() {
        val p = colours
        val cap: Dp = with(LocalDensity.current) { if (maxBarHeight > 0) maxBarHeight.toDp() else Dp.Unspecified }
        Box(Modifier.fillMaxWidth().then(if (cap != Dp.Unspecified) Modifier.heightIn(max = cap) else Modifier)
            .background(Color(p.background))) {
            Main(p)
            picker?.let { PickerPanel(p, it) }
            menu?.let { MenuPanel(p, it) }
        }
    }

    @Composable
    private fun Main(p: TranslatePalette) {
        val ctx = LocalContext.current
        val selectors = listOf(
            ConfigSelector("from", (if (fromTag == AUTO) "AUTO" + (detectedTag?.let { " (${it.uppercase()})" } ?: "") + " ▾"
                else fromTag.uppercase() + " ▾")) { showLangMenu(true) },
            ConfigSelector("swap", "⇄") { swap() },
            ConfigSelector("to", toTag.uppercase() + " ▾") { showLangMenu(false) },
        )
        // What the bar had before the shared panel and still has: Clear, the long-press edit menu, the status line.
        val extras = listOf(PanelButton("clear", ctx.getString(R.string.translate_bar_clear)) { clear() })
        WriterPanel(
            WriterPanelSpec(
                mode = WriterMode.TRANSLATE, title = ctx.getString(R.string.writer_mode_translate),
                colors = WriterPanelColors(Color(p.surface), Color(p.chip), Color(p.onChip), Color(p.accent), Color(p.onAccent),
                    Color(p.text), Color(p.muted), Color(p.outline)),
                selectors = selectors, source = source, onSource = ::takeSource,
                onAction = { a ->
                    when (a) {
                        WriterAction.GENERATE -> generateNow()
                        WriterAction.AUTO_REPLACE -> autoReplace()
                        WriterAction.REPLACE -> apply(TranslatePrefs.APPLY_REPLACE)
                        WriterAction.PASTE -> apply(TranslatePrefs.APPLY_INSERT)
                        WriterAction.COPY -> copy()
                    }
                },
                extras = extras, status = previewText, statusIsError = statusIsError, busy = busy,
                onClose = { onClose?.run() },
            ),
            input = { AndroidView(factory = { inputScroll }, update = { applyInput() }, modifier = Modifier.fillMaxWidth()) },
            output = { AndroidView(factory = { outputScroll }, update = { applyOutput() }, modifier = Modifier.fillMaxWidth()) },
        )
    }

    /** The language picker, over the bar: a list with real section headers that cannot be picked. */
    @Composable
    private fun PickerPanel(p: TranslatePalette, state: Picker) {
        Column(Modifier.fillMaxWidth().background(Color(p.surface)).padding(vertical = 4.dp)) {
            Text("✕", color = Color(p.text), fontSize = 18.sp, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().clickable { picker = null }.padding(horizontal = 16.dp, vertical = 6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(state.rows) { row ->
                    when (row) {
                        is LangRow.Header -> Text(row.label.uppercase(), color = Color(p.muted), fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        else -> Text(
                            when (row) {
                                is LangRow.RecentPair -> "${langName(row.from)} → ${langName(row.to)}"
                                is LangRow.Language -> if (row.code == AUTO) "Auto-detect" else langName(row.code)
                                else -> ""
                            },
                            color = Color(p.text), fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable { pick(state, row) }
                                .padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }

    /** The long-press edit menu, over the bar. */
    @Composable
    private fun MenuPanel(p: TranslatePalette, items: List<MenuItem>) {
        Column(Modifier.fillMaxWidth().background(Color(p.surface)).padding(vertical = 4.dp)) {
            for (item in items) {
                Text(item.label, color = Color(p.text), fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { menu = null; item.run() }
                        .padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Text("✕", color = Color(p.muted), fontSize = 18.sp, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().clickable { menu = null }.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }

    @Composable
    private fun Chip(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
        Text(label, color = fg, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(bg).clickable { onClick() }
                .sizeIn(minHeight = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp))
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
