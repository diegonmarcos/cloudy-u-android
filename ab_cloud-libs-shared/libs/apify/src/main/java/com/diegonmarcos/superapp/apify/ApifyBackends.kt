package com.diegonmarcos.superapp.apify

import android.content.Context
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import org.json.JSONObject

/** One place actors run: [id] is stored, [api] is the fixed API base (null for the self-hosted one, whose base the user can change). */
data class ApifyBackend(val id: String, val label: String, val api: String?, val defaultUrl: String?, val kind: String = if (api == null) ApifyBackends.SELF_HOSTED else ApifyBackends.CLOUD) {
    /** Cloudflare Browser Run: an account id plus an API token, and a rendering endpoint instead of an actor. */
    val cloudflare get() = kind == ApifyBackends.CLOUDFLARE
    /** #1498 Phone Browser (Cloud-Browser's headless engine on this phone) and Cloudy Headless (the cloudy-browser-headless container): headless-api v1 `run`, bearer. */
    val headless get() = kind == ApifyBackends.PHONE || kind == ApifyBackends.HEADLESS
}

/**
 * #1470 the backend choice, declared in data (an app's `apify` block: `default_backend`, `backends[]`) and parsed here; the
 * built-in [DECLARED] is the same fleet default for an app that declares nothing. Both backends speak the Apify API shape
 * (`/v2/acts/<user~name>/run-sync-get-dataset-items`, bearer auth), so a caller builds the same request for either.
 */
class ApifyBackends(val backends: List<ApifyBackend>, val default: String) {
    fun byId(id: String?): ApifyBackend = backends.firstOrNull { it.id == id } ?: backends.first { it.id == default }

    /** The API base ending in /v2 for [backend]; the self-hosted one uses [selfUrl] (blank = the declared default). */
    fun apiBase(backend: ApifyBackend, selfUrl: String? = null): String {
        backend.api?.let { return it }
        val u = (selfUrl?.takeIf { it.isNotBlank() } ?: backend.defaultUrl.orEmpty()).trim().trimEnd('/')
        return if (u.endsWith("/v2")) u else "$u/v2"
    }

