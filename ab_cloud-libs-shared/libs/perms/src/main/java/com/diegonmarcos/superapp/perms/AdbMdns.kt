package com.diegonmarcos.superapp.perms

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * mDNS discovery of the two services Wireless Debugging advertises — the role
 * Shizuku's `AdbMdns` plays, so the user never types a port:
 *
 *  - [TLS_PAIRING] `_adb-tls-pairing._tcp` — advertised only while Developer
 *    options ▸ Wireless debugging ▸ "Pair device with pairing code" is open;
 *    its port is the one in that dialog and changes every time it opens.
 *  - [TLS_CONNECT] `_adb-tls-connect._tcp` — the wireless-debugging port on
 *    the main screen, up whenever the switch is on.
 *
 * Pairing against the CONNECT port is exactly the "Connection reset" the
 * phone showed: adbd's connect listener expects the adb wire protocol, not a
 * SPAKE2 pairing handshake, and drops the socket. Taking the port from the
 * right advert is what makes that mistake impossible.
 *
 * Only a service resolved to one of THIS phone's own addresses is reported:
 * any other device on the LAN also advertises these types, and pairing with
 * a neighbour's adbd (or handing it our code) is never what the user meant.
 */
class AdbMdns(
    ctx: Context,
    private val serviceType: String,
    private val onFound: (host: InetAddress, port: Int) -> Unit,
) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    @Volatile private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { running = false }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
        override fun onServiceLost(info: NsdServiceInfo) {}
        @Suppress("DEPRECATION")
        override fun onServiceFound(info: NsdServiceInfo) {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host ?: return
                    if (i.port > 0 && isOwnAddress(host)) onFound(host, i.port)
                }
            })
        }
    }

    fun start() {
        if (running) return
        running = true
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    private fun isOwnAddress(a: InetAddress): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { it.inetAddresses.toList().contains(a) }
    }.getOrDefault(false)

    companion object {
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
    }
}
