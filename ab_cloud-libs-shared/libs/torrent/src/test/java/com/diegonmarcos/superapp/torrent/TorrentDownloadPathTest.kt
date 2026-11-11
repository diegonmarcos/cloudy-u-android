package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The download path end to end up to the session seam: a search hit (indexer body -> TorrentHit) -> magnet / fetched .torrent ->
 * TorrentEngine -> the session ([FakeBackend] standing in for libtorrent) with the folder Downloads/Cloudy-store/Torrent. This
 * proves the WIRING (what the session is asked, in what order, with which folder, and what survives a restart). It cannot prove
 * that libtorrent finds peers or that bytes arrive: that is the on-phone check (add a real magnet, confirm the files land).
 */
class TorrentDownloadPathTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var downloads: File
    private lateinit var keep: File
    private lateinit var settings: MemorySettings
    private lateinit var store: MemoryStore
    private var now = 5_000L
    private val h = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    @Before fun setUp() {
        downloads = tmp.newFolder("Download"); keep = tmp.newFolder("keep")
        settings = MemorySettings(TorrentPaths.defaultDir(downloads)); store = MemoryStore()
    }

    private fun engine(b: FakeBackend) = TorrentEngine(b, store, settings, keep, { now++ })
    private val defaultDir get() = File(downloads, "Cloudy-store/Torrent")

    @Test fun aMagnetHitReachesTheSessionWithTheDefaultFolderCreatedAndStartedRunning() {
        val body = """[{"id":"1","name":"Debian 12 netinst","info_hash":"${h.uppercase()}","leechers":"2","seeders":"40","size":"400000000","added":"1700000000","category":"207"}]"""
        val hit = TorrentSearch.search(TorrentIndexers.ApiBay, "debian") { body.toByteArray() }.single()
        val b = FakeBackend(startsPaused = true)
        val e = engine(b)
        val r = e.addMagnet(hit.magnetUri()!!)
        assertEquals(h, r.idOrNull)
        val seen = b.adds.single()
        assertEquals(h, seen.id); assertEquals(defaultDir.path, seen.dir.path)
        assertTrue("the folder exists and is writable BEFORE libtorrent is asked to write into it", seen.isDir && seen.writable)
        assertEquals("hash-only results get public trackers", h, Magnet.infoHash(seen.magnet!!))
        assertTrue(seen.magnet!!.contains("tr="))
        val i = b.calls.indexOfFirst { it.startsWith("addMagnet:") }
        assertTrue("the session is told to run it right after the add (jlibtorrent adds paused)", b.calls.drop(i).contains("resume:$h"))
        // libtorrent then writes under that folder; the finished torrent is reported at the same path
        b.progress(h, "Debian 12 netinst", Phase.DOWNLOADING, 0.5f, 400, down = 50, seeds = 5, peers = 9)
        val landed = b.land(h, "debian-12-netinst.iso", 16)
        assertEquals(defaultDir.path, landed.parentFile!!.path)
        assertTrue(landed.isFile)
        assertEquals(defaultDir.path, e.refresh().single().savePath)
    }

    @Test fun aTorrentUrlHitIsFetchedKeptAndHandedToTheSession() {
        val bytes = TorrentFixtures.torrent("bbb.mp4", 7_000)
        val body = """{"response":{"docs":[{"identifier":"bbb","title":"Big Buck Bunny","item_size":7000,"publicdate":"2011-02-03T04:05:06Z","mediatype":"movies"}]}}"""
        val hit = TorrentSearch.search(TorrentIndexers.InternetArchive, "bbb") { body.toByteArray() }.single()
        var fetched = ""
        val got = TorrentSearch.torrentBytes(hit) { fetched = it; bytes }
        assertEquals("https://archive.org/download/bbb/bbb_archive.torrent", fetched)
        val b = FakeBackend()
        val e = engine(b)
        val id = e.addTorrentBytes(hit.title + ".torrent", got).idOrNull
        assertEquals(TorrentFixtures.infoHashOf(bytes), id)
        val seen = b.adds.single()
        assertEquals(defaultDir.path, seen.dir.path); assertTrue(seen.isDir && seen.writable)
        assertTrue("the session reads the kept copy, which is the fetched bytes", seen.file!!.isFile && seen.file!!.parentFile == keep)
        assertArrayEquals(bytes, seen.file!!.readBytes())
        // a restart re-adds from the kept copy and the entry still points at the same folder
        val b2 = FakeBackend(startsPaused = true)
        engine(b2).start()
        assertEquals(seen.file, b2.adds.single().file); assertEquals(defaultDir.path, b2.adds.single().dir.path)
        assertTrue(b2.calls.contains("resume:$id"))
    }

    @Test fun aBadOrMissingTorrentBodyNeverReachesTheSession() {
        val b = FakeBackend()
        val e = engine(b)
        assertTrue(e.addTorrentBytes("x.torrent", "<html>not found</html>".toByteArray()) is AddResult.Invalid)
        assertTrue(e.addTorrentBytes("empty.torrent", ByteArray(0)) is AddResult.Invalid)
        assertTrue(b.adds.isEmpty()); assertTrue(keep.listFiles().isNullOrEmpty())
        assertTrue(e.refresh().isEmpty())
    }

    @Test fun aTorrentKeepsTheFolderItWasAddedWithWhenTheSettingChangesLater() {
        val b = FakeBackend()
        engine(b).addMagnet("magnet:?xt=urn:btih:$h")
        val drive = tmp.newFolder("drive")
        settings.saveDir = File(drive, "Torrents")
        val b2 = FakeBackend()
        val e2 = engine(b2); e2.start()
        assertEquals("a restart does not move a running download", defaultDir.path, b2.adds.single().dir.path)
        // a new add uses the new folder, created on demand
        e2.addMagnet("magnet:?xt=urn:btih:${"ab".repeat(20)}")
        assertEquals(File(drive, "Torrents").path, b2.adds.last().dir.path); assertTrue(b2.adds.last().isDir)
    }

    @Test fun aFinishedMagnetTorrentKeepsItsSizeAcrossARestartEvenThoughItIsReAddedWithoutMetadata() {
        settings.seedAfterComplete = false
        val b = FakeBackend()
        val e = engine(b)
        e.addMagnet("magnet:?xt=urn:btih:$h&dn=ISO")
        b.progress(h, "iso", Phase.DOWNLOADING, 0.5f, 4_000)
        e.refresh()
        b.progress(h, "iso", Phase.FINISHED, 1f, 4_000)
        assertEquals(TorrentState.COMPLETED, e.refresh().single().state)
        // after a restart libtorrent holds the magnet paused with no metadata: total 0
        val b2 = FakeBackend()
        val e2 = engine(b2); e2.start()
        b2.raw[h] = b2.raw.getValue(h).copy(totalBytes = 0)
        val s = e2.refresh().single()
        assertEquals(TorrentState.COMPLETED, s.state)
        assertEquals(4_000L, s.totalBytes); assertEquals(4_000L, s.doneBytes); assertEquals(1f, s.progress, 0f)
        assertTrue("a finished torrent that is not seeding is re-added paused", b2.calls.contains("addMagnet:$h:${defaultDir.path}:true"))
        assertFalse(b2.calls.contains("resume:$h"))
    }

    @Test fun anOldListWithoutASizeStillLoads() {
        val f = File(tmp.newFolder("st"), "torrents.json")
        f.writeText("""[{"id":"$h","name":"n","magnet":"magnet:?xt=urn:btih:$h","file":"","save":"/x","added":1,"paused":false,"seed":true,"completed":true}]""")
        val e = FileTorrentStore(f).load().single()
        assertEquals(0L, e.totalBytes); assertTrue(e.completed)
        FileTorrentStore(f).save(listOf(e.copy(totalBytes = 99)))
        assertEquals(99L, FileTorrentStore(f).load().single().totalBytes)
    }
}
