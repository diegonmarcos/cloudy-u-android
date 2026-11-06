package com.diegonmarcos.superapp.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The boxes of the latest detection over the live preview: a frame per object with its label, confidence and tracking
 * id. Positions go through [FrameMapping], so rotation, the front camera's mirror and the preview's crop are all the
 * one tested mapping.
 */
class DetectionOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.YELLOW }
    private val fill = Paint().apply { color = 0xAA000000.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * density }

    private var detections: List<Detection> = emptyList()
    private var frameW = 0
    private var frameH = 0
    private var rotation = 0
    private var mirrored = false

    /** Show [list], boxes in a [w] x [h] frame turned upright by [rotationDegrees] and flipped when [mirror]. */
    fun show(list: List<Detection>, w: Int, h: Int, rotationDegrees: Int = 0, mirror: Boolean = false) {
        detections = list
        frameW = w
        frameH = h
        rotation = rotationDegrees
        mirrored = mirror
        invalidate()
    }

    fun clear() = show(emptyList(), 0, 0)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections.forEach { d ->
            val r = FrameMapping.boxToView(d.box, frameW, frameH, rotation, mirrored, width, height)
            canvas.drawRect(r.left, r.top, r.right, r.bottom, stroke)
            val caption = d.caption()
            val tw = text.measureText(caption)
            val top = maxOf(0f, r.top - text.textSize - 6 * density)
            canvas.drawRect(r.left, top, r.left + tw + 8 * density, top + text.textSize + 6 * density, fill)
            canvas.drawText(caption, r.left + 4 * density, top + text.textSize, text)
        }
    }
}
