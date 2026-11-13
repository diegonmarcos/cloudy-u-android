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
     * Answers a request from the copy of a PINNED site: what the copy holds is served cache-first and never expires. Same
     * origin only, GET only. Null = not answered here, Chromium loads it itself.
     *
     * #1711 NOTHING is fetched in here any more ([PinPolicy]). A request the copy does not hold (and, for a host that shows
     * documents live, a document; and everything on a [refresh]) is left to the WebView's own network stack at once, and
     * [PinRecorder] stores it in the background, so the page's own runtime requests are still recorded (lazy chunks
     * included) while a slow or unreachable second client can no longer hold a live page's request. [source] COPY (the page is
     * shown from the pinned copy: offline, or the live site did not answer) also answers a path whose query was never stored
     * with its newest stored query. Called off the main thread by shouldInterceptRequest.
     */
    fun intercept(
        ctx: Context, pinned: List<String>, req: WebResourceRequest, refresh: Boolean = false,
        liveDocuments: Boolean = false, source: PinPolicy.Source = PinPolicy.Source.LIVE,
    ): WebResourceResponse? {
        val u = req.url
        if (req.method != "GET" || (u.scheme != "https" && u.scheme != "http")) return null
        val host = MirrorScope.hostKey(u.host.orEmpty())
        if (!PinnedSites.isPinned(pinned, host)) return null
        if (userAgent == null) userAgent = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx.applicationContext) }.getOrNull()
        val url = SiteCache.normalise(u.toString())
        val c = cache(ctx)
        val hdr = req.requestHeaders.orEmpty()
        val range = header(hdr, "Range")
        val origin = header(hdr, "Origin")
        val document = PinPolicy.isDocument(req.isForMainFrame, header(hdr, "Accept"))
        val t0 = System.nanoTime()
        val stored = c.lookup(host, url) != null
        val storedPath = !stored && source == PinPolicy.Source.COPY && c.newest(host, url) != null
        val action = PinPolicy.decide(source, document, liveDocuments, refresh, stored, storedPath)
        val served = when (action) {
            PinPolicy.Action.SERVE -> c.serve(host, url, range, origin)
            PinPolicy.Action.SERVE_NEWEST -> c.serve(host, url, range, origin, anyQuery = true)
            else -> null
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (served != null) {
            PinLog.add(if (action == PinPolicy.Action.SERVE_NEWEST) PinLog.SERVED_NEWEST else PinLog.SERVED, url, ms, "${served.body.size} bytes")
            return response(served)
        }
        // never fetched here: the WebView loads it with its own network stack, and the copy is written in the background
        val why = when {
            source == PinPolicy.Source.COPY -> "not in the copy"
            refresh -> "reload"
            document -> "document"
            else -> "not in the copy yet"
        }
        PinLog.add(PinLog.LIVE, url, ms, why)
        if (action != PinPolicy.Action.PASS) PinRecorder.record(ctx, host, url, document, refresh)
        return null
    }

    private fun header(h: Map<String, String>, name: String): String? = h.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun response(s: SiteCache.Served) = WebResourceResponse(s.mime, s.encoding, s.status,
        if (s.status == 206) "Partial Content" else "OK", s.headers, ByteArrayInputStream(s.body))
}

/**
 * #1711 the copy of a pinned site is written OFF the request path: [SiteMirrorStore.intercept] leaves what the copy does
 * not hold to the WebView and queues the address here; one background thread fetches it ([SiteMirrorStore.http]: the
 * WebView's user agent, conditional on the stored ETag) and stores it. A request is never held by this client: when it is
 * slow or has no route the page does not notice, the copy is only written later. What is fetched again and when is
 * [PinPolicy.record].
 */
object PinRecorder {
    private const val MAX_PENDING = 256

    /** The page's own first requests go out before the recorder asks for the same files. */
    private const val START_DELAY_MS = 1_500L

    private class Job(val host: String, val url: String, val document: Boolean, val refresh: Boolean)

    private val lock = Any()
    private val pending = LinkedHashMap<String, Job>()
    private var worker: Thread? = null

    /** Queues [url] of the pinned [host] and returns at once. An address already queued is not queued twice (a reload upgrades it). */
    fun record(ctx: Context, host: String, url: String, document: Boolean, refresh: Boolean) {
        val app = ctx.applicationContext
        synchronized(lock) {
            val old = pending[url]
            if (old != null && (old.refresh || !refresh)) return
            if (old == null && pending.size >= MAX_PENDING) return
            pending[url] = Job(host, url, document || old?.document == true, refresh)
            if (worker == null) worker = Thread({ drain(app) }, "pin-recorder").apply { isDaemon = true; start() }
        }
    }

    /** How many addresses wait to be stored. */
    fun queued(): Int = synchronized(lock) { pending.size }

    /** Stores what is queued, one address after the other, and ends when the queue is empty: nothing waits on a timer. */
    private fun drain(app: Context) {
        runCatching { Thread.sleep(START_DELAY_MS) }
        var job = next()
        while (job != null) {
            val j: Job = job
            runCatching { store(app, j) }.onFailure { PinLog.add(PinLog.RECORD_FAILED, j.url, -1L, it.javaClass.simpleName) }
            job = next()
        }
    }

    /** The next queued address; null when the queue is empty (this worker then ends, a later request starts a new one). */
    private fun next(): Job? = synchronized(lock) {
        val k = pending.keys.firstOrNull()
        if (k == null) { worker = null; null } else pending.remove(k)
    }

    private fun store(app: Context, job: Job) {
        val c = SiteMirrorStore.cache(app)
        val hasQuery = '?' in job.url
        val exact = c.lookup(job.host, job.url)
        val newest = if (exact == null && hasQuery) c.newest(job.host, job.url) else exact
        if (!PinPolicy.record(job.refresh, job.document, hasQuery, exact?.stored, newest?.stored, System.currentTimeMillis())) return
        val t0 = System.nanoTime()
        val r = SiteMirrorStore.http.fetch(job.url, exact?.headers?.entries?.firstOrNull { it.key.equals("ETag", true) }?.value)
        val ms = (System.nanoTime() - t0) / 1_000_000
        when {
            r == null -> PinLog.add(PinLog.RECORD_FAILED, job.url, ms, "no answer")
            r.code == 200 -> {
                c.put(job.host, job.url, 200, r.mime, SiteCache.keepHeaders(r.headers), r.body)
                PinLog.add(PinLog.RECORDED, job.url, ms, "${r.body.size} bytes")
            }
            r.code == 304 -> PinLog.add(PinLog.UNCHANGED, job.url, ms)
            else -> PinLog.add(PinLog.RECORD_FAILED, job.url, ms, "HTTP ${r.code}")
        }
    }
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
     * page makes while it runs (lazy chunks, fetch(), media) are recorded into the same copy ([PinRecorder] writes them in the
     * background, #1711). [userTap] = the user asked now.
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

    /** Runs [start] in a hidden view for [RECORD_MS]; every same-origin request it makes is served or queued for storing by [SiteMirrorStore.intercept]. */
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
