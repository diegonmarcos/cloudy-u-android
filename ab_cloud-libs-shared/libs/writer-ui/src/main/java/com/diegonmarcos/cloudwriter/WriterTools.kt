package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import com.diegonmarcos.cloudwriter.core.Answer
import com.diegonmarcos.superapp.texttools.TextTools
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The owner's four tools, and therefore WHICH ENGINE each one reaches.
 *
 * The pairing is the point of the enum. [ENHANCE], [GRAMMAR] and [SUMMARY] go to the
 * chat-completions provider chosen in this application's own settings; [TRANSLATE] goes to the
 * translation library. They cost different money, they answer differently, and a reply does not
 * say which one produced it — so [WriterToolRunner.run] is the single place that maps a tool to an
 * engine, which means there is exactly one line in this application where they could be crossed
 * and exactly one line to assert on.
 *
 * [SUMMARY] is "Resume" elsewhere in this fleet — the owner's name for SUMMARISE, condense this
 * text. Not a curriculum vitae, and not resuming anything that was paused. It is spelled Summary
 * on this screen and summarise on the wire so neither can be misread.
 *
 * [GRAMMAR] is not a fourth engine: it is the rewrite engine asked for the one `grammar` style,
 * which is what `ITextTools.enhance(text, "grammar")` describes.
 *
 * [usesModel] is true for the three tools whose model is a registry row picked under the text.
 * [TRANSLATE] has a model too, but since #800 it is the Translation ROUTE's (Configs > Routes):
 * Model = an OpenRouter model chosen from the live catalogue with the fleet Account's token,
 * On-device = ML Kit through libs:translate, falling back from the first to the second. Its prompt
 * is still [WriterRegistry.translatePrompt], the one that forbids every improvement the rewrite
 * prompt invites, so Translate translates and does not rewrite.
 *
 * [id] is the string the per-tool model preference is keyed by, so it is written down here once
 * and is not `name.lowercase()`: renaming an enum constant must not silently re-point every
 * preference the owner has set.
 */
enum class WriterTool(
    val id: String,
    @StringRes val label: Int,
    val usesModel: Boolean,
) {
    ENHANCE("enhance", R.string.tool_enhance, usesModel = true),
    GRAMMAR("grammar", R.string.tool_grammar, usesModel = true),
    SUMMARY("summary", R.string.tool_summary, usesModel = true),
    // #800 Translate runs on the Translation ROUTE (Configs > Routes): its model is picked there from
    // the live catalogue, so it has no row among the per-tool registry models.
    TRANSLATE("translate", R.string.tool_translate, usesModel = false),
}

/** How a finished run ended. Exactly one of [text] and [error] is set, and never neither. */
/** [answer] is set for a routed tool (Translate): which route answered, and why it fell back. */
class WriterOutcome(val tool: WriterTool, val text: String?, val error: String?, val answer: Answer? = null)

/**
 * Runs a tool and holds enough state for the screen to say what is happening.
 *
 * THERE IS NO SILENT PATH OUT OF [run]. Every branch — a blank input, a tool this build cannot
 * honour, a second tap while one call is in flight, a peer that is not installed, a provider that
 * refused — ends in [onDone] with a sentence. A control that does nothing and says nothing is
 * indistinguishable from a crash, a missing permission and a network failure, which is the defect
 * 1_cicd/src/data/silence-guard.json exists to catch elsewhere in this repository.
 *
 * The binder calls BLOCK, by contract: `ITextTools` says so in capitals and `TextToolsClient` does
 * no threading of its own on purpose, so that a caller cannot forget it must show progress. One
 * single-thread executor, and the callback is posted back to the main looper.
 */
class WriterToolRunner(context: Context) {

    private val app = context.applicationContext

    /**
     * ONE binding for the whole process. The client binds on construction and rebinds on use, so
     * a fresh one per screen would open a second binding to the same service and spend its own
     * rebind budget.
     */
    private val client = TextToolsClient(app)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Non-null while a call is in flight; the screen shows progress for exactly this tool. */
    @Volatile
    var busy: WriterTool? = null
        private set

    /** Is a serving application on this phone at all — the question a status line must ask. */
    fun isServingAppInstalled(): Boolean = client.isServingAppInstalled()

