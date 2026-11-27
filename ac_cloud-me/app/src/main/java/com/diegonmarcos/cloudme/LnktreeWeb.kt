package com.diegonmarcos.cloudme

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.lazy.LazyListState
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Lnktree page cache (#1323): the Android side. The rules live in LnktreeCache.kt. */
object LnktreeCache {
    val config: LnktreeCacheConfig get() = LnktreeCacheConfig.baked

    @Volatile private var store: LnktreeCacheStore? = null
    fun store(ctx: Context): LnktreeCacheStore =
        store ?: synchronized(this) { store ?: LnktreeCacheStore(File(ctx.applicationContext.cacheDir, "lnktree-cache")).also { st -> st.onWipe = { w -> android.util.Log.i("CloudMeWeb", "CACHE_WIPE $w at ${Throwable().stackTrace.drop(2).take(7).joinToString(" < ") { it.className.substringAfterLast('.') + '.' + it.methodName }}") }
            // #1957 a copy taken under another cache_version (before a site fix) is never painted: wiped once, refilled by the startup job
            if (st.ensureVersion(config.cacheVersion)) android.util.Log.i("CloudMeWeb", "CACHE_VERSION wiped=yes version=${config.cacheVersion}")
            store = st } }

    /** #1947 page id -> when the offline copy now shown was fetched; absent = the page came from the network. */
    val offlineSince = ConcurrentHashMap<String, Long>()

    /** #1951 page id -> called (any thread) when the live page differs from the cached copy that was painted: the shown page swaps to it. */
    val swapListeners = ConcurrentHashMap<String, (Int) -> Unit>()
    /** #1951 page ids whose cached copy was painted and whose live check is still running: the blank probe waits for them. */
    val swapPending = ConcurrentHashMap.newKeySet<String>()

    /** #1951 the log lines the owner and the walk read: per page entries, bytes, complete=yes/no, then the total against max_cache_mb. */
    fun logReport(ctx: Context, pages: List<LnktreePage> = pages(ctx)) {
        val s = store(ctx); val reps = pages.map { s.report(it.id, it.url) }
        reps.forEach { android.util.Log.i("CloudMeWeb", it.line()) }
        android.util.Log.i("CloudMeWeb", "CACHE_TOTAL pages=${reps.size} complete=${reps.count { it.complete }} bytes=${s.totalBytes()} pages_bytes=${reps.sumOf { it.bytes }} shared_bytes=${s.shared?.totalBytes() ?: 0} max_bytes=${config.maxBytes}")   // #1957 bytes = on disk, each shared file once
    }

    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val background by lazy { Executors.newFixedThreadPool(config.prefetchConcurrency) }

    /** The cacheable pages of the declared section, read from the same page files the app shows. */
    fun pages(ctx: Context, cfg: LnktreeCacheConfig = config): List<LnktreePage> {
        fun leaves(ps: List<Page>): List<Page> = ps.flatMap { if (it.pages.isEmpty()) listOf(it) else leaves(it.pages) }
        return cfg.cachedSections().mapNotNull { Sections.byId(it) }.flatMap { section ->
            leaves(section.pages).mapNotNull { LnktreePages.fromStack(it.id, Sections.stack(ctx, section.id, it.id), cfg) }
        }
    }

    /** One blocking GET with no HTTP caching of its own (the cache is ours). Null on any failure. */
    fun fetch(url: String): Fetched? = fetch(url, 10_000, 20_000)

    /** #1951 an intercept never waits longer than the declared ui.lnktree_cache.intercept_timeout_ms on the network. */
    fun fetch(url: String, connectMs: Int, readMs: Int): Fetched? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs; c.useCaches = false; c.instanceFollowRedirects = true
            c.setRequestProperty("Cache-Control", "no-cache")
            val ct = c.contentType.orEmpty()
            val mime = ct.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val enc = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            val code = c.responseCode
            // #1957 a video larger than the declared per-file cap is not downloaded at all
            val cap = config.videoMaxFileBytes
            if (code == 200 && cap > 0 && RefScan.isVideo(url, mime) && c.contentLengthLong > cap) return@runCatching Fetched(mime, null, LnktreePrefetch.VIDEO_CAPPED, ByteArray(0))
            // #1957 bounded: a video stops at its per-file cap (known-missing, not read whole), any body at max_file_mb or the fetch deadline
            val lim = if (cap > 0 && RefScan.isVideo(url, mime)) cap else config.maxFileBytes
            val r = if (code == 200) c.inputStream.use { BoundedRead.read(it, lim, config.fetchDeadlineMs) } else null
            if (r is BoundedRead.Result.TooLarge) return@runCatching if (RefScan.isVideo(url, mime)) Fetched(mime, "video-file-cap", LnktreePrefetch.VIDEO_CAPPED, ByteArray(0)) else null
            if (r is BoundedRead.Result.Timeout) { android.util.Log.w("CloudMeWeb", "FETCH_DEADLINE url=$url"); return@runCatching null }
            val body = (r as? BoundedRead.Result.Ok)?.bytes
            if (body != null) Fetched(mime, enc, 200, body)
            else if (code in 400..499 && code != 408 && code != 429) Fetched(mime, enc, code, ByteArray(0))   // #1947 a definite "not there" (a tombstone); #1957 any definite 4xx (jsDelivr answers 403 for a file over its size limit); every other failure stays null
            else null
        } finally { c.disconnect() }
    }.getOrNull()

    /** shouldInterceptRequest for [pageId] (declared at [pageUrl]): same-origin GETs come from the cache; the rest is the WebView's own. */
    fun intercept(ctx: Context, pageId: String, pageUrl: String, req: WebResourceRequest): WebResourceResponse? {
        val cfg = config
        if (!cfg.caches(pageId) || req.method != "GET") return null
        val u = req.url.toString()
        val base = java.net.URI(pageUrl)
        val target = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        // #1957 cross_origin: the page's CDN images and media come from its copy too
        if (target.scheme != "https" || (!cfg.crossOrigin && (!target.host.equals(base.host, true) || target.port != base.port))) return null
        val s = store(ctx); s.registerPage(pageId, pageUrl)   // #1957 its other-origin files go to the shared store
        val range = req.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        if (range != null) return if (cfg.cacheTtl) rangeOf(s.get(pageId, u), range) else null   // #1957 a stored video plays from its copy (206), offline too
        if (CacheKey.normalize(u) == CacheKey.normalize(pageUrl)) s.keep.add(CacheKey.dirName(pageId))   // #1947 a page the user opened is trimmed last
        val isMain = CacheKey.normalize(u) == CacheKey.normalize(pageUrl)
        val t = cfg.interceptTimeoutMs
        val served = LnktreeResolver.serve(pageId, u, cfg, s, { if (t > 0) fetch(it, t, t) else fetch(it) }, { stale ->
            if (cfg.cacheTtl) ttlBackground(ctx, s, pageId, stale, pageUrl)
            else {
                if (isMain && cfg.cacheThenLive && s.paintable(pageId, u)) swapPending.add(pageId)
                revalidate(s, pageId, stale, pageUrl)
            }
        }, pageUrl)
        if (CacheKey.normalize(u) == CacheKey.normalize(pageUrl)) {
            // #1947 the walk reads this: where the main frame came from; the offline note reads offlineSince
            android.util.Log.i("CloudMeWeb", "MAINFRAME_SOURCE page=$pageId source=${served?.source ?: "webview-network"} strategy=${cfg.strategy}")
            if (served?.source == "offline-cache") offlineSince[pageId] = served.fetchedAt else offlineSince.remove(pageId)
        }
        if (served == null) return null
        return WebResourceResponse(served.mime, served.encoding, served.status, if (served.status == 200) "OK" else "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), served.body.inputStream())
    }

    /** #1957 a Range request (video) answered from a stored 200 body: 206 with the asked slice. Null = not stored, the network takes it. */
    fun rangeOf(e: CacheEntry?, range: String): WebResourceResponse? {
        if (e == null || e.status != 200) return null
        val len = e.body.length(); val m = Regex("bytes=(\\d*)-(\\d*)").find(range) ?: return null
        val a = m.groupValues[1].toLongOrNull() ?: 0L; val b = (m.groupValues[2].toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
        if (a > b || a >= len) return null
        val inp = e.body.inputStream(); inp.skip(a)
        val body = object : java.io.FilterInputStream(inp) { var left = b - a + 1
            override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
            override fun read(buf: ByteArray, off: Int, n: Int): Int = if (left <= 0) -1 else super.read(buf, off, minOf(n.toLong(), left).toInt()).also { if (it > 0) left -= it } }
        return WebResourceResponse(e.mime, null, 206, "Partial Content", mapOf("Content-Range" to "bytes $a-$b/$len", "Content-Length" to (b - a + 1).toString(), "Accept-Ranges" to "bytes", "Access-Control-Allow-Origin" to "*"), body)
    }

    /**
     * #1957 cache_ttl's background work for an open page: the main frame of a copy older than ttl_days (or with no copy) runs the page's
     * startup action (staged refetch, swapped in atomically; no swap of the shown page); a file the copy lacks is added to the page's
     * manifest and stored. Nothing here runs for a fresh, complete copy.
     */
    private fun ttlBackground(ctx: Context, s: LnktreeCacheStore, pageId: String, url: String, pageUrl: String) {
        if (!refreshing.add("$pageId $url")) return
        background.execute {
            try {
                if (CacheKey.normalize(url) == CacheKey.normalize(pageUrl)) {
                    val o = TtlPrefetch.page(LnktreePage(pageId, pageUrl), config, s, ::fetch)
                    android.util.Log.i("CloudMeWeb", o.line() + " trigger=open " + s.report(pageId, pageUrl).line())
                    s.trim(config.maxBytes)
                } else {
                    s.addRef(pageId, url)
                    LnktreePrefetch.fetchCapped(url, config, s, ::fetch)?.let { if (it.status == 200 || it.status == 404 || it.status == 410 || it.status == LnktreePrefetch.VIDEO_CAPPED) s.put(pageId, url, it.mime, it.encoding, it.status, if (it.status == 200) it.body else ByteArray(0)) }
                    android.util.Log.i("CloudMeWeb", "CACHE_MISS page=$pageId url=$url stored=${s.get(pageId, url) != null}")
                }
            } finally { refreshing.remove("$pageId $url") }
        }
    }

    /** #1951 the live compare of a page: the cache is updated when the live content differs, and the shown page (its listener) swaps to it. Also what the section pager's fetch runs for a page that already has a copy. */
    fun checkLive(s: LnktreeCacheStore, pageId: String, url: String): Int {
        val changed = LiveSwap.refresh(LnktreePage(pageId, url), config, s, ::fetch)
        android.util.Log.i("CloudMeWeb", "LIVE_CHECK page=$pageId changed=$changed ${s.report(pageId, url).line()}")
        if (changed > 0 && config.cacheThenLive) swapListeners[pageId]?.invoke(changed)
        return changed
    }

    private fun revalidate(s: LnktreeCacheStore, pageId: String, url: String, pageUrl: String) {
        if (!refreshing.add("$pageId $url")) return
        background.execute {
            try {
                // #1947 the page itself: refetch it WITH its CSS/JS (completes an incomplete entry); a file: just that file
                if (CacheKey.normalize(url) == CacheKey.normalize(pageUrl)) {
                    checkLive(s, pageId, url)
                } else fetch(url)?.let { s.put(pageId, url, it.mime, it.encoding, it.status, it.body) }
            }
            finally { refreshing.remove("$pageId $url"); if (CacheKey.normalize(url) == CacheKey.normalize(pageUrl)) swapPending.remove(pageId) }
        }
    }

    /** Hard refresh: deletes the cache files (scope from build.json), then empties the WebView's own cache. Call off the UI thread for the deletion. */
    fun hardRefresh(ctx: Context, pageId: String): Int = LnktreeResolver.hardRefresh(pageId, config, store(ctx))

    /** #1947 after a hard refresh the offline fallback is filled again in the background: the page and its CSS/JS are refetched from the network. */
    fun refill(ctx: Context, pageId: String, pageUrl: String) = if (config.cacheTtl) ttlBackground(ctx, store(ctx), pageId, pageUrl, pageUrl) else revalidate(store(ctx), pageId, pageUrl, pageUrl)
}

/** Background fill of the pages that have no valid entry; enqueued once per app start. */
class LnktreePrefetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cfg = LnktreeCache.config
        val store = LnktreeCache.store(applicationContext)
        val all = LnktreeCache.pages(applicationContext, cfg)
        if (cfg.cacheTtl) {
            // #1957 every page of every section: no copy -> fill, fresh and complete -> nothing, fresh and incomplete -> its missing files, older than ttl_days -> staged refetch
            val done = java.util.concurrent.atomic.AtomicInteger()
            android.util.Log.i("CloudMeWeb", "CACHE_PROGRESS done=0 pages=${all.size} trigger=${inputData.getString("trigger") ?: "start"}")
            val out = TtlPrefetch.run(all, cfg, store, LnktreeCache::fetch, { p -> RuntimeHarvest.urls(applicationContext, p, cfg.harvestTimeoutSeconds) }) { o ->
                android.util.Log.i("CloudMeWeb", o.line())
                android.util.Log.i("CloudMeWeb", "CACHE_PROGRESS done=${done.incrementAndGet()} pages=${all.size} ${store.report(o.page, all.first { it.id == o.page }.url).line()}")
            }
            LnktreeCache.logReport(applicationContext, all)
            return@withContext if (out.none { it.result == "failed" || it.result == "partial" || it.result == "kept-old" } || runAttemptCount >= 2) Result.success() else Result.retry()
        }
        // #1951 every page of every section, and every run re-checks the pages already stored (changed files are replaced)
        val todo = PrefetchPlanner.plan(all, cfg, store, refreshAll = true)
        val failed = LnktreePrefetch.run(todo, cfg, store, LnktreeCache::fetch)
        LnktreeCache.logReport(applicationContext, all)
        if (failed.isEmpty() || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}

object LnktreePrefetchScheduler {
    const val UNIQUE = "lnktree-prefetch"

    /** Never blocks start-up: enqueueing is all that happens here, the planning and the downloads run in the worker. */
    fun schedule(ctx: Context, trigger: String = "start") {
        val cfg = LnktreeCache.config
        if (!cfg.enabled || !cfg.prefetchEnabled) return
        LnktreeSw.install(ctx)
        val net = if (cfg.prefetchNetwork == "unmetered") NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<LnktreePrefetchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setInputData(androidx.work.workDataOf("trigger" to trigger))
            .setInitialDelay(cfg.prefetchStartDelaySeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS).build()
        // #1957 EVERY app start (and an app update) runs the job: a finished one is replaced, a running one is kept
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(UNIQUE, if (trigger == "update") ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
        // #1957 refresh_hours 0: no periodic re-check (the ttl_days rule at startup replaces it); one left from an earlier version is cancelled
        if (cfg.prefetchRefreshHours == 0) WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(UNIQUE + "-periodic")
        // #1951 and again every prefetch.refresh_hours, so a changed live page is stored before it is opened
        if (cfg.prefetchRefreshHours > 0) WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(UNIQUE + "-periodic", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<LnktreePrefetchWorker>(cfg.prefetchRefreshHours.toLong(), java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build()).build())
    }
}

/** #1957 an app update starts the startup job at once (the new version's cache_version and pages apply before the app is opened). */
class LnktreeUpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_MY_PACKAGE_REPLACED) LnktreePrefetchScheduler.schedule(ctx, "update")
    }
}

