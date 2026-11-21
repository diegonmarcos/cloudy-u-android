package com.diegonmarcos.capitalfinterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.Markz
import com.diegonmarcos.capitalfinterm.MarkzCell
import com.diegonmarcos.capitalfinterm.MarkzDecl
import com.diegonmarcos.capitalfinterm.MarkzInput
import com.diegonmarcos.capitalfinterm.MarkzInputs
import com.diegonmarcos.capitalfinterm.MarkzMatrix
import com.diegonmarcos.capitalfinterm.MarkzOpt
import com.diegonmarcos.capitalfinterm.MarkzPort
import com.diegonmarcos.capitalfinterm.MarkzScreen
import com.diegonmarcos.capitalfinterm.MarkzStats
import com.diegonmarcos.capitalfinterm.MarkzStatsResult
import com.diegonmarcos.capitalfinterm.QuotesStore
import com.diegonmarcos.capitalfinterm.UrlHttp
import com.diegonmarcos.capitalfinterm.ui.fin.FinDim
import com.diegonmarcos.capitalfinterm.ui.fin.FinPanel
import com.diegonmarcos.capitalfinterm.ui.fin.FinTabs
import com.diegonmarcos.capitalfinterm.ui.fin.FinType
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** #1832 the test tag of a MARKZ page (+ its view: home, sensy, markovitz, volatility, beta, capm, correlation, optimization). */
const val MARKZ_PAGE = "markz:"

/** What the substeps read: nothing yet, the statistics, or the symbols the quote source did not answer for. */
sealed class MarkzLoad {
    object Idle : MarkzLoad()
    object Loading : MarkzLoad()
    data class Done(val r: MarkzStatsResult) : MarkzLoad()
    data class Failed(val missing: List<String>) : MarkzLoad()
}

/**
 * #1832 the one state of MARKZ (the owner's `state`, `roundToSheetStep`, `stepsMenuOpen`): every MARKZ page reads and edits it, so the Home and Markovitz
 * pages follow the Sensy Matrix as on the owner's page. Kept for the process, like the page (nothing is stored).
 */
object MarkzStore {
    @Volatile private var cache: MarkzDecl? = null
    fun decl(ctx: Context): MarkzDecl? = cache ?: runCatching { Markz.parse(AssetData.read(ctx, Markz.ASSET)) }.getOrNull()?.also { cache = it }

    var inputs by mutableStateOf<MarkzInputs?>(null)
    var rounded by mutableStateOf(true)
    var stepsOpen by mutableStateOf(false)
    /** The matrix cell the inspector shows (row, column); null = the centre. */
    var picked by mutableStateOf<Pair<Int, Int>?>(null)
    var volModel by mutableStateOf("")
    var rf by mutableStateOf<Double?>(null)
    var erm by mutableStateOf<Double?>(null)
    var load by mutableStateOf<MarkzLoad>(MarkzLoad.Idle)
    /** The owner's non-blocking toast: the last message and when it was shown (it clears itself after [TOAST_MS]). */
    var toast by mutableStateOf("")
    var toastAt by mutableStateOf(0L)
    const val TOAST_MS = 2400L

    fun state(d: MarkzDecl): MarkzInputs = inputs ?: d.defaults.also { inputs = it }

    /** resetDefaults: the owner's baseline, rounded to the sheet's step. */
    fun reset(d: MarkzDecl) { inputs = d.defaults; rounded = true; picked = null }

    /**
     * The substeps' closes: every declared asset, the benchmark and the implied volatility indices from the quote source of data/quotes.json (its URL and
     * User-Agent), once per process. Blocking: call off the main thread.
     */
    fun loadStats(ctx: Context, d: MarkzDecl) {
        if (load is MarkzLoad.Done || load is MarkzLoad.Loading) return
        load = MarkzLoad.Loading
        val src = runCatching { QuotesStore.decl(ctx).source }.getOrNull()
        if (src == null) { load = MarkzLoad.Failed(listOf(d.stats.benchmark.symbol)); return }
        val http = UrlHttp()
        fun read(sym: String) = runCatching { MarkzStats.closes(http.send(src.url.replace("{symbol}", URLEncoder.encode(sym, "UTF-8")), mapOf("User-Agent" to src.userAgent), null)) }.getOrDefault(emptyList())
        val series = d.stats.assets + d.stats.benchmark
        val closes = series.map { read(it.symbol) }
        val missing = series.filterIndexed { i, _ -> closes[i].size < 3 }.map { it.symbol }
        if (missing.isNotEmpty()) { load = MarkzLoad.Failed(missing); return }
        val implied = series.map { a -> a.implied.takeIf { it.isNotEmpty() }?.let { read(it).lastOrNull()?.second } }
        load = MarkzStats.compute(d.stats, series.map { it.id }, closes, implied)?.let { MarkzLoad.Done(it) } ?: MarkzLoad.Failed(series.map { it.symbol })
    }
}

