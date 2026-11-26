package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Lnktree page cache (#1323): the pure part. Nothing here touches Android, so every rule is a JVM test.
 *
 * Approach: an APP-OWNED disk cache behind WebViewClient.shouldInterceptRequest, keyed by URL (HTML plus same-origin
 * subresources), instead of the WebView's own HTTP cache with LOAD_CACHE_ELSE_NETWORK. Why: the WebView cache obeys
 * the server's headers (GitHub Pages says max-age=600), has no age we can set, cannot be emptied for ONE page and
 * cannot be listed, so neither "valid for 7 days", nor "hard refresh deletes that page's files", nor "download
 * every page at start-up" could be stated, let alone tested. Files we own can.
 *
 * Stale policy: STALE-WHILE-REVALIDATE. An entry older than the TTL is still served at once (switching stays
 * instant, and it works offline) and is refetched in the background; a failed refetch keeps the old entry.
 *
 * Every number (TTL, concurrency, size cap, scope of the hard refresh ...) is build.json::ui.lnktree_cache.
 */

/** build.json::ui.lnktree_cache. An absent or empty declaration is "off": no default is invented here. */
data class LnktreeCacheConfig(
    val enabled: Boolean,
    /** The ui.sections id whose web pages are cached. */
    val section: String,
    val ttlMs: Long,
    val prefetchEnabled: Boolean,
    val prefetchConcurrency: Int,
    /** "unmetered" or "connected". */
    val prefetchNetwork: String,
    val prefetchMaxPages: Int,
    val maxSubresourcesPerPage: Int,
    val maxBytes: Long,
    val holderMaxWebViews: Int,
    /** "page" = the current page's files; "all" = every Lnktree cache file. */
    val hardRefreshScope: String,
    val excludePages: Set<String>,
    /** #1901 every ui.sections id whose web pages are cached (the embedded sites are spread over Pers, Prof and Proj); empty = [section] alone. */
    val sections: List<String> = emptyList(),
    /** #1938 the section pager's background queue (`queue` block): every subpage of the shown section fetched one at a time, at most [queueMaxRetained] kept (least recently viewed released first). */
    val queueEnabled: Boolean = false,
    val queueMaxRetained: Int = 0,
) {
    fun caches(pageId: String): Boolean = enabled && pageId !in excludePages

    /** The sections whose pages are cached: the declared `sections`, else the one `section`. */
    fun cachedSections(): List<String> = sections.ifEmpty { listOf(section) }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MB = 1024L * 1024

        val OFF = LnktreeCacheConfig(false, "", 0, false, 1, "connected", 0, 0, 0, 1, "page", emptySet())

        fun parse(o: JSONObject?): LnktreeCacheConfig {
            if (o == null || o.length() == 0) return OFF
            val p = o.optJSONObject("prefetch") ?: JSONObject()
            val ex = o.optJSONArray("exclude_pages")
            return LnktreeCacheConfig(
                enabled = o.optBoolean("enabled", false),
                section = o.optString("section"),
                sections = o.optJSONArray("sections")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                ttlMs = (o.optDouble("ttl_days", 0.0) * DAY_MS).toLong(),
                prefetchEnabled = p.optBoolean("enabled", false),
                prefetchConcurrency = p.optInt("concurrency", 1).coerceAtLeast(1),
                prefetchNetwork = p.optString("network", "connected"),
                prefetchMaxPages = p.optInt("max_pages", 0),
                maxSubresourcesPerPage = p.optInt("max_subresources_per_page", 0),
                maxBytes = (o.optDouble("max_cache_mb", 0.0) * MB).toLong(),
                holderMaxWebViews = o.optInt("holder_max_webviews", 1).coerceAtLeast(1),
                hardRefreshScope = o.optString("hard_refresh_scope", "page"),
                queueEnabled = o.optJSONObject("queue")?.optBoolean("enabled", false) ?: false,
                queueMaxRetained = o.optJSONObject("queue")?.optInt("max_retained_pages", 0)?.coerceAtLeast(1) ?: 0,
                excludePages = if (ex == null) emptySet() else (0 until ex.length()).map { ex.getString(it) }.toSet(),
            )
        }

        /** The baked declaration (BuildConfig.UI_LNKTREE_CACHE_B64); fail-soft to "cache off". */
        val baked: LnktreeCacheConfig by lazy {
            runCatching {
                parse(JSONObject(String(android.util.Base64.decode(BuildConfig.UI_LNKTREE_CACHE_B64, android.util.Base64.DEFAULT))))
            }.getOrDefault(OFF)
        }
    }
}

