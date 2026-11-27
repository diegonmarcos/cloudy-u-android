package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.cloudc3.cloud.OpsClient
import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.FleetModel
import com.diegonmarcos.cloudc3.pages.InfraProjects
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1969 Infra > Projects: every declared project is a row, the columns are the declared ones, the status never claims what it did not measure. */
class InfraProjectsTest {
    private val dir = FleetFixtures.moduleDir()
    private val decl = ContainerProjects.parse(File(dir, "data/container_projects.json").readText(), File(dir, "data/repos_declared.json").readText())
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val slice = FleetModel.parse(File(dir, "data/fleet_slice.json").readText())
    private val domains = slice.domains.map { it.name }
    private val build = Json.parseToJsonElement(File(dir, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sections"]!!.jsonArray.map { it.jsonObject }

    private fun rows(report: FleetDown.Report? = null, measured: Set<String> = emptySet()) = InfraProjects.rows(decl, catalog, domains, report, measured)

    @Test
    fun `every declared project is a row and the columns match the declaration`() {
        assertEquals(decl.projects.map { it.id }, rows().map { it.id })
        val raw = Json.parseToJsonElement(File(dir, "data/container_projects.json").readText()).jsonObject["projects_table"]!!.jsonObject["columns"]!!.jsonArray
        assertEquals(raw.map { it.jsonObject["key"]!!.jsonPrimitive.content }, decl.projectsColumns.map { it.first })
        assertEquals(listOf("category", "project", "repos", "containers", "vms", "domains", "status", "links"), decl.projectsColumns.map { it.first })
        rows().forEach { r -> decl.projectsColumns.forEach { (k, _) -> assertTrue("${r.id} has no cell $k", InfraProjects.cell(r, k).isNotEmpty()) } }
    }

    @Test
    fun `Wiki rows hold the former Lib repos and Lib is not a category`() {
        val wiki = rows().filter { it.category == "Wiki" }
        assertEquals(listOf("ModernSc", "Arcanum", "Projects"), wiki.map { it.project })
        assertTrue("the former Lib repos are counted under Wiki", wiki.sumOf { it.repos } >= 3)
        assertTrue(rows().none { it.category == "Lib" })
    }

    @Test
    fun `repo counts split public and private and the row opens the project topology`() {
        rows().forEach { r ->
            val n = decl.repos.count { x -> x.attached.any { it.project == r.id } }
            assertEquals(n, r.repos); assertEquals(r.pub + r.pvt, r.repos)
            assertEquals("${r.id}_topology", r.page)
        }
    }

    @Test
    fun `status is unknown with its reason when the live read failed or has not happened, never up`() {
        val withBoxes = rows().filter { it.containers > 0 }
        assertTrue(withBoxes.isNotEmpty())
        withBoxes.forEach { assertEquals("not measured yet", it.reason); assertEquals(InfraProjects.UNKNOWN, it.status) }
        rows(FleetDown.Report.Unreachable(OpsClient.Kind.values().first(), "no route")).filter { it.containers > 0 }.forEach {
            assertEquals(InfraProjects.UNKNOWN, it.status); assertTrue(it.reason.contains("no route"))
        }
        rows().filter { it.containers == 0 }.forEach { assertEquals(InfraProjects.NONE, it.status) }
    }

    @Test
    fun `sort filter and search work over the declared columns`() {
        val all = rows()
        val sorted = InfraProjects.sort(all, "repos", false).map { it.repos }
        assertEquals(sorted.sortedDescending(), sorted)
        assertEquals(all.filter { it.category == "Wiki" }.size, InfraProjects.filter(decl, all, "", mapOf("category" to "Wiki")).size)
        assertEquals(listOf("pj-wiki-arcanum"), InfraProjects.filter(decl, all, "arcanum", emptyMap()).map { it.id })
    }

    @Test
    fun `Infra declares Projects right after Domains and a hierarchy node`() {
        val infra = build.first { it["id"]!!.jsonPrimitive.content == "infra" }
        val ids = infra["pages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals("domains_public", ids[ids.indexOf("projects") - 1])
        val rowsOrder = infra["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals("projects_row", rowsOrder[rowsOrder.indexOf("domains_row") + 1])
        assertTrue(File(dir, "data/c3_hierarchy.json").readText().contains("\"projects\""))
    }
}
