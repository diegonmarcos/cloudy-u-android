// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.util.Base64
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SuperApp addition — THE shared LLM call path behind Settings → "AI Model Routing".
 *
 * Registry = build.json::keyboard_ai, baked by libs/keyboard/build.gradle into
 * BuildConfig.AI_ROUTING_B64 (providers, model lists, enhancement prompts). User
 * choices live in the keyboard prefs: PREF_AI_PROVIDER, PREF_AI_TOKEN_PREFIX+provider,
 * PREF_AI_MODEL_PREFIX+provider — the same SharedPreferences the LanguageTool URL and
 * every other keyboard setting use (there is no other keyboard secret store).
 *
 * Every provider speaks the OpenAI chat-completions shape (the mesh bridge
 * my-ai_claude-api mimics it, OpenRouter is it), so [complete] is one POST.
 * Consumers: [TextEnhancer] (ENHANCE toolbar key), [GrammarChecker] mode "ai",
 * and translation once it opts in. Blocking — never call on the IME main thread.
 */
object AiRouter {
    /**
     * A model's price, in UNITED STATES DOLLARS PER MILLION TOKENS, which is the unit the routing
     * table displays unchanged. The names carry the unit because this number reaches the screen
     * through two different paths — the baked registry and the live catalog — and a bare "prompt"
     * gave neither path anywhere to state what it was handing over. OpenRouter publishes dollars
     * per TOKEN, so [refreshPricing] scales by a million on the way in; the registry is authored
     * in this unit already.
     */
    class Pricing(val promptUsdPerMillionTokens: Double, val completionUsdPerMillionTokens: Double)
    /**
     * One row of the routing table. [id] is the provider's exact model id — the string a request
     * is sent with and the one the prefs store, so it is the only field routing ever resolves by.
     *
     * [paramsB], [quant] and [trainedFor] are null/empty whenever the registry has nothing to say:
     * OpenRouter publishes no parameter count at all (size is only ever read off the id), serves
     * closed models without declaring a quantisation, and ranks only some models by category. The
     * table prints an explicit unknown marker for those rather than inventing a value.
     * [baked] = registry fallback price (null when the registry has none, e.g. the mesh bridge).
     */
    class Model(
        val id: String,
        val name: String,
        val open: Boolean,
        val paramsB: Int?,
        val quant: List<String>,
        val trainedFor: List<String>,
        val note: String?,
        val baked: Pricing?,
    )
    class Provider(val id: String, val label: String, val url: String, val needsToken: Boolean,
                   val defaultModel: String, val models: List<Model>,
                   val catalogUrl: String?, val pricingAsOf: String?)
    /**
     * One named prompt out of the registry. [bullets] marks a prompt that asked the model for a
     * list, and it is the flag [enforceBullets] keys off; it is registry data rather than something
     * guessed from the prompt wording, because whether a prompt wants bullets is a fact its author
     * knows and a regex over English only estimates.
     */
    class Style(val id: String, val label: String, val prompt: String, val bullets: Boolean = false)

