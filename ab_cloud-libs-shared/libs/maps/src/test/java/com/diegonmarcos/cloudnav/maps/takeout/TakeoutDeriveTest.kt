package com.diegonmarcos.cloudnav.maps.takeout

import com.diegonmarcos.cloudnav.maps.Fix
import com.diegonmarcos.cloudnav.maps.TlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** #1853 the pure pieces between the import tables and the pages: modes, places, nights, tracked-wins fixes, chunked derivation, rollups. */
class TakeoutDeriveTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(iso: String) = TkVal.ms(iso)!!

    @Test fun `every Google activity spelling maps to a Day page mode, unknown ones to the speed inference`() {
        val m = TakeoutDerive::mode
        assertEquals("TRAIN", m("IN_TRAIN")); assertEquals("TRAIN", m("in subway")); assertEquals("TRAIN", m("IN_TRAM")); assertEquals("CAR", m("IN_PASSENGER_VEHICLE")); assertEquals("CAR", m("IN_BUS"))
        assertEquals("CAR", m("MOTORCYCLING")); assertEquals("WALK", m("walking")); assertEquals("WALK", m("ON_FOOT")); assertEquals("RUN", m("RUNNING")); assertEquals("BIKE", m("CYCLING"))
        assertEquals("BIKE", m("ON_BICYCLE")); assertEquals("PLANE", m("FLYING")); assertNull(m("STILL")); assertNull(m("SKIING")); assertNull(m(null)); assertNull(m("UNKNOWN_ACTIVITY_TYPE"))
    }

    @Test fun `city and country come out of an address, the full address stays in the table`() {
        assertEquals("Berlin" to "Germany", TakeoutDerive.cityCountry("Fixture Platz 2, 10178 Berlin, Germany"))
        assertEquals("Berlin" to "Germany", TakeoutDerive.cityCountry("Berlin, Germany"))
        assertEquals("London" to "United Kingdom", TakeoutDerive.cityCountry("1 Fixture Rd, London SW1A 1AA, United Kingdom"))
        assertEquals(null to null, TakeoutDerive.cityCountry("Somewhere")); assertEquals(null to null, TakeoutDerive.cityCountry(null))
    }

    @Test fun `the night inside a long stay is found, a short stay has none`() {
        val n = TakeoutDerive.nightOf(ms("2026-03-11T18:00:00Z"), ms("2026-03-12T07:00:00Z"), utc)!!
        assertEquals(ms("2026-03-11T22:00:00Z"), n.first); assertEquals(ms("2026-03-12T07:00:00Z"), n.last)
        assertNull(TakeoutDerive.nightOf(ms("2026-03-11T10:00:00Z"), ms("2026-03-11T16:00:00Z"), utc))
        assertNull(TakeoutDerive.nightOf(ms("2026-03-11T21:00:00Z"), ms("2026-03-11T23:30:00Z"), utc))
        val whole = TakeoutDerive.nightOf(ms("2026-03-11T00:00:00Z"), ms("2026-03-12T23:59:00Z"), utc)!!
        assertTrue("a 48 h visit holds one night, not 48 h of sleep", whole.last - whole.first <= 10 * 3_600_000L)
    }

    @Test fun `a visit becomes day, sleep and morning rows and keeps its link to the import row`() {
        val v = TakeoutDerive.VisitRow("v:1", "takeout-semantic", ms("2026-03-11T18:00:00Z"), ms("2026-03-12T09:00:00Z"), 52.5, 13.4, "Fixture Home", "Fixture Strasse 1, 10117 Berlin, Germany")
        val segs = TakeoutDerive.visitSegments(v, utc)
        assertEquals(listOf(TlKind.STOP, TlKind.SLEEP, TlKind.STOP), segs.map { it.kind })
        assertTrue(segs.all { it.ref == "v:1" && it.source == "takeout-semantic" && it.place == "Fixture Home" && it.city == "Berlin" && it.country == "Germany" })
        assertEquals(v.startMs, segs.first().startMs); assertEquals(v.endMs, segs.last().endMs)
        for ((a, b) in segs.zipWithNext()) assertEquals(a.endMs, b.startMs)
    }

    @Test fun `an activity without a distance gets one from its path or its end points`() {
        val a = TakeoutDerive.ActivityRow("a:1", "timeline-export", 0, 600_000, 52.5163, 13.3777, 52.5219, 13.4132, null, "WALKING", null)
        val s = TakeoutDerive.activitySegment(a)!!
        assertEquals("mode:WALK", s.locSource); assertTrue("about 2.5 km: ${s.distanceM}", s.distanceM in 2300.0..2700.0); assertEquals(52.5219, s.lat!!, 1e-9)
        val p = TakeoutDerive.activitySegment(TakeoutDerive.ActivityRow("p:1", "timeline-export", 0, 600_000, null, null, null, null, null, null,
            """[{"point":"52.5163000°, 13.3777000°","time":"x"},{"point":"geo:52.5219,13.4132"}]"""))!!
        assertEquals("import", p.locSource); assertTrue(p.distanceM in 2300.0..2700.0)
        assertNull(TakeoutDerive.activitySegment(TakeoutDerive.ActivityRow("a:2", "x", 5, 5, null, null, null, null, null, null, null)))
    }

    @Test fun `path points are read in every spelling`() {
        assertEquals(2, TakeoutDerive.pathPoints("""{"points":[{"latE7":525163000,"lngE7":133777000},{"latE7":1,"lngE7":2}]}""").size)
        assertEquals(1, TakeoutDerive.pathPoints("""{"waypoints":[{"latE7":525163000,"lngE7":133777000}]}""").size)
        assertEquals(52.5163, TakeoutDerive.pathPoints("""[{"latitudeE7":525163000,"longitudeE7":133777000}]""")[0].first, 1e-7)
    }

    @Test fun `tracked fixes win within the window, imported ones elsewhere stay`() {
        val imp = (0..10).map { Fix(it * 10 * 60_000L, 52.0, 13.0) }
        val kept = TakeoutDerive.preferTracked(imp, longArrayOf(50 * 60_000L), 15 * 60_000L)
        assertEquals(listOf(0L, 10L, 20L, 30L, 70L, 80L, 90L, 100L).map { it * 60_000L }, kept.map { it.ts })
        assertEquals(imp, TakeoutDerive.preferTracked(imp, LongArray(0)))
    }

    @Test fun `the chunked derivation of a month is the same as one pass over the same fixes`() {
        val fx = ArrayList<Fix>()
        TakeoutTestKit.Collect().also { c -> TakeoutTestKit.parseAll(TakeoutTestKit.RECORDS_FILES, c); c.of(TkTable.FIXES).forEach { fx += Fix(it["ts"] as Long, it["lat"] as Double, it["lon"] as Double, it["accuracy"] as Double?) } }
        val now = ms("2026-03-20T00:00:00Z")
        val one = TakeoutDerive.fromFixes(fx.first().ts, fx.last().ts, { a, b -> fx.filter { it.ts in a..b } }, { _, _ -> LongArray(0) }, now, utc)
        val chunked = TakeoutDerive.fromFixes(fx.first().ts, fx.last().ts, { a, b -> fx.filter { it.ts in a..b } }, { _, _ -> LongArray(0) }, now, utc, chunkMs = 12 * 3_600_000L)
        assertTrue(one.count { it.kind != TlKind.MOVE } >= 3 && one.count { it.kind == TlKind.MOVE } >= 2)
        assertEquals("a chunk edge cuts no stay in two", one.count { it.kind != TlKind.MOVE }, chunked.count { it.kind != TlKind.MOVE })
        assertTrue(one.all { it.source == "takeout-records" })
        assertTrue("no overlap between rows", chunked.sortedBy { it.startMs }.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun `rollups cut at midnight and add up distance, time, visits and sleep per day`() {
        val segs = listOf(
            com.diegonmarcos.cloudnav.maps.TlSegment(TlKind.STOP, ms("2026-03-11T08:00:00Z"), ms("2026-03-11T17:00:00Z"), 1.0, 2.0, "A", 0, 0.0, "import"),
            com.diegonmarcos.cloudnav.maps.TlSegment(TlKind.MOVE, ms("2026-03-11T17:00:00Z"), ms("2026-03-11T17:30:00Z"), 1.0, 2.0, null, 0, 3000.0, "mode:CAR"),
            com.diegonmarcos.cloudnav.maps.TlSegment(TlKind.SLEEP, ms("2026-03-11T22:00:00Z"), ms("2026-03-12T06:00:00Z"), 1.0, 2.0, "H", 0, 0.0, "import"))
        val r = TakeoutDerive.rollups(segs, utc)
        assertEquals(2, r.size)
        val d11 = r[0]; val d12 = r[1]
        assertEquals("2026-03-11", d11.date.toString()); assertEquals(3000.0, d11.distanceM, 0.01); assertEquals(30 * 60_000L, d11.movingMs); assertEquals(9 * 3_600_000L, d11.stoppedMs)
        assertEquals(1, d11.visits); assertEquals(2 * 3_600_000L, d11.sleepMs); assertEquals(6 * 3_600_000L, d12.sleepMs); assertEquals(mapOf("CAR" to 3000.0), d11.distanceByMode)
    }
}
