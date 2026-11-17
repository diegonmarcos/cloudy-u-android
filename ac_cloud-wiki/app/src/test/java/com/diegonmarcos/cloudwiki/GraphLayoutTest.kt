package com.diegonmarcos.cloudwiki

import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.superapp.graphview.ForceLayout
import com.diegonmarcos.superapp.graphview.GEdge
import com.diegonmarcos.superapp.graphview.GraphCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The force layout of libs:graph-view on the real graph: it settles, keeps nodes apart, pulls linked nodes together, and a tap finds its node. */
class GraphLayoutTest {
    private val d = Data.wiki
    private fun dist(l: ForceLayout, a: String, b: String): Float {
        val i = l.ids.indexOf(a); val j = l.ids.indexOf(b)
        return Math.hypot((l.x[i] - l.x[j]).toDouble(), (l.y[i] - l.y[j]).toDouble()).toFloat()
    }

    @Test fun theLayoutIsDeterministicAndSettles() {
        val e = Wiki.edges(d)
        val a = ForceLayout(Wiki.nodes(d).map { it.id }, e).also { it.settle(600) }
        val b = ForceLayout(Wiki.nodes(d).map { it.id }, e).also { it.settle(600) }
        assertTrue(a.x.contentEquals(b.x) && a.y.contentEquals(b.y))
        assertTrue("finite", a.x.all { it.isFinite() } && a.y.all { it.isFinite() })
        assertTrue("settled", a.step() < 5f)
    }

    @Test fun topicsFormClustersAndLinkedPagesStayWithinTheLinkDistanceScale() {
        val nodes = Wiki.nodes(d)
        val e = Wiki.edges(d)
        val l = ForceLayout(nodes.map { it.id }, e, groups = nodes.map { it.group }).also { it.settle(800) }
        val topic = nodes.associate { it.id to it.group }
        var intra = 0.0; var ni = 0; var inter = 0.0; var nx = 0
        for (i in 0 until l.n) for (j in i + 1 until l.n) {
            val dd = dist(l, l.ids[i], l.ids[j])
            if (topic[l.ids[i]] == topic[l.ids[j]]) { intra += dd; ni++ } else { inter += dd; nx++ }
        }
        assertTrue("pages of one topic sit closer (${intra / ni}) than pages of different topics (${inter / nx})", intra / ni < 0.9 * (inter / nx))
        val links = e.filter { it.kind == GEdge.Kind.LINK }.map { dist(l, it.a, it.b) }.average()
        assertTrue("linked pages average $links, the springs hold them near the link distance ${l.rest}", links < 1.6 * l.rest)
    }

    @Test fun theLinkDistanceGrowsWithTheNodeCount() {
        val small = ForceLayout((0 until 10).map { "a$it" }, emptyList())
        val big = ForceLayout((0 until 100).map { "a$it" }, emptyList())
        assertTrue(big.rest > 1.5f * small.rest)
    }

    @Test fun theViewOpensFittedToTheScreenAndEveryNodeIsInside() {
        val l = ForceLayout(Wiki.nodes(d).map { it.id }, Wiki.edges(d)).also { it.settle(800) }
        val w = 1000f; val h = 1800f; val margin = 24f
        val (sc, pan) = GraphCamera.fit(l, w, h, margin)
        val c = Offset(w / 2f, h / 2f)
        for (i in 0 until l.n) {
            val p = GraphCamera.toScreen(l.x[i], l.y[i], c, pan, sc)
            assertTrue("node ${l.ids[i]} at $p is inside the view", p.x >= margin - 0.5f && p.x <= w - margin + 0.5f && p.y >= margin - 0.5f && p.y <= h - margin + 0.5f)
        }
        assertTrue("the graph fills the narrow side", sc * (l.x.max() - l.x.min()) > 0.7f * w || sc * (l.y.max() - l.y.min()) > 0.7f * h)
        assertEquals(1f to Offset.Zero, GraphCamera.fit(ForceLayout(emptyList(), emptyList()), w, h))
    }

    @Test fun nodesDoNotCollapseOntoEachOther() {
        val l = ForceLayout(Wiki.nodes(d).map { it.id }, Wiki.edges(d)).also { it.settle(800) }
        var min = Float.MAX_VALUE
        for (i in 0 until l.n) for (j in i + 1 until l.n) min = minOf(min, dist(l, l.ids[i], l.ids[j]))
        assertTrue("closest pair $min", min > 4f)
    }

    @Test fun anEmptyGraphIsFine() {
        val l = ForceLayout(emptyList(), emptyList())
        assertEquals(0f, l.step(), 0f)
    }

    @Test fun aTapFindsTheNodeUnderIt() {
        val l = ForceLayout(listOf("a", "b"), listOf(GEdge("a", "b", GEdge.Kind.LINK))).also { it.settle(300) }
        val center = Offset(200f, 300f); val pan = Offset(15f, -9f); val scale = 1.7f
        val i = l.ids.indexOf("a")
        val screen = GraphCamera.toScreen(l.x[i], l.y[i], center, pan, scale)
        val world = GraphCamera.toWorld(screen, center, pan, scale)
        assertEquals(l.x[i], world.x, 0.01f)
        assertEquals("a", GraphCamera.hit(l, world, 28f / scale))
        assertNull(GraphCamera.hit(l, Offset(5000f, 5000f), 28f))
        assertNotNull(GraphCamera.hit(l, Offset(l.x[1], l.y[1]), 1f))
    }
}
