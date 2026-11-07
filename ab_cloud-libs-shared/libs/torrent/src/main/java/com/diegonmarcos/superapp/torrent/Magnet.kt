package com.diegonmarcos.superapp.torrent

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** Magnet links and the .torrent file: the info-hash (the torrent's id) and the display name, with no native library. Pure JVM. */
object Magnet {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** The 40-char lowercase hex info-hash of a magnet uri (hex or base32 `xt=urn:btih:`), or null when it is not a magnet of one. */
    fun infoHash(uri: String): String? {
        val u = uri.trim()
        if (!u.startsWith("magnet:?", ignoreCase = true)) return null
        val xt = u.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first.equals("xt", true) && it.second.lowercase().startsWith("urn:btih:") }?.second ?: return null
        val h = xt.substring("urn:btih:".length)
        return when {
            h.length == 40 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> h.lowercase()
            h.length == 32 -> base32(h.uppercase())
            else -> null
        }
    }

    private fun base32(s: String): String? {
        if (s.any { it !in B32 }) return null
        var bits = 0; var value = 0
        val out = StringBuilder()
        for (c in s) {
            value = (value shl 5) or B32.indexOf(c); bits += 5
            if (bits >= 8) { bits -= 8; out.append("%02x".format((value shr bits) and 0xff)) }
        }
        return out.toString().takeIf { it.length == 40 }
    }

    /** The `dn=` display name of a magnet uri, or null. */
    fun displayName(uri: String): String? =
        uri.substringAfter('?', "").split('&').firstOrNull { it.startsWith("dn=", true) }?.substring(3)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { it.isNotBlank() }

    /** A magnet uri for [hash] with [name] and the given trackers. */
    fun build(hash: String, name: String?, trackers: List<String> = emptyList()): String =
        "magnet:?xt=urn:btih:${hash.lowercase()}" +
            (name?.takeIf { it.isNotBlank() }?.let { "&dn=" + URLEncoder.encode(it, "UTF-8") } ?: "") +
            trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }

    /** The public trackers a hash-only result (an indexer that returns no magnet) is announced to. */
    val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
    )

    /** A .torrent file read down to what the engine needs. */
    class MetaInfo(val infoHash: String, val name: String, val totalBytes: Long)

    /** Reads a bencoded .torrent: the info-hash is the SHA-1 of the `info` dictionary's exact bytes. Null when it is not a torrent file. */
    fun parseTorrent(bytes: ByteArray): MetaInfo? = runCatching {
        val r = Bencode(bytes)
        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) return null
        var pos = 1
        var info: MetaInfo? = null
        while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) {
            val (key, afterKey) = r.string(pos); pos = afterKey
            val start = pos
            val end = r.skip(pos)
            if (String(key, Charsets.ISO_8859_1) == "info") {
                val digest = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(start, end))
                val d = r.dict(start)
                val name = (d["name.utf-8"] ?: d["name"])?.let { String(it as ByteArray, Charsets.UTF_8) } ?: ""
                var total = (d["length"] as? Long) ?: 0L
                @Suppress("UNCHECKED_CAST")
                (d["files"] as? List<Map<String, Any?>>)?.let { fs -> total = fs.sumOf { (it["length"] as? Long) ?: 0L } }
                info = MetaInfo(digest.joinToString("") { "%02x".format(it) }, name, total)
            }
            pos = end
        }
        info
    }.getOrNull()

    /** A small bencode reader: [skip] finds where a value ends, [dict] reads one level (dicts and lists nest, strings are ByteArray, ints Long). */
    private class Bencode(val b: ByteArray) {
        fun string(p: Int): Pair<ByteArray, Int> {
            var i = p; var n = 0
            while (b[i] != ':'.code.toByte()) { require(b[i] in '0'.code.toByte()..'9'.code.toByte()); n = n * 10 + (b[i] - '0'.code.toByte()); i++ }
            i++
            return b.copyOfRange(i, i + n) to i + n
        }
        fun skip(p: Int): Int = when (b[p].toInt().toChar()) {
            'i' -> { var i = p + 1; while (b[i] != 'e'.code.toByte()) i++; i + 1 }
            'l', 'd' -> { var i = p + 1; while (b[i] != 'e'.code.toByte()) i = skip(i); i + 1 }
            else -> string(p).second
        }
        fun value(p: Int): Pair<Any?, Int> = when (b[p].toInt().toChar()) {
            'i' -> { val e = skip(p); String(b, p + 1, e - p - 2).toLong() to e }
            'l' -> { var i = p + 1; val out = ArrayList<Any?>(); while (b[i] != 'e'.code.toByte()) { val (v, n) = value(i); out += v; i = n }; out to i + 1 }
            'd' -> { dictAt(p) }
            else -> string(p)
        }
        fun dictAt(p: Int): Pair<Any?, Int> {
            var i = p + 1; val m = LinkedHashMap<String, Any?>()
            while (b[i] != 'e'.code.toByte()) {
                val (k, n) = string(i); val (v, n2) = value(n)
                m[String(k, Charsets.ISO_8859_1)] = v; i = n2
            }
            return m to i + 1
        }
        @Suppress("UNCHECKED_CAST")
        fun dict(p: Int): Map<String, Any?> = dictAt(p).first as Map<String, Any?>
    }
}
