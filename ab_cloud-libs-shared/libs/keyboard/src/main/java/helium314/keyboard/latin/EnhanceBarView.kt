// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import com.diegonmarcos.superapp.translate.CappedScrollView
import com.diegonmarcos.superapp.translate.ImeTextBox
import com.diegonmarcos.superapp.translate.TextBoxEditor
import com.diegonmarcos.superapp.translate.TranslateEdit
import com.diegonmarcos.superapp.translate.TranslateInputView
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.toKitPalette
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SuperApp addition — the Text Enhancements bar, hosted inside the keyboard frame
 * exactly like [LatinIME.toggleTranslateBar]'s translate bar (added above the
 * suggestion strip, its height subtracted in onComputeInsets):
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │ [Clarity ▾] [Formal ▾] [One paragraph ▾] [English ▾] ✕│  the Text Enhancements options
 *   │ what will be rewritten (dim)   312 words · ~420 tokens│  source — read-only, sized
 *   │ the rewrite, editable, scrolls                        │  output — keys are routed here
 *   │ status                                                │
 *   │ [Generate] [Resume] [Copy] [Paste] [Replace] [Undo]…  │
 *   └──────────────────────────────────────────────────────┘
 *
 * Tapping the ENHANCE toolbar key opens this; long-pressing it keeps the old
 * one-shot behaviour (rewrite in place, no bar). Nothing here touches the app's
 * field until Paste or Replace: Generate only fills the output box, so a rewrite
 * can be read and corrected before it lands.
 *
 * TWO QUESTIONS OVER ONE BAR. Generate asks for a rewrite of the source; Resume
 * ("Text Resume" — the owner's name for SUMMARISE) asks for a summary of it.
 * They share this bar because a Resume needs exactly what this bar already does:
 * resolve the same source the ENHANCE key would take, then show an answer the
 * user can read, edit and Copy before anything is applied. They differ in one
 * thing that matters, and [Held] is where that difference is enforced — a
 * rewrite may be written back over the source, a summary may never be.
 *
 * The option chips write the very same preferences as Settings → Text
 * Enhancements, so the bar and that screen can never disagree, and the prompt is
 * built by the one [AiRouter.enhanceStyle] both paths use.
 *
 * The output box is not a private editor any more: its caret, its selection, its
 * grapheme arithmetic and its undo are [TextBoxEditor], the same object the translate
 * bar's box uses. That is deliberate and it is the point — the trimmed second copy that
 * used to live here is how this box came to be missing fixes the other one had, and how
 * it would have missed the next ones. Everything routed at a box now arrives through
 * [ImeTextBox], which LatinIME resolves in one place.
 */
class EnhanceBarView(context: Context) : KitImeComposeView(context), ImeTextBox {

    /** Supplies the IME's current rich connection; null while no field is attached. */
    fun interface ConnectionProvider { fun get(): RichInputConnection? }

    companion object {
        private const val TAG = "EnhanceBar"
        /**
         * How long the field is left alone after a selection change before it is re-read. Every
         * keystroke reports a selection change, and one read is an IPC round trip plus a
         * re-layout of the source line, so reading per keystroke made typing with the bar open
         * stutter; a burst of typing now costs one read after the last key.
         */
        private const val FIELD_SETTLE_MS = 150L
        /** Lines of rewrite visible at once; more scrolls rather than growing into the keyboard. */
        private const val OUTPUT_LINES = 5
    }

    private var provider: ConnectionProvider? = null
    private var onClose: Runnable? = null

    /** The field text this session rewrites, and where it sits; re-read after every apply. */
    private var target: TextEnhancer.Target? = null
    /** The last Paste/Replace: what the field held, and what it holds now, so Undo can put it back. */
    private var lastApplied: Pair<TextEnhancer.Target, String>? = null

    // The rewrite in the box, its caret and its selection — the SHARED editor, so this
    // box gets the translate box's grapheme-correct caret, its fixed word selection and
    // its undo without a line of it being written twice.
    private val editor = TextBoxEditor()
    private var busy = false
    private var applyWhenReady = false

    /**
     * WHAT THE OUTPUT BOX HOLDS, AND THEREFORE WHETHER IT MAY BE WRITTEN BACK.
     *
     * A [REWRITE] is a better version of the source, so replacing the source with it is the whole
     * point of Enhance. A [SUMMARY] is a different, SHORTER text ABOUT the source: pasting it over
     * the paragraph it describes deletes that paragraph and leaves a précis where the writing was,
     * and no Undo the user did not think to press gets it back. So Resume never applies, and it is
     * this field the apply path asks — one branch that cannot be forgotten, rather than a rule
     * about which buttons to be careful with.
     */
    private enum class Held { REWRITE, SUMMARY }
    private var held = Held.REWRITE

    /**
     * Where the keys go. False — the default, and the state the bar opens in — means
     * the keyboard still types into the app's own field, which is the whole point:
     * you write and edit there, the bar only reads from it. True means the user
     * tapped the output box to touch up a rewrite, so the keys are routed here until
     * they tap the source line (or Generate) to hand them back.
     */
    private var editingOutput = false

    // What the bar shows besides the chips (Compose state; the output box is the TranslateInputView
    // the translate bar uses too, hosted through AndroidView).
    private var sourceText by mutableStateOf("")
    private var sourceBlank by mutableStateOf(true)
    private var sizeText by mutableStateOf("")
    private var statusText by mutableStateOf("")
    private var undoVisible by mutableStateOf(false)
    private var optTick by mutableIntStateOf(0)      // bumped when an option preference changes
    private var outputTick by mutableIntStateOf(0)   // bumped to re-apply [editor] onto [outputView]
    private var menu by mutableStateOf<List<MenuItem>?>(null)
    private var optionPick by mutableStateOf<Option?>(null)

    /** One row of the output box's long-press menu. */
    private class MenuItem(val label: String, val run: () -> Unit)

    private val outputView = TranslateInputView(context).apply {
        textSize = 16f; setPadding(0, dp(6), 0, 0)
        isClickable = true
    }
    // Inside a height-capped scroller: a rewrite of several paragraphs used to
    // show its first four lines and no more, which read as "the rest was
    // dropped". A vertical drag now scrolls; a horizontal drag still selects.
    private val outputScroll = CappedScrollView(context, outputView.lineHeight * OUTPUT_LINES + dp(6)).apply {
        isVerticalScrollBarEnabled = true
        addView(outputView)
    }
    private val options: List<Option> = listOf(
        Option(Settings.PREF_ENHANCE_STYLE, AiRouter.styles, AiRouter.defaultStyle),
        Option(Settings.PREF_ENHANCE_TONE, AiRouter.tones, AiRouter.defaultTone),
        Option(Settings.PREF_ENHANCE_LENGTH, AiRouter.lengths, AiRouter.defaultLength),
        Option(Settings.PREF_ENHANCE_LANGUAGE, AiRouter.languages, AiRouter.defaultLanguage),
    ).filter { it.entries.isNotEmpty() }

    // Cached, not single-threaded: a Generate stuck in a provider's read timeout must not
    // hold the next Generate in a queue behind it for the whole timeout.
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()
    private val reload = Runnable { reloadTarget() }

    /** One option chip, backed by a registry list from build.json::keyboard_ai and its preference. */
    private inner class Option(val key: String, val entries: List<AiRouter.Style>, val fallback: String) {
        fun current(): AiRouter.Style? {
            val id = context.prefs().getString(key, fallback) ?: fallback
            return entries.firstOrNull { it.id == id } ?: entries.firstOrNull { it.id == fallback }
        }
        fun set(id: String) {
            context.prefs().edit { putString(key, id) }
            optTick++
        }
        fun label() = current()?.label ?: "—"
    }

    init {
        attachOutputTouch()
        renderOutput()
    }

    // Same palette as the translate bar — the two panels are one feature to the eye: the keyboard theme's.
    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    // ── the bar ──────────────────────────────────────────────────────────────
    @Composable
    override fun Body() {
        val p = LocalKitPalette.current
        val hint = p.textSecondary.copy(alpha = 0.7f)
        Box(Modifier.fillMaxWidth().background(p.surface)) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 8.dp)) {
                // ── Row 1: the Text Enhancements options .................. ✕ ─────────
                // Four chips do not fit on a phone in portrait, so they scroll rather than shrink.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        optTick // read: a chosen entry re-labels its chip
                        options.forEach { option -> Chip(option.label(), p.surfaceSelected, p.textPrimary) { optionPick = option } }
                    }
                    Text("✕", color = p.textSecondary, fontSize = 16.sp,
                        modifier = Modifier.clickable { onClose?.run() }.padding(start = 10.dp, top = 2.dp, end = 4.dp, bottom = 2.dp))
                }

                // ── Row 2: what will be rewritten, and how much of it there is ───────
                // Tapping it is also how the keys go back to the app's field after a
                // detour into the output box — "type over there" is what the row means.
                // Two lines with a visible "…": the whole field is read whatever this
                // shows, and the size beside it is what says so.
                Row(verticalAlignment = Alignment.Top) {
                    Text(sourceText, color = p.textSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontStyle = if (sourceBlank) FontStyle.Italic else FontStyle.Normal,
                        modifier = Modifier.weight(1f).clickable { focusField() }.padding(top = 6.dp))
                    Text(sizeText, color = hint, fontSize = 12.sp, textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp))
                }

                // ── Row 3: the rewrite, editable ─────────────────────────────────────
                AndroidView(factory = { outputScroll }, update = { applyOutputView() }, modifier = Modifier.fillMaxWidth())

                // ── Row 4: status ────────────────────────────────────────────────────
                if (statusText.isNotEmpty())
                    Text(statusText, color = hint, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))

                // ── Row 5: actions ───────────────────────────────────────────────────
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Chip(str(R.string.enhance_bar_generate), p.accent, p.tileInk) { generate(false) }
                    // "Text Resume" — the owner's name for SUMMARISE, not a curriculum vitae and not resuming
                    // anything paused. It sits here rather than on a toolbar key of its own because this bar
                    // already is the surface a Resume needs: it reads the same source the ENHANCE key would,
                    // and it shows a result the user can read, edit and Copy before anything is applied. A
                    // second toolbar entry would have bought nothing and spent it in the one row of this
                    // keyboard that has gone blank, scrolled wrong and crashed in three separate defects.
                    Chip(str(R.string.resume_bar_resume), p.surfaceSelected, p.textPrimary) { resume() }
                    Chip(str(R.string.enhance_bar_copy), p.surfaceSelected, p.textPrimary) { copyOutput() }
                    Chip(str(R.string.enhance_bar_paste), p.surfaceSelected, p.textPrimary) { applyOutput() }
                    Chip(str(R.string.enhance_bar_replace), p.surfaceSelected, p.textPrimary) { generate(true) }
                    if (undoVisible) Chip(str(R.string.enhance_bar_undo), p.surfaceSelected, p.textPrimary) { undo() }
                    Chip(str(R.string.enhance_bar_clear), p.surfaceSelected, p.textPrimary) { clear() }
                }
            }
            optionPick?.let { OptionPanel(p, it) }
            menu?.let { MenuPanel(p, it) }
        }
    }

    @Composable
    private fun Chip(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
        Text(label, color = fg, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp))
    }

    /** Tap a chip -> the entries of that registry list, over the bar. */
    @Composable
    private fun OptionPanel(p: KitPalette, option: Option) {
        Column(Modifier.fillMaxWidth().background(p.surface).padding(vertical = 4.dp)) {
            Text("✕", color = p.textSecondary, fontSize = 18.sp, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().clickable { optionPick = null }.padding(horizontal = 16.dp, vertical = 6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(option.entries) { entry ->
                    Text(entry.label, color = p.textPrimary, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { optionPick = null; option.set(entry.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
    }

    /** The output box's long-press menu, over the bar. */
    @Composable
    private fun MenuPanel(p: KitPalette, items: List<MenuItem>) {
        Column(Modifier.fillMaxWidth().background(p.surface).padding(vertical = 4.dp)) {
            for (item in items) {
                Text(item.label, color = p.textPrimary, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { menu = null; item.run() }
                        .padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Text("✕", color = p.textSecondary, fontSize = 18.sp, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().clickable { menu = null }.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }

    /** Wire the bar to the IME. */
    fun bind(connectionProvider: ConnectionProvider, onCloseAction: Runnable) {
        provider = connectionProvider
        onClose = onCloseAction
    }

    /** Called by LatinIME every time the bar is shown — fresh session, preferences re-read. */
    fun onShown() {
        seq.incrementAndGet()
        ui.removeCallbacks(reload)
        busy = false; applyWhenReady = false
        editingOutput = false
        held = Held.REWRITE   // a fresh session holds nothing, and nothing is not a summary
        editor.reset()
        optTick++
        reloadTarget()
        renderOutput()
        showStatus("")
    }

    /**
     * The source line is what the app's field holds; re-read it once the user pauses.
     * Coalesced: LatinIME calls this on every selection update, which is every keystroke.
     */
    fun onFieldChanged() {
        if (visibility != VISIBLE || busy) return
        ui.removeCallbacks(reload)
        ui.postDelayed(reload, FIELD_SETTLE_MS)
    }

    /**
     * True while the output box owns the keys. LatinIME asks before routing anything
     * here, so with the box unfocused every key lands in the app's field as usual.
     */
    override fun consumesKeys() = visibility == VISIBLE && editingOutput

    /** Hand the keys back to the app's field and re-read what it now holds. */
    private fun focusField() {
        if (!editingOutput) return
        editingOutput = false
        editor.setCaret(editor.selLo())
        reloadTarget()
        renderOutput()
    }

    // ── key routing entry points (called from LatinIME.onEvent) ──────────────
    override fun appendCodePoint(cp: Int) {
        // Enter is the action key on a keyboard bar, not a newline: it does what the
        // user came for — apply what is in the box (generating it first if need be).
        if (cp == '\n'.code) { if (editor.isEmpty) generate(true) else applyOutput(); return }
        editor.insert(String(Character.toChars(cp)))
        renderOutput()
    }

    override fun backspace() {
        editor.deleteBackward()
        renderOutput()
    }

    /**
     * The space bar's cursor slide and the backspace swipe. They used to move the caret
     * in the application's own field while the user was dragging over a rewrite in here,
     * because they called the host's connection straight from the gesture handler
     * instead of asking who owns editing.
     */
    override fun moveCaret(steps: Int, select: Boolean) {
        editor.moveCaret(steps, select)
        renderOutput()
    }

    /**
     * Navigation and clipboard toolbar keys, applied to the output box instead of the
     * app's field while the bar is open. Returns false only when the key is not one of
     * them, so LatinIME can fall back to its normal handling.
     *
     * An EMPTY box is NOT such a case, though it used to be: LatinIME only asks while
     * the box owns the keys, so falling through then applied the key to the app's field
     * behind the bar — invisible to a user looking at the box, and for CUT or SELECT_ALL
     * destructive of text this bar never owned. Empty means the action does nothing.
     */
    override fun onEdit(action: TranslateEdit): Boolean {
        when (action) {
            TranslateEdit.LEFT -> editor.stepCaret(-1)
            TranslateEdit.RIGHT -> editor.stepCaret(1)
            TranslateEdit.WORD_LEFT -> editor.setCaret(editor.wordStart(editor.selLo()))
            TranslateEdit.WORD_RIGHT -> editor.setCaret(editor.wordEnd(editor.selHi()))
            // The box is one short buffer, so up/down are its ends.
            TranslateEdit.LINE_START -> editor.setCaret(0)
            TranslateEdit.LINE_END -> editor.setCaret(editor.length)
            TranslateEdit.SELECT_ALL -> editor.selectAll()
            TranslateEdit.SELECT_WORD -> editor.selectWordAt(editor.selLo())
            TranslateEdit.COPY -> copySelection()
            TranslateEdit.CUT -> cutSelection()
            TranslateEdit.PASTE -> pasteIntoBox()
        }
        renderOutput()
        return true
    }

    // ── the four actions ─────────────────────────────────────────────────────

    /**
     * Ask the model for a rewrite of the source; [thenApply] = the Replace button. Tapping
     * while a run is in flight starts over rather than being ignored: the old run's reply is
     * dropped by the sequence check, so a provider that hangs never locks the button until
     * its timeout.
     */
    private fun generate(thenApply: Boolean) {
        // Read the field NOW. The snapshot taken when the bar opened is stale by
        // definition — the user types after opening it, which is the normal way to
        // use this, and enhancing what the field held before that is never right.
        editingOutput = false
        ui.removeCallbacks(reload)
        reloadTarget()
        val t = target
        if (t == null || t.text.isBlank()) { showStatus(str(R.string.enhance_bar_no_source)); return }
        busy = true
        applyWhenReady = thenApply
        val id = seq.incrementAndGet()
        val style = AiRouter.enhanceStyle(context)
        val route = AiTaskRouting.route(context, AiTask.ENHANCE) // Configs ▸ AI Routing ▸ Text Enhancement model
        val providerLabel = route.provider.label
        showStatus(context.getString(R.string.enhance_in_progress, providerLabel))
        io.execute {
            val result = runCatching {
                // progress by name: rewrite() takes route after it, so a trailing lambda binds to route.
                TextEnhancer.rewrite(context, style, t.text, route = route, progress = { done, total ->
                    if (total > 1) ui.post {
                        if (id == seq.get()) showStatus(context.getString(R.string.enhance_in_progress_part, providerLabel, done + 1, total))
                    }
                })
            }
            ui.post {
                if (id != seq.get()) return@post   // a newer run, or the bar was reopened
                busy = false
                result.onSuccess { out ->
                    held = Held.REWRITE   // a rewrite may be written back; see [Held]
                    // replaceAll, not a raw overwrite: whatever the user had typed or
                    // touched up in the box is what Undo in the box menu gives back.
                    editor.replaceAll(out)
                    renderOutput(); showStatus("")
                    // The Replace chip arms this for one run; auto-paste (#355) is the standing
                    // answer to the same question, so a plain Generate reaches the field too.
                    if (applyWhenReady || autoPaste()) applyOutput()
                }.onFailure { e ->
                    Log.e(TAG, "generate failed", e)
                    showStatus(when (e) {
                        is AiRouter.NoTokenException -> context.getString(R.string.ai_no_token, e.provider.label)
                        else -> context.getString(R.string.enhance_failed, e.message ?: e.javaClass.simpleName)
                    })
                }
                applyWhenReady = false
            }
        }
    }

    /**
     * "Text Resume" — SUMMARISE what the ENHANCE key would have rewritten. The owner's product
     * name, kept exactly as they spell it: it means condense this text, not a curriculum vitae and
     * not resuming a paused operation.
     *
     * SAME INPUT AS ENHANCE, DELIBERATELY. [reloadTarget] resolves the source through
     * [TextEnhancer.target], which reads Settings → Text Enhancements → "What gets enhanced"
     * (auto / selection / whole field). "Summarise the selection, or the field when nothing is
     * selected" is the same question Enhance already answered, the source line above already shows
     * that answer, and a second scope preference that disagreed with the one on screen would be a
     * setting whose only purpose was to surprise people.
     *
     * DIFFERENT OUTPUT, AND THAT IS THE POINT. The summary lands in the output box — readable,
     * editable, Copy-able — and stops there. It is never written into the field: see [Held].
     *
     * Shares Enhance's engine, key, model, timeout, sequence number and error wording; what differs
     * is the prompt set, and AiRouter.summarise is the one place either application composes it.
     */
    private fun resume() {
        editingOutput = false
        ui.removeCallbacks(reload)
        reloadTarget()
        val t = target
        if (t == null || t.text.isBlank()) { showStatus(str(R.string.resume_bar_no_source)); return }
        busy = true
        // A Resume in flight must not leave a Replace armed behind it: the two chips share the
        // sequence number, and a summary arriving into an armed apply is the exact overwrite
        // this feature is not allowed to perform.
        applyWhenReady = false
        val id = seq.incrementAndGet()
        val style = AiRouter.summaryStyle(context)
        val providerLabel = AiRouter.provider(context).label
        showStatus(context.getString(R.string.resume_in_progress, providerLabel))
        io.execute {
            val result = runCatching { AiRouter.summarise(context, style, t.text) }
            ui.post {
                if (id != seq.get()) return@post   // a newer run, or the bar was reopened
                busy = false
                result.onSuccess { out ->
                    held = Held.SUMMARY
                    editor.replaceAll(out)
                    renderOutput(); showStatus(str(R.string.resume_bar_done))
                }.onFailure { e ->
                    Log.e(TAG, "resume failed", e)
                    showStatus(when (e) {
                        is AiRouter.NoTokenException -> context.getString(R.string.ai_no_token, e.provider.label)
                        else -> context.getString(R.string.enhance_failed, e.message ?: e.javaClass.simpleName)
                    })
                }
            }
        }
    }

    /**
     * #355: whether a finished rewrite is written into the field without a second tap. Read at
     * use time rather than cached on open, so the Settings switch takes effect on the next
     * Generate instead of the next time the bar is opened.
     */
    private fun autoPaste() =
        context.prefs().getBoolean(Settings.PREF_ENHANCE_AUTO_PASTE, Defaults.PREF_ENHANCE_AUTO_PASTE)

    /** Write the box into the field over the text it was made from. */
    private fun applyOutput() {
        // A SUMMARY IS NOT A REWRITE AND NEVER REPLACES ITS SOURCE. Refused here rather than by
        // hiding the chip, so the bar can say why instead of appearing broken — and so the rule
        // holds for every route into this method, including a Replace armed before a Resume.
        if (held == Held.SUMMARY) { showStatus(str(R.string.resume_bar_no_replace)); return }
        val text = editor.text
        if (text.isBlank()) { showStatus(str(R.string.enhance_bar_generate_first)); return }
        val t = target ?: return
        val connection = provider?.get() ?: return
        // The field moved under us (the user typed, or the host app rewrote it) — the
        // stored range no longer describes what is on screen, so applying would corrupt it.
        if (!TextEnhancer.stillThere(context, connection, t)) {
            showStatus(str(R.string.enhance_stale)); reloadTarget(); return
        }
        if (TextEnhancer.apply(context, connection, t, text)) {
            // Undo needs to find the rewrite again later, which takes real coordinates.
            lastApplied = if (t.addressable) t to text else null
            undoVisible = lastApplied != null
            showStatus(str(R.string.enhance_bar_replaced))
            editingOutput = false   // the text lives in the field now — type there
            reloadTarget()   // the field is now the rewrite: enhancing again starts from it
            renderOutput()
        } else {
            showStatus(str(R.string.enhance_no_cursor))
        }
    }

    /**
     * Put the original back over the rewrite. The field is checked for holding exactly the
     * rewrite at that spot first; anything else there means the user (or the app) moved on,
     * and overwriting it would be a second accident on top of the one being undone.
     */
    private fun undo() {
        val (original, replacement) = lastApplied ?: return
        val connection = provider?.get() ?: return
        val applied = TextEnhancer.Target(replacement, original.start, original.start + replacement.length, original.fromSelection)
        if (!TextEnhancer.stillThere(context, connection, applied)) { showStatus(str(R.string.enhance_stale)); return }
        if (TextEnhancer.apply(context, connection, applied, original.text)) {
            lastApplied = null
            undoVisible = false
            showStatus(str(R.string.enhance_bar_undone))
            reloadTarget()
        } else {
            showStatus(str(R.string.enhance_no_cursor))
        }
    }

    private fun copyOutput() {
        val text = editor.text
        if (text.isBlank()) { showStatus(str(R.string.enhance_bar_generate_first)); return }
        clipboard().setPrimaryClip(ClipData.newPlainText("enhance", text))
        toast(str(R.string.enhance_bar_copied))
    }

    private fun clear() {
        held = Held.REWRITE   // an empty box is not a summary; Paste must not stay refused
        editor.clear(); renderOutput(); showStatus("")
    }

    /** Re-read what the ENHANCE key would rewrite right now and show it, with its size. */
    private fun reloadTarget() {
        val connection = provider?.get()
        target = if (connection == null) null else TextEnhancer.target(context, connection)
        val text = target?.text.orEmpty()
        val shown = if (text.isBlank()) str(R.string.enhance_bar_no_source) else text
        sourceText = shown
        sourceBlank = text.isBlank()
        sizeText = if (text.isBlank()) "" else sizeOf(text)
    }

    /**
     * Words, and tokens because both providers are language models billed per token. The
     * token figure is an estimate (no tokenizer ships in the keyboard) and is marked "~".
     * When the text exceeds one request's budget the number of requests is shown too.
     */
    private fun sizeOf(text: String): String {
        val words = wordCount(text)
        val tokens = AiRouter.estimateTokens(text)
        val requests = TextEnhancer.pieces(text, AiRouter.maxChars).size
        return if (requests > 1) context.getString(R.string.enhance_bar_size_parts, words, tokens, requests)
        else context.getString(R.string.enhance_bar_size, words, tokens)
    }

    /** Runs of non-whitespace, counted without allocating a split. */
    private fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            val space = c.isWhitespace()
            if (!space && !inWord) count++
            inWord = !space
        }
        return count
    }

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun pasteIntoBox() {
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (text.isEmpty()) { showStatus(str(R.string.enhance_bar_clipboard_empty)); return }
        editor.insert(text)
        renderOutput()
    }

    private fun copySelection() {
        val text = editor.selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("enhance", text))
        toast(str(R.string.enhance_bar_copied))
    }

    private fun cutSelection() {
        val text = editor.selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("enhance", text))
        // No selection means cut the whole rewrite — the destructive one, so it goes
        // through clear() and stays undoable.
        if (!editor.deleteSelection()) editor.clear()
        renderOutput()
    }

    /** Touching the box is what claims the keys; until then they belong to the app's field. */
    private fun attachOutputTouch() =
        // The empty check is HERE now rather than inside attachEditing, and it keeps this
        // bar's behaviour exactly as it was: an empty output box has no text of ours to
        // edit, so tapping it must leave the keys with the app's field — which is the
        // whole point of this bar reading the field instead of stealing the keys.
        outputView.attachEditing(editor, onClaim = { if (!editor.isEmpty) editingOutput = true },
            onChange = ::renderOutput, onMenu = ::showEditMenu)

    private fun showEditMenu() {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem(str(R.string.enhance_bar_menu_paste)) { pasteIntoBox() })
        if (editor.hasSelection()) {
            items.add(MenuItem(str(R.string.enhance_bar_menu_cut)) { cutSelection() })
            items.add(MenuItem(str(R.string.enhance_bar_menu_copy)) { copySelection() })
        }
        if (editor.selLo() != 0 || editor.selHi() != editor.length)
            items.add(MenuItem(str(R.string.enhance_bar_menu_select_all)) { editor.selectAll(); renderOutput() })
        // Undo of what landed IN THE BOX — a Generate or a Resume arriving on top of
        // text the user had typed there. Distinct from the Undo chip in the action row,
        // which puts the FIELD back; offered only when there is something to restore.
        if (editor.canUndo()) items.add(MenuItem(str(R.string.enhance_bar_undo)) { editor.undo(); renderOutput() })
        items.add(MenuItem(str(R.string.enhance_bar_clear)) { clear() })
        menu = items
    }

    // ── rendering ────────────────────────────────────────────────────────────
    /** Re-apply [editor]'s state onto the hosted output view (see [applyOutputView]). */
    private fun renderOutput() { outputTick++ }

    private fun applyOutputView() {
        outputTick // read: a bump re-runs the AndroidView update that calls this
        val p = palette()
        val text = p.textPrimary.toArgb()
        val hint = p.textSecondary.copy(alpha = 0.7f).toArgb()
        outputView.caretColor = text
        if (editor.isEmpty) {
            outputView.text = str(R.string.enhance_bar_output_hint)
            outputView.setTextColor(hint)
            outputView.setTypeface(null, android.graphics.Typeface.ITALIC)
            outputView.caret = -1
            return
        }
        outputView.setTextColor(text)
        outputView.setTypeface(null, android.graphics.Typeface.NORMAL)
        if (editor.hasSelection()) {
            outputView.text = SpannableString(editor.text).apply {
                setSpan(BackgroundColorSpan(p.accent.copy(alpha = 0.5f).toArgb()), editor.selLo(), editor.selHi(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            outputView.caret = -1
        } else {
            outputView.text = editor.text
            // The caret is the only thing that says where the keys are going, so it
            // is drawn only while the box actually has them.
            outputView.caret = if (editingOutput) editor.selStart else -1
        }
        // Posted: the layout still describes the text set BEFORE this call.
        outputView.post { outputView.revealCaret() }
    }

    private fun showStatus(msg: String) { statusText = msg }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun str(id: Int): String = context.getString(id)
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
