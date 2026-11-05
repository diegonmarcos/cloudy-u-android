package com.diegonmarcos.cgomniverse.ui

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.diegonmarcos.cgomniverse.BuildConfig
import com.diegonmarcos.cloudlib.web.SiteCache
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pin offline: a pinned world's same-origin GETs are served cache-first from [SiteCache] (never expiring, server cache headers
 * ignored) and every miss is fetched, stored and served, so the whole site accrues as it loads and runs (lazy chunks included).
 * Only an explicit refresh ([refresh] true) refetches; a failed refetch keeps the old copy. The default pins are declared in
 * build.json::pin_offline (floor796); the per-world toggle is remembered on the device.
 */
object Pinned {
    fun cache(ctx: Context) = SiteCache(File(ctx.filesDir, "site-cache"))
    private val declared: Set<String> by lazy { BuildConfig.PIN_OFFLINE.split(',').filter { it.isNotBlank() }.toSet() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worlds", Context.MODE_PRIVATE)
    fun isPinned(ctx: Context, worldId: String): Boolean = prefs(ctx).getBoolean("pin.$worldId", worldId in declared)
    fun setPinned(ctx: Context, worldId: String, on: Boolean) = prefs(ctx).edit().putBoolean("pin.$worldId", on).apply()

    fun intercept(ctx: Context, host: String, req: WebResourceRequest, refresh: Boolean): WebResourceResponse? {
        val u = req.url
        if (req.method != "GET" || u.host != host || (u.scheme != "https" && u.scheme != "http")) return null
        val url = u.toString()
        val c = cache(ctx)
        val range = req.requestHeaders["Range"]
        if (!refresh) c.serve(host, url, range)?.let { return response(it) }
        val old = c.lookup(host, url)
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 20_000
                old?.headers?.get("ETag")?.let { setRequestProperty("If-None-Match", it) }
            }
            val code = conn.responseCode
            if (code == 304) true
            else if (code == 200) {
                val h = conn.headerFields.filterKeys { it != null }.mapValues { it.value.last() }
                c.put(host, url, 200, conn.contentType.orEmpty(), h.filterKeys { it.equals("ETag", true) || it.equals("Last-Modified", true) }, conn.inputStream.use { it.readBytes() })
                true
            } else false
        }
        return c.serve(host, url, range)?.let { response(it) }
    }

    private fun response(s: SiteCache.Served) = WebResourceResponse(s.mime, null, s.status,
        if (s.status == 206) "Partial Content" else "OK", s.headers, ByteArrayInputStream(s.body))
}
