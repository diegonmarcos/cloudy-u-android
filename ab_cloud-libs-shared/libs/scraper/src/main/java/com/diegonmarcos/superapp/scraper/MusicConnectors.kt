package com.diegonmarcos.superapp.scraper

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun String.enc() = MusicHttp.enc(this)
private fun String.firstArtist() = split(',', '&').first().trim()

/** #1459 Spotify Web API (OAuth PKCE). ISRC comes from track.external_ids when the API returns it. */
class SpotifyConnector(private val spec: ServiceSpec, token: String, private val http: MusicHttp = MusicHttp()) : MusicConnector {
    override val id = "spotify"
    override val canWrite = true
    private val auth = mapOf("Authorization" to "Bearer $token")

    private fun getJson(url: String) = JSONObject(http.get(url, auth))

    private fun paged(first: String, each: (JSONObject) -> Unit) {
        var url: String? = spec.api + first
        while (url != null) {
            val o = getJson(url)
            o.optJSONArray("items").objects().forEach(each)
            url = o.str("next").takeIf { it.startsWith(spec.api) }   // never send the token to another host
        }
    }

    override fun playlists(): List<Playlist> = ArrayList<Playlist>().also { out ->
        paged("/me/playlists?limit=50") { out += Playlist(it.getString("id"), it.str("name"), it.optJSONObject("tracks")?.optInt("total") ?: 0) }
    }

    override fun tracks(p: Playlist): List<Track> = ArrayList<Track>().also { out ->
        paged("/playlists/${p.id}/tracks?limit=100") { item -> item.optJSONObject("track")?.let { track(it) }?.let { out += it } }
    }

    override fun liked(): List<Track> = ArrayList<Track>().also { out ->
        paged("/me/tracks?limit=50") { item -> item.optJSONObject("track")?.let { track(it) }?.let { out += it } }
    }

    override fun find(t: Track): List<Track> {
        val code = Isrc.clean(t.isrc)
        val byIsrc = if (code.isNotEmpty()) search("isrc:$code") else emptyList()
        if (byIsrc.isNotEmpty()) return byIsrc
        return search("track:\"${t.title.replace('"', ' ')}\" artist:\"${t.artist.firstArtist().replace('"', ' ')}\"")
    }

    private fun search(q: String): List<Track> =
        getJson("${spec.api}/search?type=track&limit=5&q=${q.enc()}").optJSONObject("tracks")?.optJSONArray("items").objects().mapNotNull { track(it) }

    override fun createPlaylist(name: String): String {
        val me = getJson("${spec.api}/me").getString("id")
        val body = JSONObject().put("name", name).put("public", false).put("description", "Copied by Cloud Browser")
        return JSONObject(http.postJson("${spec.api}/users/${me.enc()}/playlists", body, auth)).getString("id")
    }

    override fun addTracks(playlistId: String, ids: List<String>) {
        http.postJson("${spec.api}/playlists/$playlistId/tracks", JSONObject().put("uris", JSONArray(ids)), auth)
    }

    companion object {
        /** null for a local file or a removed track (no id). */
        fun track(o: JSONObject): Track? {
            if (o.isNull("id")) return null
            val artists = o.optJSONArray("artists").objects().joinToString(", ") { it.str("name") }
            return Track(o.str("name"), artists, o.optJSONObject("album")?.str("name").orEmpty(),
                Isrc.clean(o.optJSONObject("external_ids")?.str("isrc")), o.optLong("duration_ms"),
                id = o.str("uri"), uri = "https://open.spotify.com/track/" + o.str("id"))
        }
    }
}

/** #1459 YouTube Data API v3 (OAuth device flow). No ISRC in the API: matching falls to title, artist and length. */
class YouTubeConnector(private val spec: ServiceSpec, token: String, private val http: MusicHttp = MusicHttp()) : MusicConnector {
    override val id = "ytmusic"
    override val canWrite = true
    private val auth = mapOf("Authorization" to "Bearer $token")

    private fun getJson(path: String) = JSONObject(http.get(spec.api + path, auth))

    private fun paged(path: String, each: (JSONObject) -> Unit) {
        var token = ""
        do {
            val o = getJson(path + if (token.isEmpty()) "" else "&pageToken=${token.enc()}")
            o.optJSONArray("items").objects().forEach(each)
            token = o.str("nextPageToken")
        } while (token.isNotEmpty())
    }

    override fun playlists(): List<Playlist> = ArrayList<Playlist>().also { out ->
        paged("/playlists?part=snippet,contentDetails&mine=true&maxResults=50") {
            out += Playlist(it.getString("id"), it.optJSONObject("snippet")?.str("title").orEmpty(), it.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0)
        }
    }

