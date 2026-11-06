package com.diegonmarcos.cloudbrowser.sites

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.diegonmarcos.superapp.browser.BrowserClearCategories
import com.diegonmarcos.superapp.browser.BrowserHistory
import com.diegonmarcos.superapp.browser.BrowserSitePermissions
import com.diegonmarcos.superapp.browser.BrowserStorage
import com.diegonmarcos.superapp.browser.BrowserVisit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The visit log (`browser_visit_log` prefs, content): every page the browser's history ever showed, kept past its own cap. */
class VisitLogStore(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("browser_visit_log", Context.MODE_PRIVATE)

    fun all(): List<BrowserVisit> = VisitLog.fromJson(sp.getString(KEY, null))

    /** Fold the browser's history in and return the log. BLOCKING: off the main thread. */
    fun sync(): List<BrowserVisit> = synchronized(LOCK) {
        val merged = VisitLog.merge(all(), BrowserHistory(ctx).all())
        sp.edit().putString(KEY, VisitLog.toJson(merged)).apply()
        merged
    }

    private companion object { const val KEY = "log_json"; val LOCK = Any() }
}

/** The site actions on this phone. Cookies, storage and permissions go through the browser's own per-site calls; MAIN THREAD. */
class AndroidSiteExec(context: Context) : SiteExec {
    private val ctx = context.applicationContext
    override fun deleteFiles(paths: List<String>) { paths.forEach { File(it).delete() } }
    override fun cookies(host: String) = BrowserStorage.clearSiteCookies("https://$host/") { }
    override fun storage(host: String) = BrowserStorage.clearSiteData("https://$host/", setOf(BrowserClearCategories.SITE_STORAGE)) { }
    override fun resetPerms(host: String) = BrowserSitePermissions(ctx).forget(host)
}

/** The last pages.json answer (`cloudy_cache` prefs, device): Cloudy shows it at once and refreshes it when the network answers. */
class CloudyCache(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cloudy_cache", Context.MODE_PRIVATE)
    fun pagesJson(): String? = sp.getString(KEY, null)

    /** GET [url] (https) and keep the answer; null when the network says no. BLOCKING. */
    fun refresh(url: String, timeoutMs: Int): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.inputStream.bufferedReader().use { it.readText() }.also { sp.edit().putString(KEY, it).apply() }
    }.getOrNull()

    private companion object { const val KEY = "pages_json" }
}

/** #1531 one GET of a site's public icon or page (at most [max] bytes) with the WebView's own user agent; null when the network or the site says no. BLOCKING. */
fun iconGet(url: String, max: Int, userAgent: String): ByteArray? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 4000; c.readTimeout = 6000; c.instanceFollowRedirects = true
    if (userAgent.isNotEmpty()) c.setRequestProperty("User-Agent", userAgent)
    if (c.responseCode !in 200..299) return@runCatching null
    c.inputStream.use { ins ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        out.toByteArray()
    }
}.getOrNull()

private val main = Handler(Looper.getMainLooper())

/** Run [f] on the main thread. */
fun onMain(f: () -> Unit) { main.post(f) }
