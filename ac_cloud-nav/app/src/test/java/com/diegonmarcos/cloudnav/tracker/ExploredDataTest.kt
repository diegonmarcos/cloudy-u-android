package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** #1863 Export / Import page logic: JSON round-trip, KMZ validity, duplicates, delete by dates, refresh parity, table sorting; each with mutations. */
class ExploredDataTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val d0 = LocalDate.of(2026, 11, 2)
    private val H = 3_600_000L
    private fun at(day: Int, h: Double) = DayTimeline.dayStart(d0.plusDays(day.toLong()), zone) + Math.round(h * H)

    private fun stay(id: Long, day: Int, a: Double, b: Double, name: String?, lat: Double?, lon: Double?, src: String = "tracked", prio: Int = 0, kind: TlKind = TlKind.STOP, city: String? = "Berlin") =
        SegRow(id, TlSegment(kind, at(day, a), at(day, b), lat, lon, name, 0, 0.0, "fix", null, city, "Deutschland", src, if (src == "tracked") null else "k$id"), prio)
    private fun move(id: Long, day: Int, a: Double, b: Double, m: Double, steps: Long, lat: Double, lon: Double, src: String = "tracked", prio: Int = 0) =
        SegRow(id, TlSegment(TlKind.MOVE, at(day, a), at(day, b), lat, lon, null, steps, m, "mode:walk", null, null, null, src, null), prio)

    /** A clean store: three days of sleep, a walk, an office, a walk home. */
    private fun clean(): List<SegRow> = (0 until 3).flatMap { d ->
        val b = d * 10L
        listOf(stay(b + 1, d, 0.0, 7.0, "Home", 52.50, 13.40, kind = TlKind.SLEEP), move(b + 2, d, 7.0, 7.5, 2_000.0, 2_700, 52.51, 13.41),
            stay(b + 3, d, 7.5, 17.0, "Office", 52.51, 13.41), move(b + 4, d, 17.0, 17.5, 2_000.0, 2_650, 52.50, 13.40),
            stay(b + 5, d, 17.5, 24.0, "Home", 52.50, 13.40))
    }
    private fun fixes(): List<FixRow> = (0 until 3).flatMap { d -> (0 until 6).map { i -> FixRow(d * 100L + i, at(d, 7.0 + i * 0.1), 52.50 + i * 0.002, 13.40 + i * 0.002, 12.0, 1.4, "gps", "tracked") } }

    private data class Totals(val ms: Map<TlKind, Long>, val dist: Double, val steps: Long, val count: Int)
    private fun totals(l: List<SegRow>) = Totals(l.groupBy { it.seg.kind }.mapValues { e -> e.value.sumOf { it.seg.endMs - it.seg.startMs } }, l.sumOf { it.seg.distanceM }, l.sumOf { it.seg.steps }, l.size)

    // --- export ---

    @Test fun `export JSON round-trips to equal data, and restoring it twice adds nothing`() {
        val b = ExportBundle(at(0, 0.0), at(3, 0.0), clean() + stay(99, 1, 20.0, 21.0, null, null, null, "takeout-semantic", 2, city = null), fixes(),
            listOf(SliceRow(at(0, 7.0), at(0, 7.5), 2700, "counter")), listOf(SleepSessionRow(at(0, 0.0), at(0, 7.0), "health")), listOf(DailyStepsRow(d0.toString(), 5350, "health")))
        val text = ExploredExport.toJson(b)
        assertEquals(b, ExploredExport.fromJson(text))
        assertNull(ExploredExport.fromJson("{\"format\":\"something-else\"}")); assertNull(ExploredExport.fromJson("not json"))
        val p1 = ExploredExport.restorePlan(emptyList(), emptyList(), b)
        assertEquals(b.segments, p1.segments); assertEquals(b.fixes, p1.fixes)
        val p2 = ExploredExport.restorePlan(b.segments, b.fixes, b)
        assertTrue(p2.segments.isEmpty()); assertTrue(p2.fixes.isEmpty())
        // mutation: a dropped field breaks the round trip
        assertNotEquals(b, ExploredExport.fromJson(text.replace("\"city\":\"Berlin\"", "\"city\":null")))
    }

    @Test fun `KMZ is a zip holding a valid doc_kml with a placemark per stay and a LineString per move`() {
        val b = ExportBundle(null, null, clean(), fixes())
        val bytes = ExploredExport.kmz(b, zone)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        assertEquals(listOf("doc.kml"), entries.keys.toList())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(entries.getValue("doc.kml")))
        assertEquals("kml", doc.documentElement.localName)
        val stays = clean().count { it.seg.kind != TlKind.MOVE }; val moves = clean().count { it.seg.kind == TlKind.MOVE }
        assertEquals(stays, doc.getElementsByTagNameNS("*", "Point").length)
        assertEquals(moves, doc.getElementsByTagNameNS("*", "LineString").length)
        assertEquals(stays + moves, doc.getElementsByTagNameNS("*", "Placemark").length)
        val ls = doc.getElementsByTagNameNS("*", "LineString").item(0).textContent.trim().split(Regex("\\s+"))
        assertTrue("a move line runs from the previous place through its fixes", ls.size >= 2)
        val (_, counts) = ExploredExport.kml(b, zone)
        assertEquals(ExploredExport.KmlCounts(stays, moves, 0), counts)
        // escaping: a place name with markup still parses
        val odd = ExportBundle(null, null, listOf(stay(1, 0, 1.0, 2.0, "Bar <&> \"Q\"", 52.5, 13.4)), emptyList())
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(ExploredExport.kml(odd, zone).first.toByteArray()))
        // mutation: an unlocated stay is skipped, not a broken placemark
        assertEquals(ExploredExport.KmlCounts(0, 0, 1), ExploredExport.kml(ExportBundle(null, null, listOf(stay(1, 0, 1.0, 2.0, "X", null, null)), emptyList()), zone).second)
    }

    // --- duplicates ---

    @Test fun `duplicates, exact and overlapping copies go, one copy is kept, totals equal a clean import`() {
        val c = clean()
        val exactCopies = c.map { it.copy(id = it.id + 1000, seg = it.seg.copy(source = "takeout-semantic", ref = "x${it.id}"), prio = 2) }
        val shifted = c.filter { it.seg.kind != TlKind.SLEEP }.map { r ->
            r.copy(id = r.id + 2000, seg = r.seg.copy(startMs = r.seg.startMs + 60_000, endMs = r.seg.endMs + 60_000, place = null, city = null, country = null, source = "takeout-records", ref = null,
                lat = r.seg.lat?.plus(0.0003)), prio = 3)
        }
        val dirty = c + exactCopies + shifted
        val plan = ExploredDedupe.plan(dirty, fixes() + fixes().map { it.copy(id = it.id + 5000, source = "takeout-records", accuracyM = 30.0) }, zone)
        val after = ExploredDedupe.apply(dirty, plan)
        assertEquals(totals(c).copy(), totals(after))
        assertEquals(c.size, after.size)
        assertEquals(exactCopies.size, plan.exact); assertEquals(shifted.size, plan.overlapping)
        assertEquals(fixes().size, plan.dropFixIds.size); assertTrue(plan.dropFixIds.all { it >= 5000 })
        // the richest copy stays: every kept row has its place name back
        assertTrue(after.filter { it.seg.kind != TlKind.MOVE }.all { it.seg.place != null })
        // the preview counts per day and kind
        assertEquals(3, plan.perDay.size); assertEquals(setOf("sleep", "stay", "move", "fix"), plan.perDay.values.flatMap { it.keys }.toSet())
        assertEquals(plan.total, plan.perDay.values.sumOf { it.values.sum() })
    }

    @Test fun `duplicates never remove a unique record`() {
        val c = clean()
        assertTrue(ExploredDedupe.plan(c, fixes(), zone).findings.isEmpty())
        // a short visit inside a long stay at ANOTHER place, a leg inside a long move, a stay at the same time far away: all unique
        val extra = listOf(stay(500, 1, 9.0, 9.5, "Cafe", 52.60, 13.50), move(501, 1, 7.1, 7.2, 300.0, 400, 52.51, 13.41),
            stay(502, 2, 8.0, 16.0, "Branch", 48.1, 11.5))
        assertTrue(ExploredDedupe.plan(c + extra, emptyList(), zone).findings.isEmpty())
        // the same place covered only 50 %: not a copy
        val half = stay(503, 0, 12.0, 20.0, "Office", 52.51, 13.41)
        assertFalse(ExploredDedupe.plan(c + half, emptyList(), zone).dropSegIds.contains(503))
    }

    @Test fun `the duplicate rules bite (mutation)`() {
        val a = stay(1, 0, 8.0, 10.0, "Office", 52.51, 13.41).seg
        assertTrue(ExploredDedupe.isDup(a, a.copy(source = "x")))
        assertFalse("another kind", ExploredDedupe.isDup(a, a.copy(kind = TlKind.SLEEP)))
        assertFalse("another place", ExploredDedupe.isDup(a, a.copy(lat = 52.60)))
        assertFalse("a sliver", ExploredDedupe.isDup(a, a.copy(startMs = a.startMs + 90 * 60_000L, endMs = a.endMs + 90 * 60_000L)))
        val m = move(2, 0, 7.0, 8.0, 1000.0, 1300, 52.5, 13.4).seg
        assertFalse("a leg of a move is not a copy", ExploredDedupe.isDup(m, m.copy(startMs = m.startMs + 10 * 60_000L, endMs = m.startMs + 20 * 60_000L)))
        assertTrue(ExploredDedupe.richness(stay(1, 0, 0.0, 1.0, "N", 1.0, 1.0)) > ExploredDedupe.richness(stay(1, 0, 0.0, 1.0, null, null, null, city = null)))
    }

    // --- delete by dates ---

    @Test fun `delete by dates removes only that range`() {
        val c = clean(); val f = fixes()
        val (from, to) = ExploredDelete.range(d0.plusDays(1), d0.plusDays(1), zone)
        val p = ExploredDelete.plan(c, f, from, to)
        val after = ExploredDelete.apply(c, p)
        assertTrue("nothing left inside", after.none { it.seg.endMs > from && it.seg.startMs < to })
        assertEquals(c.filter { it.seg.endMs <= from || it.seg.startMs >= to }.toSet(), after.toSet())
        assertEquals(f.filter { it.ts in from until to }.map { it.id }, p.fixIds)
        // a range inside a row cuts it into two parts, steps and distance shrink with the time
        val long = move(900, 0, 6.0, 12.0, 6_000.0, 6_000, 52.5, 13.4)
        val p2 = ExploredDelete.plan(listOf(long), emptyList(), at(0, 8.0), at(0, 10.0))
        val parts = ExploredDelete.apply(listOf(long), p2)
        assertEquals(listOf(at(0, 6.0) to at(0, 8.0), at(0, 10.0) to at(0, 12.0)), parts.map { it.seg.startMs to it.seg.endMs })
        assertEquals(4_000.0, parts.sumOf { it.seg.distanceM }, 1.0); assertEquals(4_000L, parts.sumOf { it.seg.steps })
        // outside time is untouched: total duration outside the range is kept exactly
        fun outside(l: List<SegRow>) = l.sumOf { r -> (minOf(r.seg.endMs, from) - r.seg.startMs).coerceAtLeast(0) + (r.seg.endMs - maxOf(r.seg.startMs, to)).coerceAtLeast(0) }
        assertEquals(outside(c), outside(after))
        // mutation: a plan that forgets the cut leaves time inside the range
        assertTrue(ExploredDelete.apply(listOf(long), p2.copy(updates = emptyList(), inserts = emptyList())).any { it.seg.endMs > at(0, 8.0) && it.seg.startMs < at(0, 10.0) })
    }

    // --- refresh ---

    @Test fun `refresh (clear then rebuild) equals a full recompute`() {
        val store = clean().map { it.seg }
        val dates = (0 until 3).map { d0.plusDays(it.toLong()) }
        val now = at(5, 0.0)
        val be = MemoryDigestBackend()
        ExploredCache.refresh(be, store, dates, now, zone)
        be.rows[dates[1]] = ExploredCache.toJson(ExploredCache.fromJson(be.rows.getValue(dates[1]))!!.let { it.copy(stats = it.stats.copy(sleepMs = 1)) }).toString()
        assertNotEquals("a planted stale digest is served while its checksum holds", Aggregates.perDay(store, dates, now, zone), ExploredCache.refresh(be, store, dates, now, zone).stats)
        be.clear()
        val r = ExploredCache.refresh(be, store, dates, now, zone)
        assertEquals(dates, r.recomputed)
        assertEquals(Aggregates.perDay(store, dates, now, zone), r.stats)
    }

    // --- tables ---

    @Test fun `tables sort by any column both ways, nulls last`() {
        val rows = listOf(
            WorldRow("Europe", "Deutschland", 3, 10, 40, 900L, 1200.0, 9000, LocalDate.of(2026, 11, 20)),
            WorldRow("Asia", "Japan", 2, 4, 5, 300L, 5000.0, 20000, LocalDate.of(2026, 3, 2)),
            WorldRow("Europe", "Portugal", 1, 5, 7, 500L, 300.0, 4000, null))
        for (c in TableSort.WORLD) {
            val asc = TableSort.sort(rows, c, true); val desc = TableSort.sort(rows, c, false)
            val keys = asc.mapNotNull { c.key(it) }
            @Suppress("UNCHECKED_CAST") val sortedKeys = (keys as List<Comparable<Any>>).sortedWith { x, y -> if (x is String && y is String) x.compareTo(y, true) else x.compareTo(y) }
            assertEquals("column ${c.id} ascending", sortedKeys, keys)
            assertEquals("column ${c.id} descending", sortedKeys.reversed(), desc.mapNotNull { c.key(it) })
            if (rows.any { c.key(it) == null }) { assertNull(c.key(asc.last())); assertNull(c.key(desc.last())) }
        }
        assertEquals(listOf("Asia", "Europe", "Europe"), TableSort.sort(rows, TableSort.WORLD.first { it.id == "continent" }, true).map { it.continent })
        assertEquals("Japan", TableSort.sort(rows, TableSort.WORLD.first { it.id == "km" }, false).first().country)
        // every column the old lists showed is in the new tables (rule 16)
        assertTrue(TableSort.WORLD.map { it.id }.containsAll(listOf("country", "days", "time")))
        assertTrue(TableSort.CITIES.map { it.id }.containsAll(listOf("city", "country", "days", "time")))
        assertTrue(TableSort.PLACES.map { it.id }.containsAll(listOf("place", "city", "country", "visits", "time")))
        assertEquals(listOf("continent", "country"), TableSort.CITIES.take(2).map { it.id }); assertEquals("continent", TableSort.WORLD.first().id)
    }
}
