package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.shellclient.AdbLink
import com.diegonmarcos.superapp.shellclient.ShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1512 The Store bar's rules over the SuperApp's shell broker (no adb link of its own to pair or probe). */
class StoreChannelTest {
    @Test fun anArmedBrokerIsUpAndUsable() {
        val s = ControlStatus.fromBroker(ShellStatus(true, true, "embedded-adb", "armed"))
        assertEquals(ControlStatus.Channel.UP, s.state); assertTrue(s.shellUsable)
        assertTrue(ControlStatus.label(s).contains("embedded-adb"))
    }

    @Test fun anUnarmedBrokerIsDownAndTheLabelSaysToArmIt() {
        val s = ControlStatus.fromBroker(ShellStatus(true, false, "none", "", 5_000L, "timeout: no answer within 4s"))
        assertEquals(ControlStatus.Channel.DOWN, s.state); assertFalse(s.shellUsable)
        assertTrue(ControlStatus.label(s).contains("Arm in Cloud C3 Webserver") && ControlStatus.label(s).contains("timeout") && ControlStatus.label(s).contains("checked"))
    }

    @Test fun noSuperAppIsNotPaired() {
        val s = ControlStatus.fromBroker(ShellStatus(false, false, "none", ""))
        assertEquals(ControlStatus.Channel.NOT_PAIRED, s.state); assertFalse(s.shellUsable)
    }

    @Test fun playProtectReadsLiveFactsOnly() {
        assertEquals(ControlStatus.Tri.UNKNOWN, ControlStatus.playProtect(ControlStatus.Reading(null, null, null)))
        assertEquals(ControlStatus.Tri.OFF, ControlStatus.playProtect(ControlStatus.Reading(-1, 0, 0)))
        assertEquals(ControlStatus.Tri.ON, ControlStatus.playProtect(ControlStatus.Reading(-1, 1, 0)))
        assertEquals(null, ControlStatus.parseSetting("null")); assertEquals(1, ControlStatus.parseSetting(" 1\n"))
    }

    @Test fun thePlayProtectCommandsAreThreeSettingsWritesTheAllowlistKnows() {
        val off = StorePlayProtect.commands(false)
        assertEquals(3, off.size)
        assertTrue(off.all { it.startsWith("settings put global ") })
        assertTrue(StorePlayProtect.commands(true).all { it.endsWith(" 1") })
    }

    /** #1550 (#1442 regression) the chip's colour is the adb transport: broker up + Wireless debugging off is not green. */
    @Test fun `chip is green only when adb is connected`() {
        fun ink(armed: Boolean, adb: AdbLink, installed: Boolean = true) =
            ControlStatus.ink(ControlStatus.fromBroker(ShellStatus(installed, armed, "local-server", "", 1_000L, adb = adb)))
        assertEquals(ControlStatus.Ink.AMBER, ink(true, AdbLink.WIRELESS_OFF))
        assertEquals(ControlStatus.Ink.AMBER, ink(true, AdbLink.NOT_PAIRED))
        assertEquals(ControlStatus.Ink.AMBER, ink(true, AdbLink.UNREACHABLE))
        assertEquals(ControlStatus.Ink.AMBER, ink(true, AdbLink.ADB_OFF))
        assertEquals(ControlStatus.Ink.GREY, ink(true, AdbLink.UNKNOWN))
        assertEquals(ControlStatus.Ink.GREEN, ink(true, AdbLink.CONNECTED))
        assertEquals(ControlStatus.Ink.RED, ink(false, AdbLink.UNKNOWN, installed = false))
        assertEquals(ControlStatus.Ink.GREY, ControlStatus.ink(null))
        val s = ControlStatus.fromBroker(ShellStatus(true, true, "local-server", "", 1_000L, adb = AdbLink.WIRELESS_OFF))
        assertTrue("broker still usable for shell work", s.shellUsable)
        assertTrue(ControlStatus.label(s).contains("Wireless debugging is off") && ControlStatus.label(s).contains("local-server"))
    }

    /** #1743 the reported chip: Local server profile, embedded session closed after the launch, healthy round trip over the server. */
    @Test fun localServerProfileWithAHealthyRoundTripIsGreenAndNeverSaysAdbdDoesNotAnswer() {
        val link = AdbLink.classify(adbEnabled = true, wireless = true, paired = true, roundTrip = false, sessionOptional = true, shellRoundTrip = true)
        assertEquals(AdbLink.CONNECTED, link)
        val s = ControlStatus.fromBroker(ShellStatus(true, true, "local-server", "armed", 5_000L, "", link, "local-server round trip ok; embedded session closed after launch"))
        assertEquals(ControlStatus.Ink.GREEN, ControlStatus.ink(s))
        val label = ControlStatus.label(s)
        assertTrue(label, label.contains("ADB: connected") && label.contains("local-server") && !label.contains("does not answer"))
        // outside the profile the same facts stay amber with the real reason
        val amber = AdbLink.classify(true, true, true, false)
        assertEquals(AdbLink.UNREACHABLE, amber)
        assertEquals(ControlStatus.Ink.AMBER, ControlStatus.ink(ControlStatus.fromBroker(ShellStatus(true, true, "local-server", "armed", 5_000L, "", amber))))
    }

    /** The ADB button says only ON / OFF, from the same verdict as the colour; the long status stays in [ControlStatus.label]. */
    @Test fun adbButtonIsOnOffOnlyAndTheDetailIsKept() {
        fun st(armed: Boolean, adb: AdbLink, installed: Boolean = true) =
            ControlStatus.fromBroker(ShellStatus(installed, armed, "local-server", "armed", 5_000L, "", adb, "local-server round trip ok; embedded session closed after launch"))
        assertEquals("ADB: ON", ControlStatus.buttonLabel(st(true, AdbLink.CONNECTED)))
        assertEquals("ADB: OFF", ControlStatus.buttonLabel(st(true, AdbLink.WIRELESS_OFF)))
        assertEquals("ADB: OFF", ControlStatus.buttonLabel(st(true, AdbLink.UNREACHABLE)))
        assertEquals("ADB: OFF", ControlStatus.buttonLabel(st(false, AdbLink.UNKNOWN, installed = false)))
        assertEquals("ADB: checking...", ControlStatus.buttonLabel(st(true, AdbLink.UNKNOWN)))
        val full = ControlStatus.label(st(true, AdbLink.CONNECTED))
        assertTrue(full, full.contains("local-server round trip ok") && full.contains("Shell: Cloud C3 Webserver") && full.contains("checked"))
        for (a in AdbLink.values()) for (armed in listOf(true, false)) {   // ON exactly when the colour is green
            val s = st(armed, a)
            assertEquals(ControlStatus.ink(s) == ControlStatus.Ink.GREEN, ControlStatus.buttonLabel(s) == "ADB: ON")
        }
    }
}
