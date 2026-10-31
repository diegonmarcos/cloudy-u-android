// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.widget.Toast
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SuperApp addition — "Text Enhancements": send the selection (or the whole field)
 * through [AiRouter] with the configured style prompt and replace it with the reply.
 * Triggered by the ENHANCE toolbar key; [GrammarChecker] mode "ai" reuses it with the
 * "grammar" style.
 *
 * What gets rewritten is [Settings.PREF_ENHANCE_SCOPE]: "auto" (the selection when there
 * is one, otherwise the whole field), "selection", or "field".
 *
 * Interaction = GrammarChecker's family: the improved text is committed in place and
 * carries a [SuggestionSpan] holding the ORIGINAL, so the field underlines it and a tap
 * offers the one-tap revert. Progress/failure are Toasts (the IME has no other surface
 * that survives the field losing focus).
 *
 * Cancel-on-new-input: each run takes a sequence number; a reply is dropped if a newer
 * run started, or if the field text changed while the model was thinking.
 */
object TextEnhancer {
    private const val TAG = "TextEnhancer"

    // Cached rather than single-threaded: a run stuck in a provider's read timeout must not
    // hold the next one in a queue behind it — that is what made the key look dead for
    // half a minute after one bad call.
    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()

    /** Values of [Settings.PREF_ENHANCE_SCOPE]; see [Defaults.PREF_ENHANCE_SCOPE]. */
    private enum class Scope { AUTO, SELECTION, FIELD }

    private fun scope(context: Context) =
        when (context.prefs().getString(Settings.PREF_ENHANCE_SCOPE, Defaults.PREF_ENHANCE_SCOPE)) {
            "selection" -> Scope.SELECTION
            "field" -> Scope.FIELD
            else -> Scope.AUTO
        }

    /**
     * The stretch of the field one run rewrites, in the editor's own coordinates, plus its
     * text. Resolving the range UP FRONT is what makes the apply deterministic: the old code
     * re-derived it from the cursor at commit time and silently left the text after the cursor
     * behind whenever the cursor position was unknown, duplicating the tail.
     *
     * [start]/[end] are negative when the editor never told us where the cursor is; only a
     * [fromSelection] target can still be applied then, because commitText replaces a
     * selection without needing coordinates.
     */
    class Target(val text: String, val start: Int, val end: Int, val fromSelection: Boolean) {
        fun sameAs(other: Target?) = other != null && other.text == text && other.start == start && other.end == end
        /** Whether the editor gave real coordinates, which is what a later write-back needs. */
        val addressable get() = start >= 0 && end >= start
    }

    /** What the ENHANCE key would rewrite right now — [EnhanceBarView] shows and applies this. */
    @JvmStatic
    fun target(context: Context, connection: RichInputConnection): Target? {
        connection.finishComposingText()
        return target(connection, scope(context))
    }