private fun hex(c: String): Color = runCatching { Color(0xFF000000 or c.removePrefix("#").toLong(16)) }.getOrDefault(Color.Gray)

@Suppress("UNUSED_PARAMETER")
private fun toast(ctx: Context, msg: String) { if (msg.isNotEmpty()) { MarkzStore.toast = msg; MarkzStore.toastAt = System.currentTimeMillis() } }

/**
 * #1832 a MARKZ sub-page ([view]: a tab of data/markz.json): the owner's page drawn in the terminal style. [onOpenPage] opens another MARKZ sub-page
 * (the owner's links between the tabs).
 */
@Composable
fun MarkzPage(view: String, onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    val d = remember { MarkzStore.decl(ctx) }
    if (d == null) { Text(view, color = p.text, style = FinType.title); return }
    val s = MarkzStore.state(d)
    val go: (String) -> Unit = { tab -> d.tab(tab)?.page?.let(onOpenPage) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(MARKZ_PAGE + view),
        verticalArrangement = Arrangement.spacedBy(FinDim.gap),
    ) {
        MarkzBar(d, s)
        MarkzToast()
        when (view) {
            "home" -> MarkzHome(d, s, go)
            "sensy" -> MarkzSensy(d, s)
            "markovitz" -> MarkzMarkovitz(d, s, go)
            else -> d.substep(view)?.let { MarkzSubstepPage(d, s, it.id, go) } ?: Text(view, color = p.text, style = FinType.title)
        }
        Spacer(Modifier.height(FinDim.bottomInset))
    }
}

/** The owner's toast (copied, reset, loaded): a strip under the bar with a green dot, gone after 2.4 s. */
@Composable
private fun MarkzToast() {
    val p = LocalFinPalette.current
    val msg = MarkzStore.toast
    val at = MarkzStore.toastAt
    LaunchedEffect(at) { if (msg.isNotEmpty()) { kotlinx.coroutines.delay(MarkzStore.TOAST_MS); if (MarkzStore.toastAt == at) MarkzStore.toast = "" } }
    if (msg.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(p.surfaceRaised).border(1.dp, p.hairline).padding(horizontal = 10.dp, vertical = 6.dp).testTag("markz:toast"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).background(p.up))
        Spacer(Modifier.width(8.dp))
        Text(msg, color = p.text, style = FinType.label, modifier = Modifier.testTag("markz:toast-message"))
    }
}

/** The owner's top bar: the PortfolioLab brand and the live net-a-month badge. */
@Composable
private fun MarkzBar(d: MarkzDecl, s: MarkzInputs) {
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().height(FinDim.panelHead).background(p.head2).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(d.brand.getOrElse(0) { "" }, color = p.text, style = FinType.label)
        Text(d.brand.getOrElse(1) { "" }, Modifier.weight(1f), color = p.up, style = FinType.label)
        Text(Markz.home(d, s, d.defaults).getValue("nav-live-badge"), color = p.up, style = FinType.label, modifier = Modifier.testTag("markz:nav-live-badge"))
    }
}

@Composable
private fun Kicker(text: String) = Text(text.uppercase(java.util.Locale.ROOT), color = LocalFinPalette.current.amber, style = FinType.label)

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = LocalFinPalette.current.text2, style = FinType.body)

/** A terminal button: amber ink on the canvas inside a rule (filled when [primary]). */
@Composable
private fun MkButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalFinPalette.current
    Box(
        modifier.height(30.dp).background(if (primary) p.accent else p.canvas).border(1.dp, if (primary) p.accent else p.hairline).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) Color.Black else p.accent, style = FinType.label, maxLines = 1) }
}

/** One label / value line. */
@Composable
private fun KvLine(k: String, v: String, tag: String = "", strong: Boolean = false) {
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, Modifier.weight(1f), color = p.text2, style = FinType.body)
        Text(v, Modifier.then(if (tag.isEmpty()) Modifier else Modifier.testTag("markz:$tag")), color = if (strong) p.up else p.text, style = if (strong) FinType.numStrong else FinType.num)
    }
}

/** A KPI tile: label, value (and a second value), sub line. */
@Composable
private fun Tile(label: String, value: String, sub: String, modifier: Modifier, value2: String = "", tag: String = "") {
    val p = LocalFinPalette.current
    Column(modifier.background(p.surface).border(1.dp, p.hairline).padding(8.dp)) {
        Text(label, color = p.text2, style = FinType.caption, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = p.text, style = FinType.kpi, maxLines = 1, modifier = if (tag.isEmpty()) Modifier else Modifier.testTag("markz:$tag"))
            if (value2.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Text(value2, color = p.up, style = FinType.label, maxLines = 1) }
        }
        Text(sub, color = p.text3, style = FinType.caption, maxLines = 2)
    }
}

// ------------------------------------------------------------------ Home

