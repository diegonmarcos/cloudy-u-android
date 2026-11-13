package com.diegonmarcos.cgomniverse.ui

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.diegonmarcos.cgomniverse.BuildConfig
import com.diegonmarcos.cloudlib.web.PinLog
import com.diegonmarcos.cloudlib.web.PinPolicy
import com.diegonmarcos.cloudlib.web.PinnedSites
import com.diegonmarcos.cloudlib.web.SiteCache
import com.diegonmarcos.cloudlib.web.SiteMirrorStore

/**
 * Pin offline: a pinned world's same-origin GETs the copy holds are served cache-first from [SiteCache] (never expiring, server
 * cache headers ignored); what it does not hold is loaded by the WebView itself and stored in the background, so the whole site
 * accrues as it loads and runs (lazy chunks included). Only an explicit refresh ([refresh] true) refetches; a failed refetch
 * keeps the old copy. The default pins are declared in build.json::pin_offline (floor796); the per-world toggle is remembered on
 * the device.
 *
 * #1662 a document (main frame, or any request that accepts text/html) is network-first: the live copy is shown and stored,
 * and the pinned copy is served only when the network fails. A never-expiring document named a JS bundle the site had since
 * replaced (front.<hash>.js), so its 4 fetches failed (static / cdn / cdn3 / origin) and the scene never started: flat #444,
 * 0 images, 0 videos. Hashed assets stay cache-first. Every URL the intercept answered is recorded, so diagnostics can say
 * whether a failed request was served by the pinned copy.
 *
 * #1711 the pinned copy never stands between the live page and the network (web-core [PinPolicy]). Until then every request
 * the copy could not answer, and every document, was fetched by the app inside the WebView's request with a second HTTP
 * client; floor796's first data request (data/matrix.json?r=<random>) is a new address on every load, so it could never be in
 * the copy and the scene waited on that client: no answer = the top bar over a flat #101010 page, 0 canvases, 0 failed
 * requests. Such a request now goes straight to the WebView's own network stack. When the live document does not arrive
 * (offline, or the site not answering within [PinPolicy.LIVE_WAIT_MS]) Galaxy shows the world from its pinned copy: the stored
 * document, and for an address whose query was never stored the newest stored answer of that path.
 */
object Pinned {
    fun cache(ctx: Context): SiteCache = SiteMirrorStore.cache(ctx)
    private val declared: Set<String> by lazy { BuildConfig.PIN_OFFLINE.split(',').filter { it.isNotBlank() }.toSet() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worlds", Context.MODE_PRIVATE)
    fun isPinned(ctx: Context, worldId: String): Boolean = prefs(ctx).getBoolean("pin.$worldId", worldId in declared)
    fun setPinned(ctx: Context, worldId: String, on: Boolean) = prefs(ctx).edit().putBoolean("pin.$worldId", on).apply()

    /**
     * The shared web-core intercept (one implementation for Galaxy and Cloud-Browser: headers, charset, COOP/COEP/CORS kept, WebView UA).
     * [fromCopy] = this load is shown from the pinned copy (the live document did not arrive).
     */
    fun intercept(ctx: Context, host: String, req: WebResourceRequest, refresh: Boolean, fromCopy: Boolean = false): WebResourceResponse? {
        val hdr = req.requestHeaders.orEmpty()
        if (!handles(req.url.host, host, hdr.entries.firstOrNull { it.key.equals("Range", true) }?.value,
                hdr.entries.firstOrNull { it.key.equals("Accept-Encoding", true) }?.value)) return null
        // a miss or a failure returns null: the WebView fetches it from the network itself, never an error or an empty body
        val r = runCatching {
            SiteMirrorStore.intercept(ctx, listOf(PinnedSites.keyOf(host)), req, refresh, liveDocuments = true, source = source(fromCopy))
        }.getOrNull() ?: return null
        if (r.data == null) return null
        served(req.url.toString())
        return r
    }

    /** #1711 where this load's answers come from: the live site (documents and anything not stored go to the network), or the pinned copy. */
    fun source(fromCopy: Boolean): PinPolicy.Source = if (fromCopy) PinPolicy.Source.COPY else PinPolicy.Source.LIVE

    /**
     * #1694 what the pinned copy may answer: only the pinned host itself (a CDN host such as static.floor796.com always goes to
     * the network), and never a media stream. A media element asks with Range (bytes=N-) and Accept-Encoding identity, aborts and
     * re-asks at new offsets as it seeks; the network answers those with 206 itself, so the intercept never stands in the way.
     */
    fun handles(reqHost: String?, host: String, range: String?, acceptEncoding: String?): Boolean =
        reqHost != null && reqHost == host && range == null && !acceptEncoding.orEmpty().trim().startsWith("identity", true)

    /** #1662 documents are network-first (the pinned copy only when the live page does not arrive); what the copy holds of everything else is cache-first. */
    fun networkFirst(mainFrame: Boolean, accept: String?): Boolean = PinPolicy.isDocument(mainFrame, accept)

    /**
     * #1711 the pinned copy holds the document Galaxy loads for [launchUrl], so the world can be shown from it when the live
     * page does not arrive. Reads the disk: call it off the main thread.
     */
    fun hasDocument(ctx: Context, host: String, launchUrl: String): Boolean =
        runCatching { cache(ctx).lookup(PinnedSites.keyOf(host), PinPolicy.documentKey(launchUrl)) != null }.getOrDefault(false)

    /** #1711 the diagnostics line under "Failed requests": what pin offline does for this load, in words. */
    fun status(pinned: Boolean, fromCopy: Boolean, why: String): String = when {
        !pinned -> "off"
        fromCopy -> "on, showing the pinned copy (" + why.ifEmpty { "the live site did not answer" } + "); Reload asks the live site again"
        else -> "on (documents network-first, assets from the pinned copy; what the copy does not hold loads from the network and is stored in the background)"
    }

    /** #1711 what the intercept did with this world's latest requests (web-core [PinLog]), oldest first. */
    fun log(host: String, last: Int = 40): List<String> = PinLog.of(host).takeLast(last).map { it.line() }

    /** `12 served from the copy, 3 left to the network, 3 stored` for this world's logged requests. */
    fun logSummary(host: String): String = PinLog.summary(PinLog.of(host))

    private val servedUrls = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    fun served(url: String) { if (servedUrls.size > 2000) servedUrls.clear(); servedUrls += url }
    fun wasServed(url: String): Boolean = url in servedUrls
}
