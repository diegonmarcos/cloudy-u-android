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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.Decl
import com.diegonmarcos.capitalfinterm.Flow
import com.diegonmarcos.capitalfinterm.Mesh
import com.diegonmarcos.capitalfinterm.SovRow
import com.diegonmarcos.capitalfinterm.SovereignReturns
import com.diegonmarcos.capitalfinterm.UrlHttp
import androidx.compose.ui.graphics.Path
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.IndexHoldings
import com.diegonmarcos.capitalfinterm.PerfBasket
import com.diegonmarcos.capitalfinterm.PerfBubble
import com.diegonmarcos.capitalfinterm.PerfDecl
import com.diegonmarcos.capitalfinterm.PerfGroup
import com.diegonmarcos.capitalfinterm.PerfPortfolios
import com.diegonmarcos.capitalfinterm.PerfRow
import com.diegonmarcos.capitalfinterm.PerfStep
import com.diegonmarcos.capitalfinterm.Quote
import com.diegonmarcos.capitalfinterm.QuotesDecl
import com.diegonmarcos.capitalfinterm.QuotesStore
import com.diegonmarcos.capitalfinterm.ui.fin.FinChip
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinPanel
import com.diegonmarcos.capitalfinterm.ui.fin.FinStat
import com.diegonmarcos.capitalfinterm.ui.fin.FinTabs
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** #1722 test tags: a performance portfolio's page (+ its key), its state line, its table, its three graphics, a Homepage card's lines. */
const val PERF_PAGE = "perf_page:"
const val PERF_STATE = "perf_state"
const val PERF_TABLE = "perf_table"
const val PERF_MAP = "perf_map"
const val PERF_WATERFALL = "perf_waterfall"
const val PERF_BUBBLE = "perf_bubble"
const val PERF_CARD = "perf_card:"
const val PERF_SCHEME = "perf_scheme"
const val PERF_FLOW = "perf_flow"

/**
 * #1722 the performance portfolios as the app reads them: the DECLARATION is the asset data/portfolios.json; the constituents and weights are the RUNTIME
 * dataset it names (repo data/fleet/app/<package>/perf-portfolios.json), never an asset of the APK. The dataset comes through libs:fleet-data-client twice
 * over, as the index holdings do: Cloud Store's provider when it has synced, else the client's own reader over the dataset's published file (the address
 * declared in data/quotes.json `index_holdings.url_template`), schema-checked and kept as last-known-good in the app's files. Null only when both are
 * empty (first run, offline): never throws, a null is never remembered, so the next call asks again.
 */
object PerfStore {
    const val DECL = "portfolios.json"
    @Volatile private var declCache: PerfDecl? = null
    @Volatile private var served: Map<String, PerfBasket>? = null

    fun decl(ctx: Context): PerfDecl? = declCache ?: runCatching { PerfPortfolios.parseDecl(AssetData.read(ctx, DECL)) }.getOrNull()?.also { declCache = it }

    private class WebSource(private val quotesJson: String) : DataSource {
        override fun open(authority: String, path: String): InputStream? = runCatching {
            val c = URL(IndexHoldingsStore.publishedUrl(quotesJson, path) ?: return@runCatching null).openConnection() as HttpURLConnection
            c.connectTimeout = 12_000; c.readTimeout = 30_000
            if (c.responseCode == 200) c.inputStream else null
        }.getOrNull()
    }

    fun load(ctx: Context): Map<String, PerfBasket>? {
        served?.let { return it }
        val app = ctx.applicationContext
        val dataset = decl(app)?.dataset ?: return null
        val parse = { t: String -> PerfPortfolios.parse(t) }
        val viaStore = runCatching { FleetData.get(app, dataset, own = true, parse = parse) }.getOrNull()?.takeIf { it.isNotEmpty() }
        val got = viaStore ?: runCatching {
            FleetDataReader(WebSource(AssetData.read(app, QuotesStore.DECL)), FleetData.FileLkg(File(app.filesDir, "fleetdata-lkg-web"))).get(dataset, app.packageName, parse)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        return got?.also { served = it }
    }
}

/**
 * #1727 the government bond return of a country without a listed bond fund (data/portfolios.json `sovereign`): the fleet container's sovereign-returns
 * route when the private mesh answers, else the same yield and duration arithmetic on the declared public yield series. Asked once per process (again
 * after a minute while nothing answered); never throws: no answer is an empty map and the page shows no figure for those countries.
 */
object SovStore {
    @Volatile private var served: Map<String, SovRow>? = null
    @Volatile private var askedAt = 0L

