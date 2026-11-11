package com.diegonmarcos.superapp.torrent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * How the body a user's search URL answers becomes hits. Each kind is a pure function (tested on recorded bodies); no kind
 * runs script or follows links. The three JSON kinds are the shapes the built-in indexers read, so a mirror or a compatible
 * API is a source of its own with no code; [RSS] covers Torznab / Jackett / Prowlarr and any torrent RSS feed; [HTML_MAGNETS]
 * reads the `magnet:` links of a search results page.
 */
enum class ParseKind(val id: String, val label: String, val blurb: String) {
    APIBAY_JSON("apibay-json", "JSON: Pirate Bay API", "A JSON array of {name, info_hash, seeders, leechers, size, added} (apibay.org shape)."),
    YTS_JSON("yts-json", "JSON: YTS API", "YTS list_movies.json: data.movies[].torrents[] with hash, quality, seeds, peers."),
    ARCHIVE_JSON("archive-json", "JSON: archive.org search", "advancedsearch.php output=json: response.docs[] with identifier, title, item_size."),
    RSS("rss", "RSS / Torznab feed", "An RSS feed: each item's magnet link, enclosure .torrent, torznab seeders / peers / size / infohash."),
    HTML_MAGNETS("html-magnets", "HTML page of magnet links", "A results page: every magnet: link on it, named by its dn= (best effort, no counts).");

    fun parse(indexerId: String, body: String): List<TorrentHit> = when (this) {
        APIBAY_JSON -> TorrentIndexers.ApiBay.parseAs(indexerId, body)
        YTS_JSON -> TorrentIndexers.Yts.parseAs(indexerId, body)
        ARCHIVE_JSON -> TorrentIndexers.InternetArchive.parseAs(indexerId, body)
        RSS -> SourceParsers.rss(indexerId, body)
        HTML_MAGNETS -> SourceParsers.htmlMagnets(indexerId, body)
    }

    companion object {
        fun of(id: String?): ParseKind? = values().firstOrNull { it.id == id }
    }
}

/** A search source the user added in Configs > Torrent. Runtime data in the app's preferences, never baked. */
data class UserSource(
    val id: String,
    val label: String,
    val homepage: String,
    /** https URL with the query placeholder [TorrentSources.PLACEHOLDER] (or {query}) where the search words go. */
    val searchUrl: String,
    val kind: ParseKind,
    val enabled: Boolean = true,
)

/** A user source as a [TorrentIndexer], so Search treats it exactly like a built-in one. */
class UserIndexer(val source: UserSource) : TorrentIndexer {
    override val id get() = source.id
    override val label get() = source.label
    override val blurb get() = "Your source: ${source.kind.label}."
    override val homepage get() = source.homepage
    override fun searchUrl(query: String) = TorrentSources.expand(source.searchUrl, query)
    override fun parse(body: String): List<TorrentHit> = source.kind.parse(id, body)
}

object SourceParsers {
    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val MAGNET_HREF = Regex("""href\s*=\s*["'](magnet:\?[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""<(?:\w+:)?attr\b[^>]*?name\s*=\s*"([^"]+)"[^>]*?value\s*=\s*"([^"]*)"[^>]*?/?>""", RegexOption.IGNORE_CASE)
    private const val MAX_HITS = 100

