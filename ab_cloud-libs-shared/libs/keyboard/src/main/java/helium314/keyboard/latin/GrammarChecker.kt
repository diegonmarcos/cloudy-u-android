// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam
import android.content.Context
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.SuggestionSpan
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.GrammarMode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Offline + optional remote grammar pass over the word/separator the user just typed.
 *
 * Mode (PREF_GRAMMAR_MODE):
 *   "off"    — all grammar checks disabled.
 *   "local"  — the three built-in rules (capitalize-I, sentence-caps, repeated-words).
 *   "remote" — whole-field check POSTs to a LanguageTool-compatible server; falls back
 *              to the local rules on any network failure.
 *
 * Back-compat: if PREF_GRAMMAR_MODE is absent the old PREF_GRAMMAR_CHECK_ENABLED boolean
 * is consulted (true → "local", false → "off").
 *
 * [checkAndFix] runs once per separator keypress — always local, never remote (too slow).
 * [checkWholeField] is triggered by the GRAMMAR toolbar key and uses the selected mode.
 */
object GrammarChecker {
    private const val LOOKBACK = 60
    /** Large limit used by checkWholeField to fetch the full field content. */
    private const val FIELD_LIMIT = 4096
    private val TRAILING_WORD = Regex("""(\w+)$""")
    private val SENTENCE_END = Regex("""[.!?]\s*$""")

    private val remoteExecutor = Executors.newSingleThreadExecutor()

    // ── mode helper ──────────────────────────────────────────────────────────

    private fun resolveMode(context: Context): String {
        val prefs = context.prefs()
        // New pref takes precedence.
        if (prefs.contains(Settings.PREF_GRAMMAR_MODE)) {
            val stored = prefs.getString(Settings.PREF_GRAMMAR_MODE, Defaults.PREF_GRAMMAR_MODE)
                ?: Defaults.PREF_GRAMMAR_MODE
            // Grammar Check ▸ Routing (ML libs vs OpenRouter), once the user has set it, decides AI or not.
            if (!prefs.contains(AiTaskRouting.key(AiTask.GRAMMAR))) return stored
            return AiTaskRouting.grammarEngine(stored, AiTaskRouting.resolution(context, AiTask.GRAMMAR).active)
        }
        // Back-compat: derive from old boolean.
        return if (prefs.getBoolean(Settings.PREF_GRAMMAR_CHECK_ENABLED, Defaults.PREF_GRAMMAR_CHECK_ENABLED)) "local" else "off"
    }

    /**
     * LanguageTool `language=` code for the ACTIVE keyboard subtype. Keyboard text is
     * short, so server-side auto-detect ("language=auto") mis-detects; the keyboard
     * already knows the layout language, so send it. A subtype with a region
     * (pt_BR, en_GB, de_CH…) maps 1:1 to an LT variant; a bare language gets the
     * variant that carries LT's spelling rules (bare "en"/"de" have no speller),
     * and bare "pt" honours PREF_GRAMMAR_PT_VARIANT (default pt-PT).
     */
    private fun ltLanguage(context: Context): String {
        val loc: Locale = runCatching { RichInputMethodManager.getInstance().currentSubtypeLocale }
            .getOrNull() ?: return "auto"
        val lang = loc.language.lowercase(Locale.ROOT)
        if (lang.isEmpty()) return "auto"
        if (loc.country.isNotEmpty()) return "$lang-${loc.country.uppercase(Locale.ROOT)}"
        return when (lang) {
            "pt" -> context.prefs().getString(Settings.PREF_GRAMMAR_PT_VARIANT, Defaults.PREF_GRAMMAR_PT_VARIANT)
                ?: Defaults.PREF_GRAMMAR_PT_VARIANT
            "en" -> "en-US"
            "de" -> "de-DE"
            else -> lang // es, fr, it, nl… LT accepts the bare code with its speller
        }
    }

    // ── per-fix helpers ─────────────────────────────────────────────────────

    /**
     * Capitalise a standalone lowercase "i" -> "I".
     * Returns the fixed string, or the original if nothing changed.
     */
    private fun applyCapitalizeI(text: String): String =
        text.replace(Regex("""(?<!\w)i(?!\w)"""), "I")

    /**
     * Collapse an immediate duplicate word pair ("the the" -> "the").
     * Only collapses the pair with optional whitespace between them; does not
     * remove later occurrences to avoid false positives in longer passages.
     */
    private fun applyDedupeWords(text: String): String =
        text.replace(Regex("""(?i)\b(\w+)[ \t]+\1\b"""), "$1")

