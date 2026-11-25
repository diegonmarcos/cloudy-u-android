package com.diegonmarcos.cloudlib.scanner

import android.graphics.Bitmap
import kotlin.math.max

/**
 * #1839 one scanned page as the Scanner edits it: the source photo, the corners, the turn, the deskew, the filter and
 * the text read from it. Nothing is baked into [source]; [ScanPipeline.render] rebuilds the page from these every time,
 * so a corner, a filter or a size can be changed again at any point.
 */
class ScanPage(val id: Long, val source: Bitmap) {
    var quad: Quad = Quad.full(source.width, source.height, 0.03)
    var quarters = 0
    var deskew = 0.0
    var filter = FilterSpec()
    var text = ""
    var words: List<OcrWord> = emptyList()
    /** The text read from the page as it is NOW; a changed page clears it (see [touch]). */
    var ocrDone = false

    fun touch() { ocrDone = false }
}

/** #1839 Bitmap <-> Raster and the page pipeline: perspective, turn, deskew, filter. */
object ScanPipeline {
    /** Photos are brought down to this on import: the warp and the filters are per-pixel Kotlin. */
    const val SOURCE_SIDE = 2400

    fun toRaster(b: Bitmap): Raster {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Raster(b.width, b.height, px)
    }

    fun toBitmap(r: Raster): Bitmap = Bitmap.createBitmap(r.px, r.w, r.h, Bitmap.Config.ARGB_8888)

    /** The framed, turned, deskewed page before any filter. */
    fun flatten(page: ScanPage, maxSide: Int = SOURCE_SIDE): Raster {
        val src = toRaster(page.source)
        val (ow, oh) = page.quad.outputSize()
        val f = minOf(1.0, maxSide.toDouble() / max(ow, oh))
        val flat = src.warp(page.quad, max(1, (ow * f).toInt()), max(1, (oh * f).toInt())) ?: src
        return flat.rotateQuarters(page.quarters).rotate(page.deskew)
    }

    fun render(page: ScanPage, maxSide: Int = SOURCE_SIDE): Raster = DocFilter.apply(flatten(page, maxSide), page.filter)

    /** The deskew angle for [page] as framed and turned now. */
    fun estimateDeskew(page: ScanPage): Double {
        val saved = page.deskew; page.deskew = 0.0
        val flat = flatten(page, 600)
        page.deskew = saved
        return EdgeDetector.deskewDegrees(flat)
    }

    /** Automatic edge detection on the source photo: its corners in source pixels. */
    fun autoQuad(page: ScanPage): Quad = EdgeDetector.findQuad(toRaster(page.source))
}
