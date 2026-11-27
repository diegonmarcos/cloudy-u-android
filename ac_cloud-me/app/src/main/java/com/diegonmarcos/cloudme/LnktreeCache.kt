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
    /** #1947 "network_first" (the live page is shown; the cache is the offline fallback) or "stale_while_revalidate" (the cache is shown at once and refreshed in the background). */
    val strategy: String = STALE_WHILE_REVALIDATE,
    /** #1951 the longest a request is held on the app's own network fetch (network_first); 0 = the fetch's default. cache_then_live never holds a request on the network. */
    val interceptTimeoutMs: Int = 0,
    /** #1951 the periodic prefetch (WorkManager); 0 = at app start only. */
    val prefetchRefreshHours: Int = 0,
    /** #1951 the app-start prefetch waits this long, so the first page opened paints before the downloads compete with it. */
    val prefetchStartDelaySeconds: Int = 0,
    /** #1957 cross-origin subresources (CDN images, media, scripts) are planned and stored too, not only the page's own origin. */
    val crossOrigin: Boolean = false,
    /** #1957 the absolute media/asset URLs named inside the page's scripts and data files (data-*.json.js) are planned too. */
    val scriptUrls: Boolean = false,
    /** #1957 subresources fetched in parallel per page (1 = one at a time). */
    val subresourceConcurrency: Int = 1,
    /** #1957 videos: the largest single file stored and the most all videos together may take (0 = no cap); images are always stored. */
    val videoMaxFileBytes: Long = 0,
    val videoMaxTotalBytes: Long = 0,
    /** #1957 a different value than the one stored with the cache wipes every copy once (copies taken before a site fix). */
    val cacheVersion: String = "",
    /** #1957 the offscreen WebView pass per page that records every URL the page loads at run time (scripts, lazy images); 0 = off. */
    val harvestTimeoutSeconds: Int = 0,
    /** #1957 the most media files (images, videos) one page's data-file scan may plan; the rest are known-missing (reason plan-cap). 0 = no cap. */
    val maxScriptMediaPerPage: Int = 0,
    /** #1957 the longest one download may take, whole body included (a trickling or endless response is dropped); 0 = no deadline. */
    val fetchDeadlineMs: Long = 0,
    /** #1957 the largest body any one download may read into memory (a video with no Content-Length is cut off here, never read whole); 0 = no cap. */
    val maxFileBytes: Long = 0,
) {
    val networkFirst: Boolean get() = strategy == NETWORK_FIRST
    /** #1951 a complete cached copy paints at once and the live page is fetched in parallel (swapped in when its hash differs); a page with no complete copy loads straight through the WebView's own network. */
    val cacheThenLive: Boolean get() = strategy == CACHE_THEN_LIVE
    /** #1957 cache_ttl (the Cloud Me default): a cached copy is shown at once and never compared live while it is younger than ttl_days; the startup job refetches a copy older than that and swaps it in atomically once the new copy is complete. */
    val cacheTtl: Boolean get() = strategy == CACHE_TTL
    /** #1947 the pager's release of a page past max_retained_pages drops its cached copy only when the cache is the PRIMARY source; under network_first the copy is the offline fallback and stays (max_cache_mb bounds it). */
    val releaseDropsCache: Boolean get() = !networkFirst && !cacheThenLive && !cacheTtl

    fun caches(pageId: String): Boolean = enabled && pageId !in excludePages

    /** The sections whose pages are cached: the declared `sections`, else the one `section`. */
    fun cachedSections(): List<String> = sections.ifEmpty { listOf(section) }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val NETWORK_FIRST = "network_first"
        const val CACHE_THEN_LIVE = "cache_then_live"
        const val CACHE_TTL = "cache_ttl"
        const val STALE_WHILE_REVALIDATE = "stale_while_revalidate"
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
                interceptTimeoutMs = o.optInt("intercept_timeout_ms", 0).coerceAtLeast(0),
                prefetchRefreshHours = p.optInt("refresh_hours", 0).coerceAtLeast(0),
                prefetchStartDelaySeconds = p.optInt("start_delay_s", 0).coerceAtLeast(0),
                strategy = o.optString("strategy", STALE_WHILE_REVALIDATE).ifEmpty { STALE_WHILE_REVALIDATE },
                queueEnabled = o.optJSONObject("queue")?.optBoolean("enabled", false) ?: false,
                queueMaxRetained = o.optJSONObject("queue")?.optInt("max_retained_pages", 0)?.coerceAtLeast(1) ?: 0,
                crossOrigin = o.optBoolean("cross_origin", false),
                scriptUrls = p.optBoolean("script_urls", false),
                subresourceConcurrency = p.optInt("subresource_concurrency", 1).coerceAtLeast(1),
                videoMaxFileBytes = ((o.optJSONObject("video")?.optDouble("max_file_mb", 0.0) ?: 0.0) * MB).toLong(),
                videoMaxTotalBytes = ((o.optJSONObject("video")?.optDouble("max_total_mb", 0.0) ?: 0.0) * MB).toLong(),
                cacheVersion = o.optString("cache_version"),
                harvestTimeoutSeconds = p.optInt("harvest_timeout_s", 0).coerceAtLeast(0),
                maxScriptMediaPerPage = p.optInt("max_script_media_per_page", 0).coerceAtLeast(0),
                fetchDeadlineMs = (p.optDouble("fetch_deadline_s", 0.0) * 1000).toLong().coerceAtLeast(0),
                maxFileBytes = (p.optDouble("max_file_mb", 0.0) * MB).toLong().coerceAtLeast(0),
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
class LnktreeCacheStore(val root: File, private val clock: () -> Long = System::currentTimeMillis, private val parent: LnktreeCacheStore? = null, private val isShared: Boolean = false, private val staged: Boolean = false) {
    fun pageDir(pageId: String) = File(root, CacheKey.dirName(pageId))

    /**
     * #1957 the SHARED store (root/.shared): a file of another origin than its page (CDN images, media, libraries) is stored ONCE, keyed by
     * URL, whichever pages name it; a page's [get] falls back to it, its manifest lists it, size caps and the trim count it once. A fresh
     * shared file is reused by every page (the fill skips it), never downloaded again per page. Staging uses the live store's shared files.
     */
    val shared: LnktreeCacheStore? by lazy { if (isShared) null else if (staged) LnktreeCacheStore(File(root, SHARED_DIR), clock, this, true) else parent?.shared ?: LnktreeCacheStore(File(root, SHARED_DIR), clock, this, true) }
    private val hosts = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The page's own host: files of any other host go to the shared store. */
    fun registerPage(pageId: String, url: String) { runCatching { URI(url).host?.lowercase() }.getOrNull()?.let { hosts[CacheKey.dirName(pageId)] = it } }
    fun sharedFor(pageId: String, url: String): Boolean = shared != null && hosts[CacheKey.dirName(pageId)]?.let { h -> runCatching { URI(url).host?.lowercase() }.getOrNull()?.let { it != h } } == true

    /** #1957 bytes on disk (shared files once, staging included) and video bytes: the in-job caps read these while the job runs. */
    class Budget(val used: java.util.concurrent.atomic.AtomicLong, val video: java.util.concurrent.atomic.AtomicLong)
    val budget: Budget by lazy { parent?.budget ?: Budget(java.util.concurrent.atomic.AtomicLong(totalBytes(true)), java.util.concurrent.atomic.AtomicLong(countVideo())) }
    /** Every entry's bytes under root, each shared file once; [withStaging] adds a refetch's staged copy. */
    fun totalBytes(withStaging: Boolean = false): Long = root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") || it.name == SHARED_DIR || (withStaging && it.name == ".staging") }
        .filter { it.isFile && (it.name.endsWith(".meta") || it.name.endsWith(".body")) }.sumOf { it.length() }
    private fun countVideo(): Long = root.walkTopDown().filter { it.isFile && it.name.endsWith(".meta") }.sumOf { m ->
        runCatching { val pr = Properties().apply { m.inputStream().use { load(it) } }; if (RefScan.isVideo(pr.getProperty("url", ""), pr.getProperty("mime", ""))) File(m.parentFile, m.nameWithoutExtension + ".body").length() else 0L }.getOrDefault(0L)
    }
    /** After deletions the used-bytes counter is read from disk again. */
    fun recount() { val b = budget; var main = this; while (main.parent != null) main = main.parent!!; b.used.set(main.totalBytes(true)) }   // always the top store (a staged shared store's parent is its staging)
    fun bodyFile(pageId: String, url: String) = File(pageDir(pageId), CacheKey.of(url) + ".body")
    fun metaFile(pageId: String, url: String) = File(pageDir(pageId), CacheKey.of(url) + ".meta")

    fun get(pageId: String, url: String): CacheEntry? {
        val own = getOwn(pageId, url)
        if (own != null && own.status == 200) return own
        // #1957 the shared copy (a page-local known-missing mark never hides a stored shared file)
        return shared?.getOwn(SHARED_ID, url)?.takeIf { it.status == 200 }
            ?: (if (staged) parent?.shared?.getOwn(SHARED_ID, url)?.takeIf { it.status == 200 } else null)   // a refetch reuses the live shared copy
            ?: own
    }

    /** #1957 why a file is a known-missing mark: video-file-cap, video-total-cap, cache-cap, plan-cap (null = stored or a 4xx). */
    fun reason(pageId: String, url: String): String? = runCatching { Properties().apply { metaFile(pageId, url).inputStream().use { load(it) } }.getProperty("reason") }.getOrNull()

    fun getOwn(pageId: String, url: String): CacheEntry? = runCatching {
        val meta = metaFile(pageId, url).takeIf { it.isFile } ?: return null
        val body = bodyFile(pageId, url).takeIf { it.isFile } ?: return null
        val p = Properties().apply { meta.inputStream().use { load(it) } }
        CacheEntry(p.getProperty("url"), p.getProperty("mime", "application/octet-stream"),
            p.getProperty("enc")?.takeIf { it.isNotEmpty() }, p.getProperty("status", "200").toInt(),
            p.getProperty("fetchedAt").toLong(), body)
    }.getOrNull()

    companion object { const val SHARED_DIR = ".shared"; const val SHARED_ID = "all" }

    /**
     * #1947 a cached HTML page is COMPLETE only when every stylesheet and script it names is stored too (a 404 tombstone counts: the file
     * is known to be absent). HTML cached without its CSS/JS is what drew Personal unstyled on a slow network. Non-HTML entries are complete.
     */
    fun paintable(pageId: String, url: String): Boolean {
        val e = get(pageId, url) ?: return false
        if (!e.mime.contains("html", ignoreCase = true)) return true
        return RefScan.critical(runCatching { String(e.body.readBytes(), Charsets.UTF_8) }.getOrDefault(""), url).all { get(pageId, it) != null }
    }

    /**
     * #1957 COMPLETE = paintable (#1947: HTML + CSS/JS) AND every file the page's manifest names is stored: images, media, data files and
     * the cross-origin ones, those its scripts load at run time included. A video over the declared cap and a definite 404 are stored as
     * tombstones, so they count as known. Non-HTML entries are complete.
     */
    fun complete(pageId: String, url: String): Boolean = paintable(pageId, url) && missing(pageId, url).isEmpty()

    /** #1957 the manifest's files that are not stored yet (the reason a page is complete=no). */
    fun missing(pageId: String, url: String): List<String> = refs(pageId).filter { CacheKey.normalize(it) != CacheKey.normalize(url) && get(pageId, it) == null }

    /** #1957 the page's manifest: every URL the last fill planned (static refs, script-named URLs, the runtime pass, misses seen while open). */
    fun refsFile(pageId: String) = File(pageDir(pageId), "_refs.txt")
    fun refs(pageId: String): List<String> = runCatching { refsFile(pageId).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    @Synchronized fun writeRefs(pageId: String, urls: Collection<String>) {
        val f = refsFile(pageId); f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "_refs.${System.nanoTime()}.tmp"); tmp.writeText(urls.distinct().joinToString("\n")); f.delete(); tmp.renameTo(f)
    }
    /** #1957 a URL the open page asked for that the copy does not hold: added to the manifest, so the page is incomplete until the next fill stores it. */
    @Synchronized fun addRef(pageId: String, url: String) { val r = refs(pageId); if (r.none { CacheKey.normalize(it) == CacheKey.normalize(url) }) writeRefs(pageId, r + url) }

    /** #1957 any page's stored copy of [url] (the service worker's requests carry no page): the first page directory that holds it. */
    fun findAny(url: String): Pair<String, CacheEntry>? = (root.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }
        .firstNotNullOfOrNull { d -> get(d.name, url)?.let { d.name to it } } ?: shared?.getOwn(SHARED_ID, url)?.let { SHARED_DIR to it }

    /** #1957 the cache's declared version (build.json cache_version): a different one wipes every copy once. Returns true when it wiped. */
    fun ensureVersion(version: String): Boolean {
        if (version.isEmpty()) return false
        val f = File(root, ".version")
        if (runCatching { f.readText().trim() }.getOrNull() == version) return false
        val had = (root.listFiles() ?: emptyArray()).any { it.isDirectory }
        if (had) clearAll()
        root.mkdirs(); f.writeText(version)
        return had
    }

    /** #1957 the staging area a refetch fills: a store of its own; [commit] swaps a page's staged copy in place of the old one. */
    /** #1957 bytes of the videos stored (the total video cap), counted once from disk. */
    val videoBytes: java.util.concurrent.atomic.AtomicLong get() = budget.video

    /** #1957 one page's staging area: its own files AND the shared files it refetched; nothing reaches the live store before [commit]. */
    fun stagingFor(pageId: String): LnktreeCacheStore = LnktreeCacheStore(File(File(root, ".staging"), CacheKey.dirName(pageId)), clock, this, false, true)

    /** #1957 atomic replace: the staged shared files move into the live shared store, the old page directory moves aside, the staged one takes its name. */
    @Synchronized fun commit(pageId: String, st: LnktreeCacheStore = stagingFor(pageId)): Boolean {
        val staged = st.pageDir(pageId).takeIf { it.isDirectory } ?: return false
        val liveShared = shared?.pageDir(SHARED_ID)?.also { it.mkdirs() }
        st.shared?.pageDir(SHARED_ID)?.listFiles()?.filter { it.name.endsWith(".body") }?.forEach { b ->
            val m = File(b.parentFile, b.nameWithoutExtension + ".meta"); if (liveShared == null || !m.isFile) return@forEach
            File(liveShared, b.name).delete(); b.renameTo(File(liveShared, b.name)); File(liveShared, m.name).delete(); m.renameTo(File(liveShared, m.name))
        }
        val live = pageDir(pageId); val old = File(File(root, ".old").also { it.mkdirs() }, live.name + "." + System.nanoTime())
        if (live.exists() && !live.renameTo(old)) return false
        if (!staged.renameTo(live)) { old.renameTo(live); return false }
        old.deleteRecursively(); st.root.deleteRecursively(); recount()
        return true
    }

    /** #1951 what the page holds: entries, bytes and whether it is complete (the line the walk and the owner read). */
    data class PageReport(val pageId: String, val entries: Int, val bytes: Long, val complete: Boolean) {
        fun line() = "CACHE_PAGE page=$pageId entries=$entries bytes=$bytes complete=${if (complete) "yes" else "no"}"
    }
    fun report(pageId: String, url: String): PageReport {
        val metas = (pageDir(pageId).listFiles() ?: emptyArray()).filter { it.name.endsWith(".meta") }
        // #1957 the page's share: its own files plus the shared files its manifest names (a shared file shows in every page that uses it; CACHE_TOTAL counts it once)
        val sh = shared?.let { sh -> refs(pageId).mapNotNull { u -> sh.metaFile(SHARED_ID, u).takeIf { it.isFile } } }.orEmpty()
        val bytes = (metas + sh).sumOf { it.length() + File(it.parentFile, it.nameWithoutExtension + ".body").length() }
        return PageReport(pageId, metas.size + sh.size, bytes, complete(pageId, url))
    }

    fun state(pageId: String, url: String, ttlMs: Long): CacheState =
        CacheTtl.decide(get(pageId, url)?.fetchedAt, clock(), ttlMs)

    fun put(pageId: String, url: String, mime: String, encoding: String?, status: Int, bytes: ByteArray, reason: String? = null) {
        val budget = budget   // read from disk BEFORE this write (a lazy first read after it counted the file twice)
        if (sharedFor(pageId, url) && status == 200) {
            shared!!.put(SHARED_ID, url, mime, encoding, status, bytes)
            metaFile(pageId, url).delete(); bodyFile(pageId, url).delete()   // a page-local mark it replaces
            return
        }
        val dir = pageDir(pageId).also { it.mkdirs() }
        val before = bodyFile(pageId, url).length() + metaFile(pageId, url).length()
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
            if (reason != null) setProperty("reason", reason)
        }
        val metaTmp = File(dir, "$key.$uniq.mtmp")
        metaTmp.outputStream().use { p.store(it, null) }
        val meta = File(dir, "$key.meta")
        meta.delete()
        check(metaTmp.renameTo(meta)) { "cannot move cache meta into place" }
        budget.used.addAndGet(body.length() + meta.length() - before)
    }

    /** Hard refresh of one page: deletes every file under its directory. Returns the number of files removed. */
    /** #1947 diagnostics: told what was wiped and why (the Android side logs it with the call stack). */
    /** #1947 dir names of the pages opened this session: [trim] evicts them last. */
    val keep: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var onWipe: ((String) -> Unit)? = null
    fun clearPage(pageId: String): Int { onWipe?.invoke("clearPage $pageId"); return deleteTree(pageDir(pageId)).also { recount() } }

    /** #1957 the hard refresh of a page also drops the shared files its manifest names, so they are fetched fresh too. */
    fun clearPageWithShared(pageId: String): Int {
        val sh = shared ?: return clearPage(pageId)
        val n = refs(pageId).sumOf { u -> listOf(sh.metaFile(SHARED_ID, u), sh.bodyFile(SHARED_ID, u)).count { it.delete() } }
        return n + clearPage(pageId)
    }

    /** Hard refresh of the whole Lnktree cache. */
    fun clearAll(): Int { onWipe?.invoke("clearAll"); return (root.listFiles() ?: emptyArray()).filter { it.name != ".version" }.sumOf { deleteTree(it) }.also { recount() } }

    /**
     * Keeps the cache under [maxBytes] by deleting the oldest entries first. #1947 images, fonts and data go BEFORE any page HTML, stylesheet or script:
     * the offline copy of a page is its HTML + CSS/JS, so a section's worth of images must never evict the page that was opened (the Personal copy was empty after the pager fetched its siblings).
     */
    @Synchronized fun trim(maxBytes: Long) {
        val files = (root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") || it.name == SHARED_DIR }.filter { it.isFile && it.name.endsWith(".meta") }.toList())
        fun size(m: File) = m.length() + File(m.parentFile, m.nameWithoutExtension + ".body").length()
        // #1957 the eviction class: videos go first, then other files, then images (always cached), then page HTML/CSS/JS
        fun critical(m: File): Int = runCatching {
            val pr = Properties().apply { m.inputStream().use { load(it) } }
            val mime = pr.getProperty("mime", "").lowercase()
            when {
                mime.contains("html") || mime.contains("css") || mime.contains("javascript") || mime.contains("ecmascript") -> 3
                RefScan.isVideo(pr.getProperty("url", ""), mime) -> 0
                mime.startsWith("image/") -> 2
                else -> 1
            }
        }.getOrDefault(1)
        var total = files.sumOf(::size)
        // #1911 the mtimes are read ONCE: concurrent page loads touch the files while they sort, and a comparator reading lastModified() live broke TimSort's contract (crashed Proj > Cloudy)
        // pages the user opened this session (dir names) are evicted last of all: the cache over its cap (heavy pages x 12) used to wipe the page just opened, the OLDEST one
        for (m in files.map { Triple(it, (if (it.parentFile?.name in keep) 10 else 0) + critical(it), it.lastModified()) }.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }) {
            if (total <= maxBytes) break
            total -= size(m)
            onWipe?.invoke("trim ${m.parentFile?.name} total=$total max=$maxBytes")
            File(m.parentFile, m.nameWithoutExtension + ".body").delete(); m.delete()
        }
        recount()
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
    fun plan(pages: List<LnktreePage>, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, refreshAll: Boolean = false): List<LnktreePage> {
        if (!cfg.enabled || !cfg.prefetchEnabled) return emptyList()
        val byState = pages.filter { cfg.caches(it.id) }.associateWith { store.state(it.id, it.url, cfg.ttlMs) }
        // #1947 a fresh page whose CSS/JS never arrived is not valid: it is planned again
        val incomplete = byState.filter { (p, st) -> st == CacheState.FRESH && !store.complete(p.id, p.url) }.keys
        val missing = byState.filterValues { it == CacheState.MISSING }.keys + incomplete
        val stale = byState.filterValues { it == CacheState.STALE }.keys
        // #1951 every page is covered: max_pages 0 = all; refreshAll (app start, the periodic run) re-checks the fresh pages too (their changed files are replaced)
        val rest = if (refreshAll) byState.filterValues { it == CacheState.FRESH }.keys - incomplete else emptySet()
        val all = (missing + stale + rest).toList()
        return if (cfg.prefetchMaxPages > 0) all.take(cfg.prefetchMaxPages) else all
    }
}

object RefScan {
    private val tag = Regex("<(script|link|img|source)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attr = Regex("\\b(src|href|srcset)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val rel = Regex("\\brel\\s*=\\s*[\"']?([^\"'>]*)", RegexOption.IGNORE_CASE)
    private val cssUrl = Regex("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)|@import\\s+['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)

    /** The CSS, JS and images an HTML page references, same-origin as [base], absolute, de-duplicated, in document order. */
    fun html(html: String, base: String, crossOrigin: Boolean = false): List<String> {
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
        return resolve(raw, base, crossOrigin)
    }

    /** #1947 the render-critical subset of [html]: stylesheets and scripts (what the page cannot be drawn or run without). */
    fun critical(html: String, base: String): List<String> = html(html, base).filter {
        val path = runCatching { URI(it).path.orEmpty().lowercase() }.getOrDefault("")
        path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".mjs")
    }

    /** The files a stylesheet pulls in (`url(...)`, `@import`), same-origin as [cssUrl]. */
    fun css(css: String, cssUrl: String, crossOrigin: Boolean = false): List<String> =
        resolve(this.cssUrl.findAll(css).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList(), cssUrl, crossOrigin)

    private val absUrl = Regex("""https?:(?:\\?/){2}(?:[^\s"'`<>()\\]|\\/)+?\.(?:jpe?g|png|webp|gif|svg|avif|ico|mp4|webm|m4v|mov|mp3|ogg|woff2?|ttf|css|m?js)(?:\?(?:[^\s"'`<>()\\]|\\/)*)?(?=[\s"'`<>()\\,;]|$)""", RegexOption.IGNORE_CASE)

    /** #1957 the absolute asset URLs (images, media, fonts, scripts, styles) named inside a script or data file: what the page's own code loads at run time. */
    fun scriptUrls(text: String, base: String): List<String> =
        resolve(absUrl.findAll(text).map { it.value.replace("\\/", "/") }.toList(), base, true)

    /** #1957 an image or a video by its path (what the data-file scan cap counts). */
    fun isMedia(url: String): Boolean = isVideo(url) ||
        runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("").let { p -> listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".avif").any { p.endsWith(it) } }

    /** #1957 a video by its path (the per-file and total caps of build.json apply to these only). */
    fun isVideo(url: String, mime: String = ""): Boolean = mime.startsWith("video/", true) ||
        runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("").let { p -> listOf(".mp4", ".webm", ".m4v", ".mov").any { p.endsWith(it) } }

    private fun resolve(raw: List<String>, base: String, crossOrigin: Boolean = false): List<String> {
        val b = runCatching { URI(base) }.getOrNull() ?: return emptyList()
        return raw.asSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("data:") && !it.startsWith("#") && !it.startsWith("javascript:") }
            .mapNotNull { runCatching { b.resolve(it.replace(" ", "%20")) }.getOrNull() }
            .filter { (it.scheme == "https" || it.scheme == "http") && it.host != null && (crossOrigin || (it.host.equals(b.host, true) && it.port == b.port)) }
            .map { URI(it.scheme, it.authority, it.path, it.query, null).toString() }
            .distinct().toList()
    }
}

