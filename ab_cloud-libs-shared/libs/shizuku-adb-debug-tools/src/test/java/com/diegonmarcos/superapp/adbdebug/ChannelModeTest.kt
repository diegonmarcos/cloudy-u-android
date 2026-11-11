package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelMode.AUTO
import com.diegonmarcos.superapp.adbdebug.ChannelMode.EMBEDDED_ONLY
import com.diegonmarcos.superapp.adbdebug.ChannelMode.SHIZUKU
import com.diegonmarcos.superapp.adbdebug.ChannelMode.LOCAL_SERVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Privileged channel" setting: which channels run commands in each mode, and that no mode reaches into another's. */
class ChannelModeTest {

    private val ladder = listOf("embedded-adb", "local-server", "shizuku")

    @Test fun modeToChannelOrder() {
        // Local server mode = the adbd connection; the loopback server answers first when it was started.
        assertEquals(listOf("local-server", "embedded-adb"), ChannelSelector.execOrder(LOCAL_SERVER, ladder))
        assertEquals(ladder, ChannelSelector.execOrder(AUTO, ladder))
        assertEquals(listOf("embedded-adb"), ChannelSelector.execOrder(EMBEDDED_ONLY, ladder))
        assertEquals(listOf("shizuku"), ChannelSelector.execOrder(SHIZUKU, ladder))
        // the Restart-server button's launch line: the mode's own channel only
        assertEquals(emptyList<String>(), ChannelSelector.launchSources(SHIZUKU, ladder))
        assertEquals(listOf("embedded-adb"), ChannelSelector.launchSources(LOCAL_SERVER, ladder))
        assertEquals(emptyList<String>(), ChannelSelector.launchSources(EMBEDDED_ONLY, ladder))
        assertEquals(listOf("embedded-adb", "shizuku"), ChannelSelector.launchSources(AUTO, ladder))
    }

    /** Independent channels: no single-channel mode ever touches another mode's channel, to run a command or to launch anything. */
    @Test fun modesNeverCrossCall() {
        val withBridge = listOf("embedded-adb", "local-server", "superapp-bridge", "shizuku")
        for (l in listOf(ladder, withBridge)) {
            assertEquals(setOf("local-server", "embedded-adb"), ChannelSelector.touches(LOCAL_SERVER, l))
            assertEquals(setOf("embedded-adb"), ChannelSelector.touches(EMBEDDED_ONLY, l))
            assertEquals(setOf("shizuku"), ChannelSelector.touches(SHIZUKU, l))
            assertTrue("shizuku" !in ChannelSelector.touches(LOCAL_SERVER, l) && "shizuku" !in ChannelSelector.touches(EMBEDDED_ONLY, l))
        }
    }

    @Test fun parseAndDefaults() {
        assertEquals(LOCAL_SERVER, ChannelMode.parse(null, LOCAL_SERVER))
        assertEquals(AUTO, ChannelMode.parse("auto", LOCAL_SERVER))
        assertEquals(EMBEDDED_ONLY, ChannelMode.parse("embedded", LOCAL_SERVER))
        assertEquals(SHIZUKU, ChannelMode.parse("shizuku", LOCAL_SERVER))
        assertEquals(4, ChannelMode.values().size)
        assertEquals(AUTO, ChannelMode.parse("garbage", AUTO))
    }

    /** A fake world: which channels answer; every question asked of a channel is logged. */
    private class World(val serverUp: Boolean, val adbUp: Boolean, val shizukuUp: Boolean = false) {
        val asked = ArrayList<String>()
        fun usable(n: String): Boolean {
            asked += n
            return when (n) { "local-server" -> serverUp; "embedded-adb" -> adbUp; "shizuku" -> shizukuUp; else -> false }
        }
        fun select(mode: ChannelMode) = ChannelSelector.select(mode, listOf("embedded-adb", "local-server", "shizuku"), { it }, ::usable)
    }

    @Test fun localServerModeRunsOverTheAdbSessionWhenNoServerWasStarted() {
        // The reported case: adb connected, no loopback server. The channel exists: nothing is started, nothing fails.
        val w = World(serverUp = false, adbUp = true, shizukuUp = true)
        assertEquals("embedded-adb", w.select(LOCAL_SERVER))
        assertTrue("Shizuku was asked in Local server mode", "shizuku" !in w.asked)
    }

    @Test fun localServerModePrefersTheLoopbackServerWhenItAnswers() {
        assertEquals("local-server", World(serverUp = true, adbUp = true).select(LOCAL_SERVER))
        // it outlives a dropped adb session
        assertEquals("local-server", World(serverUp = true, adbUp = false).select(LOCAL_SERVER))
    }

    @Test fun localServerModeIsDownWithoutAdbOrServerEvenWithShizukuUp() {
        val w = World(serverUp = false, adbUp = false, shizukuUp = true)
        assertNull(w.select(LOCAL_SERVER))
        assertTrue("shizuku" !in w.asked)
    }

