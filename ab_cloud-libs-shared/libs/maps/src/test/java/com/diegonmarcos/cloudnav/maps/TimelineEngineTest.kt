package com.diegonmarcos.cloudnav.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.hypot

/** #1851 the segmentation engine on synthetic fix streams: stays, moves, per-day separation, sleep and the steps per day. */
class TimelineEngineTest {
    private val zone = ZoneId.of("UTC")
    private val d1 = LocalDate.of(2026, 11, 20)
    private val d2 = d1.plusDays(1)
    private fun at(h: Int, m: Int = 0, s: Int = 0, plusDays: Long = 0) = d1.plusDays(plusDays).atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()

    // metres east / north of the origin in Berlin to lat / lon
    private val lat0 = 52.5; private val lon0 = 13.4
    private fun lat(n: Double) = lat0 + n / 111_195.0
    private fun lon(e: Double) = lon0 + e / (111_195.0 * cos(Math.toRadians(lat0)))

    private fun still(from: Long, to: Long, e: Double, n: Double, everyMin: Int = 5, jitterM: Double = 4.0): List<Fix> {
        val out = ArrayList<Fix>(); var t = from; var k = 0
        while (t <= to) { val j = if (k % 2 == 0) jitterM else -jitterM; out += Fix(t, lat(n + j), lon(e - j), 10.0, 0.0, "net"); t += everyMin * 60_000L; k++ }
        return out
    }

    /** Straight line from (e0,n0) to (e1,n1) at [mps], a fix every [everySec] seconds, the first one [everySec] after [t0], the last on arrival. */
    private fun travel(t0: Long, e0: Double, n0: Double, e1: Double, n1: Double, mps: Double, everySec: Int): List<Fix> {
        val dist = hypot(e1 - e0, n1 - n0); val total = (dist / mps * 1000).toLong()
        val out = ArrayList<Fix>(); var dt = everySec * 1000L
        while (dt < total) { val f = dt.toDouble() / total; out += Fix(t0 + dt, lat(n0 + (n1 - n0) * f), lon(e0 + (e1 - e0) * f), 10.0, mps, "gps"); dt += everySec * 1000L }
        out += Fix(t0 + total, lat(n1), lon(e1), 10.0, mps, "gps")
        return out
    }

    // home (0,0), cafe (500,0), office (0,4000)
    private fun dayStream(): List<Fix> {
        val f = ArrayList<Fix>()
        f += still(at(0), at(8), 0.0, 0.0)                              // home overnight
        f += travel(at(8), 0.0, 0.0, 500.0, 0.0, 1.4, 30)              // walk to the cafe
        f += still(at(8, 10), at(8, 50), 500.0, 0.0)                    // cafe
        f += travel(at(8, 50), 500.0, 0.0, 0.0, 4000.0, 10.0, 30)      // tram
        f += still(at(9, 5), at(17), 0.0, 4000.0)                       // office
        f += travel(at(17), 0.0, 4000.0, 0.0, 0.0, 1.4, 60)            // walk home
        f += still(at(17, 55), at(23, 55), 0.0, 0.0)                    // home evening
        f += still(at(0, plusDays = 1), at(7, 55, plusDays = 1), 0.0, 0.0)   // the next night, same place
        return f
    }

    private fun near(expected: Long, actual: Long, tolMin: Long = 4) = assertTrue("expected ~$expected got $actual", Math.abs(expected - actual) <= tolMin * 60_000L)

    @Test fun `a day reads home stay, walk, cafe stay, tram, office stay, walk home, home stay`() {
        val segs = TimelineEngine.segment(dayStream(), zone = zone)
        assertEquals(listOf("Stay", "Move", "Stay", "Move", "Stay", "Move", "Stay"), segs.map { it::class.simpleName })
        val moves = segs.filterIsInstance<Seg.Move>()
        assertEquals(listOf("WALK", "CAR", "WALK"), moves.map { it.mode })
        assertEquals(400.0, moves[0].distanceM, 150.0)
        assertEquals(4031.0, moves[1].distanceM, 300.0)
        assertEquals(4000.0, moves[2].distanceM, 300.0)
        val stays = segs.filterIsInstance<Seg.Stay>()
        near(at(8, 50), stays[1].endMs); near(at(8, 57), stays[2].startMs, 3)
        assertEquals(500.0, TimelineBuilder.haversineM(lat0, lon0, stays[1].lat, stays[1].lon), 30.0)
        assertTrue("the office is not the home", TimelineBuilder.haversineM(stays[0].lat, stays[0].lon, stays[2].lat, stays[2].lon) > 3500)
        // gapless: each piece starts where the previous one ended
        for (i in 1 until segs.size) assertEquals(segs[i - 1].endMs, segs[i].startMs)
    }

