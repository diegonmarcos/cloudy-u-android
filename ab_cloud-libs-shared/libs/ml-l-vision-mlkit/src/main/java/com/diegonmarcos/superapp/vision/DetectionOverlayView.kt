package com.diegonmarcos.superapp.vision

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.AbstractComposeView

/**
 * The boxes of the latest detection over the live preview: a frame per object with its label, confidence and tracking
 * id. A Compose host (like libs:bottomnav's island); positions go through [FrameMapping], so rotation, the front camera's
 * mirror and the preview's crop are all the one tested mapping.
 */
class DetectionOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AbstractComposeView(context, attrs) {
    private class Frame(val detections: List<Detection>, val w: Int, val h: Int, val rotation: Int, val mirrored: Boolean)

    private var frame by mutableStateOf(Frame(emptyList(), 0, 0, 0, false))

    /** Show [list], boxes in a [w] x [h] frame turned upright by [rotationDegrees] and flipped when [mirror]. */
    fun show(list: List<Detection>, w: Int, h: Int, rotationDegrees: Int = 0, mirror: Boolean = false) {
        frame = Frame(list, w, h, rotationDegrees, mirror)
    }

    fun clear() = show(emptyList(), 0, 0)

    @Composable
    override fun Content() {
        val f = frame
        val density = resources.displayMetrics.density
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 13 * density }
        Canvas(Modifier.fillMaxSize()) {
            val vw = size.width.toInt()
            val vh = size.height.toInt()
            f.detections.forEach { d ->
                val r = FrameMapping.boxToView(d.box, f.w, f.h, f.rotation, f.mirrored, vw, vh)
                drawRect(Color.Yellow, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(3 * density))
                val caption = d.caption()
                val tw = text.measureText(caption)
                val top = maxOf(0f, r.top - text.textSize - 6 * density)
                drawRect(Color(0xAA000000), Offset(r.left, top), Size(tw + 8 * density, text.textSize + 6 * density))
                drawContext.canvas.nativeCanvas.drawText(caption, r.left + 4 * density, top + text.textSize, text)
            }
        }
    }
}
