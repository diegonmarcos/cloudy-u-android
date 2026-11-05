package com.diegonmarcos.cloudnews

import com.diegonmarcos.superapp.apify.ApifyBackends
import com.diegonmarcos.superapp.apify.ApifyBackendStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1470 runs a declared Apify-method source on the chosen backend (Apify Cloud, or the self-hosted Cloudy Crawlee: the same
 * `/v2/acts/<user~name>/run-sync-get-dataset-items` shape, bearer auth, from the shared libs:apify store). Policy, so the page cannot widen it:
 *  - only a source declared in data/source-decl.json with method apify and enabled runs; its declared actor is used unless the user
 *    overrode it with a well-formed `user/name`;
 *  - the token / bearer is read here from the encrypted store and never returned; https only;
 *  - the answer is cached on disk for cache_hours (capped) and a stale copy is served when the run fails.
 * Result: {ok, items:[...], age_ms, error, code}; code `no-token` means nothing is stored for the chosen backend.
 */
class ApifyRunner(
    private val decl: JSONObject,
    private val cacheDir: File,
    private val store: ApifyBackendStore,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val timeoutMs = (decl.optJSONObject("apify")?.optInt("timeout_s", 120) ?: 120) * 1000

    fun declared(sourceId: String): JSONObject? {
        val a = decl.optJSONArray("sources") ?: return null
        for (i in 0 until a.length()) a.getJSONObject(i).let {
            if (it.optString("id") == sourceId && it.optString("method") == "apify" && it.optBoolean("enabled", true)) return it
        }
        return null
    }

    fun run(sourceId: String, actorOverride: String, inputJson: String, cacheHours: Double, force: Boolean): JSONObject {
        val row = declared(sourceId) ?: return fail("source $sourceId is not a declared, enabled Apify source", "not-declared")
        val actor = actorOverride.takeIf { ACTOR.matches(it) } ?: row.optString("actor")
        if (!ACTOR.matches(actor)) return fail("no actor id", "no-actor")
        val input = try { JSONObject(inputJson) } catch (e: Exception) { return fail("bad input", "bad-input") }
        val (base, bearer) = store.resolve() ?: return fail("no token stored for ${store.backend().label}", "no-token")
        val cf = store.backend().cloudflare
        val hl = store.backend().headless
        val rest = store.cfMode() == ApifyBackends.CF_REST
        val url = if (cf) (if (rest) ApifyBackends.cloudflareUrl(base) else base) else if (hl) "$base/run" else ApifyBackends.runSyncUrl(base, actor)
        // #1498 Phone Browser is the phone's own loopback listener (http, 127.0.0.1 only); every other backend is https
        if (!url.startsWith("https://") && !(hl && url.startsWith("http://127.0.0.1:"))) return fail("https only", "insecure")
        val ttl = minOf(cacheHours, MAX_TTL_HOURS) * HOUR
        val file = File(cacheDir, sourceId + "-" + EventFetcher.sha1("$url $input") + ".json")
        val cached = try { if (file.isFile) JSONObject(file.readText()) else null } catch (e: Exception) { null }
        if (cached != null) {
            val age = now() - cached.optLong("t")
            if (age < ttl || (force && age < MIN_FORCE_MS)) return ok(cached, maxOf(1L, age))
        }
        return try {
            val items = if (cf) cloudflare(url, bearer, input, rest) else if (hl) headless(url, bearer, input) else post(url, bearer, input.toString())
            val live = JSONObject().put("t", now()).put("items", items)
            try { file.parentFile?.mkdirs(); file.writeText(live.toString()) } catch (e: Exception) { /* a cache that cannot be written is only a miss */ }
            ok(live, 0)
        } catch (e: IOException) {
            if (cached != null) ok(cached, maxOf(1L, now() - cached.optLong("t"))) else fail(e.message ?: e.javaClass.simpleName, "error")
        }
    }

    private fun post(url: String, bearer: String, body: String): JSONArray =
        try { JSONArray(postText(url, bearer, body)) } catch (e: org.json.JSONException) { throw IOException("the actor did not answer with a JSON list") }

    private fun postText(url: String, bearer: String, body: String?): String {
        val c = open(URL(url))
        try {
            c.requestMethod = if (body == null) "GET" else "POST"
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException(if (code == 401 || code == 403) "HTTP $code: the token / bearer was refused" else "HTTP $code")
            val text = c.inputStream.use { it.readBytes() }
            if (text.size > MAX_BODY) throw IOException("response over $MAX_BODY bytes")
            return String(text, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /** #1475 Cloudflare Browser Run renders one page: the first start URL of the input as Markdown (Worker URL mode, default: GET <worker>/?url=; REST API mode: POST /markdown), answered as a one-item list [{url, markdown}]. */
    private fun cloudflare(url: String, bearer: String, input: JSONObject, rest: Boolean): JSONArray {
        val target = input.optJSONArray("startUrls")?.optJSONObject(0)?.optString("url").orEmpty().ifBlank { input.optString("url") }
        if (!target.startsWith("https://") && !target.startsWith("http://")) throw IOException("Cloudflare Browser Run needs a page URL in startUrls")
        val markdown = try {
            if (rest) ApifyBackends.cloudflareMarkdown(ApifyBackends.CF_REST, postText(url, bearer, JSONObject().put("url", target).toString()))
            else postText(ApifyBackends.workerUrl(url, target) ?: throw IOException("the Worker URL must be https"), bearer, null)
        } catch (e: org.json.JSONException) { throw IOException("Cloudflare did not answer with JSON") }
        return JSONArray().put(JSONObject().put("url", target).put("markdown", markdown))
    }

    /** #1498 Phone Browser / Cloudy Headless render one page (the first start URL) to Markdown through headless-api v1 `run`, answered as [{url, markdown}]. */
    private fun headless(url: String, bearer: String, input: JSONObject): JSONArray {
        val target = input.optJSONArray("startUrls")?.optJSONObject(0)?.optString("url").orEmpty().ifBlank { input.optString("url") }
        if (!target.startsWith("https://") && !target.startsWith("http://")) throw IOException("the headless browser needs a page URL in startUrls")
        val markdown = try { ApifyBackends.headlessMarkdown(postText(url, bearer, ApifyBackends.headlessRunBody(target))) } catch (e: org.json.JSONException) { throw IOException("the headless browser did not answer with JSON") }
        return JSONArray().put(JSONObject().put("url", target).put("markdown", markdown))
    }

    private fun ok(c: JSONObject, age: Long) = JSONObject().put("ok", true).put("items", c.optJSONArray("items") ?: JSONArray()).put("age_ms", age)
    private fun fail(msg: String, code: String) = JSONObject().put("ok", false).put("error", msg).put("code", code)

    companion object {
        val ACTOR = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private const val HOUR = 3_600_000.0
        private const val MAX_TTL_HOURS = 168.0
        private const val MIN_FORCE_MS = 3_600_000L
        private const val MAX_BODY = 4_000_000
    }
}
