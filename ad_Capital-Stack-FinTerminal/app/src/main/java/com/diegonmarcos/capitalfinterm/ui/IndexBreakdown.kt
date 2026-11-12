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
import com.diegonmarcos.capitalfinterm.ui.fin.FinTable
import com.diegonmarcos.capitalfinterm.ui.fin.FinTitleRow
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import com.diegonmarcos.superapp.fleetdata.DataSource
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.fleetdata.FleetDataReader
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
 * #1663 / #1702 the index holdings as the app reads them: the RUNTIME dataset `index-holdings` (repo data/fleet/app/<package>/index-holdings.json), never an
 * asset of the APK. Two routes through libs:fleet-data-client: (1) Cloud Store's provider, when it is installed and has synced; (2) ON DEMAND, the
 * client's own reader over the dataset's published file (the manifest's raw.githubusercontent.com source), schema-checked and kept as its last-known-good
 * copy in the app's files, so the first open needs no Cloud Store sync and an offline open answers from the cache. Null only when both are empty (first run,
 * offline): never throws, a null is never remembered, so the next call asks again.
 */
object IndexHoldingsStore {
    @Volatile private var served: Map<String, IndexBasket>? = null

    /** The published file of an own dataset route (`app/<package>/<dataset>`), the one the fleet manifest syncs from. */
    internal fun publishedUrl(path: String): String = "https://raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/data/fleet/$path.json"

    private class WebSource : DataSource {
        override fun open(authority: String, path: String): InputStream? = runCatching {
            val c = URL(publishedUrl(path)).openConnection() as HttpURLConnection
            c.connectTimeout = 12_000; c.readTimeout = 30_000
            if (c.responseCode == 200) c.inputStream else null
        }.getOrNull()
    }

