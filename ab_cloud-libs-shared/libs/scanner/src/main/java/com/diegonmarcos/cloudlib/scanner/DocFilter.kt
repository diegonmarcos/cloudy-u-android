package com.diegonmarcos.cloudlib.scanner

import kotlin.math.max
import kotlin.math.min

/** #1839 how a page is drawn: the four looks the ticket names; #1908 adds magic colour (the colour document, livened). */
enum class FilterMode(val id: String) {
    ORIGINAL("original"), GRAYSCALE("grayscale"), BW("bw"), COLOR_DOC("color_doc"), MAGIC("magic")
}

/** [brightness] and [contrast] are -100..100, 0 = untouched; #1908 [sharpness] is 0..100, 0 = untouched. */
data class FilterSpec(val mode: FilterMode = FilterMode.COLOR_DOC, val brightness: Int = 0, val contrast: Int = 0, val sharpness: Int = 0)

/**
 * #1839 the document filters, pure: B&W (an adaptive threshold, so a shadow does not turn the page black), grayscale, colour
 * document (the paper whitened and the shadows divided out, the ink and its colour kept) and the original, each with
 * brightness and contrast. The filter pipeline of the Filter step, and of "apply to all pages".
 */
object DocFilter {
    fun apply(src: Raster, spec: FilterSpec): Raster {
        val adjusted = adjust(src, spec.brightness, spec.contrast, gray = spec.mode == FilterMode.GRAYSCALE || spec.mode == FilterMode.BW)
        val looked = when (spec.mode) {
            FilterMode.ORIGINAL, FilterMode.GRAYSCALE -> adjusted
            FilterMode.BW -> blackAndWhite(adjusted)
            FilterMode.COLOR_DOC -> colourDocument(adjusted)
            FilterMode.MAGIC -> magicColour(adjusted)
        }
        return if (spec.sharpness > 0 && spec.mode != FilterMode.BW) sharpen(looked, spec.sharpness) else looked
    }

    /** #1908 magic colour: the colour document, then every colour pushed [MAGIC_SATURATION] times away from its grey. */
    fun magicColour(src: Raster): Raster {
        val doc = colourDocument(src)
        val out = IntArray(doc.px.size)
        for (i in out.indices) {
            val p = doc.px[i]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val y = doc.luma(i)
            out[i] = Raster.rgb((y + (r - y) * MAGIC_SATURATION).toInt(), (y + (g - y) * MAGIC_SATURATION).toInt(), (y + (b - y) * MAGIC_SATURATION).toInt())
        }
        return Raster(doc.w, doc.h, out)
    }

    /** #1908 unsharp mask: every channel gets [amount] (1..100 -> 0..1.5) times its difference from the 3x3 mean added back. */
    fun sharpen(src: Raster, amount: Int): Raster {
        val k = amount.coerceIn(0, 100) / 100.0 * 1.5
        val w = src.w; val h = src.h
        val out = IntArray(src.px.size)
        for (y in 0 until h) for (x in 0 until w) {
            val p = src.px[y * w + x]
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val q = src.px[yy * w + xx]
                sr += (q shr 16) and 255; sg += (q shr 8) and 255; sb += q and 255; n++
            }
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            out[y * w + x] = Raster.rgb((r + k * (r - sr / n)).toInt(), (g + k * (g - sg / n)).toInt(), (b + k * (b - sb / n)).toInt())
        }
        return Raster(w, h, out)
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
    private const val MAGIC_SATURATION = 1.4
    private const val INK_BELOW = 0.88
    private const val PAPER_NUDGE = 1.05
    private const val BLACK_POINT = 20.0
    private const val WHITE_POINT = 240.0
}
