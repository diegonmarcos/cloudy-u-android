package com.diegonmarcos.cloudc3

import androidx.compose.ui.graphics.Color
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.NetGraph
import com.diegonmarcos.cloudc3.pages.NetGraphLayout
import com.diegonmarcos.cloudc3.pages.OpsMaps
import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.superapp.uikit.KitPalette
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1955 A The layout draws every graph node and edge exactly once (itself, or inside one aggregate or bundle), with no overlap, deterministically. */
class NetGraphLayoutTest {
    private val dir = FleetFixtures.moduleDir()
    private fun json(f: String): JsonObject = Json.parseToJsonElement(File(dir, "data/$f").readText()).jsonObject
    private val opsMaps = json("ops_maps.json")
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val infra = TopologyModel.parseInfra(json("infra_slice.json"))
    private val slice = FleetFixtures.slice()
    private val inputs = NetGraph.Inputs(OpsMaps.Sources(opsMaps, json("mesh.json"), infra, json("infra_slice.json"), catalog, slice.domains),
        slice, TopologyModel.Inputs(catalog, slice, infra), json("mesh.json"))
    private val v = NetGraph.parseViewers(opsMaps)
    private val pal = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black,
        Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFFF0000))
    private val pages = listOf("net_wg", "net_auth", "net_dns", "net_network", "net_security", "net_mesh")
    private fun graph(id: String) = NetGraph.page(v, id, inputs)!!.second

    private fun states(g: NetGraph.Graph) = listOf("expanded" to emptySet(), "default" to NetGraphLayout.defaultCollapsed(g, v), "all collapsed" to g.clusters.map { it.id }.toSet())

    @Test fun `parity - every node and edge is drawn exactly once, expanded and collapsed, on every page`() {
        pages.forEach { id ->
            val g = graph(id)
            states(g).forEach { (name, c) ->
                assertEquals("$id $name", emptyList<String>(), NetGraphLayout.parity(g, NetGraphLayout.layout(g, v, c, pal)))
            }
        }
    }

    @Test fun `no two node rects overlap and every node sits inside its lane`() {
        pages.forEach { id ->
            val g = graph(id)
            states(g).forEach { (name, c) ->
                val l = NetGraphLayout.layout(g, v, c, pal)
                val ns = l.plan.nodes
                for (i in ns.indices) for (j in i + 1 until ns.size) {
                    val a = ns[i].rect; val b = ns[j].rect
                    assertTrue("$id $name: ${ns[i].id} overlaps ${ns[j].id}", a.r <= b.l || b.r <= a.l || a.b <= b.t || b.b <= a.t)
                }
                val lanes = l.extras.laneRects.values
                ns.forEach { n -> assertTrue("$id $name: ${n.id} outside every lane", lanes.any { r -> n.rect.l >= r.l && n.rect.r <= r.r && n.rect.t >= r.t && n.rect.b <= r.b }) }
            }
        }
    }

    @Test fun `the layout is deterministic`() {
        pages.forEach { id ->
            val g = graph(id)
            val c = NetGraphLayout.defaultCollapsed(g, v)
            assertEquals(id, NetGraphLayout.layout(g, v, c, pal), NetGraphLayout.layout(graph(id), v, c, pal))
        }
    }

    @Test fun `fit-width shows four columns at least at scale 1 on a 1080 px phone`() {
        val g = graph("net_dns")
        val l = NetGraphLayout.layout(g, v, NetGraphLayout.defaultCollapsed(g, v), pal)
        assertTrue(NetGraphLayout.fitWidth(l.plan.bounds, 1080f).scale >= 1.0f)
    }

    @Test fun `a collapsed cluster's edge weighs exactly its members, and parity goes red when it does not`() {
        val g = graph("net_auth")
        val c = g.clusters.map { it.id }.toSet()
        val l = NetGraphLayout.layout(g, v, c, pal)
        val bundles = l.plan.edges.filter { it.weight > 1f }
        assertTrue("collapsed member clusters bundle their edges", bundles.isNotEmpty())
        bundles.forEach { assertEquals(l.extras.edgeMembers.getValue(it.id).size.toFloat(), it.weight) }
        val bad = l.copy(plan = l.plan.copy(edges = l.plan.edges.map { if (it.id == bundles[0].id) it.copy(weight = it.weight + 1) else it }))
        assertTrue(NetGraphLayout.parity(g, bad).isNotEmpty())
    }

    @Test fun `parity goes red when the plan loses an edge or draws a node twice`() {
        val g = graph("net_wg")
        val l = NetGraphLayout.layout(g, v, emptySet(), pal)
        assertTrue(NetGraphLayout.parity(g, l.copy(plan = l.plan.copy(edges = l.plan.edges.drop(1)))).isNotEmpty())
        assertTrue(NetGraphLayout.parity(g, l.copy(plan = l.plan.copy(nodes = l.plan.nodes + l.plan.nodes.first()))).isNotEmpty())
    }

    private fun hit(a: com.diegonmarcos.cloudc3.pages.MailStack.Rect, b: com.diegonmarcos.cloudc3.pages.MailStack.Rect) = !(a.r <= b.l || b.r <= a.l || a.b <= b.t || b.b <= a.t)

    @Test fun `a label whose wire midpoint lies inside a node is moved off every node`() {
        val node = com.diegonmarcos.cloudc3.pages.MailStack.Rect(40f, 0f, 160f, 60f)
        val item = NetGraphLayout.LabelItem("e", "wg_public") { t -> androidx.compose.ui.geometry.Offset(300f * t, 30f) }
        val r = NetGraphLayout.placeLabels(listOf(item), listOf(node), { it.length * 13f }, 20f)["e"]
        assertTrue("the label is placed", r != null)
        assertTrue("label ${r} touches the node", !hit(r!!, node))
    }

    @Test fun `no edge or bundle label touches a node or another label, on every page and cluster state, at phone density`() {
        val unit = 2.6f
        pages.forEach { id ->
            val g = graph(id)
            states(g).forEach { (name, c) ->
                val l = NetGraphLayout.layout(g, v, c, pal, unit = unit)
                assertEquals("$id $name", emptyList<String>(), NetGraphLayout.parity(g, l))
                val view = NetGraphLayout.fitWidth(l.plan.bounds, 1080f)
                val items = l.plan.edges.filter { it.label.isNotBlank() }.map { e -> NetGraphLayout.LabelItem(e.id, e.label) { t -> view.toScreen(com.diegonmarcos.cloudc3.pages.MailStack.bezier(e, t)) } } +
                    l.extras.bundleLabels.mapIndexed { i, (w, t) -> NetGraphLayout.LabelItem("bundle#$i", t) { _ -> view.toScreen(w) } }
                val nodes = l.plan.nodes.map { n -> val a = view.toScreen(androidx.compose.ui.geometry.Offset(n.rect.l, n.rect.t)); val b = view.toScreen(androidx.compose.ui.geometry.Offset(n.rect.r, n.rect.b)); com.diegonmarcos.cloudc3.pages.MailStack.Rect(a.x, a.y, b.x, b.y) }
                val placed = NetGraphLayout.placeLabels(items, nodes, { it.length * 11f }, 20f)
                val rs = placed.entries.toList()
                rs.forEach { (lid, r) -> nodes.forEach { n -> assertTrue("$id $name: label $lid overlaps a node", !hit(r, n)) } }
                for (i in rs.indices) for (j in i + 1 until rs.size) assertTrue("$id $name: labels ${rs[i].key} and ${rs[j].key} overlap", !hit(rs[i].value, rs[j].value))
            }
        }
    }

    @Test fun `at phone density a node is as large as its text - the unit scales every declared size`() {
        val g = graph("net_wg")
        val a = NetGraphLayout.layout(g, v, emptySet(), pal).plan.nodes.first().rect
        val b = NetGraphLayout.layout(g, v, emptySet(), pal, unit = 2f).plan.nodes.first().rect
        assertEquals((a.r - a.l) * 2f, b.r - b.l, 0.01f)
        assertEquals((a.b - a.t) * 2f, b.b - b.t, 0.01f)
    }
}
