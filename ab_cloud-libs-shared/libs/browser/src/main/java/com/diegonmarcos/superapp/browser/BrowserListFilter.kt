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

    /** #1841 a long history is filtered by the browser core (Rust history_query, input order kept); a short one, and a phone without the core, in Kotlin. */
    fun visits(query: String, visits: List<BrowserVisit>): List<BrowserVisit> {
        if (visits.size < CORE_MIN || query.isBlank()) return visits.filter { matches(query, it.title, it.url) }
        val rows = visits.filter { it.url.isNotBlank() }   // the core drops a row without an address, so its indices count the rest
        val a = com.diegonmarcos.cloudlib.browsercore.BrowserCore.lists("history_query", org.json.JSONObject().put("query", query)
            .put("visits", org.json.JSONArray().also { arr -> rows.forEach { arr.put(org.json.JSONObject().put("url", it.url).put("title", it.title).put("ts", it.ts)) } }))
        val idx = a.optJSONArray("indices") ?: return visits.filter { matches(query, it.title, it.url) }
        return (0 until idx.length()).map { rows[idx.getInt(it)] }
    }

    /** From this many rows the filter goes through the core. */
    const val CORE_MIN = 200
}
