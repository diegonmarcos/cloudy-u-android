package com.diegonmarcos.clocktools

import com.diegonmarcos.clocktools.WorldTable.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * #1667 the world clock converter, the default city's table and the day bars, plus #1646's stopwatch texts and the
 * declaration parser. Hand-checked instants: sunrise / sunset against published almanac times (to a few minutes).
 */
class WorldClockTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val kathmandu = ZoneId.of("Asia/Kathmandu")
    private val stJohns = ZoneId.of("America/St_Johns")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val madridAt = LatLon(40.4168, -3.7038)
    private val tromso = LatLon(69.6492, 18.9553)
    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun near(expected: Int, actual: Int?, tol: Int = 4) {
        assertNotNull(actual)
        assertTrue("expected ${WorldTable.hhmm(expected)} got ${WorldTable.hhmm(actual!!)}", kotlin.math.abs(expected - actual) <= tol)
    }

    // ── the sun ─────────────────────────────────────────────────────────────────────────────

    @Test fun `Madrid's midsummer sunrise and sunset match the almanac`() {
        val s = Sun.day(LocalDate.of(2026, 6, 21), madridAt.lat, madridAt.lon) as Sun.Day.Times
        near(6 * 60 + 45, WorldTable.minuteOf(madrid, s.rise))
        near(21 * 60 + 48, WorldTable.minuteOf(madrid, s.set))
        assertTrue(s.rise < s.set)
    }

    @Test fun `Madrid's midwinter day is short`() {
        val s = Sun.day(LocalDate.of(2026, 12, 21), madridAt.lat, madridAt.lon) as Sun.Day.Times
        near(8 * 60 + 33, WorldTable.minuteOf(madrid, s.rise))
        near(17 * 60 + 53, WorldTable.minuteOf(madrid, s.set))
    }

    @Test fun `the southern hemisphere has its summer in December`() {
        val sydney = ZoneId.of("Australia/Sydney")
        val s = Sun.day(LocalDate.of(2026, 12, 21), -33.8688, 151.2093) as Sun.Day.Times
        near(5 * 60 + 41, WorldTable.minuteOf(sydney, s.rise))
        near(20 * 60 + 5, WorldTable.minuteOf(sydney, s.set))
    }

    @Test fun `polar day and polar night are said, not invented`() {
        assertEquals(Sun.Day.PolarDay, Sun.day(LocalDate.of(2026, 6, 21), tromso.lat, tromso.lon))
        assertEquals(Sun.Day.PolarNight, Sun.day(LocalDate.of(2026, 12, 21), tromso.lat, tromso.lon))
        assertEquals(Sun.Day.PolarDay, Sun.day(LocalDate.of(2026, 12, 21), -89.0, 0.0))
        assertEquals(Sun.Day.PolarNight, Sun.day(LocalDate.of(2026, 6, 21), -89.0, 0.0))
        // The poles themselves and out-of-range input never throw.
        assertTrue(Sun.day(LocalDate.of(2026, 6, 21), 90.0, 0.0) is Sun.Day.PolarDay)
        assertTrue(Sun.day(LocalDate.of(2026, 6, 21), 123.0, 400.0) is Sun.Day.PolarDay)
    }

    @Test fun `the equator has about twelve hours of day all year`() {
        listOf(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 6, 21), LocalDate.of(2026, 12, 21)).forEach { d ->
            val s = Sun.day(d, 0.0, 0.0) as Sun.Day.Times
            val hours = (s.set - s.rise) / 3_600_000.0
            assertTrue("$d: $hours h", hours in 12.0..12.3)
        }
    }

    @Test fun `the computed instants are pinned, and longitudes wrap`() {
        // Golden values of this implementation (each within a few minutes of the almanac, tests above): any change to a
        // coefficient of the solar position moves them.
        val m = Sun.day(LocalDate.of(2026, 6, 21), madridAt.lat, madridAt.lon) as Sun.Day.Times
        assertEquals(1782017081707.0, m.rise.toDouble(), 50.0)
        assertEquals(1782071300338.0, m.set.toDouble(), 50.0)
        val s = Sun.day(LocalDate.of(2026, 12, 21), -33.8688, 151.2093) as Sun.Day.Times
        assertEquals(1797792040566.0, s.rise.toDouble(), 50.0)
        assertEquals(1797843908930.0, s.set.toDouble(), 50.0)
        assertEquals(m, Sun.day(LocalDate.of(2026, 6, 21), madridAt.lat, madridAt.lon + 360.0))
        assertEquals(m, Sun.day(LocalDate.of(2026, 6, 21), madridAt.lat, madridAt.lon - 360.0))
        assertEquals(s, Sun.day(LocalDate.of(2026, 12, 21), -33.8688, 151.2093 - 720.0))
    }

    @Test fun `julian day to epoch`() {
        assertEquals(0L, Sun.jdToMs(2440587.5))
        assertEquals(86_400_000L, Sun.jdToMs(2440588.5))
    }

    // ── the converter's window ──────────────────────────────────────────────────────────────

    @Test fun `the window is the default city's local day holding the reference instant`() {
        val ref = at(madrid, 2026, 10, 2, 15, 0)
        val (from, to) = WorldTable.window(madrid, ref)
        assertEquals(at(madrid, 2026, 10, 2, 0, 0), from)
        assertEquals(at(madrid, 2026, 10, 3, 0, 0), to)
    }

    @Test fun `a DST day's window is 23 or 25 hours long`() {
        val (a, b) = WorldTable.window(madrid, at(madrid, 2026, 3, 29, 12, 0))
        assertEquals(23 * 3_600_000L, b - a)
        val (c, d) = WorldTable.window(madrid, at(madrid, 2026, 10, 25, 12, 0))
        assertEquals(25 * 3_600_000L, d - c)
    }

    @Test fun `an edited reference time is that date and time in the default city`() {
        assertEquals(at(kolkata, 2026, 1, 15, 9, 30), WorldTable.reference(kolkata, LocalDate.of(2026, 1, 15), 9 * 60 + 30))
        // 02:30 does not exist on Madrid's spring-forward night: it moves forward by the gap, like an alarm.
        assertEquals(at(madrid, 2026, 3, 29, 3, 30), WorldTable.reference(madrid, LocalDate.of(2026, 3, 29), 2 * 60 + 30))
        // Out-of-range minutes are clamped, never thrown.
        assertEquals(at(madrid, 2026, 1, 1, 23, 59), WorldTable.reference(madrid, LocalDate.of(2026, 1, 1), 99_999))
        assertEquals(at(madrid, 2026, 1, 1, 0, 0), WorldTable.reference(madrid, LocalDate.of(2026, 1, 1), -5))
    }

    // ── the table ───────────────────────────────────────────────────────────────────────────

    @Test fun `the default city is the first row and shows once`() {
        val rows = WorldTable.rows("Europe/Madrid", listOf("Asia/Tokyo", "Europe/Madrid", "Asia/Tokyo", "Not/AZone"), at(madrid, 2026, 10, 2, 12, 0)) { null }
        assertEquals(listOf("Europe/Madrid", "Asia/Tokyo"), rows.map { it.zone })
        assertTrue(rows[0].isDefault)
        assertFalse(rows[1].isDefault)
    }

    @Test fun `changing the default city moves the first row and the window`() {
        val ref = at(madrid, 2026, 10, 2, 12, 0)
        val rows = WorldTable.rows("Asia/Tokyo", listOf("Europe/Madrid", "Asia/Tokyo"), ref) { null }
        assertEquals(listOf("Asia/Tokyo", "Europe/Madrid"), rows.map { it.zone })
        assertEquals(at(tokyo, 2026, 10, 2, 0, 0), rows[0].bar.windowStart)
        assertEquals(0, rows[0].bar.startMinute)
        // An unknown default city is never a crash: the window falls back to UTC.
        assertEquals(listOf("Europe/Madrid"), WorldTable.rows("Bad/Zone", listOf("Europe/Madrid"), ref) { null }.map { it.zone })
    }

    @Test fun `each city's bar starts at the default city's midnight in its own time`() {
        val ref = at(madrid, 2026, 1, 15, 10, 0)  // winter: Madrid UTC+1
        val rows = WorldTable.rows("Europe/Madrid", listOf("Asia/Kolkata", "Asia/Kathmandu", "America/St_Johns", "Asia/Tokyo"), ref) { null }
        val start = rows.associate { it.zone to it.bar.startMinute }
        assertEquals(0, start["Europe/Madrid"])
        assertEquals(4 * 60 + 30, start["Asia/Kolkata"])        // +5:30 - 1
        assertEquals(4 * 60 + 45, start["Asia/Kathmandu"])      // +5:45 - 1
        assertEquals(19 * 60 + 30, start["America/St_Johns"])   // -3:30 - 1, the day before
        assertEquals(8 * 60, start["Asia/Tokyo"])
        // Every bar covers the same instants and puts the reference at the same place.
        assertEquals(1, rows.map { it.bar.windowStart to it.bar.windowEnd }.distinct().size)
        assertEquals(1, rows.map { it.bar.reference }.distinct().size)
        assertEquals(10.0 / 24.0, rows[0].bar.reference, 1e-9)
    }

    @Test fun `in summer the shift follows the DST offsets`() {
        val ref = at(madrid, 2026, 7, 1, 10, 0)  // summer: Madrid UTC+2, St John's UTC-2:30
        val rows = WorldTable.rows("Europe/Madrid", listOf("Asia/Kolkata", "America/St_Johns"), ref) { null }
        val start = rows.associate { it.zone to it.bar.startMinute }
        assertEquals(3 * 60 + 30, start["Asia/Kolkata"])
        assertEquals(19 * 60 + 30, start["America/St_Johns"])
        assertEquals("+3:30", WorldTable.shiftText(kolkata, madrid, ref))
        assertEquals("−4:30", WorldTable.shiftText(stJohns, madrid, ref))
        assertEquals("+3:45", WorldTable.shiftText(kathmandu, madrid, ref))
        assertEquals("±0", WorldTable.shiftText(madrid, madrid, ref))
    }

    @Test fun `hour marks sit at each city's own local hours`() {
        val ref = at(madrid, 2026, 1, 15, 10, 0)
        val (from, to) = WorldTable.window(madrid, ref)
        val home = WorldTable.marks(madrid, from, to)
        assertEquals(listOf(0, 6, 12, 18), home.map { it.hour })
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75), home.map { it.at })
        // Kolkata starts at 04:30: its 06:00 is 1.5 h in, its 00:00 the next day 19.5 h in.
        val k = WorldTable.marks(kolkata, from, to)
        assertEquals(listOf(6, 12, 18, 0), k.map { it.hour })
        assertEquals(1.5 / 24.0, k.first().at, 1e-9)
        assertEquals(19.5 / 24.0, k.last().at, 1e-9)
    }

    @Test fun `the bar's light spans come from the sun at the city's coordinates`() {
        val ref = at(madrid, 2026, 6, 21, 12, 0)
        val bar = WorldTable.rows("Europe/Madrid", emptyList(), ref) { madridAt }.single().bar
        assertEquals(WorldTable.Sky.TIMES, bar.sky)
        near(6 * 60 + 45, bar.rise)
        near(21 * 60 + 48, bar.set)
        assertEquals(listOf(false, true, false), bar.spans.map { it.day })
        assertEquals(0.0, bar.spans.first().from, 0.0)
        assertEquals(1.0, bar.spans.last().to, 0.0)
        assertEquals((6 * 60 + 45) / 1440.0, bar.spans[1].from, 4 / 1440.0)
        // Spans tile the window without gaps.
        bar.spans.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, 0.0) }
    }

    @Test fun `polar cases fill the bar with one colour`() {
        val ref = at(ZoneId.of("Europe/Oslo"), 2026, 6, 21, 12, 0)
        val day = WorldTable.rows("Arctic/Longyearbyen", emptyList(), ref) { tromso }.single().bar
        assertEquals(WorldTable.Sky.POLAR_DAY, day.sky)
        assertEquals(listOf(true), day.spans.map { it.day })
        assertNull(day.rise)
        val ref2 = at(ZoneId.of("Europe/Oslo"), 2026, 12, 21, 12, 0)
        val night = WorldTable.rows("Arctic/Longyearbyen", emptyList(), ref2) { tromso }.single().bar
        assertEquals(WorldTable.Sky.POLAR_NIGHT, night.sky)
        assertEquals(listOf(false), night.spans.map { it.day })
        assertNull(night.set)
    }

    @Test fun `a zone with no coordinates is estimated at 06 to 18 local`() {
        val ref = at(madrid, 2026, 1, 15, 10, 0)
        val rows = WorldTable.rows("Europe/Madrid", listOf("Asia/Kolkata"), ref) { null }
        val b = rows[1].bar
        assertEquals(WorldTable.Sky.ESTIMATED, b.sky)
        assertEquals(6 * 60, b.rise)
        assertEquals(18 * 60, b.set)
        // Kolkata's 06:00 is 01:30 Madrid, its 18:00 is 13:30 Madrid.
        val light = b.spans.filter { it.day }
        assertEquals(1, light.size)
        assertEquals(1.5 / 24.0, light[0].from, 1e-9)
        assertEquals(13.5 / 24.0, light[0].to, 1e-9)
    }

    @Test fun `merge joins overlapping and touching intervals only`() {
        assertEquals(listOf(1L to 5L, 7L to 9L), WorldTable.merge(listOf(4L to 5L, 1L to 4L, 7L to 9L)))
        assertEquals(listOf(1L to 6L), WorldTable.merge(listOf(1L to 6L, 2L to 3L)))
        assertEquals(emptyList<Pair<Long, Long>>(), WorldTable.merge(emptyList()))
    }

    @Test fun `texts`() {
        assertEquals("05:30", WorldTable.hhmm(5 * 60 + 30))
        assertEquals("00:15", WorldTable.hhmm(24 * 60 + 15))
        assertNull(WorldTable.zoneOrNull(null))
        assertNull(WorldTable.zoneOrNull("nope/nope"))
        assertEquals(madrid, WorldTable.zoneOrNull("Europe/Madrid"))
    }

    // ── #1646 the SuperApp's stopwatch readouts, now the lib's ──────────────────────────────

    @Test fun `the Home bar readout is m-ss then h-mm-ss`() {
        assertEquals("0:00", ClockLogic.stopwatchBarText(0))
        assertEquals("1:05", ClockLogic.stopwatchBarText(65_999))
        assertEquals("59:59", ClockLogic.stopwatchBarText(3_599_999))
        assertEquals("1:00:00", ClockLogic.stopwatchBarText(3_600_000))
        assertEquals("0:00", ClockLogic.stopwatchBarText(-5))
    }

    @Test fun `the dropdown readout has tenths`() {
        assertEquals("0:00.0", ClockLogic.stopwatchTenthsText(0))
        assertEquals("1:05.3", ClockLogic.stopwatchTenthsText(65_300))
        assertEquals("61:01.9", ClockLogic.stopwatchTenthsText(3_661_999))
        assertEquals("0:00.0", ClockLogic.stopwatchTenthsText(-1))
    }

    // ── the declaration ─────────────────────────────────────────────────────────────────────

    @Test fun `the declaration parser reads Calc's clock objects and survives junk`() {
        val c = ClockConfig.parse(mapOf(
            "alarms" to """{"snooze_choices":[5,10],"snooze_default":5,"ring_minutes":3,"vibrate_default":false}""",
            "worldclock" to """{"zones":["Asia/Tokyo"]}""",
            "timers" to """{"presets":[60]}""",
            "bedtime" to """{"sleep_choices":[15],"bedtime_default":"22:30"}""",
            "interval" to """{"presets":[{"label":"P","work":10,"rest":5,"rounds":2},{"label":"bad"}]}""",
        ))
        assertEquals(listOf(5, 10), c.snoozeChoices)
        assertEquals(5, c.snoozeDefault)
        assertEquals(3 * ClockLogic.MINUTE_MS, c.ringMs)
        assertFalse(c.vibrateDefault)
        assertEquals(listOf("Asia/Tokyo"), c.zones)
        assertEquals(listOf(60L), c.timerPresets)
        assertEquals(listOf(15), c.sleepChoices)
        assertEquals(22 * 60 + 30, c.bedtimeDefault)
        assertEquals(listOf(ClockConfig.IntervalPreset("P", 10, 5, 0, 0, 2)), c.intervalPresets)
        val empty = ClockConfig.parse(mapOf("alarms" to "not json"))
        assertEquals(10 * ClockLogic.MINUTE_MS, empty.ringMs)
        assertEquals(emptyList<Int>(), empty.snoozeChoices)
        assertTrue(empty.vibrateDefault)
        assertEquals(23 * 60, empty.bedtimeDefault)
        assertEquals(emptyList<String>(), empty.zones)
    }

    @Test fun `the default declaration is Cloud Calc's`() {
        val d = ClockConfig.DEFAULT
        assertEquals(10 * ClockLogic.MINUTE_MS, d.ringMs)
        assertEquals(listOf(5, 10, 15, 20, 30), d.snoozeChoices)
        assertEquals(4, d.zones.size)
        assertEquals("Pomodoro", d.intervalPresets.first().label)
        assertEquals(23 * 60, d.bedtimeDefault)
    }
}
