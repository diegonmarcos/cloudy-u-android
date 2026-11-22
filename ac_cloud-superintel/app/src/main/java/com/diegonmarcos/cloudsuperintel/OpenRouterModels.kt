package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.ai.AiModelEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * #1854 One model with the FULL metadata OpenRouter's GET /api/v1/models carries: id, name, context length, the four prices
 * (prompt, completion, request, image; the raw strings, USD per token for the first two, per request / per image for the others),
 * architecture (modality, tokenizer), top provider (max completion tokens, moderation), supported parameters, created (epoch
 * seconds) and the description. A field the list does not carry is null / empty, never invented.
 */
data class OrModel(
    val id: String, val name: String, val contextLength: Long,
    val pricePrompt: String?, val priceCompletion: String?, val priceRequest: String?, val priceImage: String?,
    val modality: String, val tokenizer: String,
    val maxCompletionTokens: Long?, val isModerated: Boolean?,
    val supportedParameters: List<String>, val created: Long, val description: String,
)

/** One row of a Core's table: the model, and whether OpenRouter's live list carried it (false = only the baked snapshot's fields). */
data class OrRow(val model: OrModel, val live: Boolean)

/** A parsed list and the time it was read. */
data class OrSnapshot(val models: List<OrModel>, val at: Long)

/**
 * The pure side of the OpenRouter metadata table: the parser, the words a cell says, the join with a Core's baked entries.
 * No Android, no network: [OpenRouterStore] holds the disk cache and the refresh rule.
 */
object OpenRouterModels {
    const val URL = "https://openrouter.ai/api/v1/models"

