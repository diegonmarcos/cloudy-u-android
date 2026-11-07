package com.diegonmarcos.superapp.torrent

/**
 * What the background download service shows and decides, pure. A torrent counts as a download only while it is
 * [TorrentStatus.active] (downloading, fetching metadata, checking, queued); paused, completed, seeding and errored rows do not,
 * so a service built on this stops the moment the last download ends and is never kept alive for seeding or idling.
 */
data class TorrentActivity(
    /** Torrents still downloading (active). */
    val downloading: Int,
    /** Torrents actually moving bytes (state DOWNLOADING), the only reason to hold a network lock. */
    val transferring: Int,
    /** Aggregate progress 0..1 over the active torrents with a known size, or -1 when no size is known yet. */
    val progress: Float,
    val downRate: Long,
    val peers: Int,
) {
    /** The service runs exactly while this is true. */
    val keepAlive: Boolean get() = downloading > 0

    /** Notification title: "2 downloading" / "1 downloading". */
    val title: String get() = "$downloading downloading"

    /** Notification text: "N downloading · X% · rate · peers". */
    val text: String get() = buildList {
        add(title)
        if (progress >= 0f) add(TorrentFormat.percent(progress))
        add("↓ " + TorrentFormat.rate(downRate))
        add("$peers peers")
    }.joinToString(" · ")

    companion object {
        fun of(list: List<TorrentStatus>): TorrentActivity {
            val act = list.filter { it.active }
            val sized = act.filter { it.totalBytes > 0 }
            val total = sized.sumOf { it.totalBytes }
            val progress = if (total > 0) (sized.sumOf { it.doneBytes }.toFloat() / total).coerceIn(0f, 1f) else -1f
            return TorrentActivity(act.size, act.count { it.state == TorrentState.DOWNLOADING }, progress, act.sumOf { it.downRate }, act.sumOf { it.peers })
        }
    }
}
