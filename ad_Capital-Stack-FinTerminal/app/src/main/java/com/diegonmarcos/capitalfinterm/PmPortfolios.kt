package com.diegonmarcos.capitalfinterm

import java.util.Locale
import org.json.JSONObject

/** One allocation of a portfolio: its name, its weight in percent and the price-history proxy symbol (null = no free history). */
data class PmLeg(val name: String, val weight: Double, val symbol: String?, val detail: String = "")

/** One portfolio of the Portfolios page: a row of the DM vs EM table or of a US cyclical profile, with its legs, its declared model Vol and the reason it has no free history, if any. */
data class PmPortfolio(val id: String, val table: String, val name: String, val legs: List<PmLeg>, val modelVol: String?, val noHistory: String?, val historyVol: Boolean)

/** The window of the Performance page (data/pm.json performance.windows). */
data class PmWindow(val id: String, val label: String, val days: Int, val years: Int, val cagr: Boolean)

data class PmPerfDecl(
    val heatPct: Double, val windows: List<PmWindow>, val columns: List<String>, val labels: List<Float>, val rebalance: String, val historyUrl: String, val userAgent: String, val source: String, val ttlSeconds: Long, val ui: Map<String, String>,
) {
    fun word(k: String): String = ui[k].orEmpty()
}

data class PmTableDecl(val id: String, val title: String)
data class PmPortfoliosDecl(val weightUnit: String, val columns: List<String>, val labels: List<Float>, val top: Int, val seeAll: String, val allTitle: String, val note: String, val tables: List<PmTableDecl>, val portfolios: List<PmPortfolio>)

/** #1539 the portfolios of the PM Portfolios and Performance pages, read from data/pm.json (vol, profiles, portfolios, performance): Kotlin names no portfolio, company, proxy or column. */
object PmPortfolios {
    private fun strs(a: org.json.JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun perf(json: String): PmPerfDecl {
        val p = JSONObject(json).getJSONObject("performance")
        val w = p.getJSONArray("windows"); val h = p.getJSONObject("history"); val ui = p.getJSONObject("ui")
        return PmPerfDecl(
            p.getDouble("heat_pct"), (0 until w.length()).map { w.getJSONObject(it) }.map { PmWindow(it.getString("id"), it.getString("label"), it.optInt("days"), it.optInt("years"), it.optBoolean("cagr")) },
            strs(p.getJSONArray("columns")), strs(p.getJSONArray("label_fracs")).map { it.toFloat() }, p.getString("rebalance"), h.getString("url"), h.getString("user_agent"), h.getString("source"), h.getLong("ttl_s"),
            ui.keys().asSequence().associateWith { ui.getString(it) } + ("rebalance_rule" to p.getJSONObject("rebalance_rules").getString(p.getString("rebalance"))),
        )
    }

    /** The portfolios of both tables: the DM vs EM rows (legs from their allocation cells, named by their dashboard) and the US cyclical profiles (equal weights). */
    fun parse(json: String, nameOf: (String) -> String): PmPortfoliosDecl {
        val root = JSONObject(json)
        val d = root.getJSONObject("portfolios"); val perf = root.getJSONObject("performance")
        val proxies = perf.getJSONObject("proxies"); val none = perf.getJSONObject("no_history")
        val out = ArrayList<PmPortfolio>(); val tables = ArrayList<PmTableDecl>()
        val ts = d.getJSONArray("tables")
        for (i in 0 until ts.length()) {
            val t = ts.getJSONObject(i); val src = root.getJSONObject(t.getString("table"))
            tables += PmTableDecl(t.getString("id"), t.getString("title"))
            if (t.has("weighting")) {   // equal-weight profiles: one portfolio per tab, its rows the companies (ticker, company, sector)
                val tabs = src.getJSONArray("tabs")
                for (k in 0 until tabs.length()) {
                    val tab = tabs.getJSONObject(k); val rows = tab.getJSONArray("rows"); val n = rows.length()
                    out += PmPortfolio(tab.getString("id"), t.getString("id"), tab.getString("label"), (0 until n).map { rows.getJSONArray(it) }.map { PmLeg(it.getString(0), 100.0 / n, it.getString(0), it.optString(1)) }, null, null, t.optString("vol_basis") == "history")
                }
            } else {
                val rows = src.getJSONArray("rows"); val legs = t.getJSONArray("legs"); val noLegs = strs(t.optJSONArray("no_legs"))
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONObject(r); val id = row.getString("dashboard"); val cells = row.getJSONArray("cells")
                    val ls = if (id in noLegs) emptyList() else (0 until legs.length()).map { legs.getJSONObject(it) }.mapNotNull { l ->
                        val w = cells.optString(l.getInt("cell")).toDoubleOrNull() ?: return@mapNotNull null
                        if (w > 0.0) PmLeg(l.getString("name"), w, proxies.optString(l.getString("name")).ifEmpty { null }) else null
                    }
                    out += PmPortfolio(id, t.getString("id"), nameOf(id), ls, cells.optString(t.getInt("vol_cell")).ifEmpty { null }, if (none.has(id)) none.getString(id) else null, false)
                }
            }
        }
        return PmPortfoliosDecl(d.getString("weight_unit"), strs(d.getJSONArray("columns")), strs(d.getJSONArray("label_fracs")).map { it.toFloat() }, d.getInt("top"), d.getString("see_all"), d.getString("all_title"), d.getString("note"), tables, out)
    }

    /** The [n] largest allocations (weight > 0), the declared order breaking ties. */
    fun top(legs: List<PmLeg>, n: Int): List<PmLeg> = legs.filter { it.weight > 0.0 }.withIndex().sortedWith(compareBy({ -it.value.weight }, { it.index })).take(n).map { it.value }

    fun weight(w: Double): String = if (w == Math.rint(w)) String.format(Locale.ROOT, "%.0f", w) else String.format(Locale.ROOT, "%.1f", w)

    fun topText(legs: List<PmLeg>, n: Int): String = top(legs, n).joinToString(" · ") { it.name + " " + weight(it.weight) }
}