    companion object {
        const val CLOUD = "cloud"
        const val SELF_HOSTED = "selfhosted"
        /** #1475 Cloudflare Browser Run (browser-rendering REST API: POST <api>/accounts/<account id>/browser-rendering/<endpoint>, bearer API token). */
        const val CLOUDFLARE = "cloudflare"
        const val CLOUDFLARE_API = "https://api.cloudflare.com/client/v4"
        /** The rendering endpoint a source run uses (markdown of the page); the others (content, scrape, json, links, crawl) share the base. */
        const val CLOUDFLARE_ENDPOINT = "markdown"
        /** Cloudflare Browser Run modes: the owner's deployed edge-md-scraper Worker (default) or the REST API (account id + token). Both answer Markdown. */
        const val CF_WORKER = "worker"
        const val CF_REST = "rest"
        const val CLOUD_API = "https://api.apify.com/v2"
        /** #1498 Phone Browser: the loopback listener of Cloud-Browser's HeadlessBrowserService (Configs > Headless Browser); the bearer is its per-run token. */
        const val PHONE = "phone"
        const val PHONE_URL = "http://127.0.0.1:8765"
        /** #1498 Cloudy Headless: the cloudy-browser-headless container behind its wg-only Caddy path route; the bearer is in the vault by name. */
        const val HEADLESS = "headless"
        const val HEADLESS_URL = "https://mcp.diegonmarcos.com/cloudy-browser-headless"
        /** The fleet's Crawlee Cloud (aa_cloud-superapp/data/cloud_services.json container `crawlee-cloud`, reached over the mesh). */
        const val SELF_URL = "https://crawlee-cloud.mesh.diegonmarcos.com"

        val DECLARED = ApifyBackends(
            listOf(
                ApifyBackend(CLOUD, "Apify Cloud", CLOUD_API, null),
                ApifyBackend(SELF_HOSTED, "Self-hosted Cloudy Crawlee", null, SELF_URL),
                ApifyBackend(CLOUDFLARE, "Cloudflare Browser Run", CLOUDFLARE_API, null, CLOUDFLARE),
                ApifyBackend(PHONE, "Phone Browser", PHONE_URL, null, PHONE),
                ApifyBackend(HEADLESS, "Cloudy Headless", HEADLESS_URL, null, HEADLESS),
            ),
            CLOUD,
        )

        fun parse(json: String?): ApifyBackends {
            if (json.isNullOrBlank()) return DECLARED
            return try {
                val o = JSONObject(json)
                val a = o.getJSONArray("backends")
                val list = (0 until a.length()).map { i ->
                    val b = a.getJSONObject(i)
                    ApifyBackend(b.getString("id"), b.optString("label", b.getString("id")), b.optString("api").ifBlank { null }, b.optString("default_url").ifBlank { null },
                        b.optString("kind").ifBlank { if (b.optString("api").isBlank()) SELF_HOSTED else CLOUD })
                }
                if (list.isEmpty()) DECLARED else ApifyBackends(list, o.optString("default_backend", CLOUD).takeIf { d -> list.any { it.id == d } } ?: list.first().id)
            } catch (e: Exception) { DECLARED }
        }

        /** `user/name` -> `user~name`, the form the API path takes. */
        fun apiId(actor: String) = actor.trim().replace('/', '~')

        /** `<api>/accounts/<id>/browser-rendering` for Cloudflare Browser Run; null while the account id is blank or not hex. */
        fun cloudflareBase(api: String, accountId: String?): String? =
            accountId?.trim()?.takeIf { Regex("[0-9a-fA-F]{32}").matches(it) }?.let { "${api.trimEnd('/')}/accounts/$it/browser-rendering" }

        fun cloudflareUrl(base: String, endpoint: String = CLOUDFLARE_ENDPOINT) = "$base/$endpoint"

        /** The Worker request for [target]: `<worker url>/?url=<encoded>` (GET, bearer). Null unless the Worker URL is https. */
        fun workerUrl(worker: String?, target: String): String? =
            worker?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") }?.let { "$it/?url=${java.net.URLEncoder.encode(target.trim(), "UTF-8")}" }

        /** The Markdown of a Cloudflare answer: the Worker answers the Markdown itself, the REST API wraps it as {success, result, errors}. */
        fun cloudflareMarkdown(mode: String, body: String): String {
            if (mode == CF_WORKER) return body
            val o = JSONObject(body)
            if (!o.optBoolean("success", false)) throw java.io.IOException(o.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty().ifBlank { "Cloudflare refused the request" })
            val r = o.opt("result")
            return if (r is org.json.JSONArray) (0 until r.length()).joinToString("\n") { r.optString(it) } else r?.toString().orEmpty()
        }

        /** #1498 the headless-api v1 `run` that renders [target] to Markdown: open, wait for idle, readable, close. */
        fun headlessRunBody(target: String): String = JSONObject().put("agent", "scraper").put("stepTimeoutMs", 30000).put("steps", org.json.JSONArray()
            .put(JSONObject().put("cmd", "open").put("url", target.trim()))
            .put(JSONObject().put("cmd", "wait").put("idle", true))
            .put(JSONObject().put("cmd", "readable"))
            .put(JSONObject().put("cmd", "close"))).toString()

        /** The Markdown of a headless `run` answer; throws IOException with the engine's message when a step failed. */
        fun headlessMarkdown(body: String): String {
            val o = JSONObject(body)
            val steps = o.optJSONArray("steps")
            if (!o.optBoolean("ok", false) && steps != null) {
                for (i in 0 until steps.length()) steps.getJSONObject(i).optJSONObject("error")?.let { throw java.io.IOException(it.optString("message", "the headless browser refused the step")) }
            }
            if (!o.optBoolean("ok", false)) throw java.io.IOException(o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "the headless browser refused the request" })
            return steps?.optJSONObject(2)?.optJSONObject("result")?.optString("markdown").orEmpty()
        }

        fun runSyncUrl(apiBase: String, actor: String) = "$apiBase/acts/${apiId(actor)}/run-sync-get-dataset-items?format=json&clean=1"
    }
}