    fun load(ctx: Context): Map<String, SovRow> {
        served?.let { return it }
        val now = System.currentTimeMillis()
        if (now - askedAt < 60_000L) return emptyMap()
        askedAt = now
        val app = ctx.applicationContext
        val decl = runCatching { SovereignReturns.parseDecl(AssetData.read(app, PerfStore.DECL)) }.getOrNull() ?: return emptyMap()
        val sources = runCatching { AssetData.sources(app) }.getOrDefault(emptyList())
        val primary = sources.firstOrNull { it.id == decl.primarySource }
        val base = primary?.let { Decl.fleetMesh[it.fleetEndpoint] }.orEmpty()
        val viaApi = if (primary == null || base.isEmpty()) null else runCatching {
            val mesh = UrlHttp(connectMs = 4_000, readMs = 15_000, cleartextHosts = Decl.fleetMesh.values.map { Mesh.hostOf(it) }.toSet())
            if (Mesh.reachable(mesh, base, primary.paths["health"].orEmpty())) SovereignReturns.parseApi(mesh.get(base.trimEnd('/') + decl.api), primary.name) else null
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val got = viaApi ?: sources.firstOrNull { it.id == decl.fallbackSource }?.let { s ->
            runCatching { SovereignReturns.parseYields(UrlHttp(readMs = 30_000).get(SovereignReturns.fallbackUrl(decl, s.endpoint)), decl, s.name) }.getOrNull()
        }.orEmpty()
        if (got.isNotEmpty()) served = got
        return got
    }

    /** Country to page period to the local-currency bond return in %. */
    fun periods(ctx: Context): Map<String, Map<String, Double>> {
        val decl = runCatching { SovereignReturns.parseDecl(AssetData.read(ctx.applicationContext, PerfStore.DECL)) }.getOrNull() ?: return emptyMap()
        return SovereignReturns.byPeriod(load(ctx), decl)
    }

    /** "source, to YYYY-MM" of what answered; "" when nothing did. */
    fun note(): String = served?.values?.firstOrNull()?.let { r -> r.source + ", to " + (served?.values?.maxOfOrNull { it.asOf } ?: r.asOf) }.orEmpty()
}

/** The mode a deep link asks a performance page to open in (`--es mode graphic`); null = the declared default. */
object PerfUi {
    @Volatile var startMode: String? = null
}

/** What a section's Homepage knows about its performance portfolios: the declaration, the dataset (null while it has not arrived) and the quotes so far. */
class PerfHomeState(val decl: PerfDecl?, val baskets: Map<String, PerfBasket>?, val quotes: Map<String, Quote>, val asked: Int, val sov: Map<String, Map<String, Double>> = emptyMap())

/** The Homepage's portfolios: the dataset asked off the main thread (again every few seconds until it arrives), then one quote request for the cards' top constituents. */
@Composable
fun rememberPerfHome(views: List<String>): PerfHomeState {
    val ctx = LocalContext.current
    val decl = remember { PerfStore.decl(ctx) }
    var baskets by remember { mutableStateOf<Map<String, PerfBasket>?>(null) }
    var asked by remember { mutableIntStateOf(0) }
    var quotes by remember { mutableStateOf<Map<String, Quote>>(emptyMap()) }
    var sov by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }
    LaunchedEffect(baskets, views) {
        val all = baskets ?: return@LaunchedEffect
        if (views.any { v -> all[v]?.let { PerfPortfolios.isSovereign(it) } == true }) sov = withContext(Dispatchers.IO) { runCatching { SovStore.periods(ctx) }.getOrDefault(emptyMap()) }
    }
    LaunchedEffect(views) {
        if (views.isEmpty() || decl == null) return@LaunchedEffect
        while (baskets == null) {
            baskets = withContext(Dispatchers.IO) { PerfStore.load(ctx) }
            asked += 1
            if (baskets == null) delay(3_000)
        }
    }
    LaunchedEffect(baskets, views) {
        val all = baskets ?: return@LaunchedEffect
        if (decl == null) return@LaunchedEffect
        val qd = runCatching { QuotesStore.decl(ctx) }.getOrNull() ?: return@LaunchedEffect
        quotes = QuotesStore.liveQuotes()
        val syms = views.flatMap { v ->
            val g = decl.group(v); val b = all[v]
            if (g == null || b == null) emptyList() else PerfPortfolios.symbols(PerfPortfolios.rows(b, PerfPortfolios.schemeOf(g, null), emptyMap(), decl.defaultPeriod, decl.cardTop))
        }.distinct()
        for (batch in syms.chunked(24)) {
            withContext(Dispatchers.IO) { runCatching { QuotesStore.refresh(qd, batch, force = false) } }
            quotes = QuotesStore.liveQuotes()
        }
    }
    return PerfHomeState(decl, baskets, quotes, asked, sov)
}

/** A Homepage card's body: the portfolio's largest constituents with their return over the default period, then "See all N". */
@Composable
fun PerfCardBody(view: String, state: PerfHomeState) {
    val p = LocalFinPalette.current
    val decl = state.decl
    val group = decl?.group(view)
    val basket = state.baskets?.get(view)
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp).testTag(PERF_CARD + view)) {
        when {
            decl == null || group == null -> Text("Not declared", color = p.text3, style = FinType.caption)
            state.baskets == null -> Text(if (state.asked == 0) decl.word("loading") else decl.word("offline"), color = p.text3, style = FinType.caption, maxLines = 6, overflow = TextOverflow.Ellipsis)
            basket == null -> Text(decl.word("missing"), color = p.text3, style = FinType.caption, maxLines = 4)
            else -> {
                val scheme = PerfPortfolios.schemeOf(group, null)
                val rows = remember(basket, state.quotes, state.sov) { PerfPortfolios.rows(basket, scheme, state.quotes, decl.defaultPeriod, decl.cardTop, state.sov) }
                val n = basket.constituents.count { (it.weights[scheme] ?: 0.0) > 0.0 }
                Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(decl.word("ticker"), Modifier.weight(1f), color = p.text2, style = FinType.caption, maxLines = 1)
                    Text(decl.defaultPeriod + " %", color = p.text2, style = FinType.caption, maxLines = 1)
                }
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.c.ticker, color = p.accent, style = FinType.symbol, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text(r.c.name, Modifier.weight(1f), color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(6.dp))
                        Text(PerfPortfolios.s1(r.perf), color = if (r.perf == null) p.text3 else p.dir(perfDir(r.perf)), style = FinType.numStrong, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(decl.word("see_all") + " " + n + "  ›", color = p.blue, style = FinType.label, maxLines = 1)
            }
        }
    }
}

private fun perfDir(v: Double?): FinFormat.Dir = if (v == null || v == 0.0) FinFormat.Dir.FLAT else if (v > 0.0) FinFormat.Dir.UP else FinFormat.Dir.DOWN
private fun perfTopLabel(n: Int): String = if (n <= 0) "All" else "Top $n"

/**
 * One performance portfolio (a factor portfolio of EQ > Performance, a bond group of FI): a header panel (the mode tabs Table | Graphic, the period, the
 * weighting scheme in use and where its weights come from, the portfolio against its benchmarks), then the mode's panels. TABLE: the sortable constituents
 * table with contribution bars and a totals line. GRAPHIC: the heat map (tile area = weight, colour = performance), the attribution waterfall and the
 * bubble chart. The weights are the runtime dataset's; every figure is asked of the app's quote source when the page opens. Every missing piece is a
 * sentence on the page (dataset not served yet, portfolio not published, quote source silent), never a crash.
 */
