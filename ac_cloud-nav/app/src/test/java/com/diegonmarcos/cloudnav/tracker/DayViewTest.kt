package com.diegonmarcos.cloudnav.tracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class DayViewTest {
    private val zone = ZoneOffset.UTC
    private val d = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int = 0) = d.atTime(h, m).toInstant(zone).toEpochMilli()
    private val from = at(0); private val to = d.plusDays(1).atStartOfDay().toInstant(zone).toEpochMilli()
    private fun fix(ts: Long, lat: Double, lon: Double = 8.0, acc: Double = 10.0, src: String = "gps") = PlaceFix(ts, lat, lon, false, acc, src)

    /** Home 08:00-08:30 (a fix every 5 min, a few metres of jitter), a 12 min drive north, the office 08:42-11:00. */
    private val track: List<PlaceFix> =
        (0..6).map { fix(at(8, it * 5), 47.0 + (it % 2) * 0.00005) } +
        listOf(47.01, 47.02, 47.03, 47.04, 47.05).mapIndexed { i, lat -> fix(at(8, 32 + i * 2), lat) } +
        (0..23).map { fix(at(8, 42) + it * 6 * 60_000L, 47.06 + (it % 3) * 0.00004, acc = 20.0) }

    private fun segment(f: List<PlaceFix> = track, rules: DayRules = DayRules()) = FixSegmenter.segment(f, from, to, rules, zone)

    private fun assertGapless(day: List<DaySeg>) {
        assertEquals(from, day.first().startMs); assertEquals(to, day.last().endMs)
        day.zipWithNext().forEach { (x, y) -> assertEquals("gap or overlap after ${x.endMs}", x.endMs, y.startMs) }
        assertEquals(24 * 3_600_000L, day.sumOf { it.durationMs })
    }

    // ---- segmentation ----

    @Test fun `a track becomes stay, move, stay at the declared radius and dwell`() {
        val day = segment()
        val stays = day.filterIsInstance<DaySeg.Stay>(); val moves = day.filterIsInstance<DaySeg.Move>()
        assertEquals(2, stays.size); assertEquals(1, moves.size)
        assertEquals(47.0, stays[0].place!!.lat!!, 0.001); assertEquals(47.06, stays[1].place!!.lat!!, 0.001)
        val m = moves.single()
        assertEquals(at(8, 30), m.startMs); assertEquals(at(8, 42), m.endMs)
        assertEquals(6670.0, m.distanceM, 6670.0 * 0.05)
        assertEquals(Mode.CAR, m.mode)
        assertEquals(at(8, 42), stays[1].startMs)
    }

    @Test fun `moves link to the stays on either side`() {
        val m = segment().filterIsInstance<DaySeg.Move>().single()
        assertEquals(47.0, m.from!!.lat!!, 0.001); assertEquals(47.06, m.to!!.lat!!, 0.001)
    }

    @Test fun `a dwell under the minimum is not a stay and exactly the minimum is`() {
        val short = listOf(fix(at(9, 0), 47.0), fix(at(9, 2), 47.0), fix(at(9, 4), 47.0))
        assertTrue(segment(short).none { it is DaySeg.Stay && it.place?.located == true })
        val exact = listOf(fix(at(9, 0), 47.0), fix(at(9, 5), 47.0))
        assertEquals(1, segment(exact).count { it is DaySeg.Stay && it.place?.located == true })
    }

    @Test fun `fixes inside the radius are one stay and fixes outside it are two`() {
        fun two(dLat: Double) = (0..2).map { fix(at(9, it * 3), 47.0) } + (0..2).map { fix(at(9, 10 + it * 3), 47.0 + dLat) }
        assertEquals(1, segment(two(0.0007)).count { it is DaySeg.Stay && it.place?.located == true })   // about 78 m
        assertEquals(2, segment(two(0.0016)).count { it is DaySeg.Stay && it.place?.located == true })   // about 178 m
    }

    @Test fun `a fix with a poor accuracy and a predicted fix are ignored`() {
        val noisy = track + fix(at(9, 0), 48.0, acc = 900.0) + PlaceFix(at(9, 1), 49.0, 8.0, true, 5.0)
        assertEquals(2, segment(noisy).count { it is DaySeg.Stay && it.place?.located == true })
    }

    @Test fun `an overnight stay is a sleep`() {
        val night = (0..8).map { fix(at(0, 0) + it * 30 * 60_000L, 47.0) }
        assertEquals(StayKind.SLEEP, segment(night).filterIsInstance<DaySeg.Stay>().first { it.place?.located == true }.kind)
    }

    @Test fun `the declared rules change the segmentation`() {
        val strict = DayRules.fromJson("""{"stay_radius_m": 100, "min_dwell_min": 60}""")
        assertEquals(1, segment(track, strict).count { it is DaySeg.Stay && it.place?.located == true })
    }

    // ---- 24 h coverage ----

    @Test fun `every day built from fixes covers 24 h with no gap`() {
        assertGapless(segment())
        assertGapless(segment(emptyList()))                                        // no fixes: one unplaced stay
        assertGapless(segment(listOf(fix(at(12, 0), 47.0))))                       // a single fix
        assertGapless(segment(track.filter { it.ts < at(9, 0) } + (0..3).map { fix(at(15, it * 4), 46.0) }))   // hours with no fix between two stays
        assertGapless(segment(listOf(fix(at(1, 0), 47.0), fix(at(1, 2), 47.001), fix(at(1, 4), 47.002), fix(at(1, 6), 47.003))))   // only moving fixes, no stay
        assertGapless(segment(track.map { it.copy(ts = it.ts + 24 * 3_600_000L) } + track))   // fixes of another day are out of range
    }

    @Test fun `a day with only a drive is one move inside the gapless day`() {
        val drive = (0..10).map { fix(at(10, it * 2), 47.0 + it * 0.01) }
        val day = segment(drive)
        assertGapless(day)
        val m = day.filterIsInstance<DaySeg.Move>().single()
        assertEquals(at(10, 0), m.startMs); assertEquals(at(10, 20), m.endMs)
    }

    // ---- summary ----

    @Test fun `summary totals add up to the day`() {
        val day = segment()
        val bar = DayDetails.bar(day, StepsTotal(4200, null))
        val move = day.filterIsInstance<DaySeg.Move>().single()
        assertEquals(move.distanceM, bar.distanceM, 1e-6)
        assertEquals(12 * 60_000L, bar.activeMs)
        assertEquals(24 * 3_600_000L - 12 * 60_000L, bar.stoppedMs)
        assertEquals(24 * 3_600_000L, bar.activeMs + bar.stoppedMs)
        assertEquals(4200L, bar.steps.count)
    }

    @Test fun `steps are n-a with the reason when the counter has nothing`() {
        val bar = DayDetails.bar(segment(emptyList()), StepsTotal(null, "physical-activity permission not granted"))
        assertEquals(null, bar.steps.count); assertEquals("physical-activity permission not granted", bar.steps.reason)
        assertEquals(0.0, bar.distanceM, 0.0); assertEquals(0L, bar.activeMs)
    }

    // ---- place detail ----

    @Test fun `place detail carries visits, arrival mode, accuracy, source and fix count`() {
        val home = Place(47.0, 8.0, "Home", "Seestrasse 1", "8000", null, "Zurich", null, "Switzerland")
        val office = Place(47.06, 8.0, "Office", "Bahnhofstrasse 14", "8001", null, "Zurich", null, "Switzerland")
        val day = listOf(
            DaySeg.Stay(from, at(8), StayKind.SLEEP, home),
            DaySeg.Move(at(8), at(9), 6700.0, 0, 1.8, 3_600_000, home, office, Mode.WALK),
            DaySeg.Stay(at(9), at(12), StayKind.IDLE, office),
            DaySeg.Move(at(12), at(13), 6700.0, 0, 1.8, 3_600_000, office, home, Mode.BIKE),
            DaySeg.Stay(at(13), at(14), StayKind.IDLE, office.copy(lat = 47.0603)),   // a second visit to the same place (within the radius)
            DaySeg.Stay(at(14), to, StayKind.IDLE, home),
        )
        val fixes = listOf(fix(at(9, 10), 47.06, acc = 10.0, src = "gps"), fix(at(10, 0), 47.06, acc = 20.0, src = "gps"), fix(at(11, 0), 47.06, acc = 30.0, src = "net"))
        val x = DayDetails.detail(day, 2, fixes)!!
        assertEquals("47.060000,8.000000", x.geoCode)
        assertEquals("+/- 20 m", x.accuracy)
        assertEquals("Bahnhofstrasse 14, 8001, Zurich, Switzerland", x.address)
        assertEquals(at(9), x.arrivalMs); assertEquals(at(12), x.departureMs); assertEquals(3 * 3_600_000L, x.durationMs)
        assertEquals(Mode.WALK, x.arrivedBy); assertEquals(6700.0, x.arrivedDistanceM!!, 1e-6)
        assertEquals(2, x.visits)
        assertEquals("GPS", x.source); assertEquals(3, x.fixCount)
        assertEquals(null, DayDetails.detail(day, 1, fixes))                          // a move has no place detail
        assertEquals(null, DayDetails.detail(day, 0, fixes)!!.arrivedBy)             // the first place has no arrival
        assertEquals("n/a", DayDetails.detail(day, 5, emptyList())!!.accuracy)
    }

    // ---- copy formats and address formatting ----

    @Test fun `geo code is lat comma lon with a dot and six decimals`() {
        assertEquals("47.376900,8.541700", DayDetails.geoCode(47.3769, 8.5417))
        assertEquals("-33.868800,151.209300", DayDetails.geoCode(-33.8688, 151.2093))
        val parts = DayDetails.geoCode(1.5, -2.25).split(",")
        assertEquals(listOf(1.5, -2.25), parts.map { it.toDouble() })
    }

    @Test fun `copy address is street, zip, city and the full address adds the country`() {
        val p = Place(1.0, 2.0, "Cafe", "Bahnhofstrasse 14", "8001", "Altstadt", "Zurich", "ZH", "Switzerland")
        assertEquals("Bahnhofstrasse 14, 8001, Zurich", DayDetails.copyAddress(p))
        assertEquals("Bahnhofstrasse 14, 8001, Zurich, Switzerland", DayDetails.fullAddress(p))
        assertEquals("Zurich, Switzerland", DayDetails.fullAddress(Place(1.0, 2.0, city = "Zurich", country = "Switzerland")))
        assertEquals("", DayDetails.copyAddress(Place(1.0, 2.0)))
        val custom = DayRules.fromJson("""{"copy_address_fields": ["street", "city", "state"]}""")
        assertEquals("Bahnhofstrasse 14, Zurich, ZH", DayDetails.copyAddress(p, custom))
    }

    @Test fun `a geocoder answer becomes street with number, zip, city, country`() {
        val body = """{"address":{"road":"Seestrasse","house_number":"1","postcode":"8000","city":"Zurich","country":"Switzerland"}}"""
        val g = AddressParser.nominatim(body)!!
        val p = Place(47.0, 8.0).withGeocoded(g)
        assertEquals("Seestrasse 1", p.street)
        assertEquals("Seestrasse 1, 8000, Zurich", DayDetails.copyAddress(p))
        assertEquals("Seestrasse 1, 8000, Zurich, Switzerland", DayDetails.fullAddress(p))
    }

    @Test fun `source labels`() {
        assertEquals("GPS", DayDetails.sourceLabel("gps")); assertEquals("Network", DayDetails.sourceLabel("net"))
        assertEquals("Network", DayDetails.sourceLabel("cell")); assertEquals("Fused", DayDetails.sourceLabel("fused"))
        assertEquals("n/a", DayDetails.source(emptyList()))
    }

    // ---- mode inference ----

    private fun kmh(v: Double) = v / 3.6

    @Test fun `walk run bike car transit from speed`() {
        assertEquals(Mode.WALK, ModeInference.infer(kmh(4.0), 0.0, null))
        assertEquals(Mode.BIKE, ModeInference.infer(kmh(16.0), 0.0, null))
        assertEquals(Mode.CAR, ModeInference.infer(kmh(60.0), 0.0, null))
        assertEquals(Mode.TRAIN, ModeInference.infer(kmh(150.0), 0.0, null))
        assertEquals("Transit", DayFormat.modeLabel(Mode.TRAIN))
    }

    @Test fun `activity recognition decides where it agrees with the speed`() {
        assertEquals(Mode.RUN, ModeInference.infer(kmh(10.0), 0.0, ModeInference.Hint.RUNNING))
        assertEquals(Mode.BIKE, ModeInference.infer(kmh(20.0), 0.0, ModeInference.Hint.ON_BICYCLE))
        assertEquals(Mode.CAR, ModeInference.infer(kmh(15.0), 0.0, ModeInference.Hint.IN_VEHICLE))
        assertEquals(Mode.WALK, ModeInference.infer(kmh(5.0), 0.0, ModeInference.Hint.WALKING))
    }

    @Test fun `activity recognition never overrules an impossible speed`() {
        assertEquals(Mode.CAR, ModeInference.infer(kmh(60.0), 0.0, ModeInference.Hint.WALKING))
        assertEquals(Mode.WALK, ModeInference.infer(kmh(3.0), 0.0, ModeInference.Hint.ON_BICYCLE))
        assertEquals(Mode.PLANE, ModeInference.infer(kmh(700.0), 0.0, ModeInference.Hint.IN_VEHICLE))
    }

    @Test fun `no hint is the plain speed and cadence rule`() {
        for (v in listOf(2.0, 9.0, 25.0, 80.0, 400.0)) for (c in listOf(0.0, 100.0, 160.0))
            assertEquals(ModeInference.infer(kmh(v), c), ModeInference.infer(kmh(v), c, null))
    }

    // ---- the declaration ----

    @Test fun `the rules parse and survive bad json`() {
        val r = DayRules.fromJson("""{"stay_radius_m": 80, "min_dwell_min": 7, "max_fix_acc_m": 50}""")
        assertEquals(80.0, r.stayRadiusM, 0.0); assertEquals(7 * 60_000L, r.minDwellMs); assertEquals(50.0, r.maxFixAccM, 0.0)
        assertEquals(DayRules(), DayRules.fromJson("not json"))
    }

    @Test fun `build json declares the same rules as the defaults`() {
        val f = listOf(File("../build.json"), File("build.json")).first { it.exists() }
        val o = JSONObject(f.readText()).getJSONObject("ui").getJSONObject("day_timeline")
        assertNotNull(o)
        assertEquals(DayRules(), DayRules.fromJson(o.toString()))
    }
}
