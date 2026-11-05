package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.PmTables
import com.diegonmarcos.capitalfinterm.RiskTabs
import com.diegonmarcos.capitalfinterm.ui.fin.FinChip
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinMatrix
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette

/** #1478 test tags of a PM page: its filter tab row, one tab (+ id), one table row (+ index) and the page body (+ table key). */
const val PM_TABS = "pm_tabs:"
const val PM_TAB = "pm_tab:"
const val PM_ROW = "pm_row:"
const val PM_PAGE = "pm_page:"

/**
 * #1478 a PM sub-page: one table of data/pm.json drawn as a dense FinMatrix (20dp rows, the ramp's small mono, no scrolling). A table whose rows name
 * a portfolio dashboard (Vol) gets the risk-level filter tabs of dashboards.json::pm_risk_tabs and a tap on a row opens the portfolio; a table with
 * `tabs` (Profiles) gets one chip per tab; the rest (Commodities) is the matrix alone. Every word, row and number is the declaration's.
 */
@Composable
fun PmPage(key: String, nav: FinNav) {
    val ctx = LocalContext.current
    val json = remember { AssetData.read(ctx, AssetData.PM) }
    val dashboards = remember { AssetData.dashboards(ctx) }
    val tabs = remember { RiskTabs.parse(AssetData.read(ctx, AssetData.DASHBOARDS)) }
    val table = remember(key) { PmTables.parse(json, key) { id -> dashboards.firstOrNull { it.id == id }?.entry ?: id } }
    val profiles = remember(key) { PmTables.profiles(json, key) }
    val p = LocalFinPalette.current
    var sel by rememberSaveable(key) { mutableStateOf(RiskTabs.ALL) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(PM_PAGE + key)) {
        val width = maxWidth
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            if (profiles != null) {
                val (head, groups) = profiles
                val g = groups.firstOrNull { it.id == sel } ?: groups.firstOrNull()
                Chips(key, groups.map { it.id to it.label }, g?.id.orEmpty()) { sel = it }
                if (g != null) {
                    Text(g.leads, color = p.text2, style = FinType.caption)
                    FinMatrix(key, head.columns, g.rows, emptyList(), emptyList(), width, head.labels)
                }
                Text(head.note, color = p.text3, style = FinType.caption)
            } else if (table != null) {
                val withTabs = table.rows.any { it.dashboard.isNotEmpty() } && tabs.isNotEmpty()
                val risk = { id: String -> dashboards.firstOrNull { it.id == id }?.risk.orEmpty() }
                val rows = if (withTabs) PmTables.filter(table.rows, sel, risk) else table.rows
                if (withTabs) Chips(key, tabs.map { it.id to it.label }, sel) { sel = it }
                FinMatrix(
                    key, table.columns, PmTables.matrixRows(rows), PmTables.heat(rows, table.heat), emptyList(), width, table.labels,
                    onRow = if (withTabs) ({ i: Int -> nav.openDashboard(rows[i].dashboard) }) else null,
                )
                Text(table.note, color = p.text3, style = FinType.caption)
                table.tool?.let { (label, id) -> Text(label, Modifier.clickable { nav.openDashboard(id) }.testTag(PM_ROW + id), color = p.accent, style = FinType.title) }
            }
        }
    }
}

/** The chip row of a PM page (one scrollable row): [tabs] are (id, label), [selected] the id on. */
@Composable
private fun Chips(key: String, tabs: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(PM_TABS + key), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        tabs.forEach { (id, label) -> FinChip(label, id == selected, Modifier.testTag(PM_TAB + id)) { onSelect(id) } }
    }
}
