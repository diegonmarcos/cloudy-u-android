package com.diegonmarcos.superapp.batterystats

import com.diegonmarcos.superapp.battery.BatteryCycles
import com.diegonmarcos.superapp.battery.BatteryCycles.Edge
import com.diegonmarcos.superapp.battery.BatteryCycles.Snap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Per-app battery use per charge cycle and per month (BatteryCycles): boundaries, reset-aware diff, monthly sums. */
class BatteryCyclesTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private fun snap(ts: Long, pct: Int, plugged: Boolean, vararg apps: Pair<Int, Double>) =
        Snap(ts, pct, plugged, if (apps.isEmpty()) null else apps.toMap())

    // ── boundaries ───────────────────────────────────────────────────────

    @Test fun `an unplug opens a cycle, the next plug-in closes it`() {
        assertEquals(Edge.OPEN, BatteryCycles.edge(snap(0, 90, true), snap(1, 90, false), open = false))
        assertEquals(Edge.CLOSE, BatteryCycles.edge(snap(1, 40, false), snap(2, 40, true), open = true))
    }

    @Test fun `a full charge opens a cycle while still plugged`() {
        assertEquals(Edge.OPEN, BatteryCycles.edge(snap(0, 99, true), snap(1, 100, true), open = false))
        assertEquals(Edge.NONE, BatteryCycles.edge(snap(0, 80, true), snap(1, 99, true), open = false))
    }

    @Test fun `staying on battery or on the charger is no boundary`() {
        assertEquals(Edge.NONE, BatteryCycles.edge(snap(0, 80, false), snap(1, 70, false), open = true))
        assertEquals(Edge.NONE, BatteryCycles.edge(snap(0, 100, true), snap(1, 100, true), open = true))
        // unplugging a cycle that opened at full keeps it open: one cycle from full to the next charge
        assertEquals(Edge.NONE, BatteryCycles.edge(snap(0, 100, true), snap(1, 100, false), open = true))
    }

    @Test fun `a cycle runs unplug to plug-in and carries the use in between`() {
        var st = BatteryCycles.State(null, snap(0, 95, true, 1 to 5.0))
        st = BatteryCycles.step(st, snap(10, 95, false, 1 to 6.0)).first
        assertEquals(10L, st.open!!.startTs)
        assertTrue("the charging span is not the cycle's", st.open!!.apps.isEmpty())
        st = BatteryCycles.step(st, snap(20, 70, false, 1 to 16.0, 2 to 3.0)).first
        val (after, closed) = BatteryCycles.step(st, snap(30, 60, true, 1 to 20.0, 2 to 3.0))
        assertNull(after.open)
        closed!!
        assertEquals(10L, closed.startTs); assertEquals(30L, closed.endTs)
        assertEquals(95, closed.startPct); assertEquals(60, closed.endPct); assertEquals(35, closed.usedPct)
        assertEquals(14.0, closed.apps[1]!!, 1e-9)
        assertEquals(3.0, closed.apps[2]!!, 1e-9)
    }

    @Test fun `a snapshot without the channel keeps the baseline, the gap is counted when it returns`() {
        var st = BatteryCycles.step(BatteryCycles.State(null, snap(0, 90, true, 1 to 2.0)), snap(1, 90, false, 1 to 2.0)).first
        st = BatteryCycles.step(st, snap(2, 80, false)).first
        assertEquals(2.0, st.last!!.apps!![1]!!, 1e-9)
        st = BatteryCycles.step(st, snap(3, 70, false, 1 to 9.0)).first
        assertEquals(7.0, st.open!!.apps[1]!!, 1e-9)
        assertEquals(70, st.open!!.endPct)
    }

    // ── reset-aware diff ─────────────────────────────────────────────────

    @Test fun `growth is the difference per uid`() {
        val d = BatteryCycles.delta(mapOf(1 to 10.0, 2 to 4.0), mapOf(1 to 15.0, 2 to 4.0, 3 to 1.0))
        assertEquals(mapOf(1 to 5.0, 3 to 1.0), d)
    }

    @Test fun `a batterystats reset counts the new reading from zero`() {
        // full charge in between: uid 1 went 50 -> 3, uid 2 grew past its old value only by accident
        val d = BatteryCycles.delta(mapOf(1 to 50.0, 2 to 1.0), mapOf(1 to 3.0, 2 to 2.0))
        assertEquals(3.0, d[1]!!, 1e-9)
        assertEquals(2.0, d[2]!!, 1e-9)
    }

    @Test fun `a uid that vanished is a reset too, no side is no use`() {
        assertEquals(mapOf(2 to 1.0), BatteryCycles.delta(mapOf(1 to 5.0), mapOf(2 to 1.0)))
        assertEquals(emptyMap<Int, Double>(), BatteryCycles.delta(null, mapOf(1 to 5.0)))
        assertEquals(emptyMap<Int, Double>(), BatteryCycles.delta(mapOf(1 to 5.0), null))
    }

    @Test fun `a cycle across a reset adds both sides`() {
        var st = BatteryCycles.step(BatteryCycles.State(null, snap(0, 100, true, 1 to 30.0)), snap(1, 100, false, 1 to 30.0)).first
        st = BatteryCycles.step(st, snap(2, 90, false, 1 to 34.0)).first
        st = BatteryCycles.step(st, snap(3, 80, false, 1 to 2.5)).first // reset
        assertEquals(6.5, st.open!!.apps[1]!!, 1e-9)
    }

    // ── months ───────────────────────────────────────────────────────────

    private val oct3 = 1_759_449_600_000L   // 2025-10-03T00:00Z
    private val oct20 = 1_760_918_400_000L  // 2025-10-20T00:00Z
    private val nov1 = 1_761_955_200_000L   // 2025-11-01T00:00Z

    @Test fun `a month sums the cycles that started in it, newest month first`() {
        val cycles = listOf(
            BatteryCycles.Cycle(oct3, oct3 + 1, 100, 60, mapOf(1 to 10.0, 2 to 5.0)),
            BatteryCycles.Cycle(oct20, oct20 + 1, 90, 20, mapOf(1 to 4.0)),
            BatteryCycles.Cycle(nov1 - 60_000, nov1 + 3_600_000, 80, 50, mapOf(3 to 7.0)),
            BatteryCycles.Cycle(nov1 + 1, null, 100, 95, mapOf(2 to 1.0)),
        )
        val m = BatteryCycles.months(cycles, utc)
        assertEquals(listOf("2025-11", "2025-10"), m.map { it.key })
        val oct = m[1]
        assertEquals(3, oct.cycles)
        assertEquals(40 + 70 + 30, oct.usedPct)
        assertEquals(mapOf(1 to 14.0, 2 to 5.0, 3 to 7.0), oct.apps)
        assertEquals(26.0, oct.totalMah, 1e-9)
        assertEquals(1, m[0].cycles)
        assertEquals("2025-10", BatteryCycles.monthKey(nov1 - 1, utc))
        assertEquals("a cycle that gained charge used none", 0, BatteryCycles.Cycle(0, 1, 40, 60, emptyMap()).usedPct)
    }

    @Test fun `shares are heaviest first and add up to one`() {
        val s = BatteryCycles.shares(mapOf(1 to 1.0, 2 to 3.0))
        assertEquals(listOf(2, 1), s.map { it.uid })
        assertEquals(0.75, s[0].share, 1e-9)
        assertEquals(emptyList<BatteryCycles.Share>(), BatteryCycles.shares(emptyMap()))
    }

    @Test fun `the storage codec round-trips`() {
        val m = mapOf(10221 to 85.25, 1000 to 0.5)
        assertEquals(m, BatteryCycles.decode(BatteryCycles.encode(m)))
        assertNull(BatteryCycles.decode(null))
        assertEquals(emptyMap<Int, Double>(), BatteryCycles.decode(""))
    }
}
