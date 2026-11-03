package com.diegonmarcos.superapp.appstore.ventoy

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1405 the only network the Ventoy tab does: GETs of official feeds and downloads, HTTPS only, following redirects,
 * with the platform's own User-Agent (none is set here, so nothing is spoofed). A download is hashed as it streams
 * onto the drive (VentoyDrive.install); nothing is trusted before its sha256 matches the official one.
 */
object VentoyNet {
    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        require(VentoyCatalogue.isOfficialUrl(url)) { "https only: $url" }
        var u = URL(url)
        repeat(8) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location: $u")
                c.disconnect()
                u = URL(u, loc)
                if (u.protocol != "https") throw IOException("redirect left https: $u")
                return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code for $u") }
            return c
        }
        throw IOException("too many redirects: $url")
    }

    fun text(url: String): String = open(url).let { c -> try { c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() } }

    /** Content-Length of [url] after redirects, or -1. */
    fun size(url: String): Long = runCatching { open(url, "HEAD").let { c -> try { c.contentLengthLong } finally { c.disconnect() } } }.getOrDefault(-1)

    fun <T> stream(url: String, body: (InputStream, Long) -> T): T = open(url).let { c ->
        try { c.inputStream.use { body(it, c.contentLengthLong) } } finally { c.disconnect() }
    }

    /** Resolves a catalogue [src] against its official feed. */
    fun resolve(src: VentoyCatalogue.Source): VentoyCatalogue.Resolved? = when (src.type) {
        "sums" -> {
            val dir = src.index.takeIf { it.isNotEmpty() }?.let { VentoyCatalogue.newestDir(text(it)) ?: throw IOException("no version under ${src.index}") }
            val s = if (dir == null) src else src.copy(feed = src.feed.replace("{dir}", dir), base = src.base.replace("{dir}", dir), download = src.download.replace("{dir}", dir))
            VentoyCatalogue.fromSums(text(s.feed), s)
        }
        "json" -> VentoyCatalogue.fromJson(text(src.feed), src)
        "github" -> {
            val rel = text(VentoyCatalogue.githubApi(src.feed))
            val sums = src.sumsAsset.takeIf { it.isNotEmpty() }?.let { re ->
                VentoyCatalogue.githubAssets(rel).second.firstOrNull { Regex(re).matches(it.name) }?.let { text(it.url) }
            }
            VentoyCatalogue.fromGithub(rel, src.asset, sums)
        }
        else -> null
    }?.let { r -> if (r.size > 0) r else r.copy(size = size(r.url)) }
}