    fun load(ctx: Context): Map<String, IndexBasket>? {
        served?.let { return it }
        val app = ctx.applicationContext
        val parse = { t: String -> IndexHoldings.parse(t) }
        val viaStore = runCatching { FleetData.get(app, IndexHoldings.DATASET, own = true, parse = parse) }.getOrNull()?.takeIf { it.isNotEmpty() }
        val got = viaStore ?: runCatching {
            FleetDataReader(WebSource(), FleetData.FileLkg(File(app.filesDir, "fleetdata-lkg-web"))).get(IndexHoldings.DATASET, app.packageName, parse)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        return got?.also { served = it }
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
                    else "The holdings could not be loaded yet: this phone is offline or the dataset is not published. This page asks again by itself every few seconds.",
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

/**
 * The heat map's own scale (the reference market map): a small gain is a light green, a large one a dark green, a loss a red that deepens with the move,
 * an unquoted holding a neutral slate. Fixed colours, so a map reads the same on the light and the dark theme.
 */
internal fun tileColor(perf: Double?, scale: Double): Color {
    val h = IndexHoldings.heat(perf, scale)
    if (perf == null || h == 0f && perf == 0.0) return Color(0xFF4A5A52)
    val m = kotlin.math.abs(h)
    return if (h > 0f) lerp(Color(0xFF52B848), Color(0xFF14531F), m.coerceIn(0f, 1f)) else lerp(Color(0xFFE53935), Color(0xFF9E1B1B), m.coerceIn(0f, 1f))
}

/** A stable brand-like badge colour per ticker (the logo stand-in: no logo files are baked into the APK). */
private fun badgeColor(ticker: String): Color {
    var hsh = 7
    ticker.forEach { hsh = hsh * 31 + it.code }
    val hue = ((hsh and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.55f, 0.55f)
}

/**
 * The market heat map after the reference picture: one Canvas inside a rounded frame, a tile per holding laid out by [IndexHoldings.treemap] (AREA =
 * weight; the largest holdings in a column on the left, then rows), thin light seams between tiles, filled by performance on [tileColor]. A big tile
 * carries a round badge, the ticker and the % change; a middle tile the badge and the %; a small one the badge; a tiny one only its colour. A tap
 * picks a tile (its line is shown under the map).
 */
@Composable
private fun Treemap(rows: List<BreakdownRow>, scale: Double, width: Dp, picked: Int, onPick: (Int) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val height = (width * 1.15f).coerceAtMost(560.dp).coerceAtLeast(260.dp)
    val wPx = with(density) { width.toPx() }
    val hPx = with(density) { height.toPx() }
    val rects = remember(rows, wPx, hPx) { IndexHoldings.treemap(rows.map { it.holding.weight }, wPx.toDouble(), hPx.toDouble()) }
    val seam = with(density) { 1.2.dp.toPx() }
    val white = Color.White
    Canvas(
        Modifier.width(width).height(height).clip(RoundedCornerShape(18.dp)).background(Color(0xFFB7E4B0)).testTag(INDEX_TREEMAP)
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
            val s = if (w > seam * 2 && h > seam * 2) seam / 2 else 0f
            drawRect(tileColor(row.perf, scale), Offset(x + s, y + s), Size(w - s * 2, h - s * 2))
            if (i == picked) drawRect(white, Offset(x + seam, y + seam), Size(w - seam * 2, h - seam * 2), style = Stroke(width = seam * 2))
            val m = minOf(w, h)
            val mDp = with(density) { m.toDp().value }
            if (mDp < 14f) return@forEachIndexed
            val ticker = row.holding.ticker
            val change = row.perf?.let { String.format(Locale.ROOT, "%+.2f%%", it) }
            // sizes from the tile: the badge a share of the short side, the text from the long ones
            val badge = (m * 0.42f).coerceIn(with(density) { 10.dp.toPx() }, with(density) { 34.dp.toPx() })
            val textPx = (m * 0.16f).coerceIn(with(density) { 8.dp.toPx() }, with(density) { 17.dp.toPx() })
            val big = FinType.symbol.copy(color = white, fontSize = with(density) { textPx.toSp() }, lineHeight = with(density) { (textPx * 1.15f).toSp() })
            val small = FinType.numSmall.copy(color = white, fontSize = with(density) { (textPx * 0.9f).toSp() }, lineHeight = with(density) { (textPx * 1.05f).toSp() })
            val t1 = measurer.measure(ticker, big, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
            val t2 = change?.let { measurer.measure(it, small, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1) }
            val showTicker = t1.size.width <= w - seam * 2 && badge + t1.size.height + (t2?.size?.height ?: 0) + seam * 4 <= h
            val showPct = t2 != null && t2.size.width <= w - seam * 2 && badge + (t2.size.height) + seam * 2 <= h
            val lines = (if (showTicker) t1.size.height else 0) + (if (showPct) t2!!.size.height else 0)
            val showBadge = badge + lines + seam * 2 <= h && badge <= w - seam * 2
            val badgeH = if (showBadge) badge else 0f
            val gapPx = if (showBadge && lines > 0) seam * 2 else 0f
            val top = y + (h - (badgeH + gapPx + lines)) / 2f
            var cy = top
            if (showBadge) {
                val c = Offset(x + w / 2f, cy + badge / 2f)
                drawCircle(badgeColor(ticker), badge / 2f, c)
                drawCircle(white.copy(alpha = 0.9f), badge / 2f, c, style = Stroke(width = seam))
                val ini = measurer.measure(ticker.take(if (badge > with(density) { 26.dp.toPx() }) 2 else 1), big.copy(fontSize = with(density) { (badge * 0.38f).toSp() }, lineHeight = with(density) { (badge * 0.42f).toSp() }),
                    softWrap = false, maxLines = 1)
                drawText(ini, topLeft = Offset(c.x - ini.size.width / 2f, c.y - ini.size.height / 2f))
                cy += badge + gapPx
            }
            if (showTicker) { drawText(t1, topLeft = Offset(x + (w - t1.size.width) / 2f, cy)); cy += t1.size.height }
            if (showPct) drawText(t2!!, topLeft = Offset(x + (w - t2.size.width) / 2f, cy))
        }
    }
}

/** The colour scale under the map: the deep red at -scale %, the light green at a small gain, the dark green at +scale % (and beyond). */
@Composable
private fun Legend(scale: Double) {
    val p = LocalFinPalette.current
    val stops = listOf(-1.0, -0.5, 0.0, 0.25, 0.5, 1.0)
    Row(Modifier.fillMaxWidth().testTag("index_legend"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        stops.forEach { s ->
            val v = s * scale
            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(3.dp)).background(tileColor(if (s == 0.0) 0.0001 else v, scale)), contentAlignment = Alignment.Center) {
                Text(if (s == 0.0) "0 %" else String.format(Locale.ROOT, "%+.1f %%", v), color = Color.White, style = FinType.caption.copy(fontSize = 9.sp))
            }
        }
    }
    Text("Tile size = weight in the index; colour = performance over the period (red down, light green small gain, dark green large gain, grey = no quote).", color = p.text3, style = FinType.caption)
}
