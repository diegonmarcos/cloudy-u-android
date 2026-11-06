package com.diegonmarcos.capitalfinterm.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.PmPerfDecl
import com.diegonmarcos.capitalfinterm.PmPerfStore
import com.diegonmarcos.capitalfinterm.PmPerformance
import com.diegonmarcos.capitalfinterm.PmPortfolio
import com.diegonmarcos.capitalfinterm.PmPortfolios
import com.diegonmarcos.capitalfinterm.PmResult
import com.diegonmarcos.capitalfinterm.ui.fin.FinChip
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinMatrix
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** #1539 test tags: the Portfolios page, one of its rows (+ portfolio id) and the Performance page. */
const val PM_PORTFOLIOS = "pm_portfolios"
const val PM_PORTFOLIO = "pm_portfolio:"
const val PM_PERFORMANCE = "pm_performance"

/** The performance numbers of the portfolios: the baked snapshot, refreshed from the price source when the page opens (within the declared TTL) and when asked. */
private class PerfLive(val results: Map<String, PmResult>, val status: String, val refresh: () -> Unit)

@Composable
private fun rememberPerf(decl: PmPerfDecl, portfolios: List<PmPortfolio>): PerfLive {
    val ctx = LocalContext.current
    val baked = remember { PmPerfStore.snapshot(AssetData.read(ctx, PmPerfStore.SNAPSHOT), decl) }
    var results by remember { mutableStateOf(PmPerfStore.merged(baked)) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun run(force: Boolean) {
        if (status == "run") return
        status = "run"
        scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { PmPerfStore.refresh(decl, portfolios, force) } }
            results = PmPerfStore.merged(baked)
            status = if (n.isSuccess) "ok" else "fail"
        }
    }
    LaunchedEffect(Unit) { run(false) }
    return PerfLive(results, status) { run(true) }
}

/** PM > Portfolios: the two tables (Portfolios DM vs EM, Portfolios US Cyclicals) with Portfolio | Vol | Top 3 | See all; a tap on a row lists its full allocation. */
@Composable
fun PortfoliosPanel() {
    val ctx = LocalContext.current
    val json = remember { AssetData.read(ctx, AssetData.PM) }
    val dashboards = remember { AssetData.dashboards(ctx) }
    val decl = remember { PmPortfolios.parse(json) { id -> dashboards.firstOrNull { it.id == id }?.entry ?: id } }
    val perf = remember { PmPortfolios.perf(json) }
    val live = rememberPerf(perf, decl.portfolios)
    var open by rememberSaveable { mutableStateOf("") }
    val p = LocalFinPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag(PM_PORTFOLIOS)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { FinChip(if (live.status == "run") perf.word("refreshing") else perf.word("refresh"), false) { live.refresh() } }
            for (t in decl.tables) {
                val ps = decl.portfolios.filter { it.table == t.id }
                Text(t.title, color = p.text, style = FinType.title)
                val rows = ps.map { pf ->
                    val vol = if (pf.historyVol) live.results[pf.id]?.vol1y?.let { String.format(Locale.ROOT, "%.1f", it) } else pf.modelVol
                    listOf(pf.name, vol ?: perf.word("na"), if (pf.legs.isEmpty()) perf.word("na") else PmPortfolios.topText(pf.legs, decl.top), decl.seeAll)
                }
                FinMatrix("pm:" + t.id, decl.columns, rows, emptyList(), emptyList(), maxWidth, decl.labels, onRow = { i -> open = if (open == ps[i].id) "" else ps[i].id })
                ps.firstOrNull { it.id == open }?.let { pf ->
                    Column(Modifier.testTag(PM_PORTFOLIO + pf.id)) {
                        Text(decl.allTitle + ": " + pf.name, color = p.text2, style = FinType.label)
                        if (pf.legs.isEmpty()) Text(perf.word("na_long"), color = p.text3, style = FinType.caption)
                        pf.legs.forEach { l -> Text(l.name + (if (l.detail.isNotEmpty()) "  " + l.detail else "") + "  " + PmPortfolios.weight(l.weight) + decl.weightUnit, color = p.text, style = FinType.numSmall) }
                    }
                }
            }
            Text(decl.note, color = p.text3, style = FinType.caption)
        }
    }
}

/** PM > Performance: every portfolio of both tables, Today | Last 7d | Last 30d | MTD | YTD | Last 1y | Last 3y CAGR from real delayed closes, each row with its as-of date. */
@Composable
fun PerformancePanel() {
    val ctx = LocalContext.current
    val json = remember { AssetData.read(ctx, AssetData.PM) }
    val dashboards = remember { AssetData.dashboards(ctx) }
    val decl = remember { PmPortfolios.parse(json) { id -> dashboards.firstOrNull { it.id == id }?.entry ?: id } }
    val perf = remember { PmPortfolios.perf(json) }
    val live = rememberPerf(perf, decl.portfolios)
    val p = LocalFinPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag(PM_PERFORMANCE)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { FinChip(if (live.status == "run") perf.word("refreshing") else perf.word("refresh"), false) { live.refresh() } }
            val rows = ArrayList<List<String>>(); val heat = ArrayList<List<Float?>>()
            for (pf in decl.portfolios) {
                val r = live.results[pf.id]
                val cells = perf.windows.map { w -> r?.values?.get(w.id)?.let { PmPerformance.format(it) } ?: perf.word("na") }
                rows += listOf(pf.name) + cells + (r?.asOf?.toString() ?: perf.word("na"))
                heat += listOf<Float?>(null) + perf.windows.map { w -> r?.values?.get(w.id)?.let { (it * 100.0 / perf.heatPct).coerceIn(-1.0, 1.0).toFloat() } } + listOf<Float?>(null)
            }
            FinMatrix("pm:performance", perf.columns, rows, heat, emptyList(), maxWidth, perf.labels)
            Text(perf.word("rebalance") + ": " + perf.word("rebalance_rule") + ".  " + perf.word("note"), color = p.text3, style = FinType.caption)
            Text(perf.word("source") + ": " + perf.source, color = p.text3, style = FinType.caption)
            live.results.values.mapNotNull { it.na }.distinct().forEach { Text(it, color = p.text3, style = FinType.caption) }
        }
    }
}
