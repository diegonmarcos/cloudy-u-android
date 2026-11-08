package com.diegonmarcos.superapp.browser

import org.json.JSONObject

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

/**
 * Graphics tab: what the injected [JS] sampled on the current page (FPS, frame time, WebGL draw calls and triangles per frame,
 * GPU name). Pure parse of the BrowserJs.await answer, JVM-tested; every figure the page cannot give reads "n/a" with a note.
 */
data class GraphicsStats(
    val available: Boolean, val fps: Double?, val frameMs: Double?, val draws: Long?, val tris: Long?,
    val gpu: String?, val webgl: Boolean, val note: String?,
) {
    /** label / value rows for the HUD, "n/a" where unknown. */
    fun rows(): List<Pair<String, String>> = listOf(
        "FPS" to (fps?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: NA),
        "Frame time" to (frameMs?.let { "%.1f ms".format(java.util.Locale.ROOT, it) } ?: NA),
        "Draw calls / frame" to (draws?.toString() ?: NA),
        "Triangles / frame" to (tris?.toString() ?: NA),
        "GPU" to (gpu ?: NA),
    )

    fun asText(): String = rows().joinToString("\n") { "${it.first}: ${it.second}" } + (note?.let { "\nNote: $it" } ?: "")

    companion object {
        const val NA = "n/a"
        const val BLOCKED = "the page blocked the injected script or did not answer"

        private fun none(note: String) = GraphicsStats(false, null, null, null, null, null, false, note)

        /** [raw] is BrowserJs.await's answer: `{"v":{fps,ms,draws,tris,gpu,webgl}}`, `{"error":..}` or null. */
        fun parse(raw: String?): GraphicsStats {
            if (raw.isNullOrBlank()) return none(BLOCKED)
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return none(BLOCKED)
            if (o.has("error")) return none("script error: ${o.optString("error")}")
            val v = o.optJSONObject("v") ?: return none(BLOCKED)
            fun num(k: String): Double? = if (v.has(k) && !v.isNull(k)) v.optDouble(k, Double.NaN).takeIf { !it.isNaN() } else null
            val fps = num("fps")?.takeIf { it > 0 }
            val webgl = v.optBoolean("webgl", false)
            val gpu = v.optString("gpu").trim().takeIf { webgl && it.isNotEmpty() && it != "null" }
            val note = when {
                fps == null -> "sampling: refresh in a second"
                !webgl -> "no WebGL context on this page: FPS and frame time only"
                else -> null
            }
            return GraphicsStats(true, fps, num("ms")?.takeIf { fps != null && it > 0 },
                if (webgl) num("draws")?.toLong() else null, if (webgl) num("tris")?.toLong() else null, gpu, webgl, note)
        }

        /**
         * Idempotent: the first run installs a requestAnimationFrame loop and wraps the WebGL / WebGL2 draw calls (counted per
         * ~1 s window, shown per frame) and reads the GPU from WEBGL_debug_renderer_info; later runs only report.
         */
        const val JS = "(function(){var g=window.__c3gfx;if(!g){g=window.__c3gfx={n:0,t0:performance.now(),last:performance.now(),ms:0,fps:0,d:0,t:0,dpf:0,tpf:0,gpu:null,webgl:false};" +
            "function tri(m,c){return m===4?Math.floor(c/3):(m===5||m===6)?Math.max(c-2,0):0}" +
            "function wrap(C){if(!C||!C.prototype)return;var P=C.prototype;g.webgl=true;" +
            "['drawArrays','drawElements','drawArraysInstanced','drawElementsInstanced'].forEach(function(k){var o=P[k];if(!o)return;" +
            "P[k]=function(){var a=arguments,c=k.indexOf('Elements')>=0?a[1]:a[2],i=k.indexOf('Instanced')>=0?(a[a.length-1]||1):1;g.d++;g.t+=tri(a[0],c)*i;return o.apply(this,a)}})}" +
            "try{wrap(window.WebGLRenderingContext);wrap(window.WebGL2RenderingContext)}catch(e){}" +
            "try{var cv=document.createElement('canvas'),gl=cv.getContext('webgl')||cv.getContext('experimental-webgl');if(gl){g.webgl=true;" +
            "var x=gl.getExtension('WEBGL_debug_renderer_info');g.gpu=String(x?gl.getParameter(x.UNMASKED_RENDERER_WEBGL):gl.getParameter(gl.RENDERER))}}catch(e){}" +
            "(function loop(t){var d=t-g.last;g.last=t;g.n++;g.ms=g.ms?g.ms*0.9+d*0.1:d;" +
            "if(t-g.t0>=1000){g.fps=g.n*1000/(t-g.t0);g.dpf=Math.round(g.d/g.n);g.tpf=Math.round(g.t/g.n);g.n=0;g.d=0;g.t=0;g.t0=t}" +
            "requestAnimationFrame(loop)})(performance.now())}" +
            "return {fps:g.fps,ms:g.ms,draws:g.dpf,tris:g.tpf,gpu:g.gpu,webgl:g.webgl}})()"
    }
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
