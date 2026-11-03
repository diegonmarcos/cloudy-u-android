package com.diegonmarcos.superapp.browser

/**
 * #1141 the pure half of the in-app debugger (JVM-tested in BrowserDevToolsTest): the bounded console and
 * network logs, what a Hard / Light refresh clears, what the Desktop / Mobile toggle sets, and the source
 * viewer's search and range selection. No android.* here; the Android half is [BrowserRefresh] and BrowserDevToolsUi.
 */

/** A bounded, thread-safe log: past [capacity] the oldest entry is dropped. [version] moves on every change so a panel knows to redraw. */
class RingBuffer<T>(val capacity: Int) {
    private val items = ArrayDeque<T>()
    @Volatile var version = 0; private set
    var dropped = 0L; private set

    @Synchronized fun add(t: T) {
        if (items.size >= capacity) { items.removeFirst(); dropped++ }
        items.addLast(t); version++
    }

    @Synchronized fun snapshot(): List<T> = items.toList()
    @Synchronized fun clear() { items.clear(); version++ }
    @Synchronized fun size(): Int = items.size

    /** Replace the newest entry matching [pick] (a late status for a request already logged). */
    @Synchronized fun updateLast(pick: (T) -> Boolean, change: (T) -> T) {
        val i = items.indexOfLast(pick)
        if (i >= 0) { items[i] = change(items[i]); version++ }
    }

    init { require(capacity > 0) { "capacity must be positive" } }
}

data class ConsoleEntry(val ts: Long, val level: String, val message: String, val source: String, val line: Int, val page: String = "") {
    /** `file.js:12` for an entry that knows where it came from; "" otherwise. */
    fun location(): String = if (source.isBlank()) "" else if (line > 0) "$source:$line" else source
}

object ConsoleLog {
    const val CAPACITY = 500
    /** Levels the filter offers; REPL lines are their own level so a result is told from page output. */
    val LEVELS = listOf("LOG", "DEBUG", "TIP", "WARNING", "ERROR", "REPL")
    /** A message longer than this is cut: one runaway page must not fill the buffer's memory. */
    const val MAX_MESSAGE = 4000

    fun entry(ts: Long, level: String, message: String, source: String?, line: Int, page: String = "") =
        ConsoleEntry(ts, level.uppercase(), message.take(MAX_MESSAGE), source.orEmpty(), line, page)

    /** [levels] empty = every level; [query] matches the message or location, case-insensitively. */
    fun filter(all: List<ConsoleEntry>, levels: Set<String>, query: String = ""): List<ConsoleEntry> = all.filter { e ->
        (levels.isEmpty() || e.level in levels) &&
            (query.isBlank() || e.message.contains(query, true) || e.location().contains(query, true))
    }

    fun asText(entries: List<ConsoleEntry>): String = entries.joinToString("\n") { e ->
        val at = e.location().let { if (it.isEmpty()) "" else "  ($it)" }
        "[${e.level}] ${e.message}$at"
    }
}

data class NetEntry(
    val ts: Long, val method: String, val url: String, val type: String,
    val blocked: Boolean, val status: Int? = null,
)

object NetLog {
    const val CAPACITY = 1000
    val TYPES = listOf("document", "script", "style", "image", "font", "media", "data", "other")

    private val EXT = mapOf(
        "js" to "script", "mjs" to "script", "css" to "style",
        "png" to "image", "jpg" to "image", "jpeg" to "image", "gif" to "image", "webp" to "image", "svg" to "image", "ico" to "image", "avif" to "image",
        "woff" to "font", "woff2" to "font", "ttf" to "font", "otf" to "font",
        "mp3" to "media", "mp4" to "media", "webm" to "media", "ogg" to "media", "m3u8" to "media",
        "json" to "data", "xml" to "data",
    )

    /** What kind of request this is, from the main-frame flag, the Accept header and the path's extension. */
    fun typeOf(url: String, mainFrame: Boolean, accept: String?): String {
        if (mainFrame) return "document"
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        EXT[path.substringAfterLast('/').substringAfterLast('.', "")]?.let { return it }
        val a = accept.orEmpty().lowercase()
        return when {
            a.startsWith("image/") -> "image"
            a.contains("text/css") -> "style"
            a.contains("json") -> "data"
            a.contains("text/html") -> "document"
            else -> "other"
        }
    }

    fun entry(ts: Long, method: String?, url: String, mainFrame: Boolean, accept: String?, blocked: Boolean) =
        NetEntry(ts, (method ?: "GET").uppercase(), url.take(2000), typeOf(url, mainFrame, accept), blocked)

    /** [types] empty = every type; [query] matches the URL; [blockedOnly] keeps tracker-blocked requests. */
    fun filter(all: List<NetEntry>, types: Set<String>, query: String = "", blockedOnly: Boolean = false): List<NetEntry> = all.filter { e ->
        (types.isEmpty() || e.type in types) && (!blockedOnly || e.blocked) &&
            (query.isBlank() || e.url.contains(query, true))
    }

    fun line(e: NetEntry): String =
        "${e.method} ${e.status?.toString() ?: "-"} ${e.type}${if (e.blocked) " BLOCKED" else ""} ${e.url}"

    fun asText(entries: List<NetEntry>): String = entries.joinToString("\n") { line(it) }
}

