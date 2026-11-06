package com.diegonmarcos.cloudlib.web

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** #1518 the Android half of the offline copy: the one cache, the network, the pin list, serving a pinned page. */
object SiteMirrorStore {
    /**
     * The one cache. v2 (#1537): copies made before it lack the response headers and were fetched with a bare Java user agent, so
     * the old directory is dropped once and every pinned site is recaptured the way the page really loads.
     */
    fun cache(ctx: Context): SiteCache {
        val files = ctx.applicationContext.filesDir
        File(files, "site-cache").takeIf { it.exists() }?.let { runCatching { it.deleteRecursively() } }
        return SiteCache(File(files, "site-cache-v2"))
    }
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("site_mirror", Context.MODE_PRIVATE)

    /** [defaults] = the declared pins (build.json::pin_offline), used until the user changes the list. */
    fun pinned(ctx: Context, defaults: List<String>): List<String> = PinnedSites.parse(prefs(ctx).getString("pinned", null), defaults)
    fun setPinned(ctx: Context, list: List<String>) = prefs(ctx).edit().putString("pinned", PinnedSites.serialise(list)).apply()

    /** The WebView's own user agent (set on the first intercepted request): a site must see the browser, not "Dalvik/..". */
    @Volatile var userAgent: String? = null

    /** A body larger than this is never buffered into memory: the request is left to Chromium (streamed, not copied). */
    const val MAX_BODY = 48L * 1024 * 1024

    /** The network: a plain GET with the WebView's UA, conditional when [etag] is given (304 -> code 304, no body). Null when the request fails or the body is too big to hold. */
    val http = Fetcher { url, etag ->
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 20_000
                userAgent?.let { setRequestProperty("User-Agent", it) }
                setRequestProperty("Accept", "*/*")
                etag?.let { setRequestProperty("If-None-Match", it) }
            }
            try {
                val code = c.responseCode
                if (code == 200 && c.contentLengthLong > MAX_BODY) return@runCatching null
                val h = c.headerFields.filterKeys { it != null }.mapValues { it.value.last() }
                Fetched(code, c.contentType.orEmpty(), h, if (code == 200) c.inputStream.use { it.readBytes() } else ByteArray(0))
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /**
     * Serves a request from the copy of a PINNED site, cache-first and never expiring; a miss is fetched, stored and served
     * (so the page's own runtime requests are recorded, lazy chunks included). Same origin only, GET only. Null = not ours,
     * Chromium loads it itself (also for a Range miss and a body too big to buffer). [refresh] (an explicit reload) refetches
     * conditionally first; a failed refetch keeps the old copy. Called off the main thread by shouldInterceptRequest.
     */
    fun intercept(ctx: Context, pinned: List<String>, req: WebResourceRequest, refresh: Boolean = false): WebResourceResponse? {
        val u = req.url
        if (req.method != "GET" || (u.scheme != "https" && u.scheme != "http")) return null
        val host = MirrorScope.hostKey(u.host.orEmpty())
        if (!PinnedSites.isPinned(pinned, host)) return null
        if (userAgent == null) userAgent = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx.applicationContext) }.getOrNull()
        val url = SiteCache.normalise(u.toString())
        val c = cache(ctx)
        val hdr = req.requestHeaders.orEmpty()
        val range = hdr["Range"]
        val origin = hdr["Origin"]
        if (!refresh) c.serve(host, url, range, origin)?.let { return response(it) }
        val old = c.lookup(host, url)
        val r = http.fetch(url, if (refresh) old?.headers?.entries?.firstOrNull { it.key.equals("ETag", true) }?.value else null)
        if (r != null && r.code == 200) c.put(host, url, 200, r.mime, SiteCache.keepHeaders(r.headers), r.body)
        if (r == null && old == null && range != null) return null
        return c.serve(host, url, range, origin)?.let { response(it) }
    }

    private fun response(s: SiteCache.Served) = WebResourceResponse(s.mime, s.encoding, s.status,
        if (s.status == 206) "Partial Content" else "OK", s.headers, ByteArrayInputStream(s.body))
}