    @Test fun `no stay and no move is lost to a place far away, and a still day has 0 km`() {
        val segs = TimelineEngine.segment(still(at(0), at(23, 55), 0.0, 0.0, everyMin = 30), zone = zone)
        assertEquals(1, segs.size)
        assertEquals(0.0, TimelineEngine.rollup(d1, segs).distanceM, 0.0)
    }

    @Test fun `the same stay is not swallowed when it is split by the distance threshold`() {
        val near = still(at(10), at(10, 30), 0.0, 0.0) + still(at(10, 35), at(11), 60.0, 0.0)
        assertEquals(1, TimelineEngine.segment(near, zone = zone).count { it is Seg.Stay })
        val far = still(at(10), at(10, 30), 0.0, 0.0) + still(at(10, 35), at(11), 160.0, 0.0)
        assertEquals(2, TimelineEngine.segment(far, zone = zone).count { it is Seg.Stay })
        val wide = EngineParams(stayRadiusM = 300.0)
        assertEquals(1, TimelineEngine.segment(far, wide, zone).count { it is Seg.Stay })
    }

    @Test fun `a dwell under the minimum is not a stay`() {
        val segs = TimelineEngine.segment(still(at(10), at(10, 3), 0.0, 0.0, everyMin = 1), zone = zone)
        assertTrue(segs.none { it is Seg.Stay })
    }

    @Test fun `stays and moves are separated per day across midnight`() {
        val segs = TimelineEngine.segment(dayStream(), zone = zone)
        val days = TimelineEngine.byDay(segs, zone)
        assertEquals(listOf(d1, d2), days.keys.toList())
        for ((d, parts) in days) for (s in parts) {
            val a = d.atStartOfDay(zone).toInstant().toEpochMilli()
            assertTrue("piece inside its date", s.startMs >= a && s.endMs <= a + 86_400_000L)
        }
        val evening = days[d1]!!.last() as Seg.Stay
        assertEquals(at(0, plusDays = 1), evening.endMs)
        val morning = days[d2]!!.first() as Seg.Stay
        assertEquals(at(0, plusDays = 1), morning.startMs)
        near(at(7, 55, plusDays = 1), morning.endMs, 1)
        // nothing is lost by the cut
        assertEquals(segs.sumOf { it.durationMs }, days.values.flatten().sumOf { it.durationMs })
        assertEquals(segs.filterIsInstance<Seg.Move>().sumOf { it.distanceM }, days.values.flatten().filterIsInstance<Seg.Move>().sumOf { it.distanceM }, 0.01)
    }

    // ── sleep ───────────────────────────────────────────────────────

    @Test fun `sleep is the night part of a stay, never the whole stay`() {
        val segs = TimelineEngine.segment(dayStream(), zone = zone)
        val stays = segs.filterIsInstance<Seg.Stay>()
        assertEquals("overnight at home", at(0), stays[0].sleepStartMs)
        assertEquals(at(8).toDouble(), stays[0].sleepEndMs!!.toDouble(), 5 * 60_000.0)
        assertNull("the cafe", stays[1].sleepStartMs)
        assertNull("the office", stays[2].sleepStartMs)
        val evening = stays[3]
        assertEquals("the evening stay starts at ~18:00 but sleeps from 22:00", at(22), evening.sleepStartMs)
        assertEquals(at(8, plusDays = 1).toDouble(), evening.sleepEndMs!!.toDouble(), 5 * 60_000.0)
        assertTrue(evening.sleepMs <= 10 * 3_600_000L)
    }

    @Test fun `a 24 hour stay at one place sleeps only at night`() {
        val segs = TimelineEngine.segment(still(at(0), at(23, 59), 0.0, 0.0, everyMin = 30), zone = zone)
        val st = segs.single() as Seg.Stay
        assertTrue(st.sleepMs in 1..(8 * 3_600_000L))
        assertEquals(at(0), st.sleepStartMs)
    }

    @Test fun `a daytime nap is not sleep`() {
        val segs = TimelineEngine.segment(still(at(13), at(18), 0.0, 0.0), zone = zone)
        assertNull((segs.single() as Seg.Stay).sleepStartMs)
    }

    @Test fun `a night spent moving is not sleep`() {
        val fx = still(at(22), at(23, 59), 0.0, 0.0).map { it.copy(speedMps = 3.0) } + still(at(0, plusDays = 1), at(6, plusDays = 1), 0.0, 0.0).map { it.copy(speedMps = 3.0) }
        assertNull((TimelineEngine.segment(fx, zone = zone).single() as Seg.Stay).sleepStartMs)
    }

    @Test fun `a Health Connect sleep session decides the sleep when there is one`() {
        val fx = still(at(18), at(23, 55), 0.0, 0.0) + still(at(0, plusDays = 1), at(8, plusDays = 1), 0.0, 0.0)
        val hc = listOf(at(23, 10)..at(6, 40, plusDays = 1))
        val st = TimelineEngine.segment(fx, zone = zone, hcSleep = hc).single() as Seg.Stay
        assertEquals(at(23, 10), st.sleepStartMs); assertEquals(at(6, 40, plusDays = 1), st.sleepEndMs)
        assertNotEquals(at(22), st.sleepStartMs)
    }

