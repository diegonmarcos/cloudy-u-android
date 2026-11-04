package com.diegonmarcos.capitalfinterm

import org.json.JSONObject

/** #1462 one top tab of the PM Homepage (data/dashboards.json::pm_risk_tabs): `all`, or a risk tier with its section [header], its [market] (DM | EM) and the highest equity share it takes. */
data class RiskTab(val id: String, val label: String, val header: String, val market: String, val equityMax: Int)

/** The PM Homepage organised by risk: the declared tabs, the tier of each card, and the groups a tab shows (All: one section header per tier). */
object RiskTabs {
    const val ALL = "all"

    fun parse(dashboardsJson: String): List<RiskTab> {
        val a = runCatching { JSONObject(dashboardsJson).getJSONObject("pm_risk_tabs").getJSONArray("tabs") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { RiskTab(it.optString("id"), it.optString("label"), it.optString("header"), it.optString("market"), it.optInt("equity_max", -1)) }
    }

    /** The tier of a portfolio: the first tier of its [market] whose `equity_max` takes its equity share (DM below EM, then more equity = higher risk). */
    fun tierOf(tabs: List<RiskTab>, market: String, equityPct: Int): String? = tabs.firstOrNull { it.id != ALL && it.market == market && equityPct <= it.equityMax }?.id

    /**
     * The groups to draw for [selected]: in All every tier in order with its header (cards with no tier, e.g. the analytics tool, first with no header);
     * in a tier tab that tier's cards alone, with no header. [riskOf] maps a card's dashboard id to its declared tier.
     */
    fun <T> groups(cards: List<T>, dashboardOf: (T) -> String, riskOf: (String) -> String, tabs: List<RiskTab>, selected: String): List<Pair<String, List<T>>> {
        if (selected != ALL) return listOf("" to cards.filter { riskOf(dashboardOf(it)) == selected })
        val none = cards.filter { riskOf(dashboardOf(it)).isEmpty() }
        return (if (none.isEmpty()) emptyList() else listOf("" to none)) +
            tabs.filter { it.id != ALL }.map { t -> t.header to cards.filter { riskOf(dashboardOf(it)) == t.id } }.filter { it.second.isNotEmpty() }
    }
}
