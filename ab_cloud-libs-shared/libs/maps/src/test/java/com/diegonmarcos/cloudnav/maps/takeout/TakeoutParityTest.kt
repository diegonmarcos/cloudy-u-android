package com.diegonmarcos.cloudnav.maps.takeout

import com.diegonmarcos.cloudnav.maps.takeout.TakeoutTestKit.RECORDS_FILES
import com.diegonmarcos.cloudnav.maps.takeout.TakeoutTestKit.SEMANTIC_FILES
import com.diegonmarcos.cloudnav.maps.takeout.TakeoutTestKit.TIMELINE_FILES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1853 field parity: every field path Google provides ([FieldSpec]) is read by a mapper (typed column or JSON-text column), the
 * fixtures carry every path, a field Google adds is flagged AND survives in raw_json, and muting a mapper makes the check fail.
 */
class TakeoutParityTest {
    private class Fmt(val name: String, val files: List<String>, val spec: List<String>, val mutate: List<String>)

    private val formats = listOf(
        Fmt("records", RECORDS_FILES, FieldSpec.RECORDS, listOf("latitudeE7", "deviceTag", "heading", "activity", "locationMetadata", "inferredLocation")),
        Fmt("semantic", SEMANTIC_FILES, FieldSpec.SEMANTIC, listOf("placeVisit.location.placeId", "activitySegment.activityType", "activitySegment.waypointPath", "activitySegment.transitPath",
            "placeVisit.childVisits[].location.name", "placeVisit.otherCandidateLocations[].semanticType", "placeVisit.placeVisitImportance", "activitySegment.parkingEvent")),
        Fmt("timeline", TIMELINE_FILES, FieldSpec.TIMELINE, listOf("visit.topCandidate.placeId", "visit.hierarchyLevel", "activity.distanceMeters", "timelineMemory", "rawSignals[].position.LatLng",
            "rawSignals[].activityRecord.probableActivities", "userLocationProfile.persona", "userLocationProfile.frequentTrips")),
    )

    private fun leaves(f: Fmt) = f.files.flatMap { TakeoutTestKit.leaves(it) }.toSet()
    private fun touched(f: Fmt, mute: Set<String> = emptySet()): Set<String> { val t = Tracker(mute); TakeoutTestKit.parseAll(f.files, TakeoutTestKit.Collect(), t); return t.touched }

    @Test fun `the spec lists exactly the paths the fixtures carry`() {
        for (f in formats) {
            val l = leaves(f); val s = f.spec.toSet()
            assertEquals("${f.name}: duplicate paths in the spec", f.spec.size, s.size)
            assertTrue("${f.name}: spec paths missing from the fixtures: ${s - l}", (s - l).isEmpty())
            assertTrue("${f.name}: fixture paths missing from the spec (a field Google has that FieldSpec lacks): ${l - s}", (l - s).isEmpty())
        }
    }

    @Test fun `every path of every format is mapped`() {
        for (f in formats) {
            val missing = f.spec.toSet() - touched(f)
            assertTrue("${f.name}: Google fields no mapper reads: $missing", missing.isEmpty())
        }
    }

    @Test fun `muting a mapper is caught (mutation)`() {
        for (f in formats) for (m in f.mutate) {
            assertTrue("${f.name}: $m is a real mapped path or subtree", f.spec.any { it == m || it.startsWith("$m.") || it.startsWith("$m[]") })
            val missing = f.spec.toSet() - touched(f, setOf(m))
            assertTrue("${f.name}: muting $m must leave unmapped paths", missing.isNotEmpty())
            assertTrue("${f.name}: only paths under $m go unmapped, got $missing", missing.all { it == m || it.startsWith("$m.") || it.startsWith("$m[]") })
        }
    }

    @Test fun `a field Google adds is flagged as unmapped and kept in the record's raw json`() {
        fun check(json: String, rawTable: String, extraLeaf: String, vararg findIn: String) {
            val t = Tracker(); val sink = TakeoutTestKit.Collect()
            TakeoutParsers.parse(json.reader(), sink, t)
            assertTrue("a row was written: $json", sink.of(rawTable).isNotEmpty())
            assertTrue("raw_json keeps $extraLeaf", sink.of(rawTable).any { (it["raw_json"] as String).contains(findIn[0]) })
            val root = JsonPull(json.reader()).readValue() as Map<*, *>
            val all = (root.values.first() as List<*>).flatMap { Node(it, if (root.keys.first() == "rawSignals") "rawSignals[]" else "", Tracker()).leaves() }
            assertTrue("$extraLeaf is unmapped (not in touched)", extraLeaf in all && extraLeaf !in t.touched)
        }
        check("""{"locations":[{"timestamp":"2026-03-10T07:00:00.000Z","latitudeE7":525163000,"longitudeE7":133777000,"zzNewGoogleField":{"inner":1}}]}""", TkTable.FIXES, "zzNewGoogleField.inner", "zzNewGoogleField")
        check("""{"timelineObjects":[{"placeVisit":{"location":{"latitudeE7":1,"longitudeE7":2,"placeId":"p"},"duration":{"startTimestamp":"2026-03-10T07:00:00Z","endTimestamp":"2026-03-10T08:00:00Z"},"zzNew":"x"}}]}""", TkTable.VISITS, "placeVisit.zzNew", "zzNew")
        check("""{"semanticSegments":[{"startTime":"2026-03-12T07:00:00Z","endTime":"2026-03-12T08:00:00Z","visit":{"topCandidate":{"placeId":"p","placeLocation":{"latLng":"1.0°, 2.0°"}}},"zzNew":5}]}""", TkTable.VISITS, "zzNew", "zzNew")
    }

    @Test fun `every column a parser emits exists in the schema`() {
        val sink = TakeoutTestKit.Collect()
        TakeoutTestKit.parseAll(RECORDS_FILES + SEMANTIC_FILES + TIMELINE_FILES, sink)
        for ((table, rows) in sink.rows) for (r in rows) {
            val unknown = r.keys - TakeoutSchema.COLS.getValue(table).toSet() - setOf("import_id")
            assertTrue("$table emits columns the schema lacks: $unknown", unknown.isEmpty())
        }
    }
}
