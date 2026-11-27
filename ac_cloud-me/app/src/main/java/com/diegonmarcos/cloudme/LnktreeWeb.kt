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
        store ?: synchronized(this) { store ?: LnktreeCacheStore(File(ctx.applicationContext.cacheDir, "lnktree-cache")).also { st -> st.onWipe = { w -> android.util.Log.i("CloudMeWeb", "CACHE_WIPE $w at ${Throwable().stackTrace.drop(2).take(7).joinToString(" < ") { it.className.substringAfterLast('.') + '.' + it.methodName }}") }; store = st } }

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
        android.util.Log.i("CloudMeWeb", "CACHE_TOTAL pages=${reps.size} complete=${reps.count { it.complete }} bytes=${reps.sumOf { it.bytes }} max_bytes=${config.maxBytes}")
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
            val body = (if (code == 200) c.inputStream else null)?.use { it.readBytes() }
            if (body != null) Fetched(mime, enc, 200, body)
            else if (code == 404 || code == 410) Fetched(mime, enc, code, ByteArray(0))   // #1947 a definite "not there" (a tombstone); every other failure stays null
            else null
        } finally { c.disconnect() }
    }.getOrNull()

    /** shouldInterceptRequest for [pageId] (declared at [pageUrl]): same-origin GETs come from the cache; the rest is the WebView's own. */
    fun intercept(ctx: Context, pageId: String, pageUrl: String, req: WebResourceRequest): WebResourceResponse? {
        val cfg = config
        if (!cfg.caches(pageId) || req.method != "GET" || req.requestHeaders.containsKey("Range")) return null
        val u = req.url.toString()
        val base = java.net.URI(pageUrl)
        val target = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        if (target.scheme != "https" || !target.host.equals(base.host, true) || target.port != base.port) return null
        val s = store(ctx)
        if (CacheKey.normalize(u) == CacheKey.normalize(pageUrl)) s.keep.add(CacheKey.dirName(pageId))   // #1947 a page the user opened is trimmed last
        val isMain = CacheKey.normalize(u) == CacheKey.normalize(pageUrl)
        val t = cfg.interceptTimeoutMs
        val served = LnktreeResolver.serve(pageId, u, cfg, s, { if (t > 0) fetch(it, t, t) else fetch(it) }, { stale ->
            if (isMain && cfg.cacheThenLive && s.complete(pageId, u)) swapPending.add(pageId)
            revalidate(s, pageId, stale, pageUrl)
        }, pageUrl)
        if (CacheKey.normalize(u) == CacheKey.normalize(pageUrl)) {
            // #1947 the walk reads this: where the main frame came from; the offline note reads offlineSince
            android.util.Log.i("CloudMeWeb", "MAINFRAME_SOURCE page=$pageId source=${served?.source ?: "webview-network"} strategy=${cfg.strategy}")
            if (served?.source == "offline-cache") offlineSince[pageId] = served.fetchedAt else offlineSince.remove(pageId)
        }
        if (served == null) return null
        return WebResourceResponse(served.mime, served.encoding, served.status, if (served.status == 200) "OK" else "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), served.body.inputStream())
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
    fun refill(ctx: Context, pageId: String, pageUrl: String) = revalidate(store(ctx), pageId, pageUrl, pageUrl)
}

/** Background fill of the pages that have no valid entry; enqueued once per app start. */
class LnktreePrefetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cfg = LnktreeCache.config
        val store = LnktreeCache.store(applicationContext)
        val all = LnktreeCache.pages(applicationContext, cfg)
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
    fun schedule(ctx: Context) {
        val cfg = LnktreeCache.config
        if (!cfg.enabled || !cfg.prefetchEnabled) return
        val net = if (cfg.prefetchNetwork == "unmetered") NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<LnktreePrefetchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build()).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        // #1951 and again every prefetch.refresh_hours, so a changed live page is stored before it is opened
        if (cfg.prefetchRefreshHours > 0) WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(UNIQUE + "-periodic", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<LnktreePrefetchWorker>(cfg.prefetchRefreshHours.toLong(), java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build()).build())
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