    @Test fun `sleep belongs to the date it ends on`() {
        val segs = TimelineEngine.segment(dayStream(), zone = zone)
        val m = TimelineEngine.sleepByEndDate(segs, zone)
        assertTrue("the first night ends on d1", (m[d1] ?: 0L) >= 7 * 3_600_000L)
        assertTrue("the evening night ends on d2", (m[d2] ?: 0L) >= 9 * 3_600_000L)
    }

    // ── steps ───────────────────────────────────────────────────────

    @Test fun `the steps attributed to a day equal the Health Connect total of that day`() {
        val slices = listOf(TlSteps(at(8), at(8, 8), 700), TlSteps(at(8, 50), at(9, 5), 1500), TlSteps(at(17), at(17, 50), 3800),
            TlSteps(at(12), at(12, 30), 400), TlSteps(at(7, 30, plusDays = 1), at(7, 50, plusDays = 1), 600))
        val totals = mapOf(d1 to 9910L, d2 to 650L)
        val days = TimelineEngine.byDay(TimelineEngine.segment(dayStream(), zone = zone), zone)
        val res = TimelineEngine.attributeSteps(days, slices, totals)
        for ((d, parts) in res) {
            assertEquals("day $d", totals[d], TimelineEngine.rollup(d, parts).steps)
            assertTrue(parts.all { ((it as? Seg.Move)?.steps ?: (it as? Seg.Stay)?.steps ?: 0L) >= 0 })
        }
        val walk = res[d1]!!.filterIsInstance<Seg.Move>().first()
        assertTrue("the walk to the cafe got its share of the 700 steps, scaled up", walk.steps in 400..900)
    }

    @Test fun `without a Health Connect total the phone counter slices are attributed as they are`() {
        val slices = listOf(TlSteps(at(8), at(8, 8), 700), TlSteps(at(17), at(17, 50), 3800))
        val res = TimelineEngine.attributeSteps(TimelineEngine.byDay(TimelineEngine.segment(dayStream(), zone = zone), zone), slices)
        assertEquals(4500.0, TimelineEngine.rollup(d1, res[d1]!!).steps.toDouble(), 3.0)
    }

    @Test fun `a day total with no slice at all is spread by duration and still adds up`() {
        val res = TimelineEngine.attributeSteps(TimelineEngine.byDay(TimelineEngine.segment(dayStream(), zone = zone), zone), emptyList(), mapOf(d1 to 1234L))
        assertEquals(1234L, TimelineEngine.rollup(d1, res[d1]!!).steps)
    }

    @Test fun `a move cut at midnight keeps its steps and distance`() {
        val fx = still(at(22), at(23, 40), 0.0, 0.0) + travel(at(23, 40), 0.0, 0.0, 0.0, 3000.0, 1.4, 60) + still(at(0, 20, plusDays = 1), at(1, 20, plusDays = 1), 0.0, 3000.0)
        val segs = TimelineEngine.segment(fx, zone = zone).map { if (it is Seg.Move) it.copy(steps = 1000) else it }
        val cut = TimelineEngine.cutAtMidnight(segs, zone).filterIsInstance<Seg.Move>()
        assertEquals(2, cut.size)
        assertEquals(1000L, cut.sumOf { it.steps })
        assertEquals(segs.filterIsInstance<Seg.Move>().single().distanceM, cut.sumOf { it.distanceM }, 0.01)
    }

    // ── the open-stop bug (#1851) ───────────────────────────────────

    private val HOME = lat(0.0) to lon(0.0)
    private fun openStop(a: Long) = TlStop(a, null, HOME.first, HOME.second, "Home")

    @Test fun `an open stop does not run for days when nothing was recorded after it`() {
        val now = at(12, plusDays = 3)
        val segs = TimelineBuilder.build(listOf(openStop(at(20))), emptyList(), emptyList(), now, zone)
        assertTrue("bounded, not 64 h", segs.single().endMs - segs.single().startMs <= TimelineBuilder.OPEN_SILENCE_MS)
    }

    @Test fun `an open stop ends at the first fix far from it`() {
        val away = TlFix(at(9, plusDays = 1), lat(3000.0), lon(0.0), 20.0)
        val segs = TimelineBuilder.build(listOf(openStop(at(20))), listOf(away), emptyList(), at(12, plusDays = 3), zone)
        assertEquals(at(9, plusDays = 1), segs.first { it.kind != TlKind.MOVE }.endMs)
    }

    @Test fun `an open stop with fixes near it keeps running to now`() {
        val near = TlFix(at(11), lat(10.0), lon(0.0), 20.0)
        val segs = TimelineBuilder.build(listOf(openStop(at(8))), listOf(near), emptyList(), at(12), zone)
        assertEquals(at(12), segs.single().endMs)
    }
}
