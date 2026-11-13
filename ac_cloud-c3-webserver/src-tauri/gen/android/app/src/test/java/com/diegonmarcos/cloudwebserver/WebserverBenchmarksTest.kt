package com.diegonmarcos.cloudwebserver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1728 Cloud C3 Webserver's declared Performance benchmarks. */
class WebserverBenchmarksTest {
    private val ops = WebserverBenchmarks.all().map { it.op }

    @Test fun theHotPathsAreDeclaredReadOnlyAndBounded() {
        assertEquals(listOf("web.api.catalogue", "web.pages.index-scan", "web.adb.status", "web.api.health", "web.core.jni-call", "web.keepalive.health"), ops)
        WebserverBenchmarks.all().forEach { b -> assertTrue(b.op, b.safety.startsWith("read-only")); assertTrue(b.op, b.maxMs in 1..60_000); assertTrue(b.op, b.repeats in 1..3) }
    }

    @Test fun everyHttpBenchmarkIsAGetOfADeclaredAdminRoute() {
        val declared = setOf(Api.ROUTES, Api.SERVED, Api.ADB_SHELL, Api.HEALTH)
        assertEquals(declared, WebserverBenchmarks.ROUTES.map { it.third }.toSet())
        assertTrue(WebserverBenchmarks.ROUTES.all { it.third.startsWith("/__") })
    }

    @Test fun theCoreStartIsRecordedAsASpanAtAttach() {
        val src = File("src/main/java/com/diegonmarcos/cloudwebserver/Core.kt").let { if (it.isFile) it else File("app/src/main/java/com/diegonmarcos/cloudwebserver/Core.kt") }.readText()
        assertTrue(src.contains("span(\"web.core.start\")"))
    }
}
