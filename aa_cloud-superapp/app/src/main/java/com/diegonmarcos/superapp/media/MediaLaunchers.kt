package com.diegonmarcos.superapp.media

import androidx.annotation.DrawableRes
import com.diegonmarcos.superapp.R

/**
 * #1923 the Media notification's launcher actions: open Tidal, open Spotify, Play last, open the Galaxy Buds app. The packages are DATA
 * ([ALL]); each entry resolves to the first of its packages that is installed at runtime and is hidden when none is. Pure: the installed
 * check is a lambda, so the Android parts are only the notification builder and the transport in MediaProxy.
 */
object MediaLaunchers {
    enum class Kind { PLAY_LAST, TIDAL, SPOTIFY, BUDS }

    /** [packages] are tried in order; a PLAY_LAST entry has none (it targets the last player, see [lastPlayer]). */
    data class Entry(val kind: Kind, val id: String, val label: String, @DrawableRes val icon: Int, val packages: List<String>)

    const val TIDAL_PKG = "com.aspiro.tidal"
    const val SPOTIFY_PKG = "com.spotify.music"

    /** Galaxy Wearable first, then the Buds plug-in / manager packages. */
    val BUDS_PKGS = listOf(
        "com.samsung.android.app.watchmanager",
        "com.samsung.accessory.berrymgr",
        "com.samsung.accessory.neobeanmanager",
    )

    /** In notification order; the first three are the compact ones. */
    val ALL: List<Entry> = listOf(
        Entry(Kind.PLAY_LAST, "play-last", "Play last", android.R.drawable.ic_media_play, emptyList()),
        Entry(Kind.TIDAL, "open-tidal", "Tidal", R.drawable.ic_media_tidal, listOf(TIDAL_PKG)),
        Entry(Kind.SPOTIFY, "open-spotify", "Spotify", R.drawable.ic_media_spotify, listOf(SPOTIFY_PKG)),
        Entry(Kind.BUDS, "open-buds", "Buds", R.drawable.ic_bluetooth, BUDS_PKGS),
    )

    /** The installed package an entry opens, or null (hidden). PLAY_LAST resolves to null here: it is shown by [visible] when a player is known. */
    fun resolve(e: Entry, installed: (String) -> Boolean): String? = e.packages.firstOrNull(installed)

    /** The entries to show with the resolved package ([lastPkg] for Play last), absent apps hidden, order kept. */
    fun visible(installed: (String) -> Boolean, lastPkg: String?): List<Pair<Entry, String>> = ALL.mapNotNull { e ->
        val pkg = if (e.kind == Kind.PLAY_LAST) lastPkg?.takeIf(installed) else resolve(e, installed)
        pkg?.let { e to it }
    }

    /** The player Play last resumes: a playing session first, else the most recently played package persisted ([persisted]), else the
     *  first active session; null when none. [sessions] are (package, playing). */
    fun lastPlayer(persisted: String?, sessions: List<Pair<String, Boolean>>): String? =
        sessions.firstOrNull { it.second }?.first ?: persisted ?: sessions.firstOrNull()?.first

    /** Compact-view indices (at most 3) of the first entries. */
    fun compact(count: Int): IntArray = IntArray(minOf(3, count)) { it }
}