@Composable
private fun MarkzHome(d: MarkzDecl, s: MarkzInputs, go: (String) -> Unit) {
    val p = LocalFinPalette.current
    val h = Markz.home(d, s, d.defaults)
    FinPanel("MKOV", d.word("home_title")) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker(d.word("home_kicker"))
            Body(d.word("home_sub"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MkButton(d.word("home_open_sensy"), primary = true) { go("sensy") }
                MkButton(d.word("home_open_markovitz")) { go("markovitz") }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        Tile(d.word("home_aum_usd"), h.getValue("home-aum-usd"), h.getValue("home-mom-usd"), Modifier.weight(1f), tag = "home-aum-usd")
        Tile(d.word("home_aum_eur"), h.getValue("home-aum-eur"), h.getValue("home-mom-eur"), Modifier.weight(1f), tag = "home-aum-eur")
        Tile(d.word("home_aum_brl"), h.getValue("home-aum-brl"), h.getValue("home-mom-brl"), Modifier.weight(1f), tag = "home-aum-brl")
    }
    FinPanel("", d.word("waterfall")) {
        KvLine(d.word("wf_gross"), h.getValue("home-wf-gross"), "home-wf-gross")
        KvLine(d.word("wf_infl"), h.getValue("home-wf-infl"), "home-wf-infl")
        KvLine(d.word("wf_tax"), h.getValue("home-wf-tax"), "home-wf-tax")
        KvLine(d.word("wf_annual"), h.getValue("home-wf-annual-eur"), "home-wf-annual-eur", strong = true)
    }
    FinPanel("", d.word("fx_title")) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            Tile(d.word("fx_eurusd"), h.getValue("home-fx-eurusd"), "", Modifier.weight(1f), tag = "home-fx-eurusd")
            Tile(d.word("fx_usdbrl"), h.getValue("home-fx-usdbrl"), "", Modifier.weight(1f), tag = "home-fx-usdbrl")
            Tile(d.word("fx_eurbrl"), h.getValue("home-fx-eurbrl"), "", Modifier.weight(1f), tag = "home-fx-eurbrl")
        }
        Text(d.word("home_note"), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = p.text3, style = FinType.caption)
    }
}

// ------------------------------------------------------------------ Sensy Matrix

@Composable
private fun MarkzSensy(d: MarkzDecl, s: MarkzInputs) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    val rounded = MarkzStore.rounded
    val m = Markz.matrix(s, d.defaults, rounded)
    val k = Markz.kpis(d, s, d.defaults, m, rounded)
    FinPanel("SENS", d.word("sensy_title"), meta = k.getValue("header-nav-pill")) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker(d.word("sensy_kicker"))
            Body(d.word("sensy_sub"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FinTabs("markz_round", listOf("sheet" to k.getValue("round-step-label"), "exact" to d.word("round_exact")), if (rounded) "sheet" else "exact", height = 30.dp) {
                    MarkzStore.rounded = it == "sheet"
                }
                MkButton(d.word("copy_csv")) {
                    val ok = runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(d.word("matrix_title"), Markz.csv(d, m))) }.isSuccess
                    toast(ctx, d.word(if (ok) "toast_copied" else "toast_copy_failed"))
                }
                MkButton(d.word("reset")) { MarkzStore.reset(d); toast(ctx, d.word("toast_reset")) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        Tile(d.word("kpi_aum"), k.getValue("kpi-nav-eur"), k.getValue("kpi-nav-usd"), Modifier.weight(1f), value2 = k.getValue("kpi-nav-brl"), tag = "kpi-nav-eur")
        Tile(d.word("kpi_baseline"), k.getValue("kpi-baseline"), k.getValue("kpi-baseline-sub"), Modifier.weight(1f), tag = "kpi-baseline")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        Tile(d.word("kpi_real"), k.getValue("kpi-real-yield"), k.getValue("kpi-yield-sub"), Modifier.weight(1f), value2 = k.getValue("kpi-net-real-yield"), tag = "kpi-real-yield")
        Tile(d.word("kpi_range"), k.getValue("kpi-range"), k.getValue("kpi-range-sub"), Modifier.weight(1f), tag = "kpi-range")
    }
    FinPanel("", d.word("matrix_title"), trailing = {
        Text("■ " + d.word("legend_down"), Modifier.padding(end = 6.dp), color = p.down, style = FinType.caption)
        Text("■ " + d.word("legend_base"), Modifier.padding(end = 6.dp), color = p.text2, style = FinType.caption)
        Text("■ " + d.word("legend_up"), Modifier.padding(end = 8.dp), color = p.up, style = FinType.caption)
    }) {
        MatrixTable(d, m)
        val (r, c) = MarkzStore.picked ?: (3 to 3)
        val cell = m.rows.getOrNull(r)?.cells?.getOrNull(c) ?: m.center
        val (head, math) = Markz.inspector(d, cell)
        Column(Modifier.fillMaxWidth().background(p.head2).padding(8.dp).testTag("markz:inspector")) {
            Row { Text(d.word("inspector").uppercase(java.util.Locale.ROOT), color = p.amber, style = FinType.label); Spacer(Modifier.width(8.dp)); Text(head, color = p.text, style = FinType.caption) }
            Text(math, color = p.text2, style = FinType.numSmall)
        }
    }
    FinPanel("", d.word("params_title"), trailing = {
        Row(Modifier.clickable { MarkzStore.stepsOpen = !MarkzStore.stepsOpen }.padding(horizontal = 8.dp).testTag("markz:btn-toggle-steps"), verticalAlignment = Alignment.CenterVertically) {
            Text(d.word("steps"), color = p.text, style = FinType.label)
            Spacer(Modifier.width(6.dp))
            Text(k.getValue("steps-summary-badge"), color = p.amber, style = FinType.caption)
            Text(if (MarkzStore.stepsOpen) " ▲" else " ▼", color = p.text2, style = FinType.caption)
        }
    }) {
        if (MarkzStore.stepsOpen) Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            d.inputs.filter { it.column == "steps" }.forEach { Field(d, s, it, Modifier.weight(1f)) }
        }
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { d.inputs.filter { it.column == "1" }.forEach { Field(d, s, it, Modifier.fillMaxWidth()) } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { d.inputs.filter { it.column == "2" }.forEach { Field(d, s, it, Modifier.fillMaxWidth()) } }
        }
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(d.word("formula_label") + " " + d.word("formula_real") + " • " + d.word("formula_mom"), color = p.text3, style = FinType.caption)
            Text(k.getValue("footer-cross-rate"), color = p.text2, style = FinType.numSmall, modifier = Modifier.testTag("markz:footer-cross-rate"))
        }
    }
}

