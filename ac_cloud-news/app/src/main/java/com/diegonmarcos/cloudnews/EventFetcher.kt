package com.diegonmarcos.cloudnews

import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Where a ticketer's user-entered API key lives (EncryptedKeyStore on the phone, a map in tests). */
interface EventKeyStore {
    fun get(id: String): String?
    fun set(id: String, value: String?)
}

/**
 * The network door of the Events finder (data/event-finder.json is its only config). The page builds each request from
 * the declared row (events-core.js) and hands it here; this class is the policy, so the page cannot widen it:
 *  - https only, and the host must be one of the row's fetch.hosts (a redirect is followed by hand and re-checked);
 *  - `{key}` in the url, a header or the body is resolved here from the encrypted store and never returned to the page;
 *  - the response is cached on disk for cache_hours (the page asks, capped by the row), a forced refresh cannot go under the
 *    row's min_interval_hours, and a stale copy is served when the network fails (so offline still shows the last list);
 *  - bodies are capped (fetch_policy.max_body_bytes), timeouts are fixed.
 * Result shape: {ok, status, body, age_ms, error}; age_ms is 0 for a live response and > 0 for a cached one.
 */
class EventFetcher(
    private val cfg: JSONObject,
    private val cacheDir: File,
    private val keys: EventKeyStore,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val policy = cfg.optJSONObject("fetch_policy") ?: JSONObject()
    private val maxBody = policy.optInt("max_body_bytes", 2_000_000)
    private val timeoutMs = policy.optInt("timeout_ms", 15_000)

    fun ticketer(id: String): JSONObject? {
        val rows = cfg.optJSONArray("ticketers") ?: return null
        for (i in 0 until rows.length()) rows.getJSONObject(i).let { if (it.optString("id") == id) return it }
        return null
    }

    fun needsKey(id: String): Boolean = ticketer(id)?.optJSONObject("auth")?.optString("type") == "key"

    fun fetch(ticketerId: String, req: JSONObject, force: Boolean): JSONObject {
        val row = ticketer(ticketerId) ?: return fail(0, "unknown ticketer")
        val f = row.optJSONObject("fetch") ?: return fail(0, "no fetch declared")
        if (f.optString("kind") == "engine") return fail(0, "engine rows are not fetched here")
        val hosts = f.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val method = req.optString("method", "GET").uppercase()
        val urlT = req.optString("url")
        val body = req.optString("body", "")
        val problem = checkUrl(urlT, hosts)
        if (problem != null) return fail(0, problem)

        val ttlMs = minOf(req.optDouble("cache_hours", row.optDouble("cache_hours", 6.0)), MAX_TTL_HOURS) * HOUR
        val minMs = row.optDouble("min_interval_hours", 1.0) * HOUR
        val file = File(cacheDir, ticketerId + "-" + sha1("$method $urlT $body") + ".json")
        val cached = readCache(file)
        if (cached != null) {
            val age = now() - cached.optLong("t")
            if (age < ttlMs || (force && age < minMs)) return ok(cached, maxOf(1L, age))
        }

        return try {
            val live = network(ticketerId, method, urlT, req.optJSONObject("headers") ?: JSONObject(), body, hosts)
            writeCache(file, live)
            ok(live, 0)
        } catch (e: IOException) {
            if (cached != null) ok(cached, maxOf(1L, now() - cached.optLong("t")))
            else fail((e as? HttpFail)?.code ?: 0, e.message ?: e.javaClass.simpleName)
        }
    }

    private class HttpFail(val code: Int, msg: String) : IOException(msg)

    private fun network(id: String, method: String, urlT: String, headers: JSONObject, body: String, hosts: List<String>): JSONObject {
        var url = resolve(id, urlT) ?: throw HttpFail(0, "no key stored for $id")
        repeat(MAX_REDIRECTS + 1) {
            checkUrl(url, hosts)?.let { throw HttpFail(0, it) }
            val c = open(URL(url))
            try {
                c.requestMethod = method
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("Accept", "application/json, text/html;q=0.8")
                c.setRequestProperty("User-Agent", USER_AGENT)
                for (k in headers.keys()) c.setRequestProperty(k, resolve(id, headers.getString(k)) ?: throw HttpFail(0, "no key stored for $id"))
                if (body.isNotEmpty() && method != "GET") {
                    c.doOutput = true
                    c.outputStream.use { it.write((resolve(id, body) ?: throw HttpFail(0, "no key stored for $id")).toByteArray()) }
                }
                val code = c.responseCode
                if (code in 301..308) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw HttpFail(code, "redirect without Location")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw HttpFail(code, "HTTP $code")
                val text = c.inputStream.use { readCapped(it) }
                if (text.size > maxBody) throw HttpFail(code, "response over $maxBody bytes")
                return JSONObject().put("t", now()).put("status", code).put("body", String(text, Charsets.UTF_8))
            } finally {
                c.disconnect()
            }
        }
        throw HttpFail(0, "too many redirects")
    }

    /** Reads at most maxBody + 1 bytes (one over the cap is how the caller sees it was exceeded). */
    private fun readCapped(s: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= maxBody) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** `{key}` -> the stored key (url-encoded in a url, raw elsewhere is the caller's job: only urls and headers carry it today). null = no key stored. */
    private fun resolve(id: String, s: String): String? {
        if (!s.contains("{key}")) return s
        val k = keys.get(id)?.takeIf { it.isNotBlank() } ?: return null
        return s.replace("{key}", URLEncoder.encode(k, "UTF-8"))
    }

    private fun ok(c: JSONObject, age: Long) =
        JSONObject().put("ok", true).put("status", c.optInt("status", 200)).put("body", c.optString("body")).put("age_ms", age)

    private fun fail(status: Int, msg: String) = JSONObject().put("ok", false).put("status", status).put("error", msg)

    private fun readCache(f: File): JSONObject? = try { if (f.isFile) JSONObject(f.readText()) else null } catch (e: Exception) { null }
    private fun writeCache(f: File, o: JSONObject) { try { f.parentFile?.mkdirs(); f.writeText(o.toString()) } catch (e: Exception) { /* a cache that cannot be written is only a miss */ } }

    companion object {
        const val USER_AGENT = "CloudNews (personal, non-commercial reader; honours robots.txt)"
        private const val HOUR = 3_600_000.0
        private const val MAX_TTL_HOURS = 168.0
        private const val MAX_REDIRECTS = 3

        /** null when [url] may be requested: an https url on one of [hosts] (the key placeholder is not a host). */
        fun checkUrl(url: String, hosts: List<String>): String? {
            val u = try { URL(url.replace("{key}", "x")) } catch (e: Exception) { return "bad url" }
            if (u.protocol != "https") return "https only"
            if (u.host !in hosts) return "host ${u.host} is not declared for this ticketer"
            return null
        }

        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