/** #1957 the site service workers inside Cloud Me (SwPolicy): blocked per WebView, answered from the app cache when one runs anyway. */
object LnktreeSw {
    @Volatile private var installed = false
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
                androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
                androidx.webkit.ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : androidx.webkit.ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        val r = SwPolicy.serve(request.url.toString(), request.method, LnktreeCache.config, LnktreeCache.store(app))
                        android.util.Log.i("CloudMeWeb", "SW_REQUEST url=${request.url} source=${r?.source ?: "sw-network"}")
                        return r?.let { WebResourceResponse(it.mime, it.encoding, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), it.body.inputStream()) }
                    }
                })
            }
        }.onFailure { android.util.Log.w("CloudMeWeb", "SW_CLIENT unavailable ${it.message}") }
    }

    /** Adds the document-start block to [view] for [pageUrl]'s origin (once per WebView). */
    fun block(view: WebView, pageUrl: String) {
        install(view.context)
        runCatching {
            val o = java.net.URI(pageUrl).let { "${it.scheme}://${it.host}" + if (it.port != -1) ":${it.port}" else "" }
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, SwPolicy.BLOCK_JS, setOf(o))
            }
        }.onFailure { android.util.Log.w("CloudMeWeb", "SW_BLOCK unavailable ${it.message}") }
    }
}

