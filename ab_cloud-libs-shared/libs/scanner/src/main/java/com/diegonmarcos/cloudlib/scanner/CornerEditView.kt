package com.diegonmarcos.cloudlib.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * #1839 the Frame step's canvas: the source photo fitted into the view with the document's four corners as handles the
 * finger can drag (manual corner adjustment). The corners live in the photo's pixels; [onChanged] fires when a drag ends.
 */
class CornerEditView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var corners: List<P> = emptyList()
    private var active = -1
    var onChanged: ((Quad) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4FC3F7"); style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334FC3F7"); style = Paint.Style.FILL }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4FC3F7"); style = Paint.Style.STROKE; strokeWidth = 3f * density }

    fun show(b: Bitmap, q: Quad) { bitmap = b; corners = q.points; invalidate() }

    fun quad(): Quad = Quad(corners[0], corners[1], corners[2], corners[3])

    private fun scale(): Float { val b = bitmap ?: return 1f; return min(width.toFloat() / b.width, height.toFloat() / b.height) }
    private fun offX(): Float { val b = bitmap ?: return 0f; return (width - b.width * scale()) / 2 }
    private fun offY(): Float { val b = bitmap ?: return 0f; return (height - b.height * scale()) / 2 }
    private fun toView(p: P) = (p.x * scale() + offX()).toFloat() to (p.y * scale() + offY()).toFloat()
    private fun toImage(x: Float, y: Float): P {
        val b = bitmap!!
        return P(((x - offX()) / scale()).toDouble().coerceIn(0.0, b.width - 1.0), ((y - offY()) / scale()).toDouble().coerceIn(0.0, b.height - 1.0))
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        val s = scale()
        c.save(); c.translate(offX(), offY()); c.scale(s, s); c.drawBitmap(b, 0f, 0f, null); c.restore()
        if (corners.size != 4) return
        val path = Path()
        corners.forEachIndexed { i, p -> val (x, y) = toView(p); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        c.drawPath(path, fill); c.drawPath(path, line)
        for (p in corners) { val (x, y) = toView(p); c.drawCircle(x, y, 11 * density, handle); c.drawCircle(x, y, 11 * density, ring) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || corners.size != 4) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1; var bd = 36 * density
                corners.forEachIndexed { i, p -> val (x, y) = toView(p); val d = hypot(x - e.x, y - e.y); if (d < bd) { bd = d; best = i } }
                active = best
                if (active >= 0) parent.requestDisallowInterceptTouchEvent(true)
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                corners = corners.toMutableList().also { it[active] = toImage(e.x, e.y) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active >= 0) {
                active = -1
                // A bow-tie cannot be flattened: only a convex page is reported.
                val q = quad()
                if (q.isConvex()) onChanged?.invoke(q)
            }
        }
        return true
    }
}
