package com.diegonmarcos.cloudlib.web

/**
 * #1711 what the pin-offline intercept does with ONE request of a pinned site. Pure (no Android), so the JVM suites of the
 * apps hold every rule.
 *
 * The rule: the pinned copy never stands between a live page and the network. Before, every request the copy could not
 * answer was fetched by the app itself INSIDE the WebView's request, with a second HTTP client (its own connections and
 * 10 s / 20 s timeouts), then stored and served. A page whose first data request can never be in the copy (floor796 asks
 * data/matrix.json?r=<random>, a new address on every load) therefore waited on that client for its first frame: while it
 * had no answer the page showed its top bar over a flat #101010 page with 0 canvases, 0 failed requests, 0 console errors.
 * Now a request the copy does not hold goes straight to the WebView's own network stack ([Action.PASS_AND_RECORD]) and the
 * copy is written afterwards, off the request path ([record]). The copy answers by itself only what it holds
 * ([Action.SERVE]) and, when the page is shown from the copy because the live site did not answer ([Source.COPY]), the
 * newest stored answer of a path whose query was never stored ([Action.SERVE_NEWEST]).
 */
object PinPolicy {
    /** Where the page in front of the user comes from: the live site, or its pinned copy (offline, or the site not answering). */
    enum class Source { LIVE, COPY }

    enum class Action {
        /** Not answered and not stored: the WebView loads it (the copy holds nothing for it and the live site is not answering). */
        PASS,

        /** The WebView loads it from the network itself; the copy is written in the background. */
        PASS_AND_RECORD,

        /** Answered from the copy: this exact address is stored. */
        SERVE,

        /** Answered from the copy with the newest stored query of the same path. */
        SERVE_NEWEST,
    }

    /** How long a pinned world's live document may take to arrive before its pinned copy is shown instead. */
    const val LIVE_WAIT_MS = 8_000L

    /** A document or a cache-defeating address is stored again only once the copy's answer is this old. */
    const val RECORD_AGAIN_MS = 3_600_000L

    /** A document: the main frame, or any request that accepts text/html. */
    fun isDocument(mainFrame: Boolean, accept: String?): Boolean = mainFrame || accept.orEmpty().contains("text/html", true)

    /**
     * [document] = [isDocument]; [liveDocuments] = the host shows documents live (Galaxy; Cloud-Browser keeps its pinned
     * documents cache-first); [refresh] = the user asked to reload; [stored] = the copy holds this exact address;
     * [storedPath] = it holds the same path under another query.
     */
    fun decide(source: Source, document: Boolean, liveDocuments: Boolean, refresh: Boolean, stored: Boolean, storedPath: Boolean): Action = when {
        source == Source.COPY -> if (stored) Action.SERVE else if (storedPath) Action.SERVE_NEWEST else Action.PASS
        refresh -> Action.PASS_AND_RECORD
        document && liveDocuments -> Action.PASS_AND_RECORD
        stored -> Action.SERVE
        else -> Action.PASS_AND_RECORD
    }

    /**
     * Whether the background recorder fetches an address now. [exactStoredAt] / [newestStoredAt] = when the copy stored this
     * exact address / the newest query of its path (null = never). Everything is fetched again on a [refresh]; a [document]
     * and a cache-defeating address ([hasQuery], not stored exactly) again once the copy's answer is [RECORD_AGAIN_MS] old; a
     * stored file never by itself (the copy does not expire); anything not stored, now.
     */
    fun record(refresh: Boolean, document: Boolean, hasQuery: Boolean, exactStoredAt: Long?, newestStoredAt: Long?, now: Long): Boolean = when {
        refresh -> true
        document -> exactStoredAt == null || now - exactStoredAt >= RECORD_AGAIN_MS
        exactStoredAt != null -> false
        hasQuery && newestStoredAt != null -> now - newestStoredAt >= RECORD_AGAIN_MS
        else -> true
    }

    /** The address a web view asks for when it is given [url]: a bare origin gets its `/` (`https://floor796.com` -> `https://floor796.com/`). */
    fun documentKey(url: String): String {
        val u = SiteCache.normalise(url.trim())
        val rest = u.substringAfter("://", "")
        if (rest.isEmpty() || '/' in rest.substringBefore('?')) return u
        return u.substringBefore('?') + "/" + (if ('?' in u) "?" + u.substringAfter('?') else "")
    }
}

/**
 * #1711 what the pin-offline intercept did with the latest requests, so a pinned page that stays blank can be read on the
 * device itself (Page diagnostics) and in the log export (logcat tag [TAG]). A bounded in-memory ring; pure apart from the
 * default [sink] (a Log call that never throws).
 */
object PinLog {
    const val TAG = "CloudWebPin"
    const val CAPACITY = 120
    const val SERVED = "served from the copy"
    const val SERVED_NEWEST = "served the newest stored copy"
    const val LIVE = "left to the network"
    const val RECORDED = "stored"
    const val UNCHANGED = "copy still current"
    const val RECORD_FAILED = "not stored"

    /** [ms] < 0 = not timed. */
    data class Entry(val ts: Long, val what: String, val url: String, val ms: Long, val note: String) {
        fun line(): String = what + (if (ms >= 0) " in $ms ms" else "") + (if (note.isEmpty()) "" else " ($note)") + ": " + url
    }

    private val ring = ArrayDeque<Entry>()

    /** Where each entry goes besides the ring; null = nowhere. The default writes logcat. */
    @Volatile var sink: ((Entry) -> Unit)? = { e -> runCatching { android.util.Log.i(TAG, e.line()) } }

    fun add(what: String, url: String, ms: Long = -1L, note: String = "", now: Long = System.currentTimeMillis()): Entry {
        val e = Entry(now, what, url.take(300), ms, note)
        synchronized(ring) {
            ring.addLast(e)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        sink?.invoke(e)
        return e
    }

    fun snapshot(): List<Entry> = synchronized(ring) { ring.toList() }

    /** The entries of one site ([host] as it stands in the address), oldest first. */
    fun of(host: String): List<Entry> = snapshot().filter { it.url.contains("://$host/") }

    /** `3 served from the copy, 2 left to the network, 1 stored` over [entries], in the order first seen. */
    fun summary(entries: List<Entry>): String =
        entries.groupingBy { it.what }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }

    fun clear() = synchronized(ring) { ring.clear() }
}
