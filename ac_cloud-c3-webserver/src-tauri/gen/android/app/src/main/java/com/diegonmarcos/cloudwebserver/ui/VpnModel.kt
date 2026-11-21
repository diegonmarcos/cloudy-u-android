package com.diegonmarcos.cloudwebserver.ui

import org.json.JSONObject

/** #1845 one declared mesh (build.json::vpn.meshes, equal to the SuperApp's ui.mesh_page.meshes: the same two meshes the VPN page probes). */
data class VpnMeshDecl(val id: String, val label: String, val cidr: String, val hub: String, val resolver: String, val name: String, val serviceUrl: String)

data class VpnPeer(val key: String, val handshakeMs: Long, val rx: Long, val tx: Long)

/** What one mesh reads right now, from this phone, through the tunnel (null = not measured). */
data class VpnMeshReading(
    val decl: VpnMeshDecl, val address: String?, val route: Boolean?, val resolverInAndroid: Boolean?,
    val hubOk: Boolean?, val hubDetail: String, val nameOk: Boolean?, val nameDetail: String,
)

/** #1845 the tunnel and its meshes, read from the engine's INetBackend (the same engine and calls the SuperApp's VPN page, WgState.backend, uses). */
data class VpnReading(
    val engine: Boolean, val tunnel: String?, val state: String, val version: String, val alwaysOn: Boolean?, val lockdown: Boolean?,
    val peers: List<VpnPeer>, val relay: String, val idle: String, val androidDns: List<String>, val vpnInterface: String?,
    val meshes: List<VpnMeshReading>, val nowMs: Long,
)

/**
 * #1845 Home's VPN table as pure rows. The reading is taken by VpnReader (INetBackend over the engine's declared bound service); this only
 * turns it into the rows and words, so a JVM test pins every one. Every row carries its light: true = fine, false = failing, null = not measured.
 */
object VpnModel {
    const val FRESH_S = 180L

    fun decls(vpn: JSONObject?): List<VpnMeshDecl> = vpn?.optJSONArray("meshes").objs().map {
        VpnMeshDecl(it.optString("id"), it.optString("label"), it.optString("cidr"), it.optString("hub"), it.optString("resolver"), it.optString("name"), it.optString("service_url"))
    }

    fun cidrContains(cidr: String, ip: String): Boolean {
        val (base, n) = cidr.split('/').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: return false) }
        fun v(s: String): Long? = s.split('.').takeIf { it.size == 4 }?.map { it.toLongOrNull() ?: return null }?.fold(0L) { a, b -> (a shl 8) or b }
        val (b, x) = (v(base) ?: return false) to (v(ip) ?: return false)
        val mask = if (n == 0) 0L else (0xFFFFFFFFL shl (32 - n)) and 0xFFFFFFFFL
        return (b and mask) == (x and mask)
    }

    fun age(ms: Long, now: Long): String = if (ms <= 0) "none" else ((now - ms) / 1000).coerceAtLeast(0).let { if (it < 120) "${it}s" else "${it / 60}m${it % 60}s" }

    fun fresh(p: VpnPeer, now: Long) = p.handshakeMs > 0 && (now - p.handshakeMs) / 1000 <= FRESH_S

    /** The block of rows for the tunnel itself, then one block per mesh. */
    fun tunnelRows(r: VpnReading?): List<HomeModel.SRow> {
        if (r == null) return listOf(HomeModel.SRow("Tunnel", "not read yet", null))
        if (!r.engine) return listOf(HomeModel.SRow("Tunnel", "engine not installed (Cloud-Lib-Net-Wg): no VPN to read", false))
        val up = r.state == "UP"
        val nFresh = r.peers.count { fresh(it, r.nowMs) }
        return listOf(
            HomeModel.SRow("Tunnel", if (up) "up · ${r.tunnel} · engine ${r.version}" else "down · no tunnel of ${r.tunnel ?: "the declared names"} is UP in the engine", up),
            HomeModel.SRow("Handshakes", if (r.peers.isEmpty()) "no peers" else "$nFresh of ${r.peers.size} fresh · " + r.peers.mapIndexed { i, p -> "peer-${i + 1} ${age(p.handshakeMs, r.nowMs)}" }.joinToString(" · "), if (r.peers.isEmpty()) false else nFresh == r.peers.size),
            HomeModel.SRow("Android DNS", if (r.androidDns.isEmpty()) "none on the VPN network" else r.androidDns.joinToString(", ") + (r.vpnInterface?.let { " · $it" } ?: ""), r.androidDns.isNotEmpty()),
            HomeModel.SRow("Active path", if (r.relay == "off" || r.relay.isEmpty()) "Direct UDP (configured endpoints, no relay)" else "TLS-443 relay: ${r.relay}", up),
            HomeModel.SRow("Fallbacks", "relay ${r.relay.ifEmpty { "off" }} · idle ${r.idle.ifEmpty { "-" }} (Test fallbacks on the SuperApp VPN page runs the paths)", null),
            HomeModel.SRow("Always-on", when (r.alwaysOn) { true -> "ON"; false -> "OFF"; null -> "?" } + (if (r.lockdown == true) " · lockdown ON" else ""), r.alwaysOn),
        )
    }

    fun meshRows(m: VpnMeshReading): List<HomeModel.SRow> {
        val l = m.decl.label
        return listOf(
            HomeModel.SRow("$l interface", (m.address?.let { "$it in ${m.decl.cidr}" } ?: "no address in ${m.decl.cidr}"), m.address != null),
            HomeModel.SRow("$l route", when (m.route) { true -> "covered (${m.decl.cidr} via the tunnel)"; false -> "not covered"; null -> "not read" }, m.route),
            HomeModel.SRow("$l mesh DNS", when (m.resolverInAndroid) { true -> "${m.decl.resolver} is among Android's resolvers"; false -> "${m.decl.resolver} is NOT among Android's resolvers"; null -> "not read" }, m.resolverInAndroid),
            HomeModel.SRow("$l hub IP", "${m.decl.hub}:53 · ${m.hubDetail}", m.hubOk),
            HomeModel.SRow("$l private name", "${m.decl.name} · ${m.nameDetail}", m.nameOk),
        )
    }

    /** The whole table: tunnel rows, then every mesh's rows. */
    fun allRows(r: VpnReading?): List<HomeModel.SRow> = tunnelRows(r) + (r?.meshes ?: emptyList()).flatMap { meshRows(it) }

    /** The badge: UP only when the tunnel is up and no measured mesh row fails. */
    fun verdict(r: VpnReading?): Pair<String, Boolean?> {
        if (r == null) return "?" to null
        if (!r.engine || r.state != "UP") return "DOWN" to false
        val bad = r.meshes.any { m -> meshRows(m).any { it.up == false } }
        return if (bad) "ATTENTION" to false else "UP" to true
    }

    /** Rows for the Markdown export: the table as `key | value`. */
    fun markdown(r: VpnReading?): String = allRows(r).joinToString("\n") { "- ${if (it.up == true) "OK" else if (it.up == false) "FAIL" else "n/a"} **${it.key}**: ${it.value}" }
}
