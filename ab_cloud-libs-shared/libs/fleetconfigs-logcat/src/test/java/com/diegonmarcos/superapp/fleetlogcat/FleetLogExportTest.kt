package com.diegonmarcos.superapp.fleetlogcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetLogExportTest {

    private val sample = FleetLogExport.Snapshot(
        app = "Cloud Tasks",
        pkg = "com.diegonmarcos.cloudagenda",
        sha = "abc1234",
        capturedAt = "2026-11-08 10:00:00",
        health = "streaming=true restarts=0 uptime=42s",
        errorsOnly = false,
        lines = listOf(
            "11-08 10:00:01.123 10234 4567 4567 I TasksApp: started",
            "11-08 10:00:02.456 10234 4567 4599 E Net: timeout after 30s",
            "--------- beginning of main",
        ),
    )

    @Test fun parse_splits_a_threadtime_line() {
        val r = FleetLogExport.parse("11-08 10:00:02.456 10234 4567 4599 E Net: timeout after 30s")
        assertEquals("10:00:02.456", r.time)
        assertEquals('E', r.level)
        assertEquals("Net", r.tag)
        assertEquals("timeout after 30s", r.message)
    }

    @Test fun parse_keeps_a_separator_as_raw() {
        val r = FleetLogExport.parse("--------- beginning of main")
        assertEquals(' ', r.level)
        assertEquals("--------- beginning of main", r.message)
    }

    @Test fun markdown_drops_the_date_prefix_and_keeps_the_time() {
        val md = FleetLogExport.markdown(sample)
        assertTrue("header names the app", md.contains("# Logcat — Cloud Tasks"))
        assertTrue("fenced log block", md.contains("```log"))
        assertTrue("time kept", md.contains("10:00:02.456 E/Net timeout after 30s"))
        assertFalse("redundant MM-DD date dropped from records", md.contains("11-08 10:00:02.456 10234"))
        assertTrue("separator passes through", md.contains("--------- beginning of main"))
    }

    @Test fun html_is_a_self_contained_escaped_page() {
        val withAngles = sample.copy(lines = listOf("11-08 10:00:03.000 10234 4567 4567 W X: a <b> & c"))
        val html = FleetLogExport.html(withAngles)
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("angle brackets escaped", html.contains("a &lt;b&gt; &amp; c"))
        assertFalse("no raw injected markup", html.contains("a <b> & c"))
    }

    @Test fun errors_only_flag_is_reported() {
        assertTrue(FleetLogExport.markdown(sample.copy(errorsOnly = true)).contains("(errors only)"))
        assertTrue(FleetLogExport.html(sample.copy(errorsOnly = true)).contains("errors only"))
    }
}
