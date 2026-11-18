package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.BoxItem
import com.diegonmarcos.capitalfinterm.Cagr
import com.diegonmarcos.capitalfinterm.CbBox
import com.diegonmarcos.capitalfinterm.CbDecl
import com.diegonmarcos.capitalfinterm.CbSnapshot
import com.diegonmarcos.capitalfinterm.CbStore
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.HomeBoxes
import com.diegonmarcos.capitalfinterm.HomeBoxesDecl
import com.diegonmarcos.capitalfinterm.Quote
import com.diegonmarcos.capitalfinterm.QuoteBox
import com.diegonmarcos.capitalfinterm.QuotesDecl
import com.diegonmarcos.capitalfinterm.QuotesStore
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinPanelHead
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** #1786 the test tags: a quote box's body (+ page id), its Show all toggle (+ page id), a CB overview box (+ box id) and its toggle. */
const val BOX_BODY = "home_box:"
const val BOX_TOGGLE = "home_box_all:"
const val CB_BOX = "cb_box:"
const val CB_BOX_TOGGLE = "cb_box_all:"
const val CB_OVERVIEW = "cb_overview"

/** The Homepage boxes' declaration, read once. */
object HomeBoxesStore {
    @Volatile private var cache: HomeBoxesDecl? = null
    fun decl(ctx: android.content.Context): HomeBoxesDecl? = cache ?: runCatching { HomeBoxes.parse(AssetData.read(ctx, HomeBoxes.ASSET)) }.getOrNull()?.also { cache = it }
}

/** What a Homepage's quote boxes know: the declaration, the quote source's declaration and the quotes so far (the baked snapshot, then live answers). */
class BoxQuotes(val decl: HomeBoxesDecl?, val quotesDecl: QuotesDecl?, val quotes: Map<String, Quote>)

/** The quotes of [wanted] (the shown rows of every box of a Homepage): the snapshot at once, then the quote source a batch at a time (within its TTL). */
@Composable
fun rememberBoxQuotes(wanted: List<String>): BoxQuotes {
    val ctx = LocalContext.current
    val decl = remember { HomeBoxesStore.decl(ctx) }
    val qd = remember { runCatching { QuotesStore.decl(ctx) }.getOrNull() }
    var quotes by remember { mutableStateOf<Map<String, Quote>>(emptyMap()) }
    LaunchedEffect(Unit) { quotes = withContext(Dispatchers.IO) { runCatching { QuotesStore.snapshot(ctx).quotes }.getOrDefault(emptyMap()) } + QuotesStore.liveQuotes() }
    LaunchedEffect(wanted) {
        if (qd == null || wanted.isEmpty()) return@LaunchedEffect
        for (batch in wanted.chunked(16)) {
            withContext(Dispatchers.IO) { runCatching { QuotesStore.refresh(qd, batch, force = false) } }
            quotes = quotes + QuotesStore.liveQuotes()
        }
    }
    return BoxQuotes(decl, qd, quotes)
}

/**
 * A quote box's body inside its Homepage card: the column headers, the [HomeBoxesDecl.top] first instruments (or every one once Show all is on) with
 * their declared columns (Last price, a period's change in %), then the Show all / Show top toggle with the count. A missing figure is n/a.
 */
@Composable
fun QuoteBoxBody(box: QuoteBox, items: List<BoxItem>, state: BoxQuotes, all: Boolean, onToggle: () -> Unit) {
    val p = LocalFinPalette.current
    val decl = state.decl ?: return
    val shown = HomeBoxes.shown(items, decl.top, all)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag(BOX_BODY + box.page)) {
        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(decl.word("instrument").ifEmpty { "Instrument" }, Modifier.weight(1f), color = p.text2, style = FinType.caption, maxLines = 1)
            box.columns.forEach { c -> Text(HomeBoxes.header(c, decl.word("last")), Modifier.width(colW(c)), color = p.text2, style = FinType.caption, maxLines = 1, textAlign = TextAlign.End) }
        }
        if (items.isEmpty()) Text(decl.word("na"), color = p.text3, style = FinType.caption)
        shown.forEach { item ->
            val q = state.quotes[item.symbol]
            Row(Modifier.fillMaxWidth().height(20.dp).testTag(BOX_BODY + box.page + ":" + item.symbol), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, Modifier.weight(1f), color = p.text, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                box.columns.forEach { c ->
                    val ch = HomeBoxes.change(q, c)
                    val txt = HomeBoxes.cell(q, c)
                    Text(txt, Modifier.width(colW(c)), color = if (txt == Cagr.NA) p.text3 else if (c == HomeBoxes.COL_LAST) p.text else p.dir(dirOf(ch)), style = FinType.numStrong, maxLines = 1, textAlign = TextAlign.End)
                }
            }
        }
        if (items.size > decl.top) {
            Spacer(Modifier.height(2.dp))
            Text(
                (if (all) decl.word("show_less") + " " + decl.top else decl.word("show_all") + " " + items.size) + (if (all) "  ‹" else "  ›"),
                Modifier.clickable(onClick = onToggle).padding(vertical = 2.dp).testTag(BOX_TOGGLE + box.page), color = p.blue, style = FinType.label, maxLines = 1,
            )
        }
    }
}

