package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.draw.Brushes
import com.diegonmarcos.cloudcanvas.draw.ColorWheel
import com.diegonmarcos.cloudcanvas.draw.Pt
import com.diegonmarcos.cloudcanvas.draw.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1865 the Draw page's brush rail, brush dynamics and colour wheel. */
class BrushTest {
    @Test fun theRailHoldsTheSevenBrushesAndTheGridEveryTool() {
        assertEquals(listOf("pencil", "marker", "brush", "airbrush", "ink", "smudge", "eraser"), Brushes.rail.map { it.id })
        assertTrue(Brushes.rail.all { it.tool.isFreehand })
        // rule 16: every earlier tool keeps its place and its ordinal; the new brushes are appended
        assertEquals(listOf("Brush", "Pencil", "Marker", "Eraser", "Fill", "Line", "Rect", "Ellipse", "Text", "Eyedropper"), Tool.entries.take(10).map { it.label })
        assertEquals(Tool.entries, Brushes.grid)
    }

    @Test fun inkIsWideWhenSlowAndThinWhenFast() {
        assertEquals(14f, Brushes.inkWidth(10f, 0f), 1e-4f)
        assertEquals(4f, Brushes.inkWidth(10f, 1000f), 1e-4f)
        assertTrue(Brushes.inkWidth(10f, 5f) > Brushes.inkWidth(10f, 30f))
        assertEquals(1f, Brushes.inkWidth(1f, 1000f), 1e-4f)
    }

    @Test fun airbrushSprayIsDeterministicInsideItsDisc() {
        val pts = listOf(Pt(100f, 100f), Pt(120f, 100f, 0.5f))
        val a = Brushes.airbrushDabs(pts, 20f)
        assertEquals(a, Brushes.airbrushDabs(pts, 20f))
        assertTrue(a.isNotEmpty())
        assertTrue(a.all { d -> pts.any { p -> Math.hypot((d.x - p.x).toDouble(), (d.y - p.y).toDouble()) <= 20.0 + 1e-3 } })
        // more pressure, more dabs
        assertTrue(Brushes.airbrushDabs(listOf(Pt(0f, 0f, 1f)), 40f).size > Brushes.airbrushDabs(listOf(Pt(0f, 0f, 0.2f)), 40f).size)
    }

    @Test fun smudgeMixesColoursChannelByChannel() {
        assertEquals(0xFF000000.toInt(), Brushes.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), Brushes.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), Brushes.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test fun colourWheelMapsAngleToHueAndRadiusToSaturation() {
        val (h0, s0) = ColorWheel.hueSat(150f, 100f, 100f, 100f, 50f)
        assertEquals(0f, h0, 1e-3f); assertEquals(1f, s0, 1e-4f)
        val (h1, s1) = ColorWheel.hueSat(100f, 125f, 100f, 100f, 50f)
        assertEquals(90f, h1, 1e-3f); assertEquals(0.5f, s1, 1e-4f)
        assertEquals(180f, ColorWheel.hueSat(0f, 100f, 100f, 100f, 50f).first, 1e-3f)
        val (x, y) = ColorWheel.pointOf(270f, 0.4f, 100f, 100f, 50f)
        val (h2, s2) = ColorWheel.hueSat(x, y, 100f, 100f, 50f)
        assertEquals(270f, h2, 1e-2f); assertEquals(0.4f, s2, 1e-4f)
    }
}
