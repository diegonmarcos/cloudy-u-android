package com.diegonmarcos.superapp.fleetxray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetXrayExportTest {

    private val profile = FleetXrayExport.Profile(
        app = "Cloud Tasks",
        pkg = "com.diegonmarcos.cloudagenda",
        sha = "abc1234",
        capturedAt = "2026-11-08 10:00:00",
        sections = listOf(
            FleetXrayExport.Section("identity", "Identity", listOf(FleetXrayExport.Row("package", "com.diegonmarcos.cloudagenda"))),
            FleetXrayExport.Section("memory", "Memory", listOf(FleetXrayExport.Row("java heap used", "12.0 MB"))),
        ),
    )

    @Test fun catalogue_has_thirteen_distinct_trackers() {
        assertEquals(13, FleetXrayProbes.TRACKERS.size)
        assertEquals("ids are distinct", 13, FleetXrayProbes.TRACKERS.map { it.id }.toSet().size)
    }

    @Test fun device_profile_is_not_one_of_the_thirteen() {
        assertFalse(FleetXrayProbes.TRACKERS.any { it.id == FleetXrayProbes.DEVICE_PROFILE_ID })
    }

    @Test fun humanBytes_scales() {
        assertEquals("512 B", FleetXrayProbes.humanBytes(512))
        assertEquals("1.0 KB", FleetXrayProbes.humanBytes(1024))
        assertEquals("1.0 MB", FleetXrayProbes.humanBytes(1024L * 1024))
        assertEquals("?", FleetXrayProbes.humanBytes(-1))
    }

    @Test fun markdown_names_app_and_each_tracker() {
        val md = FleetXrayExport.markdown(profile)
        assertTrue(md.contains("# X-Ray — Cloud Tasks"))
        assertTrue(md.contains("- trackers: 2"))
        assertTrue(md.contains("## Identity"))
        assertTrue(md.contains("- package: com.diegonmarcos.cloudagenda"))
        assertTrue(md.contains("## Memory"))
    }

    @Test fun html_is_self_contained_and_escaped() {
        val p = profile.copy(sections = listOf(FleetXrayExport.Section("x", "X<Y>", listOf(FleetXrayExport.Row("a&b", "<v>")))))
        val html = FleetXrayExport.html(p)
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("X&lt;Y&gt;"))
        assertTrue(html.contains("a&amp;b"))
        assertFalse(html.contains("<v>"))
    }
}