/**
 * #1957 a body read with a byte cap and a wall-clock deadline. Unbounded readBytes() let one video served without Content-Length
 * (chunked) be read whole into memory or trickle for ever, which stalls the page, or the job, for good.
 */
object BoundedRead {
    sealed class Result { class Ok(val bytes: ByteArray) : Result(); object TooLarge : Result(); object Timeout : Result() }
    fun read(input: java.io.InputStream, maxBytes: Long, deadlineMs: Long, now: () -> Long = System::currentTimeMillis): Result {
        val end = if (deadlineMs > 0) now() + deadlineMs else Long.MAX_VALUE
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (true) {
            if (now() > end) return Result.Timeout
            val n = input.read(buf); if (n < 0) return Result.Ok(out.toByteArray())
            out.write(buf, 0, n)
            if (maxBytes > 0 && out.size() > maxBytes) return Result.TooLarge
        }
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

    private fun page(p: LnktreePage, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?): Boolean = fill(p, cfg, store, fetch, null) != null

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toList()
    private fun criticalMime(m: String) = m.contains("html", true) || m.contains("css", true) || m.contains("javascript", true) || m.contains("ecmascript", true)

    /**
     * Stores the page and its subresources (all of them when max_subresources_per_page is 0). Cached images that are still fresh are not downloaded again; the HTML, CSS and JS always are.
     * Returns null when the page itself could not be fetched, else how many ALREADY stored HTML/CSS/JS files came back different (a live change). [main] = the page body when the caller fetched it.
     */
    fun fill(p: LnktreePage, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?, main: Fetched?, extra: Collection<String> = emptyList()): Int? {
        store.registerPage(p.id, p.url)
        val m = (main ?: fetch(p.url))?.takeIf { it.status == 200 } ?: return null
        var changed = 0
        var scriptMedia = 0
        fun save(u: String, r: Fetched) {
            val old = store.get(p.id, u)
            if (old != null && old.status == 200 && r.status == 200 && criticalMime(r.mime) && !sha(old.body.readBytes()).equals(sha(r.body))) changed++
            store.put(p.id, u, r.mime, r.encoding, r.status, r.body)
        }
        save(p.url, m)
        var budget = if (cfg.maxSubresourcesPerPage > 0) cfg.maxSubresourcesPerPage else Int.MAX_VALUE
        val seen = HashSet<String>().apply { add(CacheKey.normalize(p.url)) }
        // #1957 the manifest: every URL planned for the page (what completeness is judged against)
        val planned = LinkedHashSet<String>()
        val queue = ArrayDeque(RefScan.html(String(m.body, Charsets.UTF_8), p.url, cfg.crossOrigin) + extra)
        val pool = if (cfg.subresourceConcurrency > 1) Executors.newFixedThreadPool(cfg.subresourceConcurrency) else null
        try {
            while (queue.isNotEmpty() && budget > 0) {
                // #1957 one batch of up to subresource_concurrency files is fetched in parallel; the results are stored in order
                val batch = ArrayList<String>()
                while (queue.isNotEmpty() && batch.size < cfg.subresourceConcurrency.coerceAtMost(budget)) {
                    val u = queue.removeFirst()
                    if (!seen.add(CacheKey.normalize(u))) continue
                    planned += u
                    val have = store.get(p.id, u)
                    if (have != null && (have.status == 200 || have.status == VIDEO_CAPPED) && (!criticalMime(have.mime) || store.sharedFor(p.id, u)) && store.state(p.id, u, cfg.ttlMs) == CacheState.FRESH) continue   // #1957 a fresh shared file (a CDN library too) is reused, never downloaded again per page
                    batch += u
                }
                if (batch.isEmpty()) continue
                val got: List<Fetched?> = if (pool == null) batch.map { fetchCapped(it, cfg, store, fetch) }
                    else batch.map { u -> pool.submit(Callable { fetchCapped(u, cfg, store, fetch) }) }.map { f -> runCatching { f.get() }.getOrNull() }
                for ((u, r) in batch.zip(got)) {
                    if (r == null) continue
                    budget--
                    if (r.status == VIDEO_CAPPED) { store.put(p.id, u, r.mime, r.encoding, r.status, ByteArray(0), r.encoding ?: "video-file-cap"); continue }   // #1957 a video over the declared cap: a known skip, not a missing file
                    if (r.status != 200) { if (r.status in 400..499 && r.status != 408 && r.status != 429) store.put(p.id, u, r.mime, r.encoding, r.status, r.body); continue }   // #1947 a definite 404 is stored as a tombstone, so one dead reference does not make the page incomplete for ever
                    if (RefScan.isVideo(u, r.mime)) {
                        // #1957 the total video cap is judged here, in order (the batch was fetched in parallel)
                        if (cfg.videoMaxTotalBytes > 0 && store.videoBytes.get() + r.body.size > cfg.videoMaxTotalBytes) { store.put(p.id, u, r.mime, null, VIDEO_CAPPED, ByteArray(0), "video-total-cap"); continue }
                        // #1957 max_cache_mb holds WHILE the job runs: at the cap, videos (optional media) stop; HTML, CSS, JS, data and images still finish
                        if (cfg.maxBytes > 0 && store.budget.used.get() + r.body.size > cfg.maxBytes) { store.put(p.id, u, r.mime, null, VIDEO_CAPPED, ByteArray(0), "cache-cap"); continue }
                        store.videoBytes.addAndGet(r.body.size.toLong())
                    }
                    save(u, r)
                    if (r.mime.contains("css")) queue.addAll(RefScan.css(String(r.body, Charsets.UTF_8), u, cfg.crossOrigin))
                    // #1957 the URLs a script or data file names (data-*.json.js media, a CDN library) are what the page loads at run time
                    if (cfg.scriptUrls && (r.mime.contains("javascript", true) || r.mime.contains("ecmascript", true) || r.mime.contains("json", true)))
                        for (x in RefScan.scriptUrls(String(r.body, Charsets.UTF_8), u)) {
                            if (CacheKey.normalize(x) in seen) continue
                            // #1957 the data-file scan plans at most max_script_media_per_page media files; the rest are known-missing with their reason
                            if (RefScan.isMedia(x) && cfg.maxScriptMediaPerPage > 0 && ++scriptMedia > cfg.maxScriptMediaPerPage) {
                                seen += CacheKey.normalize(x); planned += x
                                if (store.get(p.id, x) == null) store.put(p.id, x, "application/octet-stream", null, VIDEO_CAPPED, ByteArray(0), "plan-cap")
                                continue
                            }
                            queue.add(x)
                        }
                }
            }
        } finally { pool?.shutdown() }
        planned.addAll(queue.filter { CacheKey.normalize(it) !in seen })   // over the per-page cap: still referenced, so the page stays incomplete
        store.writeRefs(p.id, planned)
        return changed
    }

    /** #1957 the stored status of a video skipped by the per-file or total cap of build.json ui.lnktree_cache.video. */
    const val VIDEO_CAPPED = 413

    /** #1957 a video over the per-file cap, or one that would pass the total cap, is not downloaded (images are always stored). */
    fun fetchCapped(u: String, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?): Fetched? {
        val video = RefScan.isVideo(u)
        if (video && cfg.videoMaxTotalBytes > 0 && store.videoBytes.get() >= cfg.videoMaxTotalBytes) return Fetched("video/mp4", "video-total-cap", VIDEO_CAPPED, ByteArray(0))
        if (video && cfg.maxBytes > 0 && store.budget.used.get() >= cfg.maxBytes) return Fetched("video/mp4", "cache-cap", VIDEO_CAPPED, ByteArray(0))
        val r = fetch(u) ?: return null
        if (RefScan.isVideo(u, r.mime) && r.status == 200 && ((cfg.videoMaxFileBytes > 0 && r.body.size > cfg.videoMaxFileBytes) ||
                (cfg.videoMaxTotalBytes > 0 && store.videoBytes.get() + r.body.size > cfg.videoMaxTotalBytes))) return Fetched(r.mime, "video-file-cap", VIDEO_CAPPED, ByteArray(0))
        return r
    }
}

/**
 * #1957 the startup job of cache_ttl (owner policy): for every page of every section,
 *  - no copy                       -> FILL (downloaded into the cache)
 *  - copy younger than ttl_days, complete   -> SKIP (nothing is downloaded)
 *  - copy younger than ttl_days, incomplete -> COMPLETE (only the files it lacks)
 *  - copy older than ttl_days      -> REFETCH: the whole page is downloaded into staging and swapped in ATOMICALLY only once the new copy
 *                                     is complete; a failed or partial refetch leaves the old copy as it was.
 */
object TtlPrefetch {
    enum class Action { SKIP, FILL, COMPLETE, REFETCH }
    data class Outcome(val page: String, val action: Action, val result: String) {
        fun line() = "CACHE_FETCH page=$page action=${action.name.lowercase()} result=$result"
    }

