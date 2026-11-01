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

    fun visits(query: String, visits: List<BrowserVisit>): List<BrowserVisit> = visits.filter { matches(query, it.title, it.url) }
}
