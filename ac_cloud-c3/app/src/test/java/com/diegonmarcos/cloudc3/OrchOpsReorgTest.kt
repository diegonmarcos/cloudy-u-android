package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.C3Nav.Target
import com.diegonmarcos.superapp.bottomnav.NavDecl
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1942 the Orch / Ops reorg (rule 16: a reorg keeps every page). [BEFORE] lists every page and sub-page of the old Ops and Observ
 * tabs with its new place; [parity] reports what the current nav no longer reaches, and the mutation test proves a dropped page turns it red.
 */
class OrchOpsReorgTest {

    private fun ui(): JsonObject {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return Json.parseToJsonElement(File(dir, "build.json").readText()).jsonObject["ui"]!!.jsonObject
    }

    private val nav: NavDecl by lazy {
        val u = ui()
        NavDecl.parse(u["sections"]!!.toString(), u["bottom_nav"]!!.jsonArray.joinToString(",") { it.jsonPrimitive.content }, u["default_section"]!!.jsonPrimitive.content)
    }
    private val aliases: Declarations.Aliases by lazy { Declarations.parseAliases(ui()["aliases"]!!.toString()) }

    /** Old place "section/page" -> new place "section/page" (a section id alone for a whole tab or group that became a tab). */
    private val BEFORE: Map<String, String> = linkedMapOf(
        "ops/net" to "ops/net", "ops/net_wg" to "ops/net_wg", "ops/net_auth" to "ops/net_auth", "ops/net_dns" to "ops/net_dns",
        "ops/net_network" to "ops/net_network", "ops/net_security" to "ops/net_security",
        "ops/mail" to "ops/mail", "ops/mail_tables" to "ops/mail_tables", "ops/mail_topology_inbound" to "ops/mail_topology_inbound",
        "ops/mail_topology_outbound" to "ops/mail_topology_outbound", "ops/mail_map" to "ops/mail_tables",
        "ops/orch" to "orch", "ops/orch_flow" to "orch/orch_home",
        // #1954 what is deployed (the Service and Declared pages) is in the Containers Table; the Release page is the Graph of the one GH Releases page
        "ops/orch_service" to "containers/all_table",
        "ops/orch_infrastructure" to "containers/all_table", "ops/orch_orchestration" to "containers/all_table",
        "ops/orch_release" to "orch/orch_gh_releases", "ops/orch_agents" to "orch/orch_agents",
        "ops/ops_health" to "ops/ops_health", "ops/ops_health_uptime" to "ops/ops_health_uptime", "ops/ops_health_tests" to "ops/ops_health_tests",
        "ops/obs" to "ops/obs", "ops/obs_logs" to "ops/obs_logs", "ops/obs_metrics" to "ops/obs_metrics", "ops/obs_alerts" to "ops/obs_alerts", "ops/obs_notify" to "ops/obs_notify",
        "observ" to "ops", "observ/observability" to "ops/obs_stack", "observ/health" to "ops/ops_health_health",
        "observ/dagu" to "orch/orch_dagu", "observ/mesh" to "ops/net_mesh",
    )

    /** The places a nav declares, as "section", "section/page" for every top and nested page. */
    private fun places(n: NavDecl): Set<String> = n.sections.flatMap { s -> listOf(s.id) + s.allPages().map { "${s.id}/${it.id}" } }.toSet()

    /** Every BEFORE entry whose new place is not in [declared]. */
    private fun parity(declared: Set<String>): List<String> = BEFORE.filterValues { it !in declared }.keys.toList()

    @Test
    fun `every page and sub-page of the old Ops and Observ tabs is still declared at its new place`() {
        assertEquals(emptyList<String>(), parity(places(nav)))
    }

    @Test
    fun `mutation - dropping any single moved page turns the parity red`() {
        val all = places(nav)
        BEFORE.values.toSet().forEach { assertTrue("parity must go red without $it", parity(all - it).isNotEmpty()) }
    }

    @Test
    fun `every old page id still resolves through the aliases to its new place`() {
        val moved = mapOf(
            "observability" to Target("ops", "obs_stack"), "health" to Target("ops", "ops_health_health"), "dagu" to Target("orch", "orch_dagu"),
            "mesh" to Target("ops", "net_mesh"), "service" to Target("containers", "all_table"), "infrastructure" to Target("containers", "all_table"), "orch_infrastructure" to Target("containers", "all_table"),
            "orchestration" to Target("containers", "all_table"), "release" to Target("orch", "orch_gh_releases"), "agents" to Target("orch", "orch_agents"),
            "orch_service" to Target("containers", "all_table"), "orch_orchestration" to Target("containers", "all_table"), "orch_release" to Target("orch", "orch_gh_releases"),
            "network" to Target("ops", "net_network"), "observ" to Target("ops"), "mail_map" to Target("ops", "mail_tables"),
        )
        moved.forEach { (old, to) -> assertEquals(old, to, C3Nav.resolve(nav, aliases, old)) }
    }

