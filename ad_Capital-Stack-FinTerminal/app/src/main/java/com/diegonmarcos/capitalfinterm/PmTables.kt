package com.diegonmarcos.capitalfinterm

import org.json.JSONObject

/** #1478 one table of the PM sub-pages (data/pm.json): the header, the label column widths, the rows ([dashboard] = the portfolio a Vol row opens, "" = none; [name] = a declared row name), the note and an optional [tool] link (label, dashboard). */
data class PmTable(val columns: List<String>, val labels: List<Float>, val rows: List<PmRow>, val note: String, val heat: IntRange?, val tool: Pair<String, String>? = null)
data class PmRow(val name: String, val dashboard: String, val cells: List<String>)
/** One Profiles tab: the cycle phases it leads and its companies (ticker, company, sector). */
data class PmProfile(val id: String, val label: String, val leads: String, val rows: List<List<String>>)

/** The Vol, Commodities and Profiles tables of the PM sub-pages, read from data/pm.json: Kotlin names no portfolio, commodity, currency, company or column. */
object PmTables {
    private fun strings(a: org.json.JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun table(o: JSONObject?, nameOf: (String) -> String): PmTable? {
        o ?: return null
        val rows = o.optJSONArray("rows")
        val heat = o.optJSONArray("heat")
        return PmTable(
            columns = strings(o.optJSONArray("columns")), labels = strings(o.optJSONArray("label_fracs")).map { it.toFloat() }, note = o.optString("note"),
            rows = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { r ->
                PmRow(r.optString("name").ifEmpty { nameOf(r.optString("dashboard")) }, r.optString("dashboard"), strings(r.optJSONArray("cells")))
            },
            heat = heat?.let { it.optInt(0)..it.optInt(1) },
            tool = o.optJSONObject("tool")?.let { it.optString("label") to it.optString("dashboard") },
        )
    }

    /** The table [key] ("vol" | "commodities"); a Vol row's name is the `entry` of its dashboard ([nameOf]). */
    fun parse(json: String, key: String, nameOf: (String) -> String = { it }): PmTable? =
        runCatching { table(JSONObject(json).optJSONObject(key), nameOf) }.getOrNull()

    /** A table with `tabs` (Profiles): its shared column header and label widths, and the tabs; null when the table has no tabs. */
    fun profiles(json: String, key: String): Pair<PmTable, List<PmProfile>>? = runCatching {
        val o = JSONObject(json).getJSONObject(key)
        val tabs = o.getJSONArray("tabs")
        table(o, { it })!! to (0 until tabs.length()).map { tabs.getJSONObject(it) }.map { t ->
            val rows = t.getJSONArray("rows")
            PmProfile(t.optString("id"), t.optString("label"), t.optString("leads"), (0 until rows.length()).map { strings(rows.getJSONArray(it)) })
        }
    }.getOrNull()

    /** The Vol filter: every row for `all`, else the rows whose portfolio's declared risk tier ([riskOf] a dashboard id) is [tab]. */
    fun filter(rows: List<PmRow>, tab: String, riskOf: (String) -> String): List<PmRow> = if (tab == RiskTabs.ALL) rows else rows.filter { riskOf(it.dashboard) == tab }

    /** Heat of each cell: its weight over the largest weight of the [range] columns (0..1, green), null outside the range or when not a number. */
    fun heat(rows: List<PmRow>, range: IntRange?): List<List<Float?>> {
        if (range == null) return rows.map { emptyList() }
        val max = rows.flatMap { r -> range.map { r.cells.getOrNull(it - 1)?.toFloatOrNull() } }.filterNotNull().maxOrNull()?.takeIf { it > 0f } ?: return rows.map { emptyList() }
        return rows.map { r -> List(r.cells.size + 1) { i -> if (i in range) r.cells.getOrNull(i - 1)?.toFloatOrNull()?.div(max) else null } }
    }

    /** The matrix rows: the label cell then the declared cells. */
    fun matrixRows(rows: List<PmRow>): List<List<String>> = rows.map { listOf(it.name) + it.cells }
}
