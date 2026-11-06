package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.CbTable
import com.diegonmarcos.capitalfinterm.FutClass
import com.diegonmarcos.capitalfinterm.FutSpec
import com.diegonmarcos.capitalfinterm.Quotes
import com.diegonmarcos.capitalfinterm.QuotesDecl
import com.diegonmarcos.capitalfinterm.QuotesSnapshot
import com.diegonmarcos.capitalfinterm.QuotesStore
import com.diegonmarcos.capitalfinterm.ui.fin.FinChip
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinMatrix
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** #1539 test tags: a price page (+ view), a futures board (+ class), a board row (+ root id) and the hedge page. */
const val QUOTE_PAGE = "quote_page:"
const val FUT_PAGE = "fut_page:"
const val FUT_ROW = "fut_row:"
const val HEDGE_PAGE = "hedge_page"

/** The quotes of a page: the baked snapshot, refreshed from the source when the page opens (within the declared TTL) and when asked; the footer says as of when and from where. */
private class Live(val decl: QuotesDecl, val snap: QuotesSnapshot, val status: String, val refresh: () -> Unit)

@Composable
private fun rememberLive(symbols: (QuotesDecl) -> List<String>): Live {
    val ctx = LocalContext.current
    val decl = remember { QuotesStore.decl(ctx) }
    var snap by remember { mutableStateOf(QuotesStore.snapshot(ctx)) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun run(force: Boolean) {
        if (status == "run") return
        status = "run"
        scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { QuotesStore.refresh(decl, symbols(decl), force) } }
            snap = QuotesStore.snapshot(ctx)
            status = if (n.isSuccess) "ok" else "fail"
        }
    }
    LaunchedEffect(Unit) { run(false) }
    return Live(decl, snap, status) { run(true) }
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) { content() }
}

@Composable
private fun Footer(l: Live, note: String, newest: Long?) {
    val p = LocalFinPalette.current
    Text(note, color = p.text3, style = FinType.caption)
    Text(l.decl.word("source") + ": " + l.snap.source + ", " + l.decl.word("delay") + ": " + l.snap.delay + (newest?.let { ", " + l.decl.word("asof") + " " + Quotes.time(it) } ?: ""), color = p.text3, style = FinType.caption)
}

@Composable
private fun Table(id: String, t: CbTable, screen: androidx.compose.ui.unit.Dp, label: androidx.compose.ui.unit.Dp, onRow: ((Int) -> Unit)? = null) {
    val total = maxOf(screen, label + FinDim.numCol * (t.header.size - 1))
    Row(Modifier.horizontalScroll(rememberScrollState())) { FinMatrix(id, t.header, t.rows, t.heat, emptyList(), total, listOf(label / total), onRow = onRow) }
}

@Composable
private fun RefreshChip(l: Live) {
    FinChip(if (l.status == "run") l.decl.word("refreshing") else l.decl.word("refresh"), false) { l.refresh() }
}

/** EQ > Exchange rates (view fx_rates) and EQ > Commodity prices (view commodities): dense FinMatrix tables of delayed quotes. */
@Composable
fun QuoteBoardPage(view: String) {
    val l = rememberLive { d -> if (view == "fx_rates") Quotes.symbolsFx(d) else Quotes.symbolsCommodities(d) }
    var base by rememberSaveable { mutableStateOf(l.decl.defaultBase) }
    var matrix by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(QUOTE_PAGE + view)) {
        val screen = maxWidth
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            if (view == "fx_rates") {
                Chips {
                    FinChip(l.decl.word("list"), !matrix) { matrix = false }
                    FinChip(l.decl.word("matrix"), matrix) { matrix = true }
                    RefreshChip(l)
                }
                if (!matrix) Chips { l.decl.ccys.forEach { c -> FinChip(c.id, c.id == base) { base = c.id } } }
                val t = if (matrix) Quotes.fxMatrix(l.decl, l.snap) else Quotes.fxTable(l.decl, l.snap, base)
                Table("fx:" + if (matrix) "matrix" else base, t, screen, if (matrix) 44.dp else 128.dp)
                Footer(l, l.decl.word("note_fx"), l.decl.ccys.mapNotNull { it.symbol }.mapNotNull { l.snap.quotes[it]?.asof }.maxOrNull())
            } else {
                Chips { RefreshChip(l) }
                Table("commodities", Quotes.commodityTable(l.decl, l.snap), screen, 128.dp)
                Footer(l, l.decl.word("note_commodities"), Quotes.symbolsCommodities(l.decl).mapNotNull { l.snap.quotes[it]?.asof }.maxOrNull())
            }
        }
    }
}

/** HG > one asset class of the futures board (rows = the roots' front contracts); a tap on a root opens its term structure. */
@Composable
fun FuturesBoardPage(classId: String) {
    val today = remember { LocalDate.now() }
    val l = rememberLive { d -> d.classes.firstOrNull { it.id == classId }?.let { Quotes.symbolsClass(d, it, today) }.orEmpty() }
    val cls = l.decl.classes.first { it.id == classId }
    var root by rememberSaveable { mutableStateOf("") }
    val p = LocalFinPalette.current
    val spec = l.decl.spec(root)
    BoxWithConstraints(Modifier.fillMaxSize().testTag(FUT_PAGE + classId)) {
        val screen = maxWidth
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            if (spec == null) {
                Chips { RefreshChip(l) }
                val t = Quotes.board(l.decl, l.snap, cls, today)
                Table("fut:" + classId, t, screen, 176.dp, onRow = { i -> root = cls.roots[i].id })
                Footer(l, l.decl.word("note_board"), Quotes.symbolsClass(l.decl, cls, today).mapNotNull { l.snap.quotes[it]?.asof }.maxOrNull())
            } else TermStructure(l, spec, today, screen) { root = "" }
        }
    }
}

