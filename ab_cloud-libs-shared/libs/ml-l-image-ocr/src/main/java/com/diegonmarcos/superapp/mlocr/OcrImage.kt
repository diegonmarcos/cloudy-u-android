package com.diegonmarcos.superapp.mlocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/** #1418 an image picked or photographed, decoded once, downscaled so neither engine is sent a 50 MP frame. */
object OcrImage {
    /** Longest side handed to an engine: enough for body text, small enough for a vision request. */
    const val MAX_SIDE = 2048

    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) dec.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }.getOrNull()

    fun jpeg(bitmap: Bitmap, quality: Int = 85): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