/**
 * A parameter field: its label and unit, then the value. While it is being typed in it keeps the user's text and every valid number recomputes the
 * page (bindVariableInput); when it loses focus it shows the formatted value again.
 */
@Composable
private fun Field(d: MarkzDecl, s: MarkzInputs, f: MarkzInput, modifier: Modifier) {
    val p = LocalFinPalette.current
    var typing by remember(f.id) { mutableStateOf<String?>(null) }
    val text = typing ?: Markz.shown(s, d.defaults, f.id)
    Column(modifier.border(1.dp, p.hairline).background(p.surface).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f.label, color = p.text2, style = FinType.caption, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text(f.unit, color = p.text3, style = FinType.caption.copy(fontSize = 9.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            text, { v ->
                typing = v
                Markz.parseSmart(v)?.let { n -> MarkzStore.inputs = Markz.edit(MarkzStore.state(d), d.defaults, f.id, n) }
            },
            Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) typing = null }.testTag("markz:" + f.id),
            singleLine = true, textStyle = FinType.num.copy(color = p.text, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(p.accent),
        )
    }
}

/** The matrix: the three header rows (target, inflation, real), then a row per FX scenario; a tap on a cell shows it in the inspector. */
@Composable
private fun MatrixTable(d: MarkzDecl, m: MarkzMatrix) {
    val p = LocalFinPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("markz:matrix")) {
        val wide = maxWidth >= 480.dp
        val axisW = if (wide) 64.dp else 48.dp
        val cellStyle = (if (wide) FinType.num else FinType.numSmall).copy(textAlign = TextAlign.End)
        val headStyle = FinType.numSmall.copy(textAlign = TextAlign.End)
        Column {
            @Composable
            fun head(label: String, sub: String, values: List<String>, color: Color, tagPrefix: String) {
                Row(Modifier.fillMaxWidth().height(20.dp).background(p.head2), verticalAlignment = Alignment.CenterVertically) {
                    // the owner's header cells: Target Return | Target, Inflation, USD | EUR / Real Return | EUR/USD (the short word on a phone)
                    Text(if (sub.isEmpty()) label else if (wide) "$label  $sub" else sub, Modifier.width(axisW * (if (wide) 2 else 1)).padding(start = 4.dp), color = p.text2, style = FinType.caption.copy(fontSize = 9.sp), maxLines = 1, overflow = TextOverflow.Clip)
                    values.forEachIndexed { i, v ->
                        Text(v, Modifier.weight(1f).padding(end = 3.dp).testTag("markz:$tagPrefix-$i"), color = if (i == 3) p.text else color,
                            style = headStyle.copy(fontWeight = if (i == 3) FontWeight.Bold else FontWeight.Normal), maxLines = 1)
                    }
                }
            }
            head(d.word("th_target"), d.word("th_target_short"), m.cols.map { Markz.fixed(it.grossPct, 2) + "%" }, p.amber, "th-gross")
            head(d.word("th_infl"), "", m.cols.map { Markz.fixed(it.inflPct, 2) + "%" }, p.text3, "th-infl")
            head((if (wide) d.word("th_usd") + " | " else "") + d.word("th_eur_real"), d.word("th_eurusd"), m.cols.map { Markz.fixed(it.realPct, 2) + "%" }, p.up, "th-real")
            m.rows.forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth().height(if (wide) 28.dp else 30.dp), verticalAlignment = Alignment.CenterVertically) {
                    val axis = if (row.isCenter) p.text else p.text2
                    if (wide) {
                        Text(Markz.fixed(row.usdBrl, 2), Modifier.width(axisW).padding(start = 4.dp), color = axis, style = FinType.num.copy(fontWeight = if (row.isCenter) FontWeight.Bold else FontWeight.Normal))
                        Text(Markz.fixed(row.eurBrl, 2), Modifier.width(axisW).padding(end = 4.dp), color = axis, style = FinType.num.copy(textAlign = TextAlign.End, fontWeight = if (row.isCenter) FontWeight.Bold else FontWeight.Normal))
                    } else {
                        Column(Modifier.width(axisW).padding(start = 4.dp)) {
                            Text(Markz.fixed(row.eurBrl, 2), color = axis, style = FinType.numSmall.copy(fontWeight = if (row.isCenter) FontWeight.Bold else FontWeight.Normal), maxLines = 1)
                            Text(Markz.fixed(row.usdBrl, 2), color = p.text3, style = FinType.numSmall.copy(fontSize = 8.sp, lineHeight = 9.sp), maxLines = 1)
                        }
                    }
                    row.cells.forEachIndexed { ci, c -> MatrixCell(d, m, c, wide, cellStyle, Modifier.weight(1f)) { MarkzStore.picked = ri to ci } }
                }
            }
        }
    }
}

