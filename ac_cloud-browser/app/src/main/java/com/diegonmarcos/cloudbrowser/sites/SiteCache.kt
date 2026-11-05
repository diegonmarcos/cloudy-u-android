package com.diegonmarcos.cloudbrowser.sites

import android.content.Context
import com.diegonmarcos.superapp.uikit.KitFileTree
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One file a site left in the WebView's cache or storage. [path] is the file on disk; for a cache entry the body is a slice of it. */
data class SiteFile(
    val host: String, val name: String, val path: String, val bytes: Long, val modified: Long,
    val url: String = "", val mime: String = "",
    /** The folder inside the site ("css", "static/js", "WebView storage/IndexedDB"), "/"-separated; "" = the site's top. */
    val dir: String = "",
)

/**
 * #1493 Files Cached: what the WebView holds per site, read as files. The HTTP cache entries (Chromium's simple cache, both
 * places WebView keeps it) are named by the page address they hold; an origin's IndexedDB folder belongs to its site; the
 * rest of the WebView's storage has no owner and sits under [APP_HOST]. Parsing is pure and JVM-tested (SiteCacheTest).
 */
object SiteCache {
    /** The pseudo-site that holds files no site owns. */
    const val APP_HOST = "App files"
    private const val MAX_ENTRY = 8L shl 20
    private val ENTRY = Regex("^[0-9a-f]{16}_0$")
    private val ORIGIN_DIR = Regex("^https?_(.+)_\\d+\\.(indexeddb\\.(leveldb|blob))$")

