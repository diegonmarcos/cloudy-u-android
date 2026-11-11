package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelMode.AUTO
import com.diegonmarcos.superapp.adbdebug.ChannelMode.EMBEDDED_ONLY
import com.diegonmarcos.superapp.adbdebug.ChannelMode.LOCAL_SERVER
import com.diegonmarcos.superapp.adbdebug.ChannelMode.SHIZUKU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status table (one row per layer), the active route and the connection state. */
class ChannelStateTest {

    private val t0 = 1_700_000_000_000L
    private fun facts(mode: ChannelMode = LOCAL_SERVER, f: ChannelFacts.() -> ChannelFacts = { this }) =
        ChannelFacts(mode, t0, serverPort = 38099).f()
    private fun row(rows: List<StatusRow>, id: String) = rows.first { it.id == id }

    /** Everything healthy: dev options, wireless debugging on Wi-Fi, adb connected, server up, Shizuku up. */
    private val healthy = ChannelFacts(LOCAL_SERVER, t0, devOptions = true, wirelessDebug = true, onWifi = true,
        notificationsAllowed = true, adbPaired = true, adbConnected = true, adbEndpoint = "10.0.0.9:37123",
        serverPort = 38099, serverRunning = true, serverUid = "uid 2000", serverUptime = "01:02", serverPid = "4242",
        shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true, shizukuVersion = "v13")

    @Test fun oneRowPerLayerInOrderAllStamped() {
        val rows = ChannelState.rows(healthy)
        assertEquals(listOf("dev-options", "wireless-debugging", "embedded-adb", "local-server", "shizuku", "active-route"), rows.map { it.id })
        assertEquals(listOf("Developer options", "Wireless debugging", "Embedded adb", "Local server", "Shizuku", "Active route"), rows.map { it.label })
        assertTrue(rows.all { it.probedAt == t0 })
        assertTrue(rows.all { it.dot == Dot.OK })
    }

    @Test fun developerOptionsRow() {
        assertEquals(Dot.OK to "enabled", row(ChannelState.rows(healthy), "dev-options").let { it.dot to it.value })
        assertEquals(Dot.BAD to "off", row(ChannelState.rows(healthy.copy(devOptions = false)), "dev-options").let { it.dot to it.value })
        assertEquals(Dot.UNKNOWN to "unknown", row(ChannelState.rows(healthy.copy(devOptions = null)), "dev-options").let { it.dot to it.value })
    }

    @Test fun wirelessDebuggingRow() {
        fun r(wd: Boolean?, wifi: Boolean?) = row(ChannelState.rows(healthy.copy(wirelessDebug = wd, onWifi = wifi)), "wireless-debugging").let { it.dot to it.value }
        assertEquals(Dot.OK to "on, on Wi-Fi", r(true, true))
        assertEquals(Dot.WARN to "on, but not on Wi-Fi", r(true, false))
        assertEquals(Dot.OK to "on", r(true, null))
        assertEquals(Dot.BAD to "off", r(false, true))
        assertEquals(Dot.UNKNOWN to "unknown", r(null, true))
    }

    @Test fun embeddedAdbRow() {
        fun r(paired: Boolean, connected: Boolean) = row(ChannelState.rows(healthy.copy(adbPaired = paired, adbConnected = connected)), "embedded-adb").let { it.dot to it.value }
        assertEquals(Dot.OK to "paired, connected 10.0.0.9:37123", r(true, true))
        assertEquals(Dot.WARN to "paired, not connected", r(true, false))
        assertEquals(Dot.BAD to "not paired", r(false, false))
    }

    @Test fun localServerRow() {
        fun r(f: ChannelFacts) = row(ChannelState.rows(f), "local-server").let { it.dot to it.value }
        assertEquals(Dot.OK to "port 38099, uid 2000, up 01:02, pid 4242", r(healthy))
        // never started while the adb session carries the commands: optional, grey, not a fault
        assertEquals(Dot.UNKNOWN to "port 38099, not started (optional: commands run over embedded adb)", r(healthy.copy(serverRunning = false)))
        assertEquals(Dot.BAD to "port 38099, not running", r(healthy.copy(serverRunning = false, adbConnected = false)))
        assertEquals(Dot.OK to "port 38099", r(healthy.copy(serverUid = null, serverUptime = null, serverPid = null)))
        // a server that answers as the app's own uid is not the shell
        assertEquals(Dot.WARN, r(healthy.copy(serverUid = "uid 10234")).first)
        assertEquals(Dot.OK, r(healthy.copy(serverUid = "uid 0")).first)
    }

