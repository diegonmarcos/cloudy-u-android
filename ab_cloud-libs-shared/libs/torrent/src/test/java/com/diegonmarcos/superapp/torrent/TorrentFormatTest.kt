package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentFormatTest {
    @Test fun numbers() {
        assertEquals("512 B", TorrentFormat.bytes(512)); assertEquals("1.5 KB", TorrentFormat.bytes(1536)); assertEquals("?", TorrentFormat.bytes(-1))
        assertEquals("2.0 MB/s", TorrentFormat.rate(2L * 1024 * 1024)); assertEquals("25.0%", TorrentFormat.percent(0.25f))
        assertEquals("unknown", TorrentFormat.eta(-1)); assertEquals("1m 15s", TorrentFormat.eta(75)); assertEquals("1h 2m", TorrentFormat.eta(3725))
    }

    @Test fun rowLineCarriesStateProgressRatesSeedsPeersAndEta() {
        val t = TorrentStatus("id", "n", TorrentState.DOWNLOADING, 0.5f, 2000, 1000, 100, 20, 4, 9, 0, 1000, "/d", null, true, 0, null)
        val l = TorrentFormat.line(t)
        listOf("downloading", "50.0% of 2.0 KB", "↓ 100 B/s", "ETA 10s", "↑ 20 B/s", "4 seeds", "9 peers").forEach { assertTrue("$l has $it", l.contains(it)) }
    }

    @Test fun addSentences() {
        assertEquals("Already in Torrent downloads.", AddResult.Duplicate("x").sentence())
        assertTrue(AddResult.Failed("disk full").sentence().contains("disk full"))
    }
}