    fun decode(s: String): String {
        var t = s.trim()
        Regex("^<!\\[CDATA\\[(.*)]]>$", RegexOption.DOT_MATCHES_ALL).find(t)?.let { return it.groupValues[1].trim() }
        t = Regex("&#(x?)([0-9a-fA-F]+);").replace(t) { m ->
            val cp = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }

    private fun tag(item: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(item)?.groupValues?.get(1)?.let(::decode)?.takeIf { it.isNotEmpty() }

    /** RSS / Torznab: one hit per `<item>` that carries a magnet, an info-hash or a .torrent url. Items with none are skipped, not invented. */
    fun rss(indexerId: String, body: String): List<TorrentHit> {
        if (!Regex("<(rss|channel|feed|item)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) throw org.json.JSONException("not an RSS feed")
        val out = ArrayList<TorrentHit>()
        for (m in ITEM.findAll(body)) {
            if (out.size >= MAX_HITS) break
            val item = m.groupValues[1]
            val attrs = ATTR.findAll(item).associate { it.groupValues[1].lowercase() to decode(it.groupValues[2]) }
            val title = tag(item, "title") ?: continue
            val enclosure = Regex("<enclosure\\b[^>]*?url\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)?.let(::decode)
            val link = tag(item, "link")
            val magnet = listOfNotNull(attrs["magneturl"], tag(item, "torrent:magnetURI"), enclosure, link, tag(item, "guid"))
                .firstOrNull { it.startsWith("magnet:?", true) }
            val hash = (attrs["infohash"] ?: tag(item, "torrent:infoHash"))?.lowercase()?.takeIf { it.length == 40 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                ?: magnet?.let { Magnet.infoHash(it) }
            val torrentUrl = listOfNotNull(enclosure, link).firstOrNull { it.startsWith("https://", true) && (it.contains(".torrent", true) || it === enclosure) }
            if (magnet == null && hash == null && torrentUrl == null) continue
            val seeders = attrs["seeders"]?.toIntOrNull() ?: tag(item, "seeders")?.toIntOrNull() ?: -1
            val peers = attrs["peers"]?.toIntOrNull()
            val leechers = attrs["leechers"]?.toIntOrNull() ?: tag(item, "leechers")?.toIntOrNull() ?: if (peers != null && seeders >= 0) (peers - seeders).coerceAtLeast(0) else -1
            val size = attrs["size"]?.toLongOrNull() ?: tag(item, "size")?.toLongOrNull() ?: Regex("<enclosure\\b[^>]*?length\\s*=\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val added = tag(item, "pubDate")?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull() } ?: 0L
            val page = (tag(item, "comments") ?: link)?.takeIf { it.startsWith("https://", true) && !it.contains(".torrent", true) }
            out += TorrentHit(indexerId, title, hash, magnet, if (magnet == null && hash == null) torrentUrl else null, seeders, leechers, size, added, page,
                category = attrs["category"]?.takeIf { it.isNotBlank() } ?: tag(item, "category"), files = attrs["files"]?.toIntOrNull() ?: -1)
        }
        return out
    }

    /** HTML: every `magnet:` href, named by its dn=. Duplicates (the same info-hash) are one hit; counts are unknown (-1). */
    fun htmlMagnets(indexerId: String, body: String): List<TorrentHit> {
        val seen = HashSet<String>()
        val out = ArrayList<TorrentHit>()
        for (m in MAGNET_HREF.findAll(body)) {
            if (out.size >= MAX_HITS) break
            val magnet = decode(m.groupValues[1])
            val hash = Magnet.infoHash(magnet) ?: continue
            if (!seen.add(hash)) continue
            out += TorrentHit(indexerId, Magnet.displayName(magnet) ?: hash, hash, magnet, null, -1, -1, -1L, 0L, null)
        }
        return out
    }
}

/** The outcome of a manage call on [TorrentSources]: [error] is one sentence for the page, null when it worked. */
class SourceResult(val error: String?) { val ok: Boolean get() = error == null }

/** Where the user's sources live between launches. */
interface TorrentSourceStore {
    fun load(): List<UserSource>
    fun save(list: List<UserSource>)
}

class MemorySourceStore(var list: List<UserSource> = emptyList()) : TorrentSourceStore {
    override fun load() = list
    override fun save(list: List<UserSource>) { this.list = list }
}

/** SharedPreferences `libs_torrent`, key `user_sources`: one JSON array. Unreadable = empty, never a crash. */
class PrefsSourceStore(ctx: Context) : TorrentSourceStore {
    private val p = ctx.applicationContext.getSharedPreferences("libs_torrent", Context.MODE_PRIVATE)

    override fun load(): List<UserSource> = runCatching {
        val a = JSONArray(p.getString(KEY, null) ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val kind = ParseKind.of(o.optString("kind")) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            UserSource(id, o.optString("label").ifEmpty { id }, o.optString("homepage"), o.optString("url"), kind, o.optBoolean("enabled", true))
        }
    }.getOrDefault(emptyList())

    override fun save(list: List<UserSource>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("label", s.label).put("homepage", s.homepage).put("url", s.searchUrl).put("kind", s.kind.id).put("enabled", s.enabled))
        }
        p.edit().putString(KEY, a.toString()).apply()
    }

    private companion object { const val KEY = "user_sources" }
}

/**
 * The torrent search sources: the declared built-ins ([TorrentIndexers.all], read-only, never removed) plus the user's own
 * (added, edited, removed and switched at runtime, persisted through [store]). Search runs [searchSet]: every built-in and each
 * ENABLED user source. [revision] moves on every change so a page can tell its list is stale.
 */
class TorrentSources(private val store: TorrentSourceStore) {
    @Volatile private var cache: List<UserSource>? = null
    @Volatile var revision: Long = 0L
        private set

    val builtIn: List<TorrentIndexer> get() = TorrentIndexers.all

    @Synchronized fun user(): List<UserSource> = cache ?: store.load().also { cache = it }

    /** What Search queries: the built-ins, then the user's enabled sources, in the order added. */
    fun searchSet(): List<TorrentIndexer> = builtIn + user().filter { it.enabled }.map(::UserIndexer)

    /** A built-in or any user source (enabled or not) by id. */
    fun byId(id: String): TorrentIndexer? = TorrentIndexers.byId(id) ?: user().firstOrNull { it.id == id }?.let(::UserIndexer)

    @Synchronized private fun commit(list: List<UserSource>) { store.save(list); cache = list; revision++ }

    /** Adds [s]; an error sentence when the id, name or url is not usable (nothing is saved then). */
    @Synchronized fun add(s: UserSource): SourceResult {
        val clean = normalise(s)
        validate(clean, user().map { it.id }, editing = null)?.let { return SourceResult(it) }
        commit(user() + clean)
        return SourceResult(null)
    }

    /** Replaces the source [id] with [s] (the id itself cannot change). */
    @Synchronized fun update(id: String, s: UserSource): SourceResult {
        val cur = user()
        if (cur.none { it.id == id }) return SourceResult("No source '$id' to edit.")
        val clean = normalise(s.copy(id = id))
        validate(clean, cur.map { it.id }, editing = id)?.let { return SourceResult(it) }
        commit(cur.map { if (it.id == id) clean else it })
        return SourceResult(null)
    }

    @Synchronized fun remove(id: String): Boolean {
        val cur = user()
        if (cur.none { it.id == id }) return false
        commit(cur.filter { it.id != id })
        return true
    }

    @Synchronized fun setEnabled(id: String, on: Boolean): Boolean {
        val cur = user()
        if (cur.none { it.id == id }) return false
        commit(cur.map { if (it.id == id) it.copy(enabled = on) else it })
        return true
    }

    companion object {
        /** Where the search words go in a search url. */
        const val PLACEHOLDER = "{q}"
        private const val ALIAS = "{query}"
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")

        private fun normalise(s: UserSource) = s.copy(id = s.id.trim().lowercase(), label = s.label.trim(), homepage = s.homepage.trim(), searchUrl = s.searchUrl.trim())

        /** The id a label suggests: lowercase letters, digits and dashes. */
        fun idFor(label: String): String = label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)

        /** [template] with the placeholder replaced by the url-encoded [query] (spaces as %20, safe in a path and a query). */
        fun expand(template: String, query: String): String {
            val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
            return template.replace(PLACEHOLDER, q).replace(ALIAS, q)
        }

        /** One sentence saying what is wrong with [s], or null when it can be saved. [existing] are the user's ids; [editing] is the id being edited. */
        fun validate(s: UserSource, existing: Collection<String>, editing: String?): String? {
            if (!ID.matches(s.id)) return "The id must be 1-32 characters: lowercase letters, digits, - or _."
            if (TorrentIndexers.byId(s.id) != null) return "'${s.id}' is a built-in source id."
            if (s.id != editing && s.id in existing) return "A source with the id '${s.id}' already exists."
            if (s.label.isBlank()) return "Give the source a name."
            if (s.label.length > 40) return "The name is longer than 40 characters."
            if (s.homepage.isNotEmpty() && hostOf(s.homepage) == null) return "The homepage must be an https address."
            if (!s.searchUrl.contains(PLACEHOLDER) && !s.searchUrl.contains(ALIAS)) return "The search address needs $PLACEHOLDER where the search words go."
            if (hostOf(expand(s.searchUrl, "test")) == null) return "The search address must be a valid https address."
            return null
        }

        /** The host of an https [url], or null when it is not one (cleartext http is not allowed: the app's network config refuses it). */
        fun hostOf(url: String): String? = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme.equals("https", true) }?.host?.takeIf { it.isNotEmpty() }
    }
}
