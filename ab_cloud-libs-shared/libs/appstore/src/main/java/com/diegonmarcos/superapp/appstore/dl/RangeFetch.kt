package com.diegonmarcos.superapp.appstore.dl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1517 ONE ATTEMPT AT ONE URL, resumable and honest about what the server did.
 *
 * Resume = `Range: bytes=<part length>-` plus `If-Range: <ETag or Last-Modified>` recorded from the first response. The server's
 * answer decides: 206 with the same validator and the right start offset appends; 200 (it ignored Range, or If-Range no longer
 * matched: the file changed) truncates and starts clean; 416 either means the part is already the whole file (Content-Range
 * `bytes * / total`) or is not a prefix of it, and then it is deleted and refetched once. A 206 whose ETag differs from the
 * recorded one is the same as a change: restart. The caller's sha256 check is the last word either way.
 *
 * Pure JVM (HttpURLConnection, java.io): a fake HTTP server drives it in a unit test.
 */
internal object RangeFetch {
    /** Raised when the engine asked this job to stop (pause or cancel). Not an error. */
    class StopSignal : RuntimeException("stopped")

    class Http(val code: Int, val url: String) : IOException("HTTP $code for $url")

    class Result(val bytes: Long, val total: Long, val etag: String, val lastModified: String)

    private const val MAX_REDIRECTS = 5
    private const val BUF = 64 * 1024

    fun fetch(
        source: DlSource,
        part: File,
        etag: String,
        lastModified: String,
        stop: () -> Boolean,
        onValidators: (etag: String, lastModified: String) -> Unit,
        onProgress: (written: Long, total: Long) -> Unit,
        connectMs: Int = 15_000,
        readMs: Int = 60_000,
    ): Result {
        var knownEtag = etag
        var knownModified = lastModified
        var restarts = 0
        while (true) {
            if (stop()) throw StopSignal()
            if (source.bytes > 0 && part.isFile && part.length() > source.bytes) part.delete()
            val have = if (part.isFile) part.length() else 0L
            val conn = open(source, have, knownEtag, knownModified, connectMs, readMs, stop)
            val code = conn.responseCode
            if (code == 416) {
                val whole = conn.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()
                conn.disconnect()
                if (have > 0 && whole != null && whole == have) return Result(have, have, knownEtag, knownModified)
                part.delete()
                if (restarts++ >= 1) throw Http(416, source.url)
                continue
            }
            if (code != 200 && code != 206) {
                conn.disconnect()
                throw Http(code, source.url)
            }
            val respEtag = conn.getHeaderField("ETag") ?: ""
            val respModified = conn.getHeaderField("Last-Modified") ?: ""
            var append = code == 206 && have > 0
            if (append) {
                val start = conn.getHeaderField("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                val changed = knownEtag.isNotEmpty() && respEtag.isNotEmpty() && respEtag != knownEtag
                if (changed || (start != null && start != have)) {
                    // Same URL, different bytes (or the wrong offset): what is on disk is not a prefix of this representation.
                    conn.disconnect()
                    part.delete()
                    if (restarts++ >= 2) throw IOException("the server keeps changing ${source.url}")
                    knownEtag = ""; knownModified = ""
                    continue
                }
            }
            // 200 (with or without a Range ask) is the whole artifact: truncate and refill.
            if (!append) part.delete()
            val startAt = if (append) have else 0L
            val newEtag = respEtag.ifEmpty { if (append) knownEtag else "" }
            val newModified = respModified.ifEmpty { if (append) knownModified else "" }
            onValidators(newEtag, newModified)
            val total = totalOf(conn, startAt, append).let { if (it > 0) it else source.bytes }
            var written = startAt
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            if (stop()) throw StopSignal()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                        out.fd.sync()
                    }
                }
            } finally {
                conn.disconnect()
            }
            // A connection that closes early without an exception is a truncated file, not a finished one.
            if (total > 0 && written != total) throw IOException("short read: $written of $total bytes")
            return Result(written, if (total > 0) total else written, newEtag, newModified)
        }
    }

    private fun open(s: DlSource, have: Long, etag: String, modified: String, connectMs: Int, readMs: Int, stop: () -> Boolean): HttpURLConnection {
        val origin = URL(s.url).host
        var current = s.url
        var hops = 0
        while (true) {
            if (stop()) throw StopSignal()
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            // Credentials only go to the host they were minted for (a presigned CDN url carries its own).
            if (URL(current).host == origin) s.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (have > 0) {
                conn.setRequestProperty("Range", "bytes=$have-")
                val v = etag.ifEmpty { modified }
                if (v.isNotEmpty()) conn.setRequestProperty("If-Range", v)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: run { conn.disconnect(); throw IOException("HTTP $code with no Location for $current") }
                conn.disconnect()
                if (++hops > MAX_REDIRECTS) throw IOException("too many redirects for ${s.url}")
                current = URL(URL(current), loc).toString()
                continue
            }
            return conn
        }
    }

    /** The whole artifact's size, from Content-Range when the answer was partial, else Content-Length; -1 when unsaid. */
    private fun totalOf(conn: HttpURLConnection, startAt: Long, append: Boolean): Long {
        if (append) conn.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()?.let { return it }
        val len = conn.contentLengthLong
        if (len < 0) return -1L
        return if (append) startAt + len else len
    }

    /** Either a bare digest or the sha256sum(1) form "<hex>  <filename>". */
    fun parseSha(body: String?): String? =
        body?.trim()?.substringBefore(' ')?.substringBefore('\n')?.trim()?.lowercase()
            ?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }

    /** The `.sha256` sidecar beside [url]. Throws when it is missing or not a digest. */
    fun sidecar(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000): String {
        val c = URL("$url.sha256").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) throw Http(c.responseCode, "$url.sha256")
            return parseSha(c.inputStream.bufferedReader().use { it.readText() }) ?: throw IOException("$url.sha256 is not a sha256 digest")
        } finally { c.disconnect() }
    }
}
