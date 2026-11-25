package com.diegonmarcos.cloudlib.scanner

import kotlin.math.max
import kotlin.math.min

/** #1839 how a page is drawn: the four looks the ticket names. */
enum class FilterMode(val id: String) {
    ORIGINAL("original"), GRAYSCALE("grayscale"), BW("bw"), COLOR_DOC("color_doc")
}

/** [brightness] and [contrast] are -100..100, 0 = untouched. */
data class FilterSpec(val mode: FilterMode = FilterMode.COLOR_DOC, val brightness: Int = 0, val contrast: Int = 0)

/**
 * #1839 the document filters, pure: B&W (an adaptive threshold, so a shadow does not turn the page black), grayscale, colour
 * document (the paper whitened and the shadows divided out, the ink and its colour kept) and the original, each with
 * brightness and contrast. The filter pipeline of the Filter step, and of "apply to all pages".
 */
object DocFilter {
    fun apply(src: Raster, spec: FilterSpec): Raster {
        val adjusted = adjust(src, spec.brightness, spec.contrast, gray = spec.mode == FilterMode.GRAYSCALE || spec.mode == FilterMode.BW)
        return when (spec.mode) {
            FilterMode.ORIGINAL, FilterMode.GRAYSCALE -> adjusted
            FilterMode.BW -> blackAndWhite(adjusted)
            FilterMode.COLOR_DOC -> colourDocument(adjusted)
        }
    }

    /** Brightness (+-100 -> +-255/2) after contrast (factor 0..2 about mid grey); [gray] also drops the colour. */
    fun adjust(src: Raster, brightness: Int, contrast: Int, gray: Boolean): Raster {
        if (brightness == 0 && contrast == 0 && !gray) return src
        val f = 1.0 + contrast.coerceIn(-100, 100) / 100.0
        val add = brightness.coerceIn(-100, 100) * 1.275
        val lut = IntArray(256) { v -> ((v - 128) * f + 128 + add).toInt().coerceIn(0, 255) }
        val out = IntArray(src.px.size)
        for (i in out.indices) {
            val p = src.px[i]
            out[i] = if (gray) { val g = lut[src.luma(i)]; Raster.rgb(g, g, g) }
            else Raster.rgb(lut[(p shr 16) and 255], lut[(p shr 8) and 255], lut[p and 255])
        }
        return Raster(src.w, src.h, out)
    }

    /** Black ink on white paper: a pixel is ink when it is [INK_BELOW] darker than the mean of the window around it. */
    fun blackAndWhite(src: Raster): Raster {
        val mean = localMean(src)
        val out = IntArray(src.px.size)
        for (i in out.indices) out[i] = if (src.luma(i) < mean[i] * INK_BELOW) BLACK else Raster.WHITE
        return Raster(src.w, src.h, out)
    }

    /** Each pixel divided by the local paper level (the window mean, nudged up), then stretched so paper is exactly white. */
    fun colourDocument(src: Raster): Raster {
        val mean = localMean(src)
        val out = IntArray(src.px.size)
        for (i in out.indices) {
            val paper = max(1.0, mean[i] * PAPER_NUDGE)
            val p = src.px[i]
            fun ch(v: Int): Int = (((v * 255.0 / paper).coerceAtMost(255.0) - BLACK_POINT) * 255.0 / (WHITE_POINT - BLACK_POINT)).toInt().coerceIn(0, 255)
            out[i] = Raster.rgb(ch((p shr 16) and 255), ch((p shr 8) and 255), ch(p and 255))
        }
        return Raster(src.w, src.h, out)
    }

    /** The mean luma of the odd window (an eighth of the shorter side) around every pixel, from an integral image. */
    fun localMean(src: Raster): DoubleArray {
        val w = src.w; val h = src.h
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += src.luma(y * w + x)
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val r = max(7, min(w, h) / 16)
        val out = DoubleArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                out[y * w + x] = sum.toDouble() / ((x1 - x0) * (y1 - y0))
            }
        }
        return out
    }

    const val BLACK = 0xFF000000.toInt()
    private const val INK_BELOW = 0.88
    private const val PAPER_NUDGE = 1.05
    private const val BLACK_POINT = 20.0
    private const val WHITE_POINT = 240.0
}
