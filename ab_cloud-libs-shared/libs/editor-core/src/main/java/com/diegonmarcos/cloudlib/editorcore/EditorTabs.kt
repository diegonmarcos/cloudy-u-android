package com.diegonmarcos.cloudlib.editorcore

import java.net.URLDecoder
import java.net.URLEncoder

/** One opened file. The path is the tab's identity: opening a path that is already open switches to its tab. */
data class EditorTab(val path: String, val mode: EditorMode = EditorMode.View) {
    val title: String get() = path.substringAfterLast('/').ifEmpty { path }
}

/**
 * The browser-like tab strip as a value: the opened files in order and the active one. Every operation returns a new
 * value (the host keeps it in state), and [encode] / [decode] persist it across restarts as plain text.
 */
data class EditorTabs(val tabs: List<EditorTab> = emptyList(), val activePath: String? = null) {

    val active: EditorTab? get() = tabs.firstOrNull { it.path == activePath }

    /** Opens [path] in a new tab at the end, or switches to it when it is open already; either way it becomes active. */
    fun open(path: String, mode: EditorMode = EditorMode.View): EditorTabs {
        if (path.isBlank()) return this
        return if (tabs.any { it.path == path }) copy(activePath = path) else EditorTabs(tabs + EditorTab(path, mode), path)
    }

    /** Switches to [path]; an unknown path changes nothing. */
    fun activate(path: String): EditorTabs = if (tabs.any { it.path == path }) copy(activePath = path) else this

    /** Closes [path]. Closing the active tab activates its right neighbour, else its left one; closing another tab keeps the active one. */
    fun close(path: String): EditorTabs {
        val i = tabs.indexOfFirst { it.path == path }
        if (i < 0) return this
        val rest = tabs.filterIndexed { n, _ -> n != i }
        val next = if (path != activePath) activePath else rest.getOrNull(i)?.path ?: rest.getOrNull(i - 1)?.path
        return EditorTabs(rest, next)
    }

    fun setMode(path: String, mode: EditorMode): EditorTabs = copy(tabs = tabs.map { if (it.path == path) it.copy(mode = mode) else it })

    fun toggleMode(path: String): EditorTabs = copy(tabs = tabs.map { if (it.path == path) it.copy(mode = it.mode.toggled()) else it })

    /** Plain text, one line per fact, paths percent-encoded so a tab or a newline in a name cannot break the format. */
    fun encode(): String = buildString {
        append("v1\n")
        append("active\t").append(enc(activePath.orEmpty())).append('\n')
        for (t in tabs) append("tab\t").append(t.mode.name).append('\t').append(enc(t.path)).append('\n')
    }

    companion object {
        /** Reads what [encode] wrote; anything unreadable gives the empty strip, never a crash. */
        fun decode(text: String): EditorTabs {
            val lines = text.lines()
            if (lines.firstOrNull() != "v1") return EditorTabs()
            var active: String? = null
            val tabs = ArrayList<EditorTab>()
            for (line in lines.drop(1)) {
                val f = line.split('\t')
                when {
                    f.size >= 2 && f[0] == "active" -> active = dec(f[1]).ifEmpty { null }
                    f.size >= 3 && f[0] == "tab" -> dec(f[2]).takeIf { it.isNotEmpty() && tabs.none { t -> t.path == it } }?.let { tabs += EditorTab(it, EditorMode.of(f[1])) }
                }
            }
            return EditorTabs(tabs, active?.takeIf { a -> tabs.any { it.path == a } } ?: tabs.firstOrNull()?.path)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault("")
    }
}
