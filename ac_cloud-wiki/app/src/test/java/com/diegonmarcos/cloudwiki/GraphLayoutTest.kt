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

    @Test fun linkedPagesEndCloserThanUnrelatedOnesOnAverage() {
        val e = Wiki.edges(d)
        val l = ForceLayout(Wiki.nodes(d).map { it.id }, e).also { it.settle(800) }
        val links = e.filter { it.kind == GEdge.Kind.LINK }
        val linked = links.map { dist(l, it.a, it.b) }.average()
        val all = (0 until l.n).flatMap { i -> (i + 1 until l.n).map { j -> dist(l, l.ids[i], l.ids[j]) } }.average()
        assertTrue("linked $linked < mean pair $all", linked < all)
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
