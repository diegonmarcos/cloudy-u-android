package cld.camera.objects

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidxc.exifinterface.media.ExifInterface
import cld.camera.capturer.DEFAULT_MEDIA_STORE_CAPTURE_PATH
import com.diegonmarcos.superapp.vision.Detection
import com.diegonmarcos.superapp.vision.FrameResult
import com.diegonmarcos.superapp.vision.TextLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #1533 the Objects page's snapshot: the photo goes to DCIM/Camera with the detections it was taken with in its own
 * EXIF (ImageDescription a sentence, UserComment the JSON), and the same JSON lands next to it as a .json file in
 * Documents/Cloud Camera, so a gallery and a script can both read it.
 *
 * #1805 the JSON also carries the text on screen: every text region ("Text" frames) and the OCR layout read for the
 * frame (blocks, lines and words with their boxes), so the saved photo keeps its text without reading it again.
 */
object ObjectsSnapshot {
    fun save(ctx: Context, jpeg: File, result: FrameResult?, minConfidence: Float, text: TextLayout? = null, textRegions: List<Detection> = emptyList()): Uri {
        val json = withText((result ?: FrameResult(0, 0, emptyList())).toJson(minConfidence), text, textRegions).toString()
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

    /** #1805 [o] with the text regions and the OCR layout (null when nothing was read). */
    fun withText(o: JSONObject, text: TextLayout?, regions: List<Detection>): JSONObject {
        fun box(b: com.diegonmarcos.superapp.vision.Box) = JSONObject().put("left", b.left.toDouble()).put("top", b.top.toDouble()).put("right", b.right.toDouble()).put("bottom", b.bottom.toDouble())
        o.put("text_regions", JSONArray().apply { regions.forEach { put(JSONObject().put("label", it.label).put("confidence", it.confidence.toDouble()).put("box", box(it.box))) } })
        o.put("text", if (text == null) JSONObject.NULL else JSONObject()
            .put("frame", JSONObject().put("width", text.frameWidth).put("height", text.frameHeight))
            .put("all", text.allText)
            .put("blocks", JSONArray().apply {
                text.blocks.forEach { b ->
                    put(JSONObject().put("text", b.text).put("script", b.script).put("box", box(b.box)).put("lines", JSONArray().apply {
                        b.lines.forEach { l ->
                            put(JSONObject().put("text", l.text).put("confidence", l.confidence.toDouble()).put("box", box(l.box)).put("words", JSONArray().apply {
                                b.words.filter { it.line == l.index }.forEach { w -> put(JSONObject().put("text", w.text).put("confidence", w.confidence.toDouble()).put("box", box(w.box))) }
                            }))
                        }
                    }))
                }
            }))
        return o
    }
}
