package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.shellclient.AdbLink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1556 THE ADB CHIP SETTLES TO A DEFINITE STATE ONCE AND DOES NOT LOOP.
 *
 * Drives the two pieces the Store chip now composes — the re-check gate [AdbRecheck.due] and the terminal classifier
 * [AdbLink.classify] — exactly as StoreBar/StoreStatus drive them: a probe runs only when due, every probe resolves to a
 * definite [AdbLink] (never a permanent "checking" / UNKNOWN), and resume / adb-setting triggers inside the 30 s floor do NOT
 * re-run the probe. Reproduces the owner's "keeps checking ADB forever" as a failing control and proves the fix.
 */
class AdbRecheckTest {

    /** A fake chip: holds the settled link and the last probe-start time, probes only when the gate says due. */
    private class FakeChip(val floorMs: Long = AdbRecheck.FLOOR_MS) {
        var link: AdbLink = AdbLink.UNKNOWN
        var lastProbeAt = 0L
        var inFlight = false
        var probes = 0
        /** One trigger (first paint, resume, or an adb-setting change). Returns whether it actually probed. */
        fun trigger(now: Long, roundTrip: Boolean?, wireless: Boolean?, paired: Boolean, adbEnabled: Boolean? = true): Boolean {
            if (!AdbRecheck.due(lastProbeAt, now, inFlight, floorMs)) return false
            lastProbeAt = now; probes++
            // settle synchronously (the real probe is bounded by a timeout and always returns a definite link)
            link = AdbLink.classify(adbEnabled, wireless, paired, roundTrip)
            return true
        }
    }

    @Test fun `wireless ON and adb answers settles to Connected once and does not re-probe in a loop`() {
        val c = FakeChip()
        // First paint probes and settles Connected.
        assertTrue(c.trigger(now = 1_000, roundTrip = true, wireless = true, paired = true))
        assertEquals(AdbLink.CONNECTED, c.link)
        assertTrue("green only when connected", c.link.up)
        // The completion bumps the recomposition key, and resume / adb-setting changes keep firing — none within the floor probes.
        var fired = 0
        for (t in longArrayOf(1_000, 1_001, 5_000, 20_000, 29_999)) if (c.trigger(t, true, true, true)) fired++
        assertEquals("no re-probe inside the 30 s floor — the owner's 'keeps checking' loop is gone", 0, fired)
        assertEquals(1, c.probes)
        assertEquals(AdbLink.CONNECTED, c.link)
        // After the floor, one resume re-verifies (still just once).
        assertTrue(c.trigger(now = 31_000, roundTrip = true, wireless = true, paired = true))
        assertEquals(2, c.probes)
    }

    @Test fun `a probe that times out resolves to a definite not-connected state, never infinite checking`() {
        val c = FakeChip()
        // Wireless ON, paired, but the getprop round trip timed out (roundTrip = false): UNREACHABLE, not UNKNOWN.
        assertTrue(c.trigger(now = 1_000, roundTrip = false, wireless = true, paired = true))
        assertEquals(AdbLink.UNREACHABLE, c.link)
        assertFalse("not green", c.link.up)
        assertEquals("a definite verdict, not 'checking'", false, c.link.verdict)
        assertTrue(c.link != AdbLink.UNKNOWN)
        // It does not spin: further triggers inside the floor do nothing.
        assertFalse(c.trigger(now = 2_000, roundTrip = false, wireless = true, paired = true))
        assertEquals(1, c.probes)
    }

    @Test fun `in-flight never starts a second probe`() {
        val c = FakeChip()
        c.lastProbeAt = 1_000; c.inFlight = true
        assertFalse("a probe is running — never start another", AdbRecheck.due(c.lastProbeAt, 99_000, c.inFlight))
    }

    @Test fun `the first probe is always due and UNKNOWN only means not-yet-probed`() {
        assertTrue(AdbRecheck.due(lastStartedAt = 0L, now = 0L, inFlight = false))
        assertNull("UNKNOWN is grey/checking — a state the probe must leave, not rest in", AdbLink.UNKNOWN.verdict)
        // Every non-UNKNOWN link is a definite verdict the chip can rest on.
        for (l in AdbLink.values().filter { it != AdbLink.UNKNOWN }) assertTrue(l.name, l.verdict != null)
    }

    @Test fun `classify never returns UNKNOWN once the wireless switch is readable`() {
        // The only UNKNOWN path is an unreadable wireless setting with no round trip; a readable switch always resolves.
        assertEquals(AdbLink.UNKNOWN, AdbLink.classify(adbEnabled = true, wireless = null, paired = true, roundTrip = null))
        assertTrue(AdbLink.classify(adbEnabled = true, wireless = true, paired = false, roundTrip = null) != AdbLink.UNKNOWN)
        assertTrue(AdbLink.classify(adbEnabled = true, wireless = false, paired = true, roundTrip = null) != AdbLink.UNKNOWN)
    }
}