    fun decide(p: LnktreePage, cfg: LnktreeCacheConfig, store: LnktreeCacheStore): Action = when (store.state(p.id, p.url, cfg.ttlMs)) {
        CacheState.MISSING -> Action.FILL
        CacheState.STALE -> Action.REFETCH
        CacheState.FRESH -> if (store.complete(p.id, p.url)) Action.SKIP else Action.COMPLETE
    }

    /** One page by its action. [extra] = the URLs the runtime pass saw (null = not harvested). */
    fun page(p: LnktreePage, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?, action: Action = decide(p, cfg, store), extra: Collection<String> = emptyList()): Outcome = when (action) {
        Action.SKIP -> Outcome(p.id, action, "fresh")
        Action.FILL -> Outcome(p.id, action, when { LnktreePrefetch.fill(p, cfg, store, fetch, null, extra) == null -> "failed"; store.complete(p.id, p.url) -> "complete"; else -> "partial" })
        Action.COMPLETE -> {
            // the copy is fresh: its stored files stay as they are (no network for them); only the files it lacks are fetched
            store.registerPage(p.id, p.url)
            val want = (store.missing(p.id, p.url) + extra.filter { store.get(p.id, it) == null }).distinctBy { CacheKey.normalize(it) }
            val pool = Executors.newFixedThreadPool(cfg.subresourceConcurrency)
            try {
                want.map { u -> u to pool.submit(Callable { LnktreePrefetch.fetchCapped(u, cfg, store, fetch) }) }.forEach { (u, f) ->
                    val r = runCatching { f.get() }.getOrNull() ?: return@forEach
                    if (r.status == 200 || (r.status in 400..499 && r.status != 408 && r.status != 429)) store.put(p.id, u, r.mime, if (r.status == 200) r.encoding else null, r.status, if (r.status == 200) r.body else ByteArray(0), if (r.status == LnktreePrefetch.VIDEO_CAPPED) r.encoding else null)
                }
            } finally { pool.shutdown() }
            store.writeRefs(p.id, store.refs(p.id) + want)
            Outcome(p.id, action, if (store.complete(p.id, p.url)) "complete" else "partial")
        }
        Action.REFETCH -> {
            val st = store.stagingFor(p.id); st.root.deleteRecursively()
            val ok = LnktreePrefetch.fill(p, cfg, st, fetch, null, extra) != null && st.complete(p.id, p.url)
            if (ok && store.commit(p.id, st)) Outcome(p.id, action, "replaced") else { st.root.deleteRecursively(); store.recount(); Outcome(p.id, action, "kept-old") }   // nothing of a failed refetch, shared files included, reaches the live copy
        }
    }

