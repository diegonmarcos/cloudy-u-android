package com.diegonmarcos.clouddrive.agents

import android.content.Context
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import com.diegonmarcos.superapp.ocr.Box
import com.diegonmarcos.superapp.ocr.LayoutBuilder
import com.diegonmarcos.superapp.ocr.MarkdownFormatter
import com.diegonmarcos.superapp.ocr.OcrLine
import com.diegonmarcos.superapp.ocr.OcrResult
import com.diegonmarcos.superapp.ocr.ScreenshotMeta
import com.diegonmarcos.superapp.ocr.SidecarIdempotency
import com.diegonmarcos.superapp.ocr.SidecarJson
import com.diegonmarcos.superapp.ocr.SidecarNaming
import java.io.File
import java.security.MessageDigest

/**
 * Screenshot OCR's output beside the image: `<name>.md` (Markdown, tables, front matter) and
 * `<name>.json` (full metadata + OCR), same folder, same base name. The shaping is libs:ocr
 * (pure Kotlin, JVM-tested); this object only gathers what needs a Context (MediaStore, device).
 * The `.md` is written first and the `.json` last, so a `.json` on disk means the pair is whole.
 * All local: the OCR text comes from the on-device record, nothing is sent anywhere.
 */
object SidecarWriter {
    const val ENGINE = "mlkit-text-latin"
    const val ENGINE_VERSION = "16.0.1"

    /** The record's lines (already in source pixels, reading order) through libs:ocr's layout and table detection. */
    fun layout(r: ScreenshotRecord): OcrResult {
        val lines = r.blocks.flatMap { it.lines }.filter { it.bbox.size == 4 }.map { OcrLine(it.text, Box(it.bbox[0], it.bbox[1], it.bbox[2], it.bbox[3]), it.confidence) }
        return LayoutBuilder.build(lines, r.width, r.height, ENGINE, ENGINE_VERSION)
    }

    fun isCurrent(f: File): Boolean = SidecarIdempotency.isCurrent(f)

    fun write(ctx: Context, f: File, r: ScreenshotRecord) {
        val ocr = layout(r)
        val sha = SidecarIdempotency.sha256(f)
        val meta = ScreenshotMeta(
            name = f.name, path = f.absolutePath, size = f.length(), sha256 = sha, md5 = digest(f, "MD5"), sha1 = digest(f, "SHA-1"),
            mime = mime(f), width = r.width, height = r.height, createdMs = null, modifiedMs = f.lastModified(), takenMs = r.takenAtMs,
            exif = exif(f), xmp = xmp(f), mediaStore = mediaStore(ctx, f), sourceApp = r.sourceApp,
            device = mapOf(
                "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER, "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
                "density" to ctx.resources.displayMetrics.density.toDouble(), "density_dpi" to ctx.resources.displayMetrics.densityDpi,
            ),
            colours = r.colours.map { it.hex to it.share },
            labels = r.labels.filter { it.source != "heuristic" }.map { it.label to it.confidence },
            engines = r.engine + mapOf("ocr_engine" to "$ENGINE $ENGINE_VERSION (ML Kit Text Recognition v2, bundled, on-device)", "layout" to "libs:ocr"),
            processedMs = System.currentTimeMillis(),
        )
        val md = MarkdownFormatter.format(ocr, MarkdownFormatter.Front(f.name, r.takenAtMs, r.sourceApp, ENGINE, ocr.meanConfidence))
        atomic(SidecarNaming.md(f), md)
        atomic(SidecarNaming.json(f), SidecarJson.build(meta, ocr).toString(2))
    }

    private fun atomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "cannot write ${target.path}" } }
    }

    private fun digest(f: File, algo: String): String? = runCatching {
        val md = MessageDigest.getInstance(algo)
        f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun mime(f: File) = when (f.extension.lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "application/octet-stream" }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_COLOR_SPACE,
    )

    private fun exif(f: File): Map<String, String> = runCatching {
        val e = ExifInterface(f.absolutePath)
        EXIF_TAGS.mapNotNull { t -> e.getAttribute(t)?.let { t to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun xmp(f: File): String? = runCatching {
        if (Build.VERSION.SDK_INT < 34) return null
        ExifInterface(f.absolutePath).getAttribute(ExifInterface.TAG_XMP)
    }.getOrNull()

    /** The MediaStore row of the file (date taken, bucket, owner package): null fields when the platform has no row or no such column. */
    private fun mediaStore(ctx: Context, f: File): Map<String, Any?> = runCatching {
        val cols = buildList {
            add(MediaStore.MediaColumns.DATE_TAKEN); add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.RELATIVE_PATH); add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        }.toTypedArray()
        ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, MediaStore.MediaColumns.DATA + "=?", arrayOf(f.absolutePath), null)?.use { c ->
            if (!c.moveToFirst()) return@use emptyMap<String, Any?>()
            fun s(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { c.getString(it) }
            fun l(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            mapOf(
                "date_taken_ms" to l(MediaStore.MediaColumns.DATE_TAKEN), "bucket" to s(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                "date_added_s" to l(MediaStore.MediaColumns.DATE_ADDED), "relative_path" to s(MediaStore.MediaColumns.RELATIVE_PATH),
                "display_name" to s(MediaStore.MediaColumns.DISPLAY_NAME), "owner_package" to (if (Build.VERSION.SDK_INT >= 29) s(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) else null),
            )
        }
    }.getOrNull() ?: emptyMap()
}
