package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainerTopology
import com.diegonmarcos.cloudc3.pages.ContainersModel
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
 * #1953 the Containers > Topology body is the declared plan (data/container_projects.json topology_layout): a services band (Apps, DB, MCP, API,
 * Other) then a Repos band, the Cloud-me Public / Private split, LFS repos under DB, hubs and declared repos on every project they are attached to,
 * and every entity the page drew before still drawn under its `<group> · <category>` sub-header.
 */
class ContainerTopologyTest {

    private val dir = FleetFixtures.moduleDir()
    private val decl = ContainerProjects.parse(File(dir, "data/container_projects.json").readText(), File(dir, "data/repos_declared.json").readText())
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val raw = Json.parseToJsonElement(File(dir, "data/container_projects.json").readText()).jsonObject["topology_layout"]!!.jsonObject

    private fun plan(id: String?) = ContainerTopology.plan(decl, ContainerProjects.scope(decl, catalog, id), id)
    private val ids: List<String?> = decl.projects.map { it.id } + listOf<String?>(null)

    @Test
    fun `bands and sections follow the declared order`() {
        val bands = raw["bands"]!!.jsonArray.map { it.jsonObject }
        val p = plan(null)
        assertEquals(bands.map { it["id"]!!.jsonPrimitive.content }, p.bands.map { it.id })
        assertEquals(listOf("services", "repos"), p.bands.map { it.id })
        bands.zip(p.bands).forEach { (b, pb) -> assertEquals(b["sections"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }, pb.sections.map { it.id }) }
        assertEquals(listOf("app", "db", "mcp", "api", "other"), p.bands[0].sections.map { it.id })
    }

    @Test
    fun `the section types are the declared Types, each in exactly one section`() {
        val types = ContainerTopology.layout(decl).bands.flatMap { b -> b.sections.flatMap { it.types } }
        assertEquals(decl.types.toSet(), types.toSet())
        assertEquals("a Type sits in two sections", types.size, types.toSet().size)
    }

    @Test
    fun `Cloud-me splits Apps, DB and Repos in Public and Private, no other project splits`() {
        val p = plan("pj-unix-cloudme")
        listOf("app", "db", "repo").forEach { s -> assertEquals(s, listOf("pub" to "Public", "pvt" to "Private"), p.section(s)!!.parts.map { it.id to it.label }) }
        listOf("mcp", "api", "other").forEach { s -> assertEquals(listOf("all"), p.section(s)!!.parts.map { it.id }) }
        val repo = p.section("repo")!!
        val pub = repo.parts[0].subs.flatMap { it.names }; val pvt = repo.parts[1].subs.flatMap { it.names }
        assertTrue(pub.isNotEmpty() && pvt.isNotEmpty())
        assertTrue(pub.all { n -> decl.repos.first { it.name == n }.visibility == "pub" })
        assertTrue(pvt.all { n -> decl.repos.first { it.name == n }.visibility == "pvt" })
        ids.filter { it != "pj-unix-cloudme" }.forEach { id ->
            assertTrue("$id splits", plan(id).bands.all { b -> b.sections.all { s -> s.parts.map { it.id } == listOf("all") } })
        }
    }

    @Test
    fun `an LFS repo is drawn in DB and not in Repos`() {
        val p = plan("pj-unix-cloudy")
        fun names(s: String) = p.section(s)!!.parts.flatMap { pt -> pt.subs.flatMap { it.names } }
        assertTrue("cloudy_db_LFS" in names("db"))
        assertFalse("cloudy_db_LFS" in names("repo"))
    }

    @Test
    fun `a hub is drawn in the Repos band of every project under its code`() {
        decl.repos.filter { it.role == "hub" }.forEach { h ->
            h.attached.forEach { a ->
                val repos = plan(a.project).section("repo")!!.parts.flatMap { pt -> pt.subs.flatMap { it.names } }
                assertTrue("${h.name} missing on ${a.project}", h.name in repos)
            }
        }
        val cg = plan("pj-cg-zirkusverse").section("repo")!!.parts.flatMap { pt -> pt.subs }
        assertTrue("the CG hub sits under a Hub sub-header on Zirkusverse", cg.any { it.label.startsWith("Hub · ") && "CG-___" in it.names })
    }

    @Test
    fun `#1968 the Wiki topology draws the former Lib repos`() {
        assertTrue("Wiki_ModernSc draws the Projects hub", "Projects-___" in plan("pj-wiki-modernsc").names())
        assertTrue("Wiki_Projects draws the root repo", "_git-repos-master" in plan("pj-wiki-projects").names())
    }

    @Test
    fun `each Wiki project carries lib-chronos with its path`() {
        mapOf("pj-wiki-modernsc" to "1.Modern-science", "pj-wiki-arcanum" to "2.Arcanum-science", "pj-wiki-projects" to "3.Projects").forEach { (id, path) ->
            val p = plan(id)
            assertTrue("$id draws no lib-chronos", "lib-chronos" in p.names())
            assertEquals(path, p.paths["lib-chronos"])
        }
    }

    @Test
    fun `parity - every entity of every project and All is drawn once under its group and category sub-header`() {
        ids.forEach { id ->
            val scoped = ContainerProjects.scope(decl, catalog, id)
            val p = ContainerTopology.plan(decl, scoped, id)
            assertEquals("$id parity", emptyList<String>(), ContainerTopology.parity(p, scoped))
            assertEquals("$id drew a different set", scoped.entries.map { it.name }.toSet(), p.names().toSet())
        }
    }

    @Test
    fun `parity - the Status, VM and Type filters reduce the plan exactly as scope does`() {
        decl.types.forEach { ty ->
            val scoped = ContainerProjects.scope(decl, catalog, null, null, setOf(ty))
            assertEquals(ty, scoped.entries.map { it.name }.toSet(), ContainerTopology.plan(decl, scoped, null).names().toSet())
        }
        val host = catalog.entries.first { it.host.isNotBlank() }.host
        val byVm = ContainerProjects.scope(decl, catalog, null, null, emptySet(), setOf(host))
        assertEquals(byVm.entries.map { it.name }.toSet(), ContainerTopology.plan(decl, byVm, null).names().toSet())
    }

    @Test
    fun `MUTATION - a plan that drops an entity fails parity`() {
        val scoped = ContainerProjects.scope(decl, catalog, "pj-unix-cloudy")
        val p = ContainerTopology.plan(decl, scoped, "pj-unix-cloudy")
        val cut = p.copy(bands = p.bands.map { b -> b.copy(sections = b.sections.map { s -> s.copy(parts = s.parts.map { pt -> pt.copy(subs = pt.subs.drop(1)) }) }) })
        assertTrue(ContainerTopology.parity(cut, scoped).isNotEmpty())
    }

    @Test
    fun `an empty band prints one line naming its sections`() {
        val b = plan(null).bands[0].copy(sections = plan(null).bands[0].sections.map { it.copy(parts = emptyList()) })
        assertEquals(0, b.count)
        assertEquals("No Apps, DB, MCP, API, Other declared for Wiki_ModernSc", ContainerTopology.emptyNote(b, "Wiki_ModernSc"))
    }
}