    private val registry: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.AI_ROUTING_B64, Base64.DEFAULT), Charsets.UTF_8))
    }
    val providers: List<Provider> by lazy {
        val o = registry.getJSONObject("providers")
        o.keys().asSequence().map { id ->
            val p = o.getJSONObject(id)
            val models = p.getJSONArray("models").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map { m ->
                val pr = m.optDouble("prompt"); val co = m.optDouble("completion")
                val id = m.getString("id")
                Model(
                    id,
                    // A registry row with no short name would render an empty Name column, which
                    // reads as a broken row rather than as a missing field; the id always works.
                    m.optString("name").ifEmpty { id },
                    m.optBoolean("open"),
                    m.optInt("params_b", 0).takeIf { it > 0 },
                    m.stringList("quant"),
                    m.stringList("trained_for"),
                    m.optString("note").ifEmpty { null },
                    if (pr.isNaN() || co.isNaN()) null else Pricing(pr, co),
                )
            }
            Provider(id, p.getString("label"), p.getString("url"), p.optBoolean("needs_token", true),
                p.getString("default_model"), models, p.optString("catalog_url").ifEmpty { null }, p.optString("pricing_as_of").ifEmpty { null })
        }.toList()
    }
    val styles: List<Style> by lazy { promptSet("styles") }
    /** Tone, length and output language are three more system-prompt lines appended after the style. */
    val tones: List<Style> by lazy { promptSet("tones") }
    val lengths: List<Style> by lazy { promptSet("lengths") }
    val languages: List<Style> by lazy { promptSet("languages") }

    /**
     * "AI Resume" / "Text Resume" — SUMMARISE. The owner's product name for condensing a text to
     * its essentials, kept exactly as they spell it. It does NOT mean a curriculum vitae, and it
     * does not mean resuming something that was paused; anyone reading this file later should take
     * the word as a synonym for "summary" and nothing else.
     *
     * Its OWN prompt set rather than three more entries in [styles], because the two are different
     * jobs and the menus must not mix: an Enhance style listed among summaries would silently make
     * "improve this" an option for a feature that promises "shorten this", and a summary prompt in
     * the Enhance list would let the ENHANCE key replace a user's paragraph with a précis of it.
     */
    val summaries: List<Style> by lazy { promptSet("summaries") }

    private fun JSONObject.stringList(key: String): List<String> =
        optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    /** A registry object of id → {label, prompt}. Android's JSONObject keeps insertion order, so this is the menu order too. */
    private fun promptSet(key: String): List<Style> {
        val o = registry.optJSONObject(key) ?: return emptyList()
        return o.keys().asSequence().map { id ->
            val s = o.getJSONObject(id)
            Style(id, s.getString("label"), s.optString("prompt"), s.optBoolean("bullets"))
        }.toList()
    }

    val defaultProvider: String get() = registry.getString("default_provider")
    val defaultStyle: String get() = registry.getString("default_style")
    val defaultTone: String get() = registry.optString("default_tone", "keep")
    val defaultLength: String get() = registry.optString("default_length", "keep")
    val defaultLanguage: String get() = registry.optString("default_language", "keep")
    /**
     * Prepended to every rewrite prompt. Without it the model reads the field text as a message
     * addressed to it and answers it conversationally instead of rewriting it.
     */
    val rewritePreamble: String get() = registry.optString("rewrite_preamble")
    val defaultSummary: String get() = registry.getString("default_summary")

    /**
     * Prepended to every summary prompt, and deliberately not [rewritePreamble]. That one says the
     * input is a field being typed in and asks for a rewrite of about the same length; this one
     * says the input is a whole received email and asks for something shorter, and it refuses
     * instructions found inside that email. A summariser that follows the mail it is summarising
     * is a prompt-injection hole with a friendly name.
     */
    val summaryPreamble: String get() = registry.optString("summary_preamble")

    /** Appended to a summary whose source did not fit [maxChars]; two arguments, sent then total. */
    val summaryTruncatedNote: String get() = registry.optString("summary_truncated_note")

    val timeoutMs: Int get() = registry.optInt("timeout_ms", 30_000)
    /** Input budget of ONE request; longer text is cut into pieces of this size by [TextEnhancer.rewrite]. */
    val maxChars: Int get() = registry.optInt("max_chars", 4096)
    /**
     * Rough width of a token, used only to show the user what a request costs before sending
     * it. No tokenizer ships in the keyboard, and every provider's differs; four characters per
     * token is the usual figure for Latin-script text and the registry can override it.
     */
    val charsPerToken: Int get() = registry.optInt("chars_per_token", 4).coerceAtLeast(1)
    /** An estimate — see [charsPerToken]; shown with a "~" for that reason. */
    fun estimateTokens(text: CharSequence): Int = (text.length + charsPerToken - 1) / charsPerToken
    /**
     * Completion cap sent with every request. Must be set: some OpenRouter upstream providers
     * read a missing max_tokens as "reserve the whole context window for the completion", then
     * refuse the call because window + input overflows the window — an HTTP 400 that lands on
     * whichever request happened to be routed to that provider.
     */
    val maxTokens: Int get() = registry.optInt("max_tokens", 2048)
    /** How long a fetched price catalog stays fresh before the settings screen re-fetches it. */
    val catalogTtlMs: Long get() = registry.optLong("catalog_ttl_ms", 86_400_000L)

    // ---- live pricing: provider catalog → prefs cache {"fetched": epochMs, "prices": {id: [prompt, completion]}} ----

    /** Cached live prices for [p], in dollars per million tokens, with the fetch time; null when never fetched. */
    fun livePricing(context: Context, p: Provider): Pair<Long, Map<String, Pricing>>? {
        val raw = context.prefs().getString(Settings.PREF_AI_PRICING_PREFIX + p.id, null) ?: return null
        return runCatching {
            val o = JSONObject(raw); val prices = o.getJSONObject("prices")
            o.getLong("fetched") to prices.keys().asSequence().associateWith { id ->
                val a = prices.getJSONArray(id); Pricing(a.getDouble(0), a.getDouble(1))
            }
        }.getOrNull()
    }
    fun pricingStale(context: Context, p: Provider): Boolean =
        p.catalogUrl != null && (livePricing(context, p)?.first ?: 0L) + catalogTtlMs < System.currentTimeMillis()
    /** Live price if fetched, else the registry's baked one, else null. */
    fun pricing(context: Context, p: Provider, m: Model): Pricing? = livePricing(context, p)?.second?.get(m.id) ?: m.baked

    /**
     * GET the provider's public model catalog (OpenRouter shape: {"data":[{"id","pricing":{"prompt","completion"}}]},
     * prices in $/token) and cache $/M for the registry's models only. Blocking; throws on failure — the screen
     * keeps showing the baked table then. Never call on the main thread.
     */
    fun refreshPricing(context: Context, p: Provider) {
        val url = p.catalogUrl ?: return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = timeoutMs; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            if (conn.responseCode != 200) throw IllegalStateException("${p.label} catalog HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        val wanted = p.models.map { it.id }.toSet()
        val prices = JSONObject()
        val data = JSONObject(body).getJSONArray("data")
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            if (m.getString("id") !in wanted) continue
            val pr = m.getJSONObject("pricing")
            prices.put(m.getString("id"), JSONArray().put(pr.getString("prompt").toDouble() * 1e6).put(pr.getString("completion").toDouble() * 1e6))
        }
        if (prices.length() == 0) throw IllegalStateException("${p.label} catalog has none of the registry models")
        context.prefs().edit().putString(Settings.PREF_AI_PRICING_PREFIX + p.id,
            JSONObject().put("fetched", System.currentTimeMillis()).put("prices", prices).toString()).apply()
    }

    fun provider(context: Context): Provider {
        val id = context.prefs().getString(Settings.PREF_AI_PROVIDER, defaultProvider) ?: defaultProvider
        return providers.firstOrNull { it.id == id } ?: providers.first { it.id == defaultProvider }
    }
    fun model(context: Context, p: Provider = provider(context)): String =
        context.prefs().getString(Settings.PREF_AI_MODEL_PREFIX + p.id, p.defaultModel)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun token(context: Context, p: Provider = provider(context)): String =
        context.prefs().getString(Settings.PREF_AI_TOKEN_PREFIX + p.id, "")?.trim() ?: ""

    /**
     * WHICH provider and WHICH model one request goes to — the routing decision, split out from
     * the request itself so it can come from somewhere other than this app's preferences.
     *
     * It exists because two applications now hold their own AI Model Routing settings while
     * sharing this one call path. The keyboard's own callers build a route from the keyboard's
     * prefs and behave exactly as they always did; cloud-mail builds one from cloud-mail's prefs
     * and hands it over the binder. Note what is NOT in here: the token. A route names a provider,
     * and the credential for that provider is looked up on this side, every time, from this app's
     * store — so choosing a model has never been a way to reach a key.
     */
    class Route(val provider: Provider, val model: String)

    /** The route this app's own preferences select. */
    fun route(context: Context): Route = provider(context).let { Route(it, model(context, it)) }

    /**
     * The route [providerId]/[modelId] name, falling back to [route] for anything the caller left
     * empty or this build does not know.
     *
     * A CALLER'S UNKNOWN PROVIDER IS NOT AN ERROR HERE. The two apps carry their own copies of the
     * registry and one can be a release behind the other, so a model id this build has never heard
     * of is a routine consequence of shipping them separately. Refusing the call would turn every
     * such skew into a dead feature; falling back to the configured route keeps it working and
     * costs the caller its preference, which the caller can see and change.
     */
    fun routeOf(context: Context, providerId: String?, modelId: String?): Route {
        val p = providers.firstOrNull { it.id == providerId } ?: return route(context)
        return Route(p, modelId?.takeIf { it.isNotBlank() } ?: model(context, p))
    }
    fun style(context: Context): Style {
        val id = context.prefs().getString(Settings.PREF_ENHANCE_STYLE, defaultStyle) ?: defaultStyle
        return styles.firstOrNull { it.id == id } ?: styles.first { it.id == defaultStyle }
    }
    fun styleById(id: String): Style =
        compose(styles.firstOrNull { it.id == id } ?: styles.first { it.id == defaultStyle }, emptyList())

    /**
     * The summary prompt the user pinned in Text Resume, composed with [summaryPreamble].
     *
     * No tone, no length, no output language: those three exist to shape a REWRITE of the user's
     * own words, and applied to a summary they fight the summary prompt's own length instruction.
     * The output language is decided by the summary preamble instead — follow the email's.
     */
    fun summaryStyle(context: Context): Style {
        val id = context.prefs().getString(Settings.PREF_SUMMARY_STYLE, defaultSummary) ?: defaultSummary
        return summaryById(id)
    }

    /** One named summary prompt, composed with [summaryPreamble]; falls back to [defaultSummary]. */
    fun summaryById(id: String): Style {
        val chosen = summaries.firstOrNull { it.id == id } ?: summaries.first { it.id == defaultSummary }
        val lines = listOf(summaryPreamble, chosen.prompt).filter { it.isNotBlank() }
        return Style(chosen.id, chosen.label, lines.joinToString(" "), chosen.bullets)
    }

    /**
     * THE summariser. Both Text Resume in the keyboard and AI Resume in cloud-mail end up here —
     * the keyboard's bar calls it directly, cloud-mail calls it across the binder through
     * TextToolsService.summarise — so the two applications cannot summarise differently.
     *
     * ONE request, never [TextEnhancer.rewrite]. rewrite() splits input past the provider's budget
     * and rejoins the answers, which is right for a rewrite and exactly wrong here: it yields a
     * summary PER PIECE, concatenated, i.e. something longer than the text it came from. Input past
     * [maxChars] is cut instead, and the cut is STATED in the reply — a summary of the first half of
     * a message, handed over as a summary of the message, is worse than no summary.
     *
     * Blocking; throws whatever [complete] throws. Never call on the main thread.
     */
    fun summarise(context: Context, style: Style, text: String, route: Route = route(context)): String {
        val sent = text.take(maxChars)
        val reply = complete(context, style.prompt, sent, route)
        val shaped = if (style.bullets) enforceBullets(reply) else reply
        if (sent.length == text.length) return shaped
        return shaped + "\n\n" + String.format(summaryTruncatedNote, sent.length, text.length)
    }

    /** Canonical bullet opener; the bullet prompts name this exact string, so the two must agree. */
    val summaryBulletMarker: String get() = registry.optString("summary_bullet_marker", "- ")

    /** The other openers models reach for. A line starting with one of these IS a bullet. */
    val summaryBulletAliases: List<String> by lazy { registry.stringList("summary_bullet_aliases") }

    /** Appended when a reply that was asked for bullets contains no list at all. */
    val summaryNotBulletsNote: String get() = registry.optString("summary_not_bullets_note")

    /**
     * Numbered openers, matched rather than listed: there are a hundred of them and a model
     * generates them, so no registry list would ever be complete.
     */
    private val numberedBullet = Regex("""^\d{1,2}[.)]\s+""")

    /**
     * Hold the model to the shape the prompt asked for, because asking is not the same as getting.
     *
     * A line opening with any known bullet marker is rewritten to the canonical one: that changes
     * punctuation, not content, and it is what makes "•" and "1." and "-" one shape to whatever
     * reads the summary next.
     *
     * When NOTHING in the reply is a list the model wrote prose, and the reply is returned exactly
     * as it wrote it with [summaryNotBulletsNote] appended. Splitting that paragraph into bullets
     * here would invent a division of the facts that no model proposed and no reader could check —
     * and a summariser that silently hands back a paragraph it was told not to write is the failure
     * this function exists to make visible.
     *
     * ponytail: a stray heading above real bullets is left where the model put it. It is the
     * model's own words and dropping lines is a bigger risk than an untidy first line; strip it if
     * anyone ever minds.
     */
    fun enforceBullets(reply: String): String {
        val marker = summaryBulletMarker
        if (marker.isEmpty()) return reply
        val aliases = summaryBulletAliases
        val lines = reply.lines().map { line ->
            val body = line.trimStart()
            when {
                body.isEmpty() -> line
                body.startsWith(marker) -> body
                else -> {
                    val alias = aliases.firstOrNull { body.startsWith(it) }
                    val numbered = if (alias == null) numberedBullet.find(body) else null
                    when {
                        alias != null -> marker + body.removePrefix(alias).trimStart()
                        numbered != null -> marker + body.removeRange(numbered.range).trimStart()
                        else -> line
                    }
                }
            }
        }
        if (lines.none { it.startsWith(marker) }) return reply.trimEnd() + "\n\n" + summaryNotBulletsNote
        return lines.joinToString("\n").trim()
    }

    /** One system prompt out of the shared preamble, the style, and whatever [extra] lines were pinned. */
    private fun compose(style: Style, extra: List<String>): Style =
        Style(style.id, style.label, AiPrompt.join(rewritePreamble, style.prompt, extra))

    /** build.json::keyboard_ai.answer_preamble, else [DraftAnswer.DEFAULT_PREAMBLE]. */
    val answerPreamble: String get() = registry.optString("answer_preamble", DraftAnswer.DEFAULT_PREAMBLE)

    /**
     * What the Draft Answer panel sends: the answer preamble and "draft a reply to this message" in place of
     * the rewrite preamble and style, then the SAME tone / length / language lines Text Enhancement uses,
     * read from Draft Answer's own three preferences.
     */
    fun answerStyle(context: Context): Style = Style("answer", "Draft answer", DraftAnswer.systemPrompt(answerPreamble,
        listOfNotNull(
            pick(context, tones, Settings.PREF_ANSWER_TONE, defaultTone),
            pick(context, lengths, Settings.PREF_ANSWER_LENGTH, defaultLength),
            pick(context, languages, Settings.PREF_ANSWER_LANGUAGE, defaultLanguage),
        )))

    /**
     * What the ENHANCE toolbar key actually sends: the chosen [style] prompt, then the tone, the
     * length and the output language the user pinned in Settings → Text Enhancements. The "keep"
     * entries carry an empty prompt, so leaving all three alone reproduces the plain style prompt.
     *
     * Returned as a [Style] so callers keep the id and label for logging. GrammarChecker
     * deliberately does NOT go through here (fixing grammar must not restyle, resize or translate
     * the text) — it goes through [styleById], which shares only the preamble.
     */
    fun enhanceStyle(context: Context, base: Style = style(context)): Style {
        return compose(
            base,
            listOfNotNull(
                pick(context, tones, Settings.PREF_ENHANCE_TONE, defaultTone),
                pick(context, lengths, Settings.PREF_ENHANCE_LENGTH, defaultLength),
                pick(context, languages, Settings.PREF_ENHANCE_LANGUAGE, defaultLanguage),
            ),
        )
    }

    /** The prompt line the user pinned under [key], or null when it is "keep" or no longer in the registry. */
    private fun pick(context: Context, set: List<Style>, key: String, fallback: String): String? {
        val id = context.prefs().getString(key, fallback) ?: fallback
        return set.firstOrNull { it.id == id }?.prompt?.takeIf { it.isNotBlank() }
    }

    /** Thrown when the selected provider needs a key and none is set — the settings screen is the fix. */
    class NoTokenException(val provider: Provider) : IllegalStateException("no API key for ${provider.label}")

    /**
     * One chat completion: [system] instructions + [user] text → assistant text.
     * Throws on any failure (no token, HTTP != 200, timeout, malformed reply); callers
     * turn the exception into a visible message. Never call on the main thread.
     */
    @JvmStatic
    fun complete(context: Context, system: String, user: String): String =
        complete(context, system, user, route(context))

    /** As above, to a route the caller chose. The token is still read HERE, from this app's store. */
    @JvmStatic
    fun complete(context: Context, system: String, user: String, route: Route): String {
        val p = route.provider
        val token = token(context, p)
        if (p.needsToken && token.isEmpty()) throw NoTokenException(p)
        val body = JSONObject()
            .put("model", route.model)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString()
        val conn = (URL(p.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // The mesh bridge can take long (its own limit is 180 s); the keyboard caps
            // far lower so a stuck call never leaves the user staring at "Enhancing…".
            connectTimeout = 5000
            readTimeout = timeoutMs
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val error = runCatching { JSONObject(err ?: "").getJSONObject("error") }.getOrNull()
                val msg = error?.optString("message")?.ifBlank { null }
                // "Provider returned error" is OpenRouter saying the upstream model refused —
                // useless on its own, and it was all the user ever saw. The why lives in
                // error.metadata (provider_name + raw). Name the model too: the same failure
                // reads completely differently depending on which one was routed to.
                val detail = error?.optJSONObject("metadata")?.let { meta ->
                    listOfNotNull(
                        meta.optString("provider_name").ifBlank { null },
                        meta.opt("raw")?.toString()?.take(300)?.ifBlank { null },
                    ).joinToString(" — ").ifBlank { null }
                }
                throw IllegalStateException(
                    "${p.label} HTTP $code${msg?.let { ": $it" } ?: ""}" +
                        "${detail?.let { " ($it)" } ?: ""} [model ${route.model}]"
                )
            }
            val reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val choice = reply.getJSONArray("choices").getJSONObject(0)
            // A reply that hit max_tokens is cut mid-text. Applied, it would replace the whole
            // field with a fragment — the one outcome worse than no rewrite — so it is a failure.
            if (choice.optString("finish_reason") == "length")
                throw IllegalStateException("${p.label} cut the reply off at $maxTokens tokens — enhance a shorter selection [model ${route.model}]")
            return choice.getJSONObject("message").getString("content").trim()
        } finally {
            conn.disconnect()
        }
    }
}
