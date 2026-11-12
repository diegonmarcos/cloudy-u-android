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
 *
 * #1662 a document (main frame, or any request that accepts text/html) is network-first: the live copy is fetched and stored,
 * and the pinned copy is served only when the network fails. A never-expiring document named a JS bundle the site had since
 * replaced (front.<hash>.js), so its 4 fetches failed (static / cdn / cdn3 / origin) and the scene never started: flat #444,
 * 0 images, 0 videos. Hashed assets stay cache-first. Every URL the intercept answered is recorded, so diagnostics can say
 * whether a failed request was served by the pinned copy.
 */
object Pinned {
    fun cache(ctx: Context): SiteCache = SiteMirrorStore.cache(ctx)
    private val declared: Set<String> by lazy { BuildConfig.PIN_OFFLINE.split(',').filter { it.isNotBlank() }.toSet() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worlds", Context.MODE_PRIVATE)
    fun isPinned(ctx: Context, worldId: String): Boolean = prefs(ctx).getBoolean("pin.$worldId", worldId in declared)
    fun setPinned(ctx: Context, worldId: String, on: Boolean) = prefs(ctx).edit().putBoolean("pin.$worldId", on).apply()

    /** The shared web-core intercept (one implementation for Galaxy and Cloud-Browser: headers, charset, COOP/COEP/CORS kept, WebView UA). */
    fun intercept(ctx: Context, host: String, req: WebResourceRequest, refresh: Boolean): WebResourceResponse? {
        val hdr = req.requestHeaders.orEmpty()
        if (!handles(req.url.host, host, hdr.entries.firstOrNull { it.key.equals("Range", true) }?.value,
                hdr.entries.firstOrNull { it.key.equals("Accept-Encoding", true) }?.value)) return null
        val live = refresh || networkFirst(req.isForMainFrame, req.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value)
        // a miss or a failure returns null: the WebView fetches it from the network itself, never an error or an empty body
        val r = runCatching { SiteMirrorStore.intercept(ctx, listOf(PinnedSites.keyOf(host)), req, live) }.getOrNull() ?: return null
        if (r.data == null) return null
        served(req.url.toString())
        return r
    }

    /**
     * #1694 what the pinned copy may answer: only the pinned host itself (a CDN host such as static.floor796.com always goes to
     * the network), and never a media stream. A media element asks with Range (bytes=N-) and Accept-Encoding identity, aborts and
     * re-asks at new offsets as it seeks; the network answers those with 206 itself, so the intercept never stands in the way.
     */
    fun handles(reqHost: String?, host: String, range: String?, acceptEncoding: String?): Boolean =
        reqHost != null && reqHost == host && range == null && !acceptEncoding.orEmpty().trim().startsWith("identity", true)

    /** #1662 documents are network-first (pinned copy only offline); everything else is cache-first. */
    fun networkFirst(mainFrame: Boolean, accept: String?): Boolean = mainFrame || accept.orEmpty().contains("text/html", true)

    private val servedUrls = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    fun served(url: String) { if (servedUrls.size > 2000) servedUrls.clear(); servedUrls += url }
    fun wasServed(url: String): Boolean = url in servedUrls
}
