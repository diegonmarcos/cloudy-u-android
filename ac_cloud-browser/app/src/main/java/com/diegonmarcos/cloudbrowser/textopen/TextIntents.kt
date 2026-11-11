package com.diegonmarcos.cloudbrowser.textopen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File

/**
 * #1642 a text file another app hands cloud-browser (the manifest's text VIEW filters: every text type, json, xml, csv,
 * markdown, and the usual extensions for senders that give a generic type): it is shown as readable text.
 * The bytes are read with a size cap, HTML-escaped into a small page in the cache, and that page's file:// URL is
 * what the browser opens. Nothing in the file is ever run as markup or script (HTML is shown as its source).
 * Never throws: any failure answers null and the caller falls through to its other handlers.
 */
object TextIntents {
    /** A text file is shown whole up to this; the rest is cut with a visible note. */
    const val MAX_BYTES = 2 * 1024 * 1024

    private val TEXT_EXT = setOf(
        "txt", "text", "md", "markdown", "log", "json", "csv", "tsv", "conf", "cfg", "ini", "yml", "yaml",
        "xml", "html", "htm", "toml", "properties", "sh",
    )
    private val TEXT_MIME = setOf(
        "application/json", "application/xml", "application/x-yaml", "application/yaml", "application/x-sh",
        "application/x-httpd-php", "application/javascript", "application/x-javascript",
    )

    /** True for a type or file name that is text. Binary types (octet-stream, images, archives, pdf) are never text. */
    fun isText(mime: String?, name: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (m.startsWith("text/") || m in TEXT_MIME || m.endsWith("+json") || m.endsWith("+xml")) return true
        if (m.isNotEmpty() && m != "application/octet-stream" && m != "*/*") return false
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in TEXT_EXT
    }

    /** The intent is a VIEW/EDIT of a content:// or file:// uri. */
    fun isFileView(intent: Intent?): Boolean {
        val i = intent ?: return false
        if (i.action != Intent.ACTION_VIEW && i.action != Intent.ACTION_EDIT) return false
        val s = i.data?.scheme ?: return false
        return s == "content" || s == "file"
    }

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> append(c)
        }
    }

    fun page(title: String, text: String, truncated: Boolean): String =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${escape(title)}</title><style>body{margin:0;padding:12px;font:14px/1.45 monospace;background:#fff;color:#111}" +
            "pre{white-space:pre-wrap;word-wrap:break-word;margin:0}@media(prefers-color-scheme:dark){body{background:#111;color:#ddd}}" +
            ".cut{opacity:.6;margin-top:12px}</style></head><body><pre>${escape(text)}</pre>" +
            (if (truncated) "<div class=\"cut\">[truncated at ${MAX_BYTES / 1024} KiB]</div>" else "") + "</body></html>"

    private fun readCapped(i: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (out.size() < cap) {
            val n = i.read(b, 0, minOf(b.size, cap - out.size()))
            if (n < 0) break
            out.write(b, 0, n)
        }
        return out.toByteArray()
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /**
     * Turns a text VIEW intent into a file:// page URL, off the main thread; [done] runs on the main thread with the
     * URL, or null when the intent is not a text file (or could not be read).
     */
    fun open(ctx: Context, intent: Intent?, done: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (!isFileView(intent)) { done(null); return }
        val appCtx = ctx.applicationContext
        val uri = intent!!.data!!
        val type = intent.type
        Thread {
            val url = runCatching {
                val name = displayName(appCtx, uri)
                val mime = type ?: if (uri.scheme == "content") appCtx.contentResolver.getType(uri) else null
                if (!isText(mime, name)) null else {
                    val buf = appCtx.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_BYTES + 1) } ?: return@runCatching null
                    val truncated = buf.size > MAX_BYTES
                    val text = String(buf, 0, minOf(buf.size, MAX_BYTES), Charsets.UTF_8)
                    val dir = File(appCtx.cacheDir, "textopen").apply { mkdirs() }
                    dir.listFiles()?.forEach { runCatching { it.delete() } }
                    val out = File(dir, "view.html")
                    out.writeText(page(name ?: "text", text, truncated), Charsets.UTF_8)
                    Uri.fromFile(out).toString()
                }
            }.getOrNull()
            main.post { done(url) }
        }.start()
    }
}
