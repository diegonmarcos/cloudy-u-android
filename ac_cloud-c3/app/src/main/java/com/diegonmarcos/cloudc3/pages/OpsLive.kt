package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #1480 The live answers behind Ops > Health and Ops > Observability, text in, models out (the JVM suite runs the exact code the
 * phone runs): c3-infra-api GET /db/uptime/report (uptime per VM), GET /reports/status (freshness of each published report) and
 * GET /logs/search (the log index, filtered). A failure is a note on the page, never an empty list that reads as "all quiet".
 */
object OpsLive {

    const val UPTIME_PATH = "/db/uptime/report"
    const val REPORTS_PATH = "/reports/status"

    data class Uptime(val vm: String, val percent: Double, val checks: Int)
    data class Report(val family: String, val ok: Boolean, val ageHours: Int?, val stale: Boolean, val error: String)
    data class LogLine(val ts: String, val vm: String, val container: String, val level: String, val text: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun parseUptime(body: String): List<Uptime>? {
        val a = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        return a.mapNotNull { it as? JsonObject }.map {
            Uptime(it.str("vm"), (it["uptimePercent"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, (it["checks"] as? JsonPrimitive)?.intOrNull ?: 0)
        }.sortedBy { it.percent }
    }

    fun parseReports(body: String): List<Report>? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return o.arr("families").map {
            Report(it.str("family"), (it["ok"] as? JsonPrimitive)?.contentOrNull == "true", (it["ageHours"] as? JsonPrimitive)?.intOrNull,
                (it["stale"] as? JsonPrimitive)?.contentOrNull == "true", it.str("error").ifBlank { if (it.str("ok") == "true") "" else "HTTP ${it.str("status")}" })
        }.sortedWith(compareBy({ it.ok && !it.stale }, { it.family }))
    }

    /** The search route with the filters it understands; the VM filter is applied to the answer ([byVm]), the API has none. */
    fun logsPath(q: String, service: String, level: String, limit: Int): String =
        "/logs/search?limit=$limit" + listOf("q" to q, "service" to service, "level" to level).filter { it.second.isNotBlank() }
            .joinToString("") { (k, v) -> "&$k=" + java.net.URLEncoder.encode(v, "UTF-8") }

    fun parseLogs(body: String): List<LogLine>? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return o.arr("lines").map { LogLine(it.str("ts"), it.str("vm"), it.str("container"), it.str("level"), it.str("line")) }
    }

    /** Lines of one VM (its id or alias); null keeps all. Newest first. */
    fun byVm(lines: List<LogLine>, vmNames: Set<String>?): List<LogLine> =
        lines.filter { vmNames == null || it.vm in vmNames }.sortedByDescending { it.ts }
}
