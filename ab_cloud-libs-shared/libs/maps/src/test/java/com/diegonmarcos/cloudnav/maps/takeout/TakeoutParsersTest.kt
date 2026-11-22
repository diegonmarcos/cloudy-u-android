package com.diegonmarcos.cloudnav.maps.takeout

import com.diegonmarcos.cloudnav.maps.takeout.TakeoutTestKit.RECORDS_FILES
import com.diegonmarcos.cloudnav.maps.takeout.TakeoutTestKit.SEMANTIC_FILES
import com.diegonmarcos.cloudnav.maps.takeout.TakeoutTestKit.TIMELINE_FILES
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1853 what the three parsers store: values, both spellings, both flavours, skipped records counted. */
class TakeoutParsersTest {
    private fun ms(iso: String) = TkVal.ms(iso)!!

    @Test fun `timestamps and coordinates in every spelling`() {
        assertEquals(1773126000123L, TkVal.ms("2026-03-10T07:00:00.123Z"))
        assertEquals(1773126000123L, TkVal.ms("1773126000123"))
        assertEquals(1773126000000L, TkVal.ms("1773126000"))
        assertEquals(ms("2026-03-12T06:00:00Z"), ms("2026-03-12T07:00:00.000+01:00"))
        assertNull(TkVal.ms("garbage")); assertNull(TkVal.ms(null))
        assertEquals(52.5163 to 13.3777, TkVal.latLng("52.5163000°, 13.3777000°"))
        assertEquals(52.5163 to 13.3777, TkVal.latLng("geo:52.5163000,13.3777000"))
        assertNull(TkVal.latLng("95.0°, 13.0°"))
    }

    @Test fun `Records json maps every position with both timestamp spellings`() {
        val sink = TakeoutTestKit.Collect()
        assertEquals(setOf(TakeoutFormat.RECORDS), TakeoutTestKit.parseAll(RECORDS_FILES, sink))
        val fixes = sink.of(TkTable.FIXES)
        val n = JSONObject(TakeoutTestKit.text("records/Records.json")).getJSONArray("locations").length()
        assertEquals(n, fixes.size)
        val first = fixes.first()
        assertEquals(52.51627, first["lat"] as Double, 1e-9); assertEquals(13.37767, first["lon"] as Double, 1e-9)
        assertEquals(20.0, first["accuracy"]); assertEquals(34.0, first["altitude"]); assertEquals(3.0, first["vertical_acc"]); assertEquals(0.0, first["speed"]); assertEquals(270.0, first["heading"])
        assertEquals("WIFI", first["g_source"]); assertEquals(-1234567L, first["device_tag"]); assertEquals("takeout-records", first["source"])
        assertTrue((first["activity_json"] as String).contains("IN_VEHICLE")); assertTrue((first["extra_json"] as String).contains("ANDROID"))
        assertEquals(ms("2026-03-10T06:00:00Z"), first["ts"])
        assertNull("a record whose fields are all mapped keeps no second copy", first["raw_json"])
        val old = TakeoutTestKit.Collect(); TakeoutParsers.parse("""{"locations":[{"timestampMs":"1773126000000","latitudeE7":1,"longitudeE7":2},{"timestamp":"2026-03-10T07:00:00Z","latitudeE7":1,"longitudeE7":2,"zzNew":1}]}""".reader(), old)
        assertEquals(1773126000000L, old.of(TkTable.FIXES)[0]["ts"]); assertNull(old.of(TkTable.FIXES)[0]["raw_json"])
        assertTrue("a record with a field no mapper knows keeps its raw copy", (old.of(TkTable.FIXES)[1]["raw_json"] as String).contains("zzNew"))
        assertEquals("keys are unique", fixes.size, fixes.map { it["ikey"] }.toSet().size)
    }