@Composable
private fun MatrixCell(d: MarkzDecl, m: MarkzMatrix, c: MarkzCell, wide: Boolean, style: TextStyle, modifier: Modifier, onPick: () -> Unit) {
    val p = LocalFinPalette.current
    val b = Markz.heat(d, c.display, m.baseline, c.isCenter)
    val fill = when {
        c.isCenter -> p.surfaceRaised
        b == 0 -> p.surface
        else -> lerp(p.surface, if (b > 0) p.up else p.down, 0.22f * kotlin.math.abs(b))
    }
    val text = if (wide) Markz.money(c.display) else Markz.upTo(Math.round(c.display).toDouble(), 0)
    Box(
        modifier.height(if (wide) 28.dp else 30.dp).background(fill).then(if (c.isCenter) Modifier.border(1.dp, Color.White) else Modifier).clickable(onClick = onPick).padding(end = 3.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, color = if (c.isCenter || kotlin.math.abs(b) == 3) Color.White else p.text, style = style.copy(fontWeight = if (c.isCenter || b == 3) FontWeight.Bold else FontWeight.Normal), maxLines = 1, softWrap = false)
    }
}

// ------------------------------------------------------------------ Markovitz

@Composable
private fun MarkzMarkovitz(d: MarkzDecl, s: MarkzInputs, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    FinPanel("MKVZ", d.word("mk_title"), meta = d.word("mk_badge", "v" to Markz.fixed(s.centerYieldPct, 2))) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker(d.word("mk_kicker"))
            Body(d.word("mk_intro"))
        }
    }
    FinPanel("", d.word("mk_steps_title")) {
        d.methodology.forEach { st ->
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.step.uppercase(java.util.Locale.ROOT), color = p.amber, style = FinType.label)
                    Spacer(Modifier.width(8.dp))
                    Text(st.symbol, color = p.up, style = FinType.numSmall)
                }
                Text(st.title, color = p.text, style = FinType.title)
                Body(st.text)
            }
        }
    }
    FinPanel("", d.word("mk_curve_title"), meta = d.word("mk_curve_sub")) {
        FrontierDiagram(d, s.centerYieldPct)
        Text(d.word("mk_curve_note"), Modifier.padding(8.dp), color = p.text3, style = FinType.caption)
    }
    FinPanel("", d.word("mk_alloc_title"), meta = d.word("mk_alloc_sub")) {
        d.portfolios.forEach { pf ->
            val active = Markz.isActive(pf, s)
            val tone = when (pf.tone) { "amber" -> p.amber; "sky" -> p.blue; else -> p.up }
            Row(Modifier.fillMaxWidth().padding(6.dp).border(1.dp, if (active) p.up else p.hairline).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pf.name, color = p.text, style = FinType.label)
                        Spacer(Modifier.width(6.dp))
                        Text(pf.tag, Modifier.border(1.dp, tone).padding(horizontal = 4.dp), color = tone, style = FinType.caption.copy(fontSize = 9.sp))
                    }
                    Text(pf.weights, color = p.text2, style = FinType.numSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(d.word("mk_er", "v" to Markz.fixed(pf.expReturn, 2)), color = p.up, style = FinType.numSmall)
                        Text(d.word("mk_vol", "v" to Markz.fixed(pf.vol, 2)), color = p.amber, style = FinType.numSmall)
                        Text(d.word("mk_sharpe", "v" to Markz.sharpe(pf, d.rf)), color = p.blue, style = FinType.numSmall)
                    }
                }
                MkButton(d.word(if (active) "mk_active" else "mk_use"), primary = !active) {
                    MarkzStore.inputs = s.copy(centerYieldPct = pf.expReturn)
                    toast(ctx, d.word("toast_loaded", "name" to pf.name, "v" to Markz.fixed(pf.expReturn, 2)))
                    go("sensy")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.word("mk_rf", "v" to Markz.fixed(d.rf, 2)), Modifier.weight(1f), color = p.text3, style = FinType.caption)
            MkButton(d.word("mk_reset", "v" to Markz.fixed(d.defaults.centerYieldPct, 2))) { MarkzStore.reset(d); toast(ctx, d.word("toast_reset")); go("sensy") }
        }
    }
}

