package com.diegonmarcos.superapp.cad

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Download once, verify, reuse (#1404). Blocking: call off the main thread. Files are named by sha256, so a changed model is a new file. */
object ModelCache {
    const val MANIFEST = "models.json"

    fun cachedName(sha256: String, format: String): String = "$sha256.$format"

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The manifest text: fresh from [base] when reachable (and cached), else the cached copy, else null. */
    fun manifest(base: String, dir: File): String? {
        dir.mkdirs(); val f = File(dir, MANIFEST)
        return runCatching { open(base + MANIFEST).inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) }
            .onSuccess { f.writeText(it) }.getOrNull() ?: f.takeIf { it.isFile }?.readText()
    }

    /** [path] of the models repo as a verified file in [dir]. Throws when the download does not match [sha256]. */
    fun fetch(base: String, dir: File, path: String, format: String, sha256: String): File {
        dir.mkdirs(); val f = File(dir, cachedName(sha256, format))
        if (f.isFile && sha256(f) == sha256) return f
        val tmp = File(dir, f.name + ".part")
        open(base + path).inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        val got = sha256(tmp)
        if (got != sha256) { tmp.delete(); throw IllegalStateException("sha256 mismatch for $path") }
        if (!tmp.renameTo(f)) throw IllegalStateException("cannot store $path")
        return f
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode " + url.substringAfter("//").substringBefore("?"))
    }
}
