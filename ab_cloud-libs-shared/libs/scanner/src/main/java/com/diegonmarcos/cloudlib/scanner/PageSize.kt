package com.diegonmarcos.cloudlib.scanner

import kotlin.math.min

/** #1839 the sizes a page can be fitted to. Dimensions are millimetres in the sheet's portrait reading (card sizes: as held, landscape). */
enum class PageSize(val id: String, val widthMm: Double, val heightMm: Double) {
    ORIGINAL("original", 0.0, 0.0),
    A4("a4", 210.0, 297.0),
    LETTER("letter", 215.9, 279.4),
    LEGAL("legal", 215.9, 355.6),
    ID_CARD("id_card", 85.6, 53.98),
    RECEIPT("receipt", 80.0, 0.0),
    BUSINESS_CARD("business_card", 88.9, 50.8),
}

enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

/** Where the page image sits on its sheet, all in PDF points (1/72 inch), y measured from the TOP of the sheet. */
data class Placement(val pageW: Double, val pageH: Double, val x: Double, val y: Double, val w: Double, val h: Double)

/** #1839 the page-size fit, pure: the scanned image scaled (never stretched) into the sheet minus its margins and centred. */
object PageFit {
    const val MM_PT = 72.0 / 25.4
    /** The "original size" sheet is the image at this resolution. */
    const val ORIGINAL_DPI = 150.0

    fun fit(imgW: Int, imgH: Int, size: PageSize, marginMm: Double, orientation: Orientation): Placement {
        require(imgW > 0 && imgH > 0) { "empty image" }
        val m = marginMm.coerceAtLeast(0.0) * MM_PT
        when (size) {
            PageSize.ORIGINAL -> {
                val w = imgW * 72.0 / ORIGINAL_DPI; val h = imgH * 72.0 / ORIGINAL_DPI
                return Placement(w + 2 * m, h + 2 * m, m, m, w, h)
            }
            PageSize.RECEIPT -> {
                // A roll: fixed width, as long as the receipt is.
                val pw = size.widthMm * MM_PT
                val w = (pw - 2 * m).coerceAtLeast(1.0)
                val h = w * imgH / imgW
                return Placement(pw, h + 2 * m, m, m, w, h)
            }
            else -> {
                var pw = size.widthMm * MM_PT; var ph = size.heightMm * MM_PT
                val landscape = when (orientation) {
                    Orientation.LANDSCAPE -> true
                    Orientation.PORTRAIT -> false
                    Orientation.AUTO -> imgW > imgH
                }
                if (landscape != (pw > ph)) { val t = pw; pw = ph; ph = t }
                val boxW = (pw - 2 * m).coerceAtLeast(1.0); val boxH = (ph - 2 * m).coerceAtLeast(1.0)
                val s = min(boxW / imgW, boxH / imgH)
                val w = imgW * s; val h = imgH * s
                return Placement(pw, ph, (pw - w) / 2, (ph - h) / 2, w, h)
            }
        }
    }
}