    fun dirOf(url: String): String {
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return path.split('/').dropLast(1).filter { it.isNotBlank() }
            .joinToString("/") { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    fun hostOf(url: String): String = VisitLog.domainOf(url)

    fun nameOf(url: String): String {
        val last = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty().substringAfterLast('/')
        return if (last.isBlank()) "index.html" else runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    /** The site an IndexedDB / storage folder name belongs to (`https_example.org_0.indexeddb.leveldb` -> example.org), else null. */
    fun hostOfOriginDir(name: String): String? = ORIGIN_DIR.find(name)?.groupValues?.get(1)?.removePrefix("www.")

    /** The files as the shared tree's entries: site, then the file's folders, then the file. */
    fun entries(files: List<SiteFile>): List<KitFileTree.Entry<SiteFile>> = files.map { f ->
        KitFileTree.Entry(listOf(f.host) + f.dir.split('/').filter { it.isNotBlank() }, f.name, f.bytes, f.modified, KitFileTree.typeOf(f.name, f.mime), f)
    }

    /** Sites A to Z (the pseudo-site last), each with its files. */
    fun hosts(files: List<SiteFile>): List<String> = files.map { it.host }.distinct().sortedWith(compareBy({ it == APP_HOST }, { it.lowercase() }))

    fun cached(files: List<SiteFile>): List<CachedFile> = files.filter { it.url.isNotEmpty() }.map { CachedFile(it.host, it.path, it.mime) }

    /** BLOCKING (file walks): off the main thread. */
    fun load(ctx: Context): List<SiteFile> {
        val out = ArrayList<SiteFile>()
        val roots = listOf("WebView cache" to File(ctx.cacheDir, "WebView"), "WebView storage" to File(ctx.dataDir, "app_webview"))
        for ((label, root) in roots) {
            for (f in root.walkTopDown().maxDepth(8).filter { it.isFile }.take(20000)) {
                if (ENTRY.matches(f.name)) {
                    if (f.length() > MAX_ENTRY) continue
                    val e = runCatching { parse(f.readBytes()) }.getOrNull() ?: continue
                    out += SiteFile(hostOf(e.url), nameOf(e.url), f.path, e.bodyBytes.toLong(), f.lastModified(), e.url, e.contentType, dirOf(e.url))
                } else {
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    val owner = rel.split('/').firstNotNullOfOrNull { hostOfOriginDir(it) }
                    out += SiteFile(owner ?: APP_HOST, f.name, f.path, f.length(), f.lastModified(), "", "",
                        if (owner != null) label + "/" + rel.substringBeforeLast('/', "") else listOf(label, rel.substringBeforeLast('/', "")).filter { it.isNotEmpty() }.joinToString("/"))
                }
            }
        }
        return out.distinctBy { it.path }
    }

    /** The bytes a viewer would show: a cache entry's body cut out (and un-gzipped), else the file itself. */
    fun body(f: SiteFile): ByteArray {
        val raw = File(f.path).readBytes()
        val e = if (f.url.isEmpty()) null else parse(raw) ?: return raw
        if (e == null) return raw
        val b = raw.copyOfRange(e.bodyStart, e.bodyEnd)
        return if (e.contentEncoding == "gzip" || (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()))
            runCatching { java.util.zip.GZIPInputStream(b.inputStream()).readBytes() }.getOrDefault(b) else b
    }

    /**
     * One entry file (`<hash>_0`): a 24-byte header (initial magic, version, key length, key hash), the key, stream 1 (the body),
     * an EOF record, then stream 0 (the response headers).
     */
    const val INITIAL_MAGIC: Long = -0x0304_92e4_588d_a3d0L   // 0xfcfb6d1ba7725c30
    const val FINAL_MAGIC: Long = -0x0b05_90ba_68f2_be28L     // 0xf4fa6f45970d41d8
    const val HEADER = 24

    data class Entry(val url: String, val bodyStart: Int, val bodyEnd: Int, val contentType: String, val contentEncoding: String) {
        val bodyBytes: Int get() = bodyEnd - bodyStart
    }

    fun parse(b: ByteArray): Entry? {
        if (b.size < HEADER) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getLong(0) != INITIAL_MAGIC) return null
        val keyLen = bb.getInt(12)
        if (keyLen <= 0 || HEADER + keyLen > b.size) return null
        val key = String(b, HEADER, keyLen, Charsets.UTF_8)
        val url = urlOfKey(key) ?: return null
        val start = HEADER + keyLen
        val end = bodyEnd(b, bb, start)
        if (end < 0) return null
        val headers = String(b, end, b.size - end, Charsets.ISO_8859_1)
        return Entry(url, start, end, header(headers, "content-type"), header(headers, "content-encoding"))
    }

    /**
     * Where the body (stream 1) ends. The file ends with stream 0's EOF record, whose stream_size is the headers' length
     * (the stream-1 EOF record carries no size, so it must NOT be matched on one: that matched nothing in a real cache,
     * which left Site Files empty). So walk back from the tail: last EOF, minus the headers (and the key SHA-256 when
     * flagged), minus the stream-1 EOF record = the body's end. Else the first EOF record after the key.
     */
    private fun bodyEnd(b: ByteArray, bb: ByteBuffer, start: Int): Int {
        for (rec in intArrayOf(24, 20)) {
            val e0 = b.size - rec
            if (e0 < start || bb.getLong(e0) != FINAL_MAGIC) continue
            val sha = if (bb.getInt(e0 + 8) and 2 != 0) 32 else 0
            val h = bb.getInt(e0 + 16)
            for (eof1 in intArrayOf(24, 20)) {
                val end = e0 - sha - h - eof1
                if (h >= 0 && end >= start && bb.getLong(end) == FINAL_MAGIC) return end
            }
        }
        var i = start
        while (i + 20 <= b.size) { if (bb.getLong(i) == FINAL_MAGIC) return i; i++ }
        return -1
    }

    /** The URL in a cache key: the last space-separated part (keys may carry an isolation prefix). */
    fun urlOfKey(key: String): String? {
        val last = key.trim().substringAfterLast(' ').substringAfter("_dk_")
        return last.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun header(raw: String, name: String): String {
        val m = Regex("(?i)(?:^|\u0000|\n)$name:\\s*([^\u0000\r\n]*)").find(raw) ?: return ""
        return m.groupValues[1].trim().substringBefore(';').lowercase()
    }
}
