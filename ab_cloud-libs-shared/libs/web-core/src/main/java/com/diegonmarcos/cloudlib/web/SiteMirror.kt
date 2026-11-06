package com.diegonmarcos.cloudlib.web

import java.net.URI

/**
 * #1518 Cloud-Browser's full offline copy of a site, the pure half (no Android: the JVM suite holds every rule). One
 * [SiteCache] holds the copy; the page serves from it cache-first and only an explicit Refresh refetches. [MirrorScope]
 * decides what belongs to a site, [MirrorCrawl] walks it, [MirrorGate] says when a download may run, [PinnedSites] and
 * [CachedSites] are the pin list and the "Cached sites" rows, [SiteMirrorEngine] runs a crawl or a refresh over a [Fetcher].
 */
object MirrorScope {
    /** The key one site is stored under: lower case, no leading `www.`. */
    fun hostKey(host: String): String = host.trim().lowercase().removePrefix("www.")

    /** `scheme://host[:port]` of an http(s) address, else null. */
    fun origin(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.let { hostKey(it) } ?: return null
        if (scheme != "http" && scheme != "https") return null
        return "$scheme://$host" + if (u.port > 0) ":${u.port}" else ""
    }

    /** Same scheme, same host (a `www.` prefix does not matter) and same port: a subdomain or another site is NOT in scope. */
    fun sameOrigin(base: String, url: String): Boolean = origin(base)?.let { it == origin(url) } == true

    /** [ref] resolved against [base]; http(s) only, fragment dropped; null for mailto:, javascript:, data: and the like. */
    fun resolve(base: String, ref: String): String? {
        val r = ref.trim().replace("&amp;", "&")
        if (r.isEmpty() || r.startsWith("#")) return null
        val abs = runCatching { URI(base).resolve(URI(r.replace(" ", "%20"))) }.getOrNull() ?: return null
        if (abs.scheme?.lowercase() != "http" && abs.scheme?.lowercase() != "https") return null
        return SiteCache.normalise(abs.toString())
    }

