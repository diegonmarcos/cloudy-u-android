package com.diegonmarcos.superapp.appstore

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
        val s = ControlStatus.fromBroker(ShellStatus(true, false, "none", "not armed: arm it in the SuperApp"))
        assertEquals(ControlStatus.Channel.DOWN, s.state); assertFalse(s.shellUsable)
        assertTrue(ControlStatus.label(s).contains("Arm in SuperApp"))
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
}
