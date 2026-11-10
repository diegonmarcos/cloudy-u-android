package com.diegonmarcos.superapp.launcher.themes.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1621 — the background shape engine's geometry, proven on the JVM.
 *
 * Every regular polytope has a KNOWN vertex and edge count; if a generating
 * coordinate set is wrong, or the nearest-neighbour edge rule picks up a wrong
 * distance class, these numbers move. So each shape is pinned to its textbook
 * (V, E), the 4D→3D→2D projection is checked to produce finite, framed points,
 * and the auto-cycle index is checked to advance on the shared clock. The
 * renderer draws what this proves; it hand-types no vertex list of its own.
 */
class PolytopeMathTest {

    // Textbook (vertices, edges) for the eleven shapes.
    private val expected = mapOf(
        PolytopeMath.TETRAHEDRON to (4 to 6),
        PolytopeMath.CUBE to (8 to 12),
        PolytopeMath.OCTAHEDRON to (6 to 12),
        PolytopeMath.DODECAHEDRON to (20 to 30),
        PolytopeMath.ICOSAHEDRON to (12 to 30),
        PolytopeMath.CELL5 to (5 to 10),
        PolytopeMath.TESSERACT to (16 to 32),
        PolytopeMath.CELL16 to (8 to 24),
        PolytopeMath.CELL24 to (24 to 96),
        PolytopeMath.CELL600 to (120 to 720),
        PolytopeMath.CELL120 to (600 to 1200),
    )

    @Test fun everyDeclaredShapeHasAMesh() {
        assertEquals(expected.keys, PolytopeMath.SHAPE_IDS.toSet())
        assertEquals("the default is the cube", PolytopeMath.CUBE, PolytopeMath.DEFAULT_SHAPE)
    }

    @Test fun vertexAndEdgeCountsAreTextbook() {
        for ((id, ve) in expected) {
            val m = PolytopeMath.mesh(id)
            assertEquals("$id vertices", ve.first, m.verts.size)
            assertEquals("$id edges", ve.second, m.edges.size)
            // Each shape is 3D (w all 0) or genuinely 4D (some w != 0).
            val anyW = m.verts.any { kotlin.math.abs(it[3]) > 1e-4f }
            assertEquals("$id dim/w agree", m.dim == 4, anyW)
        }
    }

    @Test fun cubeIsThePlatonicDefaultWithEightCornersTwelveEdges() {
        val cube = PolytopeMath.mesh(PolytopeMath.CUBE)
        assertEquals(3, cube.dim)
        assertEquals(8, cube.verts.size)
        assertEquals(12, cube.edges.size)
    }

    @Test fun unknownShapeFallsBackToCube() {
        val m = PolytopeMath.mesh("not-a-shape")
        assertEquals(8, m.verts.size)
        assertEquals(12, m.edges.size)
    }

    @Test fun edgesReferenceRealVerticesAndNoSelfLoops() {
        for (id in PolytopeMath.SHAPE_IDS) {
            val m = PolytopeMath.mesh(id)
            val seen = HashSet<Long>()
            for (e in m.edges) {
                assertTrue("$id edge endpoints in range", e[0] in m.verts.indices && e[1] in m.verts.indices)
                assertTrue("$id no self loop", e[0] != e[1])
                val key = minOf(e[0], e[1]).toLong() * 100000 + maxOf(e[0], e[1])
                assertTrue("$id no duplicate edge", seen.add(key))
            }
        }
    }

    @Test fun projectionIsFiniteAndFramedForEveryShape() {
        val w = 360f; val h = 360f
        for (id in PolytopeMath.SHAPE_IDS) {
            val m = PolytopeMath.mesh(id)
            for (t in listOf(0.0, 2_500.0, 9_000.0, 17_000.0)) {
                val r = PolytopeMath.project(m, t, w, h)
                for (i in m.verts.indices) {
                    assertTrue("$id x finite", r.x[i].isFinite())
                    assertTrue("$id y finite", r.y[i].isFinite())
                    // Stays within a sane multiple of the host box (no runaway projection).
                    assertTrue("$id x framed @$t: ${r.x[i]}", r.x[i] > -2 * w && r.x[i] < 3 * w)
                    assertTrue("$id y framed @$t: ${r.y[i]}", r.y[i] > -2 * h && r.y[i] < 3 * h)
                }
            }
        }
    }

    @Test fun autoCycleAdvancesOnTheSharedClock() {
        // 3 shapes, 5-minute cadence.
        val min = 5; val n = 3
        assertEquals(0, PolytopeMath.cycleIndex(n, min, 0.0))
        assertEquals(0, PolytopeMath.cycleIndex(n, min, 4.0 * 60_000))
        assertEquals(1, PolytopeMath.cycleIndex(n, min, 5.0 * 60_000))
        assertEquals(2, PolytopeMath.cycleIndex(n, min, 11.0 * 60_000))
        assertEquals(0, PolytopeMath.cycleIndex(n, min, 15.0 * 60_000)) // wraps
        // Guards: a single shape or a sub-minute cadence never cycles.
        assertEquals(0, PolytopeMath.cycleIndex(1, min, 99.0 * 60_000))
        assertEquals(0, PolytopeMath.cycleIndex(n, 0, 99.0 * 60_000))
    }
}