    @Test
    fun `Orch is the second bottom tab with the orchestration pages, Dagu, the release table and the GHCR table`() {
        assertEquals(listOf("infra", "orch", "home", "containers", "ops"), nav.bottomNav)
        assertNull("observ is off the bar and no longer a section", nav.section("observ"))
        assertEquals(
            listOf("orch_home", "orch_workflows", "orch_deploys", "orch_gh_releases", "orch_ghcr", "orch_dagu", "orch_agents"),
            nav.section("orch")!!.pages.map { it.id },
        )
    }

    @Test
    fun `#1972 Orch has no Infrastructure page and its old ids resolve to Containers, where the deployed state is`() {
        assertFalse("orch_infrastructure is back in Orch", nav.section("orch")!!.pages.any { it.id == "orch_infrastructure" })
        assertEquals(Target("containers", "all_table"), C3Nav.resolve(nav, aliases, "orch_infrastructure"))
        assertEquals(Target("containers", "all_table"), C3Nav.resolve(nav, aliases, "infrastructure"))
        assertEquals("Orch has the eight #1954 pages", 8, nav.section("orch")!!.pages.size)
    }

    private fun orchSection() = ui()["sections"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "orch" }
    private fun orchGroups() = orchSection()["groups"]!!.jsonArray.map { it.jsonObject }
    private fun groupPages(g: JsonObject) = g["pages"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `#1954 every Orch page appears exactly once, in Pipeline, Registries or CI-CD`() {
        val groups = orchGroups()
        assertEquals(listOf("Pipeline", "Registries", "CI/CD"), groups.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("orch_home", "orch_workflows", "orch_deploys"), groupPages(groups[0]))
        assertEquals(listOf("orch_gh_releases", "orch_ghcr"), groupPages(groups[1]))
        assertEquals(listOf("orch_dagu", "orch_agents"), groupPages(groups[2]))
        val declared = nav.section("orch")!!.pages.map { it.id }
        val inGroups = groups.flatMap { groupPages(it) }
        assertEquals("a page is declared once", declared.size, declared.toSet().size)
        assertEquals("a page is in the groups once", inGroups.size, inGroups.toSet().size)
        assertEquals("every declared page is in a group and every group page is declared", declared.sorted(), inGroups.sorted())
        // a dataset (what a page shows) is on exactly one Orch page: no second page lists the same thing
        val sets = orchSection()["pages"]!!.jsonArray.map { it.jsonObject }.flatMap { pg -> (pg["datasets"]?.jsonArray.orEmpty()).map { it.jsonPrimitive.content to pg["id"]!!.jsonPrimitive.content } }
        assertTrue("every Orch page declares what it shows", orchSection()["pages"]!!.jsonArray.all { it.jsonObject["datasets"]?.jsonArray?.isNotEmpty() == true })
        assertEquals("a dataset is listed on two Orch pages: " + sets.groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }, sets.size, sets.map { it.first }.toSet().size)
    }

    @Test
    fun `#1954 Home is the first Orch page and the first of the Pipeline group, and its flow is declared`() {
        assertEquals("orch_home", nav.section("orch")!!.pages.first().id)
        assertEquals("orch_home", groupPages(orchGroups().first()).first())
        val flow = Declarations.orchFlow
        assertEquals(listOf("sources", "workflows", "builds", "registries", "deploys", "running"), flow.stages.map { it.id })
        assertTrue("Home is reachable by id", C3Nav.resolve(nav, aliases, "orch_home") == Target("orch", "orch_home"))
    }

    @Test
    fun `#1954 one GH Releases page - the Release page is merged in, its id is an alias, both bodies are modes of the one page`() {
        val pages = nav.section("orch")!!.pages
        assertEquals("exactly one releases page", listOf("orch_gh_releases"), pages.filter { it.id.contains("release") || it.label.contains("Release") }.map { it.id })
        assertNull("the second releases page is back", pages.firstOrNull { it.id == "orch_release" })
        assertEquals(Target("orch", "orch_gh_releases"), C3Nav.resolve(nav, aliases, "orch_release"))
        assertEquals(Target("orch", "orch_gh_releases"), C3Nav.resolve(nav, aliases, "release"))
        // the fields of both former pages: the per-repo release table (Table) and the release topology view (Graph)
        assertEquals(listOf("table", "graph"), com.diegonmarcos.cloudc3.pages.GhReleasesViewer.MODES)
        assertTrue("the Graph is the declared release topology view", Declarations.topology.view(com.diegonmarcos.cloudc3.pages.TopologyModel.RELEASE) != null)
        assertTrue("the Table has the declared repos", Declarations.containerProjects.repos.isNotEmpty())
    }

