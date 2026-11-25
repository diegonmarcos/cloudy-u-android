package com.diegonmarcos.cloudlib.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1839 the corner and perspective maths. */
class GeometryTest {
    private val rect = listOf(P(0.0, 0.0), P(100.0, 0.0), P(100.0, 50.0), P(0.0, 50.0))
    private val skew = listOf(P(10.0, 20.0), P(210.0, 5.0), P(230.0, 160.0), P(0.0, 140.0))

    @Test fun orderedReadsAnyShuffleAsTopLeftClockwise() {
        val q = Quad.ordered(listOf(P(230.0, 160.0), P(10.0, 20.0), P(0.0, 140.0), P(210.0, 5.0)))
        assertEquals(P(10.0, 20.0), q.tl); assertEquals(P(210.0, 5.0), q.tr)
        assertEquals(P(230.0, 160.0), q.br); assertEquals(P(0.0, 140.0), q.bl)
    }

    @Test fun outputSizeIsTheLongerOppositeEdge() {
        val q = Quad(P(0.0, 0.0), P(100.0, 0.0), P(120.0, 50.0), P(0.0, 40.0))
        assertEquals(120 to 54, q.outputSize())
    }

    @Test fun homographyMapsEachCornerOntoItsTarget() {
        val h = Homography.solve(skew, rect)!!
        skew.zip(rect).forEach { (s, d) ->
            val m = Homography.map(h, s.x, s.y)
            assertEquals(d.x, m.x, 1e-6); assertEquals(d.y, m.y, 1e-6)
        }
    }

    @Test fun identityHomographyLeavesPointsAlone() {
        val h = Homography.solve(rect, rect)!!
        val m = Homography.map(h, 37.0, 12.0)
        assertEquals(37.0, m.x, 1e-9); assertEquals(12.0, m.y, 1e-9)
    }

    @Test fun degenerateCornersHaveNoHomography() {
        assertNull(Homography.solve(listOf(P(0.0, 0.0), P(1.0, 1.0), P(2.0, 2.0), P(3.0, 3.0)), rect))
    }

    @Test fun convexityRejectsABowTie() {
        assertTrue(Quad(P(0.0, 0.0), P(10.0, 0.0), P(10.0, 10.0), P(0.0, 10.0)).isConvex())
        assertFalse(Quad(P(0.0, 0.0), P(10.0, 10.0), P(10.0, 0.0), P(0.0, 10.0)).isConvex())
        assertFalse(Quad(P(0.0, 0.0), P(1.0, 1.0), P(2.0, 2.0), P(3.0, 3.0)).isConvex())
    }

    @Test fun edgeTiltIsTheMeanAngleOfTopAndBottom() {
        val q = Quad(P(0.0, 0.0), P(100.0, 10.0), P(100.0, 110.0), P(0.0, 100.0))
        assertEquals(Math.toDegrees(Math.atan2(10.0, 100.0)), q.edgeTiltDegrees(), 1e-9)
    }

    @Test fun rotatingTheQuadFourQuartersIsTheIdentity() {
        val q = Quad(P(1.0, 2.0), P(30.0, 3.0), P(28.0, 40.0), P(2.0, 38.0))
        val r = q.rotatedQuarters(4, 50, 60)
        q.points.zip(r.points).forEach { (a, b) -> assertEquals(a.x, b.x, 1e-9); assertEquals(a.y, b.y, 1e-9) }
    }

    @Test fun oneQuarterTurnMovesTheTopLeftCornerToTheTopRight() {
        // a 50 wide x 60 high image; its top-left pixel is the top-right of the image turned clockwise (now 60 wide)
        val q = Quad.full(50, 60).rotatedQuarters(1, 50, 60)
        assertEquals(P(59.0, 0.0), q.tr)
        assertEquals(P(0.0, 0.0), q.tl)
    }

    @Test fun warpFlattensAPerspectiveSheetToItsColours() {
        // 200x200 black image with a tilted white quad holding a red top-left quarter
        val img = Raster(200, 200, IntArray(40000) { 0xFF000000.toInt() })
        val q = Quad(P(20.0, 30.0), P(180.0, 20.0), P(170.0, 170.0), P(30.0, 180.0))
        val hm = Homography.solve(listOf(P(0.0, 0.0), P(99.0, 0.0), P(99.0, 99.0), P(0.0, 99.0)), q.points)!!
        for (y in 0 until 100) for (x in 0 until 100) {
            val p = Homography.map(hm, x.toDouble(), y.toDouble())
            val c = if (x < 50 && y < 50) Raster.rgb(255, 0, 0) else Raster.WHITE
            for (dy in 0..1) for (dx in 0..1) {
                val px = p.x.toInt() + dx; val py = p.y.toInt() + dy
                if (px in 0..199 && py in 0..199) img.px[py * 200 + px] = c
            }
        }
        val flat = img.warp(q, 100, 100)
        assertNotNull(flat)
        assertEquals(Raster.rgb(255, 0, 0), flat!![10, 10])
        assertEquals(Raster.WHITE, flat[80, 80])
        assertEquals(Raster.WHITE, flat[80, 10])
    }

    @Test fun quarterRotationSwapsTheSides() {
        val r = Raster(3, 2, intArrayOf(1, 2, 3, 4, 5, 6).map { Raster.rgb(it, 0, 0) }.toIntArray())
        val t = r.rotateQuarters(1)
        assertEquals(2, t.w); assertEquals(3, t.h)
        assertEquals(Raster.rgb(4, 0, 0), t[0, 0]); assertEquals(Raster.rgb(1, 0, 0), t[1, 0])
        assertEquals(Raster.rgb(6, 0, 0), t[0, 2]); assertEquals(Raster.rgb(3, 0, 0), t[1, 2])
        val back = t.rotateQuarters(3)
        assertEquals(r.px.toList(), back.px.toList())
    }
}
