package com.diegonmarcos.superapp.fleetendpoints.errors

/**
 * #1925 THE error model of Cloud Mail (shared lib, one definition): everything a surfaced failure knows, secrets already
 * redacted. Sync, JMAP session, send, attachments, account and push all raise this same shape.
 */
data class ErrorReport(
    val operation: String,
    val account: String,
    val url: String,
    val path: String,
    val method: String,
    val status: Int?,
    val reason: String,
    val responseHeaders: List<Pair<String, String>>,
    /** The first [BODY_LIMIT] bytes of the response body, redacted. */
    val bodyHead: String,
    /** The exception chain, outermost first: "class: message". */
    val chain: List<String>,
    val atMs: Long,
    val route: RouteProfile?,
) {
    companion object { const val BODY_LIMIT = 2048 }
}

/** The route profile taken AT failure time: how the request would travel and where it stops. */
data class RouteProfile(
    val host: String,
    /** DNS answers (addresses) and the resolver that gave them. */
    val dnsAnswers: List<String>,
    val resolver: String,
    /** "mesh" or "public". */
    val path: String,
    val tcpMs: Long?,
    val tcpError: String,
    val tlsMs: Long?,
    val certName: String,
    val tlsError: String,
    val dnsMs: Long?,
    val totalMs: Long,
    val vpn: String,
)
