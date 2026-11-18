package app.sterna.fleet

import app.sterna.ui.compose.CheckOutcome
import app.sterna.ui.compose.CheckSource
import app.sterna.ui.compose.RecipientCheckItem
import app.sterna.ui.compose.RecipientCheckKind
import app.sterna.ui.compose.RecipientCheckRequest
import app.sterna.ui.compose.RecipientChecker
import app.sterna.ui.compose.RecipientStatus
import app.sterna.ui.compose.RecipientVerdict
import com.diegonmarcos.superapp.fleetendpoints.FleetHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * #1803 The fleet client behind [app.sterna.ui.compose.RecipientChecks.checker]: POST /v1/recipients/check on the communication
 * MCP's delivery API (cloudy-u-containers infra-api_cloudy-apps-communication-mcp/DELIVERY-API.md).
 *
 * Addressing: the WG-mesh name first (no bearer there), the public path as the fallback, both through [FleetHttp] so the fleet
 * route and bearer interceptors apply exactly as for every other fleet request. At most [MAX_BATCH] addresses per request;
 * a 429 stops the round and says when to retry; any other error is reported with the server's own code. An address the server
 * did not answer is left out, and the composer shows it as unknown.
 */
object FleetRecipientChecker : RecipientChecker {
    const val MESH_BASE = "https://cloudy-apps-communication-mcp.mesh.diegonmarcos.com"
    const val PUBLIC_BASE = "https://mcp.diegonmarcos.com/cloudy-apps-communication-mcp"
    const val PATH = "/v1/recipients/check"
    const val MAX_BATCH = 50

