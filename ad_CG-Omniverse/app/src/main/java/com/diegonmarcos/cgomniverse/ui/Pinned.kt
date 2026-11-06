package com.diegonmarcos.cgomniverse.ui

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.diegonmarcos.cgomniverse.BuildConfig
import com.diegonmarcos.cloudlib.web.PinnedSites
import com.diegonmarcos.cloudlib.web.SiteCache
import com.diegonmarcos.cloudlib.web.SiteMirrorStore

/**
 * Pin offline: a pinned world's same-origin GETs are served cache-first from [SiteCache] (never expiring, server cache headers
 * ignored) and every miss is fetched, stored and served, so the whole site accrues as it loads and runs (lazy chunks included).
 * Only an explicit refresh ([refresh] true) refetches; a failed refetch keeps the old copy. The default pins are declared in
 * build.json::pin_offline (floor796); the per-world toggle is remembered on the device.
 */
object Pinned {
    fun cache(ctx: Context): SiteCache = SiteMirrorStore.cache(ctx)
    private val declared: Set<String> by lazy { BuildConfig.PIN_OFFLINE.split(',').filter { it.isNotBlank() }.toSet() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worlds", Context.MODE_PRIVATE)
    fun isPinned(ctx: Context, worldId: String): Boolean = prefs(ctx).getBoolean("pin.$worldId", worldId in declared)
    fun setPinned(ctx: Context, worldId: String, on: Boolean) = prefs(ctx).edit().putBoolean("pin.$worldId", on).apply()

    /** The shared web-core intercept (one implementation for Galaxy and Cloud-Browser: headers, charset, COOP/COEP/CORS kept, WebView UA). */
    fun intercept(ctx: Context, host: String, req: WebResourceRequest, refresh: Boolean): WebResourceResponse? =
        if (req.url.host != host) null else SiteMirrorStore.intercept(ctx, listOf(PinnedSites.keyOf(host)), req, refresh)
}
