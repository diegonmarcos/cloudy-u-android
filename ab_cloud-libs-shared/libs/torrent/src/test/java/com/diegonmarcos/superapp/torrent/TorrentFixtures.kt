package com.diegonmarcos.superapp.torrent

import java.security.MessageDigest

/** A minimal single-file .torrent built in memory (bencode by hand), so no binary fixture is committed. */
object TorrentFixtures {
    fun torrent(name: String, length: Long): ByteArray {
        val info = "d6:lengthi${length}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:AAAAAAAAAAAAAAAAAAAAe"
        val tracker = "http://tracker.test/a"
        return "d8:announce${tracker.length}:${tracker}4:info${info}e".toByteArray(Charsets.ISO_8859_1)
    }

    /** SHA-1 of the info dictionary, computed independently of the code under test: the bytes after "4:info" up to the outer "e". */
    fun infoHashOf(torrent: ByteArray): String {
        val s = String(torrent, Charsets.ISO_8859_1)
        val start = s.indexOf("4:info") + 6
        return MessageDigest.getInstance("SHA-1").digest(s.substring(start, s.length - 1).toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
    }
}
