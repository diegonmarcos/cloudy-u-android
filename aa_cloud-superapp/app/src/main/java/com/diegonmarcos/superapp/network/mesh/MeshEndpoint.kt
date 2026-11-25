package com.diegonmarcos.superapp.network.mesh

/**
 * #1928 Which endpoint a peer is reached by right now: the address family of its endpoint (v4 | v6 | name for an unresolved host name) or
 * "relay" when the tunnel runs over the TLS-443 relay ([path] = the transport view's active path). Pure: the Peers row, the status dump and
 * the tests read this one function.
 */
object MeshEndpoint {
    private val V4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    fun inUse(endpoint: String, path: String): String {
        if (path == "relay") return "relay"
        val e = endpoint.trim()
        if (e.isEmpty()) return "-"
        val host = when {
            e.startsWith("[") -> e.substringAfter('[').substringBefore(']')
            e.count { it == ':' } > 1 -> e
            else -> e.substringBeforeLast(':', e)
        }
        return when {
            host.contains(':') -> "v6"
            V4.matches(host) -> "v4"
            else -> "name"
        }
    }
}
