package com.diegonmarcos.cloudstore

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.appstore.StoreRecs
import com.diegonmarcos.superapp.appstore.StoreTorrentHost
import com.diegonmarcos.superapp.torrent.AddResult
import com.diegonmarcos.superapp.torrent.TorrentEngines
import com.diegonmarcos.superapp.torrent.TorrentHit
import com.diegonmarcos.superapp.torrent.TorrentIndexers
import com.diegonmarcos.superapp.torrent.TorrentSearch
import com.diegonmarcos.superapp.torrent.UserIndexer
import com.diegonmarcos.superapp.torrent.sentence

/**
 * The glue between libs:appstore's Search > Torrent page and libs:torrent: the sources are libs:torrent's declared indexers
 * ([TorrentIndexers.all]; a new built-in there is a new source pill with no change here) plus the user's own enabled sources
 * ([TorrentSources], managed in Configs > Torrent), a search is [TorrentSearch.search], and a result goes to the TORRENT handler -
 * the engine - never to the APK install path.
 */
class TorrentHost(private val app: Context) : StoreTorrentHost.Host {
    private val registry get() = TorrentEngines.sources(app)

    override fun sources(): List<StoreTorrentHost.Source> =
        TorrentIndexers.all.map { StoreTorrentHost.Source(it.id, it.label, it.blurb, it.homepage, builtIn = true) } +
            registry.user().filter { it.enabled }.map { UserIndexer(it) }.map { StoreTorrentHost.Source(it.id, it.label, it.blurb, it.homepage, builtIn = false) }

    override fun revision(): Long = registry.revision

    override fun search(sourceId: String, query: String): List<StoreTorrentHost.Hit> {
        val ix = registry.byId(sourceId) ?: error("unknown torrent source '$sourceId'")
        return TorrentSearch.search(ix, query).map { StoreTorrentHost.Hit(ix.id, it.title, it.seeders, it.leechers, it.sizeBytes, it.addedAt, it.page, it, ix.label, it.category, it.files, it.infoHash) }
    }

    override fun download(hit: StoreTorrentHost.Hit): String {
        val h = hit.key as? TorrentHit ?: return "This result cannot be downloaded."
        val engine = TorrentEngines.get(app)
        val magnet = h.magnetUri()
        val r: AddResult = if (magnet != null) engine.addMagnet(magnet)
        else runCatching { TorrentSearch.torrentBytes(h) }.fold({ engine.addTorrentBytes(h.title + ".torrent", it) }, { return "Could not fetch the .torrent: ${it.message}" })
        return r.sentence()
    }

    override fun folderNote(): String = TorrentEngines.get(app).settings.saveDir.path

    /** #1749 the declared catalogue (never the user's own entries) as the Torrent landing's recommendations. */
    override fun recommendations(): List<StoreRecs.Rec>? {
        val c = TorrentCatalogue.served(app) ?: return null
        return c.entries.filter { !it.user }.map {
            StoreRecs.Rec(it.id, it.name, it.category, it.sizeBytes, it.licence, it.publisher, it.sourcePage, it.torrentUrl, it.magnet, it.infoHash, it, it.image)
        }
    }

    /**
     * Live seeders / leechers: each recommendation is searched by name on the enabled sources and a hit counts only when its info-hash is
     * the declared one, so a look-alike from an open index can never stand in for the catalogued file. A source that fails is skipped.
     */
    override fun liveStats(recs: List<StoreRecs.Rec>, onStat: (String, StoreRecs.Stat) -> Unit) {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(recs.filter { it.infoHash != null })
        val sources = sources().map { it.id }
        val workers = (1..4).map {
            Thread({
                while (true) {
                    val r = queue.poll() ?: break
                    val hash = r.infoHash!!.lowercase()
                    val q = r.name.substringBefore('(').trim().ifEmpty { r.name }
                    for (id in sources) {
                        val hit = runCatching { search(id, q) }.getOrNull()?.firstOrNull { it.infoHash?.lowercase() == hash && it.seeders >= 0 } ?: continue
                        onStat(hash, StoreRecs.Stat(hit.seeders, hit.leechers, hit.sourceLabel))
                        break
                    }
                }
            }, "store-torrent-live").also { it.start() }
        }
        workers.forEach { runCatching { it.join() } }
    }

    override fun downloadRec(rec: StoreRecs.Rec): String {
        val e = rec.key as? TorrentCatalogue.Entry ?: return "This recommendation cannot be downloaded."
        return TorrentCatalogue.download(TorrentEngines.get(app), e)
    }
}

/** Adds what the system hands the app: a `magnet:` link, a .torrent file, or the files picked in Torrent / Configs > Torrent. */
object TorrentIntake {
    /** The types the .torrent picker offers (the extension's own type, and what a provider with no mapping reports). */
    val PICK_TYPES = arrayOf("application/x-bittorrent", "application/octet-stream")

    /** Adds every picked .torrent [uris]; returns one sentence per file. */
    fun importUris(ctx: Context, uris: List<Uri>): List<String> {
        val engine = TorrentEngines.get(ctx)
        return uris.map { uri ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.torrent"
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) "$name: cannot read the file." else "$name: " + engine.addTorrentBytes(name, bytes).sentence()
        }
    }

    /** #1790 the most a .torrent fetched from a web address may weigh (a real one is kilobytes; this stops a page or a file being read as one). */
    const val MAX_TORRENT_BYTES = 8L * 1024 * 1024

    /**
     * #1790 the Torrent page's one field: a magnet link goes to the engine as is; an http(s) address is fetched as a .torrent (an
     * Internet Archive item's `https://archive.org/download/<item>/<item>_archive.torrent`, an indexer's download link) and added.
     * Returns the sentence to show and whether a torrent is now in the list (added or already there). Blocking, never throws.
     */
    fun addLink(ctx: Context, text: String): Pair<String, Boolean> {
        val t = text.trim()
        val engine = TorrentEngines.get(ctx)
        if (t.startsWith("magnet:", true)) return engine.addMagnet(t).let { it.sentence() to (it.idOrNull != null) }
        if (!(t.startsWith("https://", true) || t.startsWith("http://", true))) return "Paste a magnet link (magnet:?xt=urn:btih:…) or the web address of a .torrent." to false
        val bytes = runCatching { fetchTorrent(t) }.getOrElse { return "Could not fetch the .torrent: ${it.message ?: it.javaClass.simpleName}" to false }
        val label = Uri.parse(t).lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "link.torrent"
        return engine.addTorrentBytes(label, bytes).let { "$label: " + it.sentence() to (it.idOrNull != null) }
    }

    /** GET [url] (redirects followed, 20 s timeouts) and return at most [MAX_TORRENT_BYTES]; a non-2xx answer throws with its code. */
    fun fetchTorrent(url: String): ByteArray {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 20_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/x-bittorrent, */*")
        try {
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TORRENT_BYTES) error("larger than ${MAX_TORRENT_BYTES / (1024 * 1024)} MB, not a .torrent")
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    /** True when [intent] carried a magnet link or a .torrent and it was handed to the engine. */
    fun handle(ctx: Context, intent: android.content.Intent?): Boolean {
        if (intent == null || intent.action != android.content.Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        val engine = TorrentEngines.get(ctx)
        return when {
            data.scheme.equals("magnet", true) -> { Thread { engine.addMagnet(data.toString()) }.start(); true }
            intent.type == "application/x-bittorrent" || data.path?.endsWith(".torrent", true) == true -> { Thread { importUris(ctx, listOf(data)) }.start(); true }
            else -> false
        }
    }
}
