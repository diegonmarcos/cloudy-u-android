package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The engine API against a fake session: add / progress / pause / resume / remove / seed / save path / degraded states. No peers, no native library. */
class TorrentEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var downloads: File
    private lateinit var settings: MemorySettings
    private lateinit var backend: FakeBackend
    private lateinit var store: MemoryStore
    private var now = 1_000L

    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Some+Linux+ISO"

    @Before fun setUp() {
        downloads = tmp.newFolder("Download")
        settings = MemorySettings(TorrentPaths.defaultDir(downloads))
        backend = FakeBackend()
        store = MemoryStore()
    }

    private fun engine(b: TorrentBackend = backend) = TorrentEngine(b, store, settings, tmp.newFolder(), { now++ })

    @Test fun defaultSaveFolderIsDownloadsCloudyStoreTorrentAndIsCreated() {
        val e = engine()
        val r = e.addMagnet(magnet)
        assertTrue(r is AddResult.Added)
        val dir = File(downloads, "Cloudy-store/Torrent")
        assertTrue("the default folder is created on the first add", dir.isDirectory)
        assertEquals(dir.path, e.refresh().single().savePath)
        assertTrue(backend.calls.any { it == "addMagnet:$hash:${dir.path}:false" })
    }

    @Test fun addMagnetReportsMetadataThenProgress() {
        val e = engine()
        assertEquals(hash, e.addMagnet(magnet).idOrNull)
        var s = e.refresh().single()
        assertEquals(TorrentState.METADATA, s.state)
        assertEquals("Some Linux ISO", s.name)
        backend.progress(hash, "debian.iso", Phase.DOWNLOADING, 0.25f, 4_000, down = 500, seeds = 3, peers = 7)
        s = e.refresh().single()
        assertEquals(TorrentState.DOWNLOADING, s.state)
        assertEquals(0.25f, s.progress, 0.0001f)
        assertEquals(4_000L, s.totalBytes); assertEquals(1_000L, s.doneBytes)
        assertEquals(500L, s.downRate); assertEquals(3, s.seeds); assertEquals(7, s.peers)
        assertEquals(6L, s.etaSeconds)
        assertEquals("the resolved name replaces the magnet's placeholder", "debian.iso", s.name)
    }

    @Test fun duplicateAndInvalidInputsAreAnswersNotCrashes() {
        val e = engine()
        e.addMagnet(magnet)
        assertTrue(e.addMagnet(magnet.replace("0123", "0123")) is AddResult.Duplicate)
        assertTrue(e.addMagnet("https://example.org/not-a-magnet") is AddResult.Invalid)
        assertTrue(e.addMagnet("magnet:?dn=no-hash") is AddResult.Invalid)
        assertTrue(e.addTorrentBytes("junk.torrent", "not bencode".toByteArray()) is AddResult.Invalid)
        assertEquals(1, e.refresh().size)
    }

    @Test fun addTorrentFileKeepsACopyAndUsesItsInfoHash() {
        val bytes = TorrentFixtures.torrent("ubuntu.iso", 5_000)
        val f = tmp.newFile("ubuntu.torrent").also { it.writeBytes(bytes) }
        val e = engine()
        val id = e.addTorrentFile(f).idOrNull
        assertEquals(TorrentFixtures.infoHashOf(bytes), id)
        assertEquals("ubuntu.iso", e.refresh().single().name)
        assertTrue(backend.calls.any { it.startsWith("addTorrent:$id:") })
        assertTrue(e.addTorrentFile(f) is AddResult.Duplicate)
    }

    @Test fun pauseResumeRemove() {
        val e = engine()
        e.addMagnet(magnet)
        backend.progress(hash, "x", Phase.DOWNLOADING, 0.5f, 100, down = 10, seeds = 1, peers = 2)
        assertTrue(e.pause(hash))
        var s = e.refresh().single()
        assertEquals(TorrentState.PAUSED, s.state)
        assertEquals("a paused torrent reports no live rate or peers", 0L, s.downRate)
        assertTrue(e.resume(hash))
        assertEquals(TorrentState.DOWNLOADING, e.refresh().single().state)
        assertTrue(e.remove(hash, deleteFiles = true))
        assertTrue(e.refresh().isEmpty())
        assertTrue(backend.calls.contains("remove:$hash:true"))
        assertFalse(e.pause(hash)); assertFalse(e.remove(hash))
    }

    @Test fun finishedTorrentSeedsWhenSeedingIsOn() {
        val e = engine()
        e.addMagnet(magnet)
        backend.progress(hash, "x", Phase.SEEDING, 1f, 100, up = 20, uploaded = 40, downloaded = 100)
        val s = e.refresh().single()
        assertEquals(TorrentState.SEEDING, s.state)
        assertTrue(s.finished); assertEquals(1f, s.progress, 0f)
        assertEquals(0.4f, s.ratio, 0.0001f)
        assertFalse("seeding torrents are left running", backend.calls.contains("pause:$hash"))
    }

    @Test fun seedingOffStopsAFinishedTorrentAndLeavesItCompleted() {
        settings.seedAfterComplete = false
        val e = engine()
        e.addMagnet(magnet)
        backend.progress(hash, "x", Phase.FINISHED, 1f, 100)
        val s = e.refresh().single()
        assertEquals(TorrentState.COMPLETED, s.state)
        assertFalse(s.seedEnabled)
        assertTrue(backend.calls.contains("pause:$hash"))
        // seed control per torrent turns it back on
        assertTrue(e.setSeeding(hash, true))
        assertTrue(backend.calls.contains("resume:$hash"))
        assertEquals(TorrentState.SEEDING, e.refresh().single().state)
    }

    @Test fun ratioLimitStopsSeeding() {
        settings.seedRatioLimit = 2f
        val e = engine()
        e.addMagnet(magnet)
        backend.progress(hash, "x", Phase.SEEDING, 1f, 100, uploaded = 150, downloaded = 100)
        assertEquals(TorrentState.SEEDING, e.refresh().single().state)
        backend.progress(hash, "x", Phase.SEEDING, 1f, 100, uploaded = 220, downloaded = 100)
        assertEquals(TorrentState.COMPLETED, e.refresh().single().state)
        assertTrue(backend.calls.contains("pause:$hash"))
    }

    @Test fun listSurvivesARestartAndIsReAddedToTheSession() {
        val first = engine()
        first.addMagnet(magnet)
        first.pause(hash)
        val b2 = FakeBackend()
        val second = engine(b2)
        assertEquals("the list is loaded before the session starts", 1, second.refresh().size)
        second.start()
        assertTrue("re-added paused, as it was left", b2.calls.any { it.startsWith("addMagnet:$hash:") && it.endsWith(":true") })
        assertEquals(TorrentState.PAUSED, second.refresh().single().state)
    }

    @Test fun customSaveFolderPerAddAndInSettings() {
        val drive = tmp.newFolder("drive")
        settings.saveDir = File(drive, "Torrents")
        val e = engine()
        e.addMagnet(magnet)
        assertTrue(settings.saveDirIsCustom)
        assertEquals(File(drive, "Torrents").path, e.refresh().single().savePath)
        val other = "magnet:?xt=urn:btih:${"ab".repeat(20)}"
        e.addMagnet(other, saveDir = File(drive, "Elsewhere"))
        assertEquals(File(drive, "Elsewhere").path, e.refresh().last().savePath)
        settings.resetSaveDir()
        assertFalse(settings.saveDirIsCustom)
    }

    @Test fun unwritableFolderIsAFailedAddWithAReason() {
        val blocker = tmp.newFile("file-not-dir")
        settings.saveDir = File(blocker, "sub")
        val r = engine().addMagnet(magnet)
        assertTrue(r is AddResult.Failed)
        assertTrue((r as AddResult.Failed).reason.contains("Cannot create"))
        assertTrue("nothing was handed to the session", backend.calls.none { it.startsWith("addMagnet") })
    }

    @Test fun missingNativeLibraryIsAStateNotACrash() {
        val dead = FakeBackend(failStart = UnsatisfiedLinkError("no jlibtorrent for this ABI"))
        val e = engine(dead)
        val r = e.addMagnet(magnet)
        assertTrue(r is AddResult.Unavailable)
        assertTrue((r as AddResult.Unavailable).reason.contains("no jlibtorrent"))
        assertTrue(e.state is EngineState.Unavailable)
        assertTrue(e.refresh().isEmpty())
        e.stop()
    }

    @Test fun existingListIsShownWithTheReasonWhenTheSessionIsUnavailable() {
        engine().addMagnet(magnet)
        val e = engine(FakeBackend(failStart = IllegalStateException("boom")))
        e.start()
        val s = e.refresh().single()
        assertEquals(TorrentState.ERROR, s.state)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("boom"))
    }

    @Test fun sessionAddFailureIsReportedAndNotRemembered() {
        backend.failAdd = IllegalStateException("disk full")
        val e = engine()
        val r = e.addMagnet(magnet)
        assertTrue(r is AddResult.Failed)
        assertNull(store.load().firstOrNull())
    }

    @Test fun aFreshlyAddedTorrentLeavesPausedByItselfOnASessionThatAddsPaused() {
        val b = FakeBackend(startsPaused = true)
        val e = TorrentEngine(b, store, settings, tmp.newFolder(), { now++ })
        e.addMagnet(magnet)
        assertTrue("the engine started it, no manual Resume", b.calls.contains("resume:$hash"))
        b.progress(hash, "x", Phase.DOWNLOADING, 0.1f, 100, down = 5, seeds = 1, peers = 1)
        assertEquals(TorrentState.DOWNLOADING, e.refresh().single().state)
        // a torrent added paused on purpose stays paused
        val other = "magnet:?xt=urn:btih:${"cd".repeat(20)}"
        e.addMagnet(other, startPaused = true)
        assertFalse(b.calls.contains("resume:${"cd".repeat(20)}"))
        assertEquals(TorrentState.PAUSED, e.refresh().last().state)
        // and a restart re-adds a running torrent running
        val b2 = FakeBackend(startsPaused = true)
        val e2 = TorrentEngine(b2, store, settings, tmp.newFolder(), { now++ })
        e2.start()
        assertTrue(b2.calls.contains("resume:$hash"))
    }

    @Test fun aFinishedTorrentKeepsItsSizeWhenTheSessionReportsZero() {
        val e = engine()
        e.addMagnet(magnet)
        backend.progress(hash, "x", Phase.DOWNLOADING, 0.5f, 4_000)
        e.refresh()
        backend.progress(hash, "x", Phase.SEEDING, 1f, 0)
        val s = e.refresh().single()
        assertEquals(4_000L, s.totalBytes); assertEquals(4_000L, s.doneBytes); assertTrue(s.finished)
    }
}
