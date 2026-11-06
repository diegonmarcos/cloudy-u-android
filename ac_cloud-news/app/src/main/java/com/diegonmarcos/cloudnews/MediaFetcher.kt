package com.diegonmarcos.cloudnews

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The network door of the Media feed (data/media.json is its only config; media-core.js builds the requests). The DIRECT path:
 * YouTube's public Atom feeds need no key, a channel page resolves an @handle once, and the Data API (a key in the private store)
 * only enriches. The page asks, this class is the policy, so the page cannot widen it:
 *  - https only, and only these requests: `youtube.com/feeds/videos.xml`, a channel page (`/@handle`, `/channel/...`) and
 *    `googleapis.com/youtube/v3/videos`; a redirect is followed by hand and re-checked (a consent redirect is an error, not a body);
 *  - `{key}` in a url is resolved here from the encrypted store and never returned; a url that needs a key without one stored is refused;
 *  - answers are cached on disk (feed.cache_hours; a channel page for 30 days). Modes: `auto` (the page was opened) serves a fresh cache,
 *    and refreshes a stale one only when [autoAllowed] (the unmetered / charging gate, build.json's auto-update rule); an EMPTY cache
 *    always fetches. `force` (pull-to-refresh) fetches, but not within feed.min_refresh_minutes of the last fetch;
 *  - a failed fetch serves the stale copy; bodies are capped; timeouts are fixed. No timers, no background work.
 * Result: {ok, status, body, age_ms, error, code}; age_ms is 0 for a live response.
 */
class MediaFetcher(
    cfg: JSONObject,
    private val cacheDir: File,
    private val keys: () -> EventKeyStore,
    private val autoAllowed: () -> Boolean,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val pageUserAgent: String = USER_AGENT,
) {
    private val feed = cfg.optJSONObject("feed") ?: JSONObject()
    private val ttlMs = feed.optDouble("cache_hours", 6.0) * HOUR
    private val minMs = feed.optDouble("min_refresh_minutes", 5.0) * MINUTE

    private class HttpFail(val code: String, msg: String) : IOException(msg)

    fun fetch(url: String, mode: String): JSONObject {
        val kind = kindOf(url) ?: return fail("blocked", "this request is not allowed")
        val needsKey = url.contains(KEY_PLACEHOLDER)
        if (needsKey && keys().get(KEY_ID).isNullOrBlank()) return fail("no-key", "no YouTube Data API key is stored")
        val file = File(cacheDir, EventFetcher.sha1(url) + ".json")
        val cached = try { if (file.isFile) JSONObject(file.readText()) else null } catch (e: Exception) { null }
        if (cached != null) {
            val age = now() - cached.optLong("t")
            val ttl = if (kind == Kind.PAGE) PAGE_TTL_MS else ttlMs.toLong()
            val serve = when (mode) {
                "force" -> age < minMs
                else -> age < ttl || !autoAllowed()
            }
            if (serve) return ok(cached, maxOf(1L, age))
        }
        return try {
            val live = network(url, kind, needsKey)
            try { cacheDir.mkdirs(); file.writeText(live.toString()) } catch (e: Exception) { /* a cache that cannot be written is only a miss */ }
            ok(live, 0)
        } catch (e: IOException) {
            if (cached != null) ok(cached, maxOf(1L, now() - cached.optLong("t")))
            else fail((e as? HttpFail)?.code ?: "error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun network(urlT: String, kind: Kind, needsKey: Boolean): JSONObject {
        var url = if (needsKey) urlT.replace(KEY_PLACEHOLDER, URLEncoder.encode(keys().get(KEY_ID).orEmpty(), "UTF-8")) else urlT
        repeat(MAX_REDIRECTS + 1) {
            kindOf(url.replace(KEY_PLACEHOLDER, "x")) ?: throw HttpFail("blocked", "redirected to a host that is not allowed (a consent page?)")
            val c = open(URL(url))
            try {
                c.requestMethod = "GET"
                c.instanceFollowRedirects = false
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("Accept", if (kind == Kind.API) "application/json" else "application/atom+xml, application/xml;q=0.9, text/html;q=0.8, */*;q=0.5")
                c.setRequestProperty("Accept-Language", "en")
                c.setRequestProperty("User-Agent", if (kind == Kind.PAGE) pageUserAgent else USER_AGENT)
                if (kind == Kind.PAGE) c.setRequestProperty("Cookie", "SOCS=CAI")
                val code = c.responseCode
                if (code in 301..308) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw HttpFail("error", "redirect without Location")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw HttpFail("http", "HTTP $code")
                val text = c.inputStream.use { readCapped(it) }
                if (text.size > MAX_BODY) throw HttpFail("error", "response over $MAX_BODY bytes")
                return JSONObject().put("t", now()).put("status", code).put("body", String(text, Charsets.UTF_8))
            } finally {
                c.disconnect()
            }
        }
        throw HttpFail("error", "too many redirects")
    }

    private fun readCapped(s: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= MAX_BODY) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun ok(c: JSONObject, age: Long) =
        JSONObject().put("ok", true).put("status", c.optInt("status", 200)).put("body", c.optString("body")).put("age_ms", age)

    private fun fail(code: String, msg: String) = JSONObject().put("ok", false).put("code", code).put("error", msg)

    enum class Kind { FEED, PAGE, API }

    companion object {
        const val USER_AGENT = "CloudNews (personal, non-commercial reader)"
        /** The EncryptedKeyStore id of the optional YouTube Data API key. */
        const val KEY_ID = "youtube-data-api"
        const val KEY_PLACEHOLDER = "{key}"
        private const val HOUR = 3_600_000.0
        private const val MINUTE = 60_000.0
        private const val PAGE_TTL_MS = 30L * 24 * 3_600_000
        private const val MAX_REDIRECTS = 3
        private const val MAX_BODY = 3_000_000
        private const val TIMEOUT_MS = 15_000
        private val YT_HOSTS = setOf("www.youtube.com", "youtube.com", "m.youtube.com")

        /** The kind of an allowed request, or null when [url] may not be requested. */
        fun kindOf(url: String): Kind? {
            val u = try { URL(url) } catch (e: Exception) { return null }
            if (u.protocol != "https") return null
            val path = u.path.orEmpty()
            return when {
                u.host in YT_HOSTS && path == "/feeds/videos.xml" -> Kind.FEED
                u.host in YT_HOSTS && (path.startsWith("/@") || path.startsWith("/channel/")) -> Kind.PAGE
                u.host == "www.googleapis.com" && path == "/youtube/v3/videos" -> Kind.API
                else -> null
            }
        }
    }
}
