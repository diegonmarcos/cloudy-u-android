package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** User-manageable torrent sources: validation, persistence through the store, the search set, and each parse kind on a recorded body. */
class TorrentSourcesTest {
    private val h1 = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
    private val h2 = "0123456789abcdef0123456789abcdef01234567"
    private fun src(id: String = "mine", url: String = "https://example.org/rss?q={q}", kind: ParseKind = ParseKind.RSS, enabled: Boolean = true) =
        UserSource(id, "My $id", "https://example.org", url, kind, enabled)

    @Test fun builtInsStayAndUserSourcesAddToThem() {
        val store = MemorySourceStore()
        val s = TorrentSources(store)
        assertEquals(TorrentIndexers.all.map { it.id }, s.searchSet().map { it.id })
        assertTrue(s.add(src("mine")).ok)
        assertTrue(s.add(src("off", enabled = false)).ok)
        assertEquals("built-ins first, then the user's ENABLED sources", TorrentIndexers.all.map { it.id } + "mine", s.searchSet().map { it.id })
        assertEquals("a disabled source is still listed in Configs", listOf("mine", "off"), s.user().map { it.id })
        assertNotNull("a disabled source can still be looked up", s.byId("off"))
        assertNotNull(s.byId("apibay")); assertNull(s.byId("nope"))
        assertTrue(s.setEnabled("off", true))
        assertEquals(TorrentIndexers.all.size + 2, s.searchSet().size)
    }

    @Test fun sourcesSurviveARestartAndEveryFieldIsKept() {
        val store = MemorySourceStore()
        TorrentSources(store).add(UserSource("mine", "My indexer", "https://example.org", "https://example.org/search?q={q}", ParseKind.HTML_MAGNETS, false))
        val again = TorrentSources(store).user().single()
        assertEquals(UserSource("mine", "My indexer", "https://example.org", "https://example.org/search?q={q}", ParseKind.HTML_MAGNETS, false), again)
    }

    @Test fun editAndRemove() {
        val s = TorrentSources(MemorySourceStore())
        s.add(src("mine"))
        val rev = s.revision
        assertTrue(s.update("mine", src("ignored-id", url = "https://other.example/q={q}", kind = ParseKind.APIBAY_JSON)).ok)
        assertTrue("the id of an edited source cannot change", s.user().single().id == "mine")
        assertEquals(ParseKind.APIBAY_JSON, s.user().single().kind)
        assertTrue(s.revision > rev)
        assertFalse(s.update("ghost", src("ghost")).ok)
        assertTrue(s.remove("mine")); assertFalse(s.remove("mine"))
        assertTrue(s.user().isEmpty())
        assertEquals("the built-ins can never be removed", TorrentIndexers.all.size, s.searchSet().size)
    }

    @Test fun validationSaysWhatIsWrong() {
        val s = TorrentSources(MemorySourceStore())
        assertTrue(s.add(src("apibay")).error!!.contains("built-in"))
        assertTrue(s.add(src("Bad Id!")).error!!.contains("id"))
        assertTrue(s.add(src("a", url = "https://example.org/search")).error!!.contains("{q}"))
        assertTrue(s.add(src("b", url = "http://example.org/?q={q}")).error!!.contains("https"))
        assertTrue(s.add(src("c", url = "ftp://example.org/?q={q}")).error!!.contains("https"))
        assertTrue(s.add(UserSource("d", " ", "", "https://example.org/?q={q}", ParseKind.RSS)).error!!.contains("name"))
        assertTrue(s.add(UserSource("e", "E", "http://example.org", "https://example.org/?q={q}", ParseKind.RSS)).error!!.contains("homepage"))
        assertTrue(s.user().isEmpty())
        assertTrue(s.add(src("ok")).ok)
        assertTrue(s.add(src("ok")).error!!.contains("already"))
        assertTrue("{query} is accepted too", s.add(src("q2", url = "https://example.org/?q={query}")).ok)
    }

    @Test fun theQueryIsEncodedAndPlaceholdersFilled() {
        assertEquals("https://example.org/rss?q=ubuntu%2024.04%20%26%20more", TorrentSources.expand("https://example.org/rss?q={q}", " ubuntu 24.04 & more "))
        assertEquals("https://example.org/a/x%20y/b?q=x%20y", TorrentSources.expand("https://example.org/a/{q}/b?q={query}", "x y"))
        assertEquals("my-indexer", TorrentSources.idFor("  My Indexer! "))
    }

