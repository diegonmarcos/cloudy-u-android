package com.diegonmarcos.superapp.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Shows a composited camera frame the way PreviewView's FILL_CENTER shows the live stream (scaled to cover the view, centred,
 * the overflow cropped), mirrored for the front camera. Nothing is drawn until the first frame arrives, so the live preview
 * underneath shows through. [show] may be called from any thread.
 */
class CompositeFrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    @Volatile private var frame: Bitmap? = null
    @Volatile private var mirrored = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun show(bitmap: Bitmap?, mirror: Boolean) {
        frame = bitmap
        mirrored = mirror
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = frame ?: return
        if (bmp.isRecycled || width <= 0 || height <= 0) return
        val s = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = (bmp.width * s).toInt()
        val dh = (bmp.height * s).toInt()
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        canvas.save()
        if (mirrored) canvas.scale(-1f, 1f, width / 2f, height / 2f)
        canvas.drawBitmap(bmp, null, Rect(left, top, left + dw, top + dh), paint)
        canvas.restore()
    }
}
