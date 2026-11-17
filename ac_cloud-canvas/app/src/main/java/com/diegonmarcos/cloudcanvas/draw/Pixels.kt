package com.diegonmarcos.cloudcanvas.draw

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Pixels are non-premultiplied ARGB ints, the layout of Bitmap.getPixels. Pure Kotlin, so the JVM suite runs every function here. */
object Pixels {
    private fun a(c: Int) = c ushr 24
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** [src] over [dst] (source-over), [src]'s alpha scaled by [opacity]. */
    fun over(dst: Int, src: Int, opacity: Float = 1f): Int {
        val sa = a(src) / 255f * opacity.coerceIn(0f, 1f)
        if (sa <= 0f) return dst
        val da = a(dst) / 255f
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return 0
        fun ch(s: Int, d: Int): Int = ((s * sa + d * da * (1f - sa)) / oa + 0.5f).toInt().coerceIn(0, 255)
        return argb((oa * 255f + 0.5f).toInt().coerceIn(0, 255), ch(r(src), r(dst)), ch(g(src), g(dst)), ch(b(src), b(dst)))
    }

    class LayerPixels(val pixels: IntArray, val visible: Boolean, val opacity: Float)

    /** The flattened picture: the visible layers bottom to top over [background]. */
    fun composite(width: Int, height: Int, layers: List<LayerPixels>, background: Int = 0xFFFFFFFF.toInt()): IntArray {
        val out = IntArray(width * height) { background }
        for (l in layers) {
            if (!l.visible || l.opacity <= 0f) continue
            for (i in out.indices) {
                val s = l.pixels[i]
                if (a(s) != 0) out[i] = over(out[i], s, l.opacity)
            }
        }
        return out
    }

    /** The colour of one pixel across the visible layers (the eyedropper). */
    fun compositePixel(x: Int, y: Int, width: Int, layers: List<LayerPixels>, background: Int = 0xFFFFFFFF.toInt()): Int {
        var c = background
        for (l in layers) if (l.visible && l.opacity > 0f) c = over(c, l.pixels[y * width + x], l.opacity)
        return c
    }

    /** Flood fill from ([x],[y]) with [color], over the pixels within [tolerance] (per channel) of the seed; returns how many were filled. */
    fun floodFill(px: IntArray, width: Int, height: Int, x: Int, y: Int, color: Int, tolerance: Int = 24): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val seed = px[y * width + x]
        if (seed == color) return 0
        fun near(c: Int) = Math.abs(a(c) - a(seed)) <= tolerance && Math.abs(r(c) - r(seed)) <= tolerance &&
            Math.abs(g(c) - g(seed)) <= tolerance && Math.abs(b(c) - b(seed)) <= tolerance
        val seen = BooleanArray(width * height)
        val stack = IntArray(width * height)
        var sp = 0
        var filled = 0
        stack[sp++] = y * width + x
        seen[y * width + x] = true
        while (sp > 0) {
            val i = stack[--sp]
            px[i] = color
            filled++
            val cx = i % width
            val cy = i / width
            if (cx > 0 && !seen[i - 1] && near(px[i - 1])) { seen[i - 1] = true; stack[sp++] = i - 1 }
            if (cx < width - 1 && !seen[i + 1] && near(px[i + 1])) { seen[i + 1] = true; stack[sp++] = i + 1 }
            if (cy > 0 && !seen[i - width] && near(px[i - width])) { seen[i - width] = true; stack[sp++] = i - width }
            if (cy < height - 1 && !seen[i + width] && near(px[i + width])) { seen[i + width] = true; stack[sp++] = i + width }
        }
        return filled
    }
}

/** A PNG writer (8-bit RGBA, filter 0, one IDAT): the export path needs no Bitmap.compress, so the JVM suite decodes its output. */
object PngEncoder {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "pixel count ${argb.size} is not ${width}x$height" }
        val raw = ByteArray(height * (1 + width * 4))
        var o = 0
        for (y in 0 until height) {
            raw[o++] = 0
            for (x in 0 until width) {
                val c = argb[y * width + x]
                raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte(); raw[o++] = (c ushr 24).toByte()
            }
        }
        val deflater = Deflater(6)
        deflater.setInput(raw)
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width); putInt(ihdr, 4, height)
        ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        putInt(len, 0, data.size)
        out.write(len)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        val c = ByteArray(4)
        putInt(c, 0, crc.value.toInt())
        out.write(c)
    }
}

/** File names of the exports, so a second export never overwrites the first. */
object ExportNames {
    fun png(epochSeconds: Long): String = "cloud-canvas-" + stamp(epochSeconds) + ".png"
    fun wav(epochSeconds: Long): String = "cloud-canvas-mix-" + stamp(epochSeconds) + ".wav"

    /** yyyyMMdd-HHmmss in UTC without java.time formatting state. */
    fun stamp(epochSeconds: Long): String {
        val days = Math.floorDiv(epochSeconds, 86400L)
        val sod = Math.floorMod(epochSeconds, 86400L).toInt()
        // civil from days (Howard Hinnant)
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return "%04d%02d%02d-%02d%02d%02d".format(y, m, d, sod / 3600, (sod / 60) % 60, sod % 60)
    }
}