    /**
     * Wait up to [timeoutMs] for the binder. bindService answers through a callback, so a call made
     * right after the client was constructed finds no binder yet; reading that as an answer is how
     * a current Cloud Keyboard got reported as "older than the contract" (#800). BLOCKS.
     */
    fun awaitBound(timeoutMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!client.isConnected()) {
            if (SystemClock.elapsedRealtime() >= until) return false
            Thread.sleep(100)
        }
        return true
    }

    /**
     * The package name of the peer that is ANSWERING, read out of its own reply.
     *
     * `aiRoutingSnapshot()` carries `app`, which is how a console survives the tools moving house:
     * the answer says who gave it, so nothing on this side has to hold a constant that would keep
     * naming Cloud Keyboard on the day something else began serving. That is the same property
     * that makes `TextTools.SERVICE_PACKAGES` an ordered preference rather than one address.
     *
     * NULL IS TWO DIFFERENT FACTS and the caller separates them with [isServingAppInstalled]: no
     * peer installed is an install, a peer bound but answering nothing is a peer too old to know
     * the method, which is an update. Reporting them as one sends the owner to the wrong repair.
     *
     * NOTHING IN THE SNAPSHOT IS A CREDENTIAL — it carries key PRESENCE and at most a four
     * character hint by construction on the far side — and this method reads one field out of it
     * and drops the rest, so there is nothing here to leak even if that ever changed.
     *
     * BLOCKS. Callers put it on a background thread.
     */
    fun aiRoutingApp(): String? {
        val raw = client.aiRoutingSnapshot() ?: return null
        return runCatching { JSONObject(raw).optString("app") }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * BCP-47 tags the translation engine can target right now, straight from the serving
     * application's own engine.
     *
     * ASKED, NEVER KEPT. A copy of this list in cloud-writer would be a second catalogue to go
     * stale, and the page would then offer a language the engine has since dropped. Empty when
     * nothing is bound, and the page draws `writer_ai.translate_fallback_langs` for that one
     * frame rather than an empty picker.
     *
     * BLOCKS. Callers put it on a background thread.
     */
    fun translateLanguages(): List<String> = client.translateLanguages()

    /**
     * What to name the provider in progress and error text — the label of THIS application's
     * chosen provider, not the serving application's.
     *
     * The two can differ, and naming the peer's provider over a run routed to cloud-writer's would
     * be a progress line that lies about where the request went. Null when nothing is bound, which
     * the run itself reports.
     *
     * BLOCKS. Callers put it on a background thread.
     */
    fun providerLabel(): String? = client.providerLabelFor(WriterPrefs.providerId(app))

    /**
     * Send [text] through [tool] and hand the outcome to [onDone] on the main thread.
     *
     * A second tap while one is in flight is REFUSED WITH A REASON rather than queued or ignored:
     * two runs racing to write the same output box is the bug, and an ignored tap is the silence
     * this class does not allow.
     */
    fun run(tool: WriterTool, text: String, onDone: (WriterOutcome) -> Unit) {
        if (busy != null) {
            onDone(WriterOutcome(tool, null, app.getString(R.string.run_busy)))
            return
        }
        if (text.isBlank()) {
            onDone(WriterOutcome(tool, null, app.getString(R.string.run_nothing_to_send)))
            return
        }
        // THE ONE TOOL THIS BUILD MAY BE UNABLE TO HONOUR, refused before anything is spent.
        //
        // Grammar Check is the `grammar` style and nothing else, so a registry with no such style
        // leaves this application unable to do the tool. It says so instead of falling back to the
        // default style, which would send "improve this text" under a button labelled Grammar
        // Check and hand back a rewritten paragraph to someone who asked for their commas fixed.
        if (tool == WriterTool.GRAMMAR && WriterRegistry.grammarPrompt() == null) {
            onDone(WriterOutcome(tool, null, app.getString(R.string.run_grammar_unavailable)))
            return
        }
        // TRANSLATE INTO WHAT? Refused rather than guessed, for the same reason as above.
        //
        // Language Output is one of the four options under the text box, and "Keep my language" is
        // a real choice there — for Enhance it means "rewrite it in whatever I wrote it in". For
        // Translate it is not a target at all, and picking one on the owner's behalf would send a
        // Spanish note away as English because a default had to be something.
        if (tool == WriterTool.TRANSLATE && WriterPrefs.translatePrompt(app) == null) {
            onDone(WriterOutcome(tool, null, app.getString(R.string.run_translate_no_target)))
            return
        }
        // THE MODE THE OWNER SET ON THE GRAMMAR CHECK PAGE, honoured here and honoured by
        // refusing where this application cannot do what the mode promises.
        //
        // NO MODE SILENTLY DOES ANOTHER MODE'S WORK. Remoto means "send it to LanguageTool", and
        // cloud-writer opens no socket of its own — every tool goes through the serving
        // application, and ITextTools has no LanguageTool method. Quietly running the AI rewrite
        // instead would put "improve this text" behind a button the owner set to Remote and hand
        // back a rewritten paragraph. So it says which mode is set, what that mode needs, and
        // which two modes work here.
        if (tool == WriterTool.GRAMMAR) {
            val mode = WriterPrefs.grammarMode(app)
            if (mode == WriterPrefs.GRAMMAR_OFF) {
                onDone(WriterOutcome(tool, null, app.getString(R.string.grammar_off)))
                return
            }
            if (mode == WriterPrefs.GRAMMAR_REMOTE) {
                // The reason is lifted into a local so the onDone call fits ONE LINE. That is not
                // formatting: test-cloud-writer-tools.sh proves "no silent exit from run()" by
                // requiring the line before every `return` to contain onDone(, and a call split
                // across two lines is a refusal the structural check cannot see. It flagged this
                // one the first time it was written, which is the check doing its job.
                val why = app.getString(R.string.grammar_remote_unreachable, WriterPrefs.grammarRemoteUrl(app))
                onDone(WriterOutcome(tool, null, why))
                return
            }
            if (mode == WriterPrefs.GRAMMAR_LOCAL && !WriterLocalGrammar.anyFixEnabled(app)) {
                onDone(WriterOutcome(tool, null, app.getString(R.string.grammar_local_all_off)))
                return
            }
        }
        busy = tool
        worker.execute {
            val result = runTool(tool, text)
            busy = null
            val answer = if (tool == WriterTool.TRANSLATE) WriterRoutes.lastTranslation else null
            main.post { onDone(WriterOutcome(tool, result.text, result.error, answer)) }
        }
    }

    /**
     * #1418 the Drafter: the incoming message in, a suggested reply out, on the Enhance provider
     * and model (the same ITextTools.enhanceWith path as Enhance, so no second engine and no fork).
     */
    fun draft(text: String, onDone: (WriterOutcome) -> Unit) {
        if (busy != null) {
            onDone(WriterOutcome(WriterTool.ENHANCE, null, app.getString(R.string.run_busy)))
            return
        }
        if (text.isBlank()) {
            onDone(WriterOutcome(WriterTool.ENHANCE, null, app.getString(R.string.run_nothing_to_send)))
            return
        }
        busy = WriterTool.ENHANCE
        worker.execute {
            WriterPrefs.seedFromServingApp(app, client)
            val provider = WriterPrefs.providerId(app)
            val result = client.enhanceWith(text, WriterPrefs.draftPrompt(app), provider, WriterPrefs.modelFor(app, WriterTool.ENHANCE, provider))
            busy = null
            main.post { onDone(WriterOutcome(WriterTool.ENHANCE, result.text, result.error)) }
        }
    }

    /**
     * THE ROUTING DECISION, AND THE ONLY ONE IN THIS APPLICATION.
     *
     * Every call carries THIS APPLICATION'S settings — its composed prompt, its provider, and the
     * model chosen FOR THIS TOOL — rather than an empty argument meaning "use yours". That empty
     * argument is the coupling: it makes the serving application's store the only store, and this
     * application's pages a view of settings it cannot change. It is also what makes the owner's
     * per-tool model request real rather than decorative: `WriterPrefs.modelFor(tool)` is resolved
     * separately on each of the three branches below, so Summary can run on a cheap model while
     * Enhance runs on a strong one.
     *
     * Runs on [worker]. Blocking, by contract.
     */
    private fun runTool(tool: WriterTool, text: String): TextTools.Result {
        // Bring the owner's existing configuration across the first time, before the first run
        // reads it. Idempotent, and a no-op once seeded.
        WriterPrefs.seedFromServingApp(app, client)
        val provider = WriterPrefs.providerId(app)
        return when (tool) {
            WriterTool.ENHANCE -> client.enhanceWith(
                text,
                WriterPrefs.enhancePrompt(app),
                provider,
                WriterPrefs.modelFor(app, WriterTool.ENHANCE, provider),
            )
            // Local mode never leaves the device and never spends a token: the three switches on
            // the Grammar check page, applied here. "off" and "remote" were already refused in
            // run(), so reaching this branch means the mode is "local" or "ai".
            WriterTool.GRAMMAR -> if (WriterPrefs.grammarMode(app) == WriterPrefs.GRAMMAR_LOCAL) {
                WriterLocalGrammar.fix(app, text)
            } else client.enhanceWith(
                text,
                // Non-null: refused in run() above when the registry carries no grammar style.
                WriterRegistry.grammarPrompt().orEmpty(),
                provider,
                WriterPrefs.modelFor(app, WriterTool.GRAMMAR, provider),
            )
            WriterTool.SUMMARY -> client.summariseWith(
                text,
                WriterPrefs.summaryPrompt(app),
                WriterPrefs.summaryWantsBullets(app),
                provider,
                WriterPrefs.modelFor(app, WriterTool.SUMMARY, provider),
            )
            // The chat provider, on its own model row, against a prompt that forbids rewriting —
            // so the owner can pick a translation-ranked model for translation without that model
            // also deciding how his paragraphs read. Non-null: refused in run() above when
            // Language Output is still "keep my language".
            WriterTool.TRANSLATE -> WriterRoutes.translate(app, text, WriterPrefs.enhanceLanguageId(app)).let { a ->
                if (a.ok) TextTools.Result(a.text, null) else TextTools.Result.failed(a.error ?: app.getString(R.string.run_no_reason))
            }
        }
    }
}

