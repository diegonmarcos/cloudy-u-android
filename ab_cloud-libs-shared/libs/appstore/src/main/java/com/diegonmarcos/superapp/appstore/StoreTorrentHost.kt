package com.diegonmarcos.superapp.appstore

/**
 * The torrent side of Store > Search, supplied by the host app. libs:appstore draws the Torrent source page and routes a
 * result to [Host.download], but it holds no BitTorrent code: Cloud Store links libs:torrent (the engine and the indexer
 * list) and sets [host] in its Application; SuperApp's embedded Store links no engine, leaves [host] null, and the page says
 * the Torrent sources live in Cloud Store. That keeps libtorrent's native library out of every app that only embeds the Store.
 */
object StoreTorrentHost {
    /** One torrent search source (an indexer). The list is the host's and is declarative: a new indexer needs no change here. */
    class Source(val id: String, val label: String, val blurb: String)

    /** One result, already rendered to plain fields. [key] is the host's own handle for [Host.download]. */
    class Hit(
        val sourceId: String,
        val title: String,
        /** -1 = the indexer does not say */
        val seeders: Int,
        val leechers: Int,
        val sizeBytes: Long,
        /** epoch seconds, 0 = unknown */
        val addedAt: Long,
        val page: String?,
        val key: Any,
    )

    interface Host {
        fun sources(): List<Source>
        /** Blocking, off the main thread. Throws with the reason (offline, refused, unreadable): never an empty list for a failure. */
        fun search(sourceId: String, query: String): List<Hit>
        /** Hands the result to the engine (magnet, or fetch the .torrent then add). Blocking. Returns one sentence saying what happened. */
        fun download(hit: Hit): String
        /** Where downloads go, for the page's caption. */
        fun folderNote(): String
    }

    @Volatile var host: Host? = null

    /** 1536 -> "1.5 KB". */
    fun sizeText(bytes: Long): String {
        if (bytes < 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    /** The data line of a result: seeders, leechers, size, date. */
    fun line(h: Hit): String = buildList {
        add(if (h.seeders >= 0) "S ${h.seeders}" else "S ?")
        add(if (h.leechers >= 0) "L ${h.leechers}" else "L ?")
        add(sizeText(h.sizeBytes))
        if (h.addedAt > 0) add(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(h.addedAt * 1000)))
    }.joinToString(" · ")
}
