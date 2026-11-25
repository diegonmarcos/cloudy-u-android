package com.diegonmarcos.cloudlib.scanner

import kotlin.math.max

/** One recognised word, in the pixels of the page image it was read from (y down). */
data class OcrWord(val text: String, val x: Double, val y: Double, val w: Double, val h: Double)

/** What the plan needs to know of one page. */
data class PlanPage(val imgW: Int, val imgH: Int, val words: List<OcrWord>)

/** One word of the invisible text layer, in PDF space (points, origin bottom-left): [hScale] is the Tz percentage that stretches it onto its box. */
data class PlannedWord(val text: String, val x: Double, val y: Double, val fontSize: Double, val hScale: Double)

data class PlannedPage(val placement: Placement, val words: List<PlannedWord>)

/**
 * #1839 the PDF page plan, pure: for every scanned page the sheet size, the image placement (PageFit) and the text layer
 * (every OCR word moved from image pixels to PDF points) that makes the PDF searchable. PdfWriter only serialises it.
 */
object PdfPlan {
    /** Helvetica's average advance is about half an em; the layer is invisible, this only decides where a search hit lands. */
    private const val AVG_ADVANCE = 0.5

    fun build(pages: List<PlanPage>, size: PageSize, marginMm: Double, orientation: Orientation): List<PlannedPage> =
        pages.map { p ->
            val pl = PageFit.fit(p.imgW, p.imgH, size, marginMm, orientation)
            val sx = pl.w / p.imgW; val sy = pl.h / p.imgH
            val words = p.words.filter { it.text.isNotBlank() && it.w > 0 && it.h > 0 }.map { wd ->
                val fs = max(1.0, wd.h * sy * 0.8)
                val boxW = wd.w * sx
                PlannedWord(
                    text = wd.text,
                    x = pl.x + wd.x * sx,
                    // baseline sits near the bottom of the word's box; PDF y grows upward
                    y = pl.pageH - (pl.y + (wd.y + wd.h * 0.8) * sy),
                    fontSize = fs,
                    hScale = (boxW / (AVG_ADVANCE * fs * wd.text.length) * 100).coerceIn(10.0, 1000.0),
                )
            }
            PlannedPage(pl, words)
        }
}