    @Test fun shizukuRow() {
        fun r(inst: Boolean, run: Boolean, grant: Boolean) =
            row(ChannelState.rows(healthy.copy(mode = SHIZUKU, shizukuInstalled = inst, shizukuRunning = run, shizukuGranted = grant)), "shizuku").let { it.dot to it.value }
        assertEquals(Dot.OK to "installed, running, permitted v13", r(true, true, true))
        assertEquals(Dot.WARN to "running, permission needed", r(true, true, false))
        assertEquals(Dot.WARN to "installed, not running", r(true, false, false))
        assertEquals(Dot.BAD to "not installed", r(false, false, false))
        // Local server mode never uses Shizuku: a Shizuku that is down is grey there, not a fault
        val local = row(ChannelState.rows(healthy.copy(shizukuRunning = false)), "shizuku")
        assertEquals(Dot.UNKNOWN to "installed, not running (not used in Local server mode)", local.dot to local.value)
    }

    @Test fun routeFollowsTheModeAndTheLadder() {
        assertEquals("local-server", ChannelState.route(healthy))                       // Local server mode: the loopback server first when it answers
        assertEquals("embedded-adb", ChannelState.route(healthy.copy(mode = EMBEDDED_ONLY)))
        assertEquals("shizuku", ChannelState.route(healthy.copy(mode = SHIZUKU)))
        assertEquals("embedded-adb", ChannelState.route(healthy.copy(mode = AUTO)))      // first rung wins
        assertEquals("local-server", ChannelState.route(healthy.copy(mode = AUTO, adbConnected = false)))
        assertEquals("shizuku", ChannelState.route(healthy.copy(mode = AUTO, adbConnected = false, serverRunning = false)))
        assertEquals("embedded-adb", ChannelState.route(healthy.copy(serverRunning = false)))   // the adbd connection IS the channel: no server needed
        assertNull(ChannelState.route(healthy.copy(serverRunning = false, adbConnected = false)))   // Shizuku up, and still never used in Local server mode
        assertNull(ChannelState.route(healthy.copy(mode = SHIZUKU, shizukuGranted = false)))
        assertNull(ChannelState.route(healthy.copy(mode = EMBEDDED_ONLY, adbConnected = false)))
    }

    @Test fun routeRowCarriesRouteAndMode() {
        val up = row(ChannelState.rows(healthy), "active-route")
        assertEquals(Dot.OK to "local-server - Local server mode", up.dot to up.value)
        val adb = row(ChannelState.rows(healthy.copy(serverRunning = false)), "active-route")
        assertEquals(Dot.OK to "embedded-adb - Local server mode", adb.dot to adb.value)
        val down = row(ChannelState.rows(healthy.copy(serverRunning = false, adbConnected = false)), "active-route")
        assertEquals(Dot.BAD to "none - Local server mode", down.dot to down.value)
        val deg = row(ChannelState.rows(healthy.copy(adbConnected = false, shizukuRunning = false)), "active-route")
        assertEquals(Dot.WARN, deg.dot)
        assertTrue(deg.value, deg.value.startsWith("local-server - Local server mode, degraded: commands run on the loopback server, but the adbd connection is down"))
        val con = row(ChannelState.rows(healthy.copy(connecting = true)), "active-route")
        assertEquals(Dot.WARN, con.dot); assertTrue(con.value.endsWith("connecting"))
    }