/**
 * #1957 the runtime pass: the page is loaded in an OFFSCREEN WebView (1080x2400, never attached), scrolled to its end so lazy images load,
 * and every URL it requested is returned (shouldInterceptRequest, plus performance entries, images and CSS backgrounds read by JS):
 * what the page's scripts load at run time and no static scan can see. Blocking, called off the main thread; [timeoutS] bounds it.
 */
object RuntimeHarvest {
    private const val COLLECT_JS = "(function(){var u={};function a(x){if(x&&/^https?:/.test(x))u[x]=1}" +
        "performance.getEntriesByType('resource').forEach(function(e){a(e.name)});" +
        "for(var i=0;i<document.images.length;i++){a(document.images[i].currentSrc||document.images[i].src)}" +
        "document.querySelectorAll('*').forEach(function(el){var b=getComputedStyle(el).backgroundImage;if(b&&b!='none'){(b.match(/url\\([\"']?([^\"')]+)/g)||[]).forEach(function(m){a(m.replace(/^url\\([\"']?/,''))})}});" +
        "document.querySelectorAll('video[poster],video source,video[src],source[srcset]').forEach(function(el){a(el.poster);a(el.src);a(el.getAttribute('srcset')&&el.getAttribute('srcset').split(' ')[0])});" +
        "return JSON.stringify(Object.keys(u))})()"
    private const val SCROLL_JS = "(function(){var y=0,h=function(){return document.documentElement.scrollHeight};var t=setInterval(function(){y+=900;window.scrollTo(0,y);if(y>h()){clearInterval(t);window.scrollTo(0,0)}},120)})()"

