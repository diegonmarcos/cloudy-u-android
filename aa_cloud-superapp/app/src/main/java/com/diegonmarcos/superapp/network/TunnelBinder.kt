package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * #1900 Binds a probe's socket to the network it is meant to test, before it connects. Left unbound, Android picked the
 * source: the TCP probes to 10.0.0.1:53 and 10.1.0.1:53 left from the Wi-Fi address (so they never entered the tunnel),
 * and every IPv6 probe to fd0c:1d00::1 was routed to the Wi-Fi network, which holds no such route (ENETUNREACH) although
 * the tunnel interface holds fd0c:1d00::10/64. [C3MeshProbe.VIA_TUNNEL] is the VPN network, [C3MeshProbe.VIA_DIRECT] the
 * validated non-VPN network (the bind FleetDnsBridge and DnsReach use), [C3MeshProbe.VIA_DEFAULT] is not bound.
 */
class TunnelBinder(ctx: Context) : C3MeshProbe.SocketBinder {
    private val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** The network for [via]; throws with the reason when there is none. */
    fun network(via: String): Network {
        val all = runCatching { cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } } }.getOrDefault(emptyList())
        return pick(via, all.map { (n, c) -> Cand(n, c.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) })
    }

    override fun bind(s: Socket, via: String) { network(via).bindSocket(s) }
    override fun bind(s: DatagramSocket, via: String) { network(via).bindSocket(s) }
    override fun open(url: URL, via: String): URLConnection = network(via).openConnection(url)

    /** One network as the selection sees it. */
    data class Cand<T>(val net: T, val vpn: Boolean, val internet: Boolean, val validated: Boolean)

    companion object {
        /** Pure selection (JVM-tested): the VPN network for the tunnel, the best non-VPN one for direct. */
        fun <T> pick(via: String, nets: List<Cand<T>>): T = when (via) {
            C3MeshProbe.VIA_TUNNEL -> nets.firstOrNull { it.vpn }?.net ?: throw IOException("no VPN network: the tunnel is down")
            C3MeshProbe.VIA_DIRECT -> nets.filter { !it.vpn && it.internet }.let { o -> (o.firstOrNull { it.validated } ?: o.firstOrNull())?.net }
                ?: throw IOException("no non-VPN network to bind to")
            else -> throw IOException("unknown path $via")
        }

        /** Installed once at app start and again by the page: every C3MeshProbe socket now takes its network from here. */
        fun install(ctx: Context) { if (C3MeshProbe.SystemIo.binder == null) C3MeshProbe.SystemIo.binder = TunnelBinder(ctx) }
    }
}
