package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.OpsLive
import com.diegonmarcos.cloudc3.pages.OpsMaps
import com.diegonmarcos.cloudc3.pages.TopologyModel
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1480 The Ops maps are built from this repository's own declarations, so no fixture restates the fleet. */
class OpsMapsTest {
    private val dir = FleetFixtures.moduleDir()
    private fun json(f: String): JsonObject = Json.parseToJsonElement(File(dir, "data/$f").readText()).jsonObject
    private val src = OpsMaps.Sources(
        json("ops_maps.json"), json("mesh.json"), TopologyModel.parseInfra(json("infra_slice.json")), json("infra_slice.json"),
        ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!, FleetFixtures.slice().domains,
    )

    private fun rows(id: String) = OpsMaps.build(id, src).flatMap { it.rows }

    @Test
    fun `every declared map builds sections with rows, and each derived source resolves`() {
        val decl = src.decl["maps"]!!.jsonObject
        assertTrue(decl.keys.containsAll(listOf("wg", "auth", "dns", "mail", "flow")))
        decl.keys.forEach { id ->
            assertTrue("$id has no rows", rows(id).isNotEmpty())
            assertTrue("$id has no summary", OpsMaps.summary(src.decl, id).isNotBlank())
            val derived = decl[id]!!.jsonObject["sections"]!!.toString().split("\"from\"").size - 1
            val declaredOnly = decl[id]!!.jsonObject["sections"]!!.toString().split("\"rows\"").size - 1
            assertTrue("$id: fewer sections than declared (a derived source that does not resolve)", OpsMaps.build(id, src).size >= derived + declaredOnly)
        }
    }

    @Test
    fun `the WireGuard map shows the hub's peers from the data and the planned WARP channel marked planned`() {
        val wg = OpsMaps.build("wg", src)
        assertTrue("a subnet section per declared subnet", wg.count { it.title.startsWith("Subnets") } == src.infra.subnets.size)
        val warp = rows("wg").first { it.state == "planned" }
        assertTrue("the planned channel names its ticket", warp.note.contains("#1479"))
        assertTrue("the planned edge is planned too", warp.edges.all { it.state == "planned" })
        val peers = src.infra.subnets.first().peers.size
        assertEquals(peers, wg.first().rows.size)
        assertTrue("spokes point at the hub", wg.first().rows.any { r -> r.edges.any { it.to == src.infra.subnets.first().hub } })
    }

    @Test
    fun `the auth map groups every route by its tier and every container by its auth kind, capped`() {
        val s = OpsMaps.build("auth", src)
        val tiers = s.first { it.title.startsWith("Edge") }.rows
        assertEquals(src.infra.routes.map { it.auth }.toSet(), tiers.map { it.name }.toSet())
        val kinds = s.first { it.title.startsWith("Auth kind") }.rows
        assertEquals(src.catalog.entries.map { it.authType.ifBlank { "-" } }.toSet(), kinds.map { it.name }.toSet())
        assertTrue("an edge list is capped", kinds.all { it.edges.size <= 15 })
    }

    @Test
    fun `the DNS map has every Caddy host once and the mesh names`() {
        val s = OpsMaps.build("dns", src)
        assertEquals(src.infra.routes.map { it.host }.sorted(), s.filter { it.title.startsWith("Caddy") }.flatMap { it.rows }.map { it.name }.sorted())
        assertEquals(src.infra.dns.size, s.first { it.title.startsWith("Mesh") }.rows.size)
    }

    @Test
    fun `the mail map carries inbound, outbound, the declared mail containers, relays, MX hosts and the DNS record names without values`() {
        val titles = OpsMaps.build("mail", src).map { it.title }
        assertTrue(titles.containsAll(listOf("Inbound", "Outbound", "Containers", "Relays", "DNS records")))
        val dns = OpsMaps.build("mail", src).first { it.title == "DNS records" }.rows
        assertTrue("MX, SPF, DKIM and DMARC records are listed", listOf("MX", "SPF", "DKIM", "DMARC").all { g -> dns.any { it.state == g } })
        assertTrue("a record is a name and a type, never its content", dns.all { it.edges.isEmpty() && it.note.length <= 5 })
    }

    @Test
    fun `the flow map runs CI-CD to Infra to Release and every edge lands on a node`() {
        val s = OpsMaps.build("flow", src)
        assertEquals(listOf("CI/CD", "Infra", "Release"), s.map { it.title })
        val names = s.flatMap { it.rows }.map { it.name }.toSet() + "Health"
        s.flatMap { it.rows }.flatMap { it.edges }.forEach { assertTrue("edge to ${it.to} has no node", it.to in names) }
    }

    @Test
    fun `live answers parse, and an unreadable one is null rather than an empty list`() {
        assertEquals(listOf(OpsLive.Uptime("b", 50.0, 2), OpsLive.Uptime("a", 99.5, 10)),
            OpsLive.parseUptime("""[{"vm":"a","checks":10,"up":9,"down":1,"uptimePercent":99.5},{"vm":"b","checks":2,"uptimePercent":50}]"""))
        assertNull(OpsLive.parseUptime("<html>"))
        val r = OpsLive.parseReports("""{"families":[{"family":"x","ok":true,"ageHours":3,"stale":false},{"family":"y","ok":false,"status":404}]}""")!!
        assertEquals(listOf("y", "x"), r.map { it.family })
        assertEquals("HTTP 404", r.first().error)
        assertNull(OpsLive.parseReports("nope"))
        val lines = OpsLive.parseLogs("""{"total":2,"lines":[{"ts":"2026-11-05T10:00:00Z","vm":"v1","container":"c","level":"error","line":"boom"},{"ts":"2026-11-05T11:00:00Z","vm":"v2","container":"d","level":"info","line":"ok"}]}""")
        assertNotNull(lines)
        assertEquals(listOf("v2", "v1"), OpsLive.byVm(lines!!, null).map { it.vm })
        assertEquals(listOf("v1"), OpsLive.byVm(lines, setOf("v1")).map { it.vm })
        assertEquals("/logs/search?limit=50&q=a+b&service=s&level=warn", OpsLive.logsPath("a b", "s", "warn", 50))
        assertEquals("/logs/search?limit=5", OpsLive.logsPath("", "", "", 5))
    }
}
