package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainersModel
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1454 the container -> project mapping is data/container_projects.json, held against the Containers document and build.json. */
class ContainerProjectsTest {

    private val dir = FleetFixtures.moduleDir()
    private val decl = ContainerProjects.parse(File(dir, "data/container_projects.json").readText())
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!

    private fun navTopIds(): List<String> {
        val ui = Json.parseToJsonElement(File(dir, "build.json").readText()).jsonObject["ui"]!!.jsonObject
        return ui["sections"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "containers" }["pages"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `every project page of the Containers strip is All or a declared project`() {
        val declared = decl.projects.map { it.id }
        assertTrue("the strip lists a project the mapping does not know", navTopIds().all { decl.project(it) != null || it == "all" })
        assertTrue("a declared project has no page", declared.all { it in navTopIds() })
        assertNotNull("the default project is declared", decl.project(decl.default))
    }

    @Test
    fun `every container belongs to exactly one project and the projects partition the document`() {
        val seen = decl.projects.associate { p -> p.id to ContainerProjects.scope(decl, catalog, p.id).entries.map { it.name } }
        assertEquals(catalog.names.sorted(), seen.values.flatten().sorted())
        assertEquals("no container is in two projects", catalog.names.size, seen.values.flatten().toSet().size)
    }

    @Test
    fun `a scoped catalog keeps only its project in the document's order and drops empty categories`() {
        decl.projects.forEach { p ->
            val c = ContainerProjects.scope(decl, catalog, p.id)
            val names = c.entries.map { it.name }.toSet()
            c.groups.flatMap { g -> g.categories }.forEach { cat ->
                assertTrue("empty category ${cat.label}", cat.names.isNotEmpty())
                assertTrue(cat.names.all { it in names })
            }
        }
    }

    @Test
    fun `the All table clusters rows by project and labels the group, a project table keeps the plain group`() {
        val all = ContainerProjects.rows(decl, catalog, null)
        assertEquals(catalog.entries.size, all.size)
        val labels = decl.projects.map { it.label }
        val seq = all.map { r -> labels.indexOfFirst { r.group.startsWith("$it / ") } }
        assertTrue("every All row names its project", seq.all { it >= 0 })
        assertEquals("rows are clustered in the declared project order", seq.sorted(), seq)
        val one = decl.projects.first { p -> ContainerProjects.scope(decl, catalog, p.id).entries.isNotEmpty() }
        assertTrue(ContainerProjects.rows(decl, catalog, one.id).none { it.group.startsWith("${one.label} / ") })
    }
}
