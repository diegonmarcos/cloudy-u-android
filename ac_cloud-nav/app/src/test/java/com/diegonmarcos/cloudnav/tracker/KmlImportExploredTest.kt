package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import com.diegonmarcos.cloudnav.maps.takeout.FileSource
import com.diegonmarcos.cloudnav.maps.takeout.Gazetteer
import com.diegonmarcos.cloudnav.maps.takeout.ImportSummary
import com.diegonmarcos.cloudnav.maps.takeout.SqlExec
import com.diegonmarcos.cloudnav.maps.takeout.TakeoutImporter
import com.diegonmarcos.cloudnav.maps.takeout.TakeoutRebuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The SQL the importer and the rebuild issue, held in memory (the app's JVM tests have no SQLite): rows inserted by their column list
 * (keyed, so INSERT OR REPLACE / OR IGNORE keep their meaning), the deletes, and the selects of [TakeoutRebuild.run].
 */
class MemSql : SqlExec {
    val tables = LinkedHashMap<String, LinkedHashMap<Any, Map<String, Any?>>>()
    private var auto = 0L
    private val ins = Regex("""INSERT(?: OR (\w+))? INTO (\w+) \(([^)]*)\) VALUES""", RegexOption.IGNORE_CASE)
    private val sel = Regex("""SELECT (.+?) FROM (\w+)""", RegexOption.IGNORE_CASE)
    private val del = Regex("""DELETE FROM (\w+)""", RegexOption.IGNORE_CASE)

    override fun exec(sql: String, args: List<Any?>) {
        val m = ins.find(sql)
        if (m != null) {
            val row = m.groupValues[3].split(',').map { it.trim() }.zip(args).toMap()
            val t = tables.getOrPut(m.groupValues[2]) { LinkedHashMap() }
            val k: Any = row["key"] ?: row["ikey"] ?: (++auto)
            if (m.groupValues[1].equals("IGNORE", true) && t.containsKey(k)) return
            t[k] = row
            return
        }
        val d = del.find(sql) ?: return
        val t = tables[d.groupValues[1]] ?: return
        if (sql.contains("source <> 'tracked'")) t.values.removeAll { it["source"] != "tracked" } else t.clear()
    }

    override fun rows(sql: String, args: List<Any?>): List<List<Any?>> {
        if (sql.contains("MIN(ts)")) return listOf(listOf(null, null))
        val m = sel.find(sql) ?: return emptyList()
        val cols = m.groupValues[1].split(',').map { it.trim() }
        var rs: List<Map<String, Any?>> = tables[m.groupValues[2]]?.values?.toList() ?: emptyList()
        if (sql.contains("parent_key IS NULL")) rs = rs.filter { it["parent_key"] == null }
        if (sql.contains("s.prio > 0")) rs = rs.filter { ((it["prio"] as? Number)?.toInt() ?: 0) > 0 }
        if (sql.contains("ORDER BY")) rs = rs.sortedBy { ((it["start_ms"] ?: it["ts"]) as Number).toLong() }
        return rs.map { r -> cols.map { r[it] } }
    }

    override fun begin() {}
    override fun commit() {}
    override fun rollback() {}

    /** The stored segments (every one is visible: the import is the only source). */
    fun segments(): List<TlSegment> = (tables["timeline_segments"]?.values?.toList() ?: emptyList()).map { r ->
        TlSegment(TlKind.parse(r["kind"] as String), (r["start_ms"] as Number).toLong(), (r["end_ms"] as Number).toLong(), (r["lat"] as? Number)?.toDouble(),
            (r["lon"] as? Number)?.toDouble(), r["place"] as String?, (r["steps"] as Number).toLong(), (r["distance_m"] as? Number)?.toDouble() ?: 0.0,
            r["loc_src"] as String?, r["neighborhood"] as String?, r["city"] as String?, r["country"] as String?, r["source"] as String, r["ref"] as String?)
    }.sortedBy { it.startMs }
}

/**
 * #1887 a Google Timeline converted to KML (the structure of the owner's export, on SYNTHETIC data: test/gen-kml-fixture.py, invented
 * cities and countries) goes through the real importer and rebuild into Explored: World, Cities, Places, Trips and Insights fill, the
 * sleep in another time zone drives that day's daylight on the city's own clock, and the Day header leads with the sleep city while
 * keeping every earlier field (rule 16). Each was red on the previous code (the importer skipped a .kml.xml file).
 */
class KmlImportExploredTest {
    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val first = LocalDate.of(2025, 2, 28)
    /** The history ends months before today, as an old export does. */
    private val today = LocalDate.of(2025, 9, 30)
    private val now = DayTimeline.dayStart(today, zone) + 10 * 3_600_000L

    private fun res(p: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(p)!!.use { it.readBytes() }
    private val gaz: Gazetteer by lazy { Gazetteer.parse(String(res("kml/gazetteer.tsv"), Charsets.UTF_8).reader()) }

    private class Imported(val summary: ImportSummary, val segments: List<TlSegment>, val explored: AggregatesStore.Explored)

    private val imported: Imported by lazy {
        val bytes = res("kml/Synthetic.kml.xml"); val sql = MemSql()
        val s = TakeoutImporter.run(FileSource("Synthetic.kml.xml", bytes.size.toLong()) { ByteArrayInputStream(bytes) }, sql, 1L, zone)
        TakeoutRebuild.run(sql, zone, now, geo = gaz)
        val segs = sql.segments()
        Imported(s, segs, AggregatesStore.explore(MemoryDigestBackend(), segs, first, today, now, zone, emptyList(), emptyList(), null))
    }

    private fun sleepDay(city: String) = imported.explored.stats.first { it.sleepCity == city }

    @Test fun `the KML import reads every Point as a visit and every Path as a move with the offsets of its times`() {
        val s = imported.summary
        assertTrue("no file failed: ${s.failedFiles}", s.failedFiles.isEmpty())
        assertEquals("every Point is a visit", 48L, s.visits)
        assertEquals("every Path is a move, with or without coordinates", 58L, s.activities)
        assertTrue(s.formats.map { it.sourceTag }.contains("timeline-kml"))
        // 21:00 at +09:00 is 12:00 UTC: the offset of the description is kept
        val t = OffsetDateTime.parse("2025-03-12T21:00:00.000+09:00").toInstant().toEpochMilli()
        assertTrue(imported.segments.any { it.kind != TlKind.MOVE && it.startMs == t && it.city == "Dunmore Bay" && it.country == "Eastreach" })
        // a visit wins over the paths that overlap it (the raw two-hour windows of the export): no stay is covered by a move
        val stays = imported.segments.filter { it.kind != TlKind.MOVE }; val moves = imported.segments.filter { it.kind == TlKind.MOVE }
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.none { m -> stays.any { it.startMs < m.endMs && m.startMs < it.endMs } })
    }

    @Test fun `World, Cities, Places, Trips and Insights are filled from the KML import`() {
        val e = imported.explored
        assertEquals("World", setOf("Northland", "Eastreach", "Southmark"), e.world.map { it.country }.toSet())
        assertEquals(setOf("Northland", "Eastreach", "Southmark"), e.countries.map { it.country }.toSet())
        assertTrue("Cities: ${e.cityTable.map { it.city }}", e.cityTable.map { it.city }.containsAll(listOf("Aldoria", "Brenvik", "Dunmore Bay", "Castamar")))
        assertTrue(e.cities.isNotEmpty())
        assertTrue("Places: ${e.places.map { it.label }}", e.places.map { it.label }.containsAll(listOf("Home", "Work")))
        assertEquals("Trips: home", "Aldoria", e.trips.home)
        assertEquals("Trips: ${e.trips.trips.map { it.cities }}", 2, e.trips.trips.size)
        assertEquals(listOf("Brenvik"), e.trips.trips[0].cities)
        assertTrue(e.trips.trips[1].cities.containsAll(listOf("Dunmore Bay", "Castamar")))
        assertTrue("the flights are planes", e.trips.trips[1].vehicleM.containsKey(Mode.PLANE))
        val week = InsightsData.week(e.stats)
        assertEquals("Insights: a week", 7, week.size)
        assertTrue("Insights: the week holds the recorded sleep", week.any { it.sleepMs > 0 })
        assertTrue(DayJson.daily(e.stats).toString().contains("Aldoria"))
    }

    @Test fun `the daylight strip follows the sleep city on that city's own clock`() {
        val st = sleepDay("Dunmore Bay")
        val sleep = DaySegments.sleepOf(st, emptyList(), gaz, zone)
        assertNotNull("the sleep place is known", sleep)
        assertEquals("Dunmore Bay", sleep!!.city); assertEquals(tokyo, sleep.zone)
        val l = DaySegments.dayLight(st.date, sleep, emptyList(), zone)
        assertEquals(tokyo, l.zone)
        assertEquals("the strip is the city's own 24 h", st.date.atStartOfDay(tokyo).toInstant().toEpochMilli(), l.from)
        assertTrue(l.daylight.known)
        val rise = Instant.ofEpochMilli(l.daylight.sunriseMs!!).atZone(tokyo).toLocalTime(); val set = Instant.ofEpochMilli(l.daylight.sunsetMs!!).atZone(tokyo).toLocalTime()
        assertTrue("sunrise $rise in local time", rise.hour in 5..6)
        assertTrue("sunset $set in local time", set.hour in 17..18)
        val h = sleepDay("Aldoria")
        assertEquals("a home day keeps the home city's clock", zone, DaySegments.dayLight(h.date, DaySegments.sleepOf(h, emptyList(), gaz, zone), emptyList(), zone).zone)
    }

    @Test fun `the Day header leads with the sleep city and keeps every earlier field (rule 16)`() {
        val st = sleepDay("Dunmore Bay")
        val sleep = DaySegments.sleepOf(st, emptyList(), gaz, zone)
        val day = DayTimeline.build(imported.segments, DayTimeline.dayStart(st.date, zone), DayTimeline.dayStart(st.date.plusDays(1), zone))
        val bar = DayDetails.bar(day, StepsTotal(1234, null))
        val f = DayHeader.fields(bar, sleep)
        assertEquals("City" to "Dunmore Bay", f.first())
        assertEquals("Eastreach", f.toMap()["Country"]); assertEquals("Asia/Tokyo", f.toMap()["Time zone"])
        // field parity with the previous header (the summary bar): every earlier label, in its order, with the value it showed
        val old = listOf("Total km" to "%.1f".format(Locale.US, bar.distanceM / 1000), "Total steps" to StepsFormat.n(1234),
            "Time active" to StepsFormat.duration(bar.activeMs), "Time stopped" to StepsFormat.duration(bar.stoppedMs))
        assertEquals(old, f.filter { p -> old.any { it.first == p.first } })
    }
}