    /** Bounded by prefetch.concurrency pages at once; every page's outcome is returned (and logged by the caller). */
    fun run(pages: List<LnktreePage>, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?, extra: (LnktreePage) -> Collection<String> = { emptyList() }, onPage: (Outcome) -> Unit = {}): List<Outcome> {
        if (!cfg.enabled || !cfg.prefetchEnabled) return emptyList()
        val todo = pages.filter { cfg.caches(it.id) }.let { if (cfg.prefetchMaxPages > 0) it.take(cfg.prefetchMaxPages) else it }
        val pool = Executors.newFixedThreadPool(cfg.prefetchConcurrency)
        try {
            return todo.map { p -> pool.submit(Callable {
                val a = decide(p, cfg, store)
                runCatching { page(p, cfg, store, fetch, a, if (a == Action.SKIP) emptyList() else extra(p)) }.getOrElse { Outcome(p.id, a, "error ${it.javaClass.simpleName}") }
                    .also { if (cfg.maxBytes > 0 && store.budget.used.get() > cfg.maxBytes) store.trim(cfg.maxBytes) }.also(onPage)   // #1957 the cap holds during the job, not only at its end
            }) }.map { it.get() }
        } finally { pool.shutdown(); store.trim(cfg.maxBytes) }
    }
}

/** #1951 the live check of a page served from its cache: the live body is fetched, compared by hash, and the cache is updated when it differs. */
object LiveSwap {
    /** Number of stored HTML/CSS/JS files whose live content differs (> 0 = swap the shown page); 0 = same, or the live fetch failed (the cached copy stays). The first fill of a page is not a swap. */
    fun refresh(p: LnktreePage, cfg: LnktreeCacheConfig, store: LnktreeCacheStore, fetch: (String) -> Fetched?): Int =
        (LnktreePrefetch.fill(p, cfg, store, fetch, null) ?: 0).also { store.trim(cfg.maxBytes) }
}

/** A cached body ready to hand to the WebView. */
class Served(
    val mime: String, val encoding: String?, val status: Int, val body: File,
    /** #1947 "network" (fetched just now), "cache" (an entry served by the cache-first rule) or "offline-cache" (the network failed, the stored copy is the fallback). */
    val source: String = "cache",
    /** When the served copy was fetched (ms); the "offline copy from <age>" note reads it. */
    val fetchedAt: Long = 0L,
)

/** shouldInterceptRequest's decision, with the network and the background refresh injected. */
object LnktreeResolver {
    /**
     * Cache hit (fresh or stale) -> serve the file; stale also calls [revalidate] (the caller refetches in the background).
     * Miss -> [fetch], store, serve. Network failure on a miss -> null (the WebView tries the network itself).
     */
    fun serve(
        pageId: String, url: String, cfg: LnktreeCacheConfig, store: LnktreeCacheStore,
        fetch: (String) -> Fetched?, revalidate: (String) -> Unit, pageUrl: String? = null,
    ): Served? {
        // #1947 network_first: the live page (and its same-origin CSS/JS) comes from the network; a good answer refreshes the entry; the stored copy
        // is served ONLY when the network failed (offline, DNS, timeout, 5xx) and never an empty body
        // #1951 cache_then_live: a COMPLETE copy paints from the cache at once, the live page is compared in the background (revalidate); no complete copy = the WebView's own network, filled afterwards. Nothing here waits on the network.
        // #1957 cache_ttl: a stored copy is served at once and NOTHING is fetched for it while it is younger than ttl_days; an older main frame
        // asks for the background refetch (staged, swapped in atomically by the job, no live swap of the shown page). A miss (no copy, or a file
        // the copy lacks) goes to the WebView's own network and is reported through [revalidate] so the cache gets it afterwards.
        if (cfg.cacheTtl) {
            val e = store.get(pageId, url)
            val isMain = pageUrl == null || CacheKey.normalize(url) == CacheKey.normalize(pageUrl)
            if (e != null && e.status != LnktreePrefetch.VIDEO_CAPPED && store.paintable(pageId, url)) {
                if (isMain && store.state(pageId, url, cfg.ttlMs) == CacheState.STALE) revalidate(url)
                return Served(e.mime, e.encoding, e.status, e.body, "cache", e.fetchedAt)
            }
            if (e == null || isMain) revalidate(url)
            return null
        }
        if (cfg.cacheThenLive) {
            val e = store.get(pageId, url)
            val isMain = pageUrl != null && CacheKey.normalize(url) == CacheKey.normalize(pageUrl)
            if (e != null && store.paintable(pageId, url)) {
                if (isMain || store.state(pageId, url, cfg.ttlMs) == CacheState.STALE) revalidate(url)
                return Served(e.mime, e.encoding, e.status, e.body, "cache-live", e.fetchedAt)
            }
            revalidate(url)
            return null
        }
        if (cfg.networkFirst && networkFirstTarget(url, pageUrl)) {
            val r = fetch(url)?.takeIf { it.status == 200 && it.body.isNotEmpty() }
            if (r != null) {
                store.put(pageId, url, r.mime, r.encoding, r.status, r.body)
                store.trim(cfg.maxBytes)
                return store.get(pageId, url)?.let { Served(it.mime, it.encoding, it.status, it.body, "network", it.fetchedAt) }
            }
            return store.get(pageId, url)?.takeIf { it.status == 200 && it.body.length() > 0 }
                ?.let { Served(it.mime, it.encoding, it.status, it.body, "offline-cache", it.fetchedAt) }
        }
        store.get(pageId, url)?.let { e ->
            // #1947 cached HTML without its CSS/JS is incomplete: not served (the WebView's network loads the whole page); it is completed in the background
            if (!store.paintable(pageId, url)) { revalidate(url); return null }
            if (store.state(pageId, url, cfg.ttlMs) == CacheState.STALE) revalidate(url)
            return Served(e.mime, e.encoding, e.status, e.body, "cache", e.fetchedAt)
        }
        // #1947 a sub-resource miss never blocks the WebView (a 10 s connect + 20 s read wait, then the network again): it goes to the WebView's network at once and is filled in the background
        if (pageUrl != null && CacheKey.normalize(url) != CacheKey.normalize(pageUrl)) { revalidate(url); return null }
        val r = fetch(url)?.takeIf { it.status == 200 } ?: return null
        store.put(pageId, url, r.mime, r.encoding, r.status, r.body)
        store.trim(cfg.maxBytes)
        return store.get(pageId, url)?.let { Served(it.mime, it.encoding, it.status, it.body, "network", it.fetchedAt) }
    }

