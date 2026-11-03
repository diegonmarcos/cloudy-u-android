package com.diegonmarcos.cloudwriter

import android.util.Base64
import org.json.JSONObject

/**
 * cloud-writer's OWN text-tool registry: providers, model lists, Enhance prompts, Grammar prompt,
 * Resume prompts and output languages.
 *
 * WHAT THIS IS A COPY OF, AND WHAT IT DELIBERATELY IS NOT. The serving application's `AiRouter`
 * does two separable jobs: it reads a baked registry and resolves the user's choices out of it,
 * and it makes the HTTPS request. This object is a copy of the FIRST job only, over cloud-writer's
 * own `build.json::writer_ai`. The second job — the POST, the authorisation header, the timeout,
 * the chunking, the summary budget, the bullet enforcement and the provider API KEY — is not
 * copied and must never be: it stays in the serving application's process behind `ITextTools`, in
 * one copy, so a fix to it is made once. See [WriterPrefs] for the settings side.
 *
 * WHY A COPY AT ALL. The owner asked for one application that owns Text Enhance, Translate,
 * Grammar Check and Summary and that owns the model choice for each of them separately. A single
 * shared registry cannot give an application settings of its own no matter how the screens are
 * arranged: two screens over one store still edit each other, which is what task 209 — "WHY I
 * CANT EDIT NOTHING" — was actually about. The prompts and model lists here started identical to
 * cloud-mail's and the keyboard's and are free to diverge from this point on. That divergence is
 * the feature.
 *
 * Reads `BuildConfig.WRITER_AI_ROUTING_B64`, baked by app/build.gradle. Base64 because the prompts
 * carry quotes and newlines a plain BuildConfig string would not survive.
 */
object WriterRegistry {

    /**
     * The style id Grammar Check sends, and the one id in this file that is a CONTRACT rather
     * than data.
     *
     * Grammar Check is not a separate engine here: it is the rewrite engine asked for one named
     * style, exactly as `ITextTools.enhance(text, "grammar")` describes. So the tool is only
     * honest while the registry actually holds a style with this id. [grammarStyle] answers null
     * when it does not, and the Grammar tool then REFUSES rather than falling back — a fallback
     * would quietly send "improve this text" under a button labelled Grammar Check and hand the
     * owner a rewritten paragraph when they asked for their commas fixed.
     */
    const val GRAMMAR_STYLE_ID = "grammar"

    /**
     * One named prompt out of the registry. [bullets] marks a prompt that asked the model for a
     * list; it is registry data rather than a guess from the wording, and it is the flag that
     * travels to the engine so the far side can hold the model to the shape THIS application
     * asked for.
     */
    class Style(val id: String, val label: String, val prompt: String, val bullets: Boolean = false)

    /**
     * One row of the routing table. [id] is the provider's exact model id — what a request sends.
     *
     * EVERY COLUMN THE AI MODEL ROUTING PAGE DRAWS IS A FIELD HERE, and all of them come out of
     * build.json::writer_ai. The page has no table of its own to go stale — the defect that
     * produced this fleet's 100x price column and its 3.5x stale price, both of which were a
     * second copy of a number disagreeing with the first.
     *
     * [promptUsdPerMillion] / [completionUsdPerMillion] are UNITED STATES DOLLARS PER MILLION
     * TOKENS, the unit the provider publishes, stored and drawn UNCONVERTED. There is no scaling
     * anywhere between this field and the cell: a price the screen multiplies is a price that
     * disagrees with the provider's own list and the reader cannot tell which of the two is lying.
     * Null where the provider quotes no per-token price at all, which is the mesh bridge.
     */
    class Model(
        val id: String,
        val name: String,
        val note: String?,
        val open: Boolean = false,
        val paramsB: Int? = null,
        val quant: List<String> = emptyList(),
        val trainedFor: List<String> = emptyList(),
        val promptUsdPerMillion: Double? = null,
        val completionUsdPerMillion: Double? = null,
    )

    /**
     * [pricingAsOf] is the date the baked prices were taken, drawn under the table. It is NOT
     * decoration: cloud-writer declares no INTERNET permission and fetches no catalogue, so every
     * price on its page is this date's price and the page has to say so. A table that looked live
     * and was eleven months old is exactly how a 0.966 was read as today's 0.280.
     *
     * [needsToken] is carried for the API-key row, which in THIS application is read-only — the
     * key lives in the serving application. See `ai_token_writer_summary`.
     *
     * [toolModels] maps a [WriterTool] id to the model THAT TOOL starts on, and [defaultModel] is
     * only what a tool with no entry there falls back to. One provider-wide default had to suit
     * four different jobs at once, which is how a model picked for translation ended up rewriting
     * paragraphs; the per-tool table is in build.json::writer_ai.tool_models, with the reasoning
     * for every pick beside it.
     */
    class Provider(
        val id: String,
        val label: String,
        val defaultModel: String,
        val models: List<Model>,
        val needsToken: Boolean = false,
        val pricingAsOf: String? = null,
        val catalogUrl: String? = null,
        val toolModels: Map<String, String> = emptyMap(),
    )

