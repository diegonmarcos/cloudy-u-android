package com.diegonmarcos.superapp.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MagnetTest {
    private val hex = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    @Test fun hexInfoHash() {
        assertEquals(hex, Magnet.infoHash("magnet:?xt=urn:btih:${hex.uppercase()}&dn=x"))
        assertEquals(hex, Magnet.infoHash("  magnet:?dn=x&xt=urn:btih:$hex&tr=udp%3A%2F%2Ft"))
    }

    @Test fun base32InfoHash() {
        // 20 bytes 0x00..0x13 in base32 is AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT (RFC 4648)
        assertEquals("000102030405060708090a0b0c0d0e0f10111213", Magnet.infoHash("magnet:?xt=urn:btih:AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT"))
    }

    @Test fun notAMagnet() {
        assertNull(Magnet.infoHash("https://example.org/?xt=urn:btih:$hex"))
        assertNull(Magnet.infoHash("magnet:?xt=urn:btih:tooshort"))
        assertNull(Magnet.infoHash("magnet:?xt=urn:sha1:$hex"))
        assertNull(Magnet.infoHash("magnet:?xt=urn:btih:${"z".repeat(40)}"))
    }

    @Test fun displayNameAndBuild() {
        assertEquals("My File.iso", Magnet.displayName("magnet:?xt=urn:btih:$hex&dn=My+File.iso"))
        val m = Magnet.build(hex, "A B", listOf("udp://t:1/a"))
        assertEquals(hex, Magnet.infoHash(m))
        assertEquals("A B", Magnet.displayName(m))
    }

    @Test fun torrentFileInfoHashNameAndSize() {
        val bytes = TorrentFixtures.torrent("ubuntu.iso", 5_000)
        val meta = Magnet.parseTorrent(bytes)
        assertNotNull(meta)
        assertEquals(TorrentFixtures.infoHashOf(bytes), meta!!.infoHash)
        assertEquals("ubuntu.iso", meta.name)
        assertEquals(5_000L, meta.totalBytes)
        assertNull(Magnet.parseTorrent("hello".toByteArray()))
        assertNull(Magnet.parseTorrent(ByteArray(0)))
        assertNull(Magnet.parseTorrent("d3:fooi1ee".toByteArray()))
    }
}