/** The owner's efficient-frontier diagram (its SVG, data/markz.json frontier.diagram) with the active target's dot. */
@Composable
private fun FrontierDiagram(d: MarkzDecl, target: Double) {
    val g = d.diagram
    val measurer = rememberTextMeasurer()
    val p = LocalFinPalette.current
    Canvas(Modifier.fillMaxWidth().aspectRatio((g.width / g.height).toFloat()).background(p.canvas).testTag("markz:frontier")) {
        val k = (size.width / g.width).toFloat()
        fun o(x: Double, y: Double) = Offset((x * k).toFloat(), (y * k).toFloat())
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f * k, 4f * k))
        fun label(t: String, x: Double, y: Double, color: Color, middle: Boolean, sizeSvg: Double = 9.0, bold: Boolean = false) {
            val st = TextStyle(color = color, fontSize = (sizeSvg * k / density).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            val m = measurer.measure(t, st, maxLines = 1, softWrap = false)
            drawText(m, topLeft = Offset((x * k).toFloat() - (if (middle) m.size.width / 2f else 0f), (y * k).toFloat() - m.size.height * 0.8f))
        }
        g.lines.forEach { l -> drawLine(hex(l.color), o(l.x1, l.y1), o(l.x2, l.y2), strokeWidth = 1.2f * k, pathEffect = if (l.dash) dash else null) }
        g.curves.forEach { c ->
            val path = Path().apply { moveTo((c.from.first * k).toFloat(), (c.from.second * k).toFloat()); quadraticTo((c.ctrl.first * k).toFloat(), (c.ctrl.second * k).toFloat(), (c.to.first * k).toFloat(), (c.to.second * k).toFloat()) }
            drawPath(path, hex(c.color), style = Stroke(width = (c.width * k).toFloat(), pathEffect = if (c.dash) PathEffect.dashPathEffect(floatArrayOf(3f * k, 3f * k)) else null))
        }
        g.texts.forEach { t ->
            if (t.rotate != 0.0) rotate(t.rotate.toFloat(), o(t.x, t.y)) { label(t.text, t.x, t.y, hex(t.color), t.middle, 10.0) }
            else label(t.text, t.x, t.y, hex(t.color), t.middle, if (t.middle) 10.0 else 9.0)
        }
        g.points.forEach { pt ->
            drawCircle(hex(pt.color), (pt.r * k).toFloat(), o(pt.x, pt.y))
            label(pt.label, pt.lx, pt.ly, hex(pt.labelColor), false, 9.0, bold = pt.labelColor != "#94a3b8")
        }
        val (cx, cy, lx) = Markz.targetDot(g.target, target)
        drawCircle(Color(0xFF10B981), 6f * k, o(cx, cy))
        drawCircle(Color.White, 6f * k, o(cx, cy), style = Stroke(width = 2f * k))
        label(g.target.label.replace("{v}", Markz.fixed(target, 2)), lx, cy - 6, Color(0xFF6EE7B7), false, 9.5, bold = true)
    }
}

// ------------------------------------------------------------------ the substeps (Volatility, Beta, CAPM Calculator, Correlation, Optimization)

@Composable
private fun MarkzSubstepPage(d: MarkzDecl, s: MarkzInputs, id: String, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    val st = d.substep(id) ?: return
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MarkzStore.loadStats(ctx.applicationContext, d) } }
    FinPanel(id.take(4).uppercase(java.util.Locale.ROOT), st.title) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker(st.kicker)
            Body(st.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MkButton(st.prev.second) { go(st.prev.first) }
                Text("|", color = p.text3, style = FinType.caption)
                MkButton(st.next.second, primary = true) { go(st.next.first) }
            }
        }
    }
    if (MarkzStore.volModel.isEmpty()) MarkzStore.volModel = d.stats.volDefault
    when (val l = MarkzStore.load) {
        MarkzLoad.Idle, MarkzLoad.Loading -> Text(d.word("st_loading"), Modifier.padding(8.dp), color = p.text3, style = FinType.caption)
        is MarkzLoad.Failed -> Column(Modifier.padding(8.dp)) {
            Text(d.word("st_failed", "v" to l.missing.joinToString(", ")), color = p.down, style = FinType.caption)
            MkButton("↻") { MarkzStore.load = MarkzLoad.Idle; scope.launch(Dispatchers.IO) { MarkzStore.loadStats(ctx.applicationContext, d) } }
        }
        is MarkzLoad.Done -> {
            val r = l.r
            val names = d.stats.assets.map { it.short } + d.stats.benchmark.short
            Text(d.word("st_source", "src" to (d.stats.assets.map { it.symbol } + d.stats.benchmark.symbol).joinToString(", "), "n" to r.days.toString(), "d" to r.lastDay),
                Modifier.padding(horizontal = 8.dp), color = p.text3, style = FinType.caption)
            when (id) {
                "volatility" -> VolatilityBody(d, r, names)
                "beta" -> BetaBody(d, r, names)
                "capm" -> CapmBody(d, r, names)
                "correlation" -> CorrelationBody(d, r, names)
                "optimization" -> OptimizationBody(d, s, r, go)
            }
        }
    }
}

