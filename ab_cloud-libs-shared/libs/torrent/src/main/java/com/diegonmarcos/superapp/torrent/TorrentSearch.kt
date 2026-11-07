package com.diegonmarcos.superapp.torrent

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One search result from a torrent indexer. Either a [magnet], an [infoHash] (a magnet is built from it) or a [torrentUrl] (a .torrent to fetch). */
class TorrentHit(
    val indexerId: String,
    val title: String,
    val infoHash: String?,
    val magnet: String?,
    val torrentUrl: String?,
    /** -1 = the indexer does not say */
    val seeders: Int,
    val leechers: Int,
    val sizeBytes: Long,
    /** epoch seconds, or 0 */
    val addedAt: Long,
    /** the indexer's page for this result, or null */
    val page: String?,
    /** the indexer's category in words, or null when it does not say */
    val category: String? = null,
    /** number of files in the torrent, -1 when the indexer does not say */
    val files: Int = -1,
) {
    /** The magnet to hand the engine, or null for a hit that is only a .torrent url. */
    fun magnetUri(): String? = magnet ?: infoHash?.let { Magnet.build(it, title, Magnet.PUBLIC_TRACKERS) }
    /** The info-hash id the engine will use, when known without fetching. */
    fun id(): String? = infoHash?.lowercase() ?: magnet?.let { Magnet.infoHash(it) }
}

/**
 * A torrent search source, declared as data: its id and name, how a query becomes a URL, and how the body that comes back
 * becomes hits ([parse] is a pure function, tested against recorded bodies). Adding an indexer is one more entry in
 * [TorrentIndexers.all] and nothing else; the Search page lists whatever that list holds.
 */
interface TorrentIndexer {
    val id: String
    val label: String
    val blurb: String
    val homepage: String
    fun searchUrl(query: String): String
    fun parse(body: String): List<TorrentHit>
}

object TorrentIndexers {
    private fun enc(q: String) = URLEncoder.encode(q.trim(), "UTF-8")
    private fun num(o: JSONObject, k: String): Long = o.opt(k)?.toString()?.toLongOrNull() ?: 0L

    /** The Pirate Bay's top-level category codes (100 audio ... 600 other); a sub-category code maps to its hundred. */
    fun tpbCategory(code: String): String? = when ((code.toIntOrNull() ?: -1) / 100) {
        1 -> "Audio"; 2 -> "Video"; 3 -> "Applications"; 4 -> "Games"; 5 -> "Adult"; 6 -> "Other"; else -> null
    }

