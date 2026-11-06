package com.diegonmarcos.superapp.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.AbstractComposeView

/**
 * Shows a composited camera frame the way PreviewView's FILL_CENTER shows the live stream (scaled to cover the view, centred,
 * the overflow cropped), mirrored for the front camera. A Compose host; nothing is drawn until the first frame arrives, so the
 * live preview underneath shows through. [show] may be called from any thread.
 */
class CompositeFrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AbstractComposeView(context, attrs) {
    private class Shown(val bitmap: Bitmap?, val mirrored: Boolean)

    private var shown by mutableStateOf(Shown(null, false))
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun show(bitmap: Bitmap?, mirror: Boolean) {
        shown = Shown(bitmap, mirror)
    }

    @Composable
    override fun Content() {
        val s = shown
        Canvas(Modifier.fillMaxSize()) {
            val bmp = s.bitmap ?: return@Canvas
            if (bmp.isRecycled) return@Canvas
            val w = size.width.toInt()
            val h = size.height.toInt()
            if (w <= 0 || h <= 0) return@Canvas
            val k = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            val dw = (bmp.width * k).toInt()
            val dh = (bmp.height * k).toInt()
            val left = (w - dw) / 2
            val top = (h - dh) / 2
            val c = drawContext.canvas.nativeCanvas
            c.save()
            if (s.mirrored) c.scale(-1f, 1f, w / 2f, h / 2f)
            c.drawBitmap(bmp, null, Rect(left, top, left + dw, top + dh), paint)
            c.restore()
        }
    }
}
