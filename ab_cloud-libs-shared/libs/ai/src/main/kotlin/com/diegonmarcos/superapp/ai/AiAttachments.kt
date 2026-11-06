package com.diegonmarcos.superapp.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** One attachment of a message: a file on the phone (copied under the app's cache or files dir, never the picked URI). */
data class AiAttachment(val id: String, val name: String, val mime: String, val path: String, val bytes: Long) {
    val image: Boolean get() = mime.startsWith("image/")
    val text: Boolean get() = mime.startsWith("text/") || mime in AiAttachments.TEXT_MIMES
    val file: File get() = File(path)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("mime", mime).put("path", path).put("bytes", bytes)

    companion object {
        fun fromJson(o: JSONObject) = AiAttachment(o.getString("id"), o.optString("name"), o.optString("mime"), o.getString("path"), o.optLong("bytes"))
        fun list(a: JSONArray?): List<AiAttachment> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::fromJson)
    }
}

/** The attachment as the wire carries it: OpenAI-style content parts (text, image_url, file); plain text when nothing but text is attached. */
object AiAttachWire {
    const val MAX_TEXT = 60_000

    fun dataUrl(mime: String, bytes: ByteArray): String = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** [text] with its [atts]: a string when no part is needed, else the content array. [read] gives a file's bytes (null = unreadable). */
    fun content(text: String, atts: List<AiAttachment>, read: (AiAttachment) -> ByteArray?): Any {
        if (atts.isEmpty()) return text
        val inline = StringBuilder(text)
        val parts = JSONArray()
        for (a in atts) {
            val b = read(a)
            when {
                b == null -> inline.append("\n[attached: ").append(a.name).append(" (unreadable)]")
                a.image -> parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl(a.mime, b))))
                a.mime == "application/pdf" -> parts.put(JSONObject().put("type", "file").put("file", JSONObject().put("filename", a.name).put("file_data", dataUrl(a.mime, b))))
                a.text -> inline.append("\n\n[file ").append(a.name).append("]\n").append(String(b, Charsets.UTF_8).take(MAX_TEXT))
                else -> inline.append("\n[attached: ").append(a.name).append(" (").append(a.mime).append(", ").append(b.size).append(" bytes)]")
            }
        }
        if (parts.length() == 0) return inline.toString()
        val all = JSONArray().put(JSONObject().put("type", "text").put("text", inline.toString()))
        for (i in 0 until parts.length()) all.put(parts.get(i))
        return all
    }
}

/** Images a model returned: the data URL or https URL of `message.images[]`, decoded and written under a directory the app owns. */
object AiImages {
    /** (mime, bytes) of a base64 data URL; null for anything else. */
    fun decodeDataUrl(url: String): Pair<String, ByteArray>? {
        val u = url.trim()
        if (!u.startsWith("data:")) return null
        val comma = u.indexOf(',')
        if (comma < 0) return null
        val head = u.substring(5, comma)
        if (!head.endsWith(";base64")) return null
        val mime = head.substringBefore(';').ifEmpty { return null }
        val bytes = runCatching { Base64.getMimeDecoder().decode(u.substring(comma + 1)) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return mime to bytes
    }

    /** The mime of image bytes by their first bytes; null when they are not a known image. */
    fun sniff(b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size > 6 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
        else -> null
    }

    fun extension(mime: String): String = when (mime.lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }

    /** The images of a chat-completions message: `images[].image_url.url` (OpenRouter), or `images[].url`; and `content` parts of type image_url. */
    fun urls(message: JSONObject?): List<String> {
        if (message == null) return emptyList()
        val out = ArrayList<String>()
        message.optJSONArray("images")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val u = o.optJSONObject("image_url")?.optString("url").orEmpty().ifEmpty { o.optString("url") }
                if (u.isNotBlank()) out.add(u)
            }
        }
        (message.opt("content") as? JSONArray)?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("type") == "image_url") o.optJSONObject("image_url")?.optString("url")?.takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out.distinct()
    }

    fun download(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection()
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.getInputStream().use { it.readBytes() }
    }.getOrNull()
}

