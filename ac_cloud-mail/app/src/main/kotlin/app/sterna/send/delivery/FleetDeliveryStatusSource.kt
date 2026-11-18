package app.sterna.send.delivery

import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouteInterceptor
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.FleetBearerInterceptor
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1802: the fleet mail API's per-message delivery status (cloudy-apps-communication-mcp,
 * `GET /v1/sent/{messageId}/status`). Addressing follows the fleet rule: the WireGuard mesh first
 * (no bearer), the public MCP hub path as fallback with the fleet bearer in `X-Fleet-Authorization`,
 * both through the shared [EndpointRouter] / [FleetBearerInterceptor], so the bearer never reaches
 * the mesh host and is never logged here.
 *
 * 200 -> the parsed report; 404 `not_found` (or `found:false`) -> null (no record: every server
 * stage stays pending); anything else throws, so the cache keeps its last good answer.
 */
class FleetDeliveryStatusSource(
    bearers: FleetBearers,
    router: EndpointRouter = EndpointRouter(),
    base: OkHttpClient = OkHttpClient(),
    private val publicBase: String = PUBLIC_BASE,
    meshBase: String = MESH_BASE,
) : DeliveryStatusSource {

    private val client: OkHttpClient = base.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(EndpointRouteInterceptor(router))
        .addNetworkInterceptor(FleetBearerInterceptor(router, bearers))
        .build()

    init {
        router.set(publicBase, meshBase, EndpointPolicy.MESH_FIRST)
    }

    override suspend fun fetch(key: DeliveryKey): ServerDeliveryReport? = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(key.messageId.trim(), "UTF-8").replace("+", "%20")
        val account = key.accountAddress?.takeIf { it.isNotBlank() }
            ?.let { "?account=" + URLEncoder.encode(it.trim(), "UTF-8").replace("+", "%20") }.orEmpty()
        val req = Request.Builder().url("${publicBase.trimEnd('/')}/v1/sent/$id/status$account").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 404 -> null
                resp.isSuccessful -> DeliveryStatusJson.parse(JSONObject(body))
                else -> throw IOException("delivery status ${resp.code} ${errorCode(body) ?: ""}".trim())
            }
        }
    }

    private fun errorCode(body: String): String? = runCatching { JSONObject(body).optString("code").ifEmpty { null } }.getOrNull()

    companion object {
        const val MESH_BASE = "https://cloudy-apps-communication-mcp.mesh.diegonmarcos.com"
        const val PUBLIC_BASE = "https://mcp.diegonmarcos.com/cloudy-apps-communication-mcp"
    }
}

/**
 * The wire format -> [ServerDeliveryReport]. Pure, so the mapping is unit-tested. Server stages:
 * `submitted` + `received` (the submission log) -> stage 2; `sender_valid` (with `checks`) ->
 * stage 3; `delivered.recipients[]` -> stage 4. The server's own stage state is the truth: a check
 * is only detail. A recipient the OCI relay accepted (hop `relay`, still pending) stays PENDING
 * with "accepted by relay": the relay's 250 is not delivery.
 */
object DeliveryStatusJson {

    fun parse(o: JSONObject): ServerDeliveryReport? {
        if (o.has("found") && !o.optBoolean("found")) return null
        // The server's own client-shaped fields (submission / sender / recipients) win; `stages{}` is the fallback.
        if (o.has("submission") || o.has("sender") || o.has("recipients")) return flat(o)
        val stages = o.optJSONObject("stages") ?: return ServerDeliveryReport()
        return ServerDeliveryReport(
            submission = submission(o, stages.optJSONObject("submitted"), stages.optJSONObject("received")),
            sender = sender(stages.optJSONObject("sender_valid")),
            recipients = recipients(stages.optJSONObject("delivered")),
        )
    }

