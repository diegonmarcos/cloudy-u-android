package com.diegonmarcos.cloudnav.maps.takeout

import com.diegonmarcos.cloudnav.maps.takeout.TakeoutTestKit.Jdbc
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** #1853 the import against a real SQLite: migration v5 to v6, idempotent re-import, cancel and resume, merge with tracked data, rebuild. */
class TakeoutImportDbTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(iso: String) = TkVal.ms(iso)!!
    private val records = JSONObject(TakeoutTestKit.text("records/Records.json")).getJSONArray("locations").length().toLong()

    private fun tables(j: Jdbc) = listOf("fixes", "imp_visits", "imp_candidates", "imp_activities", "imp_signals", "imp_profile").associateWith { j.count("SELECT COUNT(*) FROM $it") }
    private fun cols(j: Jdbc, t: String) = j.rows("PRAGMA table_info($t);").map { it[1] as String }.toSet()

    @Test fun `migration v5 to v6 keeps every old row as tracked and adds the source columns and the history tables`() {
        val j = TakeoutTestKit.v5()
        j.exec("INSERT INTO fixes (ts, lat, lon, accuracy, speed, provider) VALUES (1000, 52.5, 13.4, 10.0, 1.5, 'fused');")
        j.exec("INSERT INTO fixes (ts, lat, lon, provider) VALUES (2000, 52.6, 13.5, 'legacy-points');")
        j.exec("INSERT INTO timeline_segments (kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,city) VALUES ('STOP',0,5000,52.5,13.4,'Home',12,0.0,'fix','Berlin');")
        j.exec("INSERT INTO day_rollups VALUES ('2026-03-10', 100, 1000.0, 600000, 3600000, 2, 0, '{\"WALK\":1000.0}');")
        TakeoutSchema.MIGRATE_V6.forEach { j.exec(it) }
        assertEquals(2L, j.count("SELECT COUNT(*) FROM fixes WHERE source = 'tracked' AND ikey IS NULL"))
        assertEquals(listOf<Any?>(1000L, 52.5, 13.4, 10.0, 1.5, "fused"), j.rows("SELECT ts,lat,lon,accuracy,speed,provider FROM fixes WHERE ts = 1000")[0])
        assertEquals(listOf<Any?>("tracked", 0L, null), j.rows("SELECT source,prio,ref FROM timeline_segments")[0])
        assertEquals("tracked", j.rows("SELECT source FROM day_rollups")[0][0])
        assertTrue(cols(j, "fixes").containsAll(listOf("source", "altitude", "vertical_acc", "heading", "device_tag", "g_source", "activity_json", "extra_json", "raw_json", "ikey")))
        for ((t, c) in TakeoutSchema.COLS) if (t != TkTable.FIXES) assertEquals("$t has exactly the schema columns", c.toSet(), cols(j, t))
        assertTrue(cols(j, "imports").containsAll(listOf("status", "days", "visits", "activities", "places", "fixes")))
    }

    @Test fun `a fresh install and an upgraded database end up with the same columns`() {
        val a = TakeoutTestKit.v6(); val b = TakeoutTestKit.v5().also { TakeoutSchema.MIGRATE_V6.forEach { q -> it.exec(q) } }
        for (t in listOf("fixes", "timeline_segments", "day_rollups", "imp_visits", "imp_activities")) assertEquals(cols(a, t), cols(b, t))
    }

    @Test fun `the same export imported twice leaves the same rows (idempotent), from a folder and from a zip`() {
        val j = TakeoutTestKit.v6()
        val s1 = TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 1, utc)
        val once = tables(j)
        assertEquals(records + 2, once["fixes"]); assertEquals(7L, once["imp_visits"]); assertEquals(3L, once["imp_candidates"]); assertEquals(6L, once["imp_activities"])
        assertEquals(2L, once["imp_signals"]); assertEquals(3L, once["imp_profile"])
        assertEquals(setOf(TakeoutFormat.RECORDS, TakeoutFormat.SEMANTIC, TakeoutFormat.TIMELINE), s1.formats)
        assertEquals(4, s1.days); assertEquals(7L, s1.visits); assertEquals(6L, s1.activities); assertEquals(3, s1.places); assertEquals(records + 2, s1.fixes); assertFalse(s1.cancelled)
        assertEquals(ms("2026-03-10T06:00:00Z"), s1.firstMs)
        // again, from the same folder, then from the zip: nothing doubles
        TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 2, utc)
        assertEquals(once, tables(j))
        val bytes = TakeoutTestKit.zip()
        val s3 = TakeoutImporter.run(ZipSource("takeout.zip", bytes.size.toLong()) { bytes.inputStream() }, j, 3, utc)
        assertEquals(once, tables(j))
        assertEquals("the zip's Settings.json holds no location data", 5, s3.files); assertEquals(s1.visits, s3.visits); assertEquals(s1.activities, s3.activities)
        assertTrue(s3.failedFiles.isEmpty())
    }

    @Test fun `cancel keeps what was written and a second import completes it`() {
        val j = TakeoutTestKit.v6()
        var n = 0
        val s = TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 1, utc, cancelled = { n++ > 40 })
        assertTrue(s.cancelled)
        val partial = j.count("SELECT COUNT(*) FROM fixes")
        assertTrue("some rows were kept: $partial", partial > 0)
        TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 2, utc)
        assertEquals(records + 2, j.count("SELECT COUNT(*) FROM fixes")); assertEquals(7L, j.count("SELECT COUNT(*) FROM imp_visits"))
    }

    @Test fun `progress reports bytes and counts and ends at the total`() {
        val j = TakeoutTestKit.v6(); val seen = ArrayList<ImportProgress>()
        TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 1, utc, progress = { seen += it })
        assertTrue(seen.isNotEmpty()); val last = seen.last()
        assertEquals(1f, last.fraction, 0.001f); assertEquals(records + 2, last.fixes); assertEquals(7L, last.visits)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.bytesDone >= a.bytesDone })
    }

    @Test fun `a broken file is reported and the rest still imports`() {
        val j = TakeoutTestKit.v6()
        val src = object : TakeoutSource {
            override val label = "x"; override val totalBytes = 0L; override fun bytesRead() = 0L
            override fun entries() = sequenceOf(TakeoutEntry("bad.json", 0) { "{\"locations\":[{\"timestamp\":".byteInputStream() }, TakeoutEntry("Records.json", 0) { TakeoutTestKit.res("records/Records.json") })
        }
        val s = TakeoutImporter.run(src, j, 1, utc)
        assertEquals(1, s.failedFiles.size); assertEquals(records, s.fixes)
    }

    private fun rebuilt(): Jdbc {
        val j = TakeoutTestKit.v6()
        TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 1, utc)
        return j
    }
    private val now = ms("2026-03-20T00:00:00Z")
    private fun visible(j: Jdbc, from: String, to: String) = j.rows(TakeoutSchema.VISIBLE_SEGMENTS, listOf(ms(from), ms(to)))
    private fun bySource(rows: List<List<Any?>>) = rows.groupBy { it[12] as String }

    @Test fun `the rebuild turns the three imports into segments, all three sources stored`() {
        val j = rebuilt()
        val n = TakeoutRebuild.run(j, utc, now)
        assertTrue(n > 0)
        assertEquals(setOf("takeout-semantic", "timeline-export", "takeout-records"), j.rows("SELECT DISTINCT source FROM timeline_segments").map { it[0] as String }.toSet())
        val d10 = visible(j, "2026-03-10T00:00:00Z", "2026-03-10T23:59:59Z")
        assertEquals("Records.json alone: positions become stays and moves", setOf("takeout-records"), d10.map { it[12] }.toSet())
        assertTrue("stays", d10.count { it[0] != "MOVE" } >= 3); assertTrue("moves", d10.count { it[0] == "MOVE" } >= 2)
        val moveKm = d10.filter { it[0] == "MOVE" }.sumOf { it[7] as Double } / 1000
        assertTrue("two commutes of about 2.5 km: $moveKm", moveKm in 4.0..6.0)
        val d11 = visible(j, "2026-03-11T00:00:00Z", "2026-03-11T23:59:59Z")
        assertEquals(setOf("takeout-semantic"), d11.map { it[12] }.toSet())
        val names = d11.mapNotNull { it[5] as String? }.toSet()
        assertTrue(names.containsAll(listOf("Fixture Home", "Fixture Office")))
        val train = d11.first { it[0] == "MOVE" && it[8] == "mode:TRAIN" }; assertEquals(3100.0, train[7] as Double, 0.01)
        assertEquals("Berlin", d11.first { it[5] == "Fixture Office" }[10]); assertEquals("Germany", d11.first { it[5] == "Fixture Office" }[11])
        assertTrue("every imported stay links to its imp_visits row", d11.filter { it[0] != "MOVE" }.all { (it[13] as String).startsWith("v:") })
        val d12 = visible(j, "2026-03-12T00:00:00Z", "2026-03-12T23:59:59Z")
        assertTrue(d12.any { it[12] == "timeline-export" && it[8] == "mode:TRAIN" })   // IN_SUBWAY
    }

    @Test fun `a visit holding a night is a stay, a sleep and nothing else, never one whole sleep`() {
        val j = rebuilt(); TakeoutRebuild.run(j, utc, now)
        val night = j.rows("SELECT kind,start_ms,end_ms FROM timeline_segments WHERE source = 'takeout-semantic' AND ref LIKE 'v:%' AND start_ms >= ? AND start_ms < ? ORDER BY start_ms",
            listOf(ms("2026-03-11T18:00:00Z"), ms("2026-03-12T08:00:00Z")))
        assertEquals(listOf("STOP", "SLEEP"), night.map { it[0] })
        assertEquals(ms("2026-03-11T22:00:00Z"), night[1][1]); assertEquals(ms("2026-03-12T07:00:00Z"), night[1][2])
    }

    @Test fun `rebuilding twice gives the same segments and rollups`() {
        val j = rebuilt()
        TakeoutRebuild.run(j, utc, now)
        val a = j.rows("SELECT kind,start_ms,end_ms,source,ref FROM timeline_segments ORDER BY start_ms, kind"); val r = j.rows("SELECT * FROM day_rollups ORDER BY date")
        TakeoutRebuild.run(j, utc, now)
        assertEquals(a, j.rows("SELECT kind,start_ms,end_ms,source,ref FROM timeline_segments ORDER BY start_ms, kind")); assertEquals(r, j.rows("SELECT * FROM day_rollups ORDER BY date"))
        assertEquals(listOf("2026-03-10", "2026-03-11", "2026-03-12", "2026-03-13"), r.map { it[0] }.filter { (it as String) <= "2026-03-13" })
    }

    @Test fun `tracked data wins on overlap, the import is kept and shows again once the tracked rows go`() {
        val j = rebuilt()
        // the phone recorded its own office stay on the 10th (09:00-15:00) and its own rollup for that day
        j.exec("INSERT INTO timeline_segments (kind,start_ms,end_ms,lat,lon,place,steps,distance_m,loc_src,source,prio) VALUES ('STOP',?,?,52.5219,13.4132,'Tracked Office',0,0.0,'fix','tracked',0);", listOf(ms("2026-03-10T09:00:00Z"), ms("2026-03-10T15:00:00Z")))
        j.exec("INSERT INTO day_rollups (date,steps,distance_m,moving_ms,stopped_ms,visits,sleep_ms,by_mode) VALUES ('2026-03-10', 4242, 10.0, 1, 2, 1, 0, '{}');")
        TakeoutRebuild.run(j, utc, now)
        val storedImported = j.count("SELECT COUNT(*) FROM timeline_segments WHERE source = 'takeout-records'")
        val d10 = visible(j, "2026-03-10T00:00:00Z", "2026-03-10T23:59:59Z")
        assertTrue("the tracked stay is shown", d10.any { it[5] == "Tracked Office" })
        assertTrue("no imported row overlaps the tracked stay", d10.filter { it[12] != "tracked" }.none { (it[1] as Long) < ms("2026-03-10T15:00:00Z") && (it[2] as Long) > ms("2026-03-10T09:00:00Z") })
        assertTrue("the imported rows before and after it still show", d10.count { it[12] == "takeout-records" } >= 2)
        assertTrue("both are stored: the hidden imported office stay is still there", storedImported > d10.count { it[12] == "takeout-records" })
        assertEquals("a tracked day's rollup is never replaced by the import", 4242L, j.rows("SELECT steps FROM day_rollups WHERE date = '2026-03-10'")[0][0])
        assertEquals("tracked", j.rows("SELECT source FROM day_rollups WHERE date = '2026-03-10'")[0][0])
        assertEquals("import", j.rows("SELECT source FROM day_rollups WHERE date = '2026-03-11'")[0][0])
        // a step-only tracked move (no place) hides nothing
        j.exec("INSERT INTO timeline_segments (kind,start_ms,end_ms,steps,distance_m,loc_src,source,prio) VALUES ('MOVE',?,?,900,0.0,'steps','tracked',0);", listOf(ms("2026-03-11T00:00:00Z"), ms("2026-03-11T23:00:00Z")))
        assertEquals(setOf("takeout-semantic", "tracked"), visible(j, "2026-03-11T00:00:00Z", "2026-03-11T23:59:59Z").map { it[12] }.toSet())
        // the tracked rows go: the import shows through
        j.exec("DELETE FROM timeline_segments WHERE source = 'tracked';")
        assertEquals(storedImported, visible(j, "2026-03-10T00:00:00Z", "2026-03-10T23:59:59Z").count { it[12] == "takeout-records" }.toLong())
    }

    @Test fun `a semantic import hides the positions derived from raw ones where both cover the same time`() {
        val j = TakeoutTestKit.v6()
        TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 1, utc)
        // positions of the 11th as raw fixes too (a Records file that covers the semantic day)
        val day = ms("2026-03-11T08:30:00Z")
        for (k in 0..20) j.exec(TakeoutSchema.insertSql(TkTable.FIXES), TakeoutSchema.COLS.getValue(TkTable.FIXES).map { c ->
            when (c) { "ikey" -> "t$k"; "ts" -> day + k * 20 * 60_000L; "lat" -> 52.5219; "lon" -> 13.4132; "provider", "source" -> "takeout-records"; else -> null } })
        TakeoutRebuild.run(j, utc, now)
        val d11 = visible(j, "2026-03-11T08:30:00Z", "2026-03-11T16:30:00Z")
        assertTrue(d11.none { it[12] == "takeout-records" })
        assertTrue(j.count("SELECT COUNT(*) FROM timeline_segments WHERE source = 'takeout-records' AND start_ms >= ${ms("2026-03-11T08:00:00Z")} AND start_ms < ${ms("2026-03-11T17:00:00Z")}") > 0)
    }

    @Test fun `imported positions near tracked fixes are left to the tracked ones`() {
        val j = TakeoutTestKit.v6()
        TakeoutImporter.run(DirSource(TakeoutTestKit.dir()), j, 1, utc)
        j.exec("INSERT INTO fixes (ts,lat,lon,provider) VALUES (?,52.5163,13.3777,'fused');", listOf(ms("2026-03-10T07:00:00Z")))   // tracked, no ikey, source default
        val imported = j.rows("SELECT ts,lat,lon,accuracy,speed,provider FROM fixes WHERE source <> 'tracked' ORDER BY ts").map { Triple(it[0] as Long, it[1] as Double, it[2] as Double) }
        val kept = TakeoutDerive.preferTracked(imported.map { com.diegonmarcos.cloudnav.maps.Fix(it.first, it.second, it.third) }, longArrayOf(ms("2026-03-10T07:00:00Z")))
        assertTrue(kept.none { Math.abs(it.ts - ms("2026-03-10T07:00:00Z")) <= 15 * 60_000L })
        assertEquals(imported.count { Math.abs(it.first - ms("2026-03-10T07:00:00Z")) > 15 * 60_000L }, kept.size)
    }
}