@Composable
fun PerfPortfolioPage(view: String, fn: String) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    val decl = remember { PerfStore.decl(ctx) }
    val group = decl?.group(view)
    if (decl == null || group == null) {
        FinPanel(fn, view, Modifier.testTag(PERF_STATE)) { Text("This portfolio is not declared (data/portfolios.json).", Modifier.padding(10.dp), color = p.text2, style = FinType.body) }
        return
    }
    val qdecl = remember { runCatching { QuotesStore.decl(ctx) }.getOrNull() }
    var baskets by remember { mutableStateOf<Map<String, PerfBasket>?>(null) }
    var asked by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable(view) { mutableStateOf(PerfUi.startMode?.takeIf { it in decl.modes } ?: decl.defaultMode) }
    var period by rememberSaveable(view) { mutableStateOf(decl.defaultPeriod) }
    var scheme by rememberSaveable(view) { mutableStateOf(PerfPortfolios.schemeOf(group, null)) }
    var top by rememberSaveable(view) { mutableIntStateOf(decl.defaultTop) }
    var quotes by remember { mutableStateOf<Map<String, Quote>>(emptyMap()) }
    var fetching by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var sov by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }

    LaunchedEffect(view) {
        while (baskets == null) {
            baskets = withContext(Dispatchers.IO) { PerfStore.load(ctx) }
            asked += 1
            if (baskets == null) delay(3_000)
        }
    }
    val basket = baskets?.get(view)
    // #1727 a sovereign portfolio: the bond returns of the countries without a listed fund, from the sovereign-returns source
    LaunchedEffect(basket, refresh) {
        if (basket != null && PerfPortfolios.isSovereign(basket)) sov = withContext(Dispatchers.IO) { runCatching { SovStore.periods(ctx) }.getOrDefault(emptyMap()) }
    }
    LaunchedEffect(basket, scheme, top, refresh) {
        if (basket == null || qdecl == null) return@LaunchedEffect
        quotes = QuotesStore.liveQuotes()
        fetching = true
        val syms = (group.benchmarks.map { it.symbol } + PerfPortfolios.symbols(PerfPortfolios.rows(basket, scheme, emptyMap(), period, top))).filter { it.isNotBlank() }.distinct()
        for (batch in syms.chunked(24)) {
            withContext(Dispatchers.IO) { runCatching { QuotesStore.refresh(qdecl, batch, force = refresh > 0) } }
            quotes = QuotesStore.liveQuotes()
        }
        fetching = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag(PERF_PAGE + view)) {
        val width = maxWidth
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = FinDim.bottomInset), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rows = remember(basket, scheme, quotes, period, top, sov) { if (basket == null) emptyList() else PerfPortfolios.rows(basket, scheme, quotes, period, top, sov) }
            val portfolio = PerfPortfolios.portfolioReturn(rows)
            val bench = group.benchmarks.map { b -> b to IndexHoldings.perf(quotes[b.symbol], period) }
            val stats = ArrayList<FinStat>()
            stats += FinStat(decl.word("portfolio"), PerfPortfolios.s2(portfolio) + if (portfolio == null) "" else "%", portfolio?.let { perfDir(it) })
            bench.forEach { (b, v) -> stats += FinStat(b.label, PerfPortfolios.s2(v) + if (v == null) "" else "%", v?.let { perfDir(it) }) }
            PerfPortfolios.active(portfolio, bench.firstOrNull()?.second)?.let { stats += FinStat(decl.word("active"), PerfPortfolios.s2(it) + " pp", perfDir(it)) }
            stats += FinStat(decl.word("quoted"), rows.count { it.perf != null }.toString() + "/" + rows.size)

            FinPanel(
                fn, group.label, subhead = stats,
                trailing = { FinTabs("mode", decl.modes.map { it to decl.word(it).ifEmpty { it } }, mode) { mode = it } },
            ) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FinTabs("period", decl.periods.map { it to it }, period, height = FinDim.chip) { period = it }
                    Box(Modifier.width(1.dp).height(FinDim.chip).background(p.hairline))
                    FinTabs("top", decl.tops.map { it.toString() to perfTopLabel(it) }, top.toString(), height = FinDim.chip) { top = it.toIntOrNull() ?: 0 }
                    FinChip(if (fetching) decl.word("refreshing") else decl.word("refresh"), false) { if (!fetching) refresh += 1 }
                }
                if (group.schemes.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(decl.word("scheme").uppercase(Locale.ROOT), Modifier.padding(end = 6.dp), color = p.text2, style = FinType.label)
                        FinTabs("scheme", group.schemes.map { it.id to it.label }, scheme, height = FinDim.chip) { scheme = it }
                    }
                }
                val sch = group.scheme(scheme)
                val src = basket?.let { PerfPortfolios.sourceOf(it, scheme) }
                Text(
                    (sch?.let { decl.word("scheme") + ": " + it.label + ". " + it.method + ". " } ?: "") +
                        (if (group.intended.isNotEmpty()) decl.word("intended") + ": " + group.intended + " (read from " + group.etf + ", the same index or factor). " else "") +
                        (src?.let { "Source: " + it.source + (if (it.asOf.isNotEmpty()) ", as of " + it.asOf else "") + "." + (it.unlisted?.takeIf { u -> u >= 0.05 }?.let { u -> " Not listed by country or cash: " + PerfPortfolios.f1(u) + " %." } ?: "") } ?: ""),
                    Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp).testTag(PERF_SCHEME), color = p.text2, style = FinType.caption,
                )
            }

            when {
                baskets == null -> PerfStateLine(fn, group.label, if (asked == 0) decl.word("loading") else decl.word("offline"))
                basket == null -> PerfStateLine(fn, group.label, decl.word("missing"))
                rows.isEmpty() -> PerfStateLine(fn, group.label, decl.word("missing"))
                mode == PerfPortfolios.MODE_GRAPHIC -> Graphics(decl, group, basket, scheme, rows, quotes, qdecl, period, top, fetching, width, sov) { period = it }
                else -> TableMode(decl, group, basket, scheme, rows, quotes, period, top, fetching, width, sov) { period = it }
            }
            if (basket != null) {
                Text(
                    group.what + ". " + (if (group.perfNote.isNotEmpty()) group.perfNote + " " else "") + "Figures: " + (qdecl?.source?.label ?: "quote source") +
                        (qdecl?.source?.delay?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "") + ". Contribution = weight x performance, in percentage points." + (if (PerfPortfolios.isSovereign(basket) && SovStore.note().isNotEmpty()) " Approximated bond returns (~): " + SovStore.note() + "." else "") +
                        (if (basket.count > basket.constituents.size) " The dataset carries the " + basket.constituents.size + " largest of the fund's " + basket.count + " holdings." else ""),
                    Modifier.padding(horizontal = 2.dp), color = p.text3, style = FinType.caption,
                )
            }
        }
    }
}