    internal val registry: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.WRITER_AI_ROUTING_B64, Base64.DEFAULT), Charsets.UTF_8))
    }

    val providers: List<Provider> by lazy {
        val o = registry.getJSONObject("providers")
        o.keys().asSequence().map { id ->
            val p = o.getJSONObject(id)
            val models = p.getJSONArray("models").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
            }.map { m ->
                val modelId = m.getString("id")
                // A row with no short name would render an empty Name cell, which reads as a
                // broken row rather than a missing field; the id always works.
                Model(
                    id = modelId,
                    name = m.optString("name").ifEmpty { modelId },
                    note = m.optString("note").ifEmpty { null },
                    open = m.optBoolean("open"),
                    // -1 is not a parameter count: `has` separates "absent" from "zero" so a
                    // missing size draws the unknown mark rather than "0B", which is a claim.
                    paramsB = if (m.has("params_b")) m.getInt("params_b") else null,
                    quant = strings(m, "quant"),
                    trainedFor = strings(m, "trained_for"),
                    promptUsdPerMillion = if (m.has("prompt")) m.getDouble("prompt") else null,
                    completionUsdPerMillion = if (m.has("completion")) m.getDouble("completion") else null,
                )
            }
            Provider(
                id = id,
                label = p.getString("label"),
                defaultModel = p.getString("default_model"),
                models = models,
                needsToken = p.optBoolean("needs_token"),
                pricingAsOf = p.optString("pricing_as_of").ifEmpty { null },
                catalogUrl = p.optString("catalog_url").ifEmpty { null },
                toolModels = registry.optJSONObject("tool_models")?.optJSONObject(id)
                    ?.let { t -> t.keys().asSequence().associateWith { k -> t.getString(k) } }
                    .orEmpty(),
            )
        }.toList()
    }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    /**
     * Presentation order for the model picker AND the price table, and ONLY presentation: size,
     * then the lowest precision the model is served at, then name. A run resolves the stored model
     * id, never a row position, so re-sorting cannot change which model a tool calls.
     *
     * Models that publish no size sort LAST rather than first. Size is the comparison the
     * open-weight rows exist for; sorting a sizeless hosted model as 0 would put it at the top and
     * imply it is the smallest thing on the page.
     */
    fun byModelSize(models: List<Model>): List<Model> =
        models.sortedWith(compareBy({ it.paramsB ?: Int.MAX_VALUE }, { quantBits(it) }, { it.name }))

    private fun quantBits(m: Model): Int =
        m.quant.mapNotNull { q -> q.filter { it.isDigit() }.toIntOrNull() }.minOrNull() ?: Int.MAX_VALUE

    /** A provider gets a price table when it has anything to show. */
    fun hasPricing(p: Provider): Boolean =
        p.models.any { it.promptUsdPerMillion != null || it.completionUsdPerMillion != null }

    /** A registry object of id → {label, prompt}. JSONObject keeps insertion order, so this is menu order. */
    private fun promptSet(key: String): List<Style> {
        val o = registry.optJSONObject(key) ?: return emptyList()
        return o.keys().asSequence().map { id ->
            val s = o.getJSONObject(id)
            Style(id, s.getString("label"), s.optString("prompt"), s.optBoolean("bullets"))
        }.toList()
    }

    val styles: List<Style> by lazy { promptSet("styles") }
    val tones: List<Style> by lazy { promptSet("tones") }
    val lengths: List<Style> by lazy { promptSet("lengths") }
    val languages: List<Style> by lazy { promptSet("languages") }

    /**
     * "Text Resume" — SUMMARISE. The owner's product name for condensing a text, kept exactly; not
     * a curriculum vitae and not resuming a paused operation.
     *
     * Its own prompt set rather than more [styles]: an Enhance style listed among the summaries
     * would offer "improve this" to a feature promising "shorten this", and a summary prompt in
     * the Enhance list would let Enhance replace the user's paragraph with a précis of it.
     */
    val summaries: List<Style> by lazy { promptSet("summaries") }

    /**
     * Target languages the Translation page offers until the serving engine reports its own.
     *
     * A FALLBACK, NOT A CATALOGUE. The page redraws from `ITextTools.translateLanguages()` the
     * moment that binder call answers; this list only fills the first frame, which happens before
     * any call can return. Kept in build.json rather than in the page so it is not a second
     * language list written in a second kind of place.
     */
    val translateFallbackLanguages: List<String> by lazy { strings(registry, "translate_fallback_langs") }

    val defaultProvider: String get() = registry.getString("default_provider")
    val defaultStyle: String get() = registry.getString("default_style")
    val defaultTone: String get() = registry.optString("default_tone", "keep")
    val defaultLength: String get() = registry.optString("default_length", "keep")
    val defaultLanguage: String get() = registry.optString("default_language", "keep")
    val defaultSummary: String get() = registry.getString("default_summary")

    /**
     * Prepended to every rewrite prompt. Without it the model reads the text as a message
     * addressed to it and answers it conversationally instead of rewriting it.
     */
    val rewritePreamble: String get() = registry.optString("rewrite_preamble")

    /** #1418 Drafter: turns an incoming message into a suggested reply (the keyboard's Draft Answer, #1384). */
    val draftPreamble: String get() = registry.optString("draft_preamble")

    /** Prepended to every summary prompt, and deliberately NOT [rewritePreamble]. */
    val summaryPreamble: String get() = registry.optString("summary_preamble")

    /** Prepended to every Translate prompt, and deliberately NOT [rewritePreamble]. */
    val translatePreamble: String get() = registry.optString("translate_prompt")

    fun provider(id: String?): Provider =
        providers.firstOrNull { it.id == id } ?: providers.first { it.id == defaultProvider }

    private fun styleOf(set: List<Style>, id: String?, fallback: String): Style =
        set.firstOrNull { it.id == id } ?: set.first { it.id == fallback }

    fun style(id: String?): Style = styleOf(styles, id, defaultStyle)
    fun tone(id: String?): Style = styleOf(tones, id, defaultTone)
    fun length(id: String?): Style = styleOf(lengths, id, defaultLength)
    fun language(id: String?): Style = styleOf(languages, id, defaultLanguage)
    fun summary(id: String?): Style = styleOf(summaries, id, defaultSummary)

    /**
     * The Grammar Check style, or NULL when this registry does not carry one.
     *
     * Null rather than a fallback, and [style] is deliberately not used here — see
     * [GRAMMAR_STYLE_ID]. Every other resolver in this file falls back, because every other one
     * answers "which of these did the owner pick"; this one answers "does the tool exist at all",
     * and a fallback would turn a missing tool into a wrong one.
     */
    val grammarStyle: Style? get() = styles.firstOrNull { it.id == GRAMMAR_STYLE_ID }

    /**
     * The system prompt an Enhance run sends: the preamble, the chosen style, then the tone, the
     * length and the output language pinned on this application's own page.
     *
     * COMPOSED HERE, IN THIS APPLICATION, and sent whole. The engine appends nothing — if it did,
     * the serving application's own preamble and style would leak back into cloud-writer's
     * rewrites and the two would share a setting again through the back door. The "keep" entries
     * carry an empty prompt, so leaving all three alone reproduces the plain style prompt.
     */
    fun enhancePrompt(styleId: String?, toneId: String?, lengthId: String?, languageId: String?): String =
        listOf(
            rewritePreamble,
            style(styleId).prompt,
            tone(toneId).prompt,
            length(lengthId).prompt,
            language(languageId).prompt,
        ).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * The system prompt Grammar Check sends: the preamble and the grammar style ALONE.
     *
     * No tone, no length, no output language, and that is the difference between this tool and
     * Enhance rather than an omission. Grammar Check promises to correct what is wrong and change
     * nothing else; appending "make it formal" or "make it one paragraph" to that prompt would
     * make it a rewrite wearing a grammar label.
     *
     * Null when [grammarStyle] is null, so the caller has something to refuse on.
     */
    /**
     * #1418 the system prompt the Drafter sends: [draftPreamble], then the SAME tone, length and
     * output language rows as Enhance, so the Drafter's tools config is the Enhancer's.
     */
    fun draftPrompt(toneId: String?, lengthId: String?, languageId: String?): String =
        listOf(draftPreamble, tone(toneId).prompt, length(lengthId).prompt, language(languageId).prompt)
            .filter { it.isNotBlank() }.joinToString(" ")

    fun grammarPrompt(): String? {
        val grammar = grammarStyle ?: return null
        return listOf(rewritePreamble, grammar.prompt).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * The system prompt Translate sends: [translatePreamble] and the chosen output language ALONE,
     * or NULL when no output language has been chosen.
     *
     * NOT [enhancePrompt] with a language pinned onto it. [translatePreamble] forbids every
     * improvement [rewritePreamble] invites, and that is the whole difference between "the same
     * text in another language" and "a better text in another language" — the second is what
     * Enhance is for, and an owner who pressed Translate did not ask for it.
     *
     * Null rather than a fallback target, for the reason [grammarPrompt] is null. "Keep my
     * language" is a real answer to the Language Output question and it is not a language to
     * translate into; guessing English there would translate a Spanish note the owner wanted left
     * alone. The caller refuses on the null and names the row to set.
     */
    fun translatePrompt(languageId: String?): String? {
        val target = language(languageId).prompt.ifBlank { return null }
        return listOf(translatePreamble, target).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * The system prompt a Resume run sends: [summaryPreamble] and the chosen summary prompt.
     *
     * No tone, no length, no output language. Those three shape a REWRITE of the user's own words
     * and applied to a summary they fight the summary prompt's own length instruction; the output
     * language is decided by the summary preamble instead.
     */
    fun summaryPrompt(summaryId: String?): String =
        listOf(summaryPreamble, summary(summaryId).prompt).filter { it.isNotBlank() }.joinToString(" ")
}