/**
 * One site download or refresh at a time. A tap runs now; an unattended run waits for the charger AND Wi-Fi, re-checking only
 * on the system's own events (power connected, network change): there is no timer polling. A failed run retries after the
 * 30 s floor (doubling), at most [MirrorGate.MAX_ATTEMPTS] times. Progress (files, bytes) is published on the main thread.
 */
class SiteMirrorRunner(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    data class State(val host: String = "", val running: Boolean = false, val waiting: String = "", val files: Int = 0, val bytes: Long = 0, val pending: Int = 0,
        val current: String = "", val done: Boolean = false, val message: String = "")

    @Volatile var state = State(); private set
    var onState: ((State) -> Unit)? = null
    @Volatile private var cancelled = false
    private var receiver: BroadcastReceiver? = null

    private fun publish(s: State) { state = s; main.post { onState?.invoke(s) } }

    fun env(): MirrorGate.Env = Companion.env(ctx)

    fun cancel() { cancelled = true; stopWatching(); if (!state.running) publish(state.copy(waiting = "", message = "stopped")) }

    /**
     * Download [start]'s whole site: a same-origin crawl, then the start page runs once in a hidden view so the requests the
     * page makes while it runs (lazy chunks, fetch(), media) are recorded into the same copy. [userTap] = the user asked now.
     */
    fun download(start: String, userTap: Boolean, limits: MirrorLimits = MirrorLimits(), onFinished: () -> Unit = {}) =
        launch(PinnedSites.keyOf(start), userTap, {
            val cache = SiteMirrorStore.cache(ctx)
            val r = SiteMirrorEngine.crawl(cache, start, limits, SiteMirrorStore.http, { cancelled }, ::progress)
            if (r.files > 0 && !cancelled) {
                publish(state.copy(running = true, done = false, current = "recording the page's own requests"))
                record(start)
                val st = cache.stats(PinnedSites.keyOf(start))
                publish(state.copy(files = st.files, bytes = st.bytes))
            }
            r.files > 0
        }, onFinished)

    /** Runs [start] in a hidden view for [RECORD_MS]; every same-origin request it makes is stored (and served) by [SiteMirrorStore.intercept]. */
    private fun record(start: String) {
        val key = PinnedSites.keyOf(start)
        val latch = CountDownLatch(1)
        main.post {
            val wv = CloudWeb.create(ctx, WebProfile.BROWSER)
            wv.webViewClient = object : CloudWebClient(WebProfile.BROWSER) {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    request?.let { SiteMirrorStore.intercept(ctx, listOf(key), it) }
            }
            wv.loadUrl(start)
            main.postDelayed({ runCatching { wv.stopLoading(); wv.destroy() }; latch.countDown() }, RECORD_MS)
        }
        latch.await(RECORD_MS + 5_000, TimeUnit.MILLISECONDS)
    }

    /** Refresh [host]: the only thing that refetches an already-held file. A tap, so it runs now. */
    fun refresh(host: String, onFinished: () -> Unit = {}) =
        launch(host, true, { SiteMirrorEngine.refresh(SiteMirrorStore.cache(ctx), host, SiteMirrorStore.http, { cancelled }, ::progress); true }, onFinished)

    private fun progress(p: SiteMirrorEngine.Progress) = publish(state.copy(running = !p.done, files = p.files, bytes = p.bytes, pending = p.pending, current = p.current, done = p.done,
        message = if (p.done) p.stopped.ifEmpty { "done" } else "", waiting = ""))

    private fun launch(host: String, userTap: Boolean, work: () -> Boolean, onFinished: () -> Unit) {
        if (state.running) return
        cancelled = false
        val gate = MirrorGate.waiting(env(), userTap)
        if (gate != null) { publish(State(host = host, waiting = gate)); watch { launch(host, userTap, work, onFinished) }; return }
        stopWatching()
        publish(State(host = host, running = true))
        Thread {
            var attempt = 0
            var ok = false
            while (!ok && !cancelled && attempt < MirrorGate.MAX_ATTEMPTS) {
                ok = runCatching(work).getOrDefault(false)
                if (ok || cancelled) break
                val delay = MirrorGate.retryDelay(attempt++)
                publish(state.copy(message = "failed, retrying in ${delay / 1000} s"))
                runCatching { Thread.sleep(delay) }   // one bounded sleep per retry, never a loop around the clock
            }
            publish(state.copy(running = false, done = true, message = if (ok) state.message.ifEmpty { "done" } else if (cancelled) "stopped" else "failed after $attempt tries"))
            main.post { onFinished() }
        }.start()
    }

    /** Runs [again] once, on the next charger or network event (no timer). */
    private fun watch(again: () -> Unit) {
        stopWatching()
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (MirrorGate.allowed(env(), false)) { stopWatching(); again() } } }
        receiver = r
        val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(@Suppress("DEPRECATION") ConnectivityManager.CONNECTIVITY_ACTION) }
        androidx.core.content.ContextCompat.registerReceiver(ctx, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun stopWatching() { receiver?.let { runCatching { ctx.unregisterReceiver(it) } }; receiver = null }

    companion object {
        private const val RECORD_MS = 12_000L

        fun env(ctx: Context): MirrorGate.Env {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            return MirrorGate.Env(
                charging = bm.isCharging,
                wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }

        @Volatile var shared: SiteMirrorRunner? = null
        fun of(ctx: Context): SiteMirrorRunner = shared ?: synchronized(this) { shared ?: SiteMirrorRunner(ctx).also { shared = it } }
    }
}

/**
 * The maximum refresh rate while a WebGL / WebGPU canvas page is fullscreen (see [RefreshRatePolicy]). [watch] marks a view
 * so its pages tell whether they drew into a GPU canvas; [fullscreen] is fed from the fullscreen events; Super Saving Mode
 * is read as the system battery saver it switches on ([PowerManager.isPowerSaveMode]) and turns this off.
 */
class GpuRefreshRate(private val activity: Activity) {
    private val toggle = RefreshRateToggle { on -> applyRate(on) }
    private var fullscreen = false
    private var view: WeakReference<WebView>? = null
    private var receiver: BroadcastReceiver? = null

    private fun saving() = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    /** Marks [wv]: from the document start, a getContext('webgl' | 'webgl2' | 'webgpu') call sets window.__cloudGpuCanvas. */
    fun watch(wv: WebView) {
        view = WeakReference(wv)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) runCatching { WebViewCompat.addDocumentStartJavaScript(wv, HOOK, setOf("*")) }
    }

    fun fullscreen(on: Boolean) {
        fullscreen = on
        if (!on) { toggle.update(false, false, saving()); return }
        val wv = view?.get()
        if (wv == null) { toggle.update(true, false, saving()); return }
        wv.evaluateJavascript("(window.__cloudGpuCanvas===true)") { r -> toggle.update(fullscreen, r == "true", saving()) }
    }

    /** Leaving the app or a mode change: always back to the system's own rate. */
    fun release() { fullscreen = false; toggle.release() }

    /** Follows the battery saver: turning it on (Super Saving) reverts at once. */
    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (saving()) release() } }
        receiver = r
        androidx.core.content.ContextCompat.registerReceiver(activity, r, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() { release(); receiver?.let { runCatching { activity.unregisterReceiver(it) } }; receiver = null }

    private fun applyRate(on: Boolean) {
        val w = activity.window
        val lp = w.attributes
        if (on) {
            @Suppress("DEPRECATION")
            val display = (if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay) ?: return
            val modes = display.supportedModes
            val best = modes.maxByOrNull { it.refreshRate } ?: return
            lp.preferredRefreshRate = best.refreshRate
            lp.preferredDisplayModeId = best.modeId
        } else {
            lp.preferredRefreshRate = 0f
            lp.preferredDisplayModeId = 0
        }
        w.attributes = lp
    }

    companion object {
        const val HOOK = "(function(){var g=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(t){" +
            "if(/^(webgl|webgl2|experimental-webgl|webgpu)\$/.test(t))window.__cloudGpuCanvas=true;return g.apply(this,arguments);};})();"
    }
}
