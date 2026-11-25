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

    /** #1908 bumped by every change, so a screen's cached preview of this page knows it is stale. */
    var rev = 0

    fun touch() { ocrDone = false; rev++ }

    private var small: Bitmap? = null

    /** #1908 the photo brought down to [ScanPipeline.PREVIEW_SOURCE] (made once): thumbnails and filter previews are drawn from it, not from the 2400 px source. */
    fun previewSource(): Bitmap = small ?: run {
        val longest = max(source.width, source.height)
        val b = if (longest <= ScanPipeline.PREVIEW_SOURCE) source else {
            val f = ScanPipeline.PREVIEW_SOURCE.toDouble() / longest
            Bitmap.createScaledBitmap(source, max(1, (source.width * f).toInt()), max(1, (source.height * f).toInt()), true)
        }
        small = b; b
    }

    /** #1908 a duplicate page ([id] is the new one) with the same framing, turn, filter and text. */
    fun copyAs(id: Long): ScanPage = ScanPage(id, source).also {
        it.quad = quad; it.quarters = quarters; it.deskew = deskew; it.filter = filter; it.text = text; it.words = words; it.ocrDone = ocrDone
    }
}

/** #1839 Bitmap <-> Raster and the page pipeline: perspective, turn, deskew, filter. */
object ScanPipeline {
    /** Photos are brought down to this on import: the warp and the filters are per-pixel Kotlin. */
    const val SOURCE_SIDE = 2400

    /** #1908 thumbnails and previews at or below this side are rendered from [ScanPage.previewSource]. */
    const val PREVIEW_SOURCE = 900

    fun toRaster(b: Bitmap): Raster {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Raster(b.width, b.height, px)
    }

    fun toBitmap(r: Raster): Bitmap = Bitmap.createBitmap(r.px, r.w, r.h, Bitmap.Config.ARGB_8888)

    /** The framed, turned, deskewed page before any filter. */
    fun flatten(page: ScanPage, maxSide: Int = SOURCE_SIDE): Raster {
        val small = maxSide <= PREVIEW_SOURCE
        val bmp = if (small) page.previewSource() else page.source
        val k = bmp.width.toDouble() / page.source.width
        val quad = if (small) page.quad.scaled(k, bmp.height.toDouble() / page.source.height) else page.quad
        val src = toRaster(bmp)
        val (ow, oh) = quad.outputSize()
        val f = minOf(1.0, maxSide.toDouble() / max(ow, oh))
        val flat = src.warp(quad, max(1, (ow * f).toInt()), max(1, (oh * f).toInt())) ?: src
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