/** The process-wide debugger state: two bounded logs and the remote-debugging switch (off at every start). */
object DevTools {
    val console = RingBuffer<ConsoleEntry>(ConsoleLog.CAPACITY)
    val net = RingBuffer<NetEntry>(NetLog.CAPACITY)
    @Volatile var remoteDebugging = false
}

enum class RefreshMode { HARD, LIGHT }

/**
 * What a refresh clears, for the page's ORIGIN. Hard = a first visit again (cache, cookies, DOM storage,
 * Service Workers); Light = code and cached files only (cache, Service Workers), the login and the
 * app's data stay. Both reload past the HTTP cache.
 */
data class RefreshPlan(val cache: Boolean, val cookies: Boolean, val domStorage: Boolean, val serviceWorkers: Boolean) {
    fun scopes(): List<String> = listOfNotNull(
        "cache".takeIf { cache }, "cookies".takeIf { cookies }, "storage".takeIf { domStorage }, "service_workers".takeIf { serviceWorkers })

    /** Extra request headers of the reload, so the main document is not answered from the cache either. */
    val reloadHeaders: Map<String, String> get() = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")

    companion object {
        fun of(mode: RefreshMode) = when (mode) {
            RefreshMode.HARD -> RefreshPlan(cache = true, cookies = true, domStorage = true, serviceWorkers = true)
            RefreshMode.LIGHT -> RefreshPlan(cache = true, cookies = false, domStorage = false, serviceWorkers = true)
        }
    }
}

/** What the Desktop / Mobile toggle sets on the WebView; applyViewMode does nothing but copy these values. */
data class ViewModePlan(val userAgent: String, val wideViewPort: Boolean, val overview: Boolean, val initialScale: Int, val viewportJs: String?) {
    companion object {
        /** Forces the layout viewport to a desktop width even when the page's own meta viewport says device-width. */
        const val DESKTOP_VIEWPORT_JS = "(function(){var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=1280');})()"

        fun of(desktop: Boolean, desktopUa: String?, mobileUa: String?, current: String): ViewModePlan {
            // #1420 never disguised: WebView's own agent unless the app declares one (Google OAuth goes to a Custom Tab)
            val ua = (if (desktop) desktopUa else mobileUa) ?: current
            // Mobile honours the page's own <meta name=viewport> (#893); desktop starts zoomed out on a wide layout.
            return ViewModePlan(ua, wideViewPort = true, overview = true, initialScale = if (desktop) 1 else 0,
                viewportJs = if (desktop) DESKTOP_VIEWPORT_JS else null)
        }
    }
}

/** A text split into numbered lines, with search and a selectable range of lines (the source / DOM viewer). */
class SourceDoc(text: String) {
    val lines: List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val gutterDigits: Int get() = lines.size.toString().length

    /** `   12` for line index 11, padded to the widest number. */
    fun number(index: Int): String = (index + 1).toString().padStart(gutterDigits)

    /** Indices of the lines holding [query] (case-insensitive); none for a blank query. */
    fun search(query: String): List<Int> =
        if (query.isEmpty()) emptyList() else lines.indices.filter { lines[it].contains(query, ignoreCase = true) }

    /** The ranges of [query] inside line [index], for highlighting. */
    fun ranges(index: Int, query: String): List<IntRange> {
        if (query.isEmpty() || index !in lines.indices) return emptyList()
        val out = ArrayList<IntRange>()
        var at = lines[index].indexOf(query, 0, ignoreCase = true)
        while (at >= 0) { out.add(at until at + query.length); at = lines[index].indexOf(query, at + query.length, ignoreCase = true) }
        return out
    }

    /** The match after / before [current] (an index into [matches], -1 = none yet), wrapping round. -1 when there are none. */
    fun step(matches: List<Int>, current: Int, forward: Boolean): Int = when {
        matches.isEmpty() -> -1
        current !in matches.indices -> if (forward) 0 else matches.lastIndex
        forward -> (current + 1) % matches.size
        else -> (current - 1 + matches.size) % matches.size
    }

    /** Lines [from]..[to] (either order, clamped) joined by newlines. */
    fun slice(from: Int, to: Int): String {
        val a = minOf(from, to).coerceIn(0, lines.lastIndex); val b = maxOf(from, to).coerceIn(0, lines.lastIndex)
        return lines.subList(a, b + 1).joinToString("\n")
    }

    fun text(): String = lines.joinToString("\n")
}

/** Range selection by tapping line numbers: the first tap anchors, the second closes the range, a third starts over. */
data class LineSelection(val anchor: Int? = null, val end: Int? = null) {
    val active: Boolean get() = anchor != null
    val from: Int? get() = anchor?.let { a -> minOf(a, end ?: a) }
    val to: Int? get() = anchor?.let { a -> maxOf(a, end ?: a) }

    fun tap(line: Int): LineSelection = when {
        anchor == null -> LineSelection(line, null)
        end == null -> LineSelection(anchor, line)
        else -> LineSelection(line, null)
    }

    fun contains(line: Int): Boolean = from?.let { f -> line >= f && line <= to!! } ?: false
    fun count(): Int = if (from == null) 0 else to!! - from!! + 1
    fun text(doc: SourceDoc): String = if (from == null) "" else doc.slice(from!!, to!!)
}
