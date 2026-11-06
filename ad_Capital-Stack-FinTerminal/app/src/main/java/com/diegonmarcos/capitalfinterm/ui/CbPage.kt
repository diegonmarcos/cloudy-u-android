package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.CbAggregate
import com.diegonmarcos.capitalfinterm.CbDecl
import com.diegonmarcos.capitalfinterm.CbSnapshot
import com.diegonmarcos.capitalfinterm.CbStore
import com.diegonmarcos.capitalfinterm.CbTable
import com.diegonmarcos.capitalfinterm.CbTables
import com.diegonmarcos.capitalfinterm.Pages
import com.diegonmarcos.capitalfinterm.ui.fin.FinChip
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinMatrix
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** #1539 test tags of the CB page: the page (+ view), a country row (+ id), a control chip (+ id) and the country detail (+ id). */
const val CB_PAGE = "cb_page:"
const val CB_ROW = "cb_row:"
const val CB_DETAIL = "cb_detail:"

/**
 * #1539 a CB sub-page (central-bank macro analysis and predictions): a dense FinMatrix of countries x indicators (or the inverse), the DM / EM / World aggregates
 * on the first rows, a simple or full indicator list, a forecast-year selector, a PPP / market-FX weight toggle, heat against each country's own history. A tap on a country
 * opens its detail (sparkline history and forecast, source and vintage of every value). Every word, country and indicator is the declaration's (data/cb-*.json).
 */
@Composable
fun CbPage(view: String) {
    val ctx = LocalContext.current
    val decl = remember { CbStore.decl(ctx) }
    var snap by remember { mutableStateOf(CbStore.snapshot(ctx)) }
    var full by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf(snap.currentYear) }
    var weight by rememberSaveable { mutableStateOf(decl.defaultWeight) }
    var invert by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val p = LocalFinPalette.current
    val groups = CbTables.groups(decl, full)
    val g = groups.firstOrNull { it.first == group }?.first ?: groups.first().first
    if (detail.isNotEmpty()) {
        CbDetail(decl, snap, detail, full, year) { detail = "" }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(CB_PAGE + view)) {
        val screen = maxWidth
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            Chips {
                FinChip(decl.word("simple"), !full) { full = false }
                FinChip(decl.word("full"), full) { full = true }
                if (view != "predictions") decl.weights.forEach { w -> FinChip(w.label, weight == w.id) { weight = w.id } }
                if (view == "countries") {
                    FinChip(decl.word("rows_countries"), !invert) { invert = false }
                    FinChip(decl.word("rows_indicators"), invert) { invert = true }
                }
                FinChip(if (status == "run") decl.word("refreshing") else decl.word("refresh"), false) {
                    if (status != "run") {
                        status = "run"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { CbStore.refresh(ctx, decl) } }
                            r.getOrNull()?.let { snap = it }
                            status = if (r.isSuccess) "ok" else "fail"
                        }
                    }
                }
            }
            if (view != "predictions") Chips { groups.forEach { (id, label) -> FinChip(label, id == g) { group = id } } }
            Chips { CbTables.years(snap, decl).forEach { y -> FinChip(y.toString(), y == year) { year = y } } }
            val table: CbTable = when (view) {
                "groups" -> CbTables.groups(decl, snap, full, g, year, weight, decl.word("share"))
                "predictions" -> CbTables.predictions(decl, snap)
                else -> CbTables.countries(decl, snap, full, g, year, weight)
            }
            val shown = if (invert && view == "countries") CbTables.transpose(table) else table
            val label = if (invert && view == "countries") 168.dp else 112.dp
            val cols = shown.header.size
            val total = maxOf(screen, label + FinDim.numCol * (cols - 1))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FinMatrix(
                    "cb:$view", shown.header, shown.rows, shown.heat, emptyList(), total, listOf(label / total),
                    onRow = if (!(invert && view == "countries")) ({ i: Int -> shown.ids.getOrNull(i)?.takeIf { it !in AGGREGATES }?.let { detail = it } }) else null,
                )
            }
            Text(
                when (view) { "groups" -> decl.word("note_groups"); "predictions" -> decl.word("note_predictions"); else -> decl.word("note_countries") } +
                    "  " + decl.word("source") + ": " + snap.generated,
                color = p.text3, style = FinType.caption,
            )
            if (status == "ok") Text(decl.word("refreshed") + " " + snap.generated, color = p.text2, style = FinType.caption)
            if (status == "fail") Text(decl.word("na_long"), color = p.down, style = FinType.caption)
        }
    }
}

