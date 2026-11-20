package com.diegonmarcos.superapp.fleetlogcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1828 the Logcat page's filters: level, kind of log, search; the view and every export apply them; nothing previous is gone. */
class FleetLogcatFilterTest {

    private fun line(level: Char, tag: String, msg: String) = "11-20 08:59:54.689 10234  1550  1550 $level $tag: $msg"

    private val crash = line('E', "AndroidRuntime", "FATAL EXCEPTION: main")
    private val vib = line('E', "VibratorInfo", "Invalid frequency profile received from HAL")
    private val net = line('W', "OkHttp", "connect timed out")
    private val push = line('I', "PushService", "armed 2 logins")
    private val ui = line('D', "chromium", "Seed missing signature")
    private val app = line('I', "Sterna", "inbox refreshed")
    private val verbose = line('V', "Sterna", "tick")
    private val sep = "--------- beginning of crash"
    private val all = listOf(sep, crash, vib, net, push, ui, app, verbose)

    @Test fun `every line has exactly one kind`() {
        assertEquals(LogKind.CRASH, logKindOf(FleetLogExport.parse(crash)))
        assertEquals(LogKind.SYSTEM, logKindOf(FleetLogExport.parse(vib)))
        assertEquals(LogKind.NETWORK, logKindOf(FleetLogExport.parse(net)))
        assertEquals(LogKind.SYNC, logKindOf(FleetLogExport.parse(push)))
        assertEquals(LogKind.UI, logKindOf(FleetLogExport.parse(ui)))
        assertEquals(LogKind.APP, logKindOf(FleetLogExport.parse(app)))
        assertEquals("a fatal is a crash whatever its tag", LogKind.CRASH, logKindOf(FleetLogExport.parse(line('F', "Sterna", "abort"))))
    }

    @Test fun `the open filter keeps everything, separators included`() {
        assertTrue(LogcatFilter().isOpen)
        assertEquals(all, LogcatFilter().apply(all))
        assertEquals("", LogcatFilter().describe())
    }

    @Test fun `level keeps that level and above`() {
        assertEquals(listOf(crash, vib), LogcatFilter(level = LogLevelFilter.ERROR).apply(all))
        assertEquals(listOf(crash, vib, net), LogcatFilter(level = LogLevelFilter.WARN).apply(all))
        assertEquals(listOf(crash, vib, net, push, app), LogcatFilter(level = LogLevelFilter.INFO).apply(all))
        assertFalse("debug+ drops verbose", verbose in LogcatFilter(level = LogLevelFilter.DEBUG).apply(all))
    }

    @Test fun `kinds combine, none means all`() {
        assertEquals(listOf(crash), LogcatFilter(kinds = setOf(LogKind.CRASH)).apply(all))
        assertEquals(listOf(crash, net), LogcatFilter(kinds = setOf(LogKind.CRASH, LogKind.NETWORK)).apply(all))
        assertEquals("errors of the app only: system noise gone", listOf(crash), LogcatFilter(LogLevelFilter.ERROR, setOf(LogKind.CRASH, LogKind.APP)).apply(all))
    }

    @Test fun `search matches tag or message, case-blind`() {
        assertEquals(listOf(push), LogcatFilter(query = "pushservice").apply(all))
        assertEquals(listOf(net), LogcatFilter(query = "TIMED OUT").apply(all))
        assertEquals(listOf(sep), LogcatFilter(query = "beginning").apply(all))
    }

    @Test fun `the description names every active part, for the exports`() {
        assertEquals("Errors, Crashes+App, \"push\"", LogcatFilter(LogLevelFilter.ERROR, setOf(LogKind.APP, LogKind.CRASH), " push ").describe())
        val md = FleetLogExport.markdown(FleetLogExport.Snapshot("Cloud Mail", "p", "s", "t", "h", false, listOf(crash), filter = "Errors"))
        assertTrue(md.contains("- filter: Errors"))
        val plain = FleetLogExport.markdown(FleetLogExport.Snapshot("Cloud Mail", "p", "s", "t", "h", false, listOf(crash)))
        assertFalse("no filter, no line", plain.contains("- filter:"))
        assertTrue(FleetLogExport.html(FleetLogExport.Snapshot("a", "p", "s", "t", "h", false, listOf(crash), filter = "Errors")).contains("filter: Errors"))
    }

    @Test fun `the page shows and exports the filtered lines, and keeps errors-only and every export`() {
        val page = listOf("src/main/java/com/diegonmarcos/superapp/fleetlogcat/FleetLogcatPage.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(page.contains("val text = remember(tick, errorsOnly, filter) { filteredTail(uid, errorsOnly, filter) }"))
        assertTrue("exports use the same lines", page.contains("val body = filteredTail(uid, errorsOnly, filter)"))
        assertTrue(page.contains("filter = filter.describe(),"))
        listOf("FleetLogcatTags.ERRORS_ONLY", "FleetLogcatTags.COPY_MD", "FleetLogcatTags.SAVE_HTML", "FleetLogcatTags.SHARE", "FleetLogcatTags.BODY",
            "LogLevelFilter.entries.forEach", "LogKind.entries.forEach", "FleetLogcatTags.SEARCH")
            .forEach { assertTrue("missing $it", page.contains(it)) }
    }
}
