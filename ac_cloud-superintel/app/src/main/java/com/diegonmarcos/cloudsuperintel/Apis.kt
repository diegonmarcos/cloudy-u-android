package com.diegonmarcos.cloudsuperintel

import org.json.JSONArray
import org.json.JSONObject

data class ApiLink(val label: String, val url: String)

/** One account or key row. [sample] is true while it is the bundled fake; [tokenMasked] is the only token text this app ever holds. */
data class ApiRow(
    val id: String, val label: String, val provider: String, val login: String, val keyName: String,
    val tokenMasked: String, val created: String, val lastUsed: String, val links: List<ApiLink>, val sample: Boolean,
)

data class ApiConfig(
    val sampleLabel: String, val authority: String, val method: String, val rowsKey: String, val tokenKey: String,
    val caption: String, val caption2: String, val rows: List<ApiRow>,
)

/** The APIs page's model (assets/ai/apis.json): the bundled SAMPLE rows, replaced by the private store's rows at runtime, and the reveal. */
object Apis {
    fun parseRows(a: JSONArray, sample: Boolean): List<ApiRow> = (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        val l = o.optJSONArray("links") ?: JSONArray()
        ApiRow(
            o.getString("id"), o.getString("label"), o.optString("provider"), o.optString("login"), o.optString("key_name"),
            o.optString("token_masked"), o.optString("created"), o.optString("last_used"),
            (0 until l.length()).map { l.getJSONObject(it) }.map { ApiLink(it.getString("label"), it.getString("url")) }, sample,
        )
    }

    fun parse(text: String): ApiConfig {
        val o = JSONObject(text)
        val p = o.getJSONObject("private")
        return ApiConfig(
            o.getString("sample_label"), p.getString("authority"), p.getString("method"), p.getString("rows_key"), p.getString("token_key"),
            o.getString("caption"), o.getString("caption2"), parseRows(o.getJSONArray("rows"), true),
        )
    }

    /** The rows to draw: the private store's when it answers with a list, else the bundled sample. Never a token. */
    fun rows(cfg: ApiConfig, src: PrivateSource): List<ApiRow> =
        src.get(cfg.rowsKey)?.let { runCatching { parseRows(JSONArray(it), false) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: cfg.rows

    /** A caption line from the declared template ({provider}, {login}, {key_name}, {created}, {last_used}). */
    fun line(template: String, r: ApiRow): String = template
        .replace("{provider}", r.provider).replace("{login}", r.login).replace("{key_name}", r.keyName)
        .replace("{created}", r.created).replace("{last_used}", r.lastUsed)

    /** The token of one row, only from the private source and only when asked (a tap); a sample row has none to reveal. */
    fun reveal(cfg: ApiConfig, src: PrivateSource, r: ApiRow): String? =
        if (r.sample) null else src.get(cfg.tokenKey.replace("{id}", r.id))
}
