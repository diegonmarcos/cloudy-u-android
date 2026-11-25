package com.diegonmarcos.cloudlib.scanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** #1908 the crop shapes the editor offers: free, the photo's own ratio, plain ratios and the paper / card sizes. [ratio] is width / height (0 = free). */
enum class CropPreset(val id: String, val ratio: Double) {
    FREE("free", 0.0),
    ORIGINAL("original", -1.0),
    SQUARE("1_1", 1.0),
    R4_3("4_3", 4.0 / 3),
    R3_4("3_4", 3.0 / 4),
    R16_9("16_9", 16.0 / 9),
    A4("a4", 210.0 / 297),
    LETTER("letter", 215.9 / 279.4),
    LEGAL("legal", 215.9 / 355.6),
    ID_CARD("id_card", 85.6 / 53.98),
    RECEIPT("receipt", 80.0 / 200.0),
    BUSINESS_CARD("business_card", 88.9 / 50.8),
}

/** #1908 crop maths, pure: a preset turned into the quad the corner handles start from. */
object CropMath {
    /** The ratio a preset means for an [imgW] x [imgH] photo (null = free). */
    fun ratioOf(preset: CropPreset, imgW: Int, imgH: Int): Double? = when {
        preset == CropPreset.FREE -> null
        preset == CropPreset.ORIGINAL -> imgW.toDouble() / imgH
        else -> preset.ratio
    }

    /** The biggest axis-aligned quad of [ratio] centred in the photo, [inset] of each side kept clear. */
    fun centred(imgW: Int, imgH: Int, ratio: Double?, inset: Double = 0.03): Quad {
        val boxW = imgW * (1 - 2 * inset); val boxH = imgH * (1 - 2 * inset)
        var w = boxW; var h = boxH
        if (ratio != null && ratio > 0) { if (boxW / boxH > ratio) w = boxH * ratio else h = boxW / ratio }
        val x0 = (imgW - 1 - w) / 2; val y0 = (imgH - 1 - h) / 2
        return Quad(P(x0, y0), P(x0 + w, y0), P(x0 + w, y0 + h), P(x0, y0 + h))
    }

    /** The current crop's centre and area kept, its shape changed to [ratio]; shrunk to stay inside the photo. Free keeps [q]. */
    fun reshaped(q: Quad, imgW: Int, imgH: Int, ratio: Double?): Quad {
        if (ratio == null || ratio <= 0) return q
        val xs = q.points.map { it.x }; val ys = q.points.map { it.y }
        val cx = (xs.min() + xs.max()) / 2; val cy = (ys.min() + ys.max()) / 2
        val area = max(1.0, (xs.max() - xs.min()) * (ys.max() - ys.min()))
        var w = sqrt(area * ratio); var h = w / ratio
        val fit = min(1.0, min((imgW - 1.0) / w, (imgH - 1.0) / h))
        w *= fit; h *= fit
        val x0 = (cx - w / 2).coerceIn(0.0, max(0.0, imgW - 1.0 - w)); val y0 = (cy - h / 2).coerceIn(0.0, max(0.0, imgH - 1.0 - h))
        return Quad(P(x0, y0), P(x0 + w, y0), P(x0 + w, y0 + h), P(x0, y0 + h))
    }
}
