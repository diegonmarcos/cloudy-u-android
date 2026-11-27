package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.cloudc3.cloud.OpsClient
import com.diegonmarcos.cloudc3.cloud.StatusLight
import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.FleetModel
import com.diegonmarcos.cloudc3.pages.HomeLive
import com.diegonmarcos.superapp.bottomnav.NavDecl
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1969 Home: every declared section and page has a row; a source that failed shows its reason, never a zero. */
class HomeLiveTest {
    private val dir = FleetFixtures.moduleDir()
    private val ui = Json.parseToJsonElement(File(dir, "build.json").readText()).jsonObject["ui"]!!.jsonObject
    private val nav = NavDecl.parse(ui["sections"]!!.toString(), ui["bottom_nav"]!!.jsonArray.joinToString(",") { it.jsonPrimitive.content }, ui["default_section"]!!.jsonPrimitive.content)
    private val home = HomeLive.parse(ui["home"]!!.toString())
    private val decl = ContainerProjects.parse(File(dir, "data/container_projects.json").readText(), File(dir, "data/repos_declared.json").readText())
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val slice = FleetModel.parse(File(dir, "data/fleet_slice.json").readText())

    private fun rows(i: HomeLive.Inputs = HomeLive.Inputs()) = HomeLive.rows(nav, home, i, decl, catalog, slice)
    private fun flat(ps: List<com.diegonmarcos.superapp.bottomnav.NavPage>): List<String> = ps.flatMap { listOf(it.id) + flat(it.pages) }

    @Test
    fun `every declared section and page has a Home row, in the declared order`() {
        val want = nav.sections.filter { it.id != "home" }.flatMap { s -> listOf(s.id to null as String?) + flat(s.pages).map { s.id to it } }
        assertEquals(want, rows().map { it.section to it.page })
        assertTrue(rows().isNotEmpty())
    }

    @Test
    fun `every declared source is a known one and every row has a tap target`() {
        (home.sectionRows.values + home.sectionPages.values + home.pages.values + home.default).forEach { assertTrue("unknown source $it", it in HomeLive.SOURCES) }
        home.pages.keys.forEach { p -> assertTrue("ui.home.pages names an undeclared page $p", nav.sections.any { s -> flat(s.pages).contains(p) }) }
        rows().forEach { assertTrue(it.target.isNotBlank()) }
    }

    @Test
    fun `a failed source shows its reason on the row, never a zero`() {
        val failed = HomeLive.Inputs(
            down = HomeLive.Live.Failed("NETWORK: no route"), runs = HomeLive.Live.Failed("GitHub unreachable"), dagu = HomeLive.Live.Failed("Dagu down"),
            uptime = HomeLive.Live.Failed("uptime 503"), reports = HomeLive.Live.Failed("reports 503"), mail = HomeLive.Live.Failed("mail 503"),
        )
        val r = rows(failed)
        listOf("orch_flow" to "GitHub unreachable", "orch_dagu" to "Dagu down", "ops_health_uptime" to "uptime 503", "ops_health_health" to "reports 503", "mail_tables" to "mail 503", "machines" to "NETWORK: no route")
            .forEach { (page, why) ->
                val row = r.first { it.page == page }
                assertTrue("$page shows its reason: ${row.reading.text}", row.reading.text.contains(why)); assertEquals(StatusLight.State.UNKNOWN, row.reading.light)
            }
        assertTrue(r.first { it.page == "projects" }.reading.text.contains("NETWORK: no route"))
        assertTrue("a measured container count is never shown as up when the read failed", r.filter { it.source == "containers_project" && it.page != null }.none { it.reading.light == StatusLight.State.ON })
    }

    @Test
    fun `an unreachable fleet read is unknown, not up`() {
        val r = rows(HomeLive.Inputs(down = HomeLive.Live.Ok(FleetDown.Report.Unreachable(OpsClient.Kind.NETWORK, "x"))))
        assertEquals(StatusLight.State.UNKNOWN, r.first { it.page == "machines" }.reading.light)
    }
}
