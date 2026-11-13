package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.shellclient.ShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1682 P2a parity of the readiness core in libs:adb-shell-core: the same verdict, link text and push as before the move
 * (the SuperApp's AdbReadinessTest still runs against this same object), plus the host hooks start unset so a host that
 * installs nothing never crashes.
 */
class AdbReadinessCoreTest {
    private fun up() = ShellStatus(true, true, "embedded-adb", "armed: embedded-adb (live check ok)", 1000L, "")
    private fun down(why: String, detail: String = "not armed: $why") = ShellStatus(true, false, "none", detail, 2000L, why)

    @Test fun verdictIsTheBrokersLiveStatus() {
        val s = AdbReadiness.of(up())
        assertTrue(s.usable); assertEquals("", s.why); assertEquals("embedded-adb", s.via); assertEquals(1000L, s.checkedAt)
        assertEquals("ADB connected (embedded-adb)", s.linkText())
    }

    @Test fun missingReasonFallsBackToDetail() {
        val s = AdbReadiness.of(down("", detail = "broker not installed"))
        assertFalse(s.usable); assertEquals("broker not installed", s.why)
        assertEquals("ADB off: broker not installed - tap to connect", s.linkText())
    }

    @Test fun unknownIsNotUsableAndSaysSo() {
        assertFalse(AdbReadiness.UNKNOWN.usable)
        assertEquals("ADB off: not checked yet - tap to connect", AdbReadiness.UNKNOWN.linkText())
    }

    @Test fun publishReachesFlowAndListenersAndAThrowingListenerIsContained() {
        var heard = 0
        val bad: (ShellStatus) -> Unit = { throw IllegalStateException("boom") }
        val good: (ShellStatus) -> Unit = { heard++ }
        AdbReadiness.listeners_forTest(bad); AdbReadiness.listeners_forTest(good)
        AdbReadiness.publish(up())
        assertTrue(AdbReadiness.state.value.usable); assertEquals(1, heard)
        AdbReadiness.removeListener(bad); AdbReadiness.removeListener(good)
        AdbReadiness.publish(down("off"))
        assertEquals(1, heard); assertFalse(AdbReadiness.state.value.usable)
    }

    @Test fun hostHooksStartUnset() {
        assertNull(AdbReadiness.connector); assertNull(AdbReadiness.hostOpener)
    }

    @Test fun aClientOnlyAppHasNoOwnerHooksAndNeverCrashes() {
        // #1682 P3 step 5: no owner side linked -> install is a no-op and the non-blocking answer is the last verdict only.
        assertNull(AdbReadiness.ownerInstall); assertNull(AdbReadiness.ownerUsableNow)
        AdbReadiness.install()
        var ran = 0
        AdbReadiness.ownerInstall = { ran++ }
        try { AdbReadiness.install(); assertEquals(1, ran) } finally { AdbReadiness.ownerInstall = null }
        AdbReadiness.ownerInstall = { throw IllegalStateException("boom") }
        try { AdbReadiness.install() } finally { AdbReadiness.ownerInstall = null }
    }
}
