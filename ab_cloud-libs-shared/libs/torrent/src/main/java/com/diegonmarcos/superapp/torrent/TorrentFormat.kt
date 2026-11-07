package com.diegonmarcos.superapp.torrent

import java.util.Locale

/** Words for the numbers a downloads row shows. Pure. */
object TorrentFormat {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$n B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun percent(p: Float): String = String.format(Locale.US, "%.1f%%", (p.coerceIn(0f, 1f) * 100f))

    /** 3725 -> "1h 2m", 75 -> "1m 15s", -1 -> "unknown". */
    fun eta(seconds: Long): String = when {
        seconds < 0 -> "unknown"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    }

    fun stateWord(s: TorrentState): String = when (s) {
        TorrentState.QUEUED -> "queued"
        TorrentState.METADATA -> "fetching metadata"
        TorrentState.CHECKING -> "checking files"
        TorrentState.DOWNLOADING -> "downloading"
        TorrentState.SEEDING -> "seeding"
        TorrentState.PAUSED -> "paused"
        TorrentState.COMPLETED -> "completed"
        TorrentState.ERROR -> "error"
    }

    /** The data line of a row: state, percent, done of total, rates, seeds and peers, eta. */
    fun line(t: TorrentStatus): String = buildList {
        add(stateWord(t.state))
        if (t.totalBytes > 0) add("${percent(t.progress)} of ${bytes(t.totalBytes)}")
        if (t.state == TorrentState.DOWNLOADING) { add("↓ ${rate(t.downRate)}"); add("ETA ${eta(t.etaSeconds)}") }
        if (t.state == TorrentState.DOWNLOADING || t.state == TorrentState.SEEDING) add("↑ ${rate(t.upRate)}")
        if (t.active || t.state == TorrentState.SEEDING) add("${t.seeds} seeds · ${t.peers} peers")
        if (t.finished && t.uploaded > 0) add("ratio ${String.format(Locale.US, "%.2f", t.ratio)}")
    }.joinToString(" · ")
}
