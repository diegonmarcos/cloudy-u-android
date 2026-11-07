package com.diegonmarcos.superapp.devcontrol

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Logcat pages' rules (LogcatModel, FleetLogcat) and the app-scoped X-Ray filter, plain classes.
 *
 *   L1 a threadtime+uid line parses to uid, level and tag; a non-record line has no level
 *   L2 the Filters: minimum level, tag and search combine; with none set every line passes
 *   L3 Clear hides everything up to the mark; a mark the ring dropped shows all
 *   L4 the multi-app export is one titled block per app, and only the chosen uids are kept
 *   L5 the fleet's own apps sort first in the picker
 *   X1 the app X-Ray filter keeps App & Build, Repo & Releases, the app's own Resources sections and the SuperApp agents, and
 *      nothing device-wide
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogcatPagesTest {

    private val lines = listOf(
        "--------- beginning of main",
        "11-07 10:00:00.001 10123  100  100 D Net     : connect",
        "11-07 10:00:01.002 10123  100  101 W Net     : slow",
        "11-07 10:00:02.003 10124  200  200 E Boom    : crash here",
        "11-07 10:00:03.004 10123  100  100 I ActivityManager: start",
    ).joinToString("\n")

    @Test
    fun `L1 a line parses and a separator is not a record`() {
        val recs = LogcatModel.parse(lines)
        assertEquals(5, recs.size)
        assertEquals(' ', recs[0].level)
        assertEquals(-1, recs[0].uid)
        assertEquals(Triple(10123, 'D', "Net"), Triple(recs[1].uid, recs[1].level, recs[1].tag))
        assertEquals("ActivityManager", recs[4].tag)
    }

    @Test
    fun `L2 filters combine`() {
        val recs = LogcatModel.parse(lines)
        assertEquals(5, LogcatModel.filter(recs, 'V', "", "").size)
        assertEquals(listOf('W', 'E'), LogcatModel.filter(recs, 'W', "", "").map { it.level })
        assertEquals(listOf('D', 'W'), LogcatModel.filter(recs, 'V', "net", "").map { it.level })
        assertEquals(listOf('W'), LogcatModel.filter(recs, 'V', "net", "SLOW").map { it.level })
        assertTrue(LogcatModel.filter(recs, 'E', "net", "").isEmpty())
    }

    @Test
    fun `L3 clear is a mark in the stream`() {
        val recs = LogcatModel.parse(lines)
        assertEquals(recs.size, LogcatModel.afterMark(recs, null).size)
        assertEquals(2, LogcatModel.afterMark(recs, recs[2].raw).size)
        assertEquals(0, LogcatModel.afterMark(recs, recs.last().raw).size)
        assertEquals(recs.size, LogcatModel.afterMark(recs, "gone from the ring").size)
    }

    @Test
    fun `L4 the export carries one block per chosen app`() {
        val recs = LogcatModel.parse(lines)
        val a = LogcatModel.byUid(recs, setOf(10123))
        val b = LogcatModel.byUid(recs, setOf(10124))
        assertEquals(3, a.size)
        assertEquals(1, b.size)
        val out = LogcatModel.combine(listOf("App A (a.pkg)" to a, "App B (b.pkg)" to b))
        assertTrue(out.startsWith("===== App A (a.pkg) (3 lines) ====="))
        assertTrue(out.contains("===== App B (b.pkg) (1 lines) ====="))
        assertTrue(out.indexOf("App A") < out.indexOf("App B"))
        assertTrue(LogcatModel.shareable("x".repeat(300_000)).length < 300_000)
    }

    @Test
    fun `L5 the fleet apps come first`() {
        val apps = FleetLogcat.order(listOf(
            FleetLogcat.App("Zeta", "com.diegonmarcos.zeta", 1), FleetLogcat.App("Alpha", "org.other.alpha", 2),
            FleetLogcat.App("Beta", "com.diegonmarcos.beta", 3)))
        assertEquals(listOf("Beta", "Zeta", "Alpha"), apps.map { it.label })
        assertTrue(apps[0].fleet)
        assertFalse(apps[2].fleet)
    }

    @Test
    fun `X1 the app X-Ray keeps the app groups and drops the device-wide ones`() {
        val only = DevControlFragment.APP_XRAY_MACROS.toSet()
        val page = AboutPage(attached = { true }, only = only)
        fun group(macro: String, vararg heads: String) { page.macro(macro); heads.forEach { page.infoSection(it) {} } }
        group("📱  APP & BUILD", "App", "App permissions")
        group("☁  REPO & RELEASES", "Release / GHCR")
        group("🖥️  DEVICE", "SoC / CPU", "Sensors")
        group("🔐  SECURITY", "Security posture")
        group("🔋  RESOURCES", "Storage", "Battery & Usage", "Memory", "Memory & CPU Usage", "SYSFS-PROC")
        group("🌐  NETWORK", "Network", "Wi-Fi")
        group("🕸️  MESH", "VPN / WireGuard / Mesh")
        group("🛠️  DEV TOOLS", "Curl shortcuts")
        group("🤖  AGENTS", "SuperApp agents", "Fleet agents")
        assertEquals(listOf("APP & BUILD", "REPO & RELEASES", "RESOURCES", "AGENTS"), page.macros.map { it.label })
        assertEquals(
            listOf("App", "App permissions", "Release / GHCR", "Storage", "Battery & Usage", "Memory & CPU Usage", "SuperApp agents"),
            page.sections.map { it.head })
    }
}