    /**
     * Capitalise the first character of a sentence: after [.!?] followed by
     * optional whitespace, and at the very start of the text.
     */
    private fun applySentenceCaps(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text)
        // capitalise start of string
        val first = sb[0]
        if (first.isLetter() && first.isLowerCase()) sb[0] = first.uppercaseChar()
        // capitalise after sentence-end punctuation
        val sentenceFollower = Regex("""([.!?]\s+)([a-z])""")
        var offset = 0
        sentenceFollower.findAll(text).forEach { m ->
            val capIdx = m.range.last + offset  // index of the lowercase letter in sb
            sb[capIdx] = sb[capIdx].uppercaseChar()
        }
        return sb.toString()
    }

    // ── local 3-rule pass ────────────────────────────────────────────────────

    private fun applyLocalRules(context: Context, text: String): String {
        val prefs = context.prefs()
        var fixed = text
        if (prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I, Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I))
            fixed = applyCapitalizeI(fixed)
        if (prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS, Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS))
            fixed = applyDedupeWords(fixed)
        if (prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS, Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS))
            fixed = applySentenceCaps(fixed)
        return fixed
    }

    // ── remote LanguageTool call ─────────────────────────────────────────────

    /** One form POST; null unless HTTP 200. Must NOT be called on the IME main thread. */
    private fun post(urlStr: String, body: String): String? {
        // #1093 an app that runs the fleet endpoints sends the declared languagetool service over them (mesh_only: the mesh
        // endpoint, WireGuard); any other app, or a URL that is not the declared one, takes the plain request below.
        FleetHttpSeam.exchange("POST", urlStr, mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            body, 3000, 8000)?.let { return if (it.code == 200) it.body else null }
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // de-DE / pt-PT take 1.3–1.9 s per sentence on the mesh server (measured
            // 2026-09-06); the old 1500 ms read timeout cut them off and fell back to
            // the local rules silently. This is a user-triggered toolbar action on a
            // background thread, so a longer wait is fine.
            connectTimeout = 3000
            readTimeout = 8000
            doOutput = true
            instanceFollowRedirects = false // an auth redirect is a failure, not a result
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST [text] to the LanguageTool-compatible endpoint for [language] and return the
     * field with LanguageTool's matches applied:
     *  - a match with exactly ONE replacement is unambiguous → applied in place;
     *  - a match with several replacements (typical for misspellings) is NOT guessed:
     *    the span gets a [SuggestionSpan] carrying up to 5 candidates, so the target
     *    field underlines it and a tap opens the pick-one popup (native EditText UX).
     * Matches are applied from highest offset to lowest so earlier offsets stay valid.
     * Returns null on any failure (caller falls back to local).
     *
     * Every issueType is honoured. The old {typographical, grammar} allow-list dropped
     * LanguageTool's `misspelling`, `inconsistency` and `uncategorized` matches — i.e.
     * "ellos fue → fueron", "habe → bin", "dont → don't" — while blindly applying the
     * FIRST of several candidates ("about they" → "about my").
     *
     * Must NOT be called on the IME main thread.
     */
    private fun callRemote(context: Context, text: String, language: String, highlightOnly: Boolean = false,
                           marks: MutableList<GrammarIssue>? = null): CharSequence? {
        return try {
            val prefs = context.prefs()
            val urlStr = prefs.getString(Settings.PREF_GRAMMAR_REMOTE_URL, Defaults.PREF_GRAMMAR_REMOTE_URL)
                ?: Defaults.PREF_GRAMMAR_REMOTE_URL
            val encoded = URLEncoder.encode(text, "UTF-8")
            // A subtype LT does not know (e.g. es-419) answers 400 → retry with auto-detect.
            val responseJson = post(urlStr, "text=$encoded&language=$language&level=default")
                ?: (if (language != "auto") post(urlStr, "text=$encoded&language=auto&level=default") else null)
                ?: return null

            val matches = JSONObject(responseJson).optJSONArray("matches") ?: return text

            data class Match(val offset: Int, val length: Int, val replacements: List<String>)
            val found = mutableListOf<Match>()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i)
                val reps = m.optJSONArray("replacements") ?: continue
                val values = (0 until reps.length())
                    .mapNotNull { reps.getJSONObject(it).optString("value").takeIf { v -> v.isNotEmpty() } }
                if (values.isEmpty()) continue
                val offset = m.optInt("offset", -1).takeIf { it >= 0 } ?: continue
                val length = m.optInt("length", 0).takeIf { it > 0 } ?: continue
                if (offset + length > text.length) continue // guard against stale offsets
                found.add(Match(offset, length, values))
            }

            // Highest offset first: replacing later text never moves earlier offsets, and
            // SpannableStringBuilder shifts already-set spans when earlier text changes.
            found.sortByDescending { it.offset }
            val sb = SpannableStringBuilder(text)
            for (m in found) {
                val end = m.offset + m.length
                if (highlightOnly) {
                    // Highlight: a text/fix pair the user ignored this session is not marked again.
                    val original = text.substring(m.offset, end)
                    if (GrammarHighlights.session.isIgnored(original, m.replacements[0])) continue
                    marks?.add(GrammarIssue(m.offset, end, original, m.replacements[0]))
                }
                if (m.replacements.size == 1 && !highlightOnly) {
                    sb.replace(m.offset, end, m.replacements[0])
                } else {
                    sb.setSpan(
                        SuggestionSpan(context, null, m.replacements.take(SuggestionSpan.SUGGESTIONS_MAX_SIZE).toTypedArray(),
                            SuggestionSpan.FLAG_MISSPELLED, null),
                        m.offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            sb
        } catch (_: Exception) {
            null
        }
    }

    // ── public entry points ──────────────────────────────────────────────────

    /**
     * Called unconditionally by InputLogic on every separator keypress (~line 1249).
     * Always uses local rules only (remote is too slow per-keystroke).
     * Returns immediately if mode is "off".
     */
    @JvmStatic
    fun checkAndFix(context: Context, connection: RichInputConnection) {
        if (resolveMode(context) == "off") return
        // #1077: the merged mode. Off does nothing; Highlight marks the word and leaves the text alone.
        val prefs = context.prefs()
        val grammarMode = GrammarMode.read(prefs)
        if (!grammarMode.checksGrammar) return
        val highlightOnly = !grammarMode.autoCorrects
        // Always local: remote only from checkWholeField.
        val capitalizeI  = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,      Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I)
        val dedupeWords  = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,    Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS)
        val sentenceCaps = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,     Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS)

        val before = connection.getTextBeforeCursor(LOOKBACK, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val tail = before.substring(trimmed.length) // separator(s) just typed, restored after the fix

        val lastWordMatch = TRAILING_WORD.find(trimmed) ?: return
        val lastWord = lastWordMatch.value
        val lastWordStart = lastWordMatch.range.first

        // fix 1 — standalone "i" -> "I"
        if (capitalizeI && lastWord == "i") {
            replace(context, connection, before, lastWordStart, lastWordStart + 1, "I", tail, highlightOnly)
            return
        }

        // fix 2 — repeated word
        if (dedupeWords) {
            val beforeLastWord = trimmed.substring(0, lastWordStart).trimEnd()
            val prevWordMatch = TRAILING_WORD.find(beforeLastWord)
            if (prevWordMatch != null && prevWordMatch.value.equals(lastWord, ignoreCase = true)) {
                replace(context, connection, before, prevWordMatch.range.last + 1, lastWordStart + lastWord.length, "", tail, highlightOnly)
                return
            }
        }

        // fix 3 — capitalise word at sentence start
        if (sentenceCaps && lastWord.first().isLowerCase()) {
            val beforeWord = trimmed.substring(0, lastWordStart)
            val isSentenceStart = beforeWord.isBlank() || SENTENCE_END.containsMatchIn(beforeWord)
            if (isSentenceStart)
                replace(context, connection, before, lastWordStart, lastWordStart + lastWord.length,
                    lastWord.replaceFirstChar { it.uppercase() }, tail, highlightOnly)
        }
    }

    /**
     * On-demand whole-field grammar fix, triggered by the GRAMMAR toolbar key.
     * In remote mode: fires async network call; on success applies LanguageTool matches;
     * on failure falls back to local 3-rule pass.  In local mode: applies local rules
     * synchronously.  In off mode: no-op.
     */
    @JvmStatic
    fun checkWholeField(context: Context, connection: RichInputConnection) {
        val grammarMode = GrammarMode.read(context.prefs())
        if (!grammarMode.checksGrammar) return // #1077: Off
        val engine = resolveMode(context)
        // Highlight never rewrites the field: only the remote engine can mark without replacing.
        if (!grammarMode.autoCorrects && engine != "remote") return
        when (engine) {
            "off" -> return
            "remote" -> checkWholeFieldRemote(context, connection, highlightOnly = !grammarMode.autoCorrects)
            // "ai": the AI Model Routing provider with the grammar-only prompt — same
            // in-place replace + revert span as Text Enhancements, no LanguageTool.
            "ai" -> TextEnhancer.run(context, connection, AiRouter.styleById("grammar"), AiTaskRouting.route(context, AiTask.GRAMMAR))
            else -> checkWholeFieldLocal(context, connection)
        }
    }

    private fun checkWholeFieldLocal(context: Context, connection: RichInputConnection) {
        connection.finishComposingText()
        val rawBefore = connection.getTextBeforeCursor(FIELD_LIMIT, 0)?.toString() ?: return
        val rawAfter  = connection.getTextAfterCursor(FIELD_LIMIT, 0)?.toString() ?: ""
        val full = rawBefore + rawAfter

        val fixed = applyLocalRules(context, full)
        val fixedBefore = fixed.substring(0, rawBefore.length.coerceAtMost(fixed.length))
        if (fixedBefore == rawBefore) return

        connection.beginBatchEdit()
        connection.deleteTextBeforeCursor(rawBefore.length)
        connection.commitText(fixedBefore, 1)
        connection.endBatchEdit()
    }

    private fun checkWholeFieldRemote(context: Context, connection: RichInputConnection, highlightOnly: Boolean = false) {
        connection.finishComposingText()
        val rawBefore = connection.getTextBeforeCursor(FIELD_LIMIT, 0)?.toString() ?: return
        val rawAfter  = connection.getTextAfterCursor(FIELD_LIMIT, 0)?.toString() ?: ""
        val full = rawBefore + rawAfter
        if (full.isBlank()) return
        val language = ltLanguage(context) // read on the IME thread, before hopping

        // Capture connection reference for the background thread callback.
        val base = connection.expectedSelectionStart - rawBefore.length // field offset of full[0]
        remoteExecutor.execute {
            val marks = mutableListOf<GrammarIssue>()
            val fixedFull: CharSequence = callRemote(context, full, language, highlightOnly, marks) ?: (if (highlightOnly) full else applyLocalRules(context, full))
            val cut = rawBefore.length.coerceAtMost(fixedFull.length)
            if (highlightOnly && base >= 0)
                GrammarHighlights.session.record(marks.filter { it.end <= cut }.map { it.copy(start = it.start + base, end = it.end + base) })
            val fixedBefore = fixedFull.subSequence(0, cut)
            // ponytail: matches that land after the cursor are dropped (text after the
            // cursor is left untouched); make it a full-field replace if that ever matters.
            val hasSpans = fixedBefore is Spanned && fixedBefore.getSpans(0, cut, SuggestionSpan::class.java).isNotEmpty()
            if (!hasSpans && fixedBefore.toString() == rawBefore) return@execute

            // Post the edit back; RichInputConnection methods are thread-safe (they use
            // Handler.post internally through InputConnection.sendKeyEvent routing).
            // beginBatchEdit/endBatchEdit pair keeps it atomic for undo history.
            connection.beginBatchEdit()
            connection.deleteTextBeforeCursor(rawBefore.length)
            connection.commitText(fixedBefore, 1)
            connection.endBatchEdit()
        }
    }

    // ── private low-level helper ─────────────────────────────────────────────

    private fun replace(context: Context, connection: RichInputConnection, before: String, start: Int, end: Int, replacement: String, tail: String, highlightOnly: Boolean = false) {
        val original = before.substring(start, end)
        if (original == replacement) return
        // Highlight: a pair ignored this session stays unmarked; otherwise remember where the mark lands.
        if (highlightOnly && GrammarHighlights.session.isIgnored(original, replacement)) return
        val fieldStart = connection.expectedSelectionStart - before.length
        connection.deleteTextBeforeCursor(before.length - start)
        if (highlightOnly) {
            if (fieldStart >= 0)
                GrammarHighlights.session.record(listOf(GrammarIssue(fieldStart + start, fieldStart + end, original, replacement))) // #1077 Highlight: same text back, the word marked with the fix as its suggestion
            val marked = SpannableString(original + tail)
            marked.setSpan(
                SuggestionSpan(context, null, if (replacement.isEmpty()) emptyArray() else arrayOf(replacement),
                    SuggestionSpan.FLAG_MISSPELLED, null),
                0, original.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            connection.commitText(marked, 1)
            return
        }
        val combined = SpannableString(replacement + tail)
        if (replacement.isNotEmpty()) {
            combined.setSpan(
                SuggestionSpan(context, null, arrayOf(original), SuggestionSpan.FLAG_EASY_CORRECT, null),
                0, replacement.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        connection.commitText(combined, 1)
    }
}
