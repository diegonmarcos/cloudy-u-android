package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.scraper.AuthKind
import com.diegonmarcos.superapp.scraper.ConnectStatus
import com.diegonmarcos.superapp.scraper.How
import com.diegonmarcos.superapp.scraper.Isrc
import com.diegonmarcos.superapp.scraper.Matcher
import com.diegonmarcos.superapp.scraper.MemoryTokenStore
import com.diegonmarcos.superapp.scraper.MusicAccounts
import com.diegonmarcos.superapp.scraper.MusicCatalog
import com.diegonmarcos.superapp.scraper.MusicConnector
import com.diegonmarcos.superapp.scraper.MusicExport
import com.diegonmarcos.superapp.scraper.MusicHttp
import com.diegonmarcos.superapp.scraper.MusicOAuth
import com.diegonmarcos.superapp.scraper.MusicSync
import com.diegonmarcos.superapp.scraper.Playlist
import com.diegonmarcos.superapp.scraper.ServiceSpec
import com.diegonmarcos.superapp.scraper.SpotifyConnector
import com.diegonmarcos.superapp.scraper.TokenSet
import com.diegonmarcos.superapp.scraper.Track
import com.diegonmarcos.superapp.scraper.WebPlayer
import com.diegonmarcos.superapp.scraper.YouTubeConnector
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1459 the Music scraper: ISRC, matching, CSV/JSON export, playlist sync (dry-run), OAuth steps, tokens, connectors. */
class ScraperMusicTest {
    private fun t(title: String, artist: String, ms: Long = 0, isrc: String = "", id: String = "") = Track(title, artist, isrc = isrc, durationMs = ms, id = id)

    // ---- ISRC
    @Test fun `isrc is normalised and validated`() {
        assertEquals("USRC17607839", Isrc.clean("us-rc1-76-07839"))
        assertEquals("GBAYE0601498", Isrc.clean(" gbaye 0601498 "))
        assertTrue(Isrc.valid("USRC17607839"))
        for (bad in listOf("", "USRC1760783", "USRC176078390", "1SRC17607839", "USRC1760783A", null)) assertFalse("$bad", Isrc.valid(bad))
        assertEquals("", Isrc.clean("not an isrc"))
    }

    // ---- matching
    @Test fun `isrc match wins even when the text differs`() {
        val src = t("Hello", "Adele", 295_000, "GBBKS1500214")
        val m = Matcher.best(src, listOf(t("Hello (live)", "Adele", 295_000), t("Something else", "Other", 1000, "gbbks1500214", id = "x")))
        assertEquals(How.ISRC, m.how)
        assertEquals("x", m.candidate?.id)
    }

    @Test fun `fuzzy match ignores feat, brackets, remaster suffix and accents`() {
        val src = t("Déjà Vu (feat. Someone)", "Beyoncé, Jay-Z", 240_000)
        val m = Matcher.best(src, listOf(t("Deja Vu - 2011 Remaster", "Beyonce", 241_500, id = "ok"), t("Deja Vu Remix Party", "DJ Other", 240_000)))
        assertEquals(How.FUZZY, m.how)
        assertEquals("ok", m.candidate?.id)
        assertTrue(m.score >= Matcher.THRESHOLD)
    }

    @Test fun `different song or artist is not matched`() {
        assertEquals(How.NONE, Matcher.best(t("Yesterday", "The Beatles", 125_000), listOf(t("Yesterday", "Boyz II Men", 125_000))).how)
        assertEquals(How.NONE, Matcher.best(t("Yesterday", "The Beatles", 125_000), listOf(t("Tomorrow", "The Beatles", 125_000))).how)
        assertEquals(How.NONE, Matcher.best(t("Yesterday", "The Beatles"), emptyList()).how)
    }

    @Test fun `duration decides between two versions and is skipped when unknown`() {
        val src = t("Song", "Artist", 200_000)
        val m = Matcher.best(src, listOf(t("Song", "Artist", 260_000, id = "long"), t("Song", "Artist", 201_000, id = "right")))
        assertEquals("right", m.candidate?.id)
        assertNotNull(Matcher.score(t("Song", "Artist"), t("Song", "Artist", 123_000)))
        assertEquals(1.0, Matcher.durationSim(200_000, 202_000)!!, 0.0)
        assertEquals(0.0, Matcher.durationSim(200_000, 211_000)!!, 0.0)
        assertNull(Matcher.durationSim(0, 5))
    }

