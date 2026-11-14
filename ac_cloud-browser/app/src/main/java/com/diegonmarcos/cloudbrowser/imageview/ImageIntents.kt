package com.diegonmarcos.cloudbrowser.imageview

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * #1741 a picture another app hands Cloud Browser (ACTION_VIEW or ACTION_SEND of an image type, content:// or file://) is shown by
 * the browser's own web view on a small page ([ImagePage]). The page lives under the cache's `mhtml/` folder, the one folder
 * the host reads local files from. A file:// picture is shown in place, with the images of its folder as previous / next;
 * a content:// picture is copied to the cache (size capped) and, for a MediaStore one, so are its neighbours of the same
 * album (count and bytes capped). Never throws: any failure answers null and the caller falls through to its other handlers.
 */
object ImageIntents {
    const val MAX_COPY_BYTES = 24L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 48L * 1024 * 1024
    const val MAX_SIBLINGS = 200
    const val WINDOW = 10
    const val DIR = "mhtml/imageview"

    /** The uri an intent is about: VIEW data, or the SEND stream. Null when it is neither. */
    fun source(intent: Intent?): Uri? {
        val i = intent ?: return null
        return when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }?.takeIf { it.scheme == "content" || it.scheme == "file" }
    }

    /** What a camera or gallery app reports as the type of a name, when it gives none. */
    fun mimeOfName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "bmp" -> "image/bmp"; "svg" -> "image/svg+xml"; "heic" -> "image/heic"; "heif" -> "image/heif"; "avif" -> "image/avif"
        "ico" -> "image/x-icon"; "wbmp" -> "image/vnd.wap.wbmp"; else -> "image/*"
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "content") runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /** EXIF basics (camera, time, exposure); never the location. Empty when the file has none or is not a JPEG/HEIC/WebP. */
    fun exifOf(stream: InputStream): List<Pair<String, String>> = runCatching {
        val x = android.media.ExifInterface(stream)
        val rows = ArrayList<Pair<String, String>>()
        fun add(label: String, tag: String) { x.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { rows += label to it } }
        val make = x.getAttribute(android.media.ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = x.getAttribute(android.media.ExifInterface.TAG_MODEL)?.trim().orEmpty()
        if (make.isNotEmpty() || model.isNotEmpty()) rows += "Camera" to listOf(make, model).filter { it.isNotEmpty() }.joinToString(" ")
        add("Taken", android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
        add("Exposure", android.media.ExifInterface.TAG_EXPOSURE_TIME)
        add("Aperture f/", android.media.ExifInterface.TAG_F_NUMBER)
        add("ISO", "ISOSpeedRatings")
        add("Focal length", android.media.ExifInterface.TAG_FOCAL_LENGTH)
        add("Orientation", android.media.ExifInterface.TAG_ORIENTATION)
        rows
    }.getOrDefault(emptyList())

    private fun fileEntry(f: File): ImgEntry = ImgEntry(
        Uri.fromFile(f).toString(), f.name, f.length(), mimeOfName(f.name),
        runCatching { f.inputStream().use { exifOf(it) } }.getOrDefault(emptyList()),
    )

    /** The images of [file]'s folder in name order, windowed around it; the index of [file] in the list. */
    fun folderEntries(file: File): Pair<List<ImgEntry>, Int> {
        val siblings = runCatching {
            file.parentFile?.listFiles { f -> f.isFile && ImagePage.isImageName(f.name) }?.sortedBy { it.name.lowercase() }
        }.getOrNull().orEmpty().take(MAX_SIBLINGS)
        val list = if (siblings.any { it.name == file.name }) siblings else listOf(file)
        val at = list.indexOfFirst { it.name == file.name }.coerceAtLeast(0)
        return list.map { fileEntry(it) } to at
    }

    private fun copyCapped(ctx: Context, uri: Uri, into: File, cap: Long): Long? {
        val n = runCatching {
            var total = 0L
            val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching -1L
            input.use { i ->
                into.outputStream().use { o ->
                    val b = ByteArray(16 * 1024)
                    while (true) {
                        val r = i.read(b)
                        if (r < 0) break
                        total += r
                        if (total > cap) return@runCatching -1L
                        o.write(b, 0, r)
                    }
                }
            }
            total
        }.getOrDefault(-1L)
        if (n < 0) { runCatching { into.delete() }; return null }
        return n
    }

    private fun contentEntry(ctx: Context, uri: Uri, name: String, mime: String, dir: File, n: Int): ImgEntry? {
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in ImagePage.EXTENSIONS } ?: mime.substringAfter('/').substringBefore('+').takeIf { it.isNotEmpty() && it != "*" } ?: "img"
        val out = File(dir, "i$n.$ext")
        val size = copyCapped(ctx, uri, out, MAX_COPY_BYTES) ?: return null
        val exif = runCatching { out.inputStream().use { exifOf(it) } }.getOrDefault(emptyList())
        return ImgEntry(Uri.fromFile(out).toString(), name, size, mime, exif)
    }

    /** The neighbours of a MediaStore picture (same album, name order), as (uri, name) pairs with the index of the picture; null when not a MediaStore image or not readable. */
    private fun mediaAlbum(ctx: Context, uri: Uri): Pair<List<Pair<Uri, String>>, Int>? = runCatching {
        if (uri.authority != MediaStore.AUTHORITY || !uri.path.orEmpty().contains("/images/")) return@runCatching null
        val id = ContentUris.parseId(uri)
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var bucket: Long? = null
        ctx.contentResolver.query(ContentUris.withAppendedId(base, id), arrayOf(MediaStore.Images.Media.BUCKET_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) bucket = c.getLong(0)
        }
        val b = bucket ?: return@runCatching null
        val rows = ArrayList<Pair<Uri, String>>()
        ctx.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.BUCKET_ID}=?", arrayOf(b.toString()), "${MediaStore.Images.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            while (c.moveToNext() && rows.size < MAX_SIBLINGS * 5) rows += ContentUris.withAppendedId(base, c.getLong(0)) to (c.getString(1) ?: "image")
        }
        val at = rows.indexOfFirst { ContentUris.parseId(it.first) == id }
        if (at < 0) null else rows to at
    }.getOrNull()

    /**
     * Turns a picture VIEW/SEND intent into the file:// URL of the image page, off the main thread; [done] runs on the main
     * thread with it, or null when the intent is not an image (or could not be read).
     */
    fun open(ctx: Context, intent: Intent?, done: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val uri = source(intent)
        if (uri == null) { done(null); return }
        val appCtx = ctx.applicationContext
        val declared = intent?.type
        Thread {
            val url = runCatching {
                val name = displayName(appCtx, uri) ?: "image"
                val mime = (declared ?: if (uri.scheme == "content") appCtx.contentResolver.getType(uri) else null)?.takeIf { it.isNotBlank() }
                if (!ImagePage.isImage(mime, name)) return@runCatching null
                val dir = File(appCtx.cacheDir, DIR).apply { deleteRecursively(); mkdirs() }
                val (entries, at) = if (uri.scheme == "file") {
                    val f = File(uri.path ?: return@runCatching null)
                    if (f.isFile) folderEntries(f) else return@runCatching null
                } else {
                    val album = mediaAlbum(appCtx, uri)
                    var budget = MAX_TOTAL_BYTES
                    var n = 0
                    val list = ArrayList<ImgEntry>()
                    var cur = 0
                    val picks = if (album == null) listOf(uri to name) to 0 else {
                        val from = (album.second - WINDOW).coerceAtLeast(0)
                        album.first.subList(from, (album.second + WINDOW + 1).coerceAtMost(album.first.size)) to (album.second - from)
                    }
                    // the picture asked for first, so its copy is never starved by the budget; the neighbours follow in order
                    val order = listOf(picks.second) + picks.first.indices.filter { it != picks.second }
                    val byIndex = HashMap<Int, ImgEntry>()
                    for (k in order) {
                        if (budget <= 0) break
                        val (u, nm) = picks.first[k]
                        val e = contentEntry(appCtx, u, nm, if (k == picks.second) (mime ?: mimeOfName(nm)) else mimeOfName(nm), dir, n++) ?: continue
                        budget -= e.size
                        byIndex[k] = e
                    }
                    byIndex.toSortedMap().forEach { (k, e) -> if (k == picks.second) cur = list.size; list += e }
                    list to cur
                }
                if (entries.isEmpty()) return@runCatching null
                val page = File(dir, "view.html")
                page.writeText(ImagePage.html(entries, at), Charsets.UTF_8)
                Uri.fromFile(page).toString()
            }.getOrNull()
            main.post { done(url) }
        }.start()
    }

    const val MAX_HTML_BYTES = 8L * 1024 * 1024

    /**
     * #1741 the fleet's Convert to PDF door handing an HTML file: it is copied (size capped) to the one cache folder the host reads
     * local files from and that file:// URL answers, so the page is shown rendered (not as source) and can be printed. [done] runs on
     * the main thread; null when it is not an HTML file or could not be read.
     */
    fun openHtml(ctx: Context, intent: Intent?, done: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val uri = source(intent)
        if (uri == null) { done(null); return }
        val appCtx = ctx.applicationContext
        val declared = intent?.type
        Thread {
            val url = runCatching {
                val name = displayName(appCtx, uri) ?: "page.html"
                val mime = (declared ?: if (uri.scheme == "content") appCtx.contentResolver.getType(uri) else null).orEmpty().substringBefore(';').lowercase()
                val ext = name.substringAfterLast('.', "").lowercase()
                if (mime != "text/html" && !(mime.isEmpty() && (ext == "html" || ext == "htm"))) return@runCatching null
                val dir = File(appCtx.cacheDir, DIR).apply { deleteRecursively(); mkdirs() }
                val out = File(dir, "page.html")
                if (uri.scheme == "file") {
                    val f = File(uri.path ?: return@runCatching null)
                    if (!f.isFile || f.length() > MAX_HTML_BYTES) return@runCatching null
                    f.copyTo(out, overwrite = true)
                } else if (copyCapped(appCtx, uri, out, MAX_HTML_BYTES) == null) return@runCatching null
                Uri.fromFile(out).toString()
            }.getOrNull()
            main.post { done(url) }
        }.start()
    }
}
