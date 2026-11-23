package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1863 the Explored cache: a second open recomputes nothing, an import recomputes only the dates it touches, and what the cache
 * serves equals a full recompute (stats, Places, Cities, World), before and after the import. Each check is shown to bite.
 */
class ExploredCacheTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val d0 = LocalDate.of(2026, 10, 1)
    private val days = 12
    private val dates = (0 until days).map { d0.plusDays(it.toLong()) }
    private val H = 3_600_000L
    private fun at(i: Int, h: Double) = DayTimeline.dayStart(d0.plusDays(i.toLong()), zone) + Math.round(h * H)
    /** Two days after the last date: nothing in [dates] is live. */
    private val now = DayTimeline.dayStart(d0.plusDays(days + 2L), zone)

    private fun stay(i: Int, a: Double, b: Double, name: String, city: String, country: String, lat: Double, lon: Double, kind: TlKind = TlKind.STOP) =
        TlSegment(kind, at(i, a), at(i, b), lat, lon, name, 0, 0.0, "fix", null, city, country)
    private fun move(i: Int, a: Double, b: Double, m: Double, steps: Long, lat: Double, lon: Double, mode: String? = null) =
        TlSegment(TlKind.MOVE, at(i, a), at(i, b), lat, lon, null, steps, m, mode?.let { "mode:$it" })

    /** Twelve days: nights in Berlin (then Lisbon on days 5-7), a morning walk, an office stay, a drive, an evening cafe. */
    private fun store(): List<TlSegment> = dates.indices.flatMap { i ->
        val away = i in 5..7
        val (city, country, lat, lon) = if (away) listOf("Lisbon", "Portugal", 38.72, -9.14) else listOf("Berlin", "Deutschland", 52.52, 13.40)
        lat as Double; lon as Double; city as String; country as String
        listOf(
            stay(i, -1.0, 7.0, "Home", city, country, lat, lon, TlKind.SLEEP),
            stay(i, 7.0, 8.0, "Home", city, country, lat, lon),
            move(i, 8.0, 8.5, 2_000.0, 2_600, lat + 0.01, lon),
            stay(i, 8.5, 17.0, "Office", city, country, lat + 0.01, lon),
            move(i, 17.0, 17.5, 12_000.0, 0, lat + 0.02, lon + 0.05, "car"),
            stay(i, 17.5, 20.0, "Cafe " + (i % 3), city, country, lat + 0.02, lon + 0.05),
            move(i, 20.0, 20.3, 1_500.0, 1_900, lat, lon),
            stay(i, 20.3, 23.0, "Home", city, country, lat, lon),
        )
    }

    private fun fullStats(store: List<TlSegment>, c: List<Correction> = emptyList(), m: List<Remembered> = emptyList()) = Aggregates.perDay(store, dates, now, zone, c, m)
    private fun fullDays(store: List<TlSegment>, c: List<Correction> = emptyList(), m: List<Remembered> = emptyList()) =
        dates.map { d -> DayTimeline.build(store, DayTimeline.dayStart(d, zone), minOf(DayTimeline.dayStart(d.plusDays(1), zone), now), c, m) }

    private fun assertParity(r: CacheResult, store: List<TlSegment>, c: List<Correction> = emptyList(), m: List<Remembered> = emptyList()) {
        assertEquals("cached stats equal a full recompute", fullStats(store, c, m), r.stats)
        val month = fullDays(store, c, m)
        assertEquals(Rankings.places(month), ExploredFold.places(r.digests))
        assertEquals(Rankings.cities(month), ExploredFold.cities(r.digests))
        assertEquals(Rankings.countries(month), ExploredFold.countries(r.digests))
    }

    @Test fun `a second open recomputes nothing and serves the same digests`() {
        val be = MemoryDigestBackend(); val s = store()
        val first = ExploredCache.refresh(be, s, dates, now, zone)
        assertEquals(dates, first.recomputed); assertEquals(days, be.rows.size)
        val before = ExploredCache.computeCount; val writes = be.writes
        val second = ExploredCache.refresh(be, s, dates, now, zone)
        assertTrue("second open computes nothing", second.recomputed.isEmpty())
        assertEquals(0L, ExploredCache.computeCount - before); assertEquals(writes, be.writes)
        assertEquals(days, second.reused); assertEquals(first.digests, second.digests)
        assertParity(second, s)
    }

    @Test fun `an import recomputes only the dates it touches, and the result equals a full recompute`() {
        val be = MemoryDigestBackend(); val s = store()
        ExploredCache.refresh(be, s, dates, now, zone)
        // an imported museum visit on day 3, 08:24-16:00 (it takes over most of the office stay: the day changes)
        val imp = TlSegment(TlKind.STOP, at(3, 8.4), at(3, 16.0), 52.51, 13.39, "Museum", 0, 0.0, null, null, "Berlin", "Deutschland", "takeout-semantic", "v1")
        val s2 = s + imp
        val before = ExploredCache.computeCount
        val r = ExploredCache.refresh(be, s2, dates, now, zone)
        val expect = ExploredCache.dirtyDates(imp.startMs, imp.endMs, zone).filter { it in dates }
        assertEquals(listOf(d0.plusDays(2), d0.plusDays(3), d0.plusDays(4)), expect)
        assertEquals(expect, r.recomputed)
        assertEquals(expect.size.toLong(), ExploredCache.computeCount - before)
        assertParity(r, s2)
        assertTrue(ExploredFold.places(r.digests).any { it.label == "Museum" })
    }

    @Test fun `a dirty mark, a schema change or an unreadable row recompute exactly those dates`() {
        val be = MemoryDigestBackend(); val s = store()
        ExploredCache.refresh(be, s, dates, now, zone)
        assertEquals(listOf(d0.plusDays(6)), ExploredCache.refresh(be, s, dates, now, zone, dirty = setOf(d0.plusDays(6))).recomputed)
        be.rows[d0.plusDays(2)] = "{not json"
        be.rows[d0.plusDays(8)] = be.rows.getValue(d0.plusDays(8)).replace("\"v\":${ExploredCache.SCHEMA}", "\"v\":${ExploredCache.SCHEMA - 1}")
        assertEquals(listOf(d0.plusDays(2), d0.plusDays(8)), ExploredCache.refresh(be, s, dates, now, zone).recomputed)
        ExploredCache.markDirty(be, at(10, 13.0), at(10, 14.0), zone)   // a mark reaches the dates whose sleep window holds it
        assertEquals(listOf(d0.plusDays(10), d0.plusDays(11)), ExploredCache.refresh(be, s, dates, now, zone).recomputed)
    }

    @Test fun `today is live, computed each open and never stored`() {
        val be = MemoryDigestBackend(); val s = store()
        val nowLive = at(days - 1, 15.0)   // the afternoon of the last date
        val r1 = ExploredCache.refresh(be, s, dates, nowLive, zone)
        assertEquals(listOf(dates.last()), r1.live)
        assertFalse(be.rows.containsKey(dates.last()))
        // yesterday's sleep window still reaches into today until noon: live too before 12:00
        assertTrue(ExploredCache.isLive(dates[days - 2], at(days - 1, 11.0), zone)); assertFalse(ExploredCache.isLive(dates[days - 2], at(days - 1, 13.0), zone))
        val r2 = ExploredCache.refresh(be, s, dates, nowLive, zone)
        assertTrue(r2.recomputed.isEmpty()); assertEquals(listOf(dates.last()), r2.live)
        assertEquals(Aggregates.perDay(s, dates, nowLive, zone), r2.stats)
    }

    @Test fun `a correction recomputes only its dates, a remembered place only the dates near it`() {
        val be = MemoryDigestBackend(); val s = store()
        ExploredCache.refresh(be, s, dates, now, zone)
        val c = listOf(Correction(Correction.Type.EDIT_PLACE, at(2, 9.0), at(2, 16.0), place = Place(52.53, 13.40, "Studio", city = "Berlin", country = "Deutschland")))
        val r = ExploredCache.refresh(be, s, dates, now, zone, c)
        assertEquals(ExploredCache.dirtyDates(c[0].startMs, c[0].endMs, zone).filter { it in dates }, r.recomputed)
        assertParity(r, s, c)
        // a place remembered in Lisbon changes only the Lisbon days (5-7) and their neighbours' windows
        val mem = listOf(Remembered(Place(38.72, -9.14, "Casa"), 100.0))
        val r2 = ExploredCache.refresh(be, s, dates, now, zone, c, mem)
        assertTrue(r2.recomputed.isNotEmpty()); assertTrue(r2.recomputed.all { it in d0.plusDays(4)..d0.plusDays(8) })
        assertParity(r2, s, c, mem)
    }

    @Test fun `every input field moves the checksum (mutation)`() {
        val s = store(); val d = d0.plusDays(4)
        val (a, b) = ExploredCache.window(d, zone)
        val slice = ExploredCache.Slicer(s).slice(a, b)
        assertEquals(s.filter { it.endMs > a && it.startMs < b }.toSet(), slice.toSet())
        val base = ExploredCache.checksum(d, slice, emptyList(), emptyList(), zone)
        val t = slice.first { it.kind == TlKind.STOP }
        val muts = listOf<(TlSegment) -> TlSegment>(
            { it.copy(startMs = it.startMs + 1) }, { it.copy(endMs = it.endMs - 1) }, { it.copy(lat = (it.lat ?: 0.0) + 1e-6) }, { it.copy(lon = (it.lon ?: 0.0) + 1e-6) },
            { it.copy(place = "X") }, { it.copy(steps = it.steps + 1) }, { it.copy(distanceM = it.distanceM + 0.5) }, { it.copy(locSource = "gps") },
            { it.copy(neighborhood = "N") }, { it.copy(city = "C") }, { it.copy(country = "K") }, { it.copy(source = "other") }, { it.copy(ref = "r") }, { it.copy(kind = TlKind.SLEEP) })
        for ((i, m) in muts.withIndex()) assertNotEquals("mutation $i must change the checksum", base, ExploredCache.checksum(d, slice.map { if (it === t) m(it) else it }, emptyList(), emptyList(), zone))
        assertNotEquals(base, ExploredCache.checksum(d, slice.drop(1), emptyList(), emptyList(), zone))
        assertNotEquals(base, ExploredCache.checksum(d.plusDays(1), slice, emptyList(), emptyList(), zone))
    }

    @Test fun `the parity check bites: a dropped stay or a changed digest differs from the full recompute (mutation)`() {
        val be = MemoryDigestBackend(); val s = store()
        val r = ExploredCache.refresh(be, s, dates, now, zone)
        val month = fullDays(s)
        val dropped = r.digests.mapIndexed { i, d -> if (i == 3) d.copy(stays = d.stays.drop(1)) else d }
        assertNotEquals(Rankings.places(month), ExploredFold.places(dropped))
        assertNotEquals(Rankings.cities(month), ExploredFold.cities(dropped))
        val bent = r.digests.mapIndexed { i, d -> if (i == 3) d.copy(stats = d.stats.copy(sleepMs = d.stats.sleepMs + 1)) else d }
        assertNotEquals(fullStats(s), bent.map { it.stats })
    }

    @Test fun `a digest survives its stored form`() {
        val r = ExploredCache.refresh(MemoryDigestBackend(), store(), dates, now, zone)
        for (d in r.digests) assertEquals(d, ExploredCache.fromJson(ExploredCache.toJson(d).toString()))
        assertTrue(r.digests.all { it.tracks }); assertTrue(r.digests.all { it.blocks.isNotEmpty() })
    }

    @Test fun `world and city tables keep the old fields and add continent, counts, km, steps and the last visit`() {
        val r = ExploredCache.refresh(MemoryDigestBackend(), store(), dates, now, zone)
        val world = ExploredFold.world(r.digests); val countries = ExploredFold.countries(r.digests)
        for (c in countries) { val w = world.single { it.country == c.country }; assertEquals(c.days, w.days); assertEquals(c.totalMs, w.totalMs) }
        val de = world.single { it.country == "Deutschland" }; val pt = world.single { it.country == "Portugal" }
        assertEquals("Europe", de.continent); assertEquals("Europe", pt.continent)
        assertEquals(1, pt.cities); assertEquals(5, pt.places)   // Home, Office, Cafe 2 / 0 / 1 in Lisbon
        assertEquals(d0.plusDays(7), pt.lastVisit); assertEquals(dates.last(), de.lastVisit)
        assertTrue(de.distanceM > 0 && de.steps > 0)
        val cities = ExploredFold.cityTable(r.digests); val ct = ExploredFold.cities(r.digests)
        for (c in ct) { val row = cities.single { it.city == c.city }; assertEquals(c.days, row.days); assertEquals(c.totalMs, row.totalMs); assertEquals(c.country ?: "", row.country) }
        assertEquals("Portugal", cities.single { it.city == "Lisbon" }.country)
        assertEquals(3 * (2_000.0 + 12_000.0 + 1_500.0), cities.single { it.city == "Lisbon" }.distanceM, 1.0)
    }

    @Test fun `continents from English, local names and codes`() {
        assertEquals("Europe", Continents.of("Deutschland")); assertEquals("Europe", Continents.of("Germany")); assertEquals("Europe", Continents.of("España"))
        assertEquals("Asia", Continents.of("Japan")); assertEquals("North America", Continents.of("United States")); assertEquals("South America", Continents.of("Brasil"))
        assertEquals("Africa", Continents.of("Morocco")); assertEquals("Oceania", Continents.of("New Zealand")); assertEquals("Europe", Continents.of("DE"))
        assertEquals("Other", Continents.of("Atlantis")); assertEquals("Other", Continents.of(null)); assertEquals("Other", Continents.of(""))
    }
}