/**
 * The three "Correcciones" switches on the Grammar check page, applied on this device.
 *
 * WHY THIS EXISTS AT ALL: so those three switches are settings and not decoration. In the keyboard
 * they drive its own local pass; copying the page without copying the pass would have given the
 * owner three toggles that changed nothing, which is the same complaint as task 209 wearing
 * different clothes.
 *
 * DELIBERATELY SMALL, and it does not pretend otherwise. These are the three high-confidence fixes
 * the keyboard names, not a grammar engine: a lone English "i", a sentence that starts lowercase,
 * and a word typed twice in a row. Anything subtler is what Remoto (LanguageTool) and IA are for,
 * and both of those say so on the page.
 *
 * A RUN THAT CHANGED NOTHING REPORTS THAT IT CHANGED NOTHING. Handing back the identical string
 * with a cheerful "done" is indistinguishable from a tool that silently failed.
 */
object WriterLocalGrammar {

    fun anyFixEnabled(context: Context): Boolean =
        WriterPrefs.flag(context, WriterPrefs.KEY_GRAMMAR_FIX_CAPITALIZE_I, WriterPrefs.DEFAULT_GRAMMAR_FIX_CAPITALIZE_I) ||
            WriterPrefs.flag(context, WriterPrefs.KEY_GRAMMAR_FIX_SENTENCE_CAPS, WriterPrefs.DEFAULT_GRAMMAR_FIX_SENTENCE_CAPS) ||
            WriterPrefs.flag(context, WriterPrefs.KEY_GRAMMAR_FIX_REPEATED_WORDS, WriterPrefs.DEFAULT_GRAMMAR_FIX_REPEATED_WORDS)

