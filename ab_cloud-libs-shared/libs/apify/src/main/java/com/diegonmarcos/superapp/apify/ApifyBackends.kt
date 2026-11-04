package com.diegonmarcos.superapp.apify

import android.content.Context
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import org.json.JSONObject

/** One place actors run: [id] is stored, [api] is the fixed API base (null for the self-hosted one, whose base the user can change). */
data class ApifyBackend(val id: String, val label: String, val api: String?, val defaultUrl: String?, val kind: String = if (api == null) ApifyBackends.SELF_HOSTED else ApifyBackends.CLOUD) {
    /** Cloudflare Browser Run: an account id plus an API token, and a rendering endpoint instead of an actor. */
    val cloudflare get() = kind == ApifyBackends.CLOUDFLARE
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
        const val CLOUD_API = "https://api.apify.com/v2"
        /** The fleet's Crawlee Cloud (aa_cloud-superapp/data/cloud_services.json container `crawlee-cloud`, reached over the mesh). */
        const val SELF_URL = "https://crawlee-cloud.mesh.diegonmarcos.com"

        val DECLARED = ApifyBackends(
            listOf(
                ApifyBackend(CLOUD, "Apify Cloud", CLOUD_API, null),
                ApifyBackend(SELF_HOSTED, "Self-hosted Cloudy Crawlee", null, SELF_URL),
                ApifyBackend(CLOUDFLARE, "Cloudflare Browser Run", CLOUDFLARE_API, null, CLOUDFLARE),
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

        fun runSyncUrl(apiBase: String, actor: String) = "$apiBase/acts/${apiId(actor)}/run-sync-get-dataset-items?format=json&clean=1"
    }
}

/** Where the choice and the secrets live; a map in tests, [EncryptedApifyKv] on the phone. */
interface ApifyKv {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class EncryptedApifyKv(ctx: Context) : ApifyKv {
    private val sp = SafeEncryptedPrefs.prefs(ctx.applicationContext, PREFS)
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
    fun tokenKey(b: ApifyBackend) = when { b.cloudflare -> K_CF_TOKEN; b.api != null -> K_TOKEN; else -> K_BEARER }
    /** Cloudflare also needs its account id; the other backends need the token alone. */
    fun hasToken(b: ApifyBackend = backend()) = !kv.get(tokenKey(b)).isNullOrBlank() && (!b.cloudflare || accountId() != null)
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
        if (b.cloudflare) return ApifyBackends.cloudflareBase(b.api ?: ApifyBackends.CLOUDFLARE_API, accountId())?.let { it to t }
        return decl.apiBase(b, kv.get(K_URL)) to t
    }

    companion object { const val K_BACKEND = "apify.backend"; const val K_TOKEN = "apify.token"; const val K_URL = "apify.self.url"; const val K_BEARER = "apify.self.bearer"; const val K_CF_TOKEN = "cloudflare.api_token"; const val K_CF_ACCOUNT = "cloudflare.account_id" }
}
