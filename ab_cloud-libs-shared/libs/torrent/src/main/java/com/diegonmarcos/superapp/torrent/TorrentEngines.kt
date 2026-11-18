package com.diegonmarcos.superapp.torrent

import android.content.Context
import java.io.File

/** The process-wide engine on Android: libtorrent session, the app's private list, preference-backed settings. */
object TorrentEngines {
    /** #1790 the debug flag file (in getExternalFilesDir(null)): its presence turns on libtorrent's session/torrent/peer log in logcat. */
    const val DEBUG_FLAG = "torrent-debug"

    @Volatile private var engine: TorrentEngine? = null

    /** The one engine. Constructing it starts nothing: [TorrentEngine.start] (or the first add) loads libtorrent. */
    fun get(ctx: Context): TorrentEngine = engine ?: synchronized(this) {
        engine ?: build(ctx.applicationContext).also { engine = it }
    }

    private fun build(app: Context): TorrentEngine {
        val dir = File(app.filesDir, "torrent")
        // #1790 a file named torrent-debug in the app's external files dir turns on libtorrent's own log (an emulator run creates it)
        val verbose = runCatching { java.io.File(app.getExternalFilesDir(null), DEBUG_FLAG).exists() }.getOrDefault(false)
        return TorrentEngine(LibtorrentBackend(verbose), FileTorrentStore(File(dir, "torrents.json")), PrefsSettings(app), File(dir, "files"))
    }

    @Volatile private var sources: TorrentSources? = null

    /** The torrent search sources: the declared built-ins plus the user's own (SharedPreferences `libs_torrent`). One per process; touches no native code. */
    fun sources(ctx: Context): TorrentSources = sources ?: synchronized(this) {
        sources ?: TorrentSources(PrefsSourceStore(ctx.applicationContext)).also { sources = it }
    }

    /** Settings with their Android extras (fallback state, volumes), for the config page. */
    fun settings(ctx: Context): PrefsSettings = get(ctx).settings as PrefsSettings

    /** Shared-storage volume roots (internal, SD card, USB drive) this phone lists for the app, for the folder picker. */
    fun volumes(ctx: Context): List<File> = TorrentPaths.volumeRoots(ctx.getExternalFilesDirs(null).toList())
}