    fun fix(context: Context, text: String): TextTools.Result {
        var out = text
        if (WriterPrefs.flag(context, WriterPrefs.KEY_GRAMMAR_FIX_REPEATED_WORDS, WriterPrefs.DEFAULT_GRAMMAR_FIX_REPEATED_WORDS))
            out = removeRepeatedWords(out)
        if (WriterPrefs.flag(context, WriterPrefs.KEY_GRAMMAR_FIX_CAPITALIZE_I, WriterPrefs.DEFAULT_GRAMMAR_FIX_CAPITALIZE_I))
            out = capitaliseLoneI(out)
        if (WriterPrefs.flag(context, WriterPrefs.KEY_GRAMMAR_FIX_SENTENCE_CAPS, WriterPrefs.DEFAULT_GRAMMAR_FIX_SENTENCE_CAPS))
            out = capitaliseSentenceStarts(out)
        return if (out == text) TextTools.Result.failed(context.getString(R.string.grammar_local_unchanged))
        else TextTools.Result(out, null)
    }

    /** A lone lowercase English "i". Word-bounded, so "iPhone" and the Spanish "i" of a word are untouched. */
    private fun capitaliseLoneI(text: String) = Regex("\\bi\\b").replace(text, "I")

    /**
     * The same word twice in a row, case-insensitively, keeping the FIRST spelling.
     *
     * Keeping the first rather than the second is what makes "The the" become "The": the reader
     * typed the capital deliberately and the duplicate is the accident.
     */
    private fun removeRepeatedWords(text: String) =
        Regex("\\b(\\w+)(\\s+)\\1\\b", RegexOption.IGNORE_CASE).replace(text) { m ->
            m.groupValues[1]
        }

    /** The first letter of the text, and of whatever follows a full stop, question or exclamation mark. */
    private fun capitaliseSentenceStarts(text: String): String {
        val out = StringBuilder(text)
        var expectCapital = true
        for (at in out.indices) {
            val c = out[at]
            if (expectCapital && c.isLetter()) {
                out[at] = c.uppercaseChar()
                expectCapital = false
            } else if (c == '.' || c == '!' || c == '?') {
                expectCapital = true
            } else if (!c.isWhitespace() && c != '"' && c != '\'') {
                // Anything else that is not a quote or a space ends the run; a capital is only
                // owed to the first LETTER after the stop, not to every character after it.
                expectCapital = expectCapital && !c.isLetterOrDigit()
            }
        }
        return out.toString()
    }
}
