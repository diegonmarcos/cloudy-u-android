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
 *   X2 the System dropdown is four tiers (Profile, Runtime, Diagnosis by family, Suspects), icon groups kept, new probes declared
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
    fun `X2 the System dropdown is four tiers`() {
        val g = d.dropdown("right_system")!!.groups
        val ids = g.map { it.id }
        val tiers = listOf("t1_profile", "t2_runtime", "t3_diagnosis", "t4_suspects")
        assertEquals(tiers, ids.filter { it in tiers })
        assertTrue(g.filter { it.id in tiers || it.id.startsWith("f_") }.all { it.source == "header" && it.probe == null })
        fun between(a: String, b: String) = ids.subList(ids.indexOf(a) + 1, ids.indexOf(b))
        assertEquals(listOf("device", "soc", "gpu", "display", "capacity"), between("t1_profile", "t2_runtime"))
        assertEquals(listOf("memory", "cpu", "thermal", "graphics", "storage", "battery_live"), between("t2_runtime", "t3_diagnosis"))
        val diag = between("t3_diagnosis", "t4_suspects")
        assertEquals(listOf("f_power", "f_jank", "f_notif", "f_memory", "f_resp"), diag.filter { it.startsWith("f_") })
        assertEquals(listOf("wakelocks", "power_by_uid", "alarms", "jobs", "running_services"), diag.subList(1, diag.indexOf("f_jank")))
        assertEquals(listOf("jank"), diag.subList(diag.indexOf("f_jank") + 1, diag.indexOf("f_notif")))
        assertEquals(listOf("notif_rate", "binder"), diag.subList(diag.indexOf("f_notif") + 1, diag.indexOf("f_memory")))
        assertEquals(listOf("memory_3h", "meminfo_detail"), diag.subList(diag.indexOf("f_memory") + 1, diag.indexOf("f_resp")))
        assertEquals(listOf("anr"), diag.subList(diag.indexOf("f_resp") + 1, diag.size))
        assertEquals(listOf("t4_suspects", "suspects"), ids.takeLast(2))
        val by = g.associateBy { it.id }
        assertEquals("top", by.getValue("cpu").probe); assertEquals("gfxinfo", by.getValue("graphics").probe); assertEquals("gfxinfo", by.getValue("jank").probe)
        assertEquals("notification", by.getValue("notif_rate").probe); assertEquals("meminfo", by.getValue("meminfo_detail").probe)
        assertEquals("fps", by.getValue("graphics").element); assertEquals("ram", by.getValue("memory").element)
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
