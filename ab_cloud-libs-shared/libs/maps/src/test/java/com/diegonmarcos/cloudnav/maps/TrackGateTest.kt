package com.diegonmarcos.cloudnav.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1770 the event-driven tracking gate: still -> moving -> still, geofence exit, passive fixes accepted and filtered. */
class TrackGateTest {
    private val t0 = 1_800_000_000_000L
    private val home = GateFix(40.4168, -3.7038, 10f, t0)
    /** A point [m] metres north of [home] at [t]. */
    private fun north(m: Double, t: Long, acc: Float? = 10f) = GateFix(home.lat + m / 111_320.0, home.lon, acc, t)

    @Test fun `still holds the passive listener and one low-rate sample every 10 to 15 minutes`() {
        val p = TrackGate.plan(GateState())
        assertTrue(p.passive)
        assertEquals(TrackGate.STILL_SAMPLE, p.fused)
        assertTrue(p.fused!!.minIntervalMs >= 10 * 60_000L && p.fused!!.maxUpdateDelayMs <= 15 * 60_000L)
    }

    @Test fun `battery saver drops the still sample but keeps the passive listener`() {
        val p = TrackGate.plan(GateState(), powerSave = true)
        assertTrue(p.passive); assertNull(p.fused)
    }

    @Test fun `a still sample is stored although the phone did not move`() {
        val s = GateState(anchor = home, lastAccepted = home)
        val o = TrackGate.offer(s, north(3.0, t0 + 12 * 60_000L), t0 + 12 * 60_000L + 1)
        assertTrue(o.accepted)
    }

    @Test fun `still to moving to still follows the activity transitions`() {
        var s = TrackGate.offer(GateState(), home, t0 + 1_000).state
        s = TrackGate.signal(s, GateSignal.ACTIVITY_MOVING, t0 + 2_000)
        assertEquals(GateMode.MOVING, s.mode)
        val p = TrackGate.plan(s).fused
        assertNotNull(p); assertEquals(60_000L, p!!.intervalMs); assertEquals(5 * 60_000L, p.maxUpdateDelayMs); assertEquals(25f, p.minDistanceM)
        s = TrackGate.offer(s, north(300.0, t0 + 120_000), t0 + 121_000).state
        s = TrackGate.signal(s, GateSignal.ACTIVITY_STILL, t0 + 600_000)
        assertEquals(GateMode.STILL, s.mode); assertEquals(TrackGate.STILL_SAMPLE, TrackGate.plan(s).fused)
        assertEquals(s.lastAccepted, s.anchor)   // the place the phone stopped at
    }

    @Test fun `a lapsed hold ends moving, a weak signal never shortens a long hold`() {
        var s = TrackGate.signal(GateState(), GateSignal.ACTIVITY_MOVING, t0)
        s = TrackGate.signal(s, GateSignal.SIGNIFICANT_MOTION, t0 + 1_000)
        assertEquals(t0 + TrackGate.MOVE_HOLD_MS, s.untilMs)
        assertEquals(GateMode.MOVING, TrackGate.expire(s, t0 + TrackGate.MOVE_HOLD_MS - 1).mode)
        assertEquals(GateMode.STILL, TrackGate.expire(s, t0 + TrackGate.MOVE_HOLD_MS).mode)
        val weak = TrackGate.signal(GateState(), GateSignal.SIGNIFICANT_MOTION, t0)
        assertEquals(t0 + TrackGate.MOTION_HOLD_MS, weak.untilMs)
    }

    @Test fun `geofence exit and the user's Start wake the tracker`() {
        assertEquals(GateMode.MOVING, TrackGate.signal(GateState(), GateSignal.GEOFENCE_EXIT, t0).mode)
        val st = TrackGate.signal(GateState(), GateSignal.USER_START, t0)
        assertEquals(GateMode.MOVING, st.mode); assertEquals(t0 + TrackGate.START_HOLD_MS, st.untilMs)
    }

    @Test fun `a trusted passive fix far from the stable place is a geofence exit`() {
        val still = GateState(anchor = home, lastAccepted = home)
        val o = TrackGate.offer(still, north(400.0, t0 + 60_000, acc = 20f), t0 + 61_000)
        assertTrue(o.accepted); assertEquals(GateMode.MOVING, o.state.mode)
        // a loose fix (accuracy 80 m) the same distance away is kept but does not wake the tracker
        val loose = TrackGate.offer(still, north(400.0, t0 + 60_000, acc = 80f), t0 + 61_000)
        assertTrue(loose.accepted); assertEquals(GateMode.STILL, loose.state.mode)
    }

    @Test fun `passive fixes are filtered by accuracy age duplicates and displacement`() {
        val s = GateState(mode = GateMode.MOVING, untilMs = t0 + 1_000_000, lastAccepted = home)
        val now = t0 + 120_000
        assertEquals("accuracy", TrackGate.offer(s, north(100.0, t0 + 60_000, acc = 150f), now).reason)
        assertEquals("accuracy", TrackGate.offer(s, north(100.0, t0 + 60_000, acc = null), now).reason)
        assertEquals("age", TrackGate.offer(s, north(100.0, t0 - 1_500_000), now).reason)
        assertEquals("age", TrackGate.offer(s, north(100.0, now + 120_000), now).reason)
        assertEquals("duplicate", TrackGate.offer(s, north(100.0, t0), now).reason)
        assertEquals("displacement", TrackGate.offer(s, north(10.0, t0 + 60_000), now).reason)
        assertEquals("invalid", TrackGate.offer(s, GateFix(0.0, 0.0, 10f, t0 + 60_000), now).reason)
        val ok = TrackGate.offer(s, north(80.0, t0 + 60_000), now)
        assertTrue(ok.accepted); assertEquals(t0 + 60_000, ok.state.lastAccepted!!.timeMs)
    }

    @Test fun `a still phone keeps only a heartbeat of its last known position`() {
        val s = GateState(anchor = home, lastAccepted = home)
        val soon = TrackGate.offer(s, north(5.0, t0 + 60_000), t0 + 61_000)
        assertFalse(soon.accepted)
        val later = TrackGate.offer(s, north(5.0, t0 + TrackGate.HEARTBEAT_MS + 1), t0 + TrackGate.HEARTBEAT_MS + 2)
        assertTrue(later.accepted); assertEquals(GateMode.STILL, later.state.mode)
    }

    @Test fun `leaving a stop needs more than the radius and the fix noise`() {
        assertFalse(TrackGate.leftStop(home.lat, home.lon, north(120.0, t0, acc = 30f), 100.0))
        assertTrue(TrackGate.leftStop(home.lat, home.lon, north(200.0, t0, acc = 30f), 100.0))
    }

    @Test fun `scenario one hour wakeups are bounded by the gate`() {
        // 60 passive fixes at one-minute spacing from a phone that does not move: nothing is stored beyond the first and one heartbeat
        var s = GateState(); var kept = 0
        for (i in 0 until 60) {
            val o = TrackGate.offer(s, north((i % 3).toDouble(), t0 + i * 60_000L), t0 + i * 60_000L + 500)
            if (o.accepted) kept++; s = o.state
        }
        assertTrue("kept $kept", kept <= 3)
    }
}
