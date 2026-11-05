package com.diegonmarcos.capitalfinterm

import org.json.JSONObject

/** #1462 one filter tab of the PM Vol page (data/dashboards.json::pm_risk_tabs): `all`, or a risk level with its [market] (DM | EM) and the highest equity share it takes. */
data class RiskTab(val id: String, val label: String, val market: String, val equityMax: Int)

/** The PM Vol filter tabs: the declared tabs and the tier of each portfolio. */
object RiskTabs {
    const val ALL = "all"

    fun parse(dashboardsJson: String): List<RiskTab> {
        val a = runCatching { JSONObject(dashboardsJson).getJSONObject("pm_risk_tabs").getJSONArray("tabs") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { RiskTab(it.optString("id"), it.optString("label"), it.optString("market"), it.optInt("equity_max", -1)) }
    }

    /** The tier of a portfolio: the first tier of its [market] whose `equity_max` takes its equity share (DM below EM, then more equity = higher risk). */
    fun tierOf(tabs: List<RiskTab>, market: String, equityPct: Int): String? = tabs.firstOrNull { it.id != ALL && it.market == market && equityPct <= it.equityMax }?.id
}
