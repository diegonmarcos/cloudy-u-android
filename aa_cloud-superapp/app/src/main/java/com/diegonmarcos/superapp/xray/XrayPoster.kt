package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.notificationcenter.AiUsageClient
import org.json.JSONObject
import java.io.IOException

/**
 * #1504 Post to the C3 API, mesh first then public with the fleet bearer (the #1499 client pattern: [AiUsageClient.Transport] seams).
 * Order: `POST /xray/report` on cloudy-c3-api, then on its alias c3-infra-api; if neither has the route yet (404) the report goes to
 * the existing generic app-log route `POST /apk/cloud-superapp/logs` (kind probe, the report in `log`, the schema in `meta`).
 * The bearer is never logged or put in a message.
 */
object XrayPoster {

    const val ROUTE = "/xray/report"
    const val FALLBACK_ROUTE = "/apk/cloud-superapp/logs"
    const val MAX_BODY = 1_900_000

    fun interface PostTransport {
        /** Null when the URL is not this transport's; an [IOException] when the request could not be made. */
        fun post(url: String, headers: Map<String, String>, body: String): AiUsageClient.Reply?
    }

    sealed class Outcome {
        data class Ok(val via: String, val route: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** The body for the new route: the JSON report, bounded (text of the largest sections cut) so it fits the API's body limit. */
    fun body(r: XReport): String {
        var rep = r
        var s = XrayFormat.jsonText(rep)
        var cut = 40_000
        while (s.length > MAX_BODY && cut >= 2_000) {
            rep = rep.copy(sections = rep.sections.map { if (it.text.length > cut) it.copy(text = it.text.take(cut) + "\n…(cut for upload)") else it })
            s = XrayFormat.jsonText(rep); cut /= 2
        }
        return s
    }

    fun fallbackBody(r: XReport): String {
        val md = XrayFormat.markdown(r).let { if (it.length > MAX_BODY - 4_000) it.take(MAX_BODY - 4_000) else it }
        return JSONObject().put("kind", "probe").put("title", "X-Ray report " + r.device + if (r.partial) " (${PARTIAL_LABEL})" else "")
            .put("message", "schema=$XRAY_SCHEMA suspects=${r.suspects.size}").put("log", md)
            .put("meta", JSONObject().put("schema", XRAY_SCHEMA).put("device", r.device).put("version", r.appVersion).put("partial", r.partial)).toString()
    }

    fun post(bases: List<String>, bearer: String, r: XReport, fleet: PostTransport, plain: PostTransport): Outcome {
        var last = "no C3 API endpoint in this build"
        val json = body(r)
        var routeMissing = false
        for (b in bases.map { it.trimEnd('/') }.filter { it.isNotEmpty() }) {
            when (val o = one("$b$ROUTE", bearer, json, fleet, plain)) {
                is Attempt.Ok -> return Outcome.Ok(o.via, ROUTE)
                is Attempt.Missing -> { routeMissing = true; last = o.why }
                is Attempt.Err -> last = o.why
            }
        }
        if (routeMissing) {
            val fb = fallbackBody(r)
            for (b in bases.map { it.trimEnd('/') }.filter { it.isNotEmpty() }) {
                when (val o = one("$b$FALLBACK_ROUTE", bearer, fb, fleet, plain)) {
                    is Attempt.Ok -> return Outcome.Ok(o.via, FALLBACK_ROUTE)
                    is Attempt.Missing -> last = o.why
                    is Attempt.Err -> last = o.why
                }
            }
        }
        return Outcome.Failed(last)
    }

    private sealed class Attempt {
        data class Ok(val via: String) : Attempt()
        data class Missing(val why: String) : Attempt()
        data class Err(val why: String) : Attempt()
    }

    private val JSON_HEADERS = mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to "Cloud-SuperApp-XRay/1")

    private fun one(url: String, bearer: String, body: String, fleet: PostTransport, plain: PostTransport): Attempt {
        var why = ""
        val first = try { fleet.post(url, JSON_HEADERS, body) } catch (e: IOException) { why = "mesh/fleet leg: ${e.javaClass.simpleName}"; null }
        if (first != null) {
            if (first.code in 200..299) return Attempt.Ok("fleet")
            if (first.code == 404) return Attempt.Missing("HTTP 404 (route not live)")
            why = "fleet leg: HTTP ${first.code}"
            if (first.code != 401 && first.code != 403) return Attempt.Err(why)
        }
        if (bearer.isBlank()) return Attempt.Err(if (why.isEmpty()) "no fleet bearer on this device" else "$why; no fleet bearer on this device")
        val second = try { plain.post(url, JSON_HEADERS + ("Authorization" to "Bearer $bearer"), body) } catch (e: IOException) {
            return Attempt.Err("public leg: ${e.javaClass.simpleName}")
        } ?: return Attempt.Err("public leg: unavailable")
        return when {
            second.code in 200..299 -> Attempt.Ok("public")
            second.code == 404 -> Attempt.Missing("HTTP 404 (route not live)")
            else -> Attempt.Err("public leg: HTTP ${second.code}")
        }
    }

    val plainTransport = PostTransport { url, headers, body ->
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 8_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = false; conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val st = if (code in 200..299) conn.inputStream else conn.errorStream
            AiUsageClient.Reply(code, st?.bufferedReader()?.use { it.readText() }.orEmpty().take(4_000))
        } finally { conn.disconnect() }
    }

    val fleetTransport = PostTransport { url, headers, body ->
        com.diegonmarcos.superapp.fleetendpoints.FleetHttp.exchange("POST", url, headers, body, 8_000, 30_000)
            ?.let { AiUsageClient.Reply(it.code, it.body.take(4_000)) }
    }
}
