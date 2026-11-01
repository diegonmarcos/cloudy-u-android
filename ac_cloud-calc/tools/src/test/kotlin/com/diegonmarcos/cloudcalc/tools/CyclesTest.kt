package com.diegonmarcos.cloudcalc.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #1409 every calendar's cycle cut into its own units, and the Gregorian -> other day mapping the strips highlight. */
class CyclesTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun cyc(id: String, s: String) = Cycles.cycleAt(Cycles.spec(id)!!, d(s))
    private fun lens(c: Cycles.Cycle) = c.segments.map { it.days }
    private fun labels(c: Cycles.Cycle) = c.segments.map { it.label }

    @Test fun gregorianYearIsTwelveMonths() {
        val c = cyc("gregorian", "2024-06-15")
        assertEquals(d("2024-01-01"), c.start)
        assertEquals(listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31), lens(c))
        assertEquals("January", labels(c).first()); assertEquals("December", labels(c).last())
        assertEquals(366, c.length); assertEquals("2024", c.title)
        assertEquals(28, lens(cyc("gregorian", "2026-11-01"))[1])
        assertEquals(listOf(0, 31, 60), c.starts().take(3))
    }

    @Test fun gregorianIsFirst() = assertEquals("gregorian", Cycles.SPECS.first().id)

    @Test fun hebrewLeapYearHasBothAdars() {
        val c = cyc("hebrew", "2024-03-15") // 5784, a leap year
        assertEquals(13, c.segments.size)
        assertEquals("Tishrei", labels(c).first())
        assertTrue(labels(c).containsAll(listOf("Adar I", "Adar II")))
        assertEquals(30, c.segments.first { it.label == "Adar I" }.days)
        assertEquals(29, c.segments.first { it.label == "Adar II" }.days)
        assertEquals(Calendars.hebrewYearDays(5784).toInt(), c.length)
        assertEquals("5784 AM", c.title)
        val plain = cyc("hebrew", "2025-11-01") // 5786, common
        assertEquals(12, plain.segments.size)
        assertEquals("Adar", labels(plain)[5]); assertEquals("Nisan", labels(plain)[6])
    }

    @Test fun islamicTwelveLunarMonths() {
        val c = cyc("islamic", "2026-11-01")
        assertEquals(Calendars.ISLAMIC_MONTHS, labels(c))
        assertTrue(lens(c).all { it == 29 || it == 30 })
        assertTrue(c.length in 354..355)
    }

    @Test fun mayaAndAztecCounts() {
        val t = cyc("tzolkin", "2012-12-21")
        assertEquals(List(20) { 13 }, lens(t)); assertEquals("1 Imix", labels(t).first()); assertEquals("1 Ix", labels(t)[1]); assertEquals("1 Manik'", labels(t)[2])
        val h = cyc("haab", "2012-12-21")
        assertEquals(List(18) { 20 } + 5, lens(h)); assertEquals("Wayeb'", labels(h).last()); assertEquals("Pop", labels(h).first())
        assertEquals(List(20) { 13 }, lens(cyc("tonalpohualli", "2000-01-01")))
        assertEquals("1 Cipactli", labels(cyc("tonalpohualli", "2000-01-01")).first())
        assertEquals(List(18) { 20 } + 5, lens(cyc("xiuhpohualli", "2000-01-01")))
        // 2012-12-21 is 4 Ajaw 3 K'ank'in.
        val p = Cycles.position(t, d("2012-12-21"))!!
        assertEquals(4, p.offset % 13 + 1); assertEquals("Ajaw", Calendars.TZOLKIN[p.offset % 20])
        val q = Cycles.position(h, d("2012-12-21"))!!
        assertEquals("K'ank'in", labels(h)[q.segment]); assertEquals(4, q.day) // day 3 counted from 0
    }

    @Test fun thirteenMoonAndFixedAndFrench() {
        val m = cyc("thirteen_moon", "2026-11-01")
        assertEquals(d("2026-07-26"), m.start)
        assertEquals(List(13) { 28 } + 1, lens(m)); assertEquals("Day Out of Time", labels(m).last())
        val leap = cyc("thirteen_moon", "2024-02-29")
        assertEquals(366, leap.length); assertEquals(13 * 28 + 2, leap.length)
        assertEquals(1, lens(leap).count { it == 29 })
        val f = cyc("fixed", "2024-05-01")
        assertEquals(listOf(28, 28, 28, 28, 28, 28, 1, 28), lens(f).take(8)); assertEquals("Leap Day", labels(f)[6]); assertEquals("Year Day", labels(f).last())
        val fr = cyc("french", "2026-11-01")
        assertEquals(List(12) { 30 }, lens(fr).take(12)); assertTrue(lens(fr).last() in 5..6); assertEquals("Sansculottides", labels(fr).last())
        assertEquals("Vendémiaire", labels(fr).first())
    }

    @Test fun alexandrianSakaPersianJulianDiscordian() {
        val c = cyc("coptic", "2026-11-01")
        assertEquals(List(12) { 30 }, lens(c).take(12)); assertTrue(lens(c).last() in 5..6)
        assertEquals(13, cyc("ethiopian", "2026-11-01").segments.size)
        val s = cyc("saka", "2024-06-01")
        assertEquals(listOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 30), lens(s))
        val p = cyc("persian", "2026-11-01")
        assertEquals(List(6) { 31 } + List(5) { 30 }, lens(p).take(11)); assertTrue(lens(p).last() in 29..30)
        assertEquals(29, lens(cyc("julian", "2024-06-01"))[1])
        assertEquals(listOf(73, 73, 73, 73, 73), lens(cyc("discordian", "2026-11-01")))
        assertEquals(listOf(74, 73, 73, 73, 73), lens(cyc("discordian", "2024-11-01")))
    }

    @Test fun everyCycleIsContiguousAndHoldsItsDay() {
        var x = d("2023-01-01")
        while (x < d("2026-12-31")) {
            Cycles.SPECS.forEach { s ->
                val c = Cycles.cycleAt(s, x)
                val p = Cycles.position(c, x)!!
                assertEquals("${s.id} $x", s.key(x).label, c.segments[p.segment].label)
                assertTrue("${s.id} $x", p.day in 1..c.segments[p.segment].days)
                assertEquals(s.key(c.start.minusDays(1)).cycle + 1, s.key(c.start).cycle)
                assertEquals(s.key(c.start).cycle + 1, s.key(c.start.plusDays(c.length.toLong())).cycle)
            }
            x = x.plusDays(97)
        }
    }

    @Test fun gregorianDayMapsToTheSameDayEverywhere() {
        val day = d("2024-04-09")
        val all = Cycles.mapDay(day).associateBy { it.first.id }
        assertEquals(Cycles.SPECS.map { it.id }, Cycles.mapDay(day).map { it.first.id })
        all.values.forEach { (c, p) -> assertEquals(day, c.start.plusDays(p.offset.toLong())) }
        val (g, gp) = all.getValue("gregorian")
        assertEquals("April 9 · 2024", Cycles.label(g, gp)); assertEquals(99, gp.offset)
        val (h, hp) = all.getValue("hebrew")
        assertEquals("Nisan 1 · 5784 AM", Cycles.label(h, hp))
        val (i, ip) = all.getValue("islamic")
        val ref = Calendars.islamicCivil(day)
        assertEquals(Calendars.ISLAMIC_MONTHS[ref.month - 1], i.segments[ip.segment].label); assertEquals(ref.day, ip.day)
        val (f, fp) = all.getValue("french")
        val fref = Calendars.french(day)
        assertEquals(fref.month - 1, fp.segment); assertEquals(fref.day, fp.day)
        assertNull(Cycles.position(g, d("2025-01-01")))
        assertNull(Cycles.position(g, d("2023-12-31")))
        assertEquals(Cycles.Pos(365, 11, 31), Cycles.position(g, d("2024-12-31")))
        assertEquals(Cycles.Pos(0, 0, 1), Cycles.position(g, d("2024-01-01")))
    }
}
