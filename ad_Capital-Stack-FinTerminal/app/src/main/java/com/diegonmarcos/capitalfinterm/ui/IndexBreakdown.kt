package com.diegonmarcos.capitalfinterm.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.BreakdownRow
import com.diegonmarcos.capitalfinterm.IndexBasket
import com.diegonmarcos.capitalfinterm.IndexHoldings
import com.diegonmarcos.capitalfinterm.IndexLink
import com.diegonmarcos.capitalfinterm.Quote
import com.diegonmarcos.capitalfinterm.QuotesDecl
import com.diegonmarcos.capitalfinterm.QuotesStore
import com.diegonmarcos.capitalfinterm.ui.fin.FinCard
import com.diegonmarcos.capitalfinterm.ui.fin.FinChip
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinPalette
import com.diegonmarcos.capitalfinterm.ui.fin.FinTable
import com.diegonmarcos.capitalfinterm.ui.fin.FinTitleRow
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import com.diegonmarcos.superapp.fleetdata.FleetData
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** #1663 test tags: the card of tappable indices on World Indices Equity, an index's breakdown page (+ key), its treemap, its state line. */
const val INDEX_LINKS = "index_links"
const val INDEX_LINK = "index_link:"
const val INDEX_PAGE = "index_page:"
const val INDEX_TREEMAP = "index_treemap"
const val INDEX_STATE = "index_state"

/**
 * #1663 the index holdings as the app reads them: the RUNTIME dataset `index-holdings` Cloud Store serves through libs:fleet-data-client (repo
 * data/fleet/app/<package>/index-holdings.json), never an asset of the APK. Null while nothing is served (first run, Cloud Store absent or not synced
 * yet): never throws, and a null is never remembered, so the next call asks again.
 */
object IndexHoldingsStore {
    @Volatile private var served: Map<String, IndexBasket>? = null

    fun load(ctx: Context): Map<String, IndexBasket>? {
        served?.let { return it }
        val got = runCatching { FleetData.get(ctx, IndexHoldings.DATASET, own = true) { IndexHoldings.parse(it) } }.getOrNull()
        return got?.takeIf { it.isNotEmpty() }?.also { served = it }
    }
}

/** World Indices Equity: the indices that open their holdings breakdown, as one row of chips (the same indices are tappable in the panels below). */
@Composable
fun IndexLinksCard(links: List<IndexLink>, onOpen: (IndexLink) -> Unit) {
    val p = LocalFinPalette.current
    FinCard(Modifier.testTag(INDEX_LINKS)) {
        FinTitleRow("Performance breakdown by holding")
        Text("Tap an index: its holdings as a table and as a heat map (tile size = weight in the index, colour = performance).", color = p.text2, style = FinType.caption)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            links.forEach { l ->
                FinChip(l.label + (if (l.country.isNotEmpty()) " · " + l.country else ""), true, Modifier.testTag(INDEX_LINK + l.key)) { onOpen(l) }
            }
        }
    }
}

/**
 * One index's performance breakdown by holding: the period and how many holdings, the treemap (tile AREA = the holding's weight in the index, tile COLOUR
 * = its performance over the period), then the table (holding, weight %, performance %, contribution in percentage points). The weights are the runtime
 * dataset's; the performance is asked of the app's quote source (data/quotes.json `source`) when the page opens, the largest holdings first. Every
 * missing piece is a sentence on the page (dataset not served yet, index not published, quote source silent), never a crash.
 */
@Composable
fun IndexBreakdownPage(key: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    val link = remember(key) { runCatching { AssetData.dashboards(ctx).flatMap { it.breakdown }.firstOrNull { it.key == key } }.getOrNull() }
    val decl = remember { runCatching { QuotesStore.decl(ctx) }.getOrNull() }
    var baskets by remember { mutableStateOf<Map<String, IndexBasket>?>(null) }
    var asked by remember { mutableIntStateOf(0) }
    var period by rememberSaveable(key) { mutableStateOf(IndexHoldings.DEFAULT_PERIOD) }
    var top by rememberSaveable(key) { mutableIntStateOf(IndexHoldings.DEFAULT_TOP) }
    var quotes by remember { mutableStateOf<Map<String, Quote>>(emptyMap()) }
    var fetching by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    // the dataset: asked off the main thread, again every few seconds while Cloud Store has not served it
    LaunchedEffect(key) {
        while (baskets == null) {
            baskets = withContext(Dispatchers.IO) { IndexHoldingsStore.load(ctx) }
            asked += 1
            if (baskets == null) delay(3_000)
        }
    }
    val basket = baskets?.get(key)
    val shown = remember(basket, top) { basket?.let { if (top <= 0) it.holdings else it.holdings.take(top) }.orEmpty() }

    // the performance: the quote source, the largest holdings first, a batch at a time so the map colours in as the answers arrive
    LaunchedEffect(basket, top, refresh) {
        if (basket == null || decl == null) return@LaunchedEffect
        quotes = QuotesStore.liveQuotes()
        fetching = true
        for (batch in shown.map { it.symbol }.chunked(24)) {
            withContext(Dispatchers.IO) { runCatching { QuotesStore.refresh(decl, batch, force = refresh > 0) } }
            quotes = QuotesStore.liveQuotes()
        }
        fetching = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(p.canvas).testTag(INDEX_PAGE + key)) {
        val width = maxWidth - FinDim.screenPad * 2
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = FinDim.screenPad, end = FinDim.screenPad, top = FinDim.gap, bottom = FinDim.bottomInset),
            verticalArrangement = Arrangement.spacedBy(FinDim.gap),
        ) {
            val name = basket?.name ?: link?.label ?: key
            val country = basket?.country?.ifBlank { null } ?: link?.country.orEmpty()
            Text("‹ World Indices Equity", Modifier.clickable(onClick = onBack).padding(vertical = 4.dp).testTag("index_back"), color = p.accent, style = FinType.body)
            Text(name + (if (country.isNotEmpty()) "  ·  $country" else ""), color = p.text, style = FinType.title)
            when {
                baskets == null -> StateLine(
                    if (asked == 0) "Loading the index holdings…"
                    else "The holdings dataset has not reached this phone yet. Cloud Store serves it (Cloud Store > Data > Sync now); this page asks again by itself.",
                )
                basket == null -> StateLine("No holdings are published for this index yet. Breakdowns exist for: " + baskets.orEmpty().values.joinToString(", ") { it.name } + ".")
                else -> Breakdown(basket, shown, quotes, decl, period, top, fetching, width, onPeriod = { period = it }, onTop = { top = it }, onRefresh = { refresh += 1 })
            }
        }
    }
}

