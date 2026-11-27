package com.diegonmarcos.cloudc3

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.cloudc3.pages.MailStack
import com.diegonmarcos.superapp.uikit.KitPalette
import java.io.File
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1926 Ops > Mail: declaration parity, health colours, taps, tables carry every declared field. Mutations prove each guard red. */
class MailStackTest {
    private val dir = FleetFixtures.moduleDir()
    private val text = File(dir, "data/mail_stack.json").readText()
    private val decl = MailStack.parseDecl(text)
    private val pal = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black,
        Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFFF0000))

    private fun probe(id: String, state: String, source: String = "live", extra: String = "") =
        """{"id":"$id","state":"$state","detail":"d","latencyMs":12,"checkedAt":"2026-11-25T10:00:00Z","queue":3,"errors":["boom"],"checks":1,"source":"$source"$extra}"""

    private fun answer(vararg probes: String, stale: Boolean = false, tables: String = "{}") = MailStack.parseAnswer(
        """{"probes":[${probes.joinToString(",")}],"tables":$tables,"notServed":[],"report":{"generated":"x","ageText":"1h","stale":$stale,"durationMs":1},"generatedAt":"t"}""")!!

    @Test fun `the declaration is sound and has three-way structure`() {
        assertEquals(emptyList<String>(), MailStack.validate(decl))
        assertEquals(setOf("inbound", "outbound"), decl.topologies.map { it.id }.toSet())
        assertTrue(decl.tables.size >= 14)
        listOf("mail_tables", "mail_topology_inbound", "mail_topology_outbound").forEach { assertTrue(it, File(dir, "build.json").readText().contains("\"$it\"")) }
        // #1955 B the Mail subpage merged into Tables: no page object, only the alias
        assertTrue(File(dir, "build.json").readText().contains("\"mail_map\": \"ops/mail_tables\""))
        assertTrue(!File(dir, "build.json").readText().contains("\"id\": \"mail_map\""))
    }

    // ── #1955 B the old Mail map is inside Tables ──────────────────────────────────────────────────────────────

    private fun json(f: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, "data/$f").readText()) as JsonObject
    private val src by lazy {
        com.diegonmarcos.cloudc3.pages.OpsMaps.Sources(json("ops_maps.json"), json("mesh.json"), com.diegonmarcos.cloudc3.pages.TopologyModel.parseInfra(json("infra_slice.json")),
            json("infra_slice.json"), com.diegonmarcos.cloudc3.pages.ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!, FleetFixtures.slice().domains)
    }

    /** Every (section, name, note, state, to, label, edge state) of the old Mail map that the mapped Tables table does not carry; empty = nothing lost. */
    private fun mapParity(tables: (MailStack.Table) -> List<MailStack.Row>): List<String> {
        val rowsOf = { id: String -> tables(decl.table(id)!!) }
        val lost = mutableListOf<String>()
        for (sec in com.diegonmarcos.cloudc3.pages.OpsMaps.build("mail", src)) for (r in sec.rows) {
            val ok = when (sec.title) {
                "Inbound", "Outbound" -> {
                    val t = rowsOf(if (sec.title == "Inbound") "map_inbound" else "map_outbound")
                    val want = if (r.edges.isEmpty()) listOf(Triple("", "", "")) else r.edges.map { Triple(it.to, it.label, it.state) }
                    want.all { (to, label, st) -> t.any { it["node"] == r.name && it["note"] == r.note && it["state"] == r.state && it["to"] == to && it["label"] == label && it["edge_state"] == st } }
                }
                "Containers" -> rowsOf("map_containers").any { "${it["host"]} - ${it["port"]} - ${it["auth_type"]}".split(" - ").filter { x -> x.isNotBlank() }.joinToString(" - ") == r.note && it["name"] == r.name }
                "Hosts" -> rowsOf("hosts_declared").any { it["host"] == r.name && it["service"] == r.note && it["vm"] == r.edges.firstOrNull()?.to }
                "Relays" -> rowsOf("relays_declared").any { it["kind"] == "relay" && it["name"] == r.name && "${it["address"]}:${it["port"]}" == r.note }
                "MX hosts" -> rowsOf("relays_declared").any { it["kind"] == "mx" && it["name"] == r.name }
                "DNS records" -> rowsOf("dns_declared").any { it["name"] == r.name && it["type"] == r.note && it["group"] == r.state }
                else -> false
            }
            if (!ok) lost += "${sec.title}: ${r.name}"
        }
        return lost
    }

    private fun tableRows(t: MailStack.Table) = MailStack.rows(t, null, json("infra_slice.json"), FleetFixtures.slice().domains, src)

    @Test fun `parity - every section, row and edge of the old Mail map is in a Tables table`() {
        val secs = com.diegonmarcos.cloudc3.pages.OpsMaps.build("mail", src)
        assertEquals(listOf("Inbound", "Outbound", "Containers", "Hosts", "Relays", "MX hosts", "DNS records").filter { t -> secs.any { it.title == t } }, secs.map { it.title })
        assertTrue(secs.sumOf { it.rows.size } > 8)
        assertEquals(emptyList<String>(), mapParity(::tableRows))
    }

    @Test fun `MUTATION - a Tables row that loses a map edge fails the map parity`() {
        assertTrue(mapParity { t -> tableRows(t).let { rs -> if (t.id == "map_inbound") rs.drop(1) else rs } }.isNotEmpty())
    }

    @Test fun `the map tables come first, declared, with the path columns`() {
        assertEquals(listOf("map_inbound", "map_outbound", "map_containers"), decl.tables.take(3).map { it.id })
        decl.tables.take(3).forEach { assertEquals("declared", it.source); assertEquals("mail", it.map) }
        assertEquals(listOf("node", "note", "state", "to", "label", "edge_state"), decl.table("map_inbound")!!.columns.map { it.key })
        assertEquals(listOf("state"), decl.table("map_inbound")!!.filters)
    }

    @Test fun `the inbound and outbound maps name every hop the ticket lists`() {
        val inn = decl.topology("inbound")!!.nodes.map { it.id }
        val out = decl.topology("outbound")!!.nodes.map { it.id }
        listOf("sender", "dns_mx", "cf_edge", "relay", "maddy", "stalwart", "sorter", "clients", "webmail").forEach { assertTrue(it, it in inn) }
        listOf("client", "submission", "signer", "queue", "recipient").forEach { assertTrue(it, it in out) }
    }

    @Test fun `parity every declared node and edge is drawn once and a mutation is caught`() {
        decl.topologies.forEach { t ->
            val plan = MailStack.plan(t, null, decl, pal)
            assertEquals(emptyList<String>(), MailStack.parity(t, plan))
            val dup = plan.copy(nodes = plan.nodes + plan.nodes.first())
            assertTrue(MailStack.parity(t, dup).isNotEmpty())
            assertTrue(MailStack.parity(t, plan.copy(edges = plan.edges.drop(1))).isNotEmpty())
            assertTrue(MailStack.parity(t, plan.copy(nodes = plan.nodes.drop(1))).isNotEmpty())
        }
    }

    @Test fun `validate flags a dangling edge`() {
        val t = decl.topologies.first()
        val bad = decl.copy(topologies = listOf(t.copy(edges = t.edges + MailStack.Edge("x", "nope", t.nodes.first().id, "", emptyList(), false))))
        assertTrue(MailStack.validate(bad).isNotEmpty())
    }

    @Test fun `health maps ok warn bad unknown to green amber red grey`() {
        assertEquals(pal.ok, MailStack.healthColor(MailStack.Health.OK, pal))
        assertEquals(pal.warn, MailStack.healthColor(MailStack.Health.WARN, pal))
        assertEquals(pal.bad, MailStack.healthColor(MailStack.Health.BAD, pal))
        assertEquals(pal.textSecondary, MailStack.healthColor(MailStack.Health.UNKNOWN, pal))
        assertEquals(MailStack.Health.UNKNOWN, MailStack.healthOf("whatever"))
    }

    @Test fun `a node takes the worst served probe, unserved is grey, a stale report green is amber`() {
        val n = MailStack.Node("n", "N", "k", 0f, 0f, "", "", "", "", emptyList(), emptyList(), listOf("a", "b", "c"), "", emptyList())
        assertEquals(MailStack.Health.UNKNOWN, MailStack.nodeHealth(n, null))
        assertEquals(MailStack.Health.UNKNOWN, MailStack.nodeHealth(n, answer()))
        assertEquals(MailStack.Health.OK, MailStack.nodeHealth(n, answer(probe("a", "ok"))))
        assertEquals(MailStack.Health.WARN, MailStack.nodeHealth(n, answer(probe("a", "ok"), probe("b", "warn"))))
        assertEquals(MailStack.Health.BAD, MailStack.nodeHealth(n, answer(probe("a", "ok"), probe("b", "warn"), probe("c", "bad"))))
        assertEquals(MailStack.Health.WARN, MailStack.nodeHealth(n, answer(probe("a", "ok", "report"), stale = true)))
        assertEquals(MailStack.Health.OK, MailStack.nodeHealth(n, answer(probe("a", "ok", "live"), stale = true)))
    }

    @Test fun `plan colours come from live health and unknown wires carry no pulses`() {
        val t = decl.topology("inbound")!!
        val node = t.nodes.first { it.probes.isNotEmpty() }
        val a = answer(*node.probes.map { probe(it, "bad") }.toTypedArray())
        val plan = MailStack.plan(t, a, decl, pal)
        assertEquals(pal.bad, plan.nodes.first { it.id == node.id }.color)
        assertEquals(pal.textSecondary, MailStack.plan(t, null, decl, pal).nodes.first { it.id == node.id }.color)
        val grey = MailStack.plan(t, null, decl, pal).edges.first()
        assertTrue(MailStack.pulses(grey, 0.3f).isEmpty())
        assertEquals(MailStack.PULSES_PER_EDGE, MailStack.pulses(plan.edges.first { it.flowing }, 0.3f).size)
    }

    @Test fun `pulses advance along from to to`() {
        val t = decl.topology("outbound")!!
        val a = answer(*t.nodes.flatMap { it.probes }.map { probe(it, "ok") }.toTypedArray())
        val e = MailStack.plan(t, a, decl, pal).edges.first { it.flowing }
        val start = MailStack.bezier(e, 0f); val end = MailStack.bezier(e, 1f)
        assertEquals(e.p0, start); assertEquals(e.p3, end)
        val early = MailStack.pulses(e, 0.0f).first(); val later = MailStack.pulses(e, 0.2f).first()
        fun d(o: Offset) = (o - end).getDistance()
        assertTrue(d(later) < d(early))
    }

    @Test fun `tap hit returns that node and nothing on empty canvas, through pan and zoom`() {
        val t = decl.topology("inbound")!!
        val plan = MailStack.plan(t, null, decl, pal)
        val v = MailStack.View(1f, 0f, 0f).transform(Offset(100f, 100f), Offset(30f, 10f), 1.5f)
        plan.nodes.forEach { n -> assertEquals(n.id, MailStack.hit(plan, v, v.toScreen(Offset(n.rect.cx, n.rect.cy)))) }
        assertNull(MailStack.hit(plan, v, Offset(-5000f, -5000f)))
        assertTrue(v.scale <= MailStack.MAX_SCALE && MailStack.View(1f, 0f, 0f).transform(Offset.Zero, Offset.Zero, 100f).scale == MailStack.MAX_SCALE)
    }

    @Test fun `node detail carries role host container ports tls and the served probe values`() {
        val t = decl.topology("inbound")!!
        val n = t.nodes.first { it.probes.isNotEmpty() && it.queueProbe.isNotBlank() }
        val a = answer(*(n.probes + n.queueProbe).map { probe(it, "warn") }.toTypedArray())
        val d = MailStack.detail(n, a)
        assertEquals(MailStack.Health.WARN, d.health); assertEquals(12, d.latencyMs); assertEquals(3, d.queue); assertEquals(listOf("boom"), d.errors.distinct())
        assertTrue(d.lastCheck.isNotBlank()); assertTrue(MailStack.detail(n, null).notServed.containsAll(n.probes))
    }

    @Test fun `every declared column of every table is a cell, missing keys read not served`() {
        decl.tables.forEach { t ->
            assertTrue(t.id, t.columns.isNotEmpty()); assertTrue(t.id, t.filters.all { f -> t.columns.any { it.key == f } })
            val full = listOf(t.columns.associate { it.key to "v" })
            assertEquals(emptyList<String>(), MailStack.missingFields(t, full))
            t.columns.forEach { c -> assertEquals("v", MailStack.cell(full[0], c.key)) }
            val partial = listOf(full[0] - t.columns.last().key)
            assertEquals(listOf(t.columns.last().key), MailStack.missingFields(t, partial))
            assertEquals(MailStack.NOT_SERVED, MailStack.cell(partial[0], t.columns.last().key))
        }
        assertEquals("-", MailStack.cell(mapOf("a" to ""), "a"))
    }

    @Test fun `the ticket's data points each have a table`() {
        val ids = decl.tables.map { it.id }
        listOf("map_inbound", "map_outbound", "map_containers", "dns", "dns_declared", "inbound", "outbound", "accounts", "mailboxes", "ports", "tls", "health", "queues", "freshness", "pullers", "failures", "tests").forEach { assertTrue(it, it in ids) }
        assertTrue(decl.table("tls")!!.columns.any { it.key == "days_left" } && decl.table("tls")!!.columns.any { it.key == "not_after" })
        val tests = File(dir, "data/mail_stack.json").readText()
        listOf("mail_inbound", "mail_outbound", "mail_distribution", "mail_ingest", "mail_puller").forEach { assertTrue(it, tests.contains(it) || decl.table("tests") != null) }
    }

    @Test fun `search and filters narrow rows, a mutated filter is caught`() {
        val t = decl.table("ports")!!
        val rows = listOf(mapOf("host" to "A", "port" to "25", "state" to "ok"), mapOf("host" to "B", "port" to "465", "state" to "bad"))
        assertEquals(1, MailStack.filter(t, rows, "465", emptyMap()).size)
        assertEquals(1, MailStack.filter(t, rows, "", mapOf("state" to "bad")).size)
        assertEquals(2, MailStack.filter(t, rows, "  ", emptyMap()).size)
        assertNotEquals(2, MailStack.filter(t, rows, "", mapOf("state" to "ok")).size)
        assertEquals(listOf("A", "B"), MailStack.choices(rows, "host"))
    }

    @Test fun `declared tables are filled from the app's own declarations`() {
        val infra = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, "data/infra_slice.json").readText()) as JsonObject
        val domains = FleetFixtures.slice().domains
        assertTrue(MailStack.declaredRows("dns_declared", infra, domains).isNotEmpty())
        assertTrue(MailStack.declaredRows("hosts_declared", infra, domains).isNotEmpty())
        assertTrue(MailStack.declaredRows("relays_declared", infra, domains).isNotEmpty())
    }

    @Test fun `parseAnswer rejects a body that is not a mail stack`() {
        assertNull(MailStack.parseAnswer("{}")); assertNull(MailStack.parseAnswer("nope"))
    }
}
