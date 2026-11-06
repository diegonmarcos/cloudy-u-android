package com.diegonmarcos.cloudlib.mediaviewer

/** A point in an image, normalised: 0..1 across and down the image it was drawn on. */
data class P(val x: Float, val y: Float)

/** One annotation stroke: an ARGB colour and the normalised points the finger went through. */
data class Stroke(val color: Int, val points: List<P>)

/** How much to cut from each side of the image, as a fraction (0..[ImageEditMath.MAX_CROP]) of its width or height. */
data class CropFractions(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val isFull: Boolean get() = left <= 0f && top <= 0f && right <= 0f && bottom <= 0f
}

/** Where a saved edit goes: [outName] next to the original, in [format] ("jpeg", "png", "webp"); [inPlace] = it replaces the original. */
data class SavePlan(val outName: String, val format: String, val inPlace: Boolean)

/**
 * #1537 the arithmetic of the image editor: EXIF orientation, quarter turns, the crop rectangle, fitting the image into the
 * canvas and the save target. Pure Kotlin (no Android types), driven by the JVM suite; ImageRender and ImageEditor do the drawing.
 */
object ImageEditMath {
    /** Most that can be cut from one side, so at least a tenth of the image is always left. */
    const val MAX_CROP = 0.45f

    /** EXIF orientation tag (1..8) as (clockwise degrees, mirrored). Rotate first, then mirror left-right; unknown tags are upright. */
    fun orientation(tag: Int): Pair<Int, Boolean> = when (tag) {
        2 -> 0 to true
        3 -> 180 to false
        4 -> 180 to true
        5 -> 90 to true
        6 -> 90 to false
        7 -> 270 to true
        8 -> 270 to false
        else -> 0 to false
    }

    /** Width and height after [turns] clockwise quarter turns (any integer, negative = anticlockwise). */
    fun rotatedSize(w: Int, h: Int, turns: Int): Pair<Int, Int> = if (Math.floorMod(turns, 2) == 1) h to w else w to h

    /** A normalised point after [turns] clockwise quarter turns of the image it sits on. */
    fun rotatePoint(p: P, turns: Int): P = when (Math.floorMod(turns, 4)) {
        1 -> P(1f - p.y, p.x)
        2 -> P(1f - p.x, 1f - p.y)
        3 -> P(p.y, 1f - p.x)
        else -> p
    }

    /** The crop as pixels: [x, y, width, height] of a [w] x [h] image, never empty, never outside it. */
    fun cropRect(w: Int, h: Int, c: CropFractions): IntArray {
        val l = (w * c.left.coerceIn(0f, MAX_CROP)).toInt()
        val r = (w * c.right.coerceIn(0f, MAX_CROP)).toInt()
        val t = (h * c.top.coerceIn(0f, MAX_CROP)).toInt()
        val b = (h * c.bottom.coerceIn(0f, MAX_CROP)).toInt()
        val x = l.coerceIn(0, (w - 1).coerceAtLeast(0))
        val y = t.coerceIn(0, (h - 1).coerceAtLeast(0))
        val cw = (w - l - r).coerceIn(1, (w - x).coerceAtLeast(1))
        val ch = (h - t - b).coerceIn(1, (h - y).coerceAtLeast(1))
        return intArrayOf(x, y, cw, ch)
    }

    /** The rectangle [x, y, width, height] an image of [w] x [h] takes when fitted (centred, no distortion) into a [cw] x [ch] canvas. */
    fun fit(w: Int, h: Int, cw: Float, ch: Float): FloatArray {
        if (w <= 0 || h <= 0 || cw <= 0f || ch <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val s = minOf(cw / w, ch / h)
        val dw = w * s
        val dh = h * s
        return floatArrayOf((cw - dw) / 2f, (ch - dh) / 2f, dw, dh)
    }

    /** A canvas position as a normalised image point, held to 0..1 (a touch outside the picture lands on its edge). */
    fun normalize(px: Float, py: Float, fit: FloatArray): P {
        if (fit[2] <= 0f || fit[3] <= 0f) return P(0f, 0f)
        return P(((px - fit[0]) / fit[2]).coerceIn(0f, 1f), ((py - fit[1]) / fit[3]).coerceIn(0f, 1f))
    }

    /** jpg/jpeg, png and webp are re-encoded in place; every other format (gif, bmp, heic, avif…) is saved as a PNG copy named "<name>-edited.png". */
    fun savePlan(fileName: String): SavePlan {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot + 1).lowercase() else ""
        return when (ext) {
            "jpg", "jpeg", "jpe", "jfif" -> SavePlan(fileName, "jpeg", true)
            "png" -> SavePlan(fileName, "png", true)
            "webp" -> SavePlan(fileName, "webp", true)
            else -> SavePlan("$base-edited.png", "png", false)
        }
    }

    /** The pen colours the Color button cycles through (ARGB). */
    val PALETTE: List<Int> = listOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())

    /** Pen width as a fraction of the image's shorter side. */
    const val PEN_FRACTION = 0.006f
}