    private fun flat(o: JSONObject): ServerDeliveryReport {
        val sub = o.optJSONObject("submission")?.let { s ->
            if (s.isNull("accepted")) null else ServerDeliveryReport.Submission(
                accepted = s.optBoolean("accepted"),
                atMillis = at(s),
                smtpCode = int(s, "smtp_code"),
                messageId = str(s, "message_id") ?: str(o, "messageId"),
                detail = str(s, "text"),
            )
        }
        val sender = o.optJSONObject("sender")?.let { s ->
            ServerDeliveryReport.SenderAuth(
                accountMayUseFrom = bool(s, "may_use_from"),
                dkimSigned = bool(s, "dkim_signed"),
                spfAligned = bool(s, "spf_aligned"),
                dmarcAligned = bool(s, "dmarc_aligned"),
                atMillis = at(s),
                dkimSelector = str(s, "dkim_selector"),
                detail = str(s, "text"),
                spfGating = false,
            )
        }
        val arr = o.optJSONArray("recipients") ?: JSONArray()
        val rcpts = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            RecipientDelivery(
                address = str(r, "address") ?: str(r, "rcpt") ?: return@mapNotNull null,
                state = when (r.optString("state").lowercase()) {
                    "delivered", "ok" -> RecipientState.DELIVERED
                    "bounced", "failed" -> RecipientState.BOUNCED
                    "deferred" -> RecipientState.DEFERRED
                    else -> RecipientState.PENDING
                },
                atMillis = at(r),
                smtpCode = int(r, "smtp_code"),
                remoteMx = str(r, "remote_mx") ?: str(r, "host"),
                detail = str(r, "detail") ?: reason(r),
                hop = str(r, "hop"),
            )
        }
        return ServerDeliveryReport(sub, sender, rcpts)
    }

    private fun bool(o: JSONObject, k: String): Boolean? = if (!o.has(k) || o.isNull(k)) null else o.optBoolean(k)

    private fun int(o: JSONObject, k: String): Int? = if (!o.has(k) || o.isNull(k)) null else (o.opt(k) as? Number)?.toInt()

    private fun submission(o: JSONObject, submitted: JSONObject?, received: JSONObject?): ServerDeliveryReport.Submission? {
        val failed = listOfNotNull(submitted, received).firstOrNull { state(it) == "failed" }
        val messageId = str(o, "messageId")
        return when {
            failed != null -> ServerDeliveryReport.Submission(
                accepted = false, atMillis = at(failed), smtpCode = smtpCode(failed), messageId = messageId, detail = reason(failed),
            )
            received != null && state(received) == "ok" -> ServerDeliveryReport.Submission(
                accepted = true, atMillis = at(received), smtpCode = 250, messageId = messageId,
                detail = (str(received, "queueId") ?: str(o, "queueId"))?.let { "queue $it" },
            )
            else -> null
        }
    }

    private fun sender(s: JSONObject?): ServerDeliveryReport.SenderAuth? {
        if (s == null) return null
        val checks = s.optJSONObject("checks")
        val from = check(checks, "from_authorized")
        val dkim = check(checks, "dkim")
        val spf = check(checks, "spf")
        val dmarc = check(checks, "dmarc")
        val selector = checks?.optJSONObject("dkim")?.let { str(it, "selector") }
        return when (state(s)) {
            "ok" -> ServerDeliveryReport.SenderAuth(
                // The server judged the stage ok: its gating checks passed. SPF is reported, not gating.
                accountMayUseFrom = from ?: true, dkimSigned = dkim ?: true, spfAligned = spf, dmarcAligned = dmarc ?: true,
                spfGating = false, atMillis = at(s), dkimSelector = selector, detail = reason(s),
            )
            "failed" -> ServerDeliveryReport.SenderAuth(
                accountMayUseFrom = from, dkimSigned = dkim, spfAligned = spf, dmarcAligned = dmarc,
                spfGating = false, atMillis = at(s), dkimSelector = selector, detail = reason(s), serverFailed = true,
            )
            else -> null
        }
    }

    private fun recipients(d: JSONObject?): List<RecipientDelivery> {
        val arr: JSONArray = d?.optJSONArray("recipients") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val hop = str(r, "hop")
            val reason = reason(r)
            val state = when (r.optString("state").lowercase()) {
                "ok", "delivered" -> RecipientState.DELIVERED
                "failed", "bounced" -> RecipientState.BOUNCED
                "deferred" -> RecipientState.DEFERRED
                // Still in the outbound queue with the remote's reason: a deferral, retrying.
                else -> if (hop == "queue" && reason != null) RecipientState.DEFERRED else RecipientState.PENDING
            }
            RecipientDelivery(
                address = str(r, "rcpt") ?: str(r, "address") ?: return@mapNotNull null,
                state = state,
                atMillis = at(r),
                smtpCode = smtpCode(r),
                remoteMx = str(r, "host"),
                detail = reason,
                hop = hop,
            )
        }
    }

    private fun state(o: JSONObject): String = o.optString("state").lowercase()

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifBlank { null }

    /** `reason` as text, or an object's `text`/`message`. */
    private fun reason(o: JSONObject): String? = when (val r = o.opt("reason")) {
        null, JSONObject.NULL -> null
        is JSONObject -> (str(r, "text") ?: str(r, "message") ?: r.toString())
        else -> r.toString().ifBlank { null }
    }

    private fun smtpCode(o: JSONObject): Int? {
        (o.opt("code") as? Number)?.let { return it.toInt() }
        (o.opt("reason") as? JSONObject)?.opt("code")?.let { c -> (c as? Number)?.toInt()?.let { return it } }
        return reason(o)?.let { Regex("""\b([245]\d\d)\b""").find(it)?.groupValues?.get(1)?.toInt() }
    }

    /** `at`: ISO-8601, or epoch seconds / milliseconds. */
    private fun at(o: JSONObject): Long? = when (val v = o.opt("at")) {
        is Number -> v.toLong().let { if (it < 100_000_000_000L) it * 1000 else it }
        is String -> runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
        else -> null
    }

    /** A check is a boolean, a word (pass/ok/fail/none), or an object with one of those. */
    private fun check(checks: JSONObject?, k: String): Boolean? {
        val v = checks?.opt(k) ?: return null
        return when (v) {
            is Boolean -> v
            is String -> word(v)
            is JSONObject -> (v.opt("pass") as? Boolean) ?: (v.opt("ok") as? Boolean)
                ?: word(v.optString("result").ifEmpty { v.optString("state") })
            else -> null
        }
    }

    private fun word(s: String): Boolean? = when (s.lowercase()) {
        "pass", "ok", "true", "aligned", "signed", "yes" -> true
        "fail", "failed", "false", "none", "softfail", "permerror", "no" -> false
        else -> null
    }
}
