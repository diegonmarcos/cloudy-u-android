package cld.camera.vr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import cld.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** #1533 files a composited photo (camera + filters) in DCIM/Camera as a JPEG. */
object VrSnapshot {
    fun save(ctx: Context, composited: Bitmap): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_vr.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("the media store refused a new photo")
        ctx.contentResolver.openOutputStream(uri)?.use { out -> composited.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            ?: throw IllegalStateException("cannot write $uri")
        return uri
    }
}
