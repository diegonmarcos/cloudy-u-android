package com.diegonmarcos.cloudlib.web

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The permanent offline copy of a pinned site: content-addressed blobs under `<root>/<host>/blobs/<sha256>` and one
 * manifest per site (url -> hash, mime, headers, status). Pure JVM (no Android), so the suite holds every rule.
 * Cache-first and never expiring: server Cache-Control is ignored and nothing is evicted; only [put] (an explicit
 * refresh or a first capture) writes, and a failed refresh simply never calls it, so the old copy stays.
 *
 * #1711 three things keep the copy sound. Every file is written whole or not at all (a temp file renamed into place): a
 * process killed in the middle of a write can no longer leave half a manifest or half a blob that is then served for ever.
 * An entry remembers its size and when it was stored, so a blob that does not hold every byte is not served. And a path
 * keeps only its newest [MAX_VARIANTS] queries: a page that defeats caches asks for the same file under a new query on every
 * load (floor796: data/matrix.json?r=<random>), which otherwise grows the copy without bound; [newest] is what the copy
 * answers with for such a path when the live site does not answer.
 */
class SiteCache(private val root: File) {
    /** [stored] = when this entry was written (ms; 0 for a copy made before #1711). */
    data class Entry(val url: String, val hash: String, val mime: String, val status: Int, val headers: Map<String, String>, val stored: Long = 0L)
    data class Served(val status: Int, val mime: String, val headers: Map<String, String>, val body: ByteArray, val encoding: String? = null)
    data class Stats(val files: Int, val bytes: Long, val lastRefresh: Long)

