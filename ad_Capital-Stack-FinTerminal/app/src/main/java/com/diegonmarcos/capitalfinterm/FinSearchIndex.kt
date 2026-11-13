package com.diegonmarcos.capitalfinterm

import java.util.Locale

/**
 * The local search catalogue (UI spec 2.10): every symbol the dashboards' panels answered with, the watchlist, every dashboard
 * title. Prefix match on the symbol, substring on the name, the groups in the declared order, 30 results at most.
 */
object FinSearchIndex {
    const val KIND_SYMBOL = "symbol"
    const val KIND_DASHBOARD = "dashboard"

    val GROUPS = listOf("Watchlist", "Equities & indices", "FX", "Rates & yields", "Crypto", "Commodities", "Economic events", "Dashboards")

    data class Item(
        val group: String,
        val badge: String,
        val symbol: String,
        val name: String,
        val kind: String,
        /** A dashboard id for a dashboard; the panel key (dashboard/panel) a symbol was seen on. */
        val target: String,
        val last: String = "",
        val pct: String = "",
    )

    /** The group of a symbol seen on [dashboardId] / [panelId] of tab [tab]. */
    fun groupOf(tab: String, dashboardId: String, panelId: String): Pair<String, String> {
        val k = "$dashboardId/$panelId".lowercase(Locale.ROOT)
        return when {
            "crypto" in k || panelId == "crypto" -> "Crypto" to "CR"
            "fx" in k || "frankfurter" in k || dashboardId == "fx" -> "FX" to "FX"
            "commod" in k || "energy" in k -> "Commodities" to "CO"
            "equity" in k || "cboe" in k || "indices" in k -> "Equities & indices" to "EQ"
            tab == "fi" || tab == "cb" || "ust" in k || "funding" in k || "rates" in k -> "Rates & yields" to "FI"
            else -> "Equities & indices" to "EQ"
        }
    }

    /** The US-listed tickers of the SEC company_tickers.json answer ({"0":{"cik_str":..,"ticker":"AAPL","title":"Apple Inc."},..}): ticker to company name. */
    fun parseTickers(json: String): List<Pair<String, String>> {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return emptyList()
        return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { it.optString("ticker").trim() to it.optString("title").trim() } }
            .filter { it.first.isNotEmpty() }.toList()
    }

    /** Remote symbol search (#1370): the tickers whose symbol starts with the query, then those whose company name contains it, as equity Items. */
    fun remote(tickers: List<Pair<String, String>>, query: String, max: Int = 8): List<Item> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.length < 2) return emptyList()
        val (pre, rest) = tickers.filter { (t, n) -> t.lowercase(Locale.ROOT).startsWith(q) || n.lowercase(Locale.ROOT).contains(q) }
            .partition { it.first.lowercase(Locale.ROOT).startsWith(q) }
        return (pre.sortedBy { it.first.length } + rest).take(max).map { (t, n) -> Item("Equities & indices", "EQ", t, n, KIND_SYMBOL, "remote:sec") }
    }

    fun search(all: List<Item>, query: String, max: Int = 30): List<Item> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        val hits = all.filter { it.symbol.lowercase(Locale.ROOT).startsWith(q) || it.name.lowercase(Locale.ROOT).contains(q) || (it.kind == KIND_DASHBOARD && it.symbol.lowercase(Locale.ROOT).contains(q)) }
            .distinctBy { it.group + "|" + it.symbol }
        val ranked = hits.sortedWith(compareBy({ GROUPS.indexOf(it.group).let { g -> if (g < 0) GROUPS.size else g } }, { if (it.symbol.lowercase(Locale.ROOT).startsWith(q)) 0 else 1 }, { it.symbol.length }))
        return ranked.take(max)
    }
}
