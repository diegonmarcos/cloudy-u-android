package app.sterna.fleet

import app.sterna.ui.compose.CheckOutcome
import app.sterna.ui.compose.CheckSource
import app.sterna.ui.compose.RecipientCheckItem
import app.sterna.ui.compose.RecipientCheckKind
import app.sterna.ui.compose.RecipientCheckRequest
import app.sterna.ui.compose.RecipientChecker
import app.sterna.ui.compose.RecipientStatus
import app.sterna.ui.compose.RecipientVerdict
import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.FleetHttp
import com.diegonmarcos.superapp.fleetendpoints.android.FleetEndpointsClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * #1803 The fleet client behind [app.sterna.ui.compose.RecipientChecks.checker]: POST /v1/recipients/check on the communication
 * MCP's delivery API (cloudy-u-containers infra-api_cloudy-apps-communication-mcp/DELIVERY-API.md).
 *
 * Addressing (#1093): nothing is typed in here. The service's two endpoints come from Cloud Account's published
 * fleet_endpoints (declared once in ac_cloud-account/build.json as [SERVICE_ID]), pulled with [FleetEndpointsClient] and kept
 * for [ENDPOINTS_TTL_MILLIS]. The mesh endpoint is asked first (no bearer there); off the mesh, and unless the service is
 * mesh-only, the public one with the fleet bearer when Cloud Account granted it (`fleet.bearer`), on that origin alone.
 * Cloud Mail needs the `fleet.endpoints` grant for any of this; without it every check reads unknown, and says why.
 *
 * At most [MAX_BATCH] addresses per request; a non-2xx answer (429 rate_limited included) stops the round with the server's
 * own code as the note. An address the server did not answer is left out, and the composer shows it as unknown.
 */
object FleetRecipientChecker : RecipientChecker {
    const val SERVICE_ID = "communication-mcp"
    const val PATH = "/v1/recipients/check"
    const val MAX_BATCH = 50
    const val ENDPOINTS_TTL_MILLIS = 10 * 60_000L

    @Volatile private var context: Context? = null

    /** Called once at app start: the context the endpoint pull asks Cloud Account through. */
    fun bind(app: Context) {
        context = app.applicationContext
    }

    private class Endpoints(val mesh: String, val public: String, val meshOnly: Boolean, val bearer: String, val at: Long)

    @Volatile private var endpoints: Endpoints? = null

    private fun endpoints(): Endpoints {
        val now = System.currentTimeMillis()
        endpoints?.takeIf { now - it.at < ENDPOINTS_TTL_MILLIS }?.let { return it }
        val ctx = context ?: throw IOException("recipient check not bound")
        val pulled = when (val o = FleetEndpointsClient.pull(ctx)) {
            is FleetEndpointsClient.Outcome.Pulled -> o.published
            FleetEndpointsClient.Outcome.AccountMissing -> throw IOException("Cloud Account is not installed")
            is FleetEndpointsClient.Outcome.Refused -> throw IOException("Cloud Account: ${o.why} (fleet.endpoints)")
        }
        val e = pulled.entries.firstOrNull { it.service == SERVICE_ID }
            ?: throw IOException("Cloud Account declares no $SERVICE_ID endpoint")
        return Endpoints(
            mesh = e.mesh, public = e.public,
            meshOnly = e.policy == FleetEndpointsContract.POLICY_MESH_ONLY,
            bearer = if (e.auth == FleetEndpointsContract.AUTH_BEARER) pulled.bearer else "",
            at = now,
        ).also { endpoints = it }
    }

    private val meshClient by lazy {
        FleetHttp.shared.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    private val publicClient by lazy {
        FleetHttp.shared.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    override suspend fun check(request: RecipientCheckRequest): Map<String, RecipientStatus> {
        val ends = endpoints()
        val out = LinkedHashMap<String, RecipientStatus>()
        for (batch in request.addresses.chunked(MAX_BATCH)) {
            val body = JSONObject().put("addresses", JSONArray(batch))
                .also { o -> request.messageSizeBytes?.let { o.put("message_size_bytes", it) } }
                .toString()
            val (code, text) = post(ends, body)
            if (code !in 200..299) throw IOException(recipientCheckError(code, text))
            out += parseRecipientCheckResponse(text, request.messageSizeBytes)
        }
        return out
    }

    /** The mesh first; on a transport failure there (off the mesh), the public path unless the service is mesh-only. */
    private fun post(ends: Endpoints, body: String): Pair<Int, String> {
        val headers = mapOf("Accept" to "application/json")
        val meshFailure: IOException = try {
            if (ends.mesh.isNotEmpty()) return FleetHttp.call(meshClient, "POST", ends.mesh + PATH, headers, body)
            IOException("no mesh endpoint")
        } catch (mesh: IOException) {
            mesh
        }
        if (ends.meshOnly || ends.public.isEmpty()) throw IOException("mesh only, not reached: ${meshFailure.message ?: meshFailure.javaClass.simpleName}")
        val auth = if (ends.bearer.isNotEmpty()) mapOf("Authorization" to "Bearer ${ends.bearer}") else emptyMap()
        return FleetHttp.call(publicClient, "POST", ends.public + PATH, headers + auth, body)
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
    "fail", "will_fail" -> RecipientVerdict.WILL_FAIL
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

/** The wire names of a check's entry in `check_details`. */
private fun detailKeys(kind: RecipientCheckKind): List<String> = when (kind) {
    RecipientCheckKind.SYNTAX -> listOf("syntax")
    RecipientCheckKind.DOMAIN -> listOf("domain", "domain_mx")
    RecipientCheckKind.MAILBOX -> listOf("mailbox")
    RecipientCheckKind.PAST_BOUNCE -> listOf("past_bounce")
    RecipientCheckKind.DISPOSABLE_ROLE -> listOf("disposable_role", "disposable", "role")
    RecipientCheckKind.SIZE_LIMIT -> listOf("size_limit")
}

/** One `check_details` value as a line: a string as is, an object as "key: value" pairs (arrays joined), nulls left out. */
private fun detailLine(v: Any?): String? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> v.keys().asSequence().mapNotNull { k ->
        when (val x = v.opt(k)) {
            null, JSONObject.NULL -> null
            is JSONArray -> "$k: " + (0 until x.length()).joinToString(", ") { x.opt(it).toString() }
            else -> "$k: $x"
        }
    }.joinToString(" · ").takeIf { it.isNotBlank() }
    is JSONArray -> (0 until v.length()).joinToString(", ") { v.opt(it).toString() }
    else -> v.toString().takeIf { it.isNotBlank() }
}

/** The item's detail with whatever `check_details` adds for its kind (nothing the server sent is dropped, rule 16). */
private fun withCheckDetails(item: RecipientCheckItem, details: JSONObject): String? {
    val extra = detailKeys(item.kind).mapNotNull { k -> if (details.has(k)) detailLine(details.opt(k)) else null }
    return (listOfNotNull(item.detail) + extra).distinct().joinToString("\n").takeIf { it.isNotBlank() }
}

/** The current shape (cloudy-u-containers 07f51da): checks:[{check, outcome, detail?, accepts_all?}], one per kind. */
private fun listItems(a: JSONArray): List<RecipientCheckItem> = (0 until a.length()).mapNotNull { i ->
    val c = a.optJSONObject(i) ?: return@mapNotNull null
    val kind = when (c.str("check")?.lowercase()) {
        "syntax" -> RecipientCheckKind.SYNTAX
        "domain", "domain_mx" -> RecipientCheckKind.DOMAIN
        "mailbox" -> RecipientCheckKind.MAILBOX
        "past_bounce" -> RecipientCheckKind.PAST_BOUNCE
        "disposable_role", "disposable", "role" -> RecipientCheckKind.DISPOSABLE_ROLE
        "size_limit" -> RecipientCheckKind.SIZE_LIMIT
        else -> return@mapNotNull null
    }
    val outcome = when (c.str("outcome")?.lowercase()) {
        "pass" -> CheckOutcome.PASS
        "warn" -> CheckOutcome.WARN
        "fail" -> CheckOutcome.FAIL
        else -> CheckOutcome.UNKNOWN
    }
    item(kind, outcome, c.str("detail"), acceptsAll = c.optBoolean("accepts_all", false))
}.distinctBy { it.kind }

/**
 * {"results":[{address, verdict, reason, checks, cached, checked_at}]}, `checks` either the current list shape or the first
 * object shape (checks:{syntax, domain_mx, mailbox, past_bounce, disposable, role, size_limit}); both are read. → one [RecipientStatus] per address, keyed by the
 * address as the server returned it. A check the server left out is absent here (the merge marks it unknown). A size limit
 * the message exceeds makes the verdict a will-fail even when the server (which cannot know the size) said otherwise.
 */
internal fun parseRecipientCheckResponse(body: String, messageSizeBytes: Long?): Map<String, RecipientStatus> {
    val results = JSONObject(body).optJSONArray("results") ?: return emptyMap()
    val out = LinkedHashMap<String, RecipientStatus>()
    for (i in 0 until results.length()) {
        val r = results.optJSONObject(i) ?: continue
        val address = r.str("address") ?: continue
        val list = r.optJSONArray("checks")
        val checks = r.optJSONObject("checks") ?: JSONObject()
        val items = if (list != null) listItems(list) else listOfNotNull(
            syntaxItem(checks.optJSONObject("syntax")),
            domainItem(checks.optJSONObject("domain_mx")),
            mailboxItem(checks.optJSONObject("mailbox")),
            bounceItem(checks.optJSONObject("past_bounce")),
            disposableRoleItem(checks),
            sizeItem(checks.optJSONObject("size_limit"), messageSizeBytes),
        )
        val details = r.optJSONObject("check_details")
        val withDetails = if (details == null) items else items.map { it.copy(detail = withCheckDetails(it, details)) }
        var verdict = verdictOf(r.str("verdict"))
        if (items.any { it.kind == RecipientCheckKind.SIZE_LIMIT && it.outcome == CheckOutcome.FAIL }) verdict = RecipientVerdict.WILL_FAIL
        out[address] = RecipientStatus(
            address = address,
            verdict = verdict,
            checks = withDetails,
            checkedAtMillis = checkedAt(r.str("checked_at")),
            fromServer = true,
            serverReason = r.str("reason"),
            serverCached = r.optBoolean("cached", false),
        )
    }
    return out
}
