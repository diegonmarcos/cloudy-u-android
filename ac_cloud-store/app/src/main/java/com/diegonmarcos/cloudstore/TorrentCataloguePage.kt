package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.torrent.TorrentFormat

// #1790 the catalogue SECTION of the Torrent page (the suggested-torrent list: category chips, rows with Download / Open in app)
// was removed at the owner's order: the Torrent page is a download manager only. What stays: the catalogue data and its parser
// (TorrentCatalogue), Search > Torrent's recommendations (TorrentHost.recommendations), Configs > Torrent's own entries, and the
// one-line description below, which Configs and the tests use.

/** "Operating Systems · 6.0 GB · Canonical · GPL…" and "yours" for the user's own entries. */
internal fun catalogueLine(e: TorrentCatalogue.Entry, categoryLabel: String): String =
    listOfNotNull(categoryLabel, e.sizeBytes.takeIf { it > 0 }?.let { TorrentFormat.bytes(it) }, e.publisher.ifBlank { null }, e.licence.ifBlank { null },
        if (e.user) "yours" else null, if (e.magnet != null) "magnet" else ".torrent").joinToString(" · ")