@Composable
private fun StateLine(text: String) {
    val p = LocalFinPalette.current
    FinCard(Modifier.testTag(INDEX_STATE)) { Text(text, color = p.text2, style = FinType.body) }
}

private fun topLabel(n: Int): String = if (n <= 0) "All" else "Top $n"
private fun pct(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
private fun signed(v: Double): String = String.format(Locale.ROOT, "%+.2f", v)

@Composable
private fun Breakdown(
    basket: IndexBasket, shown: List<com.diegonmarcos.capitalfinterm.IndexHolding>, quotes: Map<String, Quote>, decl: QuotesDecl?, period: String, top: Int,
    fetching: Boolean, width: Dp, onPeriod: (String) -> Unit, onTop: (Int) -> Unit, onRefresh: () -> Unit,
) {
    val p = LocalFinPalette.current
    val rows = remember(basket, quotes, period, top) { IndexHoldings.rows(basket, quotes, period, top) }
    val scale = decl?.heat?.get(IndexHoldings.heatKey(period)) ?: 2.0
    val answered = rows.count { it.perf != null }
    var picked by remember(basket.key, top) { mutableIntStateOf(-1) }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        IndexHoldings.PERIODS.forEach { FinChip(it, it == period, Modifier.testTag("index_period:$it")) { onPeriod(it) } }
        Box(Modifier.width(1.dp).height(FinDim.chip).background(p.hairline))
        IndexHoldings.TOPS.forEach { n -> FinChip(topLabel(n), n == top, Modifier.testTag("index_top:$n")) { onTop(n) } }
        FinChip(if (fetching) "Refreshing…" else "Refresh", false) { if (!fetching) onRefresh() }
    }
    val covered = rows.sumOf { it.holding.weight }
    Text(
        "${rows.size} of ${basket.holdings.size} holdings shown, ${pct(covered)} % of the index." +
            (IndexHoldings.total(rows)?.let { (c, w) -> " Their contribution over $period: ${signed(c)} pp (holdings quoted: ${pct(w)} % of the index)." } ?: ""),
        color = p.text2, style = FinType.caption,
    )

    FinCard(pad = false) {
        Treemap(rows, scale, width, picked) { picked = if (picked == it) -1 else it }
    }
    rows.getOrNull(picked)?.let { r ->
        Text(
            IndexHoldings.label(r.holding) + "  ·  weight ${String.format(Locale.ROOT, "%.2f", r.holding.weight)} %  ·  $period " +
                (r.perf?.let { signed(it) + " %" } ?: "n/a") + "  ·  contribution " + (r.contribution?.let { signed(it) + " pp" } ?: "n/a"),
            Modifier.testTag("index_picked"), color = p.text, style = FinType.body,
        )
    }
    Legend(scale)
    if (answered == 0) {
        Text(
            if (fetching) "Asking the quote source for the holdings' performance…"
            else "The quote source has not answered for these holdings: the tiles show the weights only. Refresh asks again.",
            Modifier.testTag("index_noperf"), color = p.text3, style = FinType.caption,
        )
    }

    FinCard {
        FinTitleRow("Holdings")
        val cols = listOf(IndexHoldings.COLUMNS[0], IndexHoldings.COLUMNS[1], "$period %", IndexHoldings.COLUMNS[3])
        val cells = remember(rows) { IndexHoldings.table(rows, "Rest of the index") }
        FinTable("index:" + basket.key, cols, cells, emptyList(), IndexHoldings.COL_TYPES, width - FinDim.cardPad * 2, limit = 15) { }
    }
    Text(
        "Weights: " + basket.source + (if (basket.asOf.isNotEmpty()) ", as of " + basket.asOf else "") + ". Performance: " + (decl?.source?.label ?: "quote source") +
            (decl?.source?.delay?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "") + ". Contribution = weight x performance, in percentage points of the index.",
        color = p.text3, style = FinType.caption,
    )
}

