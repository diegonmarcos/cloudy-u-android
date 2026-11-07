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
 *   X2 the System dropdown's icon groups: Memory, Storage, CPU, Graphics, Running Services, Last 3h Memory Usage
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
    fun `X2 the System dropdown groups by icon`() {
        val g = d.dropdown("right_system")!!.groups
        assertEquals(listOf("Memory", "Storage", "CPU", "Graphics", "Running Services", "Last 3h Memory Usage"), g.map { it.label })
        assertEquals(listOf("ram", "storage", "cpu", "fps", null, null), g.map { it.element })
        assertEquals("Running Services is the last-but-one, Last 3h the last", listOf("services", "procstats"), g.takeLast(2).map { it.probe })
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
