package com.diegonmarcos.superapp.core

import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * The data lines of a keep-alive notification that no app computes for itself: one-shot probes, never a
 * loop or a timer (a keep-alive notification is refreshed when its owner's own state changes).
 */
object KeepAliveStatus {
    /** True when something accepts on [host]:[port] within [timeoutMs] (loopback: it answers at once or not at all). */
    @JvmStatic
    @JvmOverloads
    fun portUp(port: Int, host: String = "127.0.0.1", timeoutMs: Int = 250): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    /** "8002 up · 8024 down" for the given ports, in order. */
    @JvmStatic
    fun portsLine(label: String, states: Map<Int, Boolean>): String =
        label + " " + states.entries.joinToString(" · ") { "${it.key} ${if (it.value) "up" else "down"}" }

    /** True when a WireGuard / tun interface is up: the phone's mesh address exists. */
    @JvmStatic
    fun meshUp(interfaces: List<Pair<String, Boolean>> = systemInterfaces()): Boolean =
        interfaces.any { (name, up) -> up && (name.startsWith("wg") || name.startsWith("tun")) }

    private fun systemInterfaces(): List<Pair<String, Boolean>> = try {
        NetworkInterface.getNetworkInterfaces().toList().map { it.name to it.isUp }
    } catch (e: Exception) {
        emptyList()
    }

    /** "mesh up" / "mesh down". */
    @JvmStatic
    fun meshLine(up: Boolean): String = if (up) "mesh up" else "mesh down"
}
