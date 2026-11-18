package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1790 web seeds (BEP 19): an Internet Archive torrent carries `url-list` (https://archive.org/download/ plus the item's
 * data-node path) and is multi-file under the item's name. The reader keeps both, the row says how many web seeds the
 * session uses, and the engine passes the session's count through.
 */
class WebSeedTest {
    private fun archiveLike(): ByteArray {
        val f1 = "d6:lengthi5702577e4:pathl22:Rondo_alla_turca_2.mp3ee"
        val f2 = "d6:lengthi1096e4:pathl29:RondoAllaTurca_201401_meta.xmlee"
        val name = "RondoAllaTurca_201401"
        val info = "d5:filesl${f1}${f2}e4:name${name.length}:${name}12:piece lengthi524288e6:pieces20:AAAAAAAAAAAAAAAAAAAAe"
        val ws1 = "https://archive.org/download/"; val ws2 = "http://ia902704.us.archive.org/28/items/"
        val tr = "http://bt1.archive.org:6969/announce"
        return "d8:announce${tr.length}:${tr}4:info${info}8:url-listl${ws1.length}:${ws1}${ws2.length}:${ws2}ee".toByteArray(Charsets.ISO_8859_1)
    }

    @Test fun readsUrlListAndFiles() {
        val m = Magnet.parseTorrent(archiveLike())!!
        assertEquals(listOf("https://archive.org/download/", "http://ia902704.us.archive.org/28/items/"), m.webSeeds)
        assertEquals("RondoAllaTurca_201401", m.name)
        assertEquals(listOf("Rondo_alla_turca_2.mp3" to 5702577L, "RondoAllaTurca_201401_meta.xml" to 1096L), m.files)
        assertEquals(5702577L + 1096L, m.totalBytes)
    }

    @Test fun singleStringUrlListAndSingleFile() {
        val info = "d6:lengthi10e4:name5:a.mp312:piece lengthi16384e6:pieces20:AAAAAAAAAAAAAAAAAAAAe"
        val m = Magnet.parseTorrent("d4:info${info}8:url-list12:http://x.y/ae".toByteArray(Charsets.ISO_8859_1))!!
        assertEquals(listOf("http://x.y/a"), m.webSeeds)
        assertEquals(listOf("a.mp3" to 10L), m.files)
    }

    @Test fun noUrlListIsEmpty() {
        assertTrue(Magnet.parseTorrent(TorrentFixtures.torrent("x.bin", 5))!!.webSeeds.isEmpty())
    }

    @Test fun rowSaysWebSeedsWhileActive() {
        val t = TorrentStatus("a", "n", TorrentState.DOWNLOADING, 0.5f, 100, 50, 10, 0, 0, 1, 0, 0, "/x", null, true, 0, null, webSeeds = 2)
        assertTrue(TorrentFormat.line(t), TorrentFormat.line(t).contains("2 web seeds"))
        val done = t.copy(state = TorrentState.COMPLETED)
        assertTrue(!TorrentFormat.line(done).contains("web seed"))
    }

    @Test fun engineReportsTheSessionsWebSeedsAndNote() {
        val b = FakeBackend()
        val dir = java.nio.file.Files.createTempDirectory("ws").toFile()
        val e = TorrentEngine(b, MemoryStore(), MemorySettings(dir), java.io.File(dir, "keep"))
        val id = (e.addTorrentBytes("r.torrent", archiveLike()) as AddResult.Added).id
        b.raw[id] = b.raw.getValue(id).copy(phase = Phase.DOWNLOADING, webSeeds = 2, note = "url seed: 503")
        val s = e.status(id)!!
        assertEquals(2, s.webSeeds)
        assertEquals("url seed: 503", s.note)
    }
}