/** A small table: a header row and the rows, the first column the name. */
@Composable
private fun Grid(columns: List<String>, rows: List<List<String>>, tag: String) {
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().testTag("markz:$tag")) {
        Row(Modifier.fillMaxWidth().background(p.head2).padding(horizontal = 6.dp, vertical = 3.dp)) {
            columns.forEachIndexed { i, c -> Text(c, Modifier.weight(if (i == 0) 1.4f else 1f), color = p.text2, style = FinType.caption, textAlign = if (i == 0) TextAlign.Start else TextAlign.End, maxLines = 2) }
        }
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                r.forEachIndexed { i, c -> Text(c, Modifier.weight(if (i == 0) 1.4f else 1f), color = if (i == 0) p.text else p.text, style = if (i == 0) FinType.body else FinType.num, textAlign = if (i == 0) TextAlign.Start else TextAlign.End, maxLines = 1) }
            }
        }
    }
}

private fun pct(v: Double?, n: Int = 2): String = if (v == null || !v.isFinite()) "n/a" else Markz.fixed(v, n)

/** The selected framework's sigma of series [i], falling back to the historical SD where the framework has none (implied without an index). */
private fun sigma(r: MarkzStatsResult, i: Int): Double = r.vols[i].of(MarkzStore.volModel) ?: r.vols[i].hist

@Composable
private fun ModelTabs(d: MarkzDecl) {
    val p = LocalFinPalette.current
    Column(Modifier.padding(horizontal = 8.dp)) {
        Text(d.word("st_pick"), color = p.text2, style = FinType.caption)
        Row(Modifier.horizontalScroll(rememberScrollState())) { FinTabs("markz_vol", d.stats.volModels, MarkzStore.volModel) { MarkzStore.volModel = it } }
    }
}

@Composable
private fun VolatilityBody(d: MarkzDecl, r: MarkzStatsResult, names: List<String>) {
    ModelTabs(d)
    FinPanel("", d.word("st_pick")) {
        Grid(MarkzScreen.volColumns(d), r.vols.mapIndexed { i, v -> MarkzScreen.volRow(names[i], v) }, "volatility")
        Text(d.word("st_g_title"), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = LocalFinPalette.current.text2, style = FinType.caption)
        Grid(MarkzScreen.garchColumns(d, names), MarkzScreen.garchRows(d, r.vols, d.stats.annualDays), "garch")
        val p = LocalFinPalette.current
        (d.stats.assets + d.stats.benchmark).forEach { a ->
            Text(a.short + ": " + a.symbol + " · " + a.name + (if (a.implied.isNotEmpty()) " · " + d.word("st_implied") + ": " + a.implied + " (" + a.impliedName + ")" else ""),
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = p.text3, style = FinType.caption)
        }
    }
}

private fun betas(r: MarkzStatsResult): List<Double> {
    val m = r.ids.size - 1
    return (0 until m).map { MarkzStats.beta(r.corr[it][m], sigma(r, it), sigma(r, m)) }
}

@Composable
private fun BetaBody(d: MarkzDecl, r: MarkzStatsResult, names: List<String>) {
    ModelTabs(d)
    val m = r.ids.size - 1
    val b = betas(r)
    FinPanel("", d.word("st_beta")) {
        Grid(listOf(d.word("st_asset"), d.word("st_vol"), d.word("st_cov"), d.word("st_var_m"), d.word("st_beta")),
            (0 until m).map { i -> listOf(names[i], pct(sigma(r, i)), pct(r.corr[i][m] * sigma(r, i) * sigma(r, m), 1), pct(sigma(r, m) * sigma(r, m), 1), pct(b[i], 3)) }, "beta")
    }
}

@Composable
private fun RateField(label: String, value: Double, tag: String, onValue: (Double) -> Unit) {
    val p = LocalFinPalette.current
    var typing by remember(tag) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).border(1.dp, p.hairline).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = p.text2, style = FinType.body)
        BasicTextField(typing ?: Markz.fixed(value, 2), { v -> typing = v; Markz.parseSmart(v)?.let(onValue) },
            Modifier.width(96.dp).onFocusChanged { if (!it.isFocused) typing = null }.testTag("markz:$tag"), singleLine = true,
            textStyle = FinType.num.copy(color = p.text, textAlign = TextAlign.End), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(p.accent))
    }
}

