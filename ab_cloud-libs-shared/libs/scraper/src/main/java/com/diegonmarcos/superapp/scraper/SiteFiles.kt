package com.diegonmarcos.superapp.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where a file a site left on the phone came from. */
enum class FileSource(val label: String) { CACHE("Cache"), DOWNLOAD("Download"), SAVED("Saved page"), STORAGE("Storage") }

/** One file a site left on the phone. [path] is the file on disk; for a cache entry the body is a slice of it. */
data class SiteFile(
    val host: String,
    val name: String,
    val path: String,
    val source: FileSource,
    val bytes: Long,
    val modified: Long,
    val url: String = "",
    val mime: String = "",
    /** The folder inside the site ("css", "static/js", "WebView storage/Local Storage"), "/"-separated; "" = the site's top. */
    val dir: String = "",
)

/** A site and its files, with the two dates the list sorts by. */
data class SiteGroup(val host: String, val files: List<SiteFile>, val lastVisit: Long, val lastUpdated: Long) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

enum class SiteSort(val label: String) { NAME("Name"), LAST_VISIT("Last visit"), LAST_UPDATED("Last updated") }

/**
 * #1427 Site Files: every file per site from the WebView cache, the downloads and the saved pages.
 * Pure (JVM-tested): the Android side only hands in file bytes and the stores' JSON.
 */
object SiteFiles {
    /** The pseudo-site that holds files no site owns: the WebView's own storage and the app's overrides.css. */
    const val APP_HOST = "App files"

    /** The folders of a URL's path below its site ("css/v2" for https://a.com/css/v2/x.css), "" for a file at the top. */
    fun dirOf(url: String): String {
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return path.split('/').dropLast(1).filter { it.isNotBlank() }
            .joinToString("/") { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    /** The files of [groups] as the shared tree's entries: site, then the file's folders, then the file (downloads and saved pages get a folder of their own). */
    fun entries(groups: List<SiteGroup>): List<com.diegonmarcos.superapp.uikit.KitFileTree.Entry<SiteFile>> = groups.flatMap { g ->
        g.files.map { f ->
            val sub = f.dir.ifBlank { when (f.source) { FileSource.DOWNLOAD -> "Downloads"; FileSource.SAVED -> "Saved pages"; else -> "" } }
            com.diegonmarcos.superapp.uikit.KitFileTree.Entry(listOf(g.host) + sub.split('/').filter { it.isNotBlank() }, f.name, f.bytes, f.modified,
                com.diegonmarcos.superapp.uikit.KitFileTree.typeOf(f.name, f.mime), f)
        }
    }

    /** The site a URL belongs to: its host, lower case, without a leading www. Blank when there is none. */
    fun hostOf(url: String): String {
        val h = runCatching { URI(url.trim()).host }.getOrNull() ?: return ""
        return h.lowercase().removePrefix("www.")
    }

    /** A display name for a URL: its last path segment, or index.html for a bare origin/folder. */
    fun nameOf(url: String): String {
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        val last = path.substringAfterLast('/')
        return if (last.isBlank()) "index.html" else runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    /** Groups [files] by site; [visits] are (url, ts) rows of the browsing history. Files with no site are dropped. */
    fun group(files: List<SiteFile>, visits: List<Pair<String, Long>>): List<SiteGroup> {
        val lastVisit = HashMap<String, Long>()
        for ((u, ts) in visits) {
            val h = hostOf(u)
            if (h.isNotEmpty() && ts > (lastVisit[h] ?: Long.MIN_VALUE)) lastVisit[h] = ts
        }
        return files.filter { it.host.isNotBlank() }.groupBy { it.host }.map { (h, fs) ->
            SiteGroup(h, fs, lastVisit[h] ?: 0L, fs.maxOf { it.modified })
        }
    }

    /** Sorts the sites, and each site's files, by [by]: name A-Z, or newest first (ties by name). */
    fun sort(groups: List<SiteGroup>, by: SiteSort): List<SiteGroup> {
        val files: (List<SiteFile>) -> List<SiteFile> = { fs ->
            when (by) {
                SiteSort.NAME -> fs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                else -> fs.sortedWith(compareByDescending<SiteFile> { it.modified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }
        val sorted = when (by) {
            SiteSort.NAME -> groups.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.host })
            SiteSort.LAST_VISIT -> groups.sortedWith(compareByDescending<SiteGroup> { it.lastVisit }.thenBy { it.host })
            SiteSort.LAST_UPDATED -> groups.sortedWith(compareByDescending<SiteGroup> { it.lastUpdated }.thenBy { it.host })
        }
        return sorted.map { it.copy(files = files(it.files)) }
    }

    /** The browser's downloads (prefs browser_downloads / jobs_v2): rows whose file exists per [exists]. */
    fun downloads(json: String?, exists: (String) -> Pair<Long, Long>?): List<SiteFile> {
        val arr = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("file").ifBlank { return@mapNotNull null }
            val (len, mod) = exists(path) ?: return@mapNotNull null
            val url = o.optString("url")
            SiteFile(hostOf(url), o.optString("fileName").ifBlank { File(path).name }, path, FileSource.DOWNLOAD, len, mod, url, o.optString("mime"))
        }
    }

    /** The saved pages (filesDir/offline/index.json): each page's archive under [root]/<id>/. */
    fun saved(json: String?, root: File, exists: (String) -> Pair<Long, Long>?): List<SiteFile> {
        val arr = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        val out = ArrayList<SiteFile>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("id")
            if (id.isBlank()) continue
            val pages = s.optJSONArray("pages") ?: continue
            for (j in 0 until pages.length()) {
                val p = pages.optJSONObject(j) ?: continue
                val f = p.optString("file")
                if (f.isBlank()) continue
                val path = if (f.startsWith("/")) f else File(File(root, id), f).path
                val st = exists(path) ?: continue
                val (len, mod) = st
                val url = p.optString("url").ifBlank { s.optString("start") }
                val title = p.optString("title").ifBlank { File(f).name }
                out += SiteFile(hostOf(url.ifBlank { s.optString("origin") }), title.let { if (it.endsWith(".mhtml")) it else "$it.mhtml" },
                    path, FileSource.SAVED, len, mod, url, "multipart/related")
            }
        }
        return out
    }

    /** The browsing history (prefs browser_history / history_json) as (url, ts) rows. */
    fun visits(json: String?): List<Pair<String, Long>> {
        val arr = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o: JSONObject = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("url").takeIf { it.isNotBlank() }?.let { it to o.optLong("ts") }
        }
    }
}

/**
 * One entry file (`<hash>_0`) of Chromium's simple HTTP cache, the format the WebView writes:
 * a 24-byte header (initial magic, version, key length, key hash), the key, stream 1 (the body),
 * an EOF record (final magic, flags, crc, stream size), then stream 0 (the response headers).
 */
object SimpleCache {
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
