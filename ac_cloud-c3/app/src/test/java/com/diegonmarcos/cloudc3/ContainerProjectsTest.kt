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
    private val decl = ContainerProjects.parse(File(dir, "data/container_projects.json").readText(), File(dir, "data/repos_declared.json").readText())
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
        val all = (catalog.names + decl.repos.map { it.name }).sorted()
        assertEquals(all, seen.values.flatten().sorted())
        assertEquals("no container or repo is in two projects", all.size, seen.values.flatten().toSet().size)
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
        assertEquals(catalog.entries.size + decl.repos.size, all.size)
        val labels = decl.projects.map { it.label }
        val seq = all.map { r -> labels.indexOfFirst { r.group.startsWith("$it / ") } }
        assertTrue("every All row names its project", seq.all { it >= 0 })
        assertEquals("rows are clustered in the declared project order", seq.sorted(), seq)
        val one = decl.projects.first { p -> ContainerProjects.scope(decl, catalog, p.id).entries.isNotEmpty() }
        assertTrue(ContainerProjects.rows(decl, catalog, one.id).none { it.group.startsWith("${one.label} / ") })
    }

    private fun others() = decl.project("others")!!.id

    @Test
    fun `every declared container resolves to a non-Others project and cloud-me-api is Cloud-me`() {
        catalog.entries.forEach { assertTrue("${it.name} falls to Others", decl.projectOf(it)?.id != others()) }
        assertEquals("cloud_me", decl.projectOf(catalog.entry("cloud-me-api")!!)?.id)
        assertEquals(listOf("cloud-me-api"), ContainerProjects.scope(decl, catalog, "cloud_me").entries.filter { it.role != "repo" }.map { it.name })
        assertEquals("capital", decl.projectOf(catalog.entry("news-gdelt")!!)?.id)
        assertEquals("cloudy", decl.projectOf(catalog.entry("vaultwarden")!!)?.id)
    }

    @Test
    fun `a name override beats the prefixes and an unknown container falls to Others`() {
        val e = catalog.entry("cloud-me-api")!!
        assertEquals("cloud_me", decl.projectOf(e)?.id)
        assertEquals("others", decl.projectOf(e.copy(name = "brand-new", folder = "zzz")) ?.id)
        assertEquals("capital", decl.projectOf(e.copy(name = "capital-new", folder = "zzz"))?.id)
    }

    @Test
    fun `the kind rules classify MCPs APIs DBs Repos and leave the rest Apps`() {
        fun k(n: String) = decl.kindOf(catalog.entry(n)!!)?.id
        assertEquals("mcp", k("cloud-infra-mcp")); assertEquals("api", k("c3-infra-api")); assertEquals("api", k("cloud-me-api"))
        assertEquals("db", k("umami-db")); assertEquals("db", k("postlite-npm")); assertEquals("db", k("authelia-redis"))
        assertEquals("apps", k("caddy")); assertEquals("apps", k("dbgate"))
        assertEquals(listOf("mcp", "api", "apps", "db", "repo"), decl.kinds.map { it.id })
        decl.projects.forEach { p -> decl.kinds.forEach { kd ->
            assertTrue(ContainerProjects.scope(decl, catalog, p.id, kd.id).entries.all { e -> decl.kindsOf(e).any { it.id == kd.id } })
        } }
        assertTrue(ContainerProjects.scope(decl, catalog, "cloudy", "repo").entries.all { it.role == "repo" })
    }

    @Test
    fun `every project tab is non-empty with the bundled data, in Topology groups and Table rows`() {
        decl.projects.forEach { p ->
            val c = ContainerProjects.scope(decl, catalog, p.id)
            assertTrue("${p.id} has no entry", c.entries.isNotEmpty())
            assertTrue("${p.id} has no group with a category", c.groups.any { g -> g.categories.isNotEmpty() })
            assertTrue("${p.id} has no Table row", ContainerProjects.rows(decl, catalog, p.id).isNotEmpty())
            assertTrue("${p.id} has no repo under the Repos filter", ContainerProjects.scope(decl, catalog, p.id, "repo").entries.isNotEmpty())
        }
    }

    @Test
    fun `a git repo is both a Repo and a DB, so it shows under both filters of its project`() {
        decl.repos.forEach { r ->
            val e = ContainerProjects.entryOf(decl, r)
            assertEquals(listOf("db", "repo"), decl.kindsOf(e).map { it.id }.sorted())
            assertEquals("git", e.dbEngine)
            listOf("db", "repo").forEach { k -> assertTrue(ContainerProjects.scope(decl, catalog, r.project, k).entries.any { it.name == r.name }) }
        }
        assertEquals(decl.repos.size + catalog.entries.size, ContainerProjects.rows(decl, catalog, null).size)
    }

    @Test
    fun `Infra Apps and Cloud Apps belong to Cloudy only, no tab has Project Apps, each other project has its own families`() {
        val macro = listOf(ContainersModel.INFRA, ContainersModel.CLOUD, ContainersModel.PROJECT)
        decl.projects.filter { it.repoGroup.isBlank() }.forEach { p ->
            val c = ContainerProjects.scope(decl, catalog, p.id)
            assertTrue("${p.id} repeats a Cloudy macro group", c.groups.none { it.label in macro } && c.entries.none { it.group in macro })
            assertTrue("${p.id} has no declared families", decl.families[p.id].orEmpty().isNotEmpty())
            assertTrue("${p.id} groups are not its families", c.groups.all { it.label in decl.families[p.id].orEmpty() || it.label == decl.containerGroup })
        }
        decl.projects.forEach { p -> assertTrue(ContainerProjects.scope(decl, catalog, p.id).entries.none { it.group == ContainersModel.PROJECT }) }
        val fam = decl.projects.filter { it.repoGroup.isBlank() }.map { decl.families[it.id].orEmpty().toSet() }
        assertTrue("families of two projects coincide", fam.size == fam.toSet().size)
    }

    @Test
    fun `Project Apps is gone from Cloudy and from the Table, its containers sit in Cloud Apps`() {
        val c = ContainerProjects.scope(decl, catalog, "cloudy")
        assertTrue(c.groups.none { it.label == "Project Apps" } && c.entries.none { it.group == "Project Apps" })
        assertEquals("Cloud Apps", c.entry("send_app")!!.group)
        assertTrue(ContainerProjects.rows(decl, catalog, null).none { it.group.endsWith("/ Project Apps") })
    }

    @Test
    fun `a project's groups are its registry families, Cloudy keeps its own groups`() {
        listOf("cloud_me", "cg", "capital", "hardtech", "others").forEach { id ->
            val groups = ContainerProjects.scope(decl, catalog, id).groups.filter { g -> g.categories.isNotEmpty() }.map { it.label }
            val fams = decl.families[id].orEmpty()
            assertTrue("$id groups $groups are not registry families $fams", groups.all { it in fams || it == decl.containerGroup })
        }
        decl.containerFamily.forEach { (n, f) ->
            val p = decl.projectOf(catalog.entry(n)!!)!!.id
            assertTrue("$n family $f is not a registry family of $p", f in decl.families[p].orEmpty())
        }
        val cloudy = ContainerProjects.scope(decl, catalog, "cloudy").groups.filter { it.categories.isNotEmpty() }.map { it.label }
        assertEquals(listOf("Infra Apps", "Cloud Apps", "Repos"), cloudy)
    }

    @Test
    fun `every owner repo is under its project with its facts, none archived`() {
        assertTrue(decl.repos.isNotEmpty())
        assertTrue(decl.repos.none { it.name.startsWith("z-archived_") })
        val r = decl.repos.first { it.name == "CG-galaxy" }
        assertEquals("cg", r.project); assertEquals("https://github.com/diegonmarcos/CG-galaxy", r.githubUrl)
        assertTrue(decl.repos.all { it.visibility in listOf("pub", "pvt") && it.githubUrl.endsWith("/${it.name}") })
        assertTrue(decl.repos.filter { it.name.startsWith("cloud-me-p") }.all { it.project == "cloud_me" })
        assertTrue(decl.repos.filter { it.name.startsWith("cloudy-") }.all { it.project == "cloudy" })
        val e = ContainerProjects.scope(decl, catalog, "cg", "repo").entry("CG-galaxy")!!
        assertEquals("repo", e.role)
        assertTrue(e.fields().any { it.first == "github_url" })
    }
}