    private val meshClient by lazy {
        FleetHttp.shared.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    private val publicClient by lazy {
        FleetHttp.shared.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    override suspend fun check(request: RecipientCheckRequest): Map<String, RecipientStatus> {
        val out = LinkedHashMap<String, RecipientStatus>()
        for (batch in request.addresses.chunked(MAX_BATCH)) {
            val body = JSONObject().put("addresses", JSONArray(batch)).toString()
            val (code, text) = post(body)
            if (code !in 200..299) throw IOException(recipientCheckError(code, text))
            out += parseRecipientCheckResponse(text, request.messageSizeBytes)
        }
        return out
    }

    /** The mesh first; on a transport failure there (off the mesh), the public path. */
    private fun post(body: String): Pair<Int, String> {
        val headers = mapOf("Accept" to "application/json")
        return try {
            FleetHttp.call(meshClient, "POST", MESH_BASE + PATH, headers, body)
        } catch (mesh: IOException) {
            FleetHttp.call(publicClient, "POST", PUBLIC_BASE + PATH, headers, body)
        }
    }
}

/** The note shown for a failed request: the server's {"error","code"} when it sent one, the HTTP status otherwise. */
internal fun recipientCheckError(httpCode: Int, body: String): String {
    val o = runCatching { JSONObject(body) }.getOrNull()
    val code = o?.optString("code").orEmpty()
    val error = o?.optString("error").orEmpty()
    return listOf("HTTP $httpCode", code, error).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

private fun JSONObject.str(name: String): String? = if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

private fun verdictOf(wire: String?): RecipientVerdict = when (wire?.lowercase()) {
    "ok" -> RecipientVerdict.OK
    "risky" -> RecipientVerdict.RISKY
    "fail" -> RecipientVerdict.WILL_FAIL
    else -> RecipientVerdict.UNKNOWN
}

/** A generic {state} read as an outcome: the words the API uses for a pass, a failure, or "could not tell". */
private fun stateOutcome(state: String?): CheckOutcome = when (state?.lowercase()) {
    "ok", "valid", "pass", "passed", "found", "exists" -> CheckOutcome.PASS
    "invalid", "fail", "failed", "missing", "nxdomain", "no_mx", "null_mx", "does_not_exist", "bounced" -> CheckOutcome.FAIL
    "warn", "risky", "accepts_all" -> CheckOutcome.WARN
    else -> CheckOutcome.UNKNOWN
}

private fun item(kind: RecipientCheckKind, outcome: CheckOutcome, detail: String?, acceptsAll: Boolean = false) =
    RecipientCheckItem(kind, outcome, detail?.takeIf { it.isNotBlank() }, CheckSource.SERVER, acceptsAll)

private fun syntaxItem(o: JSONObject?): RecipientCheckItem? = o?.let {
    item(RecipientCheckKind.SYNTAX, stateOutcome(it.str("state")), it.str("reason"))
}

private fun domainItem(o: JSONObject?): RecipientCheckItem? = o?.let {
    val hosts = it.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { i -> a.optString(i) }.filter(String::isNotBlank) }.orEmpty()
    val nullMx = it.optBoolean("null_mx", false)
    val implicit = it.optBoolean("implicit", false)
    val outcome = if (nullMx) CheckOutcome.FAIL else stateOutcome(it.str("state"))
    val detail = listOfNotNull(
        it.str("reason"),
        hosts.takeIf { h -> h.isNotEmpty() }?.joinToString(", ", prefix = "MX: "),
        "implicit MX (A record)".takeIf { implicit },
        "null MX: the domain accepts no mail".takeIf { nullMx },
    ).joinToString("\n")
    item(RecipientCheckKind.DOMAIN, outcome, detail)
}

private fun mailboxItem(o: JSONObject?): RecipientCheckItem? = o?.let {
    val state = it.str("state")?.lowercase()
    val outcome = when (state) {
        "exists" -> CheckOutcome.PASS
        "does_not_exist" -> CheckOutcome.FAIL
        "accepts_all" -> CheckOutcome.WARN
        else -> CheckOutcome.UNKNOWN // unknown, not_checked: never a failure (outbound :25 may be blocked at the probe)
    }
    val code = if (it.has("code") && !it.isNull("code")) it.opt("code")?.toString() else null
    val detail = listOfNotNull(it.str("reason"), listOfNotNull(it.str("host"), code).joinToString(" ").takeIf { s -> s.isNotBlank() })
        .joinToString("\n")
    item(RecipientCheckKind.MAILBOX, outcome, detail, acceptsAll = state == "accepts_all")
}

private fun bounceItem(o: JSONObject?): RecipientCheckItem? = o?.let {
    val outcome = when (it.str("state")?.lowercase()) {
        "none" -> CheckOutcome.PASS
        "bounced" -> CheckOutcome.WARN // a past bounce makes the address risky; the server's verdict says whether it fails
        else -> CheckOutcome.UNKNOWN
    }
    item(RecipientCheckKind.PAST_BOUNCE, outcome, listOfNotNull(it.str("reason"), it.str("at")).joinToString("\n"))
}

/** A disposable / role flag as the API may send it: a boolean, or an object with a state, a flag or a value. */
private fun flag(root: JSONObject, name: String): Pair<Boolean?, String?> {
    if (!root.has(name) || root.isNull(name)) return null to null
    return when (val v = root.opt(name)) {
        is Boolean -> v to null
        is JSONObject -> {
            val b = when {
                v.has("value") -> v.optBoolean("value")
                v.has("is_$name") -> v.optBoolean("is_$name")
                v.has(name) -> v.optBoolean(name)
                v.has("state") -> v.optString("state").lowercase() in setOf("true", "yes", name, "flagged", "detected")
                else -> null
            }
            b to (v.str("reason") ?: v.str("provider") ?: v.str("kind"))
        }
        is String -> (v.lowercase() in setOf("true", "yes", name)) to null
        else -> null to null
    }
}

private fun disposableRoleItem(checks: JSONObject): RecipientCheckItem? {
    val (disposable, dReason) = flag(checks, "disposable")
    val (role, rReason) = flag(checks, "role")
    if (disposable == null && role == null) return null
    val outcome = when {
        disposable == true || role == true -> CheckOutcome.WARN
        disposable == false && role == false -> CheckOutcome.PASS
        else -> if (disposable == false || role == false) CheckOutcome.PASS else CheckOutcome.UNKNOWN
    }
    val detail = listOfNotNull(
        ("disposable" + (dReason?.let { ": $it" } ?: "")).takeIf { disposable == true },
        ("role mailbox" + (rReason?.let { ": $it" } ?: "")).takeIf { role == true },
    ).joinToString("\n")
    return item(RecipientCheckKind.DISPOSABLE_ROLE, outcome, detail)
}

/** The size limit is compared HERE: the request carries no size, the answer carries the receiving server's limit. */
private fun sizeItem(o: JSONObject?, messageSizeBytes: Long?): RecipientCheckItem? = o?.let {
    val limit = if (it.has("bytes") && !it.isNull("bytes")) it.optLong("bytes", -1).takeIf { b -> b > 0 } else null
    when {
        limit == null -> item(RecipientCheckKind.SIZE_LIMIT, CheckOutcome.UNKNOWN, "no limit advertised")
        messageSizeBytes == null -> item(RecipientCheckKind.SIZE_LIMIT, CheckOutcome.UNKNOWN, "limit $limit bytes")
        messageSizeBytes > limit -> item(RecipientCheckKind.SIZE_LIMIT, CheckOutcome.FAIL, "limit $limit bytes, message $messageSizeBytes bytes")
        else -> item(RecipientCheckKind.SIZE_LIMIT, CheckOutcome.PASS, "limit $limit bytes, message $messageSizeBytes bytes")
    }
}

private fun checkedAt(s: String?): Long =
    s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() ?: it.toLongOrNull()?.let { n -> if (n < 10_000_000_000L) n * 1000 else n } } ?: 0L

/**
 * {"results":[{address, verdict, reason, checks:{…}, cached, checked_at}]} → one [RecipientStatus] per address, keyed by the
 * address as the server returned it. A check the server left out is absent here (the merge marks it unknown). A size limit
 * the message exceeds makes the verdict a will-fail even when the server (which cannot know the size) said otherwise.
 */
internal fun parseRecipientCheckResponse(body: String, messageSizeBytes: Long?): Map<String, RecipientStatus> {
    val results = JSONObject(body).optJSONArray("results") ?: return emptyMap()
    val out = LinkedHashMap<String, RecipientStatus>()
    for (i in 0 until results.length()) {
        val r = results.optJSONObject(i) ?: continue
        val address = r.str("address") ?: continue
        val checks = r.optJSONObject("checks") ?: JSONObject()
        val items = listOfNotNull(
            syntaxItem(checks.optJSONObject("syntax")),
            domainItem(checks.optJSONObject("domain_mx")),
            mailboxItem(checks.optJSONObject("mailbox")),
            bounceItem(checks.optJSONObject("past_bounce")),
            disposableRoleItem(checks),
            sizeItem(checks.optJSONObject("size_limit"), messageSizeBytes),
        )
        var verdict = verdictOf(r.str("verdict"))
        if (items.any { it.kind == RecipientCheckKind.SIZE_LIMIT && it.outcome == CheckOutcome.FAIL }) verdict = RecipientVerdict.WILL_FAIL
        out[address] = RecipientStatus(
            address = address,
            verdict = verdict,
            checks = items,
            checkedAtMillis = checkedAt(r.str("checked_at")),
            fromServer = true,
            serverReason = r.str("reason"),
            serverCached = r.optBoolean("cached", false),
        )
    }
    return out
}