    /** The list is refreshed when the cache is older than this (once a day). */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** OpenRouter's `data` array as models; an entry with no id is skipped, a missing field stays empty. */
    fun parse(json: String): List<OrModel> {
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { m ->
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val a = m.optJSONObject("architecture")
            val p = m.optJSONObject("pricing")
            val tp = m.optJSONObject("top_provider")
            fun price(k: String): String? = p?.takeIf { it.has(k) && !it.isNull(k) }?.optString(k)?.takeIf { it.isNotBlank() }
            OrModel(
                id = id,
                name = m.optString("name", id).ifBlank { id },
                contextLength = m.optLong("context_length"),
                pricePrompt = price("prompt"), priceCompletion = price("completion"), priceRequest = price("request"), priceImage = price("image"),
                modality = a?.optString("modality").orEmpty(), tokenizer = a?.optString("tokenizer").orEmpty(),
                maxCompletionTokens = tp?.takeIf { it.has("max_completion_tokens") && !it.isNull("max_completion_tokens") }?.optLong("max_completion_tokens"),
                isModerated = tp?.takeIf { it.has("is_moderated") && !it.isNull("is_moderated") }?.optBoolean("is_moderated"),
                supportedParameters = m.optJSONArray("supported_parameters")?.strings().orEmpty(),
                created = m.optLong("created"),
                description = m.optString("description"),
            )
        }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    /** "$3" per 1M tokens from a per-token price string ("0.000003"); "free" for 0, "var" for a router's negative price, "-" for none. */
    fun perMillion(perToken: String?): String {
        val v = perToken?.trim()?.toBigDecimalOrNull() ?: return "-"
        if (v.signum() < 0) return "var"
        if (v.signum() == 0) return "free"
        return "$" + v.multiply(BigDecimal(1_000_000)).stripTrailingZeros().toPlainString()
    }

    /** "$0.01" for a flat per-request or per-image price; "free" for 0, "var" for negative, "-" for none. */
    fun flat(price: String?): String {
        val v = price?.trim()?.toBigDecimalOrNull() ?: return "-"
        if (v.signum() < 0) return "var"
        if (v.signum() == 0) return "-"
        return "$" + v.stripTrailingZeros().toPlainString()
    }

    /** "128K" / "1M" / "-" (the catalogue's words). */
    fun tokens(n: Long?): String = when {
        n == null || n <= 0 -> "-"
        n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f", n / 1_000_000.0).removeSuffix(".0") + "M"
        n >= 1000 -> (n / 1000).toString() + "K"
        else -> n.toString()
    }

    /** "2025-03-14" (UTC) from epoch seconds; "-" for none. */
    fun date(createdSec: Long): String = if (createdSec <= 0) "-" else Instant.ofEpochSecond(createdSec).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    /** "yes" / "no" / "-". */
    fun yesNo(b: Boolean?): String = when (b) { true -> "yes"; false -> "no"; null -> "-" }

    /**
     * A Core's table: one row per baked [entries] item, in the Core's order, carrying OpenRouter's live metadata when the live list has the id.
     * An entry the live list lacks keeps the baked fields (id, name, context, modalities) and is marked not live: no field is invented.
     */
    fun join(entries: List<AiModelEntry>, live: List<OrModel>): List<OrRow> {
        val byId = live.associateBy { it.id }
        return entries.map { e ->
            byId[e.id]?.let { OrRow(it, true) } ?: OrRow(
                OrModel(e.id, e.name, e.context, null, null, null, null, e.modality, "", null, null, emptyList(), 0, e.note), false,
            )
        }
    }

    /** The column heads of the table, in order (the UI draws exactly these; the parity test reads them). */
    val COLUMNS = listOf(
        "id", "name", "context_length", "prompt", "completion", "request", "image", "modality", "tokenizer",
        "max_completion_tokens", "is_moderated", "supported_parameters", "created", "description",
    )

    /** The cell of [col] for [m], as the table says it; the one place a field is worded. */
    fun cell(m: OrModel, col: String): String = when (col) {
        "id" -> m.id
        "name" -> m.name
        "context_length" -> tokens(m.contextLength)
        "prompt" -> perMillion(m.pricePrompt)
        "completion" -> perMillion(m.priceCompletion)
        "request" -> flat(m.priceRequest)
        "image" -> flat(m.priceImage)
        "modality" -> m.modality.ifBlank { "-" }
        "tokenizer" -> m.tokenizer.ifBlank { "-" }
        "max_completion_tokens" -> tokens(m.maxCompletionTokens)
        "is_moderated" -> yesNo(m.isModerated)
        "supported_parameters" -> m.supportedParameters.joinToString(", ").ifBlank { "-" }
        "created" -> date(m.created)
        "description" -> m.description.ifBlank { "-" }
        else -> "-"
    }
}

/**
 * The on-disk cache of OpenRouter's model list and its refresh rule: [get] answers the cached list, fetching first when the cache is
 * older than [OpenRouterModels.MAX_AGE_MS] (or [force]d: the Refresh button); a failed fetch keeps the old cache. [fetch] is the
 * network door (the host passes libs:ai's public fetch, a test a fake); the clock is injected for the same reason.
 */
class OpenRouterStore(private val dir: File, private val fetch: (String) -> String?, private val clock: () -> Long = System::currentTimeMillis) {
    private val body = File(dir, "openrouter-models.json")
    private val stamp = File(dir, "openrouter-models.at")

    /** The cached list, or null when none was ever stored (or it does not parse). */
    fun cached(): OrSnapshot? {
        if (!body.isFile || !stamp.isFile) return null
        val at = stamp.readText().trim().toLongOrNull() ?: return null
        val models = OpenRouterModels.parse(body.readText())
        return if (models.isEmpty()) null else OrSnapshot(models, at)
    }

    /** True when there is no cache, or it is older than the daily window (a clock set back counts as stale). */
    fun stale(): Boolean {
        val c = cached() ?: return true
        val age = clock() - c.at
        return age < 0 || age >= OpenRouterModels.MAX_AGE_MS
    }

    /** One fetch now; the new list replaces the cache only when it parses to at least one model. Null = it failed (the old cache stays). */
    fun refresh(): OrSnapshot? {
        val text = runCatching { fetch(OpenRouterModels.URL) }.getOrNull() ?: return null
        val models = OpenRouterModels.parse(text)
        if (models.isEmpty()) return null
        val now = clock()
        dir.mkdirs()
        // The body first, the stamp last: a half-written pair reads as the old stamp over a new body, which parses and is only refreshed sooner.
        body.writeText(text)
        stamp.writeText(now.toString())
        return OrSnapshot(models, now)
    }

    /** The list to show: refreshed when stale or [force]d, else the cache; a failed refresh falls back to the cache. */
    fun get(force: Boolean = false): OrSnapshot? = if (force || stale()) refresh() ?: cached() else cached()
}
