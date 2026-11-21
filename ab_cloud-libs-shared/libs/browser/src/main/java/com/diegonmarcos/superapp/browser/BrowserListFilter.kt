package com.diegonmarcos.superapp.browser

/**
 * #1410 the Tabs/History search bar: keeps the open tabs and history entries whose title or URL
 * contains the typed text (case-insensitive). A blank query keeps everything, order unchanged.
 */
object BrowserListFilter {
    fun matches(query: String, title: String, url: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || title.lowercase().contains(q) || url.lowercase().contains(q)
    }

    fun tabs(query: String, tabs: List<BrowserTab>): List<BrowserTab> = tabs.filter { matches(query, it.title, it.url) }

    /** #1841 a long history is filtered by the browser core (Rust, input order kept); a short one, and a phone without the core, in Kotlin. */
    fun visits(query: String, visits: List<BrowserVisit>): List<BrowserVisit> {
        if (visits.size < CORE_MIN || query.isBlank()) return visits.filter { matches(query, it.title, it.url) }
        val idx = com.diegonmarcos.cloudlib.browsercore.BrowserCore.search(
            visits.map { com.diegonmarcos.cloudlib.browsercore.SearchEntry(it.title, it.url, it.ts) }, query, Int.MAX_VALUE, ranked = false)
        return idx.map { visits[it] }
    }

    /** From this many rows the filter goes through the core. */
    const val CORE_MIN = 200
}