    override fun tracks(p: Playlist): List<Track> {
        val rows = ArrayList<Triple<String, String, String>>()   // video id, title, channel
        paged("/playlistItems?part=snippet&maxResults=50&playlistId=${p.id.enc()}") {
            val s = it.optJSONObject("snippet") ?: return@paged
            val vid = s.optJSONObject("resourceId")?.str("videoId").orEmpty()
            if (vid.isNotEmpty() && s.str("title") !in setOf("Private video", "Deleted video")) rows += Triple(vid, s.str("title"), s.str("videoOwnerChannelTitle"))
        }
        val lengths = rows.map { it.first }.chunked(50).flatMap { ids ->
            getJson("/videos?part=contentDetails&maxResults=50&id=${ids.joinToString(",").enc()}").optJSONArray("items").objects()
        }.associate { it.str("id") to isoDurationMs(it.optJSONObject("contentDetails")?.str("duration").orEmpty()) }
        return rows.map { (vid, title, ch) -> track(vid, title, ch, lengths[vid] ?: 0) }
    }

    /** The liked-videos list (playlist id LL). */
    override fun liked(): List<Track> = tracks(Playlist("LL", "Liked"))

    override fun find(t: Track): List<Track> {
        val q = "${t.artist.firstArtist()} ${t.title}"
        val hits = getJson("/search?part=snippet&type=video&videoCategoryId=10&maxResults=5&q=${q.enc()}").optJSONArray("items").objects()
        val ids = hits.mapNotNull { it.optJSONObject("id")?.str("videoId")?.takeIf { v -> v.isNotEmpty() } }
        if (ids.isEmpty()) return emptyList()
        val lengths = getJson("/videos?part=contentDetails&id=${ids.joinToString(",").enc()}").optJSONArray("items").objects()
            .associate { it.str("id") to isoDurationMs(it.optJSONObject("contentDetails")?.str("duration").orEmpty()) }
        return hits.mapNotNull { h ->
            val vid = h.optJSONObject("id")?.str("videoId").orEmpty().ifEmpty { return@mapNotNull null }
            val s = h.optJSONObject("snippet") ?: return@mapNotNull null
            track(vid, s.str("title"), s.str("channelTitle"), lengths[vid] ?: 0)
        }
    }

    override fun createPlaylist(name: String): String {
        val body = JSONObject().put("snippet", JSONObject().put("title", name)).put("status", JSONObject().put("privacyStatus", "private"))
        return JSONObject(http.postJson("${spec.api}/playlists?part=snippet,status", body, auth)).getString("id")
    }

    override fun addTracks(playlistId: String, ids: List<String>) {
        for (v in ids) {
            val s = JSONObject().put("playlistId", playlistId).put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", v))
            http.postJson("${spec.api}/playlistItems?part=snippet", JSONObject().put("snippet", s), auth)
        }
    }