@Composable
private fun TermStructure(l: Live, spec: FutSpec, today: LocalDate, screen: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
    val p = LocalFinPalette.current
    val t = Quotes.term(l.decl, l.snap, spec, today)
    Text("< " + spec.name + "  " + spec.exchange, Modifier.clickable(onClick = onBack).testTag(FUT_ROW + spec.id), color = p.accent, style = FinType.title)
    Text(spec.unit + "  x " + spec.multiplier.toLong() + "  " + l.decl.word("tick_value") + " " + String.format(java.util.Locale.ROOT, "%.2f", spec.multiplier * spec.tick), color = p.text2, style = FinType.caption)
    if (t.rows.isEmpty()) {
        Text(l.decl.word("na_long"), color = p.text3, style = FinType.body)
    } else {
        val flag = t.shape?.let { l.decl.word(it) }
        if (flag != null) Text(l.decl.word("curve") + ": " + flag, color = if (t.shape == "contango") p.up else if (t.shape == "backwardation") p.down else p.text2, style = FinType.label)
        Curve(t.prices)
        Table("term:" + spec.id, CbTable(t.header, t.rows, t.rows.map { emptyList() }, t.labels, 0), screen, 72.dp)
    }
    Footer(l, l.decl.word("note_board"), Quotes.contracts(l.decl, spec, today).mapNotNull { l.snap.quotes[it.symbol]?.asof }.maxOrNull())
}

/** The price curve over the listed expiries. */
@Composable
private fun Curve(prices: List<Double>) {
    val p = LocalFinPalette.current
    Canvas(Modifier.fillMaxWidth().height(FinDim.keyRow)) {
        if (prices.size < 2) return@Canvas
        val lo = prices.min()
        val span = (prices.max() - lo).takeIf { it > 1e-12 } ?: 1.0
        fun at(i: Int) = Offset(size.width * i / (prices.size - 1), (size.height * (1.0 - (prices[i] - lo) / span)).toFloat())
        for (i in 1 until prices.size) drawLine(p.accent, at(i - 1), at(i), strokeWidth = 3f)
    }
}

/** HG > Hedge calculator: the hedge ratio and notional from a future's declared spec and its delayed price. */
@Composable
fun HedgePage() {
    val today = remember { LocalDate.now() }
    val l = rememberLive { d -> d.classes.flatMap { c -> Quotes.symbolsClass(d, c, today) } }
    val p = LocalFinPalette.current
    var cls by rememberSaveable { mutableStateOf("fx") }
    var root by rememberSaveable { mutableStateOf("") }
    var exposure by rememberSaveable { mutableStateOf("") }
    var ratio by rememberSaveable { mutableStateOf(l.decl.defaultRatio.toLong().toString()) }
    val c = l.decl.classes.firstOrNull { it.id == cls } ?: l.decl.classes.first()
    val usable = c.roots.filter { r -> Quotes.front(l.decl, l.snap, r, today)?.second != null }
    val spec = usable.firstOrNull { it.id == root } ?: usable.firstOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(HEDGE_PAGE), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        Chips { RefreshChip(l); l.decl.classes.forEach { k -> FinChip(k.label, k.id == c.id) { cls = k.id; root = "" } } }
        Chips { usable.forEach { r -> FinChip(r.id, r.id == spec?.id) { root = r.id } } }
        if (spec == null) {
            Text(l.decl.word("na_long"), color = p.text3, style = FinType.body)
        } else {
            val q = Quotes.front(l.decl, l.snap, spec, today)?.second!!
            val dec = Quotes.decimals(spec.tick)
            Text(spec.name + "  " + Quotes.price(q.last, dec) + "  " + spec.unit, color = p.text, style = FinType.title)
            Text(l.decl.word("exposure") + (if (spec.sizeUnit.isNotEmpty()) " (" + spec.sizeUnit + ")" else ""), color = p.text2, style = FinType.label)
            BasicTextField(exposure, { exposure = it }, textStyle = FinType.num.copy(color = p.text), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(l.decl.word("ratio"), color = p.text2, style = FinType.label)
            BasicTextField(ratio, { ratio = it }, textStyle = FinType.num.copy(color = p.text), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            val e = exposure.replace(',', '.').toDoubleOrNull(); val r = ratio.replace(',', '.').toDoubleOrNull()
            val n = Quotes.notionalPerContract(spec, q.last)
            val rows = ArrayList<Pair<String, String>>()
            rows += l.decl.word("notional") to String.format(java.util.Locale.ROOT, "%,.2f", n)
            rows += l.decl.word("tick_value") to String.format(java.util.Locale.ROOT, "%.2f", spec.multiplier * spec.tick)
            if (e != null && r != null) {
                val h = Quotes.hedge(spec, q.last, e, r)
                rows += l.decl.word("contracts") to (h.contracts.toString() + " (" + String.format(java.util.Locale.ROOT, "%.2f", h.exact) + ")")
                rows += l.decl.word("hedged") to String.format(java.util.Locale.ROOT, "%,.2f", h.hedged)
                rows += l.decl.word("residual") to String.format(java.util.Locale.ROOT, "%,.2f", h.residual)
            }
            rows.forEach { (k, v) -> Row(Modifier.fillMaxWidth()) { Text(k, Modifier.weight(1f), color = p.text2, style = FinType.body); Text(v, color = p.text, style = FinType.num) } }
            Footer(l, l.decl.word("note_board"), q.asof)
        }
    }
}
