package com.diegonmarcos.superapp.adbdebug

/** Colour state of one layer: a dot on the ADB Shell page, a chip elsewhere. UNKNOWN is a state, not a default. */
enum class Dot { OK, WARN, BAD, UNKNOWN }

/** How the whole channel stands, derived from [ChannelFacts] and nothing else. */
enum class ConnState { CONNECTING, UP, DEGRADED, DOWN }

/**
 * Every fact the ADB Shell page reads off the device, as plain values, so the table, the connect
 * sequence, the buttons and the checklist are all pure functions of this one object (and are tested
 * without a device). null on a Boolean/String = the device would not say.
 */
data class ChannelFacts(
    val mode: ChannelMode,
    val probedAt: Long,
    val devOptions: Boolean? = null,
    val wirelessDebug: Boolean? = null,
    val onWifi: Boolean? = null,
    val notificationsAllowed: Boolean? = null,
    val adbPaired: Boolean = false,
    val adbConnected: Boolean = false,
    /** "host:port" (or just "host" when the port was discovered over mDNS and not kept). */
    val adbEndpoint: String? = null,
    val serverPort: Int = 0,
    val serverRunning: Boolean = false,
    val serverUid: String? = null,
    val serverUptime: String? = null,
    val serverPid: String? = null,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val shizukuVersion: String? = null,
    /** A Connect run is in flight (set by the page, not read off the device). */
    val connecting: Boolean = false,
    /** This app runs a local server of its own (build.json::shizuku_diagnostics.local_server). A terminal does not. */
    val ownsServer: Boolean = true,
    /** The SuperApp bridge is a declared provider of this app (a terminal), and whether it answers. */
    val bridgeDeclared: Boolean = false,
    val bridgeUp: Boolean = false,
) {
    val shizuku: ShizukuState get() = ShizukuState.of(shizukuInstalled, shizukuRunning, shizukuGranted)
}

/** One line of the status table. */
data class StatusRow(val id: String, val label: String, val dot: Dot, val value: String, val probedAt: Long)

/** The page's table, the active route and the connection state: pure rules over [ChannelFacts]. */
object ChannelState {

    const val ROW_DEV = "dev-options"
    const val ROW_WD = "wireless-debugging"
    const val ROW_ADB = "embedded-adb"
    const val ROW_SERVER = "local-server"
    const val ROW_SHIZUKU = "shizuku"
    const val ROW_BRIDGE = "superapp-bridge"
    const val ROW_ROUTE = "active-route"

    /** The ladder, in preference order (the same one [ShellChannels] walks); the bridge only for apps that declare it. */
    fun ladder(f: ChannelFacts): List<String> =
        listOf(ChannelSelector.EMBEDDED, ChannelSelector.LOCAL) +
            (if (f.bridgeDeclared) listOf(ChannelSelector.BRIDGE) else emptyList()) + ChannelSelector.SHIZUKU

    private fun ready(name: String, f: ChannelFacts): Boolean = when (name) {
        ChannelSelector.EMBEDDED -> f.adbConnected
        ChannelSelector.LOCAL -> f.serverRunning
        ChannelSelector.SHIZUKU -> f.shizuku == ShizukuState.UP
        ChannelSelector.BRIDGE -> f.bridgeDeclared && f.bridgeUp
        else -> false
    }

    /** The channel that runs commands in [ChannelFacts.mode], or null. */
    fun route(f: ChannelFacts): String? =
        ChannelSelector.execOrder(f.mode, ladder(f)).firstOrNull { ready(it, f) }

    /** A session of this mode's own channel is up for the Restart-server button to launch the optional server through. */
    fun canLaunchServer(f: ChannelFacts): Boolean =
        f.ownsServer && ChannelSelector.launchSources(f.mode, ladder(f)).any { ready(it, f) }

    /** Does [mode] use this layer at all, in an app that [ownsServer]. A layer never touched is greyed, not red. */
    fun used(layer: String, mode: ChannelMode, ownsServer: Boolean = true): Boolean = when (layer) {
        ROW_WD, ROW_ADB, ROW_DEV -> mode != ChannelMode.SHIZUKU
        ROW_SERVER -> ownsServer && (mode == ChannelMode.LOCAL_SERVER || mode == ChannelMode.AUTO)
        // Shizuku is its own channel: only Shizuku mode (and the Auto ladder) ever touch it.
        ROW_SHIZUKU -> mode == ChannelMode.SHIZUKU || mode == ChannelMode.AUTO
        ROW_BRIDGE -> mode == ChannelMode.AUTO
        else -> true
    }

    fun connState(f: ChannelFacts): ConnState {
        if (f.connecting) return ConnState.CONNECTING
        val r = route(f) ?: return ConnState.DOWN
        val degraded = when (f.mode) {
            // The loopback server carries the commands while the adbd connection (the mode's channel) is down.
            ChannelMode.LOCAL_SERVER -> r == ChannelSelector.LOCAL && !f.adbConnected
            // A paired adb session that dropped while a lower rung carries the commands.
            ChannelMode.AUTO -> r != ChannelSelector.EMBEDDED && f.adbPaired && !f.adbConnected
            else -> false
        }
        return if (degraded) ConnState.DEGRADED else ConnState.UP
    }

