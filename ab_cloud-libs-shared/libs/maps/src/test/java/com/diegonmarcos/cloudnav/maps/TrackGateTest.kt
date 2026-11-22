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
        // 60 passive fixes at one-minute spacing from a phone that does not move: nothing is stored beyond the first and one still sample per 9 min (#1851)
        var s = GateState(); var kept = 0
        for (i in 0 until 60) {
            val o = TrackGate.offer(s, north((i % 3).toDouble(), t0 + i * 60_000L), t0 + i * 60_000L + 500)
            if (o.accepted) kept++; s = o.state
        }
        assertTrue("kept $kept", kept in 2..8)
    }

    // ---- #1851 the emulator walk: `adb emu geo fix` every 20 s, accuracy only, no speed/bearing, 3 m jitter while still ----
    private data class Leg(val name: String, val sec: Int, val from: Pair<Double, Double>, val to: Pair<Double, Double>)
    private val legs = listOf(
        Leg("home", 660, 0.0 to 0.0, 0.0 to 0.0), Leg("walk", 360, 0.0 to 0.0, 500.0 to 0.0), Leg("cafe", 660, 500.0 to 0.0, 500.0 to 0.0),
        Leg("tram", 360, 500.0 to 0.0, 0.0 to 4000.0), Leg("office", 660, 0.0 to 4000.0, 0.0 to 4000.0),
        Leg("bus", 360, 0.0 to 4000.0, 0.0 to 0.0), Leg("home", 660, 0.0 to 0.0, 0.0 to 0.0))
    private data class Emu(val name: String, val fix: GateFix)
    private fun emulatorWalk(): List<Emu> {
        val out = ArrayList<Emu>(); var t = t0
        for (l in legs) {
            val n = l.sec / 20
            for (i in 0..n) {
                val f = i.toDouble() / n; val jit = if (l.from == l.to && i % 2 == 1) 3.0 else 0.0
                val east = l.from.first + (l.to.first - l.from.first) * f + jit; val nor = l.from.second + (l.to.second - l.from.second) * f
                out += Emu(l.name, GateFix(home.lat + nor / 111_195.0, home.lon + east / (111_195.0 * Math.cos(Math.toRadians(home.lat))), 5f, t))
                t += 20_000L
            }
        }
        return out
    }
    /** What the platform delivers to the passive listener under a request of [minIntervalMs] / [minDistanceM] (it filters on the last delivery), then the gate and the user's Start. */
    private fun run(minIntervalMs: Long, minDistanceM: Float): List<Emu> {
        var s = TrackGate.signal(GateState(), GateSignal.USER_START, t0)
        var last: GateFix? = null; val kept = ArrayList<Emu>()
        for (e in emulatorWalk()) {
            if (last != null && (e.fix.timeMs - last.timeMs < minIntervalMs || TrackGate.distanceM(last, e.fix) < minDistanceM)) continue
            last = e.fix
            s = TrackGate.expire(s, e.fix.timeMs)
            val o = TrackGate.offer(s, e.fix, e.fix.timeMs + 500)
            s = o.state; if (o.accepted) kept += e
        }
        return kept
    }

    /** The stays (legs 0, 2, 4, 6) that did not get two stored fixes five minutes apart. */
    private fun thinStays(kept: List<Emu>): List<String> {
        var a = t0; val bad = ArrayList<String>()
        legs.forEachIndexed { i, l ->
            val b = a + l.sec * 1000L + 20_000L
            if (i % 2 == 0) {
                val here = kept.filter { it.fix.timeMs in (a - 20_000L) until b }   // the arrival fix is the previous leg's last
                if (here.size < 2 || here.maxOf { it.fix.timeMs } - here.minOf { it.fix.timeMs } < 5 * 60_000L) bad += "${l.name}#$i(${here.size})"
            }
            a = b
        }
        return bad
    }

    @Test fun `the emulator walk under the old 60 s and 25 m platform filter loses the stays`() {
        val kept = run(60_000L, 25f)
        assertTrue("old request kept ${kept.size}, every stay thin: ${thinStays(kept)}", thinStays(kept).size == 4)
    }

    @Test fun `the emulator walk reaches the store with at least two fixes five minutes apart in every stay`() {
        val kept = run(TrackGate.PASSIVE_MIN_INTERVAL_MS, TrackGate.PASSIVE_MIN_DISTANCE_M)
        assertTrue("kept ${kept.size}", kept.size >= 20)
        assertEquals("thin stays", emptyList<String>(), thinStays(kept))
        assertTrue("moves are stored", kept.count { it.name == "tram" } >= 5 && kept.count { it.name == "walk" } >= 5)
    }
}
