package com.diegonmarcos.cloudlib.scanner

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** #1908 image encoders' density metadata, pure on bytes: the DPI a JPEG (JFIF) or a PNG (pHYs) declares. */
object ImageMeta {
    /** The JPEG with its JFIF density set to [dpi] (a JFIF APP0 is inserted when there is none). */
    fun jpegWithDpi(jpeg: ByteArray, dpi: Int): ByteArray {
        require(jpeg.size > 4 && (jpeg[0].toInt() and 255) == 0xFF && (jpeg[1].toInt() and 255) == 0xD8) { "not a JPEG" }
        val d = dpi.coerceIn(1, 65535)
        val jfif = jpeg.size > 20 && (jpeg[2].toInt() and 255) == 0xFF && (jpeg[3].toInt() and 255) == 0xE0 &&
            jpeg[6].toInt().toChar() == 'J' && jpeg[7].toInt().toChar() == 'F' && jpeg[8].toInt().toChar() == 'I' && jpeg[9].toInt().toChar() == 'F'
        if (jfif) {
            val out = jpeg.copyOf()
            out[13] = 1 // units: dots per inch
            out[14] = (d shr 8).toByte(); out[15] = d.toByte(); out[16] = (d shr 8).toByte(); out[17] = d.toByte()
            return out
        }
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1, 1, (d shr 8).toByte(), d.toByte(), (d shr 8).toByte(), d.toByte(), 0, 0)
        return jpeg.copyOfRange(0, 2) + app0 + jpeg.copyOfRange(2, jpeg.size)
    }

    /** The DPI a JPEG declares in its JFIF header, or null. */
    fun jpegDpi(jpeg: ByteArray): Int? {
        if (jpeg.size < 20 || jpeg[13].toInt() != 1 || jpeg[6].toInt().toChar() != 'J') return null
        return ((jpeg[14].toInt() and 255) shl 8) or (jpeg[15].toInt() and 255)
    }

    /** The PNG with a pHYs chunk of [dpi] (pixels per metre = dpi / 0.0254) after IHDR; an existing pHYs is dropped. */
    fun pngWithDpi(png: ByteArray, dpi: Int): ByteArray {
        require(png.size > 33 && png[1].toInt().toChar() == 'P' && png[2].toInt().toChar() == 'N' && png[3].toInt().toChar() == 'G') { "not a PNG" }
        val ppm = Math.round(dpi.coerceAtLeast(1) / 0.0254).toInt()
        val out = ByteArrayOutputStream()
        out.write(png, 0, 8)
        var i = 8
        var written = false
        while (i + 12 <= png.size) {
            val len = ((png[i].toInt() and 255) shl 24) or ((png[i + 1].toInt() and 255) shl 16) or ((png[i + 2].toInt() and 255) shl 8) or (png[i + 3].toInt() and 255)
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            val end = i + 12 + len
            if (end > png.size) break
            if (type != "pHYs") out.write(png, i, end - i)
            if (type == "IHDR" && !written) { out.write(phys(ppm)); written = true }
            i = end
        }
        return out.toByteArray()
    }

    /** The DPI a PNG declares in its pHYs chunk (metre units), or null. */
    fun pngDpi(png: ByteArray): Int? {
        var i = 8
        while (i + 12 <= png.size) {
            val len = ((png[i].toInt() and 255) shl 24) or ((png[i + 1].toInt() and 255) shl 16) or ((png[i + 2].toInt() and 255) shl 8) or (png[i + 3].toInt() and 255)
            if (String(png, i + 4, 4, Charsets.ISO_8859_1) == "pHYs" && len == 9 && png[i + 16].toInt() == 1) {
                val ppm = ((png[i + 8].toInt() and 255) shl 24) or ((png[i + 9].toInt() and 255) shl 16) or ((png[i + 10].toInt() and 255) shl 8) or (png[i + 11].toInt() and 255)
                return Math.round(ppm * 0.0254).toInt()
            }
            i += 12 + len
        }
        return null
    }

    private fun phys(ppm: Int): ByteArray {
        val data = ByteArray(9)
        for (k in 0..3) { data[k] = (ppm shr (24 - 8 * k)).toByte(); data[4 + k] = (ppm shr (24 - 8 * k)).toByte() }
        data[8] = 1
        val body = "pHYs".toByteArray(Charsets.ISO_8859_1) + data
        val crc = CRC32().also { it.update(body) }.value
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 9)); out.write(body)
        for (k in 0..3) out.write((crc shr (24 - 8 * k)).toInt() and 255)
        return out.toByteArray()
    }
}

/** #1908 "compress to a target size": the highest quality whose encoding fits, found by bisection. Pure over an encoder. */
object TargetSize {
    class Result(val quality: Int, val bytes: ByteArray, val fits: Boolean)

    /** [encode] maps a quality (1..100) to the encoded bytes. When even [minQuality] is too big, that smallest encoding is returned with fits = false. */
    fun search(maxBytes: Int, minQuality: Int = 5, maxQuality: Int = 100, encode: (Int) -> ByteArray): Result {
        var lo = minQuality; var hi = maxQuality
        val atLo = encode(lo)
        if (atLo.size > maxBytes) return Result(lo, atLo, false)
        var best = Result(lo, atLo, true)
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val b = encode(mid)
            if (b.size <= maxBytes) { best = Result(mid, b, true); lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}