    @Test fun rssTorznabItemsBecomeHits() {
        val body = """<?xml version="1.0"?><rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><title>x</title>
          <item><title><![CDATA[Ubuntu 24.04 [1080p]]]></title><link>https://idx.example/details/1</link>
            <enclosure url="https://idx.example/dl/1.torrent?apikey=k&amp;x=1" length="6000" type="application/x-bittorrent"/>
            <pubDate>Tue, 14 Nov 2023 22:13:20 +0000</pubDate>
            <torznab:attr name="seeders" value="120"/><torznab:attr name="peers" value="130"/><torznab:attr name="infohash" value="${h1.uppercase()}"/><torznab:attr name="size" value="6000000000"/></item>
          <item><title>Magnet only &amp; more</title><link>magnet:?xt=urn:btih:$h2&amp;dn=Magnet+only</link></item>
          <item><title>No source at all</title><link>https://idx.example/page-only</link></item>
        </channel></rss>"""
        val hits = ParseKind.RSS.parse("mine", body)
        assertEquals(2, hits.size)
        val a = hits[0]
        assertEquals("mine", a.indexerId); assertEquals("Ubuntu 24.04 [1080p]", a.title)
        assertEquals(h1, a.id()); assertEquals(120, a.seeders); assertEquals(10, a.leechers); assertEquals(6_000_000_000L, a.sizeBytes)
        assertEquals(1_700_000_000L, a.addedAt)
        assertEquals("the hash is enough for a magnet", h1, Magnet.infoHash(a.magnetUri()!!))
        assertEquals("https://idx.example/details/1", a.page)
        val b = hits[1]
        assertEquals("Magnet only & more", b.title); assertEquals(h2, b.id()); assertEquals(-1, b.seeders); assertEquals(-1L, b.sizeBytes)
        assertTrue(b.magnetUri()!!.startsWith("magnet:?xt=urn:btih:$h2"))
    }

    @Test fun rssTorrentOnlyItemIsAFetchableUrlAndAnEmptyFeedIsAnEmptyList() {
        val one = """<rss><channel><item><title>T</title><enclosure url="https://idx.example/get/9.torrent" length="5"/></item></channel></rss>"""
        val h = ParseKind.RSS.parse("m", one).single()
        assertEquals("https://idx.example/get/9.torrent", h.torrentUrl); assertNull(h.magnetUri()); assertEquals(5L, h.sizeBytes)
        assertTrue(ParseKind.RSS.parse("m", "<rss><channel><title>none</title></channel></rss>").isEmpty())
        try { ParseKind.RSS.parse("m", "{\"not\":\"xml\"}"); fail("a body that is not a feed is an error, not an empty list") } catch (e: org.json.JSONException) { }
    }

    @Test fun htmlMagnetsAreNamedByDnAndDeduplicated() {
        val body = """<html><a href="magnet:?xt=urn:btih:$h1&amp;dn=Some+Linux+ISO&amp;tr=udp%3A%2F%2Ft.example%3A80">m</a>
            <a href='magnet:?xt=urn:btih:$h1&dn=dup'>again</a><a href="magnet:?dn=no-hash">bad</a><a href="https://x">page</a></html>"""
        val hits = ParseKind.HTML_MAGNETS.parse("m", body)
        assertEquals(1, hits.size)
        assertEquals("Some Linux ISO", hits[0].title); assertEquals(h1, hits[0].id()); assertEquals(-1, hits[0].seeders)
        assertTrue(ParseKind.HTML_MAGNETS.parse("m", "<html>nothing</html>").isEmpty())
    }

    @Test fun jsonKindsReuseTheBuiltInParsersUnderTheUsersId() {
        val body = """[{"id":"1","name":"Ubuntu","info_hash":"$h1","leechers":"4","seeders":"12","size":"600","added":"1700000000","category":"207"}]"""
        val h = ParseKind.APIBAY_JSON.parse("mirror", body).single()
        assertEquals("mirror", h.indexerId); assertEquals(12, h.seeders); assertNull("no built-in site link on a user's mirror", h.page)
        assertEquals("apibay", TorrentIndexers.ApiBay.parse(body).single().indexerId)
        assertNotNull(TorrentIndexers.ApiBay.parse(body).single().page)
    }

    @Test fun aUserSourceSearchesLikeABuiltInOne() {
        val ix = UserIndexer(src("mine", url = "https://example.org/rss?q={q}"))
        var asked = ""
        val feed = """<rss><channel><item><title>A</title><link>magnet:?xt=urn:btih:$h1</link><torznab:attr name="seeders" value="3"/></item>
            <item><title>B</title><link>magnet:?xt=urn:btih:$h2</link><torznab:attr name="seeders" value="30"/></item></channel></rss>"""
        val hits = TorrentSearch.search(ix, "debian 12") { asked = it; feed.toByteArray() }
        assertEquals("https://example.org/rss?q=debian%2012", asked)
        assertEquals(listOf("B", "A"), hits.map { it.title })
        assertTrue("an unreadable answer says so", runCatching { TorrentSearch.search(UserIndexer(src("j", kind = ParseKind.APIBAY_JSON)), "x") { "<html>".toByteArray() } }.exceptionOrNull() is IOException)
    }
}