/** Writes the images of an answer into [dir] and returns them as attachments; [download] fetches an https URL (blocking). */
class AiImageStore(private val dir: File, private val download: (String) -> ByteArray? = AiImages::download) {
    fun save(url: String): AiAttachment? {
        val decoded = AiImages.decodeDataUrl(url)
            ?: (if (url.startsWith("https://")) download(url)?.let { b -> (AiImages.sniff(b) ?: return null) to b } else null)
            ?: return null
        val (mime, bytes) = decoded
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val f = File(dir, "ai-image-" + id.take(8) + "." + AiImages.extension(mime))
        f.writeBytes(bytes)
        return AiAttachment(id, f.name, mime, f.path, bytes.size.toLong())
    }
}

/** Importing, capturing, saving and sharing attachments on the phone. The picked URI is copied at once; a chip and a sent message hold the copy. */
object AiAttachments {
    const val DIR = "ai-attach"
    const val IMAGES = "ai-images"
    const val AUTHORITY_SUFFIX = ".aifiles"
    val TEXT_MIMES = setOf("application/json", "application/xml", "application/x-yaml", "application/javascript", "application/x-sh", "application/sql", "application/toml")

    private val BY_EXT = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif", "heic" to "image/heic",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "html" to "text/html", "json" to "application/json",
        "xml" to "application/xml", "yaml" to "application/x-yaml", "yml" to "application/x-yaml", "log" to "text/plain", "kt" to "text/plain", "py" to "text/plain",
        "js" to "text/plain", "sh" to "application/x-sh", "mp3" to "audio/mpeg", "wav" to "audio/wav", "mp4" to "video/mp4",
    )

    /** The mime of a file: the one the provider declared, else by extension, else octet-stream. */
    fun mimeOf(name: String, declared: String?): String =
        declared?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: BY_EXT[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"

    fun safeName(n: String): String = n.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "file" }

    /** Blocking: copies [uri] under the cache dir; a file over [maxBytes] is refused with a Failure naming its size. */
    fun import(ctx: Context, uri: Uri, maxBytes: Long): Result<AiAttachment> = runCatching {
        val r = ctx.contentResolver
        var name = uri.lastPathSegment.orEmpty()
        r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst() && c.getColumnIndex(OpenableColumns.DISPLAY_NAME) >= 0) name = c.getString(0) ?: name }
        name = safeName(name)
        val dir = File(ctx.cacheDir, DIR).also { it.mkdirs() }
        val id = UUID.randomUUID().toString()
        val f = File(dir, id.take(8) + "-" + name)
        var total = 0L
        (r.openInputStream(uri) ?: error("cannot open $name")).use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) { out.close(); f.delete(); error("$name is larger than ${maxBytes / 1_000_000} MB") }
                    out.write(buf, 0, n)
                }
            }
        }
        AiAttachment(id, name, mimeOf(name, r.getType(uri)), f.path, total)
    }

    /** A camera shot: the temp file the camera app writes and the content URI it is handed. */
    class Capture(val file: File, val uri: Uri)

    fun capture(ctx: Context): Capture {
        val dir = File(ctx.cacheDir, DIR).also { it.mkdirs() }
        val f = File(dir, "camera-" + System.currentTimeMillis() + ".jpg")
        return Capture(f, uri(ctx, f))
    }

    /** The captured file as an attachment, or null when the camera wrote nothing. */
    fun fromCapture(c: Capture): AiAttachment? =
        if (c.file.exists() && c.file.length() > 0) AiAttachment(UUID.randomUUID().toString(), c.file.name, "image/jpeg", c.file.path, c.file.length()) else null

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, f)

    fun imageStore(ctx: Context) = AiImageStore(File(ctx.filesDir, IMAGES))

    /** Saves an image into the gallery's Pictures/AI (no permission from Android 10); returns where, or null when it failed. */
    fun saveToGallery(ctx: Context, a: AiAttachment): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, a.name)
                put(MediaStore.MediaColumns.MIME_TYPE, a.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AI")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("no gallery")
            (r.openOutputStream(u) ?: error("no gallery")).use { out -> a.file.inputStream().use { it.copyTo(out) } }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(u, v, null, null)
            u.toString()
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: error("no storage")
            File(dir, a.name).also { a.file.copyTo(it, overwrite = true) }.path
        }
    }.getOrNull()

    /** The system share sheet for [a]. */
    fun share(ctx: Context, a: AiAttachment): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).setType(a.mime).putExtra(Intent.EXTRA_STREAM, uri(ctx, a.file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** The provider the share sheet and the camera go through (its own class: an app's own FileProvider declaration never collides with it). */
class AiFileProvider : FileProvider()