    @Test
    fun `#1954 the removed Service and Declared pages are not in Orch and their ids resolve into Containers`() {
        val ids = nav.section("orch")!!.pages.map { it.id }
        listOf("orch_service", "orch_orchestration", "orch_release", "orch_infrastructure").forEach { assertFalse("$it is back in Orch", it in ids) }
        listOf("orch_service", "orch_orchestration", "service", "orchestration", "infrastructure").forEach {
            assertEquals(it, Target("containers", "all_table"), C3Nav.resolve(nav, aliases, it))
        }
    }

    private fun infraGroups() = ui()["sections"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "infra" }["groups"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `#1972 the Infra groups are Infra Cloud, Infra Bare, Services API, Services Domains and Projects in the declared order`() {
        assertEquals(listOf("Infra Cloud", "Infra Bare", "Services API", "Services Domains", "Projects"), infraGroups().map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("bare", "personal", "apis", "domains_row", "projects_row"), infraGroups().map { it["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `#1972 Infra Bare has no Control page, the old id resolves to Gadgets and every other page is still there`() {
        val bare = infraGroups().first { it["id"]!!.jsonPrimitive.content == "personal" }["pages"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("desktop", "mobile", "gadgets"), bare)
        assertNull("control is a page again", nav.section("infra")!!.pages.firstOrNull { it.id == "control" })
        assertEquals(Target("infra", "gadgets"), C3Nav.resolve(nav, aliases, "control"))
    }

    @Test
    fun `#1972 parity the merged C3 API page keeps every field of C3 Endpoints and C3 MCP, and both old ids resolve to it`() {
        val pages = nav.section("infra")!!.pages
        assertEquals("C3 API", pages.single { it.id == "api_endpoints" }.label)
        assertNull("api_mcp is its own page again", pages.firstOrNull { it.id == "api_mcp" })
        assertEquals(Target("infra", "api_endpoints"), C3Nav.resolve(nav, aliases, "api_mcp"))
        val apis = Declarations.apiProviders
        assertEquals("both former groups answer the merged page", listOf("api_endpoints", "api_endpoints"), listOf(Declarations.fleetEndpoints.group, Declarations.c3Mcp.group).map { g -> apis.groups.single { it.id == g }.page })
        // what the two old pages showed per row: a service row (C3 Endpoints), a server row and a tool row (C3 MCP); the merged page draws the same lines
        val fleet = Declarations.fleetEndpoints
        assertTrue(fleet.services.isNotEmpty())
        fleet.services.forEach { s -> listOf("Public:", "Mesh:", "Health:", "Auth:").forEach { f -> assertTrue("${s.id} lost $f", com.diegonmarcos.cloudc3.pages.FleetEndpoints.lines(s).any { it.startsWith(f) }) } }
        val mcp = Declarations.c3Mcp
        assertTrue(mcp.servers.isNotEmpty())
        mcp.servers.forEach { s ->
            listOf("Endpoint:", "Auth:", "API:", "Aliases:", "Tools:", "c3-api nodes:").forEach { f -> assertTrue("${s.name} lost $f", com.diegonmarcos.cloudc3.pages.C3Mcp.lines(s).any { it.startsWith(f) }) }
            s.tools.forEach { t -> listOf("API route", "tree", "node").forEach { f -> assertTrue("${t.tool} lost $f", com.diegonmarcos.cloudc3.pages.C3Mcp.lines(t).any { it.startsWith(f) }) } }
        }
    }

    @Test
    fun `Health sits beside Uptime and Tests, WG Mesh is under Network, and the Observability group is labelled Obsv`() {
        val ops = nav.section("ops")!!
        assertEquals(listOf("ops_health_uptime", "ops_health_tests", "ops_health_health"), ops.pages.first { it.id == "ops_health" }.pages.map { it.id })
        assertEquals("WG Mesh", ops.pages.first { it.id == "net" }.pages.first { it.id == "net_mesh" }.label)
        val obs = ops.pages.first { it.id == "obs" }
        assertEquals("Obsv", obs.label)
        assertEquals("Observability", obs.pages.first { it.id == "obs_stack" }.label)
        assertFalse("no Ops entry duplicates the name Observability beside its page", ops.pages.any { it.label == "Observability" })
    }
}
