package com.diegonmarcos.cloudwiki

import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.superapp.graphview.GraphCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Crash 2026-11-25 (GraphView drawText with negative constraints): a label never starts at or past the canvas edge. */
class GraphLabelOriginTest {
    @Test fun aLabelInsideTheCanvasIsDrawnBesideItsNode() {
        val at = requireNotNull(GraphCamera.labelOrigin(Offset(100f, 100f), 5f, 1080f, 2000f)) { "an in-canvas label must be drawn" }
        assertEquals(108f, at.x, 0.001f)
        assertEquals(93f, at.y, 0.001f)
    }

    @Test fun aLabelPastTheRightOrBottomEdgeIsSkipped() {
        assertNull(GraphCamera.labelOrigin(Offset(1079f, 100f), 5f, 1080f, 2000f))
        assertNull(GraphCamera.labelOrigin(Offset(5000f, 100f), 5f, 1080f, 2000f))
        assertNull(GraphCamera.labelOrigin(Offset(100f, 2500f), 5f, 1080f, 2000f))
    }

    @Test fun aLabelLeftOrAboveTheCanvasIsClampedAndNonFiniteOrEmptyCanvasIsSkipped() {
        val at = GraphCamera.labelOrigin(Offset(-50f, -50f), 5f, 1080f, 2000f)!!
        assertEquals(0f, at.x, 0.001f); assertEquals(0f, at.y, 0.001f)
        assertNull(GraphCamera.labelOrigin(Offset(Float.NaN, 10f), 5f, 1080f, 2000f))
        assertNull(GraphCamera.labelOrigin(Offset(10f, 10f), 5f, 0f, 0f))
    }
}
