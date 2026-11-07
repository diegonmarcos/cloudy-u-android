package com.diegonmarcos.cloudstore

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.appstore.StoreTorrentHost
import com.diegonmarcos.superapp.torrent.AddResult
import com.diegonmarcos.superapp.torrent.TorrentEngines
import com.diegonmarcos.superapp.torrent.TorrentHit
import com.diegonmarcos.superapp.torrent.TorrentIndexers
import com.diegonmarcos.superapp.torrent.TorrentSearch
import com.diegonmarcos.superapp.torrent.sentence

/**
 * The glue between libs:appstore's Search > Torrent page and libs:torrent: the sources are libs:torrent's declared indexers
 * ([TorrentIndexers.all]; a new indexer there is a new source pill with no change here), a search is [TorrentSearch.search],
 * and a result goes to the TORRENT handler - the engine - never to the APK install path.
 */
class TorrentHost(private val app: Context) : StoreTorrentHost.Host {
    override fun sources(): List<StoreTorrentHost.Source> = TorrentIndexers.all.map { StoreTorrentHost.Source(it.id, it.label, it.blurb) }

    override fun search(sourceId: String, query: String): List<StoreTorrentHost.Hit> {
        val ix = TorrentIndexers.byId(sourceId) ?: error("unknown torrent source '$sourceId'")
        return TorrentSearch.search(ix, query).map { StoreTorrentHost.Hit(ix.id, it.title, it.seeders, it.leechers, it.sizeBytes, it.addedAt, it.page, it, ix.label, it.category, it.files) }
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