    /** #1947 the requests network_first applies to: the main frame and the page's stylesheets and scripts (images stay cache-first). */
    fun networkFirstTarget(url: String, pageUrl: String?): Boolean {
        if (pageUrl != null && CacheKey.normalize(url) == CacheKey.normalize(pageUrl)) return true
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".mjs")
    }

    /** #1947 "2 h", "3 d": the age the offline note shows. */
    fun age(fetchedAt: Long, now: Long): String {
        val m = ((now - fetchedAt).coerceAtLeast(0)) / 60_000
        return when { m < 60 -> "${m.coerceAtLeast(1)} min"; m < 48 * 60 -> "${m / 60} h"; else -> "${m / (24 * 60)} d" }
    }

    /** Hard refresh: what to delete for [pageId] under the declared scope. Returns files removed. */
    fun hardRefresh(pageId: String, cfg: LnktreeCacheConfig, store: LnktreeCacheStore): Int =
        if (cfg.hardRefreshScope == "all") store.clearAll() else store.clearPageWithShared(pageId)
}

/**
 * #1957 the embedded sites' own service workers (mySocials is a PWA). Decision: they are BLOCKED inside Cloud Me's WebViews and any
 * registration left from an earlier version is removed, instead of routing them through the app cache. Why: a service worker answers the
 * page's requests from its own CacheStorage before any client hook runs, so with one installed the app cache could neither serve nor see
 * them (the walk logged source=none), and its CacheStorage kept pre-fix data files with dead media URLs that no app-side refresh could
 * replace. With it gone the app cache is the ONE copy, page-scoped and ttl-aged. The ServiceWorkerClient is still installed as the
 * backstop: a worker that runs anyway (the first load before the removal, a site that ignores the refusal) is answered from the app cache.
 */
