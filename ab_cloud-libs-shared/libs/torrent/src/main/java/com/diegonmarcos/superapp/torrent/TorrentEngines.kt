package com.diegonmarcos.superapp.torrent

import android.content.Context
import java.io.File

/** The process-wide engine on Android: libtorrent session, the app's private list, preference-backed settings. */
object TorrentEngines {
    @Volatile private var engine: TorrentEngine? = null

    /** The one engine. Constructing it starts nothing: [TorrentEngine.start] (or the first add) loads libtorrent. */
    fun get(ctx: Context): TorrentEngine = engine ?: synchronized(this) {
        engine ?: build(ctx.applicationContext).also { engine = it }
    }

    private fun build(app: Context): TorrentEngine {
        val dir = File(app.filesDir, "torrent")
        return TorrentEngine(JlibtorrentBackend(), FileTorrentStore(File(dir, "torrents.json")), PrefsSettings(app), File(dir, "files"))
    }

    /** Settings with their Android extras (fallback state, volumes), for the config page. */
    fun settings(ctx: Context): PrefsSettings = get(ctx).settings as PrefsSettings

    /** Shared-storage volume roots (internal, SD card, USB drive) this phone lists for the app, for the folder picker. */
    fun volumes(ctx: Context): List<File> = TorrentPaths.volumeRoots(ctx.getExternalFilesDirs(null).toList())
}
