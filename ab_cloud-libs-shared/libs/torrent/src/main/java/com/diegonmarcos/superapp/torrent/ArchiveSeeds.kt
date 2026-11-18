package com.diegonmarcos.superapp.torrent

import java.net.HttpURLConnection
import java.net.URL

/**
 * #1790 fresh web seeds for an Internet Archive torrent (BEP 19).
 *
 * An archive.org `_archive.torrent` bakes the data server that held the item WHEN THE TORRENT WAS MADE into its `url-list`
 * (for RondoAllaTurca_201401 that is `http://ia902704.us.archive.org/28/items/`). Items move between data servers, and a moved
 * item's baked seed points at a host that no longer answers: on the CI emulator every connect to ia902704 timed out after 15-39 s
 * for the whole run, while the item lives on ia601607 / ia801607 under `/19/items/`. The torrent's other seed,
 * `https://archive.org/download/`, redirects to the live server but goes over TLS, and libtorrent's TLS setup for web seeds
 * failed on the emulator (`asio.ssl init fail (BIO routines)`). So nothing ever downloaded: 0 B and 0 peers.
 *
 * The fix asks archive.org which servers hold the item NOW (`archive.org/metadata/<identifier>`: `workable_servers` and `dir`,
 * falling back to `d1` / `d2` / `server`) and adds each as a plain-HTTP web seed, `http://<server><dir without the identifier>/`.
 * libtorrent appends the torrent's name (the identifier) and the file path, which is exactly archive.org's item layout.
 */
object ArchiveSeeds {
    private val ARCHIVE_HOST = Regex("""^[a-z]+://([a-z0-9-]+\.)*archive\.org(:\d+)?(/|$)""", RegexOption.IGNORE_CASE)
    private val IDENT = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$""")
    private val HOST = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""", RegexOption.IGNORE_CASE)

    /** The archive.org identifier of a torrent whose web seeds are archive.org's (its name is the identifier), else null. */
    fun identifier(meta: Magnet.MetaInfo): String? {
        if (meta.webSeeds.none { ARCHIVE_HOST.containsMatchIn(it.trim()) }) return null
        return meta.name.trim().takeIf { IDENT.matches(it) }
    }

    /**
     * The web seeds an archive.org metadata document names for [identifier]: one `http://<server><parent>/` per live server, in
     * the document's order, no duplicates. The item's own servers (top-level `workable_servers`, else `d1` / `d2` / `server`) go
     * with the TOP-LEVEL `dir`; `alternate_locations` (`workable`, else `servers`) carry their own `dir` each and are used only
     * when the item lists no server of its own. Run 44298351157 showed why the pairing matters: the alternate's `/0/items` dir
     * read against ia601607 / ia801607 made every file a redirect to `/19/items`, and the download stalled at 84.6%.
     */
    fun fromMetadata(json: String, identifier: String): List<String> {
        val doc = runCatching { Json(json).parse() }.getOrNull() as? Map<*, *> ?: return emptyList()
        fun hostOk(h: String) = HOST.matches(h) && (h == "archive.org" || h.endsWith(".archive.org"))
        fun parentOf(dir: Any?): String? {
            val d = (dir as? String)?.trimEnd('/') ?: return null
            if (!d.startsWith("/") || ".." in d || !d.endsWith("/$identifier")) return null
            return d.removeSuffix(identifier)
        }
        val own = mutableListOf<String>()
        parentOf(doc["dir"])?.let { parent ->
            val listed = (doc["workable_servers"] as? List<*>).orEmpty().filterIsInstance<String>()
            val hosts = listed.ifEmpty { listOf(doc["d1"], doc["d2"], doc["server"]).filterIsInstance<String>() }
            hosts.map { it.trim().lowercase() }.filter(::hostOk).forEach { own += "http://$it$parent" }
        }
        if (own.isNotEmpty()) return own.distinct()
        val alt = doc["alternate_locations"] as? Map<*, *> ?: return emptyList()
        val locs = (alt["workable"] as? List<*>).orEmpty().ifEmpty { (alt["servers"] as? List<*>).orEmpty() }
        return locs.mapNotNull { l ->
            val m = l as? Map<*, *> ?: return@mapNotNull null
            val h = (m["server"] as? String)?.trim()?.lowercase()?.takeIf(::hostOk) ?: return@mapNotNull null
            parentOf(m["dir"])?.let { "http://$h$it" }
        }.distinct()
    }

    /** [fromMetadata] for a torrent, fetching the metadata through [get] (an https GET returning the body or null). */
    fun resolve(meta: Magnet.MetaInfo, get: (String) -> String? = ::httpGet): List<String> {
        val id = identifier(meta) ?: return emptyList()
        val body = runCatching { get("https://archive.org/metadata/$id") }.getOrNull() ?: return emptyList()
        return fromMetadata(body, id)
    }

    private const val MAX_BYTES = 4 * 1024 * 1024

    fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "CloudStore-Torrent")
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null }
                out.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    /** A small strict JSON reader (objects, arrays, strings, numbers, true / false / null) for the metadata document. */
    private class Json(private val t: String) {
        private var i = 0
        fun parse(): Any? { val v = value(); ws(); require(i == t.length) { "trailing data" }; return v }
        private fun ws() { while (i < t.length && t[i].isWhitespace()) i++ }
        private fun value(): Any? {
            ws(); require(i < t.length) { "end of input" }
            return when (t[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> num()
            }
        }
        private fun lit(w: String, v: Any?): Any? { require(t.startsWith(w, i)) { "bad literal" }; i += w.length; return v }
        private fun num(): Double {
            val s = i
            while (i < t.length && (t[i].isDigit() || t[i] in "+-.eE")) i++
            return t.substring(s, i).toDouble()
        }
        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (t[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); require(t[i] == ':') { "expected :" }; i++
                m[k] = value(); ws()
                when (t[i++]) { ',' -> continue; '}' -> return m; else -> error("expected , or }") }
            }
        }
        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (t[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (t[i++]) { ',' -> continue; ']' -> return l; else -> error("expected , or ]") }
            }
        }
        private fun str(): String {
            require(t[i] == '"') { "expected string" }; i++
            val sb = StringBuilder()
            while (true) {
                val c = t[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = t[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(t.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
