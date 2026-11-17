package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.draw.CanvasPresets
import com.diegonmarcos.cloudcanvas.draw.DocState
import com.diegonmarcos.cloudcanvas.draw.DrawModel
import com.diegonmarcos.cloudcanvas.draw.ExportNames
import com.diegonmarcos.cloudcanvas.draw.Op
import com.diegonmarcos.cloudcanvas.draw.Pixels
import com.diegonmarcos.cloudcanvas.draw.PngEncoder
import com.diegonmarcos.cloudcanvas.draw.Pt
import com.diegonmarcos.cloudcanvas.draw.RecentColors
import com.diegonmarcos.cloudcanvas.draw.Tool
import java.util.zip.CRC32
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawTest {
    private fun stroke(x: Float) = Op.Stroke(Tool.Brush, 0xFF000000.toInt(), 10f, 1f, listOf(Pt(x, 0f), Pt(x + 5, 5f)))
    private fun model() = DrawModel(DocState.blank(8, 8))

    // ── stroke / undo model ────────────────────────────────────────────────
    @Test fun strokeIsAddedToTheActiveLayer() {
        val m = model()
        m.addOp(stroke(1f))
        assertEquals(1, m.state.active.ops.size)
        assertTrue(m.canUndo)
    }

    @Test fun undoAndRedoWalkTheHistory() {
        val m = model()
        m.addOp(stroke(1f)); m.addOp(stroke(2f)); m.addOp(stroke(3f))
        assertTrue(m.undo()); assertTrue(m.undo())
        assertEquals(1, m.state.active.ops.size)
        assertTrue(m.redo())
        assertEquals(2, m.state.active.ops.size)
        assertTrue(m.undo()); assertTrue(m.undo())
        assertFalse(m.undo())
        assertEquals(0, m.state.active.ops.size)
    }

    @Test fun aNewStrokeClearsTheRedoStack() {
        val m = model()
        m.addOp(stroke(1f)); m.addOp(stroke(2f))
        m.undo()
        assertTrue(m.canRedo)
        m.addOp(stroke(9f))
        assertFalse(m.canRedo)
        assertFalse(m.redo())
    }

    @Test fun historyIsBounded() {
        val m = DrawModel(DocState.blank(8, 8), maxHistory = 5)
        repeat(20) { m.addOp(stroke(it.toFloat())) }
        assertEquals(5, m.undoDepth)
    }

    @Test fun undoCoversLayerEditsToo() {
        val m = model()
        m.addLayer()
        m.setVisible(1, false)
        m.undo()
        assertTrue(m.state.layers.first { it.id == 1 }.visible)
        m.undo()
        assertEquals(1, m.state.layers.size)
    }

    @Test fun stylusPressureIsKeptOnThePoints() {
        val m = model()
        m.addOp(Op.Stroke(Tool.Pencil, 0, 4f, 1f, listOf(Pt(0f, 0f, 0.2f), Pt(1f, 1f, 0.9f))))
        assertEquals(0.9f, (m.state.active.ops[0] as Op.Stroke).pts[1].p, 0f)
    }

    // ── layer ops ──────────────────────────────────────────────────────────
    @Test fun addLayerMakesItActiveOnTop() {
        val m = model()
        m.addLayer()
        assertEquals(listOf(1, 2), m.state.layers.map { it.id })
        assertEquals(2, m.state.activeId)
        m.addOp(stroke(1f))
        assertEquals(0, m.state.layers[0].ops.size)
        assertEquals(1, m.state.layers[1].ops.size)
    }

    @Test fun hideAndOpacity() {
        val m = model()
        m.setVisible(1, false)
        m.setOpacity(1, 3f)
        assertFalse(m.state.layers[0].visible)
        assertEquals(1f, m.state.layers[0].opacity, 0f)
        m.setOpacity(1, 0.4f)
        assertEquals(0.4f, m.state.layers[0].opacity, 0f)
    }

    @Test fun reorderMovesALayerAndClamps() {
        val m = model()
        m.addLayer(); m.addLayer()
        m.move(1, 2)
        assertEquals(listOf(2, 3, 1), m.state.layers.map { it.id })
        m.move(1, -5)
        assertEquals(listOf(1, 2, 3), m.state.layers.map { it.id })
        m.move(3, 99)
        assertEquals(listOf(1, 2, 3), m.state.layers.map { it.id })
    }

    @Test fun theLastLayerCannotBeRemovedAndTheActiveFallsBack() {
        val m = model()
        assertFalse(m.removeLayer(1))
        m.addLayer(); m.addLayer()
        assertTrue(m.removeLayer(3))
        assertEquals(2, m.state.activeId)
        assertEquals(2, m.state.layers.size)
    }

    @Test fun selectingALayerIsNotAnEdit() {
        val m = model()
        m.addLayer()
        val depth = m.undoDepth
        m.select(1)
        assertEquals(1, m.state.activeId)
        assertEquals(depth, m.undoDepth)
    }

    @Test fun openedPictureBecomesALayerInOneStep() {
        val m = model()
        m.addLayer("Picture", listOf(Op.Image(Any(), 4, 4)))
        assertEquals("Picture", m.state.active.name)
        m.undo()
        assertEquals(1, m.state.layers.size)
    }

    @Test fun newDocumentResetsTheHistory() {
        val m = model()
        m.addOp(stroke(1f))
        val p = CanvasPresets.all[2]
        m.newDocument(p.width, p.height)
        assertFalse(m.canUndo)
        assertEquals(p.width to p.height, m.state.width to m.state.height)
        assertTrue(CanvasPresets.all.size >= 4)
    }

    @Test fun recentColorsAreMostRecentFirstWithoutDuplicates() {
        val r = RecentColors(3)
        r.use(1); r.use(2); r.use(3); r.use(1); r.use(4)
        assertEquals(listOf(4, 1, 3), r.list)
        assertEquals(r.list, RecentColors.decode(r.encode(), 3).list)
    }

    // ── compositing and fill ───────────────────────────────────────────────
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun compositeHonoursVisibilityOrderAndOpacity() {
        val bottom = Pixels.LayerPixels(intArrayOf(red), true, 1f)
        val top = Pixels.LayerPixels(intArrayOf(blue), true, 1f)
        assertEquals(blue, Pixels.composite(1, 1, listOf(bottom, top))[0])
        assertEquals(red, Pixels.composite(1, 1, listOf(bottom, Pixels.LayerPixels(intArrayOf(blue), false, 1f)))[0])
        val half = Pixels.composite(1, 1, listOf(Pixels.LayerPixels(intArrayOf(blue), true, 0.5f)), 0xFFFFFFFF.toInt())[0]
        assertEquals(0xFF, half ushr 24)
        assertEquals(0x80, (half shr 16) and 0xFF)
        assertEquals(0xFF, half and 0xFF)
    }

    @Test fun floodFillStopsAtABoundary() {
        val px = IntArray(16) { 0 }
        for (y in 0 until 4) px[y * 4 + 2] = 0xFF000000.toInt()
        val n = Pixels.floodFill(px, 4, 4, 0, 0, red, 0)
        assertEquals(8, n)
        assertEquals(red, px[0]); assertEquals(0, px[3])
    }

    // ── PNG export path ────────────────────────────────────────────────────
    /** A minimal PNG reader (8-bit RGBA, filter 0): checks every chunk CRC, inflates the IDAT and returns width, height and ARGB pixels. */
    private class Decoded(val width: Int, val height: Int, val px: IntArray) {
        fun getRGB(x: Int, y: Int) = px[y * width + x]
    }

    private fun decode(b: ByteArray): Decoded {
        fun i32(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        var at = 8
        var w = 0; var h = 0
        val idat = java.io.ByteArrayOutputStream()
        while (at < b.size) {
            val len = i32(at)
            val type = String(b, at + 4, 4, Charsets.US_ASCII)
            val crc = CRC32(); crc.update(b, at + 4, 4 + len)
            assertEquals("crc of $type", crc.value.toInt(), i32(at + 8 + len))
            if (type == "IHDR") { w = i32(at + 8); h = i32(at + 12); assertEquals(8, b[at + 16].toInt()); assertEquals(6, b[at + 17].toInt()) }
            if (type == "IDAT") idat.write(b, at + 8, len)
            at += 12 + len
        }
        val raw = ByteArray(h * (1 + w * 4))
        val inf = Inflater(); inf.setInput(idat.toByteArray()); assertEquals(raw.size, inf.inflate(raw)); inf.end()
        val px = IntArray(w * h)
        for (y in 0 until h) {
            assertEquals(0, raw[y * (1 + w * 4)].toInt())
            for (x in 0 until w) {
                val o = y * (1 + w * 4) + 1 + x * 4
                px[y * w + x] = ((raw[o + 3].toInt() and 0xFF) shl 24) or ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
            }
        }
        return Decoded(w, h, px)
    }
    @Test fun pngRoundTripsThroughAnImageDecoder() {
        val w = 5; val h = 3
        val px = IntArray(w * h) { if (it % 2 == 0) red else 0x80003300.toInt() }
        val bytes = PngEncoder.encode(w, h, px)
        assertEquals(0x89, bytes[0].toInt() and 0xFF)
        assertEquals("PNG", String(bytes, 1, 3, Charsets.US_ASCII))
        val img = decode(bytes)
        assertEquals(w, img.width); assertEquals(h, img.height)
        assertEquals(red, img.getRGB(0, 0))
        assertEquals(0x80003300.toInt(), img.getRGB(1, 0))
    }

    @Test fun exportedPngIsTheFlattenedLayers() {
        val layers = listOf(Pixels.LayerPixels(intArrayOf(red, 0), true, 1f), Pixels.LayerPixels(intArrayOf(0, blue), true, 1f))
        val flat = Pixels.composite(2, 1, layers)
        val img = decode(PngEncoder.encode(2, 1, flat))
        assertEquals(red, img.getRGB(0, 0))
        assertEquals(blue, img.getRGB(1, 0))
    }

    @Test fun exportNamesAreUtcStampsAndDiffer() {
        assertEquals("cloud-canvas-20260101-000000.png", ExportNames.png(1767225600))
        assertEquals("cloud-canvas-mix-20260101-000001.wav", ExportNames.wav(1767225601))
        assertTrue(ExportNames.png(10) != ExportNames.png(11))
    }
}