private fun fill(p: FinPalette, heat: Float): Color = lerp(p.surfaceRaised, if (heat >= 0f) p.up else p.down, IndexHoldings.blend(heat))

/**
 * The market heat map: one Canvas, a tile per holding laid out by [IndexHoldings.treemap] (area = weight), filled by performance (down colour .. neutral ..
 * up colour), the ticker and the % change written where the tile is large enough. A tap picks a tile (its line is shown under the map).
 */
@Composable
private fun Treemap(rows: List<BreakdownRow>, scale: Double, width: Dp, picked: Int, onPick: (Int) -> Unit) {
    val p = LocalFinPalette.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // a little taller than wide on a phone, capped so the table stays within reach
    val height = (width * 1.1f).coerceAtMost(460.dp).coerceAtLeast(220.dp)
    val wPx = with(density) { width.toPx() }
    val hPx = with(density) { height.toPx() }
    val rects = remember(rows, wPx, hPx) { IndexHoldings.treemap(rows.map { it.holding.weight }, wPx.toDouble(), hPx.toDouble()) }
    val gap = with(density) { 1.dp.toPx() }
    val minLabel = with(density) { 26.dp.toPx() }
    Canvas(
        Modifier.width(width).height(height).clip(RoundedCornerShape(FinDim.cardRadius)).background(p.canvas).testTag(INDEX_TREEMAP)
            .pointerInput(rects) {
                detectTapGestures { at ->
                    val i = rects.indexOfFirst { r -> r.w > 0 && at.x >= r.x && at.x < r.x + r.w && at.y >= r.y && at.y < r.y + r.h }
                    if (i >= 0) onPick(i)
                }
            },
    ) {
        rects.forEachIndexed { i, r ->
            if (r.w <= 0.0 || r.h <= 0.0) return@forEachIndexed
            val row = rows[i]
            val x = r.x.toFloat(); val y = r.y.toFloat(); val w = r.w.toFloat(); val h = r.h.toFloat()
            val inset = if (w > gap * 3 && h > gap * 3) gap / 2 else 0f
            drawRect(fill(p, IndexHoldings.heat(row.perf, scale)), Offset(x + inset, y + inset), Size(w - inset * 2, h - inset * 2))
            if (i == picked) drawRect(p.text, Offset(x + gap, y + gap), Size(w - gap * 2, h - gap * 2), style = Stroke(width = gap * 2))
            if (w < minLabel || h < minLabel * 0.6f) return@forEachIndexed
            // the ticker sized to the tile, the % change under it when there is room for a second line
            val ticker = row.holding.ticker
            val sizePx = minOf(w * 0.9f / (ticker.length.coerceAtLeast(3) * 0.62f), h * 0.36f).coerceIn(with(density) { 7.dp.toPx() }, with(density) { 20.dp.toPx() })
            val big = FinType.symbol.copy(color = p.text, fontSize = with(density) { sizePx.toSp() }, lineHeight = with(density) { (sizePx * 1.15f).toSp() })
            val t1 = measurer.measure(ticker, big, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
            if (t1.size.width > w - gap * 2) return@forEachIndexed
            val change = row.perf?.let { String.format(Locale.ROOT, "%+.2f%%", it) }
            val small = FinType.numSmall.copy(color = p.text, fontSize = with(density) { (sizePx * 0.72f).coerceAtLeast(7.dp.toPx()).toSp() })
            val t2 = change?.let { measurer.measure(it, small, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1) }
                ?.takeIf { it.size.width <= w - gap * 2 && t1.size.height + it.size.height <= h - gap * 2 }
            val total = t1.size.height + (t2?.size?.height ?: 0)
            val top = y + (h - total) / 2f
            drawText(t1, topLeft = Offset(x + (w - t1.size.width) / 2f, top))
            if (t2 != null) drawText(t2, topLeft = Offset(x + (w - t2.size.width) / 2f, top + t1.size.height))
        }
    }
}

/** The colour scale under the map: the down colour at -scale %, neutral at 0, the up colour at +scale % (and beyond). */
@Composable
private fun Legend(scale: Double) {
    val p = LocalFinPalette.current
    val stops = listOf(-1f, -0.5f, 0f, 0.5f, 1f)
    Row(Modifier.fillMaxWidth().testTag("index_legend"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        stops.forEach { s ->
            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(3.dp)).background(fill(p, s)), contentAlignment = Alignment.Center) {
                val v = s * scale
                Text(if (s == 0f) "0 %" else String.format(Locale.ROOT, "%+.1f %%", v) + (if (kotlin.math.abs(s) == 1f) (if (s > 0) " +" else " −") else ""), color = p.text, style = FinType.caption.copy(fontSize = 9.sp))
            }
        }
    }
}
