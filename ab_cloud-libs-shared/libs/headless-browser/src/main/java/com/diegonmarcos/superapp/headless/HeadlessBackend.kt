package com.diegonmarcos.superapp.headless

/**
 * #1498 the backend selector (Configs > Headless Browser). The default is This App. The other two are remote: Cloudflare Browser Run reuses
 * the #1474/#1475 store keys of libs:apify (ApifyBackendStore: cloudflare.worker_url / cloudflare.worker_token); Cloudy-Browser-Headless reads
 * its URL and bearer from store keys whose NAMES are settings (the values live in the encrypted store, never in settings or code).
 */
enum class HeadlessBackend(val id: String, val label: String) {
    CLOUDFLARE("cloudflare-browser-run", "Cloudflare-Browser-Run"),
    CLOUDY("cloudy-browser-headless", "Cloudy-Browser-Headless"),
    THIS_APP("this-app", "This App");

    companion object {
        val DEFAULT = THIS_APP
        const val KEY = "headless_backend"
        const val CLOUDY_URL_KEY = "headless_cloudy_url_key"
        const val CLOUDY_TOKEN_KEY = "headless_cloudy_token_key"
        const val CF_URL_STORE_KEY = "cloudflare.worker_url"
        const val CF_TOKEN_STORE_KEY = "cloudflare.worker_token"
        const val DEFAULT_CLOUDY_URL_KEY = "headless.cloudy.url"
        const val DEFAULT_CLOUDY_TOKEN_KEY = "headless.cloudy.token"

        /** The stored id, else the default; an unknown id falls back to the default. */
        fun parse(id: String?): HeadlessBackend = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Where a call goes: this app's engine, or a remote base URL + bearer. [reason] says why a remote one is not usable. */
data class HeadlessRoute(val backend: HeadlessBackend, val baseUrl: String?, val bearer: String?, val reason: String? = null) {
    val local get() = backend == HeadlessBackend.THIS_APP
    val usable get() = local || (baseUrl != null && bearer != null && reason == null)
}

object HeadlessSelector {
    /** [setting] reads the Configs values by key, [store] reads the encrypted store by key NAME. */
    fun resolve(setting: (String) -> String?, store: (String) -> String?): HeadlessRoute {
        val b = HeadlessBackend.parse(setting(HeadlessBackend.KEY))
        return when (b) {
            HeadlessBackend.THIS_APP -> HeadlessRoute(b, null, null)
            HeadlessBackend.CLOUDFLARE -> remote(b, store(HeadlessBackend.CF_URL_STORE_KEY), store(HeadlessBackend.CF_TOKEN_STORE_KEY))
            HeadlessBackend.CLOUDY -> remote(
                b,
                store(setting(HeadlessBackend.CLOUDY_URL_KEY)?.takeIf { it.isNotBlank() } ?: HeadlessBackend.DEFAULT_CLOUDY_URL_KEY),
                store(setting(HeadlessBackend.CLOUDY_TOKEN_KEY)?.takeIf { it.isNotBlank() } ?: HeadlessBackend.DEFAULT_CLOUDY_TOKEN_KEY),
            )
        }
    }

    private fun remote(b: HeadlessBackend, url: String?, token: String?): HeadlessRoute {
        val u = url?.trim()?.trimEnd('/')
        return when {
            u.isNullOrEmpty() -> HeadlessRoute(b, null, null, "no URL in the store")
            !u.startsWith("https://") -> HeadlessRoute(b, null, null, "the URL must be https")
            token.isNullOrBlank() -> HeadlessRoute(b, u, null, "no bearer token in the store")
            else -> HeadlessRoute(b, u, token.trim())
        }
    }
}
