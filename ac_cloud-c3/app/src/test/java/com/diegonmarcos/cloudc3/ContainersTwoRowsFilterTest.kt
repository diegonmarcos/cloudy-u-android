package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.ContainersNav
import com.diegonmarcos.cloudc3.pages.ContainerHosts
import com.diegonmarcos.cloudc3.pages.FleetModel
import com.diegonmarcos.superapp.bottomnav.NavPage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1982 Containers shows the Projects row and the VM row together, as AND filters (All in a row = no filter on it), and keeps everything
 * #1971 had (views, Topology | Table, Status / VM / Type filter rows, every project, every VM page). Pure JVM; the drawn rows are
 * ContainersTwoRowsComposeTest.
 */
class ContainersTwoRowsFilterTest {

    private val dir = FleetFixtures.moduleDir()
    private val decl0 = ContainerProjects.parse(File(dir, "data/container_projects.json").readText(), File(dir, "data/repos_declared.json").readText())
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val slice = FleetModel.parse(File(dir, "data/fleet_slice.json").readText())
    private val hosts = ContainerHosts.derive(slice, catalog, decl0.vm.hostOrder)
    private val decl = decl0.copy(hostIds = hosts.map { it.id })
    private val vm = decl.vm.prefix
    private val pages = Json.parseToJsonElement(File(dir, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sections"]!!.jsonArray
        .map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "containers" }["pages"]!!.jsonArray.map { it.jsonObject }
    private val navPages = pages.map { p -> NavPage(p["id"]!!.jsonPrimitive.content, p["label"]!!.jsonPrimitive.content, "ic_p_c3_stack") }
    private val declared = navPages.filterNot { decl.isVmPage(it.id) }
    private val allId = ContainersNav.allPageOf(decl, declared).id

    private fun names(scope: String?) = ContainerProjects.scope(decl, catalog, scope).entries.map { it.name }.toSet()
    private val hostPages = hosts.map { vm + it.id } + (vm + decl.vm.unknown)

    @Test
    fun `#1982 AND filtering - a project or category row and a VM row narrow together, All in a row is no filter`() {
        var narrowed = 0
        val rows1 = decl.projects.map { it.id } + decl.categories().map { ContainerProjects.CAT + it }
        rows1.forEach { r1 ->
            hostPages.forEach { r2 ->
                val both = names("$r1${ContainerProjects.SCOPE_SEP}$r2")
                assertEquals("$r1 AND $r2 is the intersection", names(r1) intersect names(r2), both)
                if (both.isNotEmpty() && both.size < names(r1).size && both.size < names(r2).size) narrowed++
            }
        }
        assertTrue("at least one project/category x VM pair narrows both rows (Unix on oci-apps)", narrowed > 0)
        // All in a row: no filter on that row
        assertEquals("", ContainersNav.scope(decl, allId, "", vm + decl.vm.all))
        decl.categories().forEach { c ->
            assertEquals(names(ContainerProjects.CAT + c), names(ContainersNav.scope(decl, allId, c, vm + decl.vm.all)))
            hostPages.forEach { h -> assertEquals(names(ContainerProjects.CAT + c) intersect names(h), names(ContainersNav.scope(decl, allId, c, h))) }
        }
        // category Unix = the union of its projects
        assertEquals(decl.projectsOf("Unix").flatMap { names(it.id) }.toSet(), names(ContainerProjects.CAT + "Unix"))
        // the scope id of a pick is parsed back into its parts
        val sc = decl.scopeOf("pj-unix-cloudme|${vm}oci-apps")
        assertEquals("pj-unix-cloudme", sc.project); assertEquals("${vm}oci-apps", sc.vm)
        assertTrue(decl.scopeKnown("cat:Unix|${vm}oci-apps")); assertFalse(decl.scopeKnown("cat:Nope|${vm}oci-apps"))
    }

    @Test
    fun `#1982 the VM row order and labels are the declared host_order, All first, Unknown host last`() {
        val cp = Json.parseToJsonElement(File(dir, "data/container_projects.json").readText()).jsonObject["vm_view"]!!.jsonObject
        val order = cp["host_order"]!!.jsonArray.map { it.jsonPrimitive.content }
        val short = cp["device_short"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val want = listOf("All") + order.map { short[it] ?: it } + "Unknown host"
        assertEquals(want, navPages.filter { decl.isVmPage(it.id) }.map { it.label })
        assertEquals(order, hosts.map { it.id })
    }

    @Test
    fun `#1982 parity with 1971 - every page, view, project, VM and filter row of the toggle UI is still there`() {
        // views: every page keeps its Topology | Table leaves
        pages.forEach { p ->
            val id = p["id"]!!.jsonPrimitive.content
            assertEquals("$id keeps Topology | Table", listOf("${id}_topology", "${id}_table"), p["pages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        }
        assertEquals(listOf("all") + decl.projects.map { it.id } + listOf(vm + decl.vm.all) + hosts.map { vm + it.id } + (vm + decl.vm.unknown), navPages.map { it.id })
        // every project is reachable through the Projects row (its category chip) and its sub-row
        decl.projects.forEach { p ->
            assertTrue(p.category in ContainersNav.categories(decl, declared))
            assertTrue("${p.id} is on the sub-row of ${p.category}", ContainersNav.subProjects(decl, declared, p.category).any { it.id == p.id })
            // Projects=X / VM=All is the old project page
            assertEquals(p.id, ContainersNav.scope(decl, p.id, "", vm + decl.vm.all))
            assertEquals(names(p.id), names(ContainersNav.scope(decl, p.id, "", vm + decl.vm.all)))
        }
        // VM=Y / Projects=All is the old VM page, repos band and unknown-host reason included
        hostPages.forEach { h -> assertEquals(h, ContainersNav.scope(decl, allId, "", h)) }
        assertEquals(names(null), names(vm + decl.vm.all))
        // the toggle is gone, the filter rows and the chrome rows stay
        val page = File(dir, "app/src/main/java/com/diegonmarcos/cloudc3/pages/ContainersPage.kt").readText()
        assertFalse("the Projects | VM toggle is gone", page.contains("ROW_MODE") || page.contains("selectedMode"))
        listOf("Status", "VM", "Type").forEach { assertTrue("Topology filter row $it", page.contains("ChipRow(\"$it\")")) }
        listOf("ROW_CATEGORY", "ROW_PROJECT", "ROW_VIEW", "ROW_FILTERS").forEach { assertTrue(it, page.contains("ContainersTags.$it")) }
        val table = File(dir, "app/src/main/java/com/diegonmarcos/cloudc3/pages/RoutesTable.kt").readText()
        listOf("Status", "Auth", "VM", "Project", "Type").forEach { assertTrue("Table filter row $it", table.contains("ChipRow(\"$it\"")) }
    }
}