    private fun dir(host: String) = File(root, host.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun manifestFile(host: String) = File(dir(host), "manifest.json")
    private fun blob(host: String, hash: String) = File(File(dir(host), "blobs"), hash)

    private fun load(host: String): JSONObject =
        manifestFile(host).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: JSONObject().put("entries", JSONObject()).put("refreshed", 0L)

    private fun save(host: String, m: JSONObject) {
        writeWhole(manifestFile(host), m.toString().toByteArray(Charsets.UTF_8))
    }

    /** [bytes] into [f], whole or not at all: written beside it and renamed into place, so a reader never sees half a file. */
    private fun writeWhole(f: File, bytes: ByteArray) {
        val parent = f.parentFile ?: throw java.io.IOException("no folder for " + f.name)
        parent.mkdirs()
        val tmp = File(parent, f.name + TMP + System.nanoTime())
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("cannot store " + f.name) }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Stores [body] for [url]; returns true when the stored bytes changed (new or different hash). */
    fun put(host: String, url: String, status: Int, mime: String, headers: Map<String, String>, body: ByteArray, now: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        val hash = sha256(body)
        val m = load(host)
        val key = normalise(url)
        val entries = m.getJSONObject("entries")
        val old = entries.optJSONObject(key)
        val f = blob(host, hash)
        // a blob is trusted only when it holds every byte: one cut short by a killed process is written again, never served
        if (!f.isFile || f.length() != body.size.toLong()) writeWhole(f, body)
        val h = JSONObject(); headers.forEach { (k, v) -> h.put(k, v) }
        entries.put(key, JSONObject().put("hash", hash).put("mime", mimeFor(key, mime)).put("status", status).put("headers", h)
            .put("size", body.size.toLong()).put("t", now))
        prune(host, entries, key)
        m.put("refreshed", now)
        save(host, m)
        old?.optString("hash") != hash
    }

    /**
     * A path keeps its newest [MAX_VARIANTS] queries (the entry without a query is never counted or dropped); the blobs no
     * entry refers to any more are deleted. Called with the entry just stored, so that one always stays.
     */
    private fun prune(host: String, entries: JSONObject, key: String) {
        if ('?' !in key) return
        val path = pathOf(key)
        val same = entries.keys().asSequence().filter { '?' in it && pathOf(it) == path }.toList()
        if (same.size <= MAX_VARIANTS) return
        // newest first; entries from before #1711 carry no time, among those the ones written last (later in the manifest) count as newer
        val drop = same.asReversed().sortedByDescending { if (it == key) Long.MAX_VALUE else entries.optJSONObject(it)?.optLong("t", 0L) ?: 0L }.drop(MAX_VARIANTS)
        val gone = drop.mapNotNull { entries.optJSONObject(it)?.optString("hash") }.toSet()
        drop.forEach { entries.remove(it) }
        val live = entries.keys().asSequence().mapNotNull { entries.optJSONObject(it)?.optString("hash") }.toSet()
        (gone - live).forEach { runCatching { blob(host, it).delete() } }
    }

    private fun entryOf(host: String, key: String, o: JSONObject): Entry? {
        val f = blob(host, o.optString("hash"))
        if (!f.isFile) return null
        // an entry that knows its size is served only when the blob holds exactly that many bytes
        if (o.has("size") && f.length() != o.optLong("size")) return null
        val h = o.optJSONObject("headers")
        return Entry(key, o.getString("hash"), o.getString("mime"), o.optInt("status", 200),
            h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap(), o.optLong("t", 0L))
    }

    fun lookup(host: String, url: String): Entry? {
        val key = normalise(url)
        val o = load(host).getJSONObject("entries").optJSONObject(key) ?: return null
        return entryOf(host, key, o)
    }

    /**
     * #1711 the newest stored answer for [url]'s path under ANY query (the exact address first when it is stored): what the
     * copy answers with when the page asks under a cache-defeating query that was never stored and the live site does not
     * answer. Null when the path holds nothing.
     */
    fun newest(host: String, url: String): Entry? {
        val key = normalise(url)
        val es = load(host).getJSONObject("entries")
        es.optJSONObject(key)?.let { o -> entryOf(host, key, o)?.let { return it } }
        val path = pathOf(key)
        // entries from before #1711 carry no time: among equals the one written last (later in the manifest) wins
        return es.keys().asSequence().filter { pathOf(it) == path }
            .mapNotNull { k -> es.optJSONObject(k)?.let { entryOf(host, k, it) } }
            .toList().asReversed().maxByOrNull { it.stored }
    }

    /**
     * The cached response for [url]: the stored status, mime, charset and headers, or a 206 slice for a `Range: bytes=a-b` request.
     * A WebResourceResponse carries only the headers it is given, so everything that matters is set here explicitly: Content-Length
     * (a worker computes download progress from it), the stored COOP / COEP / CORP / CSP / CORS headers, and CORS for the page's
     * [origin] (echoed with `Vary: Origin`, else `*`). Cache-Control and Expires are dropped (the copy never expires).
     */
    fun serve(host: String, url: String, range: String? = null, origin: String? = null, anyQuery: Boolean = false): Served? {
        // [anyQuery]: the exact address, else the newest stored query of the same path ([newest])
        val e = (if (anyQuery) newest(host, url) else lookup(host, url)) ?: return null
        val body = blob(host, e.hash).readBytes()
        val dropped = setOf("cache-control", "expires", "content-length", "content-type", "content-encoding", "transfer-encoding", "content-range", "age", "date")
        val headers = e.headers.filterKeys { it.lowercase() !in dropped }.toMutableMap()
        headers.keys.firstOrNull { it.equals("Access-Control-Allow-Origin", true) }?.takeIf { origin != null || headers[it] == "*" }?.let { headers.remove(it) }
        if (headers.keys.none { it.equals("Access-Control-Allow-Origin", true) }) {
            headers["Access-Control-Allow-Origin"] = origin ?: "*"
            if (origin != null) { headers["Vary"] = "Origin"; headers["Access-Control-Allow-Credentials"] = "true" }
        }
        headers["Accept-Ranges"] = "bytes"
        val encoding = charsetOf(e.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value)
        val r = range?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it.trim()) }
        if (r != null && body.isNotEmpty()) {
            val (a, b) = r.destructured
            val start = if (a.isEmpty()) (body.size - b.toInt()).coerceAtLeast(0) else a.toInt()
            val end = if (a.isEmpty() || b.isEmpty()) body.size - 1 else minOf(b.toInt(), body.size - 1)
            if (start in 0..end) {
                headers["Content-Range"] = "bytes $start-$end/${body.size}"
                headers["Content-Length"] = (end - start + 1).toString()
                return Served(206, e.mime, headers, body.copyOfRange(start, end + 1), encoding)
            }
        }
        headers["Content-Length"] = body.size.toString()
        return Served(e.status, e.mime, headers, body, encoding)
    }

    fun stats(host: String): Stats {
        val m = load(host)
        val es = m.getJSONObject("entries")
        val hashes = es.keys().asSequence().map { es.getJSONObject(it).getString("hash") }.toSet()
        return Stats(es.length(), hashes.sumOf { blob(host, it).length() }, m.optLong("refreshed"))
    }

    fun hosts(): List<String> = root.listFiles()?.filter { manifestFile(it.name).isFile }?.map { it.name }?.sorted() ?: emptyList()

    /** Every URL held for [host] (the refresh walks these). */
    fun urls(host: String): List<String> = load(host).getJSONObject("entries").keys().asSequence().sorted().toList()

    fun delete(host: String) { synchronized(LOCK) { dir(host).deleteRecursively() } }

    companion object {
        /** One writer at a time across every instance: the manifest is read, changed and written back. */
        private val LOCK = Any()
        private const val TMP = ".tmp"

        /** How many queries of one path the copy keeps (the newest win). */
        const val MAX_VARIANTS = 4

        /** The address without its query: `https://host/data/matrix.json?r=0.5` -> `https://host/data/matrix.json`. */
        fun pathOf(url: String): String = normalise(url).substringBefore('?')

        /**
         * The response headers a copy keeps (case-insensitive): what the browser acts on. Content-Type is kept whole (its charset
         * is served back), COOP / COEP / CORP keep cross-origin isolation and SharedArrayBuffer working, CORS and CSP keep the
         * page's own fetches valid. Everything else (cookies, hop-by-hop, cache validators other than ETag / Last-Modified) is dropped.
         */
        val KEPT_HEADERS: List<String> = listOf(
            "ETag", "Last-Modified", "Content-Type", "Content-Language", "Cross-Origin-Opener-Policy", "Cross-Origin-Embedder-Policy",
            "Cross-Origin-Resource-Policy", "Access-Control-Allow-Origin", "Access-Control-Expose-Headers", "Timing-Allow-Origin", "Content-Security-Policy",
        )

        fun keepHeaders(h: Map<String, String>): Map<String, String> = h.filterKeys { k -> KEPT_HEADERS.any { it.equals(k, true) } }

        /** The `charset=` of a Content-Type value, or null. */
        fun charsetOf(contentType: String?): String? =
            contentType?.let { Regex("charset=\"?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }

        /** Fragment dropped, query kept (a different query is a different resource). */
        fun normalise(url: String): String = url.substringBefore('#')

        /** `.wasm` is always application/wasm (streaming compilation refuses anything else); a blank mime falls back by extension. */
        fun mimeFor(url: String, declared: String): String {
            val path = url.substringBefore('#').substringBefore('?').lowercase()
            val d = declared.substringBefore(';').trim()
            return when {
                path.endsWith(".wasm") -> "application/wasm"
                d.isNotEmpty() && d != "application/octet-stream" -> d
                path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".html") -> "text/html"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".mp3") -> "audio/mpeg"
                else -> d.ifEmpty { "application/octet-stream" }
            }
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
