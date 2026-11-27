package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.NetGraph
import com.diegonmarcos.cloudc3.pages.OpsMaps
import com.diegonmarcos.cloudc3.pages.TopologyModel
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1955 A The Graph of every Network subpage is built from the same inputs as its Table and draws exactly the table (parity), over the baked data. */
class NetGraphTest {
    private val dir = FleetFixtures.moduleDir()
    private fun json(f: String): JsonObject = Json.parseToJsonElement(File(dir, "data/$f").readText()).jsonObject
    private val opsMaps = json("ops_maps.json")
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val infra = TopologyModel.parseInfra(json("infra_slice.json"))
    private val slice = FleetFixtures.slice()
    private val src = OpsMaps.Sources(opsMaps, json("mesh.json"), infra, json("infra_slice.json"), catalog, slice.domains)
    private val inputs = NetGraph.Inputs(src, slice, TopologyModel.Inputs(catalog, slice, infra), json("mesh.json"))
    private val viewers = NetGraph.parseViewers(opsMaps)
    private fun page(id: String): Pair<NetGraph.Table, NetGraph.Graph> = NetGraph.page(viewers, id, inputs) ?: error("$id declares no graph")

    /** The net_* leaves build.json declares under Ops > Network. */
    private val netPages: List<String> = run {
        val text = File(dir, "build.json").readText()
        Regex("\"id\": \"(net_[a-z_]+)\"").findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    @Test fun `every Network page declared in build json has a viewers entry, and the six are there`() {
        assertEquals(listOf("net_wg", "net_auth", "net_dns", "net_network", "net_security", "net_mesh"), netPages)
        netPages.forEach { assertNotNull("$it has no viewers.pages entry: a Network page cannot ship without a graph", viewers.graph(it)) }
        assertEquals(listOf("table", "graph"), viewers.modes)
        assertEquals("table", viewers.default)
    }

    @Test fun `every section of a bound map matches exactly one rule, every rule matches a section, binds name real rows and containers`() {
        netPages.forEach { id ->
            val g = viewers.graph(id)!!
            if (g["from"]!!.jsonPrimitive.content != "map") return@forEach
            val map = g["map"]!!.jsonPrimitive.content
            val titles = OpsMaps.build(map, src, Int.MAX_VALUE).map { it.title }
            val rules = g["sections"]!!.jsonArray.map { it.jsonObject }
            titles.forEach { t -> assertEquals("$id: section '$t' must match exactly one rule", 1, rules.count { t.startsWith(it["match"]!!.jsonPrimitive.content) }) }
            rules.forEach { r ->
                val m = r["match"]!!.jsonPrimitive.content
                assertTrue("$id: rule '$m' matches no section", titles.any { it.startsWith(m) })
                (r["bind_rows"] as? JsonObject)?.forEach { (row, bind) ->
                    assertTrue("$id: bind_rows names an undeclared row $row", OpsMaps.build(map, src, Int.MAX_VALUE).any { s -> s.rows.any { it.name == row } })
                    val b = (bind as JsonPrimitive).content
                    if (b.startsWith("container:")) assertNotNull("$id: $b is no catalog container", catalog.entry(b.removePrefix("container:")))
                }
            }
        }
        val binds = Regex("\"container:([a-z0-9-]+)\"").findAll(opsMaps["viewers"].toString()).map { it.groupValues[1] }.toList()
        assertTrue(binds.isNotEmpty())
        binds.forEach { assertNotNull("container:$it is no catalog container", catalog.entry(it)) }
    }

    @Test fun `parity - each page's graph draws exactly its table`() {
        netPages.forEach { id ->
            val (t, g) = page(id)
            assertTrue("$id has rows", t.rows.isNotEmpty())
            assertEquals("$id parity", emptyList<String>(), NetGraph.parity(t, g))
        }
    }

    @Test fun `WireGuard - a host on two subnets is one node with two rows, and the hub edge lands on it through the id resolve`() {
        val (_, g) = page("net_wg")
        val hub = infra.subnets.first().hub
        val alias = slice.vms.first { it.id == hub }.alias
        val n = g.node("host:$alias")!!
        assertEquals(2, n.rows.size)
        assertTrue("no node is named by the VM id", g.nodes.none { it.label == hub })
        assertTrue("spokes point at the hub", g.inEdges(n.id).isNotEmpty())
        assertEquals(1, n.column)
    }

    @Test fun `Auth - a tier and an auth kind of the same name are two nodes`() {
        val rule = viewers.graph("net_auth")!!
        val g = NetGraph.fromSections("net_auth", rule, listOf(
            OpsMaps.Section("Edge tier of each route", listOf(OpsMaps.Row("none", edges = listOf(OpsMaps.Edge("a.example", "svc"))))),
            OpsMaps.Section("Auth kind of each container", listOf(OpsMaps.Row("none", edges = listOf(OpsMaps.Edge("box", "vm"))))),
        ))
        assertNotNull(g.node("tier:none")); assertNotNull(g.node("authkind:none"))
        val (_, real) = page("net_auth")
        assertTrue("a gate reaches its tier", real.outEdges("gate:Authelia").any { it.to.startsWith("tier:") })
        assertTrue("member clusters exist", real.clusters.isNotEmpty())
    }

    @Test fun `DNS - lanes are the VMs of the data and the mesh names depend on the primary's address`() {
        val (_, g) = page("net_dns")
        val vms = (infra.routes.map { r -> slice.vms.firstOrNull { it.id == r.vm }?.alias ?: r.vm } +
            infra.dns.map { d -> slice.vms.firstOrNull { it.wgIp == d.value }?.alias ?: d.value }).toSet()
        assertEquals(vms, g.lanes.map { it.id }.toSet())
        assertTrue(g.depends.isNotEmpty())
        assertTrue(g.depends.all { it.to == "address:${infra.dnsPrimary}" })
    }

    @Test fun `Routes - every tree node is a node and every parent-child line an edge`() {
        val roots = TopologyModel.build(TopologyModel.NETWORK, inputs.topology)
        val (_, g) = page("net_network")
        assertEquals(TopologyModel.walk(roots).size, g.nodes.size)
        assertEquals(g.nodes.size - roots.size, g.edges.size)
        assertEquals(roots.map { it.id }, g.lanes.map { it.id })
    }

    @Test fun `Mesh - every directed peering once, one lane per transport`() {
        val mesh = json("mesh.json")
        val (_, g) = page("net_mesh")
        assertEquals(mesh["peers"]!!.jsonArray.size, g.edges.size)
        assertEquals(mesh["transports"]!!.jsonArray.size, g.lanes.size)
        assertTrue("spokes depend on the hub", g.depends.isNotEmpty())
    }

    @Test fun `Security - one page root and one root per section`() {
        val (_, g) = page("net_security")
        assertEquals(1, g.nodes.count { it.ns == "root" })
        assertEquals(2, g.nodes.count { it.ns == "server" })
        assertTrue(g.edges.all { it.declared == "root" })
    }

    @Test fun `the node sheet prints every field of every row it stands for`() {
        netPages.forEach { id ->
            val (_, g) = page(id)
            g.nodes.forEach { n -> n.rows.flatMap { it.fields }.forEach { f -> assertTrue("$id ${n.id} sheet misses ${f.first}", f in NetGraph.sheet(n, g)) } }
        }
    }

    @Test fun `parity goes red when an edge is lost, a node is doubled or an undeclared node is added`() {
        val (t, g) = page("net_wg")
        assertTrue(NetGraph.parity(t, g.copy(edges = g.edges.drop(1))).isNotEmpty())
        assertTrue(NetGraph.parity(t, g.copy(nodes = g.nodes + g.nodes.first())).isNotEmpty())
        assertTrue(NetGraph.parity(t, g.copy(nodes = g.nodes + g.nodes.first().copy(id = "x:undeclared", rows = emptyList(), declared = "made-up"))).isNotEmpty())
    }
}