object SwPolicy {
    /** Runs at document start in every cached page: refuses register(), unregisters what is there and empties CacheStorage; a page that was
     *  still controlled by an old worker reloads once, so its requests reach the app. */
    const val BLOCK_JS = "(function(){try{var n=navigator.serviceWorker;if(!n)return;" +
        "try{Object.defineProperty(n,'register',{configurable:true,value:function(){return Promise.reject(new DOMException('service workers are off in Cloud Me (#1957)','SecurityError'))}})}catch(e){}" +
        "var had=!!n.controller;n.getRegistrations().then(function(rs){return Promise.all(rs.map(function(r){return r.unregister()}))}).then(function(){" +
        "return self.caches?caches.keys().then(function(k){return Promise.all(k.map(function(x){return caches.delete(x)}))}):0}).then(function(){" +
        "if(had&&!sessionStorage.getItem('cm-sw-off')){sessionStorage.setItem('cm-sw-off','1');location.reload()}}).catch(function(){})}catch(e){}})();"

    /** A request a service worker made: served from whichever page's copy holds it; null = the worker's own network. */
    fun serve(url: String, method: String, cfg: LnktreeCacheConfig, store: LnktreeCacheStore): Served? {
        if (!cfg.enabled || method != "GET" || !url.startsWith("https://")) return null
        val (_, e) = store.findAny(url) ?: return null
        if (e.status != 200) return null
        return Served(e.mime, e.encoding, e.status, e.body, "sw-cache", e.fetchedAt)
    }
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