    @Test fun layersTheModeNeverUsesAreGreyNotRed() {
        // Shizuku mode: wireless debugging / adb / dev options / server are not used
        val rows = ChannelState.rows(facts(SHIZUKU) { copy(wirelessDebug = false, devOptions = false) })
        for (id in listOf("dev-options", "wireless-debugging", "embedded-adb", "local-server")) {
            assertEquals(id, Dot.UNKNOWN, row(rows, id).dot)
            assertTrue(id, row(rows, id).value.endsWith("(not used in Shizuku mode)"))
        }
        // Local server mode: Shizuku is another channel, never used
        val l = ChannelState.rows(facts(LOCAL_SERVER))
        assertEquals(Dot.UNKNOWN, row(l, "shizuku").dot); assertTrue(row(l, "shizuku").value.endsWith("(not used in Local server mode)"))
        assertEquals(false, ChannelState.used("shizuku", LOCAL_SERVER)); assertEquals(false, ChannelState.used("shizuku", EMBEDDED_ONLY))
        assertEquals(false, ChannelState.used("embedded-adb", SHIZUKU)); assertEquals(false, ChannelState.used("local-server", SHIZUKU))
        // Embedded-only mode: the server and Shizuku are not used
        val e = ChannelState.rows(facts(EMBEDDED_ONLY))
        assertEquals(Dot.UNKNOWN, row(e, "local-server").dot); assertEquals(Dot.UNKNOWN, row(e, "shizuku").dot)
        assertEquals(Dot.BAD, row(e, "embedded-adb").dot)
        // a healthy unused layer is still green
        assertEquals(Dot.OK, row(ChannelState.rows(healthy.copy(mode = EMBEDDED_ONLY)), "local-server").dot)
    }

    @Test fun anAppWithNoServerOfItsOwnGreysTheServerRow() {
        val term = healthy.copy(mode = AUTO, ownsServer = false, serverRunning = false)
        val r = row(ChannelState.rows(term), "local-server")
        assertEquals(Dot.UNKNOWN, r.dot)
        assertTrue(r.value, r.value.endsWith("(this app runs no server of its own)"))
        assertEquals(false, ChannelState.used("local-server", AUTO, ownsServer = false))
        assertEquals(ConnState.UP, ChannelState.connState(term))                 // embedded adb carries it
    }

    /** A terminal declares the SuperApp bridge as a provider; it carries commands in Auto mode only. */
    @Test fun theSuperAppBridgeIsALayerOnlyForAppsThatDeclareIt() {
        val term = ChannelFacts(AUTO, t0, devOptions = true, wirelessDebug = true, onWifi = true, ownsServer = false,
            bridgeDeclared = true, bridgeUp = true)
        val rows = ChannelState.rows(term)
        assertEquals(listOf("dev-options", "wireless-debugging", "embedded-adb", "local-server", "shizuku", "superapp-bridge", "active-route"), rows.map { it.id })
        assertEquals(Dot.OK, row(rows, "superapp-bridge").dot)
        assertEquals("superapp-bridge", ChannelState.route(term))
        assertEquals(ConnState.UP, ChannelState.connState(term))
        assertEquals(Dot.BAD, row(ChannelState.rows(term.copy(bridgeUp = false)), "superapp-bridge").dot)
        assertNull(ChannelState.route(term.copy(bridgeUp = false)))
        // the bridge never runs commands in the other modes
        assertNull(ChannelState.route(term.copy(mode = EMBEDDED_ONLY)))
        assertNull(ChannelState.route(term.copy(mode = SHIZUKU)))
        assertEquals(Dot.UNKNOWN, row(ChannelState.rows(term.copy(mode = EMBEDDED_ONLY, bridgeUp = false)), "superapp-bridge").dot)
        // an app that does not declare it has no such row, and a stray "up" does nothing
        assertTrue(ChannelState.rows(healthy.copy(mode = AUTO, bridgeUp = true)).none { it.id == "superapp-bridge" })
        assertEquals("embedded-adb", ChannelState.route(healthy.copy(mode = AUTO, bridgeUp = true)))
        assertNull(ChannelState.route(healthy.copy(mode = AUTO, adbConnected = false, serverRunning = false, shizukuGranted = false, bridgeUp = true)))
    }

