package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The indexers are declared data plus a pure parser each: tested on recorded bodies, never on the network. */
class TorrentSearchTest {
    private val h1 = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    @Test fun sourceListIsDeclarativeAndHasSeveralSources() {
        val ids = TorrentIndexers.all.map { it.id }
        assertTrue("at least a couple of public indexers", ids.size >= 3)
        assertEquals("ids are unique", ids.size, ids.toSet().size)
        TorrentIndexers.all.forEach {
            assertTrue(it.label.isNotBlank() && it.blurb.isNotBlank())
            assertTrue(it.searchUrl("ubuntu 24.04").startsWith("https://"))
            assertFalse("the query is url-encoded", it.searchUrl("a b").contains("a b"))
        }
        assertNotNull(TorrentIndexers.byId("apibay")); assertNull(TorrentIndexers.byId("nope"))
    }

    @Test fun apiBayParsesHitsAndIgnoresTheNoResultsRow() {
        val body = """[{"id":"1","name":"Ubuntu 24.04","info_hash":"${h1.uppercase()}","leechers":"4","seeders":"120","size":"6000000000","added":"1700000000","num_files":"3","category":"207"},
            {"id":"2","name":"Ubuntu old","info_hash":"$h1","leechers":"0","seeders":"3","size":"1","added":"1"}]"""
        val hits = TorrentIndexers.ApiBay.parse(body)
        assertEquals(2, hits.size)
        assertEquals(120, hits[0].seeders); assertEquals(4, hits[0].leechers); assertEquals(6_000_000_000L, hits[0].sizeBytes)
        assertEquals(h1, hits[0].id())
        assertEquals("Video", hits[0].category); assertEquals(3, hits[0].files); assertEquals(1_700_000_000L, hits[0].addedAt)
        assertEquals(-1, hits[1].files); assertEquals(null, hits[1].category)
        assertEquals(h1, Magnet.infoHash(hits[0].magnetUri()!!))
        val none = """[{"id":"0","name":"No results returned","info_hash":"0000000000000000000000000000000000000000","seeders":"0"}]"""
        assertTrue(TorrentIndexers.ApiBay.parse(none).isEmpty())
    }

    @Test fun ytsGivesOneHitPerQuality() {
        val body = """{"status":"ok","data":{"movie_count":1,"movies":[{"id":1,"url":"https://yts.mx/movies/x","title_long":"Big Buck Bunny (2008)",
            "torrents":[{"hash":"$h1","quality":"720p","type":"web","seeds":50,"peers":5,"size_bytes":900},
                        {"hash":"${"ab".repeat(20)}","quality":"1080p","type":"bluray","seeds":9,"peers":1,"size_bytes":1900}]}]}}"""
        val hits = TorrentIndexers.Yts.parse(body)
        assertEquals(listOf("Big Buck Bunny (2008) [720p web]", "Big Buck Bunny (2008) [1080p bluray]"), hits.map { it.title })
        assertEquals(50, hits[0].seeders); assertEquals("Movies", hits[0].category)
        assertTrue(TorrentIndexers.Yts.parse("""{"status":"ok","data":{"movie_count":0}}""").isEmpty())
    }

    @Test fun internetArchiveHitsAreTorrentUrls() {
        val body = """{"response":{"numFound":1,"docs":[{"identifier":"bbb_sunflower","title":"Big Buck Bunny","item_size":276134947,"publicdate":"2011-02-03T04:05:06Z","mediatype":"movies","files_count":12}]}}"""
        val h = TorrentIndexers.InternetArchive.parse(body).single()
        assertEquals("https://archive.org/download/bbb_sunflower/bbb_sunflower_archive.torrent", h.torrentUrl)
        assertNull(h.magnetUri()); assertEquals(-1, h.seeders)
        assertEquals("Movies", h.category); assertEquals(12, h.files); assertEquals(1296705906L, h.addedAt)
    }

    @Test fun searchSortsBySeedersAndPassesTheUrlToTheFetch() {
        var asked = ""
        val hits = TorrentSearch.search(TorrentIndexers.ApiBay, " ubuntu ") { asked = it
            """[{"id":"1","name":"low","info_hash":"$h1","seeders":"1","leechers":"0","size":"1","added":"0"},
                {"id":"2","name":"high","info_hash":"${"ab".repeat(20)}","seeders":"99","leechers":"0","size":"1","added":"0"}]""".toByteArray() }
        assertEquals(listOf("high", "low"), hits.map { it.title })
        assertTrue(asked.contains("q=ubuntu&"))
        assertTrue(TorrentSearch.search(TorrentIndexers.ApiBay, "  ") { fail("no request for a blank query"); ByteArray(0) }.isEmpty())
    }

    @Test fun failuresSurfaceAsReasonsNeverAsEmptyResults() {
        try { TorrentSearch.search(TorrentIndexers.Yts, "x") { throw IOException("HTTP 503 from yts.mx") }; fail() } catch (e: IOException) { assertTrue(e.message!!.contains("503")) }
        try { TorrentSearch.search(TorrentIndexers.Yts, "x") { "<html>blocked</html>".toByteArray() }; fail() } catch (e: IOException) { assertTrue(e.message!!.contains("shape")) }
    }
}
