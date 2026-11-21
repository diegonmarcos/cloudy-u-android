package com.diegonmarcos.superapp.browser

import android.util.Log

/**
 * #1841 the browser's own structured app log. Before it, the browser's logcat held framework noise only: nothing said what a tab or the
 * lifecycle did. One line per event, `key=value` pairs, under one tag per concern:
 *
 *   CBrowser/Life    process start, onCreate / onResume / onPause / onStop / onDestroy of the host, window focus
 *   CBrowser/Tab     create, load start / finish / error, restore (source), close, switch
 *   CBrowser/Render  renderer gone (didCrash, priority at exit), recovery, the black-page watchdog, loop guard
 *   CBrowser/Mem     onTrimMemory level (named), heap, the live WebView count
 *   CBrowser/Keep    keep-alive state: battery-optimisation exemption, foreground service, renderer priority policy
 *   CBrowser/Core    which engine answers (Rust core or the Kotlin fallback), and why a fallback happened
 *
 * NEVER a secret: urls are logged as host + path ([url]): no scheme credentials, no query, no fragment (tokens live there), and a
 * long path is cut. Page titles and form values are not logged at all. The in-app Logcat page (Configs > Logcat) reads this process's
 * logcat, so these lines show there with no extra wiring; [recent] also keeps a bounded ring for tests and the crash report.
 */
object BrowserLog {
    const val LIFE = "CBrowser/Life"
    const val TAB = "CBrowser/Tab"
    const val RENDER = "CBrowser/Render"
    const val MEM = "CBrowser/Mem"
    const val KEEP = "CBrowser/Keep"
    const val CORE = "CBrowser/Core"

    private const val RING = 400
    private val ring = ArrayDeque<String>()

    /** Where lines go: priority, tag, line. Android's Log by default; a JVM test swaps it. */
    @Volatile var sink: (Int, String, String) -> Unit = { p, tag, line -> runCatching { Log.println(p, tag, line) } }

    /** `https://user:pw@host:8443/a/b?token=x#f` -> `host:8443/a/b`. Never the query, fragment or credentials; "-" when unparseable. */
    fun url(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return "-"
        val lower = s.lowercase()
        if (lower.startsWith("data:")) return "data:"
        if (lower.startsWith("javascript:")) return "javascript:"
        if (lower.startsWith("blob:")) return "blob:"
        if (lower.startsWith("about:")) return s.substringBefore('?').substringBefore('#').take(40)
        val noScheme = if (s.contains("://")) s.substringAfter("://") else s
        val cut = noScheme.substringBefore('#').substringBefore('?')
        val hostPath = cut.substringAfter('@')   // userinfo (user:pw@) never
        return if (hostPath.length > 96) hostPath.take(96) + "~" else hostPath.ifEmpty { "-" }
    }

    /** `ev=<event> k=v k=v`: values with spaces or `=` are made single tokens. One line, no newline. */
    fun line(event: String, vararg kv: Pair<String, Any?>): String {
        val sb = StringBuilder("ev=").append(event)
        for ((k, v) in kv) sb.append(' ').append(k).append('=').append(tok(v))
        return sb.toString()
    }

    private fun tok(v: Any?): String {
        val s = (v ?: "-").toString().replace('\n', ' ').replace('\r', ' ').trim()
        return if (s.isEmpty()) "-" else s.replace(' ', '_').take(160)
    }

    fun i(tag: String, event: String, vararg kv: Pair<String, Any?>) = emit(Log.INFO, tag, line(event, *kv))
    fun w(tag: String, event: String, vararg kv: Pair<String, Any?>) = emit(Log.WARN, tag, line(event, *kv))
    fun d(tag: String, event: String, vararg kv: Pair<String, Any?>) = emit(Log.DEBUG, tag, line(event, *kv))

    private fun emit(priority: Int, tag: String, text: String) {
        synchronized(ring) { if (ring.size >= RING) ring.removeFirst(); ring.addLast("$tag $text") }
        sink(priority, tag, text)
    }

    /** The newest lines, oldest first (a bounded ring: tests and the crash report read it). */
    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    fun clearRecent() = synchronized(ring) { ring.clear() }

    /** The short id a tab is logged under: its key's first 8 characters (the key is an opaque id, not a url). */
    fun tab(key: String?): String = key?.take(8) ?: "-"

    /** onTrimMemory levels by name (ComponentCallbacks2.TRIM_MEMORY_*). */
    fun trimName(level: Int): String = when (level) {
        5 -> "RUNNING_MODERATE"
        10 -> "RUNNING_LOW"
        15 -> "RUNNING_CRITICAL"
        20 -> "UI_HIDDEN"
        40 -> "BACKGROUND"
        60 -> "MODERATE"
        80 -> "COMPLETE"
        else -> "LEVEL_$level"
    }
}