    companion object {
        /** A video as a song: "Artist - Topic" channels give the artist, "Artist - Song" titles split, else the channel is the artist. */
        fun track(videoId: String, title: String, channel: String, durationMs: Long): Track {
            var artist = channel.removeSuffix(" - Topic").trim()
            var name = title
            if (!channel.endsWith(" - Topic") && title.contains(" - ")) { artist = title.substringBefore(" - ").trim(); name = title.substringAfter(" - ").trim() }
            return Track(name, artist, "", "", durationMs, id = videoId, uri = YtTool.canonical(videoId))
        }

        /** ISO 8601 "PT1H2M3S" in ms, 0 when it is not one. */
        fun isoDurationMs(iso: String): Long {
            val m = Regex("""^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""").find(iso) ?: return 0
            val (h, mi, s) = m.destructured
            return ((h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
        }
    }
}

/** #1459 Deezer API (OAuth implicit). Playlist rows carry no ISRC, so each track is looked up once for it (throttled). */
class DeezerConnector(
    private val spec: ServiceSpec,
    private val token: String,
    private val http: MusicHttp = MusicHttp(),
    private val pause: () -> Unit = { Thread.sleep(120) },
) : MusicConnector {
    override val id = "deezer"
    override val canWrite = true

    private fun url(path: String) = spec.api + path + (if (path.contains('?')) "&" else "?") + "access_token=${token.enc()}"

    private fun paged(first: String, each: (JSONObject) -> Unit) {
        var u: String? = url(first)
        while (u != null) {
            val o = JSONObject(http.get(u))
            if (o.has("error")) throw MusicHttpException(401, o.optJSONObject("error")?.str("message") ?: "Deezer error")
            o.optJSONArray("data").objects().forEach(each)
            u = o.str("next").takeIf { it.startsWith(spec.api) }
        }
    }

    override fun playlists(): List<Playlist> = ArrayList<Playlist>().also { out ->
        paged("/user/me/playlists?limit=100") { out += Playlist(it.get("id").toString(), it.str("title"), it.optInt("nb_tracks")) }
    }

    override fun tracks(p: Playlist): List<Track> = ArrayList<Track>().also { out ->
        paged("/playlist/${p.id}/tracks?limit=100") { out += withIsrc(track(it)) }
    }

    override fun liked(): List<Track> = ArrayList<Track>().also { out ->
        paged("/user/me/tracks?limit=100") { out += withIsrc(track(it)) }
    }

    private fun withIsrc(t: Track): Track {
        if (t.isrc.isNotEmpty() || t.id.isEmpty()) return t
        pause()
        val code = runCatching { Isrc.clean(JSONObject(http.get(url("/track/${t.id}"))).str("isrc")) }.getOrDefault("")
        return t.copy(isrc = code)
    }

    override fun find(t: Track): List<Track> {
        val code = Isrc.clean(t.isrc)
        if (code.isNotEmpty()) {
            val o = runCatching { JSONObject(http.get(url("/track/isrc:$code"))) }.getOrNull()
            if (o != null && !o.has("error") && o.has("id")) return listOf(track(o))
        }
        val q = "artist:\"${t.artist.firstArtist().replace('"', ' ')}\" track:\"${t.title.replace('"', ' ')}\""
        return JSONObject(http.get(url("/search?limit=5&q=${q.enc()}"))).optJSONArray("data").objects().map { track(it) }
    }

    override fun createPlaylist(name: String): String =
        JSONObject(http.postForm(spec.api + "/user/me/playlists", mapOf("title" to name, "access_token" to token))).get("id").toString()

    override fun addTracks(playlistId: String, ids: List<String>) {
        http.postForm("${spec.api}/playlist/$playlistId/tracks", mapOf("songs" to ids.joinToString(","), "access_token" to token))
    }

    companion object {
        fun track(o: JSONObject): Track = Track(o.str("title"), o.optJSONObject("artist")?.str("name").orEmpty(), o.optJSONObject("album")?.str("title").orEmpty(),
            Isrc.clean(o.str("isrc")), o.optLong("duration") * 1000, id = o.opt("id")?.toString().orEmpty(), uri = o.str("link"))
    }
}

/** #1459 a read-only source whose rows came from a web player page the user opened (Apple Music). */
class SnapshotConnector(override val id: String, private val listName: String, private val rows: List<Track>) : MusicConnector {
    override val canWrite = false
    override fun playlists() = listOf(Playlist(listName, listName, rows.size))
    override fun tracks(p: Playlist) = rows
    override fun liked() = emptyList<Track>()
    override fun find(t: Track): List<Track> = throw UnsupportedOperationException("$id is read-only")
    override fun createPlaylist(name: String): String = throw UnsupportedOperationException("$id is read-only")
    override fun addTracks(playlistId: String, ids: List<String>) { throw UnsupportedOperationException("$id is read-only") }
}

object MusicConnectors {
    /** The API connector of [spec] signed in with [token]; null for a web-only service. */
    fun create(spec: ServiceSpec, token: String, http: MusicHttp = MusicHttp()): MusicConnector? = when (spec.id) {
        "spotify" -> SpotifyConnector(spec, token, http)
        "ytmusic" -> YouTubeConnector(spec, token, http)
        "deezer" -> DeezerConnector(spec, token, http)
        else -> null
    }
}

/** #1459 reading a logged-in web player page: the user opens the page, the script reads the rows on screen (no automation, no network). */
object WebPlayer {
    /** The JS (for WebView.evaluateJavascript) that returns the page's track rows as a JSON string. */
    fun script(w: WebSpec): String {
        val sel = JSONObject().put("row", w.row).put("title", w.title).put("artist", w.artist).put("album", w.album).put("duration", w.duration)
        return "(function(){var S=$sel;var out=[],seen={};" +
            "function t(r,s){var e=s?r.querySelector(s):null;return e?e.textContent.trim():''}" +
            "function dur(x){var p=x.split(':').map(Number);if(!x||p.some(isNaN))return 0;var s=0;p.forEach(function(v){s=s*60+v});return s*1000}" +
            "document.querySelectorAll(S.row).forEach(function(r){var ti=t(r,S.title);if(!ti)return;" +
            "var a=t(r,S.artist),al=t(r,S.album);out.push({title:ti,artist:a,album:al,duration_ms:dur(t(r,S.duration))})});" +
            "return JSON.stringify(out)})()"
    }

    /** evaluateJavascript hands the script's string back JSON-quoted; this unwraps it and maps the rows (apple music has no ISRC on the page). */
    fun parse(evaluated: String?): List<Track> {
        if (evaluated.isNullOrBlank() || evaluated == "null") return emptyList()
        val inner = (org.json.JSONTokener(evaluated).nextValue() as? String) ?: evaluated
        val arr = JSONArray(inner)
        return arr.objects().map { Track(it.str("title"), it.str("artist"), it.str("album"), "", it.optLong("duration_ms")) }
            .filter { it.title.isNotBlank() }
    }

    /** Rows read by several taps (a virtualised list shows a window of it): each song once. */
    fun merge(have: List<Track>, more: List<Track>): List<Track> =
        (have + more).distinctBy { listOf(Matcher.norm(it.title), Matcher.norm(it.artist), Matcher.norm(it.album)) }
}
