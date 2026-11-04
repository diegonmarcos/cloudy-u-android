package com.diegonmarcos.superapp.scraper

import org.json.JSONArray
import org.json.JSONObject

/** #1459 CSV and JSON export; a row is (playlist name, track). */
object MusicExport {
    val COLUMNS = listOf("playlist", "title", "artist", "album", "isrc", "duration_ms", "uri")

    /** RFC 4180 cell. A leading = + @ (or tab / CR) gets a quote mark so a spreadsheet never runs it as a formula. */
    fun cell(raw: String): String {
        val s = if (raw.isNotEmpty() && raw[0] in "=+@\t\r") "'$raw" else raw
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s != s.trim()) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun csv(rows: List<Pair<String, Track>>): String {
        val sb = StringBuilder(COLUMNS.joinToString(",")).append("\r\n")
        for ((p, t) in rows) {
            sb.append(listOf(p, t.title, t.artist, t.album, t.isrc, if (t.durationMs > 0) t.durationMs.toString() else "", t.uri)
                .joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun json(rows: List<Pair<String, Track>>, source: String, exportedAt: String): String {
        val lists = JSONArray()
        for ((name, group) in rows.groupBy({ it.first }, { it.second })) {
            val ts = JSONArray()
            for (t in group) ts.put(JSONObject().put("title", t.title).put("artist", t.artist).put("album", t.album)
                .put("isrc", t.isrc).put("duration_ms", t.durationMs).put("uri", t.uri))
            lists.put(JSONObject().put("name", name).put("tracks", ts))
        }
        return JSONObject().put("format", "cloud-browser-music").put("version", 1).put("source", source)
            .put("exported_at", exportedAt).put("playlists", lists).toString(2)
    }
}
