package com.diegonmarcos.cloudnav.tracker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1862 / #1863 the segment model the Timeline strips and the Explored calendar share: blocks cover the 24 h without gap or overlap,
 * one colour per state, one icon per transport mode, the city under each sleep, and the day/night gradient from sunrise / sunset.
 */
class DaySegmentsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 11, 22)
    private val from = DayTimeline.dayStart(date, zone); private val to = DayTimeline.dayStart(date.plusDays(1), zone)
    private fun t(h: Double) = from + Math.round(h * 3_600_000)

    /** The 2026-11-22 Timeline of the ticket's screenshot, as `tracker/segments` serves it (a gap 16:20-17:00 left on purpose). */
    private fun segments(): JSONObject {
        fun loc(lat: Double, lon: Double, name: String = "", city: String = "Berlin") = JSONObject().put("lat", lat).put("lon", lon).put("name", name).put("city", city).put("address", "")
        val a = JSONArray()
        a.put(JSONObject().put("type", "stay").put("kind", "sleep").put("start", t(0.0)).put("end", t(8.0)).put("location", loc(52.50397, 13.44737)))
        a.put(JSONObject().put("type", "stay").put("kind", "idle").put("start", t(8.0)).put("end", t(11.87)).put("location", loc(52.50397, 13.44737)))
        a.put(JSONObject().put("type", "move").put("mode", "walk").put("start", t(11.87)).put("end", t(12.03)).put("distance_m", 368).put("steps", 600).put("from", loc(52.50397, 13.44737)).put("to", loc(52.50604, 13.45067)))
        a.put(JSONObject().put("type", "stay").put("kind", "idle").put("start", t(12.03)).put("end", t(12.17)).put("location", loc(52.50604, 13.45067, "Cafe")))
        a.put(JSONObject().put("type", "move").put("mode", "run").put("start", t(12.17)).put("end", t(12.9)).put("distance_m", 9200).put("steps", 3954).put("from", loc(52.50604, 13.45067)).put("to", loc(52.45833, 13.37439)))
        a.put(JSONObject().put("type", "move").put("mode", "train").put("start", t(13.72)).put("end", t(14.93)).put("distance_m", 9100).put("steps", 0).put("from", loc(52.45833, 13.37439)).put("to", loc(52.52, 13.40)))
        a.put(JSONObject().put("type", "stay").put("kind", "idle").put("start", t(14.93)).put("end", t(16.33)).put("location", JSONObject.NULL))
        a.put(JSONObject().put("type", "stay").put("kind", "sleep").put("start", t(23.0)).put("end", t(24.0)).put("location", loc(48.85, 2.35, city = "Paris")))
        return JSONObject().put("version", 1).put("segments", a)
    }

    private fun assertCovers(b: List<Block>) {
        assertEquals(from, b.first().startMs); assertEquals(to, b.last().endMs)
        b.zipWithNext().forEach { (x, y) -> assertEquals("no gap, no overlap", x.endMs, y.startMs) }
        assertTrue(b.all { it.endMs > it.startMs })
        assertEquals(to - from, b.sumOf { it.durationMs })
    }

    @Test fun `timeline rows become blocks that cover the 24 h without overlap, gaps as no data`() {
        val rows = TrackerRows.timeline(segments())
        val b = DaySegments.blocksOfRows(rows, from, to)
        assertCovers(b)
        assertEquals(SegState.SLEEP, b.first().state)
        assertTrue("the 12:54-13:43 gap is no data", b.any { it.state == SegState.NONE && it.startMs == t(12.9) && it.endMs == t(13.72) })
        assertEquals(SegState.NONE, b.single { it.startMs == t(14.93) }.state)   // a stay with no place
        assertEquals(SegState.STAY, b.single { it.startMs == t(12.03) }.state)   // a named place
        assertEquals(SegState.IDLE, b.single { it.startMs == t(8.0) }.state)
        // overlapping input never overlaps out: the earlier row wins
        val ov = DaySegments.cover(listOf(Block(t(1.0), t(5.0), SegState.SLEEP), Block(t(4.0), t(6.0), SegState.IDLE)), from, to)
        assertCovers(ov); assertEquals(t(5.0), ov.single { it.state == SegState.IDLE }.startMs)
        // mutation: dropping the gap fill leaves the day uncovered
        assertNotEquals(to - from, b.filter { it.state != SegState.NONE }.sumOf { it.durationMs })
    }

    @Test fun `colours per state are distinct, icons per mode are distinct, moves carry their mode`() {
        assertEquals(SegState.values().size, SegState.values().map { it.argb }.toSet().size)
        assertEquals(Mode.values().size, Mode.values().map { DaySegments.modeIcon(it) }.toSet().size)
        assertEquals("transit", DaySegments.modeIcon(Mode.TRAIN)); assertEquals("plane", DaySegments.modeIcon(Mode.PLANE))
        val b = DaySegments.blocksOfRows(TrackerRows.timeline(segments()), from, to)
        assertEquals(listOf(Mode.WALK, Mode.RUN, Mode.TRAIN), b.filter { it.state == SegState.MOVE }.map { it.mode })
        assertTrue(b.filter { it.state != SegState.MOVE }.all { it.mode == null })
    }

    @Test fun `each sleep carries the city it started in`() {
        val b = DaySegments.blocksOfRows(TrackerRows.timeline(segments()), from, to)
        assertEquals(listOf("Berlin", "Paris"), DaySegments.sleepCities(b).map { it.second })
        assertEquals("unknown city", DaySegments.sleepCities(listOf(Block(t(0.0), t(1.0), SegState.SLEEP))).single().second)
    }

    @Test fun `the explored day model gives the same states as the timeline rows`() {
        val home = Place(52.5, 13.4, null, city = "Berlin")
        val day = listOf(DaySeg.Stay(t(0.0), t(7.0), StayKind.SLEEP, home), DaySeg.Stay(t(7.0), t(8.0), StayKind.IDLE, home),
            DayTimeline.moveOf(t(8.0), t(8.5), 2000.0, 2600, home, home, Mode.WALK), DaySeg.Stay(t(8.5), t(17.0), StayKind.IDLE, home.copy(name = "Office")),
            DaySeg.Stay(t(17.0), t(24.0), StayKind.IDLE, null))
        val b = DaySegments.blocks(day, from, to)
        assertCovers(b)
        assertEquals(listOf(SegState.SLEEP, SegState.IDLE, SegState.MOVE, SegState.STAY, SegState.NONE), b.map { it.state })
        assertEquals("Berlin", b.first().city)
    }

    @Test fun `daylight from the location, else a fixed curve, and the strip runs black to blue`() {
        val june = DaySegments.daylight(LocalDate.of(2026, 6, 21), 52.52, 13.40, zone)
        fun hm(ms: Long?) = java.time.Instant.ofEpochMilli(ms!!).atZone(zone).toLocalTime().toSecondOfDay() / 60
        assertTrue("Berlin sunrise about 04:43", Math.abs(hm(june.sunriseMs) - (4 * 60 + 43)) <= 10)
        assertTrue("Berlin sunset about 21:33", Math.abs(hm(june.sunsetMs) - (21 * 60 + 33)) <= 10)
        val nov = DaySegments.daylight(date, 52.52, 13.40, zone)
        assertTrue(Math.abs(hm(nov.sunriseMs) - (7 * 60 + 41)) <= 10); assertTrue(Math.abs(hm(nov.sunsetMs) - (15 * 60 + 58)) <= 10)
        val fixed = DaySegments.daylight(date, null, null, zone)
        assertEquals(false, fixed.known); assertEquals(t(6.0), fixed.sunriseMs); assertEquals(t(18.0), fixed.sunsetMs)
        val polar = DaySegments.daylight(LocalDate.of(2026, 12, 21), 78.2, 15.6, ZoneId.of("Arctic/Longyearbyen"))
        assertNull(polar.sunriseMs); assertEquals(0f, DaySegments.brightness(DayTimeline.dayStart(LocalDate.of(2026, 12, 21), ZoneId.of("Arctic/Longyearbyen")) + 43_200_000L, polar))
        val g = DaySegments.gradient(nov, from)
        assertEquals(49, g.size); assertEquals(0f, g.first().first); assertEquals(1f, g.last().first)
        assertEquals(DaySegments.NIGHT_ARGB, g.first().second); assertEquals(DaySegments.NIGHT_ARGB, g.last().second)
        assertEquals(DaySegments.DAY_ARGB, g[24].second)   // noon is full day
        assertEquals(0f, DaySegments.brightness(t(3.0), nov)); assertEquals(1f, DaySegments.brightness(t(12.0), nov))
        // mutation: a day without a sunrise would be all night
        assertNotEquals(g, DaySegments.gradient(Daylight(null, null, true), from))
    }
}
