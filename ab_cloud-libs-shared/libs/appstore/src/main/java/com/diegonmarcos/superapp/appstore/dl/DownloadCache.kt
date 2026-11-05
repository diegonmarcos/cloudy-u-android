package com.diegonmarcos.superapp.appstore.dl

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * #1517 CONTENT-ADDRESSED CACHE. A finished APK is named `<prefix><pkg>-<versionCode>-<sha256>.apk`: the key is package +
 * versionCode + the digest of the bytes, so the same build is never fetched twice and a different build can never be mistaken
 * for it. `.part` files are the engine's in-flight state and are never a cache entry: [prune] cannot see them as candidates and
 * refuses anything in [protect].
 */
class DownloadCache(val dir: File) {
    fun finalName(prefix: String, pkg: String, versionCode: Long, sha256: String) = "$prefix$pkg-${vc(versionCode)}-$sha256.apk"

    fun partFile(prefix: String) = File(dir, "${prefix}dl.apk.part")

    fun finalFile(prefix: String, pkg: String, versionCode: Long, sha256: String) = File(dir, finalName(prefix, pkg, versionCode, sha256))

    /** A completed file for this key whose bytes really hash to [sha256], or null. A hit is touched: it is the LRU's "used". */
    fun hit(prefix: String, pkg: String, versionCode: Long, sha256: String, now: Long = System.currentTimeMillis()): File? {
        val f = finalFile(prefix, pkg, versionCode, sha256)
        if (!f.isFile || !sha256Of(f).equals(sha256, ignoreCase = true)) return null
        f.setLastModified(now)
        return f
    }

    class Pruned(val deleted: List<String>, val freedBytes: Long)

    /**
     * Least-recently-used first: drop completed `.apk` files older than [maxAgeMs], then the oldest until the cache fits [maxBytes].
     * [protect] (the finished files of live jobs, and whatever the host says is still pending an install) is never touched, and
     * neither is any `.part`.
     */
    fun prune(maxBytes: Long, maxAgeMs: Long, protect: (File) -> Boolean, now: Long = System.currentTimeMillis(), remove: (File) -> Unit = { it.delete() }): Pruned {
        val all = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".apk") && !it.name.endsWith(".part") }
        val deleted = ArrayList<String>()
        var freed = 0L
        var total = all.sumOf { it.length() }
        fun drop(f: File) { val n = f.length(); remove(f); if (!f.exists()) { deleted += f.name; freed += n; total -= n } }
        val candidates = all.filter { !protect(it) }.sortedBy { it.lastModified() }
        for (f in candidates) if (maxAgeMs > 0 && now - f.lastModified() > maxAgeMs) drop(f)
        for (f in candidates) { if (total <= maxBytes) break; if (f.exists()) drop(f) }
        return Pruned(deleted, freed)
    }

    companion object {
        fun vc(versionCode: Long) = if (versionCode < 0) "x" else versionCode.toString()

        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