enum class CacheState { MISSING, FRESH, STALE }

object CacheTtl {
    /** Missing = no entry; Fresh = at most [ttlMs] old; older (or stamped in the future, a clock that moved) = Stale. */
    fun decide(fetchedAt: Long?, now: Long, ttlMs: Long): CacheState = when {
        fetchedAt == null -> CacheState.MISSING
        now - fetchedAt in 0..ttlMs -> CacheState.FRESH
        else -> CacheState.STALE
    }
}

object CacheKey {
    /** URL identity: scheme and host lower-cased, default port and fragment dropped; path and query kept as they are. */
    fun normalize(url: String): String {
        val u = URI(url.trim())
        val scheme = (u.scheme ?: "").lowercase()
        val port = u.port.takeIf { it != -1 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        val path = u.rawPath.orEmpty().ifEmpty { "/" }
        return buildString {
            append(scheme).append("://").append((u.host ?: "").lowercase())
            if (port != null) append(':').append(port)
            append(path)
            u.rawQuery?.let { append('?').append(it) }
        }
    }

    /** The file stem of a URL: sha-256 of the normalized URL, 32 hex chars. */
    fun of(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalize(url).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    /** A page id as a directory name. */
    fun dirName(pageId: String): String = pageId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "_" }
}

data class CacheEntry(val url: String, val mime: String, val encoding: String?, val status: Int, val fetchedAt: Long, val body: File)

/**
 * root/<page dir>/<key>.body + <key>.meta. The meta file is written LAST, so an entry exists only when both are whole.
 * Everything is scoped by page id: a hard refresh of one page can never touch another page's files.
 */
class LnktreeCacheStore(val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    fun pageDir(pageId: String) = File(root, CacheKey.dirName(pageId))
    fun bodyFile(pageId: String, url: String) = File(pageDir(pageId), CacheKey.of(url) + ".body")
    fun metaFile(pageId: String, url: String) = File(pageDir(pageId), CacheKey.of(url) + ".meta")

    fun get(pageId: String, url: String): CacheEntry? = runCatching {
        val meta = metaFile(pageId, url).takeIf { it.isFile } ?: return null
        val body = bodyFile(pageId, url).takeIf { it.isFile } ?: return null
        val p = Properties().apply { meta.inputStream().use { load(it) } }
        CacheEntry(p.getProperty("url"), p.getProperty("mime", "application/octet-stream"),
            p.getProperty("enc")?.takeIf { it.isNotEmpty() }, p.getProperty("status", "200").toInt(),
            p.getProperty("fetchedAt").toLong(), body)
    }.getOrNull()

    fun state(pageId: String, url: String, ttlMs: Long): CacheState =
        CacheTtl.decide(get(pageId, url)?.fetchedAt, clock(), ttlMs)

    fun put(pageId: String, url: String, mime: String, encoding: String?, status: Int, bytes: ByteArray) {
        val dir = pageDir(pageId).also { it.mkdirs() }
        val key = CacheKey.of(url)
        val uniq = "${Thread.currentThread().id}-${System.nanoTime()}"
        val tmp = File(dir, "$key.$uniq.tmp")
        tmp.writeBytes(bytes)
        val body = File(dir, "$key.body")
        body.delete()
        check(tmp.renameTo(body)) { "cannot move cache body into place" }
        val p = Properties().apply {
            setProperty("url", url); setProperty("mime", mime); setProperty("enc", encoding.orEmpty())
            setProperty("status", status.toString()); setProperty("fetchedAt", clock().toString())
        }
        val metaTmp = File(dir, "$key.$uniq.mtmp")
        metaTmp.outputStream().use { p.store(it, null) }
        val meta = File(dir, "$key.meta")
        meta.delete()
        check(metaTmp.renameTo(meta)) { "cannot move cache meta into place" }
    }

    /** Hard refresh of one page: deletes every file under its directory. Returns the number of files removed. */
    fun clearPage(pageId: String): Int = deleteTree(pageDir(pageId))

    /** Hard refresh of the whole Lnktree cache. */
    fun clearAll(): Int = (root.listFiles() ?: emptyArray()).sumOf { deleteTree(it) }

    /** Keeps the cache under [maxBytes] by deleting the oldest entries first. */
    fun trim(maxBytes: Long) {
        val files = (root.walkTopDown().filter { it.isFile && it.name.endsWith(".meta") }.toList())
        fun size(m: File) = m.length() + File(m.parentFile, m.nameWithoutExtension + ".body").length()
        var total = files.sumOf(::size)
        // #1911 the mtimes are read ONCE: concurrent page loads touch the files while they sort, and a comparator reading lastModified() live broke TimSort's contract (crashed Proj > Cloudy)
        for (m in files.map { it to it.lastModified() }.sortedBy { it.second }.map { it.first }) {
            if (total <= maxBytes) break
            total -= size(m)
            File(m.parentFile, m.nameWithoutExtension + ".body").delete(); m.delete()
        }
    }

    private fun deleteTree(f: File): Int {
        if (!f.exists()) return 0
        val n = f.walkBottomUp().count { it.isFile }
        f.deleteRecursively()
        return n
    }
}

/** One declared, cacheable Lnktree page: an https site embedded as a web page. */
data class LnktreePage(val id: String, val url: String)

object LnktreePages {
    /** The page's stack is a single `{"kind":"fragment","id":"web","url":"https://..."}` block. */
    fun fromStack(id: String, stack: JSONArray, cfg: LnktreeCacheConfig): LnktreePage? {
        if (stack.length() != 1 || !cfg.caches(id)) return null
        val b = stack.optJSONObject(0) ?: return null
        val url = b.optString("url")
        return if (b.optString("kind") == "fragment" && b.optString("id") == "web" && b.optString("inject").isEmpty() &&
            url.startsWith("https://")) LnktreePage(id, url) else null
    }
}

object PrefetchPlanner {
    /** Pages with no valid entry: missing first, then stale (refresh), at most [LnktreeCacheConfig.prefetchMaxPages]. Fresh pages are left alone. */
    fun plan(pages: List<LnktreePage>, cfg: LnktreeCacheConfig, store: LnktreeCacheStore): List<LnktreePage> {
        if (!cfg.enabled || !cfg.prefetchEnabled) return emptyList()
        val byState = pages.filter { cfg.caches(it.id) }.associateWith { store.state(it.id, it.url, cfg.ttlMs) }
        val missing = byState.filterValues { it == CacheState.MISSING }.keys
        val stale = byState.filterValues { it == CacheState.STALE }.keys
        return (missing + stale).take(cfg.prefetchMaxPages)
    }
}

object RefScan {
    private val tag = Regex("<(script|link|img|source)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attr = Regex("\\b(src|href|srcset)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val rel = Regex("\\brel\\s*=\\s*[\"']?([^\"'>]*)", RegexOption.IGNORE_CASE)
    private val cssUrl = Regex("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)|@import\\s+['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)

    /** The CSS, JS and images an HTML page references, same-origin as [base], absolute, de-duplicated, in document order. */
    fun html(html: String, base: String): List<String> {
        val raw = ArrayList<String>()
        for (t in tag.findAll(html)) {
            val text = t.value
            val name = t.groupValues[1].lowercase()
            if (name == "link" && rel.find(text)?.groupValues?.get(1)?.lowercase()
                    ?.split(' ')?.none { it in setOf("stylesheet", "icon", "preload", "modulepreload") } != false) continue
            for (a in attr.findAll(text)) {
                val v = a.groupValues[2].ifEmpty { a.groupValues[3] }
                if (a.groupValues[1].lowercase() == "srcset") v.split(',').forEach { raw += it.trim().substringBefore(' ') } else raw += v
            }
        }
        return resolve(raw, base)
    }

    /** The files a stylesheet pulls in (`url(...)`, `@import`), same-origin as [cssUrl]. */
    fun css(css: String, cssUrl: String): List<String> =
        resolve(this.cssUrl.findAll(css).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList(), cssUrl)

    private fun resolve(raw: List<String>, base: String): List<String> {
        val b = runCatching { URI(base) }.getOrNull() ?: return emptyList()
        return raw.asSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("data:") && !it.startsWith("#") && !it.startsWith("javascript:") }
            .mapNotNull { runCatching { b.resolve(it.replace(" ", "%20")) }.getOrNull() }
            .filter { (it.scheme == "https" || it.scheme == "http") && it.host.equals(b.host, true) && it.port == b.port }
            .map { URI(it.scheme, it.authority, it.path, it.query, null).toString() }
            .distinct().toList()
    }
}

/** What one network fetch returned; null from the fetcher = the network said no. */
class Fetched(val mime: String, val encoding: String?, val status: Int, val body: ByteArray)

/** Fills the cache for pages: the HTML first, then the CSS/JS/images it names (and what those stylesheets name). */
object LnktreePrefetch {
    /** Bounded: at most [LnktreeCacheConfig.prefetchConcurrency] pages are in flight. Returns the ids that could not be fetched. */
    fun run(pages: List<LnktreePage>, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?): List<String> {
        if (pages.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(cfg.prefetchConcurrency)
        try {
            val jobs = pages.map { p -> p to pool.submit(Callable { page(p, cfg, store, fetch) }) }
            return jobs.filter { (_, f) -> !runCatching { f.get() }.getOrDefault(false) }.map { it.first.id }
        } finally {
            pool.shutdown()
            store.trim(cfg.maxBytes)
        }
    }

    private fun page(p: LnktreePage, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?): Boolean {
        val main = fetch(p.url)?.takeIf { it.status == 200 } ?: return false
        store.put(p.id, p.url, main.mime, main.encoding, main.status, main.body)
        var budget = cfg.maxSubresourcesPerPage
        val seen = HashSet<String>().apply { add(CacheKey.normalize(p.url)) }
        val queue = ArrayDeque(RefScan.html(String(main.body, Charsets.UTF_8), p.url))
        while (queue.isNotEmpty() && budget > 0) {
            val u = queue.removeFirst()
            if (!seen.add(CacheKey.normalize(u))) continue
            val r = fetch(u)?.takeIf { it.status == 200 } ?: continue
            budget--
            store.put(p.id, u, r.mime, r.encoding, r.status, r.body)
            if (r.mime.contains("css")) queue.addAll(RefScan.css(String(r.body, Charsets.UTF_8), u))
        }
        return true
    }
}

/** A cached body ready to hand to the WebView. */
class Served(val mime: String, val encoding: String?, val status: Int, val body: File)

/** shouldInterceptRequest's decision, with the network and the background refresh injected. */
object LnktreeResolver {
    /**
     * Cache hit (fresh or stale) -> serve the file; stale also calls [revalidate] (the caller refetches in the background).
     * Miss -> [fetch], store, serve. Network failure on a miss -> null (the WebView tries the network itself).
     */
    fun serve(
        pageId: String, url: String, cfg: LnktreeCacheConfig, store: LnktreeCacheStore,
        fetch: (String) -> Fetched?, revalidate: (String) -> Unit,
    ): Served? {
        store.get(pageId, url)?.let { e ->
            if (store.state(pageId, url, cfg.ttlMs) == CacheState.STALE) revalidate(url)
            return Served(e.mime, e.encoding, e.status, e.body)
        }
        val r = fetch(url)?.takeIf { it.status == 200 } ?: return null
        store.put(pageId, url, r.mime, r.encoding, r.status, r.body)
        store.trim(cfg.maxBytes)
        return store.get(pageId, url)?.let { Served(it.mime, it.encoding, it.status, it.body) }
    }

    /** Hard refresh: what to delete for [pageId] under the declared scope. Returns files removed. */
    fun hardRefresh(pageId: String, cfg: LnktreeCacheConfig, store: LnktreeCacheStore): Int =
        if (cfg.hardRefreshScope == "all") store.clearAll() else store.clearPage(pageId)
}

/** A bounded most-recently-used holder: the oldest entry is [onEvict]ed when [max] is exceeded. */
class LruHolder<T : Any>(private val max: Int, private val onEvict: (String, T) -> Unit) {
    private val map = LinkedHashMap<String, T>(16, 0.75f, true)
    val size: Int get() = map.size
    val keys: List<String> get() = map.keys.toList()

    fun get(key: String): T? = map[key]

    fun getOrPut(key: String, create: () -> T): T {
        map[key]?.let { return it }
        val v = create()
        map[key] = v
        while (map.size > max) {
            val eldest = map.entries.first { it.key != key }
            map.remove(eldest.key)
            onEvict(eldest.key, eldest.value)
        }
        return v
    }

    fun remove(key: String): T? = map.remove(key)
    fun clear() { map.entries.toList().forEach { onEvict(it.key, it.value) }; map.clear() }
}