    @Test fun connectionStates() {
        assertEquals(ConnState.UP, ChannelState.connState(healthy))
        assertEquals(ConnState.CONNECTING, ChannelState.connState(healthy.copy(connecting = true)))
        assertEquals(ConnState.CONNECTING, ChannelState.connState(facts().copy(connecting = true)))     // even with no route
        assertEquals(ConnState.DOWN, ChannelState.connState(facts()))
        // Local server mode: the adbd connection alone is UP (no server needed); nothing answering is DOWN whatever Shizuku says
        assertEquals(ConnState.UP, ChannelState.connState(healthy.copy(serverRunning = false)))
        assertEquals(ConnState.DOWN, ChannelState.connState(healthy.copy(serverRunning = false, adbConnected = false)))
        // the loopback server carries the commands while the adbd connection is down -> degraded, with or without Shizuku
        assertEquals(ConnState.DEGRADED, ChannelState.connState(healthy.copy(adbConnected = false, shizukuRunning = false)))
        assertEquals(ConnState.DEGRADED, ChannelState.connState(healthy.copy(adbConnected = false)))
        // Auto: a paired adb that dropped while the server carries the commands -> degraded
        assertEquals(ConnState.DEGRADED, ChannelState.connState(healthy.copy(mode = AUTO, adbConnected = false)))
        assertEquals(ConnState.UP, ChannelState.connState(healthy.copy(mode = AUTO)))
        assertEquals(ConnState.UP, ChannelState.connState(healthy.copy(mode = AUTO, adbPaired = false, adbConnected = false)))
        assertEquals(ConnState.UP, ChannelState.connState(healthy.copy(mode = EMBEDDED_ONLY)))
        assertEquals(ConnState.UP, ChannelState.connState(healthy.copy(mode = SHIZUKU)))
        assertEquals(ConnState.DOWN, ChannelState.connState(healthy.copy(mode = SHIZUKU, shizukuRunning = false)))
    }

    @Test fun launchSourceRules() {
        assertTrue(ChannelState.canLaunchServer(healthy))
        assertEquals(false, ChannelState.canLaunchServer(healthy.copy(adbConnected = false)))           // Local server mode never launches through Shizuku
        assertTrue(ChannelState.canLaunchServer(healthy.copy(mode = AUTO, adbConnected = false)))       // the Auto ladder may
        assertEquals(false, ChannelState.canLaunchServer(healthy.copy(mode = SHIZUKU)))
        assertEquals(false, ChannelState.canLaunchServer(healthy.copy(ownsServer = false)))
        assertEquals(false, ChannelState.canLaunchServer(healthy.copy(mode = AUTO, adbConnected = false, shizukuGranted = false)))
        assertEquals(false, ChannelState.canLaunchServer(healthy.copy(mode = EMBEDDED_ONLY)))           // that mode never launches a server
    }

    @Test fun chipTextIsOneLine() {
        assertEquals("ADB Shell: checking...", ChannelState.chip(null))
        assertEquals("ADB Shell: up (local-server)", ChannelState.chip(healthy))
        assertEquals("ADB Shell: up (embedded-adb)", ChannelState.chip(healthy.copy(serverRunning = false)))
        assertEquals("ADB Shell: down", ChannelState.chip(healthy.copy(serverRunning = false, adbConnected = false)))
        assertEquals("ADB Shell: connecting...", ChannelState.chip(healthy.copy(connecting = true)))
        assertEquals("ADB Shell: degraded (local-server)", ChannelState.chip(healthy.copy(adbConnected = false, shizukuRunning = false)))
    }

    /** #1647: Shizuku running and permitted is not a route once a round trip over its binder was asked and went unanswered. */
    @Test fun shizukuIsARouteOnlyWhileItsRoundTripAnswers() {
        val up = facts(SHIZUKU) { copy(shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true) }
        assertEquals("shizuku", ChannelState.route(up.copy(shizukuAnswers = true)))
        assertEquals(ConnState.UP, ChannelState.connState(up.copy(shizukuAnswers = true)))
        assertNull(ChannelState.route(up.copy(shizukuAnswers = false)))
        assertEquals(ConnState.DOWN, ChannelState.connState(up.copy(shizukuAnswers = false)))
        // not asked (another mode's read): the binder's word stands, as before
        assertEquals("shizuku", ChannelState.route(up))
    }
}
