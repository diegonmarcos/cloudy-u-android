package com.diegonmarcos.capitalfinterm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** A blocking GET that answers the body text or throws IOException. The app's one network seam; the tests give it a fake. */
interface Http {
    @Throws(IOException::class)
    fun get(url: String): String

    /** A bodiless POST (the container actions); the answer's text. */
    @Throws(IOException::class)
    fun post(url: String): String

    /** #1416 a request with its own headers (a declared User-Agent, a key header) and an optional JSON body (POST when present). */
    @Throws(IOException::class)
    fun send(url: String, headers: Map<String, String>, body: String?): String = if (body == null) get(url) else throw IOException("no body support")
}

/**
 * The real GET: HttpURLConnection, so names are resolved by Android's own resolver (the SuperApp's Configs > Mesh > DNS choice applies,
 * no resolver of our own), https only, bounded size and time. It carries no credential and logs nothing.
 */
class UrlHttp(
    private val connectMs: Int = 10_000,
    private val readMs: Int = 25_000,
    private val maxBytes: Int = 4_000_000,
    /** Hosts allowed over plain http: the private mesh names (WireGuard encrypts the hop), taken from the baked fleet declaration. */
    private val cleartextHosts: Set<String> = emptySet(),
) : Http {
    override fun get(url: String): String = call("GET", url)

    override fun post(url: String): String = call("POST", url)

    override fun send(url: String, headers: Map<String, String>, body: String?): String = call(if (body == null) "GET" else "POST", url, headers, body)

    private fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): String {
        val u = URL(url)
        val plain = u.protocol == "http" && u.host in cleartextHosts
        if (u.protocol != "https" && !plain) throw IOException("https only")
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "application/json, text/csv, application/xml, */*")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (method == "POST") {
                c.doOutput = true
                c.outputStream.use { it.write((body ?: "").toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("answer too large")
                }
                return out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val AGENT = "Capital-Stack-FinTerminal"
    }
}

/**
 * Remembers an answer for [ttlMs], or for [ttlFor] of its address when that names a longer one (a source's declared schedule.ttl_s), so two panels
 * reading the same series (or a re-opened dashboard) cost one request and a rate-limited source is never asked more often than declared.
 */
class CachedHttp(
    private val inner: Http,
    private val ttlMs: Long = 60_000L,
    @Volatile var ttlFor: (String) -> Long? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) : Http {
    private val seen = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun ttl(url: String): Long = maxOf(ttlMs, ttlFor(url) ?: 0L)

    /** Forgets the answers kept for the default time, so a refresh asks those sources again; one kept longer (a declared schedule) stays. */
    fun clear() {
        seen.keys.filter { ttl(it) <= ttlMs }.forEach { seen.remove(it) }
    }

    override fun get(url: String): String {
        val now = clock()
        seen[url]?.let { (at, body) -> if (now - at < ttl(url)) return body }
        val body = inner.get(url)
        seen[url] = now to body
        return body
    }

    override fun post(url: String): String = inner.post(url).also { seen.clear() }
}

/** Answers `asset:<name>` from [read] (a file declared in the app's data/ folder, e.g. the central bank calendar); anything else goes to [inner]. */
class AssetHttp(private val inner: Http, private val read: (String) -> String) : Http {
    override fun get(url: String): String =
        if (url.startsWith(ASSET)) try { read(url.removePrefix(ASSET)) } catch (e: Exception) { throw IOException("asset ${url.removePrefix(ASSET)}") } else inner.get(url)

    override fun post(url: String): String = inner.post(url)

    companion object {
        const val ASSET = "asset:"
    }
}