    /**
     * One extraction gives the whole field and where the selection sits in it, so both the
     * text and its range come from the editor's own coordinates. Nothing here stops at a
     * line break: what stopped short before was the per-side character cap of the
     * cursor-relative calls, which is now only the fallback below.
     */
    private fun target(connection: RichInputConnection, scope: Scope): Target? {
        val extracted = connection.getExtractedText()
        val whole = extracted?.text?.toString() ?: return targetAroundCursor(connection, scope)
        val base = extracted.startOffset
        val lo = minOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, whole.length)
        val hi = maxOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, whole.length)
        val selected = whole.substring(lo, hi)
        if (scope != Scope.FIELD && selected.isNotBlank()) return Target(selected, base + lo, base + hi, true)
        if (scope == Scope.SELECTION) return null
        return Target(whole, base, base + whole.length, false)
    }

    /**
     * For editors that refuse extraction: the window the cursor-relative calls can reach.
     * getTextBeforeCursor stops at the selection start and getTextAfterCursor resumes at the
     * selection end, so before + selected + after is the field as far as each cap allows; the
     * range is derived from the very same lengths, so what is replaced is exactly what was sent.
     */
    private fun targetAroundCursor(connection: RichInputConnection, scope: Scope): Target? {
        // Apps that do not report the cursor leave the expected position at -1; this is the
        // engine's own repair for that, and without it the range below cannot be computed.
        connection.tryFixIncorrectCursorPosition()
        val limit = AiRouter.maxChars
        val selectionStart = connection.expectedSelectionStart
        val selectionEnd = connection.expectedSelectionEnd
        val selected = connection.getSelectedText(0)?.toString()
        if (scope != Scope.FIELD && !selected.isNullOrBlank()) return Target(selected, selectionStart, selectionEnd, true)
        if (scope == Scope.SELECTION) return null
        // A selection the editor will not hand over cannot be stitched around: the text sent
        // would have a hole where the range replaced does not, so the rewrite would overwrite
        // text the model never saw.
        if (selected == null && selectionStart != selectionEnd) return null
        val before = connection.getTextBeforeCursor(limit, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(limit, 0)?.toString() ?: ""
        return Target(before + selected.orEmpty() + after, selectionStart - before.length, selectionEnd + after.length, false)
    }

    /**
     * True when the editor still holds exactly [target] at its range, which is the one question
     * a write-back has to ask. Checked against the field itself rather than against what the
     * ENHANCE key would pick now: moving the cursor or selecting a word after generating does
     * not change what is under the rewrite.
     */
    @JvmStatic
    fun stillThere(context: Context, connection: RichInputConnection, target: Target): Boolean {
        val whole = connection.getExtractedText()?.let { it.text?.toString() to it.startOffset }
            ?: return target.sameAs(target(context, connection))
        val (text, base) = whole
        if (text == null) return false
        val lo = target.start - base
        val hi = target.end - base
        return target.addressable && lo >= 0 && hi <= text.length && hi - lo == target.text.length &&
            text.regionMatches(lo, target.text, 0, target.text.length)
    }

    /** One piece of the text sent as its own request, and the whitespace that followed it in the field. */
    class Piece(val body: String, val tail: String)

    /**
     * Cut [text] into pieces of at most [limit] chars, preferring a paragraph break, then a line
     * break, then a sentence end, then a space, and only then a hard cut (never inside a surrogate
     * pair). The whitespace at each cut stays OUT of the request: models trim their reply, so a
     * paragraph break sent to the model comes back missing and the paragraphs fuse.
     */
    fun pieces(text: String, limit: Int): List<Piece> {
        val out = ArrayList<Piece>()
        val step = limit.coerceAtLeast(1)   // a zero budget would otherwise never advance
        var rest = text
        while (rest.isNotEmpty()) {
            val cut = if (rest.length <= step) rest.length else cutPoint(rest, step)
            val piece = rest.substring(0, cut)
            val body = piece.trimEnd()
            out.add(Piece(body, piece.substring(body.length)))
            rest = rest.substring(cut)
        }
        return out
    }

    private fun cutPoint(text: String, limit: Int): Int {
        for (separator in listOf("\n\n", "\n", ". ", " ")) {
            val at = text.lastIndexOf(separator, limit - separator.length)
            if (at <= 0) continue
            // Swallow the whole whitespace run so the next piece starts on a word: leading
            // whitespace sent to the model is trimmed from its reply and would be lost.
            var cut = at + separator.length
            while (cut < text.length && text[cut].isWhitespace()) cut++
            return cut
        }
        return if (limit > 1 && Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    }

    /**
     * The whole of [text] rewritten: one request when it fits the model's input budget, else one
     * per piece, joined back with the whitespace that separated them. [progress] gets (done,
     * total) before each request, on the calling thread. Blocking — never on the main thread.
     *
     * ponytail: pieces are rewritten independently; feed the previous piece's last sentence
     * as context if the seams ever read badly.
     */
    fun rewrite(
        context: Context,
        style: AiRouter.Style,
        text: String,
        progress: (Int, Int) -> Unit = { _, _ -> },
        // Which provider and model every piece goes to. Defaulted to this app's own choice, so
        // the keyboard's callers are unchanged; a sibling app that holds its own AI Model Routing
        // settings passes its route instead. The chunking, the seams and the credential lookup are
        // the same either way — the route decides where the request goes, not how it is built.
        route: AiRouter.Route = AiRouter.route(context),
    ): String {
        val lead = text.length - text.trimStart().length
        val pieces = pieces(text.substring(lead), AiRouter.maxChars)
        val out = StringBuilder(text.substring(0, lead))
        pieces.forEachIndexed { i, piece ->
            progress(i, pieces.size)
            if (piece.body.isNotEmpty()) out.append(AiRouter.complete(context, style.prompt, piece.body, route))
            out.append(piece.tail)
        }
        return out.toString()
    }

    @JvmStatic
    fun enhance(context: Context, connection: RichInputConnection) {
        adviseNext = true   // #883: the ENHANCE key (not Grammar's explicit style) may be advised on which style to use
        run(context, connection, AiRouter.enhanceStyle(context), AiTaskRouting.route(context, AiTask.ENHANCE))
    }

    @Volatile private var adviseNext = false

    /**
     * Whole-field / selection enhancement with an explicit [style] (GrammarChecker passes "grammar").
     *
     * EVERY WAY OUT OF HERE IS ATTRIBUTABLE. A run that ends without changing the text says which
     * of the eleven reasons it was, because a silent return is indistinguishable from a crash, a
     * dead key, a missing key, an unconfigured key and a network failure — the owner pressed
     * ENHANCE, saw nothing, and had no way to tell those apart. That ambiguity is what hid the
     * fact that a whole class of application was unsupported. Exits go through [ended], which
     * both logs the reason and puts a message on screen; [endedQuietly] is the single exception
     * and carries the one reason it is allowed to have.
     */
    @JvmStatic
    @JvmOverloads
    // route: Configs ▸ AI Routing, the task's own model when one is picked there (AiTaskRouting.route).
    fun run(context: Context, connection: RichInputConnection, style: AiRouter.Style, route: AiRouter.Route = AiRouter.route(context)) {
        val advise = adviseNext
        adviseNext = false
        val scope = scope(context)

        // An editor that declares TYPE_NULL accepts no text at all, and the InputConnection
        // contract gives commitText no way to report that it was ignored. Refused before the
        // model is paid, because otherwise the run reads as a success that changed nothing.
        if (Settings.getValues()?.mInputAttributes?.isTypeNull == true)
            return ended(context, "pre-flight", "editor declared TYPE_NULL", R.string.enhance_read_only)

        val target = target(context, connection)
        // BLANK MEANS TWO THINGS AND THE PLATFORM WILL NOT SAY WHICH. The field may genuinely be
        // empty, or it may hold text the app never exposes to an input method — a canvas editor,
        // a web view, a custom drawing surface. Both arrive here as the same empty buffer from
        // the same InputConnection call, which is the only view of the field an input method
        // gets, so the message names both readings rather than lying under one of them.
        if (target == null || target.text.isBlank())
            return ended(context, "pre-flight", "scope=$scope; the field reads back ${target?.text?.length ?: -1} characters",
                if (scope == Scope.SELECTION) R.string.enhance_no_selection else R.string.enhance_nothing_readable)

        val id = seq.incrementAndGet()
        Log.i(TAG, "run $id: scope=$scope, selection=${target.fromSelection}, " +
                "range=${target.start}..${target.end}, chars=${target.text.length}, style=${style.id}")
        toast(context, context.getString(R.string.enhance_in_progress, route.provider.label))

        executor.execute {
            val improved = try {
                // #883 Jev may pick the style when the owner never did (advise only on the ENHANCE key); no opinion = `style`
                val used = if (advise) KeyboardJev.base(context, target.text)?.let { AiRouter.enhanceStyle(context, it) } ?: style else style
                rewrite(context, used, target.text, route = route)
            } catch (e: AiRouter.NoTokenException) {
                return@execute ended(context, "run $id", "no API key for ${e.provider.id}",
                    R.string.ai_no_token, e.provider.label)
            } catch (e: IOException) {
                // The provider was never reached: no route, no name resolution, connect or read
                // timeout. Kept apart from a provider error because the repair is a different
                // one — this is the only failure here that is worth simply trying again.
                Log.w(TAG, "run $id: no answer from the provider", e)
                return@execute ended(context, "run $id", "network failure ${e.javaClass.simpleName}",
                    R.string.enhance_offline, route.provider.label)
            } catch (e: Exception) {
                Log.e(TAG, "run $id failed", e)
                return@execute ended(context, "run $id", "provider refused or replied unreadably",
                    R.string.enhance_failed, e.message ?: e.javaClass.simpleName)
            }
            if (id != seq.get()) return@execute endedQuietly("run $id", SUPERSEDED)
            // An empty reply and a reply identical to the source are not the same event: one is
            // the model failing to answer, the other is the model saying there was nothing to
            // fix. Sharing a sentence made a provider fault read as a compliment.
            if (improved.isEmpty())
                return@execute ended(context, "run $id", "reply was empty",
                    R.string.enhance_empty_reply, route.provider.label)
            if (improved == target.text)
                return@execute ended(context, "run $id", "reply is identical to the source", R.string.enhance_unchanged)

            main.post {
                if (id != seq.get()) return@post endedQuietly("run $id", SUPERSEDED)
                // Field changed while we waited → the reply no longer matches what the user sees.
                if (!stillThere(context, connection, target))
                    return@post ended(context, "run $id", "field moved while enhancing", R.string.enhance_stale)

                if (apply(context, connection, target, improved)) Log.i(TAG, "run $id: applied ${improved.length} chars")
                else return@post ended(context, "run $id", "range ${target.start}..${target.end} is not addressable",
                    R.string.enhance_no_cursor)
            }
        }
    }

    /**
     * The one reason a run is allowed to end without saying anything, spelled once so the tester
     * can hold the list to exactly this. A superseded run is not a failure the owner needs told
     * about: the run that superseded it is on screen saying its own piece, and a second message
     * landing on top of that would describe an event the owner deliberately caused.
     */
    private const val SUPERSEDED = "superseded by a newer run"

    /**
     * End a run without changing the text, and say so — in the log for whoever is reading a bug
     * report, and on screen for the owner holding the phone. [message] is a string resource
     * because every sentence this keyboard shows has to reach the owner in their own language.
     */
    private fun ended(context: Context, label: String, why: String, message: Int, vararg args: Any) {
        Log.i(TAG, "$label ended without a rewrite: $why")
        toast(context, context.getString(message, *args))
    }

    /** As [ended], but log-only; [why] may only ever be [SUPERSEDED]. */
    private fun endedQuietly(label: String, why: String) {
        Log.i(TAG, "$label ended without a rewrite and without a message: $why")
    }

    /**
     * Write [replacement] over [target]'s range, keeping the original as a one-tap revert.
     * Returns false when the range cannot be addressed, so callers can say so rather than
     * silently dropping the rewrite. Main thread only.
     */
    @JvmStatic
    fun apply(context: Context, connection: RichInputConnection, target: Target, replacement: String): Boolean {
        val spanned = SpannableString(replacement).apply {
            // ponytail: the whole original as ONE revert candidate; the popup gets long for
            // long fields — split per sentence if that ever bothers anyone.
            setSpan(SuggestionSpan(context, null, arrayOf(target.text), SuggestionSpan.FLAG_EASY_CORRECT, null),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        connection.beginBatchEdit()
        // Select the range, then commit: commitText replaces the selection (InputConnection
        // contract), so one call covers both halves of the field.
        val ready = if (target.addressable) connection.setSelection(target.start, target.end) else target.fromSelection
        if (ready) connection.commitText(spanned, 1)
        connection.endBatchEdit()
        return ready
    }

    private fun toast(context: Context, msg: String) = main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
}
