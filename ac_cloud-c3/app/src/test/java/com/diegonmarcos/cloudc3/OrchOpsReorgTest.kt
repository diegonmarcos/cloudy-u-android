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
        "ops/orch" to "orch", "ops/orch_flow" to "orch/orch_flow", "ops/orch_service" to "orch/orch_service",
        "ops/orch_infrastructure" to "orch/orch_infrastructure", "ops/orch_orchestration" to "orch/orch_orchestration",
        "ops/orch_release" to "orch/orch_release", "ops/orch_agents" to "orch/orch_agents",
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
            "mesh" to Target("ops", "net_mesh"), "service" to Target("orch", "orch_service"), "infrastructure" to Target("orch", "orch_infrastructure"),
            "orchestration" to Target("orch", "orch_orchestration"), "release" to Target("orch", "orch_release"), "agents" to Target("orch", "orch_agents"),
            "network" to Target("ops", "net_network"), "observ" to Target("ops"), "mail_map" to Target("ops", "mail_tables"),
        )
        moved.forEach { (old, to) -> assertEquals(old, to, C3Nav.resolve(nav, aliases, old)) }
    }

    @Test
    fun `Orch is the second bottom tab with the orchestration pages, Dagu, the release table and the GHCR table`() {
        assertEquals(listOf("infra", "orch", "home", "containers", "ops"), nav.bottomNav)
        assertNull("observ is off the bar and no longer a section", nav.section("observ"))
        assertEquals(
            listOf("orch_flow", "orch_service", "orch_infrastructure", "orch_orchestration", "orch_release", "orch_agents", "orch_dagu", "orch_gh_releases", "orch_ghcr"),
            nav.section("orch")!!.pages.map { it.id },
        )
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