    @Test fun `Semantic Location History maps visits, children, candidates and activities`() {
        val sink = TakeoutTestKit.Collect()
        assertEquals(setOf(TakeoutFormat.SEMANTIC), TakeoutTestKit.parseAll(SEMANTIC_FILES, sink))
        val v = sink.of(TkTable.VISITS)
        assertEquals(4, v.size)   // three placeVisits and one child
        val home = v.first { it["place_id"] == "ChIJ-FIXTURE-HOME" && it["parent_key"] == null && it["end_ms"] == ms("2026-03-11T08:00:00Z") }
        assertEquals("Fixture Home", home["name"]); assertEquals("Fixture Strasse 1, 10117 Berlin, Germany", home["address"]); assertEquals("TYPE_HOME", home["semantic_type"])
        assertEquals("HIGH_CONFIDENCE", home["place_confidence"]); assertEquals(85L, home["visit_confidence"]); assertEquals(94L, home["location_confidence"])
        assertEquals("SINGLE_PLACE", home["visit_type"]); assertEquals("MAIN", home["importance"]); assertEquals("NOT_CONFIRMED", home["edit_status"])
        assertEquals(52.5163, home["center_lat"] as Double, 1e-6); assertEquals(-1234567L, home["device_tag"])
        assertEquals(ms("2026-03-12T09:00:00Z"), home["last_edited_ms"]); assertNotNull(home["simplified_path_json"])
        val child = v.first { it["parent_key"] == home["key"] }; assertEquals("ChIJ-FIXTURE-CHILD", child["place_id"])
        val old = v.first { it["place_id"] == "ChIJ-FIXTURE-WORK" }
        assertEquals(ms("2026-03-11T08:30:00Z"), old["start_ms"]); assertEquals(ms("2026-03-11T17:00:00Z"), old["end_ms"])
        val cands = sink.of(TkTable.CANDIDATES)
        assertEquals(3, cands.size)
        assertEquals(listOf("ChIJ-FIXTURE-OTH1", "ChIJ-FIXTURE-OTH2"), cands.filter { it["visit_key"] == home["key"] }.sortedBy { it["rank"] as Long }.map { it["place_id"] })
        val a = sink.of(TkTable.ACTIVITIES)
        assertEquals(2, a.size)
        val train = a.first { it["activity_type"] == "IN_TRAIN" }
        assertEquals(3100.0, train["distance_m"]); assertEquals("HIGH", train["confidence"])
        assertEquals("ChIJ-FIXTURE-HOME", train["start_place_id"]); assertEquals("ChIJ-FIXTURE-WORK", train["end_place_id"]); assertEquals(-1234567L, train["end_device_tag"])
        for (c in listOf("probabilities_json", "waypoints_json", "simplified_path_json", "transit_json", "parking_json")) assertNotNull("$c is stored", train[c])
        assertTrue((train["probabilities_json"] as String).contains("WALKING"))
        assertTrue((train["transit_json"] as String).contains("Fixture Line 1"))
    }

    @Test fun `Timeline export maps segments, raw signals and the profile in both flavours`() {
        val sink = TakeoutTestKit.Collect()
        assertEquals(setOf(TakeoutFormat.TIMELINE), TakeoutTestKit.parseAll(TIMELINE_FILES, sink))
        val v = sink.of(TkTable.VISITS)
        assertEquals(3, v.size)
        val home = v.first { it["start_ms"] == ms("2026-03-12T06:00:00Z") }
        assertEquals("ChIJ-FIXTURE-HOME", home["place_id"]); assertEquals("HOME", home["semantic_type"]); assertEquals(0.85, home["probability"]); assertEquals(60L, home["start_offset_min"])
        assertEquals(52.5163, home["lat"] as Double, 1e-6)
        val android = v.first { it["start_ms"] == ms("2026-03-13T06:00:00Z") }
        assertEquals(52.5163, android["lat"] as Double, 1e-6); assertEquals(0L, android["hierarchy_level"]); assertEquals(0.8, android["probability"])
        val a = sink.of(TkTable.ACTIVITIES)
        assertEquals(4, a.size)
        val sub = a.first { it["activity_type"] == "IN_SUBWAY" }
        assertEquals(3100.4, sub["distance_m"]); assertEquals(0.81, sub["confidence"]); assertEquals(52.5219, sub["end_lat"] as Double, 1e-6); assertNotNull(sub["memory_json"])
        assertEquals("walking", a.first { it["key"] == "a:${ms("2026-03-13T07:00:00Z")}:${ms("2026-03-13T07:20:00Z")}:walking" }["activity_type"])
        assertEquals(2, a.count { (it["key"] as String).startsWith("p:") })   // timelinePath-only segments
        assertTrue(a.filter { (it["key"] as String).startsWith("p:") }.all { (it["path_json"] as String).contains("point") })
        val fixes = sink.of(TkTable.FIXES)
        assertEquals(2, fixes.size); assertEquals("GPS", fixes[0]["g_source"]); assertEquals(36.5, fixes[0]["altitude"]); assertEquals("timeline-export", fixes[0]["source"])
        assertEquals(setOf("activityRecord", "wifiScan"), sink.of(TkTable.SIGNALS).map { it["kind"] }.toSet())
        assertEquals(setOf("frequentPlaces", "frequentTrips", "persona"), sink.of(TkTable.PROFILE).map { it["k"] }.toSet())
    }

    @Test fun `records without a time or a position are counted as skipped, not dropped silently`() {
        val sink = TakeoutTestKit.Collect()
        TakeoutParsers.parse("""{"locations":[{"latitudeE7":1,"longitudeE7":2},{"timestamp":"2026-03-10T07:00:00Z"},{"timestamp":"2026-03-10T07:00:00Z","latitudeE7":1,"longitudeE7":2}]}""".reader(), sink)
        assertEquals(1, sink.of(TkTable.FIXES).size); assertEquals(2, sink.skips.size)
    }

    @Test fun `a json file that is no location export yields nothing`() {
        val sink = TakeoutTestKit.Collect()
        assertTrue(TakeoutParsers.parse("""{"deviceSettings":[{"a":1}]}""".reader(), sink).isEmpty()); assertTrue(sink.rows.isEmpty())
    }

    @Test fun `cancel stops the parse at the next record`() {
        val sink = TakeoutTestKit.Collect(); var seen = 0
        try { TakeoutParsers.parse(TakeoutTestKit.res("records/Records.json").reader(), sink, cancelled = { seen++ >= 5 }); org.junit.Assert.fail("not cancelled") } catch (e: ImportCancelled) {}
        assertEquals(5, sink.of(TkTable.FIXES).size)
    }
}