    fun urls(ctx: Context, p: LnktreePage, timeoutS: Int): Collection<String> {
        if (timeoutS <= 0) return emptyList()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val done = java.util.concurrent.CountDownLatch(1)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var wv: WebView? = null
        main.post {
            runCatching {
                val w = com.diegonmarcos.cloudlib.web.CloudWeb.create(ctx.applicationContext, com.diegonmarcos.cloudlib.web.WebProfile.APP_PAGE); wv = w
                LnktreeSw.block(w, p.url)
                w.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY))
                w.layout(0, 0, 1080, 2400)
                w.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        request?.url?.toString()?.takeIf { it.startsWith("http") }?.let { seen += it }
                        return null
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(SCROLL_JS, null)
                        view?.postDelayed({ view.evaluateJavascript(COLLECT_JS) { r ->
                            runCatching { val a = org.json.JSONArray(org.json.JSONTokener(r).nextValue() as String); for (i in 0 until a.length()) seen += a.getString(i) }
                            done.countDown()
                        } }, (timeoutS * 1000L / 2).coerceAtLeast(3000))
                    }
                }
                w.loadUrl(p.url)
            }.onFailure { done.countDown() }
        }
        done.await(timeoutS.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        main.post { wv?.let { it.stopLoading(); it.destroy() } }
        val out = seen.filter { it.startsWith("https://") && CacheKey.normalize(it) != CacheKey.normalize(p.url) && !it.contains("/script-service-worker.js") }
        android.util.Log.i("CloudMeWeb", "CACHE_HARVEST page=${p.id} urls=${out.size}")
        return out
    }
}

/** Activity-scoped: the WebViews of the pages seen so far (bounded LRU) and the scroll state of the native ones. */
class LnktreeHolder(max: Int) {
    private val webs = LruHolder<WebView>(max) { _, w -> w.stopLoading(); w.destroy() }
    private val lists = HashMap<String, LazyListState>()

    fun web(key: String, create: () -> WebView): WebView = webs.getOrPut(key, create)
    fun peek(key: String): WebView? = webs.get(key)
    /** Drops the entry WITHOUT destroying it (its AndroidView's onRelease does that). */
    fun forget(key: String) { webs.remove(key) }
    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }
    fun destroy() { webs.clear(); lists.clear() }
}

val LocalLnktreeHolder = androidx.compose.runtime.staticCompositionLocalOf<LnktreeHolder?> { null }
