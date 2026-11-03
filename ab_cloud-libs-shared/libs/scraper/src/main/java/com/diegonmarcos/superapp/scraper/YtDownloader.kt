package com.diegonmarcos.superapp.scraper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.diegonmarcos.superapp.mediaconvert.audio.AudioExtract
import java.io.File

/** #1428 runs one YouTube request: API download, optional on-device audio extract, then delivery. BLOCKING. */
object YtDownloader {
    fun externalDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Scrapers").apply { mkdirs() }

    /** Returns a human line saying where the file went. [chosen] is the picked document for [YtTarget.CHOOSE]. */
    fun run(ctx: Context, r: YtRequest, chosen: Uri?, api: ScraperApi = ScraperApi(), progress: (Long, Long) -> Unit = { _, _ -> }): String {
        val tmpDir = File(ctx.cacheDir, "scraper-dl").apply { mkdirs() }
        fun fetch(serverExtract: Boolean): Pair<File, String> = api.downloadYoutube(YtTool.body(r, serverExtract)) { input, d ->
            val out = File(tmpDir, d.fileName)
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var n: Int; var done = 0L
                while (input.read(buf).also { n = it } >= 0) { o.write(buf, 0, n); done += n; progress(done, d.length) }
            }
            out to d.mime
        }
        var (file, mime) = fetch(serverExtract = false)
        if (YtTool.extractsOnDevice(r)) {
            val local = runCatching { AudioExtract.extract(file, File(tmpDir, file.nameWithoutExtension + "-audio")) }.getOrNull()
            file.delete()
            if (local != null) { file = local.first; mime = local.second } else { val s = fetch(serverExtract = true); file = s.first; mime = s.second }
        }
        try {
            return when (r.target) {
                YtTarget.EXTERNAL -> { val dst = File(externalDir(ctx), file.name); file.copyTo(dst, overwrite = true); "Saved to ${dst.path}" }
                YtTarget.DOWNLOADS -> "Saved to Downloads/${toDownloads(ctx, file, mime)}"
                YtTarget.CHOOSE -> {
                    val uri = chosen ?: throw IllegalStateException("no place chosen")
                    ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                        ?: throw IllegalStateException("cannot write to the chosen place")
                    "Saved to the chosen place"
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun toDownloads(ctx: Context, f: File, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Downloads refused the file")
            ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        } else {
            @Suppress("DEPRECATION")
            f.copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), f.name), overwrite = true)
        }
        return f.name
    }
}
