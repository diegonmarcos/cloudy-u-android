package com.diegonmarcos.cloudlib.scanner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** #1908 the formats the image converters write. */
enum class ImageFormat(val id: String, val ext: String, val mime: String) {
    JPEG("jpeg", "jpg", "image/jpeg"), PNG("png", "png", "image/png"), WEBP("webp", "webp", "image/webp")
}

/**
 * #1908 the image converters of the Tools hub: any picture the platform decodes (JPEG, PNG, WebP, HEIC where the device has the
 * codec, GIF, BMP) in; JPEG, PNG or WebP out with a resize, a quality, a DPI, a grayscale and a "no bigger than N bytes" option;
 * and image to PDF. The byte-level parts are pure (ImageMeta, TargetSize) and tested in the JVM suite.
 */
object ImageTools {
    /** Decodes [uri] at most [maxSide] on its longer side (the EXIF turn applied by the decoder). Null when it cannot be read. */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int = ScanPipeline.SOURCE_SIDE): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val f = maxSide.toDouble() / longest
                    dec.setTargetSize(max(1, (info.size.width * f).toInt()), max(1, (info.size.height * f).toInt()))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }.getOrNull()

    /** Scaled so the longer side is [maxSide] (the picture itself when it is already smaller). */
    fun resize(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide || maxSide < 1) return b
        val f = maxSide.toDouble() / longest
        return Bitmap.createScaledBitmap(b, max(1, (b.width * f).toInt()), max(1, (b.height * f).toInt()), true)
    }

    /** Scaled to [percent] of its size (1..100). */
    fun scalePercent(b: Bitmap, percent: Int): Bitmap {
        val f = percent.coerceIn(1, 100) / 100.0
        return if (f >= 1.0) b else Bitmap.createScaledBitmap(b, max(1, (b.width * f).toInt()), max(1, (b.height * f).toInt()), true)
    }

    fun grayscale(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(b, 0f, 0f, paint)
        return out
    }

    /** JPEG has no alpha: a transparent picture is laid on white first. */
    private fun opaque(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).also { it.drawColor(-1) }.drawBitmap(b, 0f, 0f, null)
        return out
    }

    @Suppress("DEPRECATION")
    fun encode(b: Bitmap, format: ImageFormat, quality: Int): ByteArray = ByteArrayOutputStream().also { o ->
        when (format) {
            ImageFormat.JPEG -> opaque(b).compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), o)
            ImageFormat.PNG -> b.compress(Bitmap.CompressFormat.PNG, 100, o)
            ImageFormat.WEBP -> b.compress(
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, quality.coerceIn(1, 100), o)
        }
    }.toByteArray()

    /** What the converter writes: format, quality, the longer side (0 = unchanged), DPI (0 = none), grayscale and a size ceiling (0 = none). */
    data class Convert(
        val format: ImageFormat = ImageFormat.JPEG,
        val quality: Int = 90,
        val maxSide: Int = 0,
        val dpi: Int = 0,
        val gray: Boolean = false,
        val maxBytes: Int = 0,
    )

    /** One picture converted by [o]. PNG is lossless: a size ceiling shrinks it by scaling instead of by quality. */
    fun convert(src: Bitmap, o: Convert): ByteArray {
        var b = if (o.maxSide > 0) resize(src, o.maxSide) else src
        if (o.gray) b = grayscale(b)
        var bytes = when {
            o.maxBytes > 0 && o.format != ImageFormat.PNG -> TargetSize.search(o.maxBytes) { q -> encode(b, o.format, q) }.bytes
            else -> encode(b, o.format, o.quality)
        }
        if (o.maxBytes > 0 && o.format == ImageFormat.PNG) {
            var percent = 100
            while (bytes.size > o.maxBytes && percent > 10) { percent -= 10; bytes = encode(scalePercent(b, percent), o.format, 100) }
        }
        if (o.dpi > 0) bytes = when (o.format) { ImageFormat.JPEG -> ImageMeta.jpegWithDpi(bytes, o.dpi); ImageFormat.PNG -> ImageMeta.pngWithDpi(bytes, o.dpi); else -> bytes }
        return bytes
    }

    /** Pictures to one PDF, a page each, on [size] sheets (no text layer; the Scanner's own export adds that from OCR). */
    fun toPdf(images: List<Bitmap>, size: PageSize, marginMm: Double, orientation: Orientation, quality: Int, title: String = ""): ByteArray {
        require(images.isNotEmpty()) { "no pictures" }
        val jpegs = images.map { encode(it, ImageFormat.JPEG, quality) }
        val plan = PdfPlan.build(images.map { PlanPage(it.width, it.height, emptyList()) }, size, marginMm, orientation)
        val out = ByteArrayOutputStream()
        PdfWriter.write(out, plan.mapIndexed { i, pl -> PdfPageData(pl, jpegs[i], images[i].width, images[i].height) }, title)
        return out.toByteArray()
    }
}