private fun colW(c: String) = if (c == HomeBoxes.COL_LAST) 64.dp else 46.dp
private fun dirOf(v: Double?): FinFormat.Dir = if (v == null || v == 0.0) FinFormat.Dir.FLAT else if (v > 0.0) FinFormat.Dir.UP else FinFormat.Dir.DOWN

/**
 * #1786 the CB Homepage overview: one box per declared indicator group (yield curves 2Y / 5Y / 10Y / 30Y, CPI inflation, real GDP growth, the policy
 * rate), each row a country with the latest print of each column from the CB snapshot (data/cb-snapshot.json, or the phone's refreshed copy); the
 * major economies first (the top [HomeBoxesDecl.top]), Show all lists every country. A tap on a box's header opens its page.
 */
@Composable
fun CbOverview(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val decl = remember { HomeBoxesStore.decl(ctx) } ?: return
    var cbDecl by remember { mutableStateOf<CbDecl?>(null) }
    var snap by remember { mutableStateOf<CbSnapshot?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { cbDecl = CbStore.decl(ctx); snap = CbStore.snapshot(ctx) } }
    }
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().testTag(CB_OVERVIEW), verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        decl.cbBoxes.forEach { b -> CbBoxPanel(b, decl, cbDecl, snap) { onOpen(b.opens) } }
        Text(decl.word("cb_note") + (snap?.generated?.let { "  ·  snapshot $it" } ?: ""), color = p.text3, style = FinType.caption)
    }
}

@Composable
private fun CbBoxPanel(b: CbBox, decl: HomeBoxesDecl, cbDecl: CbDecl?, snap: CbSnapshot?, onOpen: () -> Unit) {
    val p = LocalFinPalette.current
    var all by rememberSaveable(b.id) { mutableStateOf(false) }
    val countries = remember(cbDecl) { cbDecl?.let { HomeBoxes.cbCountries(decl.cbTop, it.countries) }.orEmpty() }
    val shown = if (all) countries else countries.take(decl.top)
    Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).background(p.canvas).border(1.dp, p.hairline).padding(1.dp).testTag(CB_BOX + b.id)) {
        Box(Modifier.fillMaxWidth().clickable(onClick = onOpen)) { FinPanelHead(b.fn, b.title, meta = b.unit) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("", Modifier.weight(1f))
                b.columns.forEach { c -> Text(c.label, Modifier.width(CB_COL), color = p.text2, style = FinType.caption, maxLines = 1, textAlign = TextAlign.End) }
                Text(decl.word("asof"), Modifier.width(CB_ASOF), color = p.text2, style = FinType.caption, maxLines = 1, textAlign = TextAlign.End)
            }
            if (snap == null || cbDecl == null) Text(decl.word("loading"), color = p.text3, style = FinType.caption)
            shown.forEach { c ->
                val cells = b.columns.map { col -> HomeBoxes.cbCell(snap?.of(c.id, col.indicator), col.mode, snap?.currentYear ?: 0) }
                Row(Modifier.fillMaxWidth().height(20.dp).testTag(CB_BOX + b.id + ":" + c.id), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.id, Modifier.width(30.dp), color = p.accent, style = FinType.symbol, maxLines = 1)
                    Text(c.name, Modifier.weight(1f), color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    cells.forEach { cell -> Text(HomeBoxes.cbText(cell), Modifier.width(CB_COL), color = if (cell.value == null) p.text3 else p.text, style = FinType.numStrong, maxLines = 1, textAlign = TextAlign.End) }
                    Text(cells.firstOrNull { it.value != null }?.asOf.orEmpty(), Modifier.width(CB_ASOF), color = p.text3, style = FinType.caption, maxLines = 1, textAlign = TextAlign.End)
                }
            }
            if (countries.size > decl.top) {
                Text(
                    (if (all) decl.word("show_less") + " " + decl.top + "  ‹" else decl.word("show_all") + " " + countries.size + "  ›"),
                    Modifier.clickable { all = !all }.padding(vertical = 2.dp).testTag(CB_BOX_TOGGLE + b.id), color = p.blue, style = FinType.label, maxLines = 1,
                )
            }
        }
    }
}

private val CB_COL = 48.dp
private val CB_ASOF = 58.dp
