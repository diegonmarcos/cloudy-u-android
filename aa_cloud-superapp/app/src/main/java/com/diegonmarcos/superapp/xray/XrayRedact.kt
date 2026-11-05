package com.diegonmarcos.superapp.xray

/** #1504 Nothing secret-shaped leaves the phone in a report: keyed values, bearer/basic credentials, JWTs, well-known token prefixes, PEM keys, long hex/base64 runs. */
object XrayRedact {
    const val MASK = "[REDACTED]"
    private val PEM = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")
    private val KEYED = Regex(
        "(?i)((?:token|secret|passw(?:or)?d|passwd|api[_-]?key|apikey|bearer|authorization|cookie|private[_-]?key|credential|session[_-]?id|access[_-]?key)[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;&]+)")
    private val SCHEME = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*")
    private val PREFIXED = Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|glpat-[A-Za-z0-9_-]{20,})")
    private val LONG = Regex("\\b[A-Fa-f0-9]{40,}\\b|[A-Za-z0-9+/_-]{48,}={0,2}")

    fun scrub(s: String): String {
        if (s.isEmpty()) return s
        var t = PEM.replace(s, MASK)
        t = SCHEME.replace(t) { "${it.groupValues[1]} $MASK" }
        t = KEYED.replace(t) { it.groupValues[1] + MASK }
        t = JWT.replace(t, MASK)
        t = PREFIXED.replace(t, MASK)
        t = LONG.replace(t, MASK)
        return t
    }
}