    @Test fun `non latin titles still compare`() {
        assertEquals(How.FUZZY, Matcher.best(t("夜に駆ける", "YOASOBI", 261_000), listOf(t("夜に駆ける", "YOASOBI", 261_000, id = "j"))).how)
    }

    // ---- export
    @Test fun `csv quotes commas, quotes and newlines and defuses formulas`() {
        val rows = listOf("My, list" to Track("He said \"hi\"", "A\nB", "=cmd|x", "USRC17607839", 1234, uri = "u"), "p" to Track("T", "@x"))
        val expected = "playlist,title,artist,album,isrc,duration_ms,uri\r\n" +
            "\"My, list\",\"He said \"\"hi\"\"\",\"A\nB\",'=cmd|x,USRC17607839,1234,u\r\n" +
            "p,T,'@x,,,,\r\n"
        assertEquals(expected, MusicExport.csv(rows))
    }

    @Test fun `json groups tracks by playlist and keeps the fields`() {
        val rows = listOf("a" to Track("T1", "X", "Al", "USRC17607839", 1000, uri = "u1"), "b" to Track("T2", "Y"), "a" to Track("T3", "Z"))
        val o = JSONObject(MusicExport.json(rows, "spotify", "2026-11-04T00:00:00Z"))
        assertEquals("spotify", o.getString("source"))
        assertEquals(2, o.getJSONArray("playlists").length())
        val a = o.getJSONArray("playlists").getJSONObject(0)
        assertEquals("a", a.getString("name"))
        assertEquals(2, a.getJSONArray("tracks").length())
        assertEquals("USRC17607839", a.getJSONArray("tracks").getJSONObject(0).getString("isrc"))
        assertEquals(1000L, a.getJSONArray("tracks").getJSONObject(0).getLong("duration_ms"))
    }

    // ---- sync
    private class Fake(override val id: String = "fake", override val canWrite: Boolean = true, val shelf: List<Track>) : MusicConnector {
        val created = ArrayList<String>()
        val added = ArrayList<String>()
        override fun playlists() = emptyList<Playlist>()
        override fun tracks(p: Playlist) = emptyList<Track>()
        override fun liked() = emptyList<Track>()
        override fun find(t: Track) = shelf
        override fun createPlaylist(name: String): String { created += name; return "pl1" }
        override fun addTracks(playlistId: String, ids: List<String>) { added += ids }
    }

    private val shelf = listOf(t("One", "Band", 100_000, "USRC10000001", "id1"), t("Two", "Band", 200_000, id = "id2"))
    private val source = listOf(t("One", "Band", 0, "USRC10000001"), t("Two (Remastered)", "Band", 200_500), t("Three", "Nobody"), t("One", "Band", 0, "USRC10000001"))

    @Test fun `plan counts isrc, fuzzy and unmatched and lists each target song once`() {
        val plan = MusicSync.plan("List", source, Fake(shelf = shelf))
        assertEquals(3, plan.byIsrc + plan.byFuzzy)
        assertEquals(2, plan.byIsrc)
        assertEquals(1, plan.byFuzzy)
        assertEquals(listOf("Three"), plan.unmatched.map { it.source.title })
        assertEquals(listOf("id1", "id2"), plan.targetIds)
    }

    @Test fun `dry run writes nothing, a real run creates the list and adds the ids`() {
        val dst = Fake(shelf = shelf)
        val plan = MusicSync.plan("List", source, dst)
        val dry = MusicSync.run(plan, dst, dryRun = true)
        assertTrue(dry.dryRun); assertEquals(0, dry.added)
        assertTrue(dst.created.isEmpty() && dst.added.isEmpty())
        val real = MusicSync.run(plan, dst, dryRun = false)
        assertEquals(2, real.added)
        assertEquals(listOf("List"), dst.created)
        assertEquals(listOf("id1", "id2"), dst.added)
    }

