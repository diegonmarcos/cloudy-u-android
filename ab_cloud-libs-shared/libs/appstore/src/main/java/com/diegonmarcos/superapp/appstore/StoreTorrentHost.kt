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
        /** the indexer's display name (Pirate Bay, YTS, Internet Archive) */
        val sourceLabel: String = "",
        val category: String? = null,
        /** -1 = the indexer does not say */
        val files: Int = -1,
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
        if (bytes < 0) return DASH
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    /** The table's columns, in order. */
    enum class Col(val label: String, val textFirst: Boolean) {
        NAME("Name", true), SIZE("Size", false), SEEDERS("S", false), LEECHERS("L", false), SOURCE("Source", true),
        AGE("Age", false), CATEGORY("Category", true), FILES("Files", false)
    }

    /** The content-type filter buckets of the table. */
    enum class Bucket(val label: String) { MUSIC("Music"), APPS("Apps"), VIDEO("Movies & TV"), BOOKS("PDF/Books"), GAMES("Gaming"), OTHER("Other") }

    /** Maps an indexer's category word (The Pirate Bay: Audio, Video, Applications, Games, Adult, Other; YTS: Movies; Archive mediatype:
     *  audio, movies, texts, software ...) to a bucket. A missing or unknown category is Other, so no result is ever unreachable. */
    fun bucketOf(category: String?): Bucket {
        val c = category?.lowercase()?.trim().orEmpty()
        return when {
            c.contains("audio") || c.contains("music") || c.contains("etree") -> Bucket.MUSIC
            c.contains("app") || c.contains("software") -> Bucket.APPS
            c.contains("video") || c.contains("movie") || c.contains("film") || c.contains("tv") -> Bucket.VIDEO
            c.contains("text") || c.contains("book") || c.contains("pdf") || c.contains("comic") -> Bucket.BOOKS
            c.contains("game") -> Bucket.GAMES
            else -> Bucket.OTHER
        }
    }

    /** [hits] narrowed to the picked [buckets]; none picked = all of them. */
    fun filtered(hits: List<Hit>, buckets: Collection<Bucket>): List<Hit> =
        if (buckets.isEmpty()) hits else hits.filter { bucketOf(it.category) in buckets }

    /** The first click on a column: text columns A to Z, figures biggest first (Age: newest first). */
    fun defaultDescending(c: Col): Boolean = !c.textFirst

    /** [hits] ordered by column [c]. A hit with no value for it (-1, 0, blank) is listed after the known ones in either direction. Stable. */
    fun sorted(hits: List<Hit>, c: Col, descending: Boolean): List<Hit> {
        fun num(h: Hit): Long? = when (c) {
            Col.SIZE -> h.sizeBytes.takeIf { it >= 0 }; Col.SEEDERS -> h.seeders.toLong().takeIf { it >= 0 }
            Col.LEECHERS -> h.leechers.toLong().takeIf { it >= 0 }; Col.AGE -> h.addedAt.takeIf { it > 0 }
            Col.FILES -> h.files.toLong().takeIf { it >= 0 }; else -> null
        }
        fun txt(h: Hit): String? = when (c) {
            Col.NAME -> h.title; Col.SOURCE -> h.sourceLabel.ifEmpty { h.sourceId }; Col.CATEGORY -> h.category; else -> null
        }?.takeIf { it.isNotBlank() }?.lowercase()
        val (known, unknown) = if (c.textFirst) hits.partition { txt(it) != null } else hits.partition { num(it) != null }
        val cmp: Comparator<Hit> = if (c.textFirst) compareBy<Hit> { txt(it)!! } else compareBy<Hit> { num(it)!! }
        return known.sortedWith(if (descending) cmp.reversed() else cmp) + unknown
    }

    /** Age of an upload as one short word: 5m, 3h, 12d, 7mo, 2y; "—" when unknown. [now] in epoch seconds. */
    fun ageText(addedAt: Long, now: Long = System.currentTimeMillis() / 1000): String {
        if (addedAt <= 0) return DASH
        val d = (now - addedAt).coerceAtLeast(0)
        return when {
            d < 3600 -> "${d / 60}m"; d < 86400 -> "${d / 3600}h"; d < 30 * 86400L -> "${d / 86400}d"
            d < 365 * 86400L -> "${d / (30 * 86400)}mo"; else -> "${d / (365 * 86400L)}y"
        }
    }

    fun seedText(n: Int) = if (n >= 0) n.toString() else DASH
    fun filesText(n: Int) = if (n >= 0) n.toString() else DASH

    const val DASH = "—"
}
