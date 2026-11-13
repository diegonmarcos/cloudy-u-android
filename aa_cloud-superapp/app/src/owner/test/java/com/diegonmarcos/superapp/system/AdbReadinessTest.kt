package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.configs.AdbTester
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1678 The one readiness source: verdict from the broker's live status, flips pushed to the flow, link text names what is missing. */
class AdbReadinessTest {

    private fun up() = ShellStatus(true, true, "embedded-adb", "armed: embedded-adb (live check ok)", 1000L, "")
    private fun down(why: String) = ShellStatus(true, false, "none", "not armed: $why", 2000L, why)

    @Test fun connectedStatusIsUsable() {
        val s = AdbReadiness.of(up())
        assertTrue(s.usable); assertEquals("", s.why); assertEquals("embedded-adb", s.via)
    }

    @Test fun downStatusSaysWhatIsMissingAndIsALink() {
        val s = AdbReadiness.of(down("wireless debugging is off"))
        assertFalse(s.usable)
        assertEquals("ADB off: wireless debugging is off - tap to connect", s.linkText())
    }

    @Test fun stateChangeIsPublishedToFlowAndListeners() {
        var heard: ShellStatus? = null
        val l: (ShellStatus) -> Unit = { heard = it }
        AdbReadiness.listeners_forTest(l)
        AdbReadiness.publish(down("no adb session"))
        assertFalse(AdbReadiness.state.value.usable)
        AdbReadiness.publish(up())
        assertTrue(AdbReadiness.state.value.usable)
        assertEquals(true, heard?.armed)
        AdbReadiness.removeListener(l)
    }

    @Test fun needsAdbMarkerStaysTheRowMarker() {
        assertTrue(SystemGroups.NEEDS_ADB.startsWith("needs ADB"))
        assertFalse(SystemGroups.NEEDS_ADB.contains("arm the channel"))
        assertEquals(SystemGroups.NEEDS_ADB, SystemGroups.adbLines("thermal", null, 5).single().value)
    }

    @Test fun testerLinesReportChannelExitAndError() {
        val ok = AdbTester.line("getprop ro.build.version.release", ShellResult.Ok(0, "15\n", "", "local-server"), 12)
        assertTrue(ok.ok); assertEquals("local-server", ok.channel); assertEquals(0, ok.exit); assertEquals("15", ok.output)
        val busy = AdbTester.line("dumpsys battery", ShellResult.Failed("busy: 3 calls already running"), 1)
        assertFalse(busy.ok); assertTrue(busy.output.contains("busy"))
        val deny = AdbTester.line("x", ShellResult.Denied("no rule"), 1)
        assertTrue(deny.output.startsWith("refused"))
        assertEquals("none", AdbTester.line("x", ShellResult.NeedsArm, 1).channel)
        assertFalse(AdbTester.line("x", null, 1).ok)
    }
}