    @Test fun `a read-only target refuses a real run but allows a dry run`() {
        val dst = Fake(canWrite = false, shelf = shelf)
        val plan = MusicSync.plan("List", source, dst)
        assertEquals(0, MusicSync.run(plan, dst, true).added)
        try { MusicSync.run(plan, dst, false); org.junit.Assert.fail("expected refusal") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("fake")) }
    }

    @Test fun `nothing matched creates no empty playlist and big lists are added in chunks`() {
        val none = Fake(shelf = emptyList())
        assertNull(MusicSync.run(MusicSync.plan("L", source, none), none, false).playlistId)
        assertTrue(none.created.isEmpty())
        val many = (1..120).map { t("Song $it", "Band $it", id = "i$it") }
        val dst = object : MusicConnector by Fake(shelf = many) {
            val sizes = ArrayList<Int>()
            override fun find(t: Track) = many
            override fun addTracks(playlistId: String, ids: List<String>) { sizes += ids.size }
        }
        MusicSync.run(MusicSync.plan("L", many, dst), dst, false)
        assertEquals(listOf(50, 50, 20), dst.sizes)
    }

    // ---- catalogue, tokens, OAuth
    @Test fun `catalogue declares the four services and only web-only Apple has a web reader`() {
        val s = MusicCatalog.services
        assertEquals(listOf("spotify", "ytmusic", "deezer", "apple"), s.map { it.id })
        assertEquals(AuthKind.WEB, s.single { it.id == "apple" }.auth)
        assertNotNull(s.single { it.id == "apple" }.web)
        assertFalse(s.single { it.id == "apple" }.write)
        assertTrue(s.filter { it.id != "apple" }.all { it.write && it.web == null })
    }

    @Test fun `token status follows save, expiry, refresh and disconnect`() {
        var now = 1_000_000L
        val a = MusicAccounts(MemoryTokenStore()) { now }
        val sp = MusicCatalog.services.first { it.id == "spotify" }
        assertEquals(ConnectStatus.DISCONNECTED, a.status(sp))
        a.save("spotify", TokenSet("tok", "ref", 3600))
        assertEquals(ConnectStatus.CONNECTED, a.status(sp))
        assertEquals("tok", a.access("spotify"))
        now += 3_600_000
        assertEquals(ConnectStatus.EXPIRED, a.status(sp)); assertNull(a.access("spotify")); assertEquals("ref", a.refreshToken("spotify"))
        a.save("spotify", TokenSet("never", null, 0)); now += 99_000_000
        assertEquals(ConnectStatus.CONNECTED, a.status(sp))
        a.disconnect("spotify")
        assertEquals(ConnectStatus.DISCONNECTED, a.status(sp)); assertNull(a.refreshToken("spotify"))
        assertEquals(ConnectStatus.WEB, a.status(MusicCatalog.services.first { it.id == "apple" }))
    }

    @Test fun `pkce challenge matches the RFC 7636 vector and the auth url carries it`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", MusicOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = MusicOAuth.verifier()
        assertTrue(v.length >= 43 && v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val sp = MusicCatalog.services.first { it.id == "spotify" }
        val u = MusicOAuth.authUrl(sp, "CID", "st", "CH")
        assertTrue(u.startsWith("https://accounts.spotify.com/authorize?"))
        for (p in listOf("response_type=code", "client_id=CID", "code_challenge=CH", "code_challenge_method=S256", "state=st", "redirect_uri=http%3A%2F%2F127.0.0.1%3A8765%2Fcallback")) assertTrue(p, u.contains(p))
    }

    @Test fun `redirect callback gives the code, the fragment token, errors and ignores other pages`() {
        val sp = MusicCatalog.services.first { it.id == "spotify" }
        val dz = MusicCatalog.services.first { it.id == "deezer" }
        assertEquals("abc", MusicOAuth.callback(sp.redirect + "?code=abc&state=st", sp, "st")?.code)
        assertEquals("state mismatch", MusicOAuth.callback(sp.redirect + "?code=abc&state=evil", sp, "st")?.error)
        assertEquals("access_denied", MusicOAuth.callback(sp.redirect + "?error=access_denied&state=st", sp, "st")?.error)
        val cb = MusicOAuth.callback(dz.redirect + "#access_token=TK&expires=0", dz, "st")
        assertEquals("TK", cb?.token); assertEquals(0L, cb?.expiresInSec)
        assertNull(MusicOAuth.callback("https://accounts.spotify.com/login", sp, "st"))
    }

    // ---- HTTP, connectors
    private fun spec(base: String) = ServiceSpec("spotify", "Spotify", AuthKind.PKCE, api = base)

    @Test fun `http retries 429 with Retry-After then succeeds, and a 401 is final`() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
            s.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            s.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
            val waits = ArrayList<Long>()
            val http = MusicHttp(sleep = { waits += it })
            assertEquals("{\"ok\":true}", http.get(s.url("/x").toString()))
            assertEquals(listOf(2000L), waits)
            try { http.get(s.url("/y").toString()); org.junit.Assert.fail() } catch (e: com.diegonmarcos.superapp.scraper.MusicHttpException) { assertEquals(401, e.code) }
            assertEquals(3, s.requestCount)
        }
    }

    @Test fun `spotify reads playlists and tracks with isrc, skips local files and never follows a foreign next link`() {
        MockWebServer().use { s ->
            val base = s.url("/v1").toString().trimEnd('/')
            s.enqueue(MockResponse().setBody("""{"items":[{"id":"p1","name":"Mix","tracks":{"total":2}}],"next":"https://evil.example/steal"}"""))
            s.enqueue(MockResponse().setBody("""{"items":[
                {"track":{"id":"a1","uri":"spotify:track:a1","name":"Song","duration_ms":200000,"artists":[{"name":"A"},{"name":"B"}],"album":{"name":"Alb"},"external_ids":{"isrc":"US-RC1-76-07839"}}},
                {"track":{"id":null,"name":"local file","artists":[]}}],"next":null}"""))
            val c = SpotifyConnector(spec(base), "TOKEN", MusicHttp())
            val pls = c.playlists()
            assertEquals(listOf(Playlist("p1", "Mix", 2)), pls)
            val tr = c.tracks(pls[0])
            assertEquals(1, tr.size)
            assertEquals(Track("Song", "A, B", "Alb", "USRC17607839", 200_000, "spotify:track:a1", "https://open.spotify.com/track/a1"), tr[0])
            val r1 = s.takeRequest()
            val r2 = s.takeRequest()
            assertEquals("Bearer TOKEN", r1.getHeader("Authorization"))
            assertEquals("/v1/me/playlists?limit=50", r1.path)
            assertEquals("/v1/playlists/p1/tracks?limit=100", r2.path)
            assertEquals(2, s.requestCount)
        }
    }

    @Test fun `youtube video becomes a song and iso durations parse`() {
        val a = YouTubeConnector.track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley - Topic", 213_000)
        assertEquals("Rick Astley", a.artist); assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", a.uri); assertEquals("dQw4w9WgXcQ", a.id)
        val b = YouTubeConnector.track("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "Some Channel", 0)
        assertEquals("Rick Astley", b.artist); assertEquals("Never Gonna Give You Up", b.title)
        assertEquals(3_723_000L, YouTubeConnector.isoDurationMs("PT1H2M3S"))
        assertEquals(213_000L, YouTubeConnector.isoDurationMs("PT3M33S"))
        assertEquals(0L, YouTubeConnector.isoDurationMs("P0D"))
    }

    @Test fun `web player rows unwrap the evaluated string, drop blanks and merge scroll windows`() {
        val inner = """[{"title":"Song","artist":"Art","album":"Alb","duration_ms":185000},{"title":"","artist":"x"}]"""
        val evaluated = JSONObject.quote(inner)
        val rows = WebPlayer.parse(evaluated)
        assertEquals(listOf(Track("Song", "Art", "Alb", "", 185_000)), rows)
        assertTrue(WebPlayer.parse("null").isEmpty())
        val merged = WebPlayer.merge(rows, listOf(Track("SONG", "art", "alb"), Track("Other", "Art")))
        assertEquals(listOf("Song", "Other"), merged.map { it.title })
    }
}