    @Test fun autoIsTheLadderFirstThatAnswers() {
        assertEquals("embedded-adb", World(serverUp = true, adbUp = true).select(AUTO))
        assertEquals("local-server", World(serverUp = true, adbUp = false).select(AUTO))
        assertEquals("shizuku", World(serverUp = false, adbUp = false, shizukuUp = true).select(AUTO))
        assertNull(World(serverUp = false, adbUp = false).select(AUTO))
    }

    @Test fun embeddedOnlySkipsTheServer() {
        val w = World(serverUp = true, adbUp = false, shizukuUp = true)
        assertNull(w.select(EMBEDDED_ONLY))
        assertEquals(listOf("embedded-adb"), w.asked)
        assertEquals("embedded-adb", World(serverUp = true, adbUp = true).select(EMBEDDED_ONLY))
    }

    @Test fun shizukuModeUsesOnlyTheShizukuBinder() {
        val w = World(serverUp = true, adbUp = true, shizukuUp = true)
        assertEquals("shizuku", w.select(SHIZUKU))
        assertEquals(listOf("shizuku"), w.asked)
        // Shizuku down: the server and adb are NOT used instead.
        val down = World(serverUp = true, adbUp = true)
        assertNull(down.select(SHIZUKU))
        assertEquals(listOf("shizuku"), down.asked)
    }

    @Test fun allFourModesPickDifferentRoutesFromTheSameWorld() {
        fun route(m: ChannelMode) = World(serverUp = true, adbUp = true, shizukuUp = true).select(m)
        assertEquals(listOf("local-server", "embedded-adb", "shizuku", "embedded-adb"),
            listOf(LOCAL_SERVER, EMBEDDED_ONLY, SHIZUKU, AUTO).map(::route))
    }

    /** Reconnect re-binds the chosen mode's own channel and no other. */
    @Test fun reconnectRungsPerMode() {
        assertEquals(listOf(EnsureRung.SHIZUKU), ShellAccess.rungs(SHIZUKU))
        assertEquals(listOf(EnsureRung.EMBEDDED_ADB), ShellAccess.rungs(LOCAL_SERVER))
        assertEquals(listOf(EnsureRung.EMBEDDED_ADB), ShellAccess.rungs(EMBEDDED_ONLY))
        assertEquals(listOf(EnsureRung.EMBEDDED_ADB, EnsureRung.SHIZUKU), ShellAccess.rungs(AUTO))
    }

    @Test fun shizukuStateAndLabels() {
        assertEquals(ShizukuState.NOT_INSTALLED, ShizukuState.of(installed = false, running = false, granted = false))
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuState.of(installed = true, running = false, granted = false))
        assertEquals(ShizukuState.PERMISSION_NEEDED, ShizukuState.of(installed = true, running = true, granted = false))
        assertEquals(ShizukuState.UP, ShizukuState.of(installed = true, running = true, granted = true))
        assertEquals("Shizuku: up", ChannelSelector.shizukuLabel(ShizukuState.UP))
        assertEquals("Shizuku: not running", ChannelSelector.shizukuLabel(ShizukuState.NOT_RUNNING))
        assertEquals("Shizuku: permission needed", ChannelSelector.shizukuLabel(ShizukuState.PERMISSION_NEEDED))
        assertEquals("Shizuku: not installed", ChannelSelector.shizukuLabel(ShizukuState.NOT_INSTALLED))
    }

    @Test fun shizukuModeProbeAndTap() {
        class B(val st: ShizukuState, val answers: Boolean) : ControlStatus.Backend {
            override fun paired() = false
            override fun id() = if (answers) "shizuku" to "uid=2000(shell)" else null
            override fun shizukuState() = st
        }
        for (st in listOf(ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING, ShizukuState.PERMISSION_NEEDED)) {
            val s = ControlStatus.Probe(B(st, answers = false)).get()
            assertEquals(st, s.shizuku)
            val tap = ControlStatus.channelAction(s, false, SHIZUKU)
            assertEquals(if (st == ShizukuState.PERMISSION_NEEDED) ControlStatus.Action.REQUEST_SHIZUKU else ControlStatus.Action.OPEN_SHIZUKU, tap)
        }
        val up = ControlStatus.Probe(B(ShizukuState.UP, answers = true)).get()
        assertEquals(ControlStatus.Channel.UP, up.state); assertEquals(ShizukuState.UP, up.shizuku)
        // "up" that never answered is not up
        assertEquals(ShizukuState.NOT_RUNNING, ControlStatus.Probe(B(ShizukuState.UP, answers = false)).get().shizuku)
    }

    @Test fun statusLabel() {
        assertEquals("Wireless Dbg: up (local-server)", ChannelSelector.label("local-server", down = false))
        assertEquals("Wireless Dbg: up (embedded-adb)", ChannelSelector.label("embedded-adb", down = false))
        assertEquals("Wireless Dbg: down - Reconnect", ChannelSelector.label(null, down = true))
        assertEquals("Wireless Dbg: not paired - Pair", ChannelSelector.label(null, down = false))
    }
}
