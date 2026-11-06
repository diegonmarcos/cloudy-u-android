package com.diegonmarcos.superapp.vision

/** An axis-aligned rectangle; no android.graphics.RectF so the maths runs on the JVM. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Where a point or box of an analysed image lands in the view that shows the same camera stream.
 *
 * The analysis image is in SENSOR orientation: [rotationDegrees] (0, 90, 180, 270, clockwise, CameraX's
 * ImageInfo.rotationDegrees) turns it upright, and [mirrored] flips it left-right afterwards (the front camera's
 * preview is a mirror, the analysis image is not). The upright image is then scaled into the view the way
 * PreviewView does: FILL_CENTER covers the view and crops the overflow ([cover] = true), FIT_CENTER letterboxes it.
 * An analysis built with setOutputImageRotationEnabled(true) is already upright: pass rotationDegrees = 0.
 */
object FrameMapping {
    /** The size of an [imageW] x [imageH] image after it is turned upright. */
    fun uprightSize(imageW: Int, imageH: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (norm(rotationDegrees) % 180 == 0) imageW to imageH else imageH to imageW

    /** A point of the sensor-oriented image, in the upright (and, when [mirrored], flipped) image's pixels. */
    fun toUpright(x: Float, y: Float, imageW: Int, imageH: Int, rotationDegrees: Int, mirrored: Boolean): Pair<Float, Float> {
        val (uw, _) = uprightSize(imageW, imageH, rotationDegrees)
        val (ux, uy) = when (norm(rotationDegrees)) {
            90 -> (imageH - y) to x
            180 -> (imageW - x) to (imageH - y)
            270 -> y to (imageW - x)
            else -> x to y
        }
        return (if (mirrored) uw - ux else ux) to uy
    }

    /** A point of the sensor-oriented image, in view pixels. */
    fun pointToView(
        x: Float, y: Float, imageW: Int, imageH: Int, rotationDegrees: Int, mirrored: Boolean,
        viewW: Int, viewH: Int, cover: Boolean = true,
    ): Pair<Float, Float> {
        if (imageW <= 0 || imageH <= 0 || viewW <= 0 || viewH <= 0) return 0f to 0f
        val (uw, uh) = uprightSize(imageW, imageH, rotationDegrees)
        val (ux, uy) = toUpright(x, y, imageW, imageH, rotationDegrees, mirrored)
        val sx = viewW.toFloat() / uw
        val sy = viewH.toFloat() / uh
        val scale = if (cover) maxOf(sx, sy) else minOf(sx, sy)
        val dx = (viewW - uw * scale) / 2f
        val dy = (viewH - uh * scale) / 2f
        return (dx + ux * scale) to (dy + uy * scale)
    }

    /** A box of the sensor-oriented image, in view pixels (rotating a box swaps its corners, so the result is re-ordered). */
    fun boxToView(
        box: Box, imageW: Int, imageH: Int, rotationDegrees: Int, mirrored: Boolean,
        viewW: Int, viewH: Int, cover: Boolean = true,
    ): Box {
        val a = pointToView(box.left, box.top, imageW, imageH, rotationDegrees, mirrored, viewW, viewH, cover)
        val b = pointToView(box.right, box.bottom, imageW, imageH, rotationDegrees, mirrored, viewW, viewH, cover)
        return Box(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    private fun norm(deg: Int): Int = ((deg % 360) + 360) % 360
}
