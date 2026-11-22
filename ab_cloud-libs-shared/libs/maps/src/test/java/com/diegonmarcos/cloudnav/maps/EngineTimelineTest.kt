package com.diegonmarcos.cloudnav.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.hypot

/** #1851 the one derivation: fixes + steps + sleep in, the stored segment rows and day rollups out. */
class EngineTimelineTest {
    private val zone = ZoneId.of("UTC")
    private val d1 = LocalDate.of(2026, 11, 20)
    private val d2 = d1.plusDays(1)
    private fun at(h: Int, m: Int = 0, plusDays: Long = 0) = d1.plusDays(plusDays).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val lat0 = 52.5; private val lon0 = 13.4
    private fun lat(n: Double) = lat0 + n / 111_195.0
    private fun lon(e: Double) = lon0 + e / (111_195.0 * cos(Math.toRadians(lat0)))

    private fun still(from: Long, to: Long, e: Double, n: Double, every: Int = 5): List<Fix> {
        val out = ArrayList<Fix>(); var t = from; var k = 0
        while (t <= to) { val j = if (k % 2 == 0) 4.0 else -4.0; out += Fix(t, lat(n + j), lon(e - j), 10.0, 0.0, "fused"); t += every * 60_000L; k++ }
        return out
    }
    private fun travel(t0: Long, e0: Double, n0: Double, e1: Double, n1: Double, mps: Double, every: Int): List<Fix> {
        val total = (hypot(e1 - e0, n1 - n0) / mps * 1000).toLong(); val out = ArrayList<Fix>(); var dt = every * 1000L
        while (dt < total) { val f = dt.toDouble() / total; out += Fix(t0 + dt, lat(n0 + (n1 - n0) * f), lon(e0 + (e1 - e0) * f), 10.0, mps, "fused"); dt += every * 1000L }
        out += Fix(t0 + total, lat(n1), lon(e1), 10.0, mps, "fused"); return out
    }
    private val stream: List<Fix> get() =
        still(at(0), at(8), 0.0, 0.0) + travel(at(8), 0.0, 0.0, 500.0, 0.0, 1.4, 30) + still(at(8, 10), at(8, 50), 500.0, 0.0) +
            travel(at(8, 50), 500.0, 0.0, 0.0, 4000.0, 10.0, 30) + still(at(9, 5), at(17), 0.0, 4000.0) +
            travel(at(17), 0.0, 4000.0, 0.0, 0.0, 1.4, 60) + still(at(17, 55), at(23, 55), 0.0, 0.0) + still(at(0, 0, 1), at(7, 55, 1), 0.0, 0.0)

    private val slices = listOf(TlSteps(at(8), at(8, 8), 700), TlSteps(at(8, 50), at(9, 5), 1500), TlSteps(at(17), at(17, 50), 3800),
        TlSteps(at(7, 30, 1), at(7, 50, 1), 600))
    private val daily = mapOf(d1 to 9910L, d2 to 650L)
    private fun build() = EngineTimeline.build(stream, slices, daily, emptyList(), emptyList(), at(12, 0, 1), zone)

    @Test fun `the day has several stays and moves with distance, and no stay is a whole day of sleep`() {
        val r = build()
        assertTrue(r.segments.count { it.kind == TlKind.MOVE } >= 3)
        assertTrue(r.segments.count { it.kind != TlKind.MOVE } >= 4)
        assertTrue("no sleep longer than the night", r.segments.filter { it.kind == TlKind.SLEEP }.all { it.durationMs <= 10 * 3_600_000L })
        assertTrue(r.segments.filter { it.kind == TlKind.MOVE }.sumOf { it.distanceM } > 7_500.0)
        val l = r.segments.sortedBy { it.startMs }
        for (i in 1 until l.size) assertTrue(l[i - 1].endMs <= l[i].startMs)
    }

    @Test fun `the night is a sleep row between two day rows of the same stay`() {
        val r = build()
        val evening = r.segments.filter { it.startMs >= at(17, 40) && it.startMs < at(8, 30, 1) && it.kind != TlKind.MOVE }
        assertEquals(listOf(TlKind.STOP, TlKind.SLEEP), evening.map { it.kind }.distinct())
        assertEquals(at(22), evening.first { it.kind == TlKind.SLEEP }.startMs)
    }

    @Test fun `rollups carry the Health Connect total of each day and the segments add up to it`() {
        val r = build()
        assertEquals(9910L, r.rollups.first { it.date == d1 }.steps)
        assertEquals(650L, r.rollups.first { it.date == d2 }.steps)
        // the segments, cut at their day, give the same totals (steps are attributed per day then folded back)
        val all = r.segments.sumOf { it.steps }
        assertEquals(9910L + 650L, all)
        assertTrue(r.rollups.first { it.date == d1 }.distanceM > 7_500.0)
        assertTrue(r.rollups.first { it.date == d1 }.visits >= 4)
    }

    @Test fun `sleep belongs to the date it ends on`() {
        val r = build()
        assertTrue(r.rollups.first { it.date == d1 }.sleepMs >= 7 * 3_600_000L)
        assertTrue(r.rollups.first { it.date == d2 }.sleepMs >= 9 * 3_600_000L)
    }

    @Test fun `a day with a total but no fix still has its steps`() {
        val r = EngineTimeline.build(emptyList(), emptyList(), mapOf(d1 to 4321L), emptyList(), emptyList(), at(12), zone)
        assertTrue(r.segments.isEmpty())
        assertEquals(4321L, r.rollups.single().steps)
    }

    @Test fun `a known place name near a stay is carried onto it`() {
        val label = TlStop(at(0), at(8), lat(0.0), lon(0.0), "Home", null, "Friedrichshain", "Berlin", "Deutschland")
        val r = EngineTimeline.build(still(at(10), at(11), 0.0, 0.0), emptyList(), emptyMap(), emptyList(), listOf(label), at(12), zone)
        assertEquals("Home", r.segments.single().place); assertEquals("Berlin", r.segments.single().city)
    }
}
