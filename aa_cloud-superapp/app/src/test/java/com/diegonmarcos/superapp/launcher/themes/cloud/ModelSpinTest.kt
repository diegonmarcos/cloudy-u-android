package com.diegonmarcos.superapp.launcher.themes.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1685 the home model's Auto-rotate, proven on the JVM through [ModelSpin]
 * (the one orientation state the renderer draws from).
 */
class ModelSpinTest {

    private fun ModelSpin.snap() = listOf(rotX.toDouble(), rotY.toDouble(), a4, b4)

    @Test fun startsAtTheOldFirstFrame() {
        val s = ModelSpin()
        assertEquals(PolytopeMath.angleX(0.0), s.rotX, 1e-6f)
        assertEquals(PolytopeMath.angleY(0.0), s.rotY, 1e-6f)
        // The default first frame is the same picture the clock-driven renderer drew at elapsed=0.
        val m = PolytopeMath.mesh(PolytopeMath.TESSERACT)
        val old = PolytopeMath.project(m, 0.0, 400f, 400f)
        val now = PolytopeMath.project(m, 0.0, 400f, 400f, s.rotX, s.rotY, s.a4, s.b4)
        for (i in old.x.indices) { assertEquals(old.x[i], now.x[i], 1e-3f); assertEquals(old.y[i], now.y[i], 1e-3f) }
    }

    @Test fun autoRotateTurnsBoth3dAnd4d() {
        val s = ModelSpin(); val before = s.snap()
        assertTrue("auto-rotate keeps the loop alive", s.step(16.0, autoRotate = true))
        val after = s.snap()
        for (i in 0..3) assertNotEquals("angle $i moved", before[i], after[i], 1e-9)
        // 3D model: its projection changes (it used to be frozen with drag-to-spin on).
        val cube = PolytopeMath.mesh(PolytopeMath.CUBE)
        val a = PolytopeMath.project(cube, 0.0, 400f, 400f, before[0].toFloat(), before[1].toFloat(), before[2], before[3])
        val b = PolytopeMath.project(cube, 0.0, 400f, 400f, s.rotX, s.rotY, s.a4, s.b4)
        assertNotEquals(a.x[0], b.x[0], 1e-4f)
    }

    @Test fun offStopsBothAndStopsTheLoop() {
        val s = ModelSpin(); val before = s.snap()
        assertFalse("off + idle = no render loop", s.step(16.0, autoRotate = false))
        assertEquals(before, s.snap())
        repeat(100) { s.step(16.0, false) }
        assertEquals("the model stays where it was left", before, s.snap())
    }

    @Test fun dragWorksWithAutoRotateOn() {
        val s = ModelSpin()
        s.step(500.0, true)
        s.dragStart(); s.dragBy(30f, -10f)
        val held = s.snap()
        assertTrue("dragging keeps frames coming", s.step(16.0, true))
        assertEquals("while dragging the user owns the orientation", held, s.snap())
        s.dragEnd()
        // Resume from the user's orientation: the next frames continue from `held` (plus coast), no snap.
        s.step(16.0, true)
        val after = s.snap()
        for (i in 0..3) assertTrue("angle $i resumes near the drag, not the clock", Math.abs(after[i] - held[i]) < 0.5)
        assertNotEquals(held, after)
    }

    @Test fun dragWorksWithAutoRotateOffAndThenStays() {
        val s = ModelSpin()
        s.dragStart(); s.dragBy(40f, 0f); s.dragEnd()
        var frames = 0
        while (s.step(16.0, false)) { frames++; assertTrue("coast ends", frames < 1000) }
        val left = s.snap()
        assertNotEquals(PolytopeMath.ROT_Y0.toDouble(), left[1], 1e-6)
        assertEquals("4D planes untouched by a 3D drag", PolytopeMath.ROT_Y0.toDouble(), left[2], 1e-9)
        repeat(50) { assertFalse(s.step(16.0, false)) }
        assertEquals("stays where the user left it", left, s.snap())
    }

    @Test fun badOrHugeFrameDeltasNeverJump() {
        val s = ModelSpin(); val b = s.snap()
        s.step(Double.NaN, true); s.step(-5.0, true)
        assertEquals(b, s.snap())
        s.step(60_000.0, true)
        val tau = 2 * Math.PI
        assertEquals(b[1] + ModelSpin.MAX_DT_MS / PolytopeMath.PERIOD_Y_MS * tau, s.rotY.toDouble(), 1e-5)
    }
}