private fun capmMu(d: MarkzDecl, r: MarkzStatsResult): List<Double> {
    val rf = MarkzStore.rf ?: d.rf
    val erm = MarkzStore.erm ?: r.benchmarkAnnual
    return betas(r).map { MarkzStats.capm(rf, it, erm) }
}

@Composable
private fun CapmBody(d: MarkzDecl, r: MarkzStatsResult, names: List<String>) {
    val p = LocalFinPalette.current
    ModelTabs(d)
    RateField(d.word("st_rf"), MarkzStore.rf ?: d.rf, "capm-rf") { MarkzStore.rf = it }
    RateField(d.word("st_erm"), MarkzStore.erm ?: r.benchmarkAnnual, "capm-erm") { MarkzStore.erm = it }
    Text(d.word("st_erm_note"), Modifier.padding(horizontal = 8.dp), color = p.text3, style = FinType.caption)
    val b = betas(r); val mu = capmMu(d, r)
    FinPanel("", d.word("st_eri")) {
        Grid(listOf(d.word("st_asset"), d.word("st_beta"), d.word("st_eri")), b.indices.map { listOf(names[it], pct(b[it], 3), pct(mu[it])) }, "capm")
    }
}

@Composable
private fun CorrelationBody(d: MarkzDecl, r: MarkzStatsResult, names: List<String>) {
    ModelTabs(d)
    FinPanel("", d.word("st_corr")) {
        Grid(listOf("") + names, r.corr.mapIndexed { i, row -> listOf(names[i]) + row.map { pct(it) } }, "correlation")
    }
    val sig = r.ids.indices.map { sigma(r, it) }
    val cov = MarkzStats.covMatrix(r.corr, sig)
    FinPanel("", d.word("st_covm")) {
        Grid(listOf("") + names, cov.mapIndexed { i, row -> listOf(names[i]) + row.map { pct(it, 1) } }, "covariance")
    }
}

@Composable
private fun OptimizationBody(d: MarkzDecl, s: MarkzInputs, r: MarkzStatsResult, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalFinPalette.current
    ModelTabs(d)
    val n = d.stats.assets.size
    val mu = capmMu(d, r)
    val cov = MarkzStats.covMatrix(r.corr.take(n).map { it.take(n) }, (0 until n).map { sigma(r, it) })
    val rf = MarkzStore.rf ?: d.rf
    val opt: MarkzOpt = remember(mu, cov, rf, s.centerYieldPct) { MarkzStats.optimise(mu, cov, rf, d.stats.gridStep, d.stats.frontierPoints, s.centerYieldPct) }
    val short = d.stats.assets.map { it.short }
    fun weights(pt: MarkzPort) = pt.w.mapIndexed { i, w -> Markz.fixed(w * 100, 0) + "% " + short[i] }.joinToString(" • ")
    @Composable
    fun card(title: String, pt: MarkzPort, tag: String) {
        Row(Modifier.fillMaxWidth().padding(6.dp).border(1.dp, p.hairline).padding(8.dp).testTag("markz:$tag"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = p.text, style = FinType.label)
                Text(weights(pt), color = p.text2, style = FinType.numSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(d.word("mk_er", "v" to pct(pt.er)), color = p.up, style = FinType.numSmall)
                    Text(d.word("mk_vol", "v" to pct(pt.vol)), color = p.amber, style = FinType.numSmall)
                    Text(d.word("mk_sharpe", "v" to pct(pt.sharpe)), color = p.blue, style = FinType.numSmall)
                }
            }
            MkButton(d.word("mk_use"), primary = true) {
                MarkzStore.inputs = s.copy(centerYieldPct = pt.er)
                toast(ctx, d.word("toast_loaded", "name" to title, "v" to Markz.fixed(pt.er, 2)))
                go("sensy")
            }
        }
    }
    FinPanel("OPTM", d.word("st_frontier"), meta = "Rf " + pct(rf) + "%") {
        card(d.word("st_mvp"), opt.mvp, "opt-mvp")
        card(d.word("st_ms"), opt.maxSharpe, "opt-max-sharpe")
        val match = opt.match
        if (match != null) card(d.word("st_match"), match, "opt-match")
        else Text(d.word("st_unreachable", "v" to Markz.fixed(s.centerYieldPct, 2), "lo" to pct(opt.lo), "hi" to pct(opt.hi)), Modifier.padding(8.dp), color = p.amber, style = FinType.caption)
        Grid(listOf(d.word("st_weights"), d.word("st_er"), d.word("st_volp"), d.word("st_sharpe")), opt.frontier.map { listOf(weights(it), pct(it.er), pct(it.vol), pct(it.sharpe)) }, "frontier")
    }
}
