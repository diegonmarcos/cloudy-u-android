package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The background-service run condition and notification numbers: active downloads only, never paused / finished / seeding. */
class TorrentActivityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h1 = "0123456789abcdef0123456789abcdef01234567"
    private val h2 = "1123456789abcdef0123456789abcdef01234567"
    private fun magnet(h: String) = "magnet:?xt=urn:btih:$h&dn=x"

    private fun engine(b: FakeBackend): TorrentEngine =
        TorrentEngine(b, MemoryStore(), MemorySettings(File(tmp.newFolder("d"), "Torrent")), tmp.newFolder("k"))

    @Test fun idleEngineDoesNotKeepTheServiceAlive() {
        val a = engine(FakeBackend()).activity()
        assertFalse(a.keepAlive); assertEquals(0, a.downloading)
    }

    @Test fun activeAddKeepsAliveAndAggregatesProgressRatePeers() {
        val b = FakeBackend(); val e = engine(b)
        e.addMagnet(magnet(h1)); e.addMagnet(magnet(h2))
        b.progress(h1, "a", Phase.DOWNLOADING, 0.5f, 1000, down = 100, peers = 3)
        b.progress(h2, "b", Phase.DOWNLOADING, 0.0f, 1000, down = 300, peers = 4)
        val a = e.activity()
        assertTrue(a.keepAlive); assertEquals(2, a.downloading); assertEquals(2, a.transferring)
        assertEquals(0.25f, a.progress, 0.001f); assertEquals(400L, a.downRate); assertEquals(7, a.peers)
        assertEquals("2 downloading · 25.0% · ↓ 400 B/s · 7 peers", a.text)
    }

    @Test fun pausedAllFinishedAndRemovedStopTheService() {
        val b = FakeBackend(); val e = engine(b)
        e.addMagnet(magnet(h1)); e.addMagnet(magnet(h2))
        b.progress(h1, "a", Phase.DOWNLOADING, 0.5f, 1000, down = 100)
        b.progress(h2, "b", Phase.DOWNLOADING, 0.5f, 1000, down = 100)
        assertTrue(e.activity().keepAlive)
        e.pauseAll()
        assertFalse("all paused", e.activity().keepAlive)
        e.resume(h1); e.resume(h2)
        b.progress(h1, "a", Phase.DOWNLOADING, 0.5f, 1000, down = 100)
        b.progress(h2, "b", Phase.DOWNLOADING, 0.5f, 1000, down = 100)
        b.progress(h1, "a", Phase.FINISHED, 1f, 1000)
        e.remove(h2)
        assertFalse("one finished, one removed", e.activity().keepAlive)
    }

    @Test fun onlySeedingIsNotADownload() {
        val b = FakeBackend(); val e = engine(b)
        e.addMagnet(magnet(h1))
        b.progress(h1, "a", Phase.SEEDING, 1f, 1000, up = 50)
        assertFalse(e.activity().keepAlive)
    }

    @Test fun downloadStartedHookFiresOnActiveAddAndResumeNotOnPausedAdd() {
        val b = FakeBackend(); val e = engine(b)
        var n = 0
        e.onDownloadStarted = { n++ }
        e.addMagnet(magnet(h1), startPaused = true)
        assertEquals(0, n)
        e.resume(h1)
        assertEquals(1, n)
        e.addMagnet(magnet(h2))
        assertEquals(2, n)
        e.onDownloadStarted = { error("a failing host must not break the engine") }
        e.pause(h1); assertTrue(e.resume(h1))
    }
}
