package com.diegonmarcos.superapp.fleetendpoints.errors

/** #1925 Secrets never reach a report, a log line or an export: the Authorization header, cookies, passwords, tokens. */
object ErrorRedactor {
    const val MASK = "[redacted]"

    private val SECRET_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-fleet-token", "x-csrf-token",
    )

    private val KEYS = "authorization|cookie|password|passwd|pwd|pass|secret|token|access_token|refresh_token|id_token|client_secret|api[_-]?key|apikey|credential"
    // key=value / key: value / "key":"value"
    private val PAIR = Regex("(?i)(\\b[\"']?(?:$KEYS)[\"']?\\s*[=:]\\s*)([\"']?)([^\\s\"'&,;}]+)")
    private val BEARER = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*")
    private val URL_USERINFO = Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@")

    fun isSecretHeader(name: String): Boolean = name.lowercase() in SECRET_HEADERS

    fun header(name: String, value: String): String = if (isSecretHeader(name)) MASK else text(value)

    fun text(s: String): String {
        var out = URL_USERINFO.replace(s) { "${it.groupValues[1]}$MASK@" }
        out = JWT.replace(out, MASK)
        out = BEARER.replace(out) { "${it.groupValues[1]} $MASK" }
        out = PAIR.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        return out
    }

    /** A URL with its query values that look like secrets masked. */
    fun url(u: String): String = text(u)
}
