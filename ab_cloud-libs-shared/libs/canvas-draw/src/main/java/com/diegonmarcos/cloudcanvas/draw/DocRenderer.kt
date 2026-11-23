package com.diegonmarcos.cloudcanvas.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/** Replays a document's layers onto one bitmap per layer; the Compose canvas draws those bitmaps, the PNG export flattens them. */
class DocRenderer {
    private class Entry(var bitmap: Bitmap, var done: List<Op>)

    private val entries = HashMap<Int, Entry>()

    fun bitmap(id: Int): Bitmap? = entries[id]?.bitmap

    /** Brings every layer's bitmap in line with [state]: only the new ops are drawn when a layer just grew, else the layer is replayed. */
    fun sync(state: DocState) {
        val live = state.layers.map { it.id }.toSet()
        entries.keys.filter { it !in live }.forEach { entries.remove(it)?.bitmap?.recycle() }
        for (layer in state.layers) {
            var e = entries[layer.id]
            if (e == null || e.bitmap.width != state.width || e.bitmap.height != state.height) {
                e?.bitmap?.recycle()
                e = Entry(Bitmap.createBitmap(state.width, state.height, Bitmap.Config.ARGB_8888), emptyList())
                entries[layer.id] = e
            }
            val ops = layer.ops
            val prev = e.done
            val grew = ops.size >= prev.size && (prev.isEmpty() || ops[prev.size - 1] === prev[prev.size - 1]) &&
                (prev.size < 2 || ops[0] === prev[0])
            if (grew) {
                if (ops.size > prev.size) {
                    val c = Canvas(e.bitmap)
                    for (i in prev.size until ops.size) draw(c, e.bitmap, ops[i])
                }
            } else {
                e.bitmap.eraseColor(0)
                val c = Canvas(e.bitmap)
                for (op in ops) draw(c, e.bitmap, op)
            }
            e.done = ops
        }
    }

    /** The pixels of every layer for the flatten/eyedropper functions of [Pixels]. */
    fun layerPixels(state: DocState): List<Pixels.LayerPixels> = state.layers.map { l ->
        val px = IntArray(state.width * state.height)
        bitmap(l.id)?.getPixels(px, 0, state.width, 0, 0, state.width, state.height)
        Pixels.LayerPixels(px, l.visible, l.opacity)
    }

    fun flatten(state: DocState): IntArray = Pixels.composite(state.width, state.height, layerPixels(state), state.background)

    fun release() {
        entries.values.forEach { it.bitmap.recycle() }
        entries.clear()
    }

    companion object {
        private fun paint(color: Int, opacity: Float, style: Paint.Style, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = style
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            alpha = (((color ushr 24) / 255f) * opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        /** The stroke width of a tool at brush size [size]. */
        fun widthOf(tool: Tool, size: Float): Float = when (tool) {
            Tool.Pencil -> (size * 0.35f).coerceAtLeast(1f)
            Tool.Marker -> size * 1.6f
            else -> size
        }

        fun smoothPath(pts: List<Pt>): Path {
            val path = Path()
            if (pts.isEmpty()) return path
            path.moveTo(pts[0].x, pts[0].y)
            if (pts.size == 1) { path.lineTo(pts[0].x + 0.01f, pts[0].y); return path }
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i].x + pts[i + 1].x) / 2f
                val my = (pts[i].y + pts[i + 1].y) / 2f
                path.quadTo(pts[i].x, pts[i].y, mx, my)
            }
            path.lineTo(pts.last().x, pts.last().y)
            return path
        }

        fun draw(c: Canvas, bmp: Bitmap, op: Op) {
            when (op) {
                is Op.Stroke -> drawStroke(c, op)
                is Op.Shape -> {
                    val p = paint(op.color, op.opacity, if (op.filled && op.kind != Tool.Line) Paint.Style.FILL else Paint.Style.STROKE, op.size)
                    val r = RectF(minOf(op.a.x, op.b.x), minOf(op.a.y, op.b.y), maxOf(op.a.x, op.b.x), maxOf(op.a.y, op.b.y))
                    when (op.kind) {
                        Tool.Line -> c.drawLine(op.a.x, op.a.y, op.b.x, op.b.y, p)
                        Tool.Ellipse -> c.drawOval(r, p)
                        else -> c.drawRect(r, p)
                    }
                }
                is Op.Text -> {
                    val p = paint(op.color, op.opacity, Paint.Style.FILL, 1f)
                    p.textSize = (op.size * 3f).coerceAtLeast(12f)
                    c.drawText(op.text, op.at.x, op.at.y, p)
                }
                is Op.Fill -> {
                    val w = bmp.width
                    val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val a = (((op.color ushr 24) / 255f) * op.opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val col = (a shl 24) or (op.color and 0xFFFFFF)
                    if (Pixels.floodFill(px, w, h, op.at.x.toInt(), op.at.y.toInt(), col, op.tolerance) > 0) bmp.setPixels(px, 0, w, 0, 0, w, h)
                }
                is Op.Image -> {
                    val src = op.bitmap as? Bitmap ?: return
                    val s = minOf(bmp.width.toFloat() / src.width, bmp.height.toFloat() / src.height)
                    val dw = src.width * s
                    val dh = src.height * s
                    val l = (bmp.width - dw) / 2f
                    val t = (bmp.height - dh) / 2f
                    c.drawBitmap(src, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        }

        private fun drawStroke(c: Canvas, op: Op.Stroke) {
            val erase = op.tool == Tool.Eraser
            val w = widthOf(op.tool, op.size)
            val opacity = if (op.tool == Tool.Marker) op.opacity * 0.6f else op.opacity
            val p = paint(if (erase) 0xFF000000.toInt() else op.color, if (erase) 1f else opacity, Paint.Style.STROKE, w)
            if (erase) p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            if (op.tool == Tool.Marker) p.strokeCap = Paint.Cap.SQUARE
            if (op.pts.size == 1) {
                val q = Paint(p).apply { style = Paint.Style.FILL }
                c.drawCircle(op.pts[0].x, op.pts[0].y, maxOf(w * op.pts[0].p, 1f) / 2f, q)
                return
            }
            if (op.pts.any { it.p != 1f }) {
                // pressure: each segment at the width of its mean pressure
                for (i in 1 until op.pts.size) {
                    val a = op.pts[i - 1]
                    val b = op.pts[i]
                    p.strokeWidth = maxOf(w * (a.p + b.p) / 2f, 1f)
                    c.drawLine(a.x, a.y, b.x, b.y, p)
                }
            } else {
                c.drawPath(smoothPath(op.pts), p)
            }
        }
    }
}
