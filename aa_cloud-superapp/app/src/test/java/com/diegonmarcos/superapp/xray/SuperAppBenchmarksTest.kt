package com.diegonmarcos.superapp.xray

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1728 Cloud SuperApp's declared Performance benchmarks and the engine-profiler bridge. */
class SuperAppBenchmarksTest {
    private fun src(rel: String) = listOf("src/main/java/com/diegonmarcos/superapp/$rel", "app/src/main/java/com/diegonmarcos/superapp/$rel").map { File(it) }.first { it.isFile }.readText()

    @Test fun declaredBenchmarksAreReadOnlyAndBounded() {
        val all = SuperAppBenchmarks.all()
        assertEquals(listOf("superapp.xray.profile-build", "search.index"), all.map { it.op })
        all.forEach { b -> assertTrue(b.op, b.safety.startsWith("read-only")); assertTrue(b.op, b.maxMs in 1..60_000); assertTrue(b.op, b.repeats in 1..3) }
    }

    @Test fun eachOperationIsRecordedByAnInstrumentedPath() {
        assertTrue(src("xray/SuperAppBenchmarks.kt").contains("span(\"superapp.xray.profile-build\")"))
        assertTrue(src("search/SuperappSearchIndex.kt").contains("time(\"search\", \"index\")"))
    }

    @Test fun everyEnginePathReachesTheFleetProfiler() {
        assertTrue(src("xray/EngineProfiler.kt").contains("XrayProfiler.record("))
    }
}