    private val ATTR = Regex("""(?:href|src|data-src|poster)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("""url\(\s*["']?([^"')\s]+)["']?\s*\)""", RegexOption.IGNORE_CASE)
    private val CSS_IMPORT = Regex("""@import\s+["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun isHtml(mime: String): Boolean = mime.substringBefore(';').trim().lowercase().let { it == "text/html" || it == "application/xhtml+xml" }
    fun isCss(mime: String): Boolean = mime.substringBefore(';').trim().lowercase() == "text/css"

    /** The same-origin addresses a page (or stylesheet) refers to, in order, without repeats. Off-origin links are dropped here. */
    fun links(base: String, body: String, mime: String): List<String> {
        val refs = ArrayList<String>()
        if (isHtml(mime)) ATTR.findAll(body).mapTo(refs) { it.groupValues[1] }
        if (isHtml(mime) || isCss(mime)) { CSS_URL.findAll(body).mapTo(refs) { it.groupValues[1] }; CSS_IMPORT.findAll(body).mapTo(refs) { it.groupValues[1] } }
        return refs.mapNotNull { resolve(base, it) }.filter { sameOrigin(base, it) }.distinct()
    }
}

/** What a crawl may take: files, bytes and how many page links deep it follows. */
data class MirrorLimits(val maxFiles: Int = 2000, val maxBytes: Long = 300L shl 20, val maxDepth: Int = 3)

/** The work list of one site download: breadth first, same origin only, bounded. */
class MirrorCrawl(val start: String, val limits: MirrorLimits = MirrorLimits()) {
    private val queue = ArrayDeque<Pair<String, Int>>()
    private val seen = HashSet<String>()
    var files = 0; private set
    var bytes = 0L; private set
    val pending: Int get() = queue.size

    init { offer(listOf(start), 0) }

    /** Queues what is in scope, unseen and within the depth; returns how many were taken. */
    fun offer(urls: List<String>, depth: Int): Int {
        var n = 0
        for (u in urls) {
            val k = SiteCache.normalise(u)
            // the depth bounds PAGES; a file a page needs (script, image, font) is taken at any depth
            if (depth > limits.maxDepth && !isAsset(k)) continue
            if (MirrorScope.sameOrigin(start, k) && seen.add(k)) { queue.addLast(k to depth); n++ }
        }
        return n
    }

    private fun isAsset(url: String): Boolean =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase() in ASSET_EXT

    val stopReason: String? get() = when {
        files >= limits.maxFiles -> "file limit reached"
        bytes >= limits.maxBytes -> "size limit reached"
        else -> null
    }

    /** The next address and its depth, or null when done or a limit is reached. */
    fun next(): Pair<String, Int>? = if (stopReason != null) null else queue.removeFirstOrNull()

    fun saved(size: Long) { files++; bytes += size }

    private companion object {
        val ASSET_EXT = setOf("js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "woff", "woff2", "ttf", "otf",
            "mp3", "ogg", "wav", "mp4", "webm", "wasm", "json", "bin", "glb", "gltf", "map")
    }
}

/**
 * When a full-site download may run: on a user tap at any time, otherwise only while charging AND on Wi-Fi. Nothing polls:
 * the runner re-evaluates on the charger / network events, and a failure retries after [RETRY_FLOOR_MS] at the soonest.
 */
object MirrorGate {
    const val RETRY_FLOOR_MS = 30_000L
    const val MAX_ATTEMPTS = 3
    private const val RETRY_CEIL_MS = 600_000L

    data class Env(val charging: Boolean, val wifi: Boolean)

    fun allowed(env: Env, userTap: Boolean): Boolean = userTap || (env.charging && env.wifi)

    fun waiting(env: Env, userTap: Boolean): String? = when {
        allowed(env, userTap) -> null
        !env.charging && !env.wifi -> "waiting for the charger and Wi-Fi"
        !env.charging -> "waiting for the charger"
        else -> "waiting for Wi-Fi"
    }

    /** Delay before attempt number [attempt] (0 = the first retry): doubles from the floor, never below it. */
    fun retryDelay(attempt: Int): Long = (RETRY_FLOOR_MS shl attempt.coerceIn(0, 10)).coerceIn(RETRY_FLOOR_MS, RETRY_CEIL_MS)

    /** The earliest time a retry may start after [failedAt]: the floor holds whatever delay was asked for. */
    fun retryAt(failedAt: Long, asked: Long): Long = failedAt + maxOf(asked, RETRY_FLOOR_MS)
}

/** The sites pinned offline (host keys), as a list with pure ops; the default is declared (build.json::pin_offline). */
object PinnedSites {
    fun keyOf(urlOrHost: String): String = (MirrorScope.origin(urlOrHost)?.substringAfter("://")?.substringBefore(':') ?: urlOrHost).let { MirrorScope.hostKey(it) }

    /** [raw] is the stored list; null (never stored) means [defaults]. */
    fun parse(raw: String?, defaults: List<String>): List<String> =
        (raw?.split('\n') ?: defaults).map { MirrorScope.hostKey(it) }.filter { it.isNotEmpty() }.distinct()

    fun serialise(list: List<String>): String = list.joinToString("\n")

    /** [host] may be a host or any address of the site. */
    fun isPinned(list: List<String>, host: String): Boolean = keyOf(host) in list
    fun pin(list: List<String>, host: String): List<String> = (list + keyOf(host)).filter { it.isNotEmpty() }.distinct()
    fun unpin(list: List<String>, host: String): List<String> = list.filterNot { it == keyOf(host) }
}

/** One row of Domains > Files Cached > Cached sites. */
data class CachedSite(val host: String, val pinned: Boolean, val files: Int, val bytes: Long, val lastRefresh: Long)

object CachedSites {
    /** Every site that is pinned or holds a copy, A to Z. */
    fun rows(cache: SiteCache, pinned: List<String>): List<CachedSite> =
        (cache.hosts() + pinned).distinct().sorted().map { h -> cache.stats(h).let { CachedSite(h, h in pinned, it.files, it.bytes, it.lastRefresh) } }

    /** Unpin keeps the copy (it stays listed, unpinned, until Delete) and returns the new pin list. */
    fun unpin(pinned: List<String>, host: String): List<String> = PinnedSites.unpin(pinned, host)

    /** Delete removes the copy and the pin; returns the new pin list. */
    fun delete(cache: SiteCache, pinned: List<String>, host: String): List<String> {
        cache.delete(PinnedSites.keyOf(host))
        return PinnedSites.unpin(pinned, host)
    }
}

/** One network answer. */
class Fetched(val code: Int, val mime: String, val headers: Map<String, String>, val body: ByteArray)

/** The network, abstracted so the engine is JVM-tested over a fake. [etag] makes the request conditional; null result = the request failed. */
fun interface Fetcher { fun fetch(url: String, etag: String?): Fetched? }

object SiteMirrorEngine {
    data class Progress(val files: Int, val bytes: Long, val pending: Int, val current: String, val done: Boolean = false, val stopped: String = "")

    private fun keep(h: Map<String, String>) = SiteCache.keepHeaders(h)

    /** Crawls [start]'s site into [cache]: same origin only; HTML and CSS are scanned for more. Returns the final progress. */
    fun crawl(
        cache: SiteCache, start: String, limits: MirrorLimits, fetch: Fetcher,
        cancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {},
    ): Progress {
        val host = PinnedSites.keyOf(start)
        val crawl = MirrorCrawl(start, limits)
        var stopped = ""
        while (true) {
            if (cancelled()) { stopped = "stopped by you"; break }
            val item = crawl.next()
            if (item == null) { stopped = crawl.stopReason.orEmpty(); break }
            val (url, depth) = item
            onProgress(Progress(crawl.files, crawl.bytes, crawl.pending, url))
            val r = fetch.fetch(url, null) ?: continue
            if (r.code != 200) continue
            cache.put(host, url, 200, r.mime, keep(r.headers), r.body)
            crawl.saved(r.body.size.toLong())
            val text = if (MirrorScope.isHtml(r.mime) || MirrorScope.isCss(r.mime)) String(r.body, Charsets.UTF_8) else null
            if (text != null) crawl.offer(MirrorScope.links(url, text, r.mime), if (MirrorScope.isHtml(r.mime)) depth + 1 else depth)
        }
        return Progress(crawl.files, crawl.bytes, crawl.pending, "", true, stopped).also(onProgress)
    }

    /**
     * Refresh: refetches every held URL (conditional on its ETag); a changed body replaces the copy, a 304 keeps it, and a
     * failed request never touches it (the old copy stays). Returns how many files changed.
     */
    fun refresh(cache: SiteCache, host: String, fetch: Fetcher, cancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {}): Int {
        val key = PinnedSites.keyOf(host)
        val urls = cache.urls(key)
        var changed = 0
        for ((i, url) in urls.withIndex()) {
            if (cancelled()) break
            onProgress(Progress(i, 0, urls.size - i, url))
            val old = cache.lookup(key, url)
            val r = fetch.fetch(url, old?.headers?.get("ETag")) ?: continue
            if (r.code == 200 && cache.put(key, url, 200, r.mime, keep(r.headers), r.body)) changed++
        }
        onProgress(Progress(urls.size, cache.stats(key).bytes, 0, "", true))
        return changed
    }
}
