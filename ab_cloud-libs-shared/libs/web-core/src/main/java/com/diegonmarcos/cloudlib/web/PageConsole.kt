package com.diegonmarcos.cloudlib.web

/**
 * The console of every page a fleet web view shows (#1537): [CloudChromeClient.onConsoleMessage] (and the Browser's own client)
 * record each message here. A bounded in-memory ring feeds the "Page diagnostics" sheet, and every entry also goes to logcat
 * under tag [TAG], which the app's log export (libs:devtools LogPipe) already collects, so a JS error on a page that renders
 * grey is readable without a debugger. Pure apart from the default [sink] (a Log call that never throws).
 */
object PageConsole {
    const val TAG = "CloudWebConsole"
    const val CAPACITY = 200
    const val MAX_MESSAGE = 2000

    data class Entry(val ts: Long, val level: String, val message: String, val source: String, val line: Int, val page: String) {
        fun location(): String = if (source.isBlank()) "" else if (line > 0) "$source:$line" else source
        fun isError(): Boolean = level == "ERROR"
    }

    private val ring = ArrayDeque<Entry>()

    /** Where each entry goes besides the ring; null = nowhere. The default writes logcat. */
    @Volatile var sink: ((Entry) -> Unit)? = { e -> runCatching { android.util.Log.println(priority(e.level), TAG, format(e)) } }

    fun priority(level: String): Int = when (level) { "ERROR" -> 6; "WARNING" -> 5; "DEBUG", "TIP" -> 3; else -> 4 }

    fun record(level: String, message: String?, source: String?, line: Int, page: String?, now: Long = System.currentTimeMillis()): Entry {
        val e = Entry(now, level.uppercase(), message.orEmpty().take(MAX_MESSAGE), source.orEmpty(), line, page.orEmpty())
        synchronized(ring) {
            ring.addLast(e)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        sink?.invoke(e)
        return e
    }

    fun snapshot(): List<Entry> = synchronized(ring) { ring.toList() }

    /** Errors only, newest last; [page] (a URL prefix) narrows to one page. */
    fun errors(page: String? = null): List<Entry> = snapshot().filter { it.isError() && (page.isNullOrEmpty() || it.page.startsWith(page)) }

    fun clear() = synchronized(ring) { ring.clear() }

    fun format(e: Entry): String = "[${e.level}] ${e.message}" + e.location().let { if (it.isEmpty()) "" else " ($it)" }

    fun text(entries: List<Entry>): String = entries.joinToString("\n") { format(it) }
}
