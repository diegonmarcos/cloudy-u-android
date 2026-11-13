package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.system.WirelessDebugKeepAlive.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keep-alive against a fake phone: a settings value, a platform that may
 * clear it again, and an embedded channel that may or may not answer.
 */
class WirelessDebugKeepAliveTest {

    private class FakePhone(
        var wd: Boolean,
        /** What the platform leaves the setting at after we write 1. */
        val platformAccepts: Boolean = true,
        var answers: Boolean = true,
        val reconnectWorks: Boolean = true,
        var wifi: Boolean = true,
    ) : WirelessDebugKeepAlive.Device {
        val calls = mutableListOf<String>()
        override fun wirelessDebuggingOn() = wd
        override fun onWifi() = wifi
        override fun enableWirelessDebugging() { calls += "enable"; wd = platformAccepts }
        override fun channelAnswers() = answers
        override fun dropChannel() { calls += "drop" }
        override fun reconnect(): Pair<Boolean, String> {
            calls += "reconnect"
            if (reconnectWorks) answers = true
            return reconnectWorks to (if (reconnectWorks) "ok" else "no _adb-tls-connect service found")
        }
    }

    @Test fun switchedOff_touchesNothing_evenWithDebuggingOffAndChannelDead() {
        val p = FakePhone(wd = false, answers = false)
        val o = WirelessDebugKeepAlive.tick(false, p)
        assertEquals(emptyList<String>(), p.calls)
        assertFalse(p.wd)
        assertFalse(o.needsOwner)
    }

    @Test fun wifiDropClearedTheSetting_rearmsAndReconnects() {
        // The architect's test: Wi-Fi off/on clears adb_wifi_enabled and the
        // adbd behind the old socket is gone.
        val p = FakePhone(wd = false, answers = false)
        val o = WirelessDebugKeepAlive.tick(true, p)
        assertTrue(p.wd)
        assertEquals(listOf("enable", "drop", "reconnect"), p.calls)
        assertTrue(o.ready); assertTrue(o.rearmed); assertTrue(o.reconnected)
        assertFalse(o.needsOwner)
    }

    @Test fun wifiStillOff_waitsQuietly_thenRecoversWhenItReturns() {
        val p = FakePhone(wd = false, answers = false, wifi = false)
        val waiting = WirelessDebugKeepAlive.tick(true, p)
        assertEquals(emptyList<String>(), p.calls)
        assertFalse(waiting.needsOwner)
        assertEquals(WirelessDebugKeepAlive.CAUSE_NO_WIFI, waiting.cause)
        p.wifi = true
        assertTrue(WirelessDebugKeepAlive.tick(true, p).ready)
    }

    @Test fun settingStillOn_butChannelStale_reconnectsWithoutWriting() {
        val p = FakePhone(wd = true, answers = false)
        val o = WirelessDebugKeepAlive.tick(true, p)
        assertEquals(listOf("drop", "reconnect"), p.calls)
        assertTrue(o.ready); assertFalse(o.rearmed)
    }

    @Test fun healthy_isANoOp() {
        val p = FakePhone(wd = true, answers = true)
        val o = WirelessDebugKeepAlive.tick(true, p)
        assertEquals(emptyList<String>(), p.calls)
        assertTrue(o.ready)
    }

    @Test fun platformRejectsTheWrite_asksTheOwner_andDoesNotPretend() {
        // After a reboot with Wi-Fi not yet up, or on a network never allowed.
        val p = FakePhone(wd = false, platformAccepts = false, answers = false)
        val o = WirelessDebugKeepAlive.tick(true, p)
        assertTrue(o.needsOwner)
        assertFalse(o.ready)
        assertEquals(WirelessDebugKeepAlive.CAUSE_REJECTED, o.cause)
        assertEquals(listOf("enable"), p.calls)
    }

    @Test fun reconnectFails_causeIsKept_notReportedReady() {
        val p = FakePhone(wd = true, answers = false, reconnectWorks = false)
        val o = WirelessDebugKeepAlive.tick(true, p)
        assertFalse(o.ready)
        assertFalse(o.needsOwner)
        assertEquals("no _adb-tls-connect service found", o.cause)
    }

    @Test fun clearedAfterARejectedRearm_doesNotLoopThePrompt() {
        assertFalse(WirelessDebugKeepAlive.shouldTick(Trigger.SETTING_CLEARED, lastNeedsOwner = true))
        assertTrue(WirelessDebugKeepAlive.shouldTick(Trigger.SETTING_CLEARED, lastNeedsOwner = false))
        for (t in Trigger.values().filter { it != Trigger.SETTING_CLEARED })
            assertTrue(t.name, WirelessDebugKeepAlive.shouldTick(t, lastNeedsOwner = true))
    }
}