    private fun grey(layer: String, f: ChannelFacts, dot: Dot): Dot =
        if (dot != Dot.OK && !used(layer, f.mode, f.ownsServer)) Dot.UNKNOWN else dot

    private fun tri(v: Boolean?, on: String, off: String): Pair<Dot, String> = when (v) {
        true -> Dot.OK to on
        false -> Dot.BAD to off
        null -> Dot.UNKNOWN to "unknown"
    }

    fun rows(f: ChannelFacts): List<StatusRow> {
        val at = f.probedAt
        fun row(id: String, label: String, dot: Dot, value: String) =
            StatusRow(id, label, grey(id, f, dot), value + when {
                dot == Dot.OK || used(id, f.mode, f.ownsServer) -> ""
                id == ROW_SERVER && !f.ownsServer -> " (this app runs no server of its own)"
                else -> " (not used in ${f.mode.title()} mode)"
            }, at)

        val (devDot, devVal) = tri(f.devOptions, "enabled", "off")
        val wd = when {
            f.wirelessDebug == null -> Dot.UNKNOWN to "unknown"
            f.wirelessDebug == false -> Dot.BAD to "off"
            f.onWifi == false -> Dot.WARN to "on, but not on Wi-Fi"
            f.onWifi == null -> Dot.OK to "on"
            else -> Dot.OK to "on, on Wi-Fi"
        }
        val adb = when {
            f.adbConnected -> Dot.OK to ("paired, connected" + (f.adbEndpoint?.let { " $it" } ?: ""))
            f.adbPaired -> Dot.WARN to "paired, not connected"
            else -> Dot.BAD to "not paired"
        }
        val server = when {
            // Optional: a server that was never started is not a fault while the adb session carries the commands.
            !f.serverRunning && f.adbConnected && used(ROW_SERVER, f.mode, f.ownsServer) ->
                Dot.UNKNOWN to "port ${f.serverPort}, not started (optional: commands run over embedded adb)"
            !f.serverRunning -> Dot.BAD to "port ${f.serverPort}, not running"
            f.serverUid != null && f.serverUid != "uid 2000" && f.serverUid != "uid 0" ->
                Dot.WARN to "port ${f.serverPort}, ${f.serverUid} (not the shell)"
            else -> Dot.OK to listOfNotNull("port ${f.serverPort}", f.serverUid,
                f.serverUptime?.let { "up $it" }, f.serverPid?.let { "pid $it" }).joinToString(", ")
        }
        val shz = when (f.shizuku) {
            ShizukuState.UP -> Dot.OK to ("installed, running, permitted" + (f.shizukuVersion?.let { " $it" } ?: ""))
            ShizukuState.PERMISSION_NEEDED -> Dot.WARN to "running, permission needed"
            ShizukuState.NOT_RUNNING -> Dot.WARN to "installed, not running"
            ShizukuState.NOT_INSTALLED -> Dot.BAD to "not installed"
        }
        val bridge = if (f.bridgeUp) Dot.OK to "reachable (SuperApp's loopback exec route)" else Dot.BAD to "SuperApp not reachable: open it once"
        val conn = connState(f)
        val r = route(f)
        val routeDot = when (conn) {
            ConnState.UP -> Dot.OK
            ConnState.DEGRADED, ConnState.CONNECTING -> Dot.WARN
            ConnState.DOWN -> Dot.BAD
        }
        val routeVal = (r ?: "none") + " - ${f.mode.title()} mode" + when (conn) {
            ConnState.CONNECTING -> ", connecting"
            ConnState.DEGRADED -> ", degraded: " + degradedWhy(f)
            else -> ""
        }
        return listOf(
            row(ROW_DEV, "Developer options", devDot, devVal),
            row(ROW_WD, "Wireless debugging", wd.first, wd.second),
            row(ROW_ADB, "Embedded adb", adb.first, adb.second),
            row(ROW_SERVER, "Local server", server.first, server.second),
            row(ROW_SHIZUKU, "Shizuku", shz.first, shz.second),
        ) + (if (f.bridgeDeclared) listOf(row(ROW_BRIDGE, "SuperApp bridge", bridge.first, bridge.second)) else emptyList()) +
            StatusRow(ROW_ROUTE, "Active route", routeDot, routeVal, at)
    }

    /** Why [ConnState.DEGRADED], in the words of what is missing. */
    fun degradedWhy(f: ChannelFacts): String = when (f.mode) {
        ChannelMode.LOCAL_SERVER -> "commands run on the loopback server, but the adbd connection is down " +
            "(embedded adb ${if (f.adbPaired) "not connected" else "not paired"})"
        else -> "the embedded adb dropped; a lower rung carries the commands"
    }

    /** The one-line chip other screens draw: the route, or why there is none. */
    fun chip(f: ChannelFacts?): String = when {
        f == null -> "ADB Shell: checking..."
        else -> when (connState(f)) {
            ConnState.UP -> "ADB Shell: up (${route(f)})"
            ConnState.DEGRADED -> "ADB Shell: degraded (${route(f)})"
            ConnState.CONNECTING -> "ADB Shell: connecting..."
            ConnState.DOWN -> "ADB Shell: down"
        }
    }
}
