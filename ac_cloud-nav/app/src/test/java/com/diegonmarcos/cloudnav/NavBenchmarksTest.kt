package com.diegonmarcos.cloudnav

import com.diegonmarcos.cloudnav.maps.MapsRouting
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1728 Cloud Nav's declared Performance benchmarks: the hot paths, the safety statement, the instrumented span, and the fixtures. */
class NavBenchmarksTest {
    @Test fun theFiveHotPathsAreDeclaredAndSafe() {
        val all = NavBenchmarks.all()
        assertEquals(listOf("nav.location.timeline-build", "nav.tracker.day-build", "nav.steps.segment", "nav.route.polyline-decode", "nav.sky.planet-positions"), all.map { it.op })
        all.forEach { b -> assertTrue(b.op, b.safety.startsWith("read-only")); assertTrue(b.op, b.maxMs in 1..60_000); assertTrue(b.op, b.repeats in 1..3) }
    }

    @Test fun everyDeclaredOperationIsASpanInTheBenchmarkSource() {
        val src = listOf("src/main/java/com/diegonmarcos/cloudnav/NavBenchmarks.kt", "app/src/main/java/com/diegonmarcos/cloudnav/NavBenchmarks.kt").map { File(it) }.first { it.isFile }.readText()
        NavBenchmarks.all().forEach { assertTrue(it.op, src.contains("span(\"${it.op}\")")) }
    }

    @Test fun fixtureIsDeterministicAndBounded() {
        val a = NavBenchmarks.fixture(10, 100, 100); val b = NavBenchmarks.fixture(10, 100, 100)
        assertEquals(a.fixes, b.fixes); assertEquals(10, a.stops.size); assertEquals(100, a.steps.size)
    }

    @Test fun polylineEncoderRoundTripsThroughTheRealDecoder() {
        val pts = MapsRouting.decodePolyline6(NavBenchmarks.encodePolyline6(500))
        assertEquals(500, pts.size)
        assertTrue(pts.all { it[0] in 39.0..42.0 && it[1] in -5.0..-2.0 })
    }
}