@Composable
private fun PerfStateLine(fn: String, title: String, text: String) {
    val p = LocalFinPalette.current
    FinPanel(fn, title, Modifier.testTag(PERF_STATE)) { Text(text, Modifier.padding(10.dp), color = p.text2, style = FinType.body) }
}

@Composable
private fun NoPerf(decl: PerfDecl, fetching: Boolean) {
    val p = LocalFinPalette.current
    Text(if (fetching) decl.word("asking") else decl.word("noperf"), Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("perf_noperf"), color = p.text3, style = FinType.caption)
}

// ── TABLE ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

// the width of a phone holds the ticker, the name, the weight and three period columns; the contribution and the bucket scroll in from the right
private val COL_TICKER = 58.dp
private val COL_NAME = 100.dp
private val COL_BUCKET = 110.dp
private val COL_NUM = 52.dp
private val COL_CONTRIB = 112.dp
private val ROW_H = 26.dp

/** TABLE mode: the constituents, sortable by any column (a period column also makes that period the page's), with contribution bars and a totals line. */
@Composable
private fun TableMode(
    decl: PerfDecl, group: PerfGroup, basket: PerfBasket, scheme: String, rows: List<PerfRow>, quotes: Map<String, Quote>, period: String, top: Int, fetching: Boolean, width: Dp,
    sov: Map<String, Map<String, Double>>, onPeriod: (String) -> Unit,
) {
    val sovereign = remember(basket) { PerfPortfolios.isSovereign(basket) }
    val p = LocalFinPalette.current
    var sortKey by rememberSaveable(basket.key) { mutableStateOf(PerfPortfolios.SORT_WEIGHT) }
    var desc by rememberSaveable(basket.key) { mutableStateOf(true) }
    var picked by rememberSaveable(basket.key) { mutableStateOf("") }
    val sorted = remember(rows, sortKey, desc) { PerfPortfolios.sort(rows, sortKey, desc) }
    val totals = remember(basket, scheme, quotes, period, top, sov) { PerfPortfolios.totals(basket, scheme, quotes, decl.periods, period, top, decl.word("total"), sov) }
    fun sortBy(key: String) {
        if (sortKey == key) desc = !desc else { sortKey = key; desc = key == PerfPortfolios.SORT_WEIGHT || key == PerfPortfolios.SORT_PERF || key == PerfPortfolios.SORT_CONTRIB }
    }
    FinPanel("PRTU", decl.word("constituents"), Modifier.testTag(PERF_TABLE), meta = rows.size.toString() + " of " + basket.count + " · tap a column to sort") {
        if (rows.none { it.perf != null }) NoPerf(decl, fetching)
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxWidth()) {
            // the pinned ticker column
            Column(Modifier.width(COL_TICKER)) {
                HeadCell(decl.word("ticker"), COL_TICKER, left = true, on = sortKey == PerfPortfolios.SORT_TICKER, desc = desc) { sortBy(PerfPortfolios.SORT_TICKER) }
                sorted.forEachIndexed { i, r ->
                    Box(Modifier.width(COL_TICKER).height(ROW_H).background(rowFill(i, r.c.ticker == picked)).clickable { picked = if (picked == r.c.ticker) "" else r.c.ticker }
                        .padding(horizontal = 6.dp).testTag("perf_row:" + r.c.ticker), contentAlignment = Alignment.CenterStart) {
                        Text(r.c.ticker, color = p.accent, style = FinType.symbol, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    }
                }
                Box(Modifier.width(COL_TICKER).height(1.dp).background(p.hairline))
                Box(Modifier.width(COL_TICKER).height(ROW_H).background(p.surfaceRaised).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                    Text(totals[0], color = p.yellow, style = FinType.symbol, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f).horizontalScroll(scroll)) {
                Row {
                    HeadCell(decl.word("name"), COL_NAME, left = true, on = sortKey == PerfPortfolios.SORT_NAME, desc = desc) { sortBy(PerfPortfolios.SORT_NAME) }
                    HeadCell(decl.word("weight"), COL_NUM, left = false, on = sortKey == PerfPortfolios.SORT_WEIGHT, desc = desc) { sortBy(PerfPortfolios.SORT_WEIGHT) }
                    decl.periods.forEach { per ->
                        HeadCell("$per %", COL_NUM, left = false, on = sortKey == PerfPortfolios.SORT_PERF && per == period, desc = desc, tag = "perf_col:$per") {
                            if (per != period) { onPeriod(per); sortKey = PerfPortfolios.SORT_PERF; desc = true } else sortBy(PerfPortfolios.SORT_PERF)
                        }
                    }
                    // #1727 a sovereign's dollar return in its two legs over the page's period: the bond in local currency, the currency against the dollar
                    if (sovereign) {
                        HeadCell(decl.word("bond"), COL_NUM + 8.dp, left = false, on = false, desc = desc, tag = "perf_col:bond") { }
                        HeadCell(decl.word("fx"), COL_NUM, left = false, on = false, desc = desc, tag = "perf_col:fx") { }
                    }
                    HeadCell(decl.word("contrib"), COL_CONTRIB, left = false, on = sortKey == PerfPortfolios.SORT_CONTRIB, desc = desc) { sortBy(PerfPortfolios.SORT_CONTRIB) }
                    HeadCell(group.bucketLabel, COL_BUCKET, left = true, on = sortKey == PerfPortfolios.SORT_BUCKET, desc = desc) { sortBy(PerfPortfolios.SORT_BUCKET) }
                }
                sorted.forEachIndexed { i, r ->
                    Row(Modifier.height(ROW_H).background(rowFill(i, r.c.ticker == picked)).clickable { picked = if (picked == r.c.ticker) "" else r.c.ticker }, verticalAlignment = Alignment.CenterVertically) {
                        TextCell(r.c.name, COL_NAME, p.text)
                        NumCell(PerfPortfolios.f2(r.weight), COL_NUM, p.text)
                        decl.periods.forEach { per ->
                            val v = if (per == period) r.perf else PerfPortfolios.perf(r.c, quotes, per, sov)
                            NumCell(PerfPortfolios.s2(v), COL_NUM, if (v == null) p.text3 else p.dir(perfDir(v)))
                        }
                        if (sovereign) {
                            val parts = PerfPortfolios.parts(r.c, quotes, period, sov)
                            NumCell((if (parts.approx && parts.bond != null) "~" else "") + PerfPortfolios.s2(parts.bond), COL_NUM + 8.dp, if (parts.bond == null) p.text3 else p.dir(perfDir(parts.bond)))
                            NumCell(PerfPortfolios.s2(parts.fx), COL_NUM, if (parts.fx == null) p.text3 else p.dir(perfDir(parts.fx)))
                        }
                        ContribCell(r.contribution, PerfPortfolios.barShare(r.contribution, rows))
                        TextCell(r.c.bucket, COL_BUCKET, p.text2)
                    }
                }
                Box(Modifier.width(COL_NAME + COL_BUCKET + COL_NUM * (decl.periods.size + 1 + (if (sovereign) 2 else 0)) + (if (sovereign) 8.dp else 0.dp) + COL_CONTRIB).height(1.dp).background(p.hairline))
                Row(Modifier.height(ROW_H).background(p.surfaceRaised), verticalAlignment = Alignment.CenterVertically) {
                    TextCell(PerfPortfolios.sourceOf(basket, scheme).asOf.let { if (it.isEmpty()) "" else "as of $it" }, COL_NAME, p.text2)
                    NumCell(totals[3], COL_NUM, p.text, bold = true)
                    decl.periods.forEachIndexed { k, _ ->
                        val s = totals.getOrElse(4 + k) { FinFormat.DASH }
                        NumCell(s, COL_NUM, if (s == FinFormat.DASH) p.text3 else p.dir(FinFormat.dirOf(s)), bold = true)
                    }
                    if (sovereign) { NumCell("", COL_NUM + 8.dp, p.text3); NumCell("", COL_NUM, p.text3) }
                    val c = totals.last()
                    NumCell(c, COL_CONTRIB, if (c == FinFormat.DASH) p.text3 else p.dir(FinFormat.dirOf(c)), bold = true)
                    TextCell("", COL_BUCKET, p.text2)
                }
            }
        }
        sorted.firstOrNull { it.c.ticker == picked }?.let { r -> PickedLine(r, period) }
    }
}

@Composable
private fun rowFill(i: Int, selected: Boolean): Color {
    val p = LocalFinPalette.current
    return if (selected) p.select else if (i % 2 == 1) p.zebra else Color.Transparent
}

@Composable
private fun HeadCell(text: String, w: Dp, left: Boolean, on: Boolean, desc: Boolean, tag: String = "", onClick: () -> Unit) {
    val p = LocalFinPalette.current
    Box(
        Modifier.width(w).height(ROW_H).background(p.surfaceRaised).clickable(onClick = onClick).padding(horizontal = 6.dp).let { if (tag.isEmpty()) it else it.testTag(tag) },
        contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
    ) { Text(text + if (on) (if (desc) " ▼" else " ▲") else "", color = if (on) p.accent else p.text, style = FinType.label, maxLines = 1, softWrap = false) }
}

@Composable
private fun TextCell(text: String, w: Dp, color: Color) {
    Text(text, Modifier.width(w).padding(horizontal = 6.dp), color = color, style = FinType.body, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun NumCell(text: String, w: Dp, color: Color, bold: Boolean = false) {
    Text(text, Modifier.width(w).padding(horizontal = 6.dp), color = color, style = if (bold) FinType.numStrong else FinType.num, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
}

/** The contribution cell: a bar as long as the contribution's share of the largest one (up or down colour), then the signed figure. */
@Composable
private fun ContribCell(contribution: Double?, share: Float) {
    val p = LocalFinPalette.current
    Row(Modifier.width(COL_CONTRIB).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (contribution != null && share > 0f) Box(Modifier.width(44.dp * share).height(8.dp).background(p.dir(perfDir(contribution))))
        Spacer(Modifier.width(6.dp))
        Text(PerfPortfolios.s2(contribution), Modifier.width(48.dp), color = if (contribution == null) p.text3 else p.dir(perfDir(contribution)), style = FinType.num, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PickedLine(r: PerfRow, period: String) {
    val p = LocalFinPalette.current
    Text(
        r.c.ticker + "  " + r.c.name + (if (r.c.proxy.isNotEmpty()) "  ·  " + r.c.proxy + (if (r.c.symbol.isNotEmpty()) " (" + r.c.symbol + ")" else "") else "") +
            (if (r.c.basis == PerfPortfolios.BASIS_FX) "  ·  currency vs USD (" + r.c.symbol + ")" else "") +
            (if (r.c.basis == PerfPortfolios.BASIS_SOV) "  ·  " + (if (r.c.symbol.isEmpty()) "no listed bond fund: yield and duration approximation where published" else "dollar return of its government bonds") else "") + (if (r.c.note.isNotEmpty()) "  ·  " + r.c.note else "") +
            "  ·  weight " + PerfPortfolios.f2(r.weight) + " %  ·  $period " + (r.perf?.let { PerfPortfolios.s2(it) + " %" } ?: "n/a") +
            "  ·  contribution " + (r.contribution?.let { PerfPortfolios.s2(it) + " pp" } ?: "n/a"),
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp).testTag("perf_picked"), color = p.text, style = FinType.caption,
    )
}

// ── GRAPHIC ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** GRAPHIC mode: the heat map, the attribution waterfall and the bubble chart, each in its own panel with a function code. */
@Composable
private fun Graphics(
    decl: PerfDecl, group: PerfGroup, basket: PerfBasket, scheme: String, rows: List<PerfRow>, quotes: Map<String, Quote>, qdecl: QuotesDecl?, period: String, top: Int,
    fetching: Boolean, width: Dp, sov: Map<String, Map<String, Double>>, onPeriod: (String) -> Unit,
) {
    val p = LocalFinPalette.current
    val scale = qdecl?.heat?.get(IndexHoldings.heatKey(period)) ?: 2.0
    val (adv, dec) = PerfPortfolios.advDec(rows)
    var picked by remember(basket.key, scheme, top) { mutableIntStateOf(-1) }
    val inner = width - 2.dp

    FinPanel(
        "MAP", decl.word("map"), Modifier.testTag(PERF_MAP),
        subhead = listOf(
            FinStat(decl.word("size"), group.scheme(scheme)?.label ?: decl.word("weight")),
            FinStat(decl.word("color"), "Performance $period"),
            FinStat(decl.word("advdec"), "$adv / $dec"),
        ),
        trailing = { FinTabs("map", decl.periods.map { it to it }, period) { onPeriod(it) } },
    ) {
        PerfTreemap(rows, scale, inner, picked) { picked = if (picked == it) -1 else it }
        rows.getOrNull(picked)?.let { PickedLine(it, period) }
        if (rows.none { it.perf != null }) NoPerf(decl, fetching)
    }

    val steps = remember(rows) { PerfPortfolios.attribution(rows, 10, "Rest", decl.word("total")) }
    val portfolio = PerfPortfolios.portfolioReturn(rows)
    val bench = group.benchmarks.firstOrNull()
    val benchRet = bench?.let { IndexHoldings.perf(quotes[it.symbol], period) }
    val portStats = ArrayList<FinStat>()
    portStats += FinStat(decl.word("portfolio"), PerfPortfolios.s2(portfolio) + if (portfolio == null) "" else "%", portfolio?.let { perfDir(it) })
    if (bench != null) portStats += FinStat(bench.label, PerfPortfolios.s2(benchRet) + if (benchRet == null) "" else "%", benchRet?.let { perfDir(it) })
    PerfPortfolios.active(portfolio, benchRet)?.let { portStats += FinStat(decl.word("active"), PerfPortfolios.s2(it) + " pp", perfDir(it)) }
    PerfPortfolios.total(rows)?.let { (c, w) -> portStats += FinStat(decl.word("contrib"), PerfPortfolios.s2(c) + " on " + PerfPortfolios.f1(w) + " %") }
    FinPanel("PORT", decl.word("attribution"), Modifier.testTag(PERF_WATERFALL), meta = "by " + group.bucketLabel.lowercase(Locale.ROOT) + " · " + period, subhead = portStats) {
        if (steps.isEmpty()) NoPerf(decl, fetching) else PerfWaterfall(steps)
    }

    // #1727 the flow panel (the reference's sankey): the portfolio into its buckets, each bucket into the largest constituents
    val flow = remember(rows) { PerfPortfolios.flow(rows, decl.sankeyBuckets, decl.sankeyHoldings, group.label, decl.word("other").ifEmpty { "Other" }, decl.word("rest_short").ifEmpty { "Rest" }) }
    FinPanel(
        "FLOW", decl.word("flow").ifEmpty { "Weight flow" }, Modifier.testTag(PERF_FLOW), meta = group.bucketLabel.lowercase(Locale.ROOT) + " \u2192 constituents",
        subhead = listOf(
            FinStat(group.bucketLabel + "s", flow.nodes.count { it.col == 1 }.toString()),
            FinStat("Largest", flow.nodes.filter { it.col == 1 }.maxByOrNull { it.value }?.let { it.label + " " + PerfPortfolios.f1(it.value) + "%" }.orEmpty()),
            FinStat("Colour", "Performance $period"),
        ),
    ) {
        if (flow.nodes.isEmpty()) NoPerf(decl, fetching) else PerfFlowChart(flow, scale, inner)
    }

    val bubbles = remember(basket, scheme, quotes, top, sov) { PerfPortfolios.bubbles(basket, scheme, quotes, decl.bubbleX, decl.bubbleY, top, sov) }
    FinPanel(
        "EQRV", decl.word("bubble"), Modifier.testTag(PERF_BUBBLE), meta = decl.bubbleX + " vs " + decl.bubbleY,
        subhead = listOf(
            FinStat("Median " + decl.bubbleY, PerfPortfolios.s2(PerfPortfolios.median(bubbles.map { it.y })) + if (bubbles.isEmpty()) "" else "%"),
            FinStat("Median " + decl.bubbleX, PerfPortfolios.s2(PerfPortfolios.median(bubbles.map { it.x })) + if (bubbles.isEmpty()) "" else "%"),
            FinStat("Bubble", decl.word("weight")),
        ),
    ) {
        if (bubbles.isEmpty()) NoPerf(decl, fetching) else PerfBubbleChart(bubbles, decl.bubbleX, decl.bubbleY, inner)
    }
    Text("Tile size = weight; colour = performance over the period, full tint at " + PerfPortfolios.f1(scale) + " % (red down, green up, grey = no quote).", Modifier.padding(horizontal = 2.dp),
        color = p.text3, style = FinType.caption)
}

/** A tile's fill on the terminal scale: the neutral grey toward the full down red or the full up green, by the clamped performance; no quote = a darker grey. */
internal fun perfTileColor(perf: Double?, scale: Double): Color {
    if (perf == null) return Color(0xFF141414)
    val h = IndexHoldings.heat(perf, scale)
    val b = IndexHoldings.blend(h)
    return if (h > 0f) lerp(Color(0xFF2A2A2A), Color(0xFF1FA35C), b) else if (h < 0f) lerp(Color(0xFF2A2A2A), Color(0xFFD6362F), b) else Color(0xFF2A2A2A)
}

/**
 * The heat map: one Canvas, a tile per constituent laid out by [IndexHoldings.treemap] (AREA = weight, the largest at the top left), black seams, filled
 * by performance on [perfTileColor]. A tile large enough carries its ticker and its % change; a tap picks a tile (its line is shown under the map).
 */
@Composable
private fun PerfTreemap(rows: List<PerfRow>, scale: Double, width: Dp, picked: Int, onPick: (Int) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val height = (width * 0.95f).coerceAtMost(480.dp).coerceAtLeast(220.dp)
    val wPx = with(density) { width.toPx() }
    val hPx = with(density) { height.toPx() }
    val rects = remember(rows, wPx, hPx) { IndexHoldings.treemap(rows.map { it.weight }, wPx.toDouble(), hPx.toDouble()) }
    val seam = with(density) { 1.dp.toPx() }
    val big = FinType.symbol.copy(color = Color.White)
    val small = FinType.numSmall.copy(color = Color.White)
    val tiny = FinType.caption.copy(color = Color.White, fontWeight = FontWeight.SemiBold)
    Canvas(
        Modifier.width(width).height(height).background(Color.Black).testTag("perf_treemap")
            .pointerInput(rects) {
                detectTapGestures { at ->
                    val i = rects.indexOfFirst { r -> r.w > 0 && at.x >= r.x && at.x < r.x + r.w && at.y >= r.y && at.y < r.y + r.h }
                    if (i >= 0) onPick(i)
                }
            },
    ) {
        rects.forEachIndexed { i, r ->
            if (r.w <= 0.0 || r.h <= 0.0 || i >= rows.size) return@forEachIndexed
            val row = rows[i]
            val x = r.x.toFloat(); val y = r.y.toFloat(); val w = r.w.toFloat(); val h = r.h.toFloat()
            val s = if (w > seam * 3 && h > seam * 3) seam else 0f
            drawRect(perfTileColor(row.perf, scale), Offset(x + s, y + s), Size(max(0f, w - s * 2), max(0f, h - s * 2)))
            if (i == picked) drawRect(Color.White, Offset(x + seam, y + seam), Size(max(0f, w - seam * 2), max(0f, h - seam * 2)), style = Stroke(width = seam * 2))
            val t1 = measurer.measure(row.c.ticker, big, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
            val change = row.perf?.let { String.format(Locale.ROOT, "%+.1f%%", it) }
            val t2 = change?.let { measurer.measure(it, small, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1) }
            val pad = seam * 4
            if (t1.size.width + pad <= w && t1.size.height + (t2?.size?.height ?: 0) + pad <= h) {
                val total = t1.size.height + (t2?.size?.height ?: 0)
                val top = y + (h - total) / 2f
                drawText(t1, topLeft = Offset(x + (w - t1.size.width) / 2f, top))
                if (t2 != null && t2.size.width + pad <= w) drawText(t2, topLeft = Offset(x + (w - t2.size.width) / 2f, top + t1.size.height))
            } else {
                val t3 = measurer.measure(row.c.ticker, tiny, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)
                if (t3.size.width + pad <= w && t3.size.height + seam * 2 <= h) drawText(t3, topLeft = Offset(x + (w - t3.size.width) / 2f, y + (h - t3.size.height) / 2f))
            }
        }
    }
}

/**
 * The attribution waterfall, turned on its side for a phone: one line per bucket (its name, a bar floating from where the previous one ended to where
 * this one ends, its contribution in pp), then the total from zero. Up bars in the up colour, down bars in the down colour, the total in the accent.
 */
@Composable
private fun PerfWaterfall(steps: List<PerfStep>) {
    val p = LocalFinPalette.current
    val (lo, hi) = PerfPortfolios.span(steps)
    val span = if (hi - lo > 1e-9) hi - lo else 1.0
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        steps.forEach { s ->
            Row(Modifier.fillMaxWidth().height(24.dp).background(if (s.total) p.surfaceRaised else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                Text(s.label, Modifier.width(118.dp).padding(horizontal = 8.dp), color = if (s.total) p.yellow else p.text, style = if (s.total) FinType.symbol else FinType.body,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                val color = if (s.total) p.accent else p.dir(perfDir(s.value))
                val rule = p.hairline
                Canvas(Modifier.weight(1f).fillMaxHeight().testTag("perf_step:" + s.label)) {
                    fun xOf(v: Double): Float = ((v - lo) / span * size.width).toFloat()
                    val zero = xOf(0.0)
                    drawLine(rule, Offset(zero, 0f), Offset(zero, size.height), strokeWidth = 1.dp.toPx())
                    val a = xOf(min(s.start, s.end)); val b = xOf(max(s.start, s.end))
                    val inset = 5.dp.toPx()
                    drawRect(color, Offset(a, inset), Size(max(b - a, 1.dp.toPx()), size.height - inset * 2))
                }
                Text(PerfPortfolios.s2(s.value), Modifier.width(58.dp).padding(horizontal = 6.dp), color = if (s.total) p.yellow else p.dir(perfDir(s.value)), style = FinType.num,
                    textAlign = TextAlign.End, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The bubble chart: x = performance over [xPeriod], y = performance over [yPeriod], bubble AREA = weight; the zero lines are drawn, a bubble is the up
 * colour when its y is positive and the down colour when negative, the largest bubbles carry their ticker.
 */
@Composable
private fun PerfBubbleChart(bubbles: List<PerfBubble>, xPeriod: String, yPeriod: String, width: Dp) {
    val p = LocalFinPalette.current
    val measurer = rememberTextMeasurer()
    val height = (width * 0.7f).coerceAtMost(340.dp).coerceAtLeast(200.dp)
    val labelStyle = FinType.caption.copy(color = p.text)
    val axisStyle = FinType.caption.copy(color = p.text2)
    val up = p.up; val down = p.down; val rule = p.hairline; val grid = p.head2
    Canvas(Modifier.width(width).height(height).testTag("perf_bubbles")) {
        val padL = 34.dp.toPx(); val padB = 18.dp.toPx(); val padT = 8.dp.toPx(); val padR = 10.dp.toPx()
        val plotW = size.width - padL - padR; val plotH = size.height - padT - padB
        if (plotW <= 0f || plotH <= 0f) return@Canvas
        var x0 = min(0.0, bubbles.minOf { it.x }); var x1 = max(0.0, bubbles.maxOf { it.x })
        var y0 = min(0.0, bubbles.minOf { it.y }); var y1 = max(0.0, bubbles.maxOf { it.y })
        val mx = max((x1 - x0) * 0.08, 0.5); val my = max((y1 - y0) * 0.08, 0.5)
        x0 -= mx; x1 += mx; y0 -= my; y1 += my
        fun px(v: Double): Float = padL + ((v - x0) / (x1 - x0) * plotW).toFloat()
        fun py(v: Double): Float = padT + plotH - ((v - y0) / (y1 - y0) * plotH).toFloat()
        drawRect(grid, Offset(padL, padT), Size(plotW, plotH), style = Stroke(width = 1.dp.toPx()))
        drawLine(rule, Offset(px(0.0), padT), Offset(px(0.0), padT + plotH), strokeWidth = 1.dp.toPx())
        drawLine(rule, Offset(padL, py(0.0)), Offset(padL + plotW, py(0.0)), strokeWidth = 1.dp.toPx())
        val wMax = bubbles.maxOf { it.weight }.coerceAtLeast(1e-9)
        val rMax = 22.dp.toPx(); val rMin = 3.dp.toPx()
        val order = bubbles.sortedByDescending { it.weight }
        order.forEach { b ->
            val r = max(rMin, (rMax * sqrt(b.weight / wMax)).toFloat())
            val c = Offset(px(b.x), py(b.y))
            val color = if (b.y >= 0.0) up else down
            drawCircle(color.copy(alpha = 0.45f), r, c)
            drawCircle(color, r, c, style = Stroke(width = 1.dp.toPx()))
        }
        order.take(12).forEach { b ->
            val t = measurer.measure(b.ticker, labelStyle, softWrap = false, maxLines = 1)
            val cx = px(b.x) - t.size.width / 2f; val cy = py(b.y) - t.size.height / 2f
            drawText(t, topLeft = Offset(cx.coerceIn(padL, max(padL, size.width - padR - t.size.width)), cy.coerceIn(padT, max(padT, padT + plotH - t.size.height))))
        }
        // the axis: the ends of each scale and what each axis is
        val yHi = measurer.measure(String.format(Locale.ROOT, "%+.0f", y1), axisStyle, softWrap = false, maxLines = 1)
        val yLo = measurer.measure(String.format(Locale.ROOT, "%+.0f", y0), axisStyle, softWrap = false, maxLines = 1)
        drawText(yHi, topLeft = Offset(max(0f, padL - yHi.size.width - 3.dp.toPx()), padT))
        drawText(yLo, topLeft = Offset(max(0f, padL - yLo.size.width - 3.dp.toPx()), padT + plotH - yLo.size.height))
        val xLo = measurer.measure(String.format(Locale.ROOT, "%+.0f", x0), axisStyle, softWrap = false, maxLines = 1)
        val xHi = measurer.measure(String.format(Locale.ROOT, "%+.0f", x1), axisStyle, softWrap = false, maxLines = 1)
        val xT = measurer.measure("$xPeriod % →   ($yPeriod % ↑)", axisStyle, softWrap = false, maxLines = 1)
        drawText(xLo, topLeft = Offset(padL, padT + plotH + 2.dp.toPx()))
        drawText(xHi, topLeft = Offset(padL + plotW - xHi.size.width, padT + plotH + 2.dp.toPx()))
        drawText(xT, topLeft = Offset(padL + (plotW - xT.size.width) / 2f, padT + plotH + 2.dp.toPx()))
    }
}

/** The bucket colours of the flow panel, in the order of the buckets (the terminal's amber first, then blues and muted tones; the folded bucket is grey). */
private val FLOW_COLORS = listOf(Color(0xFFFB8B1E), Color(0xFF3D8BFF), Color(0xFFFFD24A), Color(0xFF9B6BFF), Color(0xFF2BB7C4), Color(0xFFE0629A), Color(0xFF8A8A8A))

/**
 * The flow panel (the reference's sankey): three columns of nodes (the portfolio, its buckets, its largest constituents) joined by ribbons as thick as the
 * weight they carry. A bucket and its ribbons share a colour; a constituent's node is filled by its performance on [perfTileColor]; labels sit right of
 * their node with the weight in %.
 */
@Composable
private fun PerfFlowChart(flow: Flow, scale: Double, width: Dp) {
    val p = LocalFinPalette.current
    val measurer = rememberTextMeasurer()
    val height = (width * 0.9f).coerceAtMost(420.dp).coerceAtLeast(260.dp)
    val label = FinType.caption.copy(color = p.text)
    val muted = FinType.caption.copy(color = p.text2)
    val buckets = flow.nodes.withIndex().filter { it.value.col == 1 }.map { it.index }
    Canvas(Modifier.width(width).height(height).testTag("perf_flow_chart")) {
        val pad = 6.dp.toPx(); val nodeW = 9.dp.toPx()
        val h = size.height - pad * 2
        val x0 = pad; val x1 = size.width * 0.36f; val x2 = size.width * 0.72f
        fun xOf(col: Int): Float = when (col) { 0 -> x0; 1 -> x1; else -> x2 }
        fun yOf(v: Double): Float = pad + (v * h).toFloat()
        fun colorOf(node: Int): Color = buckets.indexOf(node).let { i -> if (i < 0) FLOW_COLORS.last() else FLOW_COLORS[minOf(i, FLOW_COLORS.size - 1)] }
        flow.links.forEach { l ->
            val a = xOf(flow.nodes[l.from].col) + nodeW; val b = xOf(flow.nodes[l.to].col)
            val mid = (a + b) / 2f
            val c = colorOf(if (flow.nodes[l.from].col == 1) l.from else l.to)
            val path = Path().apply {
                moveTo(a, yOf(l.sy0)); cubicTo(mid, yOf(l.sy0), mid, yOf(l.ty0), b, yOf(l.ty0))
                lineTo(b, yOf(l.ty1)); cubicTo(mid, yOf(l.ty1), mid, yOf(l.sy1), a, yOf(l.sy1)); close()
            }
            drawPath(path, c.copy(alpha = 0.30f))
        }
        flow.nodes.forEachIndexed { i, n ->
            val x = xOf(n.col); val top = yOf(n.y0); val hh = max(1.dp.toPx(), yOf(n.y1) - top)
            val fill = when (n.col) { 0 -> p.accent; 1 -> colorOf(i); else -> n.row?.let { perfTileColor(it.perf, scale) } ?: FLOW_COLORS.last() }
            drawRect(fill, Offset(x, top), Size(nodeW, hh))
            if (n.col == 0) return@forEachIndexed
            val t = measurer.measure(n.label + "  " + PerfPortfolios.f1(n.value), if (n.col == 1) label else muted, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = max(1f, (if (n.col == 1) x2 - x1 - nodeW - 8.dp.toPx() else size.width - x2 - nodeW - 4.dp.toPx())).toInt()))
            if (t.size.height <= hh + 2.dp.toPx()) drawText(t, topLeft = Offset(x + nodeW + 4.dp.toPx(), top + (hh - t.size.height) / 2f))
        }
    }
}
