package com.diegonmarcos.superapp.notificationcenter

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1151 One read of c3-infra-api `GET /ai/usage`, mesh first and then the public address with the fleet bearer, like every
 * fleet endpoint. Two legs behind two seams so the order is unit-tested without a network:
 *
 *  1. [fleet]: the app's fleet transport (libs:fleet-endpoints) - a registered fleet host is tried on the mesh and falls back
 *     to its public URL with the fleet bearer on its own. It answers null when the host is not a registered fleet endpoint.
 *  2. [plain]: the public URL with `Authorization: Bearer <fleet bearer>` held by this device (Cloud Account's fleet.bearer,
 *     else the Dagu login). Used when the first leg is not this transport's, cannot be made, or is refused (401/403).
 *
 * The bearer is never logged and never part of a message.
 */
object AiUsageClient {

    data class Reply(val code: Int, val body: String)

    fun interface Transport {
        /** Null when the URL is not this transport's; an [IOException] when the request could not be made. */
        fun get(url: String, headers: Map<String, String>): Reply?
    }

    sealed class Outcome {
        data class Ok(val body: String, val via: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private val ACCEPT = mapOf("Accept" to "application/json", "User-Agent" to "Cloud-SuperApp-AiBadge/1")

    /** The one route every reader of the usage table calls (#1499: the SuperApp badge and SuperIntel's Cost page). */
    const val PATH = "/ai/usage"

    /** The first of [bases] (the new API name, then its alias) that answers; the last failure when none does. */
    fun fetchAny(bases: List<String>, bearer: String, fleet: Transport, plain: Transport): Outcome {
        var last: Outcome = Outcome.Failed("no ops endpoint in this build")
        for (b in bases) { last = fetch(b, bearer, fleet, plain); if (last is Outcome.Ok) return last }
        return last
    }

    fun fetch(baseUrl: String, bearer: String, fleet: Transport, plain: Transport): Outcome {
        val base = baseUrl.trimEnd('/')
        if (base.isEmpty()) return Outcome.Failed("no ops endpoint in this build")
        val url = "$base$PATH"
        var why = ""

        val first = try { fleet.get(url, ACCEPT) } catch (e: IOException) { why = "mesh/fleet leg: ${e.javaClass.simpleName}"; null }
        if (first != null) {
            if (first.code in 200..299) return Outcome.Ok(first.body, "fleet")
            why = "fleet leg: HTTP ${first.code}"
            if (first.code != 401 && first.code != 403) return Outcome.Failed(why)
        }

        if (bearer.isBlank()) return Outcome.Failed(if (why.isEmpty()) "no fleet bearer on this device" else "$why; no fleet bearer on this device")
        val second: Reply? = try {
            plain.get(url, ACCEPT + ("Authorization" to "Bearer $bearer"))
        } catch (e: IOException) {
            return Outcome.Failed("public leg: ${e.javaClass.simpleName}")
        }
        if (second == null) return Outcome.Failed("public leg: unavailable")
        return if (second.code in 200..299) Outcome.Ok(second.body, "public")
        else Outcome.Failed("public leg: HTTP ${second.code}")
    }

    /** Production plain leg: a bounded HttpURLConnection, no redirects (a login page is not the answer). */
    val plainTransport = Transport { url, headers ->
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Reply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(MAX_BODY))
        } finally { conn.disconnect() }
    }

    /** Production fleet leg: libs:fleet-endpoints routes a registered fleet host mesh first, public second. */
    val fleetTransport = Transport { url, headers ->
        com.diegonmarcos.superapp.fleetendpoints.FleetHttp.exchange("GET", url, headers, null, 8_000, 15_000)
            ?.let { Reply(it.code, it.body.take(MAX_BODY)) }
    }

    private const val MAX_BODY = 200_000
}