private val AGGREGATES = setOf(CbAggregate.WORLD, "DM", "EM")

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) { content() }
}

/** The country detail: every indicator of the selected list, the actual, the forecast years, a sparkline of the history and forecast, the source and its vintage. */
@Composable
private fun CbDetail(decl: CbDecl, snap: CbSnapshot, countryId: String, full: Boolean, year: Int, onBack: () -> Unit) {
    val p = LocalFinPalette.current
    val country = decl.countries.first { it.id == countryId }
    val rows = if (full) decl.full.flatMap { g -> listOf<Any>(g.label) + g.items } else decl.simple.flatMap { g -> listOf<Any>(g.label) + g.items.map { s -> decl.indicators.getValue(s.refs.first()) } }
    val years = CbTables.years(snap, decl)
    LazyColumn(Modifier.fillMaxSize().testTag(CB_DETAIL + countryId), contentPadding = PaddingValues(bottom = FinDim.bottomInset), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        item {
            Column(Modifier.clickable(onClick = onBack)) {
                Text("< " + country.name, color = p.accent, style = FinType.title)
                if (country.note.isNotEmpty()) Text(country.note, color = p.text3, style = FinType.caption)
            }
        }
        items(rows) { r ->
            if (r is String) Text(r, color = p.text2, style = FinType.label)
            else {
                val ind = r as com.diegonmarcos.capitalfinterm.CbIndicator
                val s = snap.of(countryId, ind.id)
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(ind.name, Modifier.weight(1f), color = p.text, style = FinType.body)
                        if (s != null) {
                            years.forEach { y -> Text(s.at(y)?.let { CbTables.fmt(it) } ?: decl.word("na"), Modifier.width(FinDim.numCol), color = if (s.isForecast(y)) p.amber else p.text, style = FinType.numSmall) }
                            Spark(s.hist.toSortedMap().values.toList(), s.fc.toSortedMap().values.toList())
                        } else Text(if (ind.modelOnly) decl.word("model_only") else decl.word("na_long"), color = p.text3, style = FinType.caption)
                    }
                    if (s != null) {
                        Text(ind.unit + "  " + decl.word("source") + ": " + s.source + ", " + decl.word("vintage") + " " + s.vintage + (if (s.note.isNotEmpty()) ", " + s.note else ""), color = p.text3, style = FinType.caption)
                        s.projections.forEach { pr -> Text(pr.label + ": " + pr.fc.toSortedMap().entries.joinToString(" ") { "${it.key} ${CbTables.fmt(it.value)}" } + ", " + decl.word("vintage") + " " + pr.vintage, color = p.text3, style = FinType.caption) }
                    }
                }
            }
        }
    }
}

/** A sparkline: the actuals in ink, the forecast in amber. */
@Composable
private fun Spark(hist: List<Double>, fc: List<Double>) {
    val p = LocalFinPalette.current
    val all = hist + fc
    Canvas(Modifier.width(FinDim.symbolCol).height(FinDim.note)) {
        if (all.size < 2) return@Canvas
        val lo = all.min()
        val span = (all.max() - lo).takeIf { it > 1e-9 } ?: 1.0
        fun at(i: Int) = Offset(size.width * i / (all.size - 1), (size.height * (1.0 - (all[i] - lo) / span)).toFloat())
        for (i in 1 until all.size) drawLine(if (i >= hist.size) p.amber else p.text2, at(i - 1), at(i), strokeWidth = 2f)
    }
}
