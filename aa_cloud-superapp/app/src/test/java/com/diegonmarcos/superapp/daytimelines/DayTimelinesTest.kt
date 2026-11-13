package com.diegonmarcos.superapp.daytimelines

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1734 the card's data: gradient stops, weather icons where the condition changes, the MET parse and cache round trip, and
 * the OFFLINE FALLBACK (last cache + its age, or a reason; never a throw). Robolectric: the plain classpath only has android.jar's org.json stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DayTimelinesTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 6, 21)
    private val startHour = date.atStartOfDay(berlin).toEpochSecond() / 3600L
    private val noon = date.atTime(12, 0).atZone(berlin).toInstant().toEpochMilli()

    private fun hours(symbol: (Int) -> String?, temp: (Int) -> Double = { 10.0 + it }) =
        (0 until 24).map { Hour(startHour + it, temp(it), symbol(it)) }

    @Test fun `day-night gradient follows the real solar events`() {
        val sun = Astro.solarDay(date, berlin, 52.52, 13.405)
        val start = date.atStartOfDay(berlin).toInstant().toEpochMilli(); val end = date.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()
        val s = DayTimelines.dayStops(sun, start, end)
        assertEquals(0f, s.first().frac, 0f); assertEquals(1f, s.last().frac, 0f)
        assertTrue("non-decreasing", s.zipWithNext().all { (a, b) -> a.frac <= b.frac })
        val rise = ((sun.sunrise!! - start).toDouble() / (end - start)).toFloat()
        val riseStop = s.first { it.argb == 0xFFF29E4C.toInt() }
        assertEquals(rise, riseStop.frac, 0.001f)
        val set = ((sun.sunset!! - start).toDouble() / (end - start)).toFloat()
        assertEquals(set, s.first { it.argb == 0xFFEF7B45.toInt() }.frac, 0.001f)
        assertEquals(0xFF0B1026.toInt(), s.first().argb); assertEquals(0xFF0B1026.toInt(), s.last().argb)
        assertEquals(0xFF8CD3FF.toInt(), s.first { it.frac == ((sun.solarNoon - start).toDouble() / (end - start)).toFloat() }.argb)
        // polar night is one flat colour
        val polar = Astro.solarDay(LocalDate.of(2026, 12, 21), ZoneId.of("Arctic/Longyearbyen"), 78.22, 15.63)
        assertEquals(2, DayTimelines.dayStops(polar, 0L, 1000L).size)
    }

    @Test fun `temperature gradient is relative min to max`() {
        assertEquals(0xFF3B82F6.toInt(), DayTimelines.tempColor(5.0, 5.0, 25.0))
        assertEquals(0xFFEF4444.toInt(), DayTimelines.tempColor(25.0, 5.0, 25.0))
        assertEquals(0xFF4ADE80.toInt(), DayTimelines.tempColor(15.0, 5.0, 25.0))
        assertEquals("a flat day sits mid-ramp", 0xFF4ADE80.toInt(), DayTimelines.tempColor(12.0, 12.0, 12.2))
        val m = DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405, hours({ null }, { 5.0 + it }), noon)
        assertEquals(24, m.temp.stops.size)
        assertEquals(0xFF3B82F6.toInt(), m.temp.stops.first().argb); assertEquals(0xFFEF4444.toInt(), m.temp.stops.last().argb)
        assertEquals(5.0, m.temp.minC!!, 0.0); assertEquals(28.0, m.temp.maxC!!, 0.0)
        assertEquals(0.5f / 24f, m.temp.stops.first().frac, 0.001f)
        assertEquals("28\u00B0~5\u00B0C", m.legend.temp)
    }

    @Test fun `weather icons sit where the condition changes`() {
        val m = DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405,
            hours({ if (it < 6) "clearsky_night" else if (it < 10) "heavyrain" else "partlycloudy_day" }), noon)
        assertEquals(listOf(0, 6, 10), m.temp.icons.map { it.hour })
        assertEquals(listOf(Cond.CLEAR, Cond.RAIN, Cond.PARTLY), m.temp.icons.map { it.cond })
        assertTrue("hour 0 is night in Berlin", m.temp.icons[0].night); assertFalse(m.temp.icons[1].night)
        assertEquals(6.5f / 24f, m.temp.icons[1].frac, 0.001f)
        // a flip-flop closer than 1.5 h is not drawn twice
        val busy = DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405, hours({ if (it % 2 == 0) "cloudy" else "rain" }), noon)
        assertTrue(busy.temp.icons.size <= 12)
        assertEquals(Cond.THUNDER, DayTimelines.condOf("lightrainshowersandthunder_day"))
        assertEquals(Cond.SNOW, DayTimelines.condOf("snow")); assertEquals(Cond.SLEET, DayTimelines.condOf("sleet"))
        assertEquals(Cond.FOG, DayTimelines.condOf("fog")); assertNull(DayTimelines.condOf("martian_dust")); assertNull(DayTimelines.condOf(null))
    }

    @Test fun `model carries the sky and the legend`() {
        val m = DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405, hours({ "cloudy" }), noon)
        assertEquals("Sunday 21 June 2026", m.header)
        assertEquals(listOf("sun", "moon", "mercury", "venus", "mars", "jupiter", "saturn"), m.sky.map { it.id })
        val d10 = LocalDate.of(2026, 6, 10)
        val m10 = DayTimelines.build(d10, berlin, "Berlin", 52.52, 13.405, emptyList(), d10.atTime(12, 0).atZone(berlin).toInstant().toEpochMilli())
        assertEquals("Gemini", m10.sky.first { it.id == "sun" }.sign.name)
        assertEquals(0.5f, m.nowFrac!!, 0.001f)
        assertTrue(m.legend.daylight, m.legend.daylight.matches(Regex("\\d\\d:\\d\\d - \\d\\d:\\d\\d")))
        assertTrue(m10.legend.signs, m10.legend.signs.startsWith("\u2609 \u264A"))
        assertNotNull(m.moon.phase)
        assertNull("a day other than today has no marker", DayTimelines.build(date, berlin, "x", 52.52, 13.405, emptyList(), noon + 3 * 86_400_000L).nowFrac)
    }

    @Test fun `MET body parses to hours with symbols and the cache round trips`() {
        fun e(t: String, c: Double, sym: String?) = """{"time":"$t","data":{"instant":{"details":{"air_temperature":$c}}""" +
            (sym?.let { ""","next_1_hours":{"summary":{"symbol_code":"$it"},"details":{}}""" } ?: "") + "}}"
        val body = """{"properties":{"timeseries":[${listOf(e("2026-06-21T10:00:00Z", 18.5, "rain"), e("2026-06-21T11:00:00Z", 19.0, null)).joinToString(",")}]}}"""
        val h = DayRepo.parseMet(body)
        assertEquals(2, h.size); assertEquals("rain", h[0].symbol); assertNull(h[1].symbol); assertEquals(18.5, h[0].tempC, 0.0)
        assertEquals(Instant.parse("2026-06-21T10:00:00Z").epochSecond / 3600L, h[0].epochHour)
        val c = DayRepo.Cache(123L, 52.5, 13.4, h)
        assertEquals(c, DayRepo.decode(DayRepo.encode(c)))
        assertTrue(DayRepo.parseMet("{nope").isEmpty()); assertTrue(DayRepo.parseMet(null).isEmpty()); assertNull(DayRepo.decode("garbage"))
    }

    // ---- offline fallback
    private class MemStore(var json: String? = null) : DayRepo.Store {
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }
    private val now = noon
    private val h3 = 3 * 3_600_000L
    private val cachedHours = hours({ "cloudy" })
    private fun cacheJson(savedMs: Long, lat: Double = 52.52, lon: Double = 13.405) = DayRepo.encode(DayRepo.Cache(savedMs, lat, lon, cachedHours))
    private val liveBody = """{"properties":{"timeseries":[{"time":"2026-06-21T12:00:00Z","data":{"instant":{"details":{"air_temperature":30.0}},"next_1_hours":{"summary":{"symbol_code":"clearsky_day"},"details":{}}}}]}}"""

    @Test fun `offline with a cache shows the cache and its age`() {
        val store = MemStore(cacheJson(now - h3))
        val r = DayRepo.resolve(now, 52.52, 13.405, store, null, true) { _, _ -> throw java.io.IOException("offline") }
        assertEquals(DataStatus.Kind.CACHED, r.status.kind); assertEquals(h3, r.status.ageMs)
        assertEquals(cachedHours, r.hours)
        assertTrue(r.status.line(), r.status.line().startsWith("Temperature: cached 3 h ago (offline"))
    }

    @Test fun `offline without a cache says why and does not throw`() {
        val r = DayRepo.resolve(now, 52.52, 13.405, MemStore(), null, true) { _, _ -> throw java.io.IOException("offline") }
        assertEquals(DataStatus.Kind.NONE, r.status.kind); assertTrue(r.hours.isEmpty())
        assertTrue(r.status.line(), r.status.line().startsWith("Temperature: unavailable - no forecast stored yet"))
        // a corrupt cache and an unreadable body are also just reasons
        val bad = DayRepo.resolve(now, 52.52, 13.405, MemStore("{{{"), null, true) { _, _ -> "<html>" }
        assertEquals(DataStatus.Kind.NONE, bad.status.kind); assertNotNull(bad.status.reason)
        val nul = DayRepo.resolve(now, 52.52, 13.405, MemStore(), null, true) { _, _ -> null }
        assertEquals(DataStatus.Kind.NONE, nul.status.kind)
    }

    @Test fun `a cache of another place is not shown here`() {
        val r = DayRepo.resolve(now, 40.4, -3.7, MemStore(cacheJson(now - h3)), null, true) { _, _ -> null }
        assertEquals(DataStatus.Kind.NONE, r.status.kind)
    }

    @Test fun `a fresh cache needs no request and a request merges and saves`() {
        val fresh = DayRepo.resolve(now, 52.52, 13.405, MemStore(cacheJson(now - 600_000L)), null, true) { _, _ -> error("no request expected") }
        assertEquals(DataStatus.Kind.LIVE, fresh.status.kind); assertEquals(600_000L, fresh.status.ageMs)
        val store = MemStore(cacheJson(now - h3))
        val r = DayRepo.resolve(now, 52.52, 13.405, store, null, true) { _, _ -> liveBody }
        assertEquals(DataStatus.Kind.LIVE, r.status.kind)
        assertEquals("the new hour wins, earlier hours stay", 30.0, r.hours.first { it.epochHour == Instant.parse("2026-06-21T12:00:00Z").epochSecond / 3600L }.tempC, 0.0)
        assertEquals(24, r.hours.size)
        assertEquals(now, DayRepo.decode(store.json)!!.savedMs)
        // no-fetch first paint: cache only, says it is refreshing
        val first = DayRepo.resolve(now, 52.52, 13.405, MemStore(cacheJson(now - h3)), null, false) { _, _ -> error("no request expected") }
        assertEquals(DataStatus.Kind.CACHED, first.status.kind)
    }

    @Test fun `the Weather badge's cached forecast seeds the card`() {
        val seedHours = hours({ "rain" }, { 7.0 })
        val r = DayRepo.resolve(now, 52.52, 13.405, MemStore(), seedHours to (now - 300_000L), false) { _, _ -> error("no request expected") }
        assertEquals(DataStatus.Kind.LIVE, r.status.kind); assertEquals(seedHours, r.hours)
    }

    @Test fun `hours older than 48 h are dropped by the merge`() {
        val old = Hour(now / 3_600_000L - 49, 1.0, null); val keep = Hour(now / 3_600_000L - 47, 2.0, null)
        assertEquals(listOf(keep), DayRepo.merge(listOf(old, keep), emptyList(), now))
    }

    @Test fun `data view is JSON with the sun moon and sky`() {
        val m = DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405, hours({ "cloudy" }), noon)
        val o = DayTimelines.toJson(m, DataStatus(DataStatus.Kind.CACHED, h3, "offline"))
        assertEquals("2026-06-21", o.getString("date")); assertEquals("Sunday", o.getString("weekday")); assertEquals("Berlin", o.getString("place"))
        assertEquals("CACHED", o.getJSONObject("dataStatus").getString("kind"))
        assertEquals(24, o.getJSONObject("temperature").getJSONArray("hourly").length())
        assertEquals(7, o.getJSONArray("sky").length())
        assertTrue(o.getJSONObject("sun").getString("sunrise").startsWith("2026-06-21T04:4"))
        assertTrue(o.toString(2).contains("\"moon\""))
    }

    @Test fun `status lines`() {
        assertEquals("Temperature: updated just now", DataStatus(DataStatus.Kind.LIVE, 0L).line())
        assertEquals("Temperature: updated 12 min ago", DataStatus(DataStatus.Kind.LIVE, 12 * 60_000L).line())
        assertEquals("Temperature: cached 2 d ago (offline)", DataStatus(DataStatus.Kind.CACHED, 49 * 3_600_000L, "offline").line())
        assertEquals("Temperature: unavailable - why", DataStatus(DataStatus.Kind.NONE, 0L, "why").line())
    }
}
