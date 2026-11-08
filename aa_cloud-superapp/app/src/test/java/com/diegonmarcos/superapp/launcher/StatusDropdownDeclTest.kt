package com.diegonmarcos.superapp.launcher

import android.app.Application
import com.diegonmarcos.superapp.xray.XrayProbes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status-bar dropdowns are declared (build.json::home_status_bar.dropdowns).
 *
 *   X1 the LEFT dropdown and the RIGHT System and Battery dropdowns are all declared, each opened by real strip elements
 *   X2 the System dropdown is two macro buckets (Device Profile Scan, Profiling Trackers 01-12 + 13 Suspects), icon groups kept, new probes declared
 *   X3 the Battery dropdown's two since-last-charge sections
 *   X4 every adb group names an X-Ray probe that exists; the FPS icon is a right element that opens the System dropdown
 *   X5 a bad side or an unknown opener is refused
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusDropdownDeclTest {
    private val d = StatusBarDecl.declared

    @Test
    fun `X1 left and right dropdowns are declared`() {
        assertEquals(setOf("left_network", "right_system", "right_battery"), d.dropdowns.map { it.id }.toSet())
        assertEquals("left", d.dropdown("left_network")!!.side)
        assertEquals("right", d.dropdown("right_system")!!.side)
        assertEquals("right", d.dropdown("right_battery")!!.side)
        val ids = d.elements.associateBy { it.id }
        for (x in d.dropdowns) for (o in x.openedBy) assertEquals("${x.id}/$o opens ${x.popup}", x.popup, ids.getValue(o).opens)
        // the left dropdown's groups are the network table's
        assertEquals(d.networkTable!!.groups.map { it.id }, d.dropdown("left_network")!!.groups.map { it.id })
    }

    @Test
    fun `X2 the System dropdown is two macro buckets, Profile Scan then 13 trackers`() {
        val g = d.dropdown("right_system")!!.groups
        val ids = g.map { it.id }
        val macros = g.filter { it.source == "header" && it.id.startsWith("m") }.map { it.id }
        assertEquals(listOf("m1_profile_scan", "m2_trackers"), macros)
        val trackers = listOf("tr01_performance", "tr02_render", "tr03_storage", "tr04_battery", "tr05_thermal", "tr06_memory", "tr07_notif", "tr08_resp",
            "tr09_crash", "tr10_network", "tr11_process", "tr12_security", "tr13_suspects")
        assertEquals(trackers, ids.filter { it.startsWith("tr") && it[2].isDigit() })
        assertTrue(g.filter { it.id in macros || it.id in trackers }.all { it.source == "header" && it.probe == null })
        fun between(a: String, b: String) = ids.subList(ids.indexOf(a) + 1, ids.indexOf(b))
        assertEquals(listOf("device", "soc", "gpu", "display", "capacity"), between("m1_profile_scan", "m2_trackers"))
        assertEquals(trackers.first(), ids[ids.indexOf("m2_trackers") + 1])
        assertEquals(listOf("cpu"), between("tr01_performance", "tr02_render"))
        assertEquals(listOf("graphics", "jank"), between("tr02_render", "tr03_storage"))
        assertEquals(listOf("storage", "df", "diskstats", "app_sizes"), between("tr03_storage", "tr04_battery"))
        assertEquals(listOf("battery_live", "wakelocks", "power_by_uid", "alarms", "jobs"), between("tr04_battery", "tr05_thermal"))
        assertEquals(listOf("thermal"), between("tr05_thermal", "tr06_memory"))
        assertEquals(listOf("memory", "memory_3h", "meminfo_detail"), between("tr06_memory", "tr07_notif"))
        assertEquals(listOf("notif_rate", "binder"), between("tr07_notif", "tr08_resp"))
        assertEquals(listOf("anr"), between("tr08_resp", "tr09_crash"))
        assertEquals(listOf("crashes"), between("tr09_crash", "tr10_network"))
        assertEquals(listOf("net_usage", "net_conn", "net_signal"), between("tr10_network", "tr11_process"))
        assertEquals(listOf("running_services", "oom_adj"), between("tr11_process", "tr12_security"))
        assertEquals(listOf("sec_perms"), between("tr12_security", "tr13_suspects"))
        assertEquals(listOf("tr13_suspects", "suspects"), ids.takeLast(2))
        val by = g.associateBy { it.id }
        assertEquals("top", by.getValue("cpu").probe); assertEquals("gfxinfo", by.getValue("graphics").probe); assertEquals("gfxinfo", by.getValue("jank").probe)
        assertEquals("notification", by.getValue("notif_rate").probe); assertEquals("meminfo", by.getValue("meminfo_detail").probe)
        assertEquals("df", by.getValue("df").probe); assertEquals("diskstats", by.getValue("app_sizes").probe); assertEquals("dropbox", by.getValue("crashes").probe)
        assertEquals("netstats", by.getValue("net_usage").probe); assertEquals("connectivity", by.getValue("net_conn").probe); assertEquals("telephony", by.getValue("net_signal").probe)
        assertEquals("procs", by.getValue("oom_adj").probe); assertEquals("services", by.getValue("running_services").probe); assertEquals("pkgperms", by.getValue("sec_perms").probe)
        assertEquals("fps", by.getValue("graphics").element); assertEquals("ram", by.getValue("memory").element)
        // every tracker of the export has a header here, in the same order and with the same number
        assertEquals(XrayProbes.trackers.map { it.num }, trackers.map { it.substring(2, 4).toInt() })
    }

    @Test
    fun `X3 the Battery dropdown has the two since-last-charge sections`() {
        val g = d.dropdown("right_battery")!!.groups
        assertEquals(listOf("Since Last Charge - Usage Infos", "Since Last Charge - Per-App Usage"), g.map { it.label })
        assertTrue(g.all { it.source == "adb" && it.probe == "batterystats" })
    }

    @Test
    fun `X4 probes exist and the FPS icon opens the System dropdown`() {
        for (x in d.dropdowns) for (g in x.groups) g.probe?.let { p -> assertNotNull("${g.id}: $p", XrayProbes.all.firstOrNull { it.id == p }) }
        val fps = d.elements.single { it.id == "fps" }
        assertEquals("right", fps.position)
        assertEquals("system_popup", fps.opens)
        assertTrue(StatusBarDecl.layout(d).getValue("right").any { it.id == "fps" })
    }

    @Test
    fun `X5 a bad side or opener is refused`() {
        val el = """{"id":"ram","label":"r","kind":"metric","position":"right","order":1,"visible":true,"pet":null,"opens":"system_popup"}"""
        fun j(side: String, by: String) = """{"row_height_dp":26,"elements":[$el],"dropdowns":[{"id":"x","side":"$side","title":"X","popup":"system_popup","opened_by":["$by"],"groups":[]}]}"""
        assertTrue(runCatching { StatusBarDecl.parse(j("right", "ram")) }.isSuccess)
        assertTrue(runCatching { StatusBarDecl.parse(j("middle", "ram")) }.isFailure)
        assertTrue(runCatching { StatusBarDecl.parse(j("right", "nope")) }.isFailure)
    }
}
