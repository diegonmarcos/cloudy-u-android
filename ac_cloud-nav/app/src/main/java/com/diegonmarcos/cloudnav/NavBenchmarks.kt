package com.diegonmarcos.cloudnav

import com.diegonmarcos.cloudnav.maps.MapsRouting
import com.diegonmarcos.cloudnav.maps.TimelineBuilder
import com.diegonmarcos.cloudnav.maps.TlFix
import com.diegonmarcos.cloudnav.maps.TlSegment
import com.diegonmarcos.cloudnav.maps.TlStop
import com.diegonmarcos.cloudnav.maps.TlSteps
import com.diegonmarcos.cloudnav.sky.PlanetEphemeris
import com.diegonmarcos.cloudnav.tracker.Aggregates
import com.diegonmarcos.cloudnav.tracker.DayTimeline
import com.diegonmarcos.cloudnav.tracker.Segmenter
import com.diegonmarcos.cloudnav.tracker.StepSlice
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmark
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmarks
import com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Random

/**
 * #1728 Cloud Nav's declared benchmarks (Configs > X-Ray App > Performance). Nav's hot paths are pure compute over the location
 * stream, so each runs the app's REAL function over a deterministic synthetic fixture (seeded, bounded, generated outside the
 * timed span): no user location is read, nothing is written, no network. The question: is it worth a native rewrite?
 */
object NavBenchmarks {
    const val DAYS = 14
    const val STOPS = 300
    const val FIXES = 20_000
    const val STEP_SLICES = 20_000
    const val POLYLINE_POINTS = 50_000
    const val SKY_TIMES = 2_000

    private const val T0 = 1_760_000_000_000L
    private val ZONE = ZoneOffset.UTC

    class Fixture(val stops: List<TlStop>, val fixes: List<TlFix>, val steps: List<TlSteps>, val nowMs: Long)

    /** Deterministic synthetic day-to-day movement: [stops] stops 20..90 min long, a fix every ~20 s between them, step slices of 1 min. */
    fun fixture(stops: Int = STOPS, fixes: Int = FIXES, slices: Int = STEP_SLICES, seed: Long = 42): Fixture {
        val r = Random(seed)
        val span = DAYS * 86_400_000L
        val stopList = ArrayList<TlStop>(stops)
        var lat = 40.4; var lon = -3.7
        for (i in 0 until stops) {
            val start = T0 + span * i / stops
            val end = start + (20 + r.nextInt(70)) * 60_000L
            lat += (r.nextDouble() - 0.5) * 0.02; lon += (r.nextDouble() - 0.5) * 0.02
            stopList += TlStop(start, end, lat, lon, "P$i", null, "N${i % 9}", "City${i % 5}", "ES")
        }
        val fixList = (0 until fixes).map { TlFix(T0 + span * it / fixes, 40.4 + r.nextDouble() * 0.1, -3.7 + r.nextDouble() * 0.1, 5.0 + r.nextDouble() * 60) }
        val stepList = (0 until slices).map { val a = T0 + span * it / slices; TlSteps(a, a + 60_000L, r.nextInt(120).toLong()) }
        return Fixture(stopList, fixList, stepList, T0 + span + 3_600_000L)
    }

    /** A Valhalla precision-6 encoded polyline of [n] points on a smooth random walk. */
    fun encodePolyline6(n: Int, seed: Long = 7): String {
        val r = Random(seed); val sb = StringBuilder()
        var lat = 40_400_000; var lon = -3_700_000; var pl = 0; var plo = 0
        fun put(v0: Int) { var v = if (v0 < 0) (v0 shl 1).inv() else v0 shl 1; while (v >= 0x20) { sb.append((((v and 0x1f) or 0x20) + 63).toChar()); v = v shr 5 }; sb.append((v + 63).toChar()) }
        repeat(n) { lat += r.nextInt(400) - 200; lon += r.nextInt(400) - 200; put(lat - pl); put(lon - plo); pl = lat; plo = lon }
        return sb.toString()
    }

    fun all(): List<XrayBenchmark> = listOf(
        XrayBenchmark("nav.location.timeline-build", "Location stream to timeline", "read-only; synthetic $STOPS stops, $FIXES fixes, $STEP_SLICES step slices, nothing read from the phone", 20_000, 3) { _ ->
            val f = fixture()
            XrayProfiler.span("nav.location.timeline-build") { sp -> TimelineBuilder.build(f.stops, f.fixes, f.steps, f.nowMs, ZONE).also { sp.add(items = (f.fixes.size + f.steps.size).toLong()) } }
            null
        },
        XrayBenchmark("nav.tracker.day-build", "Tracker day views ($DAYS days)", "read-only; synthetic segments, pure compute", 20_000, 3) { _ ->
            val f = fixture(); val store: List<TlSegment> = TimelineBuilder.build(f.stops, f.fixes, f.steps, f.nowMs, ZONE)
            val days = (0 until DAYS).map { LocalDate.ofEpochDay(T0 / 86_400_000L + it) }
            XrayProfiler.span("nav.tracker.day-build") { sp -> Aggregates.perDay(store, days, f.nowMs, ZONE).also { sp.add(items = store.size.toLong() * days.size) } }
            null
        },
        XrayBenchmark("nav.steps.segment", "Step cadence segmentation", "read-only; synthetic step slices, pure compute", 15_000, 3) { _ ->
            val f = fixture()
            val slices = f.steps.map { StepSlice(it.startMs, it.endMs, it.count) }
            XrayProfiler.span("nav.steps.segment") { sp -> Segmenter.segment(slices, T0, f.nowMs).also { sp.add(items = slices.size.toLong()) } }
            null
        },
        XrayBenchmark("nav.route.polyline-decode", "Route polyline decode", "read-only; a synthetic $POLYLINE_POINTS-point polyline, no routing request", 15_000, 3) { _ ->
            val enc = encodePolyline6(POLYLINE_POINTS)
            XrayProfiler.span("nav.route.polyline-decode") { sp -> MapsRouting.decodePolyline6(enc).also { sp.add(items = it.size.toLong(), bytes = enc.length.toLong()) } }
            null
        },
        XrayBenchmark("nav.sky.planet-positions", "Sky: planet positions ($SKY_TIMES instants)", "read-only; the planets dataset already served by Cloud Store, no network", 15_000, 3) { ctx ->
            val el = PlanetEphemeris.loadElements(ctx) ?: return@XrayBenchmark "the celestial/planets dataset is not served yet"
            XrayProfiler.span("nav.sky.planet-positions") { sp -> var n = 0L; for (i in 0 until SKY_TIMES) n += PlanetEphemeris.positions(el, T0 + i * 3_600_000L).size; sp.add(items = n) }
            null
        },
    )

    fun register() { XrayBenchmarks.register(*all().toTypedArray()) }
}