    /** The Pirate Bay's public JSON API: every category, hash-only results (the magnet is built with public trackers). */
    object ApiBay : TorrentIndexer {
        override val id = "apibay"
        override val label = "The Pirate Bay"
        override val blurb = "apibay.org public search API: all categories, seeders and leechers."
        override val homepage = "https://thepiratebay.org"
        override fun searchUrl(query: String) = "https://apibay.org/q.php?q=${enc(query)}&cat=0"
        override fun parse(body: String): List<TorrentHit> {
            val a = JSONArray(body)
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val hash = o.optString("info_hash").lowercase()
                // The API answers "No results returned" with an all-zero hash: that is an empty list, not a hit.
                if (hash.length != 40 || hash.all { it == '0' }) return@mapNotNull null
                TorrentHit(id, o.optString("name"), hash, null, null, num(o, "seeders").toInt(), num(o, "leechers").toInt(),
                    num(o, "size"), num(o, "added"), "https://thepiratebay.org/description.php?id=${o.optString("id")}",
                    category = tpbCategory(o.optString("category")), files = num(o, "num_files").toInt().takeIf { it > 0 } ?: -1)
            }
        }
    }

    /** YTS: films, one hit per quality / source of each film. */
    object Yts : TorrentIndexer {
        override val id = "yts"
        override val label = "YTS"
        override val blurb = "yts.mx movies: one result per quality (720p, 1080p, 2160p)."
        override val homepage = "https://yts.mx"
        override fun searchUrl(query: String) = "https://yts.mx/api/v2/list_movies.json?query_term=${enc(query)}&limit=30"
        override fun parse(body: String): List<TorrentHit> {
            val movies = JSONObject(body).optJSONObject("data")?.optJSONArray("movies") ?: return emptyList()
            val out = ArrayList<TorrentHit>()
            for (i in 0 until movies.length()) {
                val m = movies.optJSONObject(i) ?: continue
                val ts = m.optJSONArray("torrents") ?: continue
                for (j in 0 until ts.length()) {
                    val t = ts.optJSONObject(j) ?: continue
                    val hash = t.optString("hash").lowercase().takeIf { it.length == 40 } ?: continue
                    out += TorrentHit(id, "${m.optString("title_long")} [${t.optString("quality")} ${t.optString("type")}]".trim(), hash, null, null,
                        num(t, "seeds").toInt(), num(t, "peers").toInt(), num(t, "size_bytes"), num(t, "date_uploaded_unix"), m.optString("url").takeIf { it.isNotEmpty() },
                        category = "Movies", files = 1)
                }
            }
            return out
        }
    }

    /** The Internet Archive: items that ship a .torrent (public-domain and creative-commons media, software, books). */
    object InternetArchive : TorrentIndexer {
        override val id = "archive"
        override val label = "Internet Archive"
        override val blurb = "archive.org items with a .torrent: public-domain and open media."
        override val homepage = "https://archive.org"
        override fun searchUrl(query: String) =
            "https://archive.org/advancedsearch.php?q=${enc("(" + query.trim() + ") AND format:(Archive BitTorrent)")}" +
                "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=item_size&fl%5B%5D=publicdate&fl%5B%5D=mediatype&fl%5B%5D=files_count&sort%5B%5D=downloads+desc&rows=30&output=json"
        override fun parse(body: String): List<TorrentHit> {
            val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
            return (0 until docs.length()).mapNotNull { i ->
                val d = docs.optJSONObject(i) ?: return@mapNotNull null
                val ident = d.optString("identifier").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TorrentHit(id, d.optString("title").ifEmpty { ident }, null, null, "https://archive.org/download/$ident/${ident}_archive.torrent",
                    -1, -1, num(d, "item_size"),
                    runCatching { java.time.Instant.parse(d.optString("publicdate")).epochSecond }.getOrDefault(0L), "https://archive.org/details/$ident",
                    category = d.optString("mediatype").takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() },
                    files = num(d, "files_count").toInt().takeIf { it > 0 } ?: -1)
            }
        }
    }

    /** Every torrent search source, in the order the page lists them. ADD A SOURCE HERE. */
    val all: List<TorrentIndexer> = listOf(ApiBay, Yts, InternetArchive)

    fun byId(id: String): TorrentIndexer? = all.firstOrNull { it.id == id }
}

/** Runs a search over an indexer. The fetch is injectable so tests (and a host with its own HTTP stack) supply the body. */
object TorrentSearch {
    private const val UA = "Mozilla/5.0 (Android) CloudStore-Torrent"

    /** GET [url] and return the bytes. Throws IOException with the status in the message; no body is ever invented. */
    fun fetch(url: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json, application/x-bittorrent, */*")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("response from ${URL(url).host} is larger than $maxBytes bytes")
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    /** Hits of [indexer] for [query], most seeded first (hits without a count last). Throws with the reason (offline, refused, unreadable). */
    fun search(indexer: TorrentIndexer, query: String, get: (String) -> ByteArray = { fetch(it) }): List<TorrentHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val body = get(indexer.searchUrl(q)).toString(Charsets.UTF_8)
        val hits = try { indexer.parse(body) } catch (e: org.json.JSONException) {
            throw IOException("${indexer.label} answered in a shape this app does not read (${e.message})")
        }
        return hits.sortedByDescending { it.seeders }
    }

    /** The .torrent bytes of a hit that is a .torrent url. */
    fun torrentBytes(hit: TorrentHit, get: (String) -> ByteArray = { fetch(it) }): ByteArray =
        get(hit.torrentUrl ?: throw IOException("this result has no .torrent url"))
}
