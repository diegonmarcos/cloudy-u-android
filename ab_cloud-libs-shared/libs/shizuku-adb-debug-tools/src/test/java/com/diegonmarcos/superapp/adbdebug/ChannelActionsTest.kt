package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelAction.DISCONNECT
import com.diegonmarcos.superapp.adbdebug.ChannelAction.RECONNECT
import com.diegonmarcos.superapp.adbdebug.ChannelAction.RESTART_SERVER
import com.diegonmarcos.superapp.adbdebug.ChannelAction.STOP_SERVER
import com.diegonmarcos.superapp.adbdebug.ChannelAction.TEST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which "Active connection" buttons are live in each state. */
class ChannelActionsTest {

    private val up = ChannelFacts(ChannelMode.LOCAL_SERVER, 1L, adbPaired = true, adbConnected = true, serverPort = 38099,
        serverRunning = true, serverUid = "uid 2000", shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true)
    private fun on(f: ChannelFacts) = ChannelActions.enablement(f).filterValues { it.enabled }.keys

    @Test fun upEverythingApplies() {
        assertEquals(ConnState.UP, ChannelState.connState(up))
        assertEquals(setOf(DISCONNECT, RECONNECT, RESTART_SERVER, STOP_SERVER, TEST), on(up))
        assertTrue(ChannelActions.enablement(up).values.all { it.why == null })
    }

    @Test fun connectingDisablesEverything() {
        val e = ChannelActions.enablement(up.copy(connecting = true))
        assertEquals(ChannelAction.values().toSet(), e.keys)
        assertTrue(e.values.none { it.enabled })
        assertTrue(e.values.all { it.why == "a connect is running" })
    }

    @Test fun degradedKeepsTestAndStopButCannotRestart() {
        val d = up.copy(adbConnected = false, shizukuRunning = false)
        assertEquals(ConnState.DEGRADED, ChannelState.connState(d))
        assertEquals(setOf(RECONNECT, STOP_SERVER, TEST), on(d))
        assertEquals("no adb session to start it with", ChannelActions.enablement(d)[RESTART_SERVER]!!.why)
        // Shizuku being up changes nothing in Local server mode: its channel is never borrowed to launch the server
        val shizukuUp = up.copy(adbConnected = false)
        assertEquals(setOf(RECONNECT, STOP_SERVER, TEST), on(shizukuUp))
        assertEquals("no adb session to start it with", ChannelActions.enablement(shizukuUp)[RESTART_SERVER]!!.why)
        // the Auto ladder may use either
        assertTrue(ChannelActions.enablement(shizukuUp.copy(mode = ChannelMode.AUTO))[RESTART_SERVER]!!.enabled)
        assertEquals("no adb or Shizuku session to start it with",
            ChannelActions.enablement(d.copy(mode = ChannelMode.AUTO))[RESTART_SERVER]!!.why)
        assertEquals("embedded adb is not connected", ChannelActions.enablement(d)[DISCONNECT]!!.why)
    }

    @Test fun noServerIsStillUpOverAdbAndDownOnlyWhenNothingAnswers() {
        val down = up.copy(serverRunning = false)           // adb still connected, the optional server not started
        assertEquals(ConnState.UP, ChannelState.connState(down))
        assertEquals(setOf(DISCONNECT, RECONNECT, RESTART_SERVER, TEST), on(down))
        val dead = down.copy(adbConnected = false, shizukuRunning = false)
        assertEquals(setOf(RECONNECT), on(dead))
        assertEquals("the local server is not running", ChannelActions.enablement(dead)[STOP_SERVER]!!.why)
        assertEquals("no route to test", ChannelActions.enablement(dead)[TEST]!!.why)
    }

    @Test fun modesThatDoNotUseTheServerNeverOfferItsButtons() {
        val emb = up.copy(mode = ChannelMode.EMBEDDED_ONLY)
        assertEquals(false, ChannelActions.enablement(emb)[RESTART_SERVER]!!.enabled)
        assertEquals("Embedded adb mode does not use the local server", ChannelActions.enablement(emb)[RESTART_SERVER]!!.why)
        assertEquals(setOf(DISCONNECT, RECONNECT, STOP_SERVER, TEST), on(emb))     // a leftover server can still be stopped
        val shz = up.copy(mode = ChannelMode.SHIZUKU, adbConnected = false)
        assertEquals(setOf(RECONNECT, STOP_SERVER, TEST), on(shz))
        assertEquals("Shizuku is stopped from the Shizuku app", ChannelActions.enablement(shz)[DISCONNECT]!!.why)
    }

    @Test fun anAppWithNoServerOfItsOwnNeverOffersServerButtons() {
        val term = up.copy(mode = ChannelMode.AUTO, ownsServer = false, serverRunning = false)
        assertEquals("this app runs no server of its own", ChannelActions.enablement(term)[RESTART_SERVER]!!.why)
        assertEquals("this app runs no server of its own", ChannelActions.enablement(term)[STOP_SERVER]!!.why)
        assertEquals(setOf(DISCONNECT, RECONNECT, TEST), on(term))
    }

    @Test fun everyDisabledButtonSaysWhy() {
        val states = listOf(up, up.copy(serverRunning = false), up.copy(adbConnected = false, shizukuRunning = false, serverRunning = false),
            up.copy(mode = ChannelMode.SHIZUKU), up.copy(mode = ChannelMode.EMBEDDED_ONLY), up.copy(connecting = true))
        for (f in states) for ((a, e) in ChannelActions.enablement(f)) {
            if (e.enabled) assertNull("$a", e.why) else assertNotNull("$a ${f.mode}", e.why)
        }
    }
}
