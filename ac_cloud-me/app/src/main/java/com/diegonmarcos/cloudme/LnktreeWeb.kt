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
        store ?: synchronized(this) { store ?: LnktreeCacheStore(File(ctx.applicationContext.cacheDir, "lnktree-cache")).also { store = it } }

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
    fun fetch(url: String): Fetched? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 20_000; c.useCaches = false; c.instanceFollowRedirects = true
            c.setRequestProperty("Cache-Control", "no-cache")
            val ct = c.contentType.orEmpty()
            val mime = ct.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val enc = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            val body = (if (c.responseCode == 200) c.inputStream else null)?.use { it.readBytes() }
            if (body == null) null else Fetched(mime, enc, 200, body)
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
        val served = LnktreeResolver.serve(pageId, u, cfg, s, ::fetch) { stale -> revalidate(s, pageId, stale) } ?: return null
        return WebResourceResponse(served.mime, served.encoding, served.status, "OK", mapOf("Access-Control-Allow-Origin" to "*"), served.body.inputStream())
    }

    private fun revalidate(s: LnktreeCacheStore, pageId: String, url: String) {
        if (!refreshing.add("$pageId $url")) return
        background.execute {
            try { fetch(url)?.let { s.put(pageId, url, it.mime, it.encoding, it.status, it.body) } }
            finally { refreshing.remove("$pageId $url") }
        }
    }

    /** Hard refresh: deletes the cache files (scope from build.json), then empties the WebView's own cache. Call off the UI thread for the deletion. */
    fun hardRefresh(ctx: Context, pageId: String): Int = LnktreeResolver.hardRefresh(pageId, config, store(ctx))
}

/** Background fill of the pages that have no valid entry; enqueued once per app start. */
class LnktreePrefetchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cfg = LnktreeCache.config
        val store = LnktreeCache.store(applicationContext)
        val todo = PrefetchPlanner.plan(LnktreeCache.pages(applicationContext, cfg), cfg, store)
        val failed = LnktreePrefetch.run(todo, cfg, store, LnktreeCache::fetch)
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
