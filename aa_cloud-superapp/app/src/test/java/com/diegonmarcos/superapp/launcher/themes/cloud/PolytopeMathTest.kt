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

    // Textbook (vertices, edges) for every shape (#1915: 27 = the eleven regular polytopes + the sixteen additions).
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
        // Kepler-Poinsot solids
        PolytopeMath.GREAT_ICOSAHEDRON to (12 to 30),
        PolytopeMath.GREAT_DODECAHEDRON to (12 to 30),
        PolytopeMath.SMALL_STELLATED_DODECAHEDRON to (12 to 30),
        PolytopeMath.GREAT_STELLATED_DODECAHEDRON to (20 to 30),
        // hexagon hybrids (3D)
        PolytopeMath.HEXAGONAL_PRISM to (12 to 18),
        PolytopeMath.HEXAGONAL_PYRAMID to (7 to 12),
        PolytopeMath.TRUNCATED_TETRAHEDRON to (12 to 18),
        PolytopeMath.TRUNCATED_OCTAHEDRON to (24 to 36),
        PolytopeMath.TRUNCATED_ICOSAHEDRON to (60 to 90),
        // Schlaefli-Hess star polychora (on the 600-cell's 120 vertices) + the truncations
        PolytopeMath.ICOSAHEDRAL_120_CELL to (120 to 720),
        PolytopeMath.GREAT_120_CELL to (120 to 720),
        PolytopeMath.GRAND_120_CELL to (120 to 720),
        PolytopeMath.SMALL_STELLATED_120_CELL to (120 to 720),
        PolytopeMath.GREAT_STELLATED_120_CELL to (120 to 720),
        PolytopeMath.TRUNCATED_5_CELL to (20 to 40),
        PolytopeMath.TRUNCATED_16_CELL to (48 to 120),
    )

    @Test fun everyDeclaredShapeHasAMesh() {
        assertEquals(expected.keys, PolytopeMath.SHAPE_IDS.toSet())
        assertEquals("no id is listed twice", PolytopeMath.SHAPE_IDS.size, PolytopeMath.SHAPE_IDS.toSet().size)
        assertEquals(expected.keys, PolytopeMath.FACES.keys)
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
            // Phone budget: no shape draws more edges per frame than the 120-cell always did.
            assertTrue("$id edge budget", m.edges.size <= 1200)
        }
    }

    /** Face data is DERIVED from the geometry (chordless planar cycles of the edge graph) and must equal the
     *  declared [PolytopeMath.FACES]; Euler's formula then ties V, E and F together for the 3D solids. */
    @Test fun faceCountsAreDerivedFromTheGeometryAndMatchTheDeclaredData() {
        for (id in PolytopeMath.SHAPE_IDS) {
            val m = PolytopeMath.mesh(id)
            for (f in PolytopeMath.FACES.getValue(id)) {
                assertEquals("$id ${if (f.star) "pentagram" else "${f.sides}-gon"} faces",
                    f.count, PolytopeMath.countFaces(m, f.sides, f.star))
            }
        }
    }

    @Test fun eulerCharacteristicOfTheThreeDimensionalSolids() {
        // chi = V - E + F: 2 for the convex solids, 2 for the great icosahedron and great stellated dodecahedron,
        // -6 for the great dodecahedron and the small stellated dodecahedron (Kepler-Poinsot).
        val chi = mapOf(
            PolytopeMath.GREAT_DODECAHEDRON to -6, PolytopeMath.SMALL_STELLATED_DODECAHEDRON to -6,
        )
        for (id in PolytopeMath.SHAPE_IDS) {
            val m = PolytopeMath.mesh(id)
            if (m.dim != 3) continue
            val f = PolytopeMath.FACES.getValue(id).sumOf { it.count }
            assertEquals("$id V-E+F", chi[id] ?: 2, m.verts.size - m.edges.size + f)
        }
    }

    @Test fun fourDimensionalCellCountsSatisfyEuler() {
        // V - E + F - C = 0 for a 4-polytope; the truncations pin C (10 and 24 cells).
        val cells = mapOf(PolytopeMath.TRUNCATED_5_CELL to 10, PolytopeMath.TRUNCATED_16_CELL to 24,
            PolytopeMath.CELL5 to 5, PolytopeMath.TESSERACT to 8, PolytopeMath.CELL16 to 16, PolytopeMath.CELL24 to 24,
            PolytopeMath.CELL600 to 600, PolytopeMath.CELL120 to 120)
        for ((id, c) in cells) {
            val m = PolytopeMath.mesh(id)
            val f = PolytopeMath.FACES.getValue(id).sumOf { it.count }
            assertEquals("$id V-E+F-C", 0, m.verts.size - m.edges.size + f - c)
        }
    }

    @Test fun starShapesShareAVertexSetAndDifferOnlyInTheEdgeClass() {
        val ico = PolytopeMath.mesh(PolytopeMath.ICOSAHEDRON)
        val gd = PolytopeMath.mesh(PolytopeMath.GREAT_DODECAHEDRON)
        val gi = PolytopeMath.mesh(PolytopeMath.GREAT_ICOSAHEDRON)
        assertEquals(ico.verts.size, gd.verts.size)
        // The great dodecahedron draws the icosahedron's own edges; the great icosahedron the longer second class.
        assertEquals(ico.edges.map { it.toList() }.toSet(), gd.edges.map { it.toList() }.toSet())
        assertTrue(gi.edges.map { it.toList() }.toSet() != ico.edges.map { it.toList() }.toSet())
        // 4D: the 120 vertices of the 600-cell carry the star polychora; stellated ones use the 108-degree class.
        val c600 = PolytopeMath.mesh(PolytopeMath.CELL600)
        val ss = PolytopeMath.mesh(PolytopeMath.SMALL_STELLATED_120_CELL)
        assertEquals(c600.verts.size, ss.verts.size)
        assertTrue(c600.edges.map { it.toList() }.toSet() != ss.edges.map { it.toList() }.toSet())
    }

    @Test fun aliasesResolveToTheSameMesh() {
        assertTrue(PolytopeMath.mesh("hypercube") === PolytopeMath.mesh(PolytopeMath.TESSERACT))
        assertTrue(PolytopeMath.mesh("soccer_ball") === PolytopeMath.mesh(PolytopeMath.TRUNCATED_ICOSAHEDRON))
        for (target in PolytopeMath.ALIASES.values) assertTrue(target in PolytopeMath.SHAPE_IDS)
    }

    /** build.json's catalogue (labels, groups, declared counts) against the generated geometry. */
    @Test fun declaredCatalogueMatchesTheGeometry() {
        val f = listOf("../build.json", "build.json", "aa_cloud-superapp/build.json").map { java.io.File(it) }.firstOrNull { it.isFile }
            ?: return   // no checkout layout to read from: the shell tester test-launcher-ui-subpages-1915.sh covers it
        val bg = org.json.JSONObject(f.readText()).getJSONObject("ui").getJSONObject("launcher_settings").getJSONObject("background")
        val shapes = bg.getJSONArray("shapes")
        assertEquals(PolytopeMath.SHAPE_IDS.size, shapes.length())
        for (i in 0 until shapes.length()) {
            val s = shapes.getJSONObject(i); val id = s.getString("id")
            assertEquals("catalogue order", PolytopeMath.SHAPE_IDS[i], id)
            val m = PolytopeMath.mesh(id)
            assertEquals("$id declared vertices", m.verts.size, s.getInt("vertices"))
            assertEquals("$id declared edges", m.edges.size, s.getInt("edges"))
            val faces = PolytopeMath.FACES.getValue(id).sumOf { it.count }
            assertEquals("$id declared faces", faces, s.getInt("faces"))
            assertTrue("$id label ends with its face count", s.getString("label").endsWith(" / $faces faces"))
            assertEquals("$id dim", m.dim, s.getInt("dim"))
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