/** Where the choice and the secrets live; a map in tests, [EncryptedApifyKv] on the phone. */
interface ApifyKv {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class EncryptedApifyKv(ctx: Context) : ApifyKv {
    // SafeEncryptedPrefs already falls back to plain app-private prefs on a LinkageError; this is the second net.
    private val sp: android.content.SharedPreferences = try { SafeEncryptedPrefs.prefs(ctx.applicationContext, PREFS) } catch (_: LinkageError) {
        ctx.applicationContext.getSharedPreferences("${PREFS}_unencrypted_fallback", Context.MODE_PRIVATE)
    }
    override fun get(key: String): String? = sp.getString(key, null)
    override fun set(key: String, value: String?) {
        sp.edit().apply { if (value.isNullOrBlank()) remove(key) else putString(key, value.trim()) }.apply()
    }
    companion object { const val PREFS = "apify_tokens" }
}

/** The shared store: the chosen backend (default from the declaration, Apify Cloud), the Apify token, the self-hosted URL and fleet bearer. Secrets never leave it except through [resolve]. */
class ApifyBackendStore(private val kv: ApifyKv, val decl: ApifyBackends = ApifyBackends.DECLARED) {
    fun backend(): ApifyBackend = decl.byId(kv.get(K_BACKEND))
    fun setBackend(id: String) = kv.set(K_BACKEND, decl.backends.firstOrNull { it.id == id }?.id)
    fun selfUrl(): String = kv.get(K_URL)?.takeIf { it.isNotBlank() } ?: decl.backends.firstOrNull { it.api == null }?.defaultUrl.orEmpty()
    fun setSelfUrl(url: String) = kv.set(K_URL, url.takeIf { it.isNotBlank() && it.trim().startsWith("https://") })
    fun tokenKey(b: ApifyBackend) = when { b.kind == ApifyBackends.PHONE -> K_PHONE_TOKEN; b.kind == ApifyBackends.HEADLESS -> K_HEADLESS_TOKEN; b.cloudflare -> if (cfMode() == ApifyBackends.CF_REST) K_CF_TOKEN else K_CF_WORKER_TOKEN; b.api != null -> K_TOKEN; else -> K_BEARER }
    /** Cloudflare Browser Run mode: the Worker URL (default) or the REST API. */
    fun cfMode(): String = if (kv.get(K_CF_MODE) == ApifyBackends.CF_REST) ApifyBackends.CF_REST else ApifyBackends.CF_WORKER
    fun setCfMode(mode: String) = kv.set(K_CF_MODE, mode.takeIf { it == ApifyBackends.CF_REST || it == ApifyBackends.CF_WORKER })
    fun workerUrl(): String? = kv.get(K_CF_WORKER_URL)?.trim()?.takeIf { ApifyBackends.workerUrl(it, "x") != null }
    /** False unless blank (clears) or an https URL. */
    fun setWorkerUrl(raw: String): Boolean {
        val t = raw.trim()
        if (t.isNotEmpty() && ApifyBackends.workerUrl(t, "x") == null) return false
        kv.set(K_CF_WORKER_URL, t)
        return true
    }
    /** Cloudflare needs the Worker URL (worker mode) or the account id (REST mode) besides its token; the other backends need the token alone. */
    fun hasToken(b: ApifyBackend = backend()) = !kv.get(tokenKey(b)).isNullOrBlank() &&
        (!b.cloudflare || (if (cfMode() == ApifyBackends.CF_REST) accountId() else workerUrl()) != null)
    fun accountId(): String? = kv.get(K_CF_ACCOUNT)?.trim()?.takeIf { ApifyBackends.cloudflareBase("x", it) != null }
    /** False (nothing stored) unless blank (clears) or the 32-hex Cloudflare account id. */
    fun setAccountId(raw: String): Boolean {
        val t = raw.trim()
        if (t.isNotEmpty() && ApifyBackends.cloudflareBase("x", t) == null) return false
        kv.set(K_CF_ACCOUNT, t)
        return true
    }
    /** False (nothing stored) for a blank value or one with spaces inside. */
    fun setToken(b: ApifyBackend, raw: String): Boolean {
        val t = raw.trim()
        if (t.isNotEmpty() && t.any { it.isWhitespace() }) return false
        kv.set(tokenKey(b), t)
        return true
    }

    /** The API base and bearer of the chosen backend, or null while no token / bearer is stored. */
    fun resolve(b: ApifyBackend = backend()): Pair<String, String>? {
        val t = kv.get(tokenKey(b)).orEmpty()
        if (t.isEmpty()) return null
        if (b.headless) return (if (b.kind == ApifyBackends.PHONE) b.api ?: ApifyBackends.PHONE_URL else (kv.get(K_HEADLESS_URL)?.takeIf { it.trim().startsWith("https://") } ?: b.api ?: ApifyBackends.HEADLESS_URL)).trimEnd('/') to t
        if (b.cloudflare) return (if (cfMode() == ApifyBackends.CF_REST) ApifyBackends.cloudflareBase(b.api ?: ApifyBackends.CLOUDFLARE_API, accountId()) else workerUrl())?.let { it to t }
        return decl.apiBase(b, kv.get(K_URL)) to t
    }

    companion object { const val K_BACKEND = "apify.backend"; const val K_TOKEN = "apify.token"; const val K_URL = "apify.self.url"; const val K_BEARER = "apify.self.bearer"; const val K_CF_TOKEN = "cloudflare.api_token"; const val K_CF_ACCOUNT = "cloudflare.account_id"
        const val K_CF_MODE = "cloudflare.mode"; const val K_CF_WORKER_URL = "cloudflare.worker_url"; const val K_CF_WORKER_TOKEN = "cloudflare.worker_token"
        const val K_PHONE_TOKEN = "phone.browser.token"; const val K_HEADLESS_TOKEN = "headless.cloudy.token"; const val K_HEADLESS_URL = "headless.cloudy.url" }
}
