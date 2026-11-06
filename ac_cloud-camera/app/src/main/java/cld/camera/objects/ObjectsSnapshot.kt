package cld.camera.objects

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidxc.exifinterface.media.ExifInterface
import cld.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import com.diegonmarcos.superapp.vision.FrameResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #1533 the Objects page's snapshot: the photo goes to DCIM/Camera with the detections it was taken with in its own
 * EXIF (ImageDescription a sentence, UserComment the JSON), and the same JSON lands next to it as a .json file in
 * Documents/Cloud Camera, so a gallery and a script can both read it.
 */
object ObjectsSnapshot {
    fun save(ctx: Context, jpeg: File, result: FrameResult?, minConfidence: Float): Uri {
        val json = (result ?: FrameResult(0, 0, emptyList())).toJson(minConfidence).toString()
        val seen = result?.atLeast(minConfidence).orEmpty().map { it.label }.distinct()
        ExifInterface(jpeg).apply {
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, if (seen.isEmpty()) "Objects: none" else seen.joinToString(", ", "Objects: "))
            setAttribute(ExifInterface.TAG_USER_COMMENT, json)
            saveAttributes()
        }
        val stamp = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_objects"
        val photo = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$stamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo)
            ?: throw IllegalStateException("the media store refused a new photo")
        ctx.contentResolver.openOutputStream(uri)?.use { out -> jpeg.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("cannot write $uri")
        val side = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$stamp.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Cloud Camera")
        }
        // The JSON also rides inside the photo; a refused sidecar must not lose the photo that is already saved.
        runCatching {
            ctx.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), side)?.let { u ->
                ctx.contentResolver.openOutputStream(u)?.use { it.write(json.toByteArray()) }
            }
        }
        return uri
    }
}
