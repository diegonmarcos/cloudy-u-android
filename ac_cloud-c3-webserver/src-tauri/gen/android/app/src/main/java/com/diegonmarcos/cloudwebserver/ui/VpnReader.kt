package com.diegonmarcos.cloudwebserver.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.diegonmarcos.superapp.net.AidlBackend
import com.wireguard.android.backend.Tunnel
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * #1845 the live VPN reading Home's VPN table draws. NEVER a proxy: the tunnel, its peers' handshakes, always-on and the relay come from the
 * engine's INetBackend (the signature-guarded bound service Cloud-Lib-Net-Wg declares in the fleet IPC registry, the one the SuperApp's VPN page
 * drives through WgState.backend / AidlBackend), the addresses, routes and resolvers from the VPN network Android holds, and the hub and
 * private-name probes are made from THIS phone with the socket bound to the phone's address in that mesh (a socket left unbound is sourced
 * from the tunnel's FIRST address, which the other mesh's hub drops).
 * Blocking: call off the main thread.
 */
object VpnReader {
    @Volatile private var backend: AidlBackend? = null
    private fun be(ctx: Context): AidlBackend = backend ?: synchronized(this) { backend ?: AidlBackend(ctx.applicationContext).also { backend = it } }

    private class Named(private val n: String) : Tunnel {
        override fun getName(): String = n
        override fun onStateChange(newState: Tunnel.State) {}
    }

    fun read(ctx: Context, vpn: org.json.JSONObject?): VpnReading {
        val now = System.currentTimeMillis()
        val b = be(ctx)
        val decls = VpnModel.decls(vpn)
        val names = vpn?.optJSONArray("tunnel_candidates").strs()
        if (!b.isEngineInstalled()) return VpnReading(false, null, "DOWN", "-", null, null, emptyList(), "", "", emptyList(), null, emptyList(), now)
        b.bindBlocking(3000)
        var tunnel: String? = null
        for (n in names) if (runCatching { b.getState(Named(n)) }.getOrNull() == Tunnel.State.UP) { tunnel = n; break }
        val peers = tunnel?.let { n ->
            runCatching {
                val st = b.getStatistics(Named(n))
                st.peers().map { k -> val p = st.peer(k); VpnPeer(k.toBase64(), p?.latestHandshakeEpochMillis() ?: 0L, p?.rxBytes() ?: 0L, p?.txBytes() ?: 0L) }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val relay = runCatching { b.relayStatus().state }.getOrDefault("off")
        val idle = runCatching { b.idleStatus() }.getOrDefault("")
        val net = vpnNetwork(ctx)
        val dns = net?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        val addrs = (net?.linkAddresses.orEmpty().mapNotNull { (it.address as? Inet4Address)?.hostAddress } + localV4()).distinct()
        val routes = net?.routes.orEmpty().map { it.destination.toString() }
        val meshes = decls.map { d -> meshOf(d, addrs, routes, dns) }
        return VpnReading(true, tunnel ?: names.firstOrNull(), if (tunnel != null) "UP" else "DOWN", runCatching { b.getVersion() }.getOrDefault("-"),
            runCatching { b.isAlwaysOn() }.getOrNull(), runCatching { b.isLockdownEnabled() }.getOrNull(), peers, relay, idle, dns, net?.interfaceName, meshes, now)
    }

    private fun vpnNetwork(ctx: Context) = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }?.let { cm.getLinkProperties(it) }
    }.getOrNull()

    private fun localV4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
    }.getOrDefault(emptyList())

    private fun meshOf(d: VpnMeshDecl, addrs: List<String>, routes: List<String>, dns: List<String>): VpnMeshReading {
        val mine = addrs.firstOrNull { VpnModel.cidrContains(d.cidr, it) }
        val covered = routes.any { r -> r.trimStart('/').let { it == d.cidr || (it.contains('/') && VpnModel.cidrContains(it, d.hub)) } }
        val (hubOk, hubDetail) = tcp(mine, d.hub, 53)
        val (nameOk, nameDetail) = name(d.name)
        return VpnMeshReading(d, mine, if (routes.isEmpty()) null else covered, if (dns.isEmpty()) null else dns.contains(d.resolver), hubOk, hubDetail, nameOk, nameDetail)
    }

    private fun tcp(from: String?, host: String, port: Int): Pair<Boolean, String> {
        val t0 = System.nanoTime()
        return try {
            Socket().use { s ->
                if (from != null) s.bind(InetSocketAddress(InetAddress.getByName(from), 0))
                s.connect(InetSocketAddress(host, port), 2000)
            }
            true to "TCP answers in ${(System.nanoTime() - t0) / 1_000_000} ms" + (from?.let { " (from $it)" } ?: "")
        } catch (e: Exception) { false to "${e.javaClass.simpleName}: ${e.message.orEmpty().take(90)}" + (from?.let { " (from $it)" } ?: " (no phone address in this mesh)") }
    }

    private fun name(host: String): Pair<Boolean, String> = try {
        true to "resolves to " + InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.joinToString(", ")
    } catch (e: Exception) { false to "does not resolve (${e.javaClass.simpleName})" }
}
