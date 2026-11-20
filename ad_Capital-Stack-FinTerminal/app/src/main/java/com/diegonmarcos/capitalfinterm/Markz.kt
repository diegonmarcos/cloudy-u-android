package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** #1832 one tab of the owner's page (data/markz.json `tabs`): its id, label and the MARKZ sub-page that draws it. */
data class MarkzTab(val id: String, val label: String, val page: String, val default: Boolean)

/** #1832 one parameter field of the Sensy Matrix (`inputs`), by the owner's element id; [column] 1, 2 or steps. */
data class MarkzInput(val id: String, val label: String, val unit: String, val column: String)

/** #1832 a frontier portfolio card of the Markovitz page. */
data class MarkzPortfolio(val name: String, val tag: String, val tone: String, val expReturn: Double, val vol: Double, val weights: String)

/** #1832 one of the five methodology steps of the Markovitz page. */
data class MarkzStep(val step: String, val symbol: String, val title: String, val text: String)

/** #1832 a Markovitz substep page: its words and the tabs its back / next links open. */
data class MarkzSubstep(val id: String, val kicker: String, val title: String, val text: String, val prev: Pair<String, String>, val next: Pair<String, String>)

/** #1832 an element of the efficient-frontier diagram, in the owner's SVG units (420 x 220). */
data class MarkzLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val color: String, val dash: Boolean)
data class MarkzCurve(val from: Pair<Double, Double>, val ctrl: Pair<Double, Double>, val to: Pair<Double, Double>, val color: String, val dash: Boolean, val width: Double)
data class MarkzPoint(val x: Double, val y: Double, val r: Double, val color: String, val label: String, val lx: Double, val ly: Double, val labelColor: String)
data class MarkzText(val x: Double, val y: Double, val text: String, val color: String, val middle: Boolean, val rotate: Double)
data class MarkzTarget(val from: Double, val span: Double, val x0: Double, val dx: Double, val y0: Double, val dy: Double, val flipX: Double, val label: String)
data class MarkzDiagram(val width: Double, val height: Double, val lines: List<MarkzLine>, val curves: List<MarkzCurve>, val points: List<MarkzPoint>, val texts: List<MarkzText>, val target: MarkzTarget)

/** #1832 an instrument of the substeps (`stats.assets`, `stats.benchmark`): its quote-source symbol and, for an implied volatility, the index's symbol. */
data class MarkzAsset(val id: String, val label: String, val short: String, val symbol: String, val name: String, val implied: String, val impliedName: String)

data class MarkzStatsDecl(
    val assets: List<MarkzAsset>, val benchmark: MarkzAsset, val windowDays: Int, val annualDays: Int, val lambda: Double, val alpha: Double, val beta: Double,
    val gridStep: Double, val frontierPoints: Int, val volModels: List<Pair<String, String>>, val volDefault: String,
)

/** #1832 the whole declaration of MARKZ (data/markz.json). */
data class MarkzDecl(
    val title: String, val brand: List<String>, val tabs: List<MarkzTab>, val defaults: MarkzInputs, val inputs: List<MarkzInput>, val words: Map<String, String>,
    val heatBase: Double, val heatEdges: List<Double>, val rf: Double, val portfolios: List<MarkzPortfolio>, val diagram: MarkzDiagram,
    val methodology: List<MarkzStep>, val substeps: List<MarkzSubstep>, val stats: MarkzStatsDecl,
) {
    fun word(k: String): String = words[k].orEmpty()
    /** The word [k] with each {name} slot filled. */
    fun word(k: String, vararg slots: Pair<String, String>): String = slots.fold(word(k)) { s, (n, v) -> s.replace("{$n}", v) }
    fun tab(id: String): MarkzTab? = tabs.firstOrNull { it.id == id }
    fun substep(id: String): MarkzSubstep? = substeps.firstOrNull { it.id == id }
}

/** #1832 the model's variables (the owner's `state`): every percentage in percent, the AuM anchored to the currency last edited. */
data class MarkzInputs(
    val aumUsd: Double, val aumEur: Double, val aumBrl: Double, val aumAnchor: String,
    val cgTaxPct: Double, val spreadPct: Double, val inflationPct: Double, val centerYieldPct: Double,
    val centerUsdBrl: Double, val centerEurUsd: Double, val yieldStepBps: Double, val fxStepPct: Double, val roundStepEur: Double,
)

/** #1832 the monthly cash flow of the centre: real and net return (fractions), BRL / USD / EUR a month and the euro figure rounded to the sheet's step. */
data class MarkzFlow(val real: Double, val net: Double, val brlMom: Double, val usdMom: Double, val eurMom: Double, val sheetEurMom: Double)

/** #1832 a return column of the matrix: gross, inflation, real (percent). */
data class MarkzCol(val idx: Int, val grossPct: Double, val inflPct: Double, val realPct: Double, val isCenter: Boolean)

/** #1832 one cell of the matrix: its scenario and its euro a month (exact, rounded to the sheet's step, and the one shown). */
data class MarkzCell(
    val usdBrl: Double, val eurBrl: Double, val grossPct: Double, val realPct: Double, val cgTax: Double, val spread: Double,
    val exactEur: Double, val roundedEur: Double, val display: Double, val isCenter: Boolean,
)

data class MarkzRow(val usdBrl: Double, val eurBrl: Double, val isCenter: Boolean, val cells: List<MarkzCell>)

/** #1832 the 7 x 7 matrix (FX rows -3 .. +3 steps, return columns -3 .. +3 steps) and the KPIs read from it. */
data class MarkzMatrix(
    val cols: List<MarkzCol>, val rows: List<MarkzRow>, val centerExact: Double, val centerRounded: Double, val baseline: Double,
    val min: Double, val max: Double, val centerRealPct: Double, val center: MarkzCell,
)

/**
 * #1832 the arithmetic of the owner's page, one function per behaviour of its script (Portfolio_Returns_Sensitivity_Matrix.html): the AuM kept in three
 * currencies, the return fields solved backwards to the gross target, the 7 x 7 sensitivity matrix, its heatmap, the inspector, the CSV, the Home and
 * Markovitz figures. Pure: the JVM suite covers it (MarkzTest).
 */
object Markz {
    const val ASSET = "markz.json"
    const val ANCHOR_USD = "USD"
    const val ANCHOR_EUR = "EUR"
    const val ANCHOR_BRL = "BRL"

    /** The owner's field ids (data/markz.json `inputs`). */
    const val AUM_USD = "input-aum-usd"
    const val AUM_EUR = "input-aum-eur"
    const val AUM_BRL = "input-aum-brl"
    const val RETURN_NET = "input-return-net"
    const val RETURN_USD_MOM = "input-return-usd-mom"
    const val RETURN_EUR_MOM = "input-return-eur-mom"
    const val RETURN_BRL_MOM = "input-return-brl-mom"
    const val GROSS = "input-center-yield"
    const val INFLATION = "input-inflation"
    const val TAX = "input-cg-tax"
    const val EUR_USD = "input-center-eur-usd"
    const val USD_BRL = "input-center-usd"
    const val EUR_BRL = "input-center-eur-brl"
    const val SPREAD = "input-spread"
    const val YIELD_STEP = "input-yield-step"
    const val FX_STEP = "input-fx-step"
    const val ROUND_STEP = "input-round-step"
    val FIELDS: List<String> = listOf(AUM_USD, AUM_EUR, AUM_BRL, RETURN_NET, RETURN_USD_MOM, RETURN_EUR_MOM, RETURN_BRL_MOM, GROSS, INFLATION, TAX, EUR_USD, USD_BRL, EUR_BRL, SPREAD, YIELD_STEP, FX_STEP, ROUND_STEP)

    private const val FALLBACK_ROUND = 250.0

    // ------------------------------------------------------------------ the declaration

    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun pair(a: JSONArray?): Pair<Double, Double> = (a?.optDouble(0) ?: 0.0) to (a?.optDouble(1) ?: 0.0)
    private fun asset(o: JSONObject): MarkzAsset = MarkzAsset(o.optString("id"), o.optString("label"), o.optString("short"), o.optString("symbol"), o.optString("name"), o.optString("implied"), o.optString("implied_name"))

    fun parse(json: String): MarkzDecl? = runCatching {
        val o = JSONObject(json)
        val df = o.getJSONObject("defaults")
        val usdBrl = df.getDouble("centerUsdBrl")
        val eurUsd = df.getDouble("centerEurBrl") / usdBrl
        val aumUsd = df.getDouble("aumUsd")
        val defaults = MarkzInputs(
            aumUsd = aumUsd, aumEur = aumUsd * usdBrl / (usdBrl * eurUsd), aumBrl = df.getDouble("aumBrl"), aumAnchor = df.optString("aumAnchor", ANCHOR_USD),
            cgTaxPct = df.getDouble("cgTaxPct"), spreadPct = df.getDouble("spreadPct"), inflationPct = df.getDouble("inflationPct"), centerYieldPct = df.getDouble("centerYieldPct"),
            centerUsdBrl = usdBrl, centerEurUsd = eurUsd, yieldStepBps = df.getDouble("yieldStepBps"), fxStepPct = df.getDouble("fxStepPct"), roundStepEur = df.getDouble("roundStepEur"),
        )
        val w = o.getJSONObject("words")
        val heat = o.getJSONObject("heat")
        val fr = o.getJSONObject("frontier")
        val dg = fr.getJSONObject("diagram")
        val tg = dg.getJSONObject("target")
        val st = o.getJSONObject("stats")
        MarkzDecl(
            title = o.optString("title"), brand = strs(o.optJSONArray("brand")),
            tabs = objs(o.optJSONArray("tabs")).map { MarkzTab(it.getString("id"), it.getString("label"), it.getString("page"), it.optBoolean("default", false)) },
            defaults = defaults,
            inputs = objs(o.optJSONArray("inputs")).map { MarkzInput(it.getString("id"), it.getString("label"), it.optString("unit"), it.opt("column")?.toString().orEmpty()) },
            words = w.keys().asSequence().associateWith { w.optString(it) },
            heatBase = heat.getDouble("scale_base"), heatEdges = heat.getJSONArray("edges").let { a -> (0 until a.length()).map { a.getDouble(it) } },
            rf = fr.getDouble("rf"),
            portfolios = objs(fr.optJSONArray("portfolios")).map { MarkzPortfolio(it.getString("name"), it.optString("tag"), it.optString("tone"), it.getDouble("expReturn"), it.getDouble("vol"), it.optString("weights")) },
            diagram = MarkzDiagram(
                dg.getDouble("width"), dg.getDouble("height"),
                objs(dg.optJSONArray("lines")).map { MarkzLine(it.getDouble("x1"), it.getDouble("y1"), it.getDouble("x2"), it.getDouble("y2"), it.optString("color"), it.optBoolean("dash", false)) },
                objs(dg.optJSONArray("curves")).map { MarkzCurve(pair(it.optJSONArray("from")), pair(it.optJSONArray("ctrl")), pair(it.optJSONArray("to")), it.optString("color"), it.optBoolean("dash", false), it.optDouble("width", 2.0)) },
                objs(dg.optJSONArray("points")).map { MarkzPoint(it.getDouble("x"), it.getDouble("y"), it.getDouble("r"), it.optString("color"), it.optString("label"), it.getDouble("lx"), it.getDouble("ly"), it.optString("lcolor", "#94a3b8")) },
                objs(dg.optJSONArray("texts")).map { MarkzText(it.getDouble("x"), it.getDouble("y"), it.optString("text"), it.optString("color", "#94a3b8"), it.optString("anchor") == "middle", it.optDouble("rotate", 0.0)) },
                MarkzTarget(tg.getDouble("from"), tg.getDouble("span"), tg.getDouble("x0"), tg.getDouble("dx"), tg.getDouble("y0"), tg.getDouble("dy"), tg.getDouble("label_flip_x"), tg.optString("label")),
            ),
            methodology = objs(o.optJSONArray("methodology")).map { MarkzStep(it.optString("step"), it.optString("symbol"), it.optString("title"), it.optString("text")) },
            substeps = objs(o.optJSONArray("substeps")).map {
                val p = strs(it.optJSONArray("prev")); val n = strs(it.optJSONArray("next"))
                MarkzSubstep(it.getString("id"), it.optString("kicker"), it.optString("title"), it.optString("text"), p.getOrElse(0) { "" } to p.getOrElse(1) { "" }, n.getOrElse(0) { "" } to n.getOrElse(1) { "" })
            },
            stats = MarkzStatsDecl(
                objs(st.optJSONArray("assets")).map(::asset), asset(st.getJSONObject("benchmark")), st.optInt("window_days", 252), st.optInt("annual_days", 252),
                st.optDouble("ewma_lambda", 0.94), st.optDouble("garch_alpha", 0.05), st.optDouble("garch_beta", 0.90), st.optDouble("grid_step", 0.01), st.optInt("frontier_points", 12),
                st.optJSONArray("vol_models")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { it.optString(0) to it.optString(1) } } ?: emptyList(),
                st.optString("vol_default", "hist"),
            ),
        )
    }.getOrNull()

    // ------------------------------------------------------------------ formatting (the page's en-US figures)

    private val US = DecimalFormatSymbols(Locale.US)

    /** formatMoney: two decimals, grouped; a non-finite value is 0.00. */
    fun money(v: Double): String = if (!v.isFinite()) "0.00" else String.format(Locale.US, "%,.2f", v)

    /** toLocaleString with at most [digits] decimals and no trailing zeros (593,000 / 593,000.5). */
    fun upTo(v: Double, digits: Int): String = DecimalFormat("#,##0" + (if (digits > 0) "." + "#".repeat(digits) else ""), US).apply { roundingMode = java.math.RoundingMode.HALF_UP }.format(v)

    /** toFixed(n): rounded on the double's exact value, half up (5.525 is 5.52499... in binary: "5.52", as the page shows it; Java's %.2f says 5.53). */
    fun fixed(v: Double, n: Int): String = if (!v.isFinite()) v.toString() else java.math.BigDecimal(v).setScale(n, java.math.RoundingMode.HALF_UP).toPlainString()

    /** String(n): an integral value without a decimal point. */
    fun plain(v: Double): String = if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    /**
     * parseSmartNumber: a typed amount in any common form ("100k", "1.5m", "100,000.00", "14,98", "R$ 593.000"); null when it is not a non-negative number.
     * The separator that comes last is the decimal mark; a lone comma is decimal unless it groups thousands.
     */
    fun parseSmart(raw: String?): Double? {
        var s = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (s.isEmpty()) return null
        var mult = 1.0
        if (s.endsWith("k")) { mult = 1e3; s = s.dropLast(1).trim() } else if (s.endsWith("m")) { mult = 1e6; s = s.dropLast(1).trim() }
        s = s.replace(Regex("[r\$€\\s]"), "")
        if (s.isEmpty()) return null
        val comma = s.contains(','); val dot = s.contains('.')
        if (comma && dot) {
            s = if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replaceFirst(',', '.') else s.replace(",", "")
        } else if (comma) {
            val parts = s.split(',')
            s = if (parts.size == 2 && parts[1].length != 3) s.replaceFirst(',', '.') else if (parts.size == 2 && parts[0] == "0") s.replaceFirst(',', '.') else s.replace(",", "")
        } else if (dot) {
            if (s.split('.').size > 2) s = s.replace(".", "")
        }
        if (!Regex("^[0-9]*\\.?[0-9]*(e[+-]?[0-9]+)?$").matches(s) || s == ".") return null
        val v = s.toDoubleOrNull() ?: return null
        if (!v.isFinite() || v < 0) return null
        return v * mult
    }

    /** Excel MROUND: [v] to the nearest multiple of [step] (half away from zero upwards, as Math.round); unchanged when the step is not positive. */
    fun mround(v: Double, step: Double): Double = if (!v.isFinite() || step <= 0.0) v else Math.round(v / step) * step

    // ------------------------------------------------------------------ the model

    fun usdBrl(s: MarkzInputs, d: MarkzInputs): Double = if (s.centerUsdBrl > 0) s.centerUsdBrl else d.centerUsdBrl
    fun eurUsd(s: MarkzInputs, d: MarkzInputs): Double = if (s.centerEurUsd > 0) s.centerEurUsd else d.centerEurUsd
    /** getCenterEurBrl: USD/BRL x EUR/USD. */
    fun eurBrl(s: MarkzInputs, d: MarkzInputs): Double = usdBrl(s, d) * eurUsd(s, d)
    private fun roundStep(s: MarkzInputs): Double = if (s.roundStepEur > 0) s.roundStepEur else FALLBACK_ROUND

    /** Fisher: (1 + gross) / (1 + inflation) - 1, as a fraction. */
    fun real(grossPct: Double, inflPct: Double): Double = (1 + grossPct / 100) / (1 + inflPct / 100) - 1

    fun flow(s: MarkzInputs, d: MarkzInputs): MarkzFlow {
        val real = real(s.centerYieldPct, s.inflationPct)
        val net = real * (1 - s.cgTaxPct / 100)
        val brl = s.aumBrl * net / 12
        val usd = brl / usdBrl(s, d) * (1 - s.spreadPct / 100)
        val eur = brl / eurBrl(s, d) * (1 - s.spreadPct / 100)
        return MarkzFlow(real, net, brl, usd, eur, mround(eur, roundStep(s)))
    }

    /** syncAumFields: the other two AuM figures from the anchor currency at the centre rates. */
    fun syncAum(s: MarkzInputs, d: MarkzInputs): MarkzInputs {
        val ub = usdBrl(s, d); val eb = eurBrl(s, d)
        return when (s.aumAnchor) {
            ANCHOR_USD -> (s.aumUsd * ub).let { brl -> s.copy(aumBrl = brl, aumEur = brl / eb) }
            ANCHOR_EUR -> (s.aumEur * eb).let { brl -> s.copy(aumBrl = brl, aumUsd = brl / ub) }
            else -> s.copy(aumUsd = s.aumBrl / ub, aumEur = s.aumBrl / eb)
        }
    }

    /** solveGrossFromNetYield: the gross target whose real return after tax is [targetNet] (a fraction); never below 0. */
    fun solveGross(s: MarkzInputs, targetNet: Double): MarkzInputs {
        val tax = min(0.9999, s.cgTaxPct / 100)
        val gross = (1 + targetNet / (1 - tax)) * (1 + s.inflationPct / 100) - 1
        return s.copy(centerYieldPct = max(0.0, gross * 100))
    }

    /** The field [id] typed as [v] (already parsed, >= 0): the owner's input handler of that field; a value it rejects leaves the state as it was. */
    fun edit(s: MarkzInputs, d: MarkzInputs, id: String, v: Double): MarkzInputs = when (id) {
        AUM_USD -> syncAum(s.copy(aumAnchor = ANCHOR_USD, aumUsd = v), d)
        AUM_EUR -> syncAum(s.copy(aumAnchor = ANCHOR_EUR, aumEur = v), d)
        AUM_BRL -> syncAum(s.copy(aumAnchor = ANCHOR_BRL, aumBrl = v), d)
        RETURN_NET -> solveGross(s, v / 100)
        RETURN_USD_MOM -> if (s.aumBrl > 0) solveGross(s, v / (1 - min(0.9999, s.spreadPct / 100)) * usdBrl(s, d) * 12 / s.aumBrl) else s
        RETURN_EUR_MOM -> if (s.aumBrl > 0) solveGross(s, v / (1 - min(0.9999, s.spreadPct / 100)) * eurBrl(s, d) * 12 / s.aumBrl) else s
        RETURN_BRL_MOM -> if (s.aumBrl > 0) solveGross(s, v * 12 / s.aumBrl) else s
        GROSS -> s.copy(centerYieldPct = v)
        INFLATION -> s.copy(inflationPct = v)
        TAX -> s.copy(cgTaxPct = v)
        EUR_USD -> if (v > 0) syncAum(s.copy(centerEurUsd = v), d) else s
        USD_BRL -> if (v > 0) syncAum(s.copy(centerUsdBrl = v), d) else s
        EUR_BRL -> if (v > 0 && s.centerUsdBrl > 0) syncAum(s.copy(centerEurUsd = v / s.centerUsdBrl), d) else s
        SPREAD -> s.copy(spreadPct = v)
        YIELD_STEP -> s.copy(yieldStepBps = v)
        FX_STEP -> s.copy(fxStepPct = v)
        ROUND_STEP -> if (v > 0) s.copy(roundStepEur = v) else s
        else -> s
    }

    /** What the field [id] shows when it is not being typed in (the owner's sync and blur formats). */
    fun shown(s: MarkzInputs, d: MarkzInputs, id: String): String {
        val f = flow(s, d)
        return when (id) {
            AUM_USD -> money(s.aumUsd)
            AUM_EUR -> money(s.aumEur)
            AUM_BRL -> upTo(s.aumBrl, 2)
            RETURN_NET -> fixed(f.net * 100, 2)
            RETURN_USD_MOM -> money(f.usdMom)
            RETURN_EUR_MOM -> money(f.eurMom)
            RETURN_BRL_MOM -> money(f.brlMom)
            GROSS -> fixed(s.centerYieldPct, 2)
            INFLATION -> fixed(s.inflationPct, 2)
            TAX -> fixed(s.cgTaxPct, 1)
            EUR_USD -> fixed(eurUsd(s, d), 4)
            USD_BRL -> fixed(s.centerUsdBrl, 2)
            EUR_BRL -> fixed(eurBrl(s, d), 2)
            SPREAD -> fixed(s.spreadPct, 1)
            YIELD_STEP -> plain(s.yieldStepBps)
            FX_STEP -> plain(s.fxStepPct)
            ROUND_STEP -> plain(roundStep(s))
            else -> ""
        }
    }

    /** renderMatrix: 7 return columns and 7 FX rows of -3 .. +3 steps around the centre, each cell the euro a month of its scenario. */
    fun matrix(s: MarkzInputs, d: MarkzInputs, rounded: Boolean): MarkzMatrix {
        val tax = s.cgTaxPct / 100; val spread = s.spreadPct / 100
        val cross = eurUsd(s, d); val centerEb = usdBrl(s, d) * cross
        val yStep = s.yieldStepBps / 10000; val fStep = s.fxStepPct / 100; val step = roundStep(s)
        val cols = (-3..3).map { c ->
            val gross = s.centerYieldPct / 100 + c * yStep
            MarkzCol(c + 3, gross * 100, s.inflationPct, real(gross * 100, s.inflationPct) * 100, c == 0)
        }
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        val rows = (-3..3).map { r ->
            val eb = max(0.01, centerEb * (1 + r * fStep)); val ub = eb / cross
            val cells = cols.map { col ->
                val exact = s.aumBrl * (col.realPct / 100) * (1 - tax) / 12 / eb * (1 - spread)
                val rnd = mround(exact, step)
                val shown = if (rounded) rnd else exact
                lo = min(lo, shown); hi = max(hi, shown)
                MarkzCell(ub, eb, col.grossPct, col.realPct, tax, spread, exact, rnd, shown, r == 0 && col.isCenter)
            }
            MarkzRow(ub, eb, r == 0, cells)
        }
        val center = rows[3].cells[3]
        val cExact = s.aumBrl * (cols[3].realPct / 100) * (1 - tax) / 12 / centerEb * (1 - spread)
        val cRound = mround(cExact, step)
        return MarkzMatrix(cols, rows, cExact, cRound, if (rounded) cRound else cExact, lo, hi, cols[3].realPct, center)
    }

    /** getHeatmapStyle as a bucket: -3 (deep red) .. +3 (deep green) of the cell against the baseline; the centre cell is 0 (drawn bold with a ring). */
    fun heat(decl: MarkzDecl, value: Double, baseline: Double, isCenter: Boolean): Int {
        if (isCenter) return 0
        val scale = if (baseline > 0) baseline / decl.heatBase else 1.0
        val diff = (value - baseline) / (if (scale == 0.0) 1.0 else scale)
        val e = decl.heatEdges
        return when {
            diff <= e[0] -> -3
            diff <= e[1] -> -2
            diff <= e[2] -> -1
            diff < e[3] -> 0
            diff < e[4] -> 1
            diff < e[5] -> 2
            else -> 3
        }
    }

    /** updateInspector: the scenario line and the arithmetic line of a cell. */
    fun inspector(decl: MarkzDecl, c: MarkzCell): Pair<String, String> {
        val head = decl.word("inspector_head", "label" to decl.word(if (c.isCenter) "inspector_center" else "inspector_scenario"),
            "eb" to fixed(c.eurBrl, 2), "ub" to fixed(c.usdBrl, 2), "g" to fixed(c.grossPct, 2))
        val math = decl.word("inspector_math", "r" to fixed(c.realPct, 2), "t" to fixed(c.cgTax * 100, 0), "pt" to fixed(c.realPct * (1 - c.cgTax), 2),
            "s" to fixed(c.spread * 100, 1), "e" to money(c.exactEur), "sh" to money(c.roundedEur))
        return head to math
    }

    /** copyMatrixToClipboard: the header (USD/BRL, EUR/BRL, each column's target and real return) and one quoted line per FX row. */
    fun csv(decl: MarkzDecl, m: MarkzMatrix): String {
        val head = listOf("USD/BRL", "EUR/BRL") + m.cols.map { decl.word("csv_head", "g" to fixed(it.grossPct, 2) + "%", "r" to fixed(it.realPct, 2) + "%") }
        val lines = m.rows.map { r -> (listOf(fixed(r.usdBrl, 2), fixed(r.eurBrl, 2)) + r.cells.map { money(it.display) }).joinToString(",") { "\"$it\"" } }
        return (listOf(head.joinToString(",")) + lines).joinToString("\n")
    }

    /** The Grid Stress Range KPI: min - max of the cells shown (no decimals on the sheet's step), and its ±FX / ±bps line. */
    fun range(decl: MarkzDecl, s: MarkzInputs, m: MarkzMatrix, rounded: Boolean): Pair<String, String> {
        val digits = if (rounded) 0 else 2
        return ("€" + upTo(m.min, digits) + "–" + upTo(m.max, digits)) to
            decl.word("kpi_range_sub", "fx" to fixed(s.fxStepPct / 100 * 3 * 100, 0), "bp" to Math.round(s.yieldStepBps / 10000 * 3 * 10000).toString())
    }

    /** The header pill: AuM in thousand euros and million reais. */
    fun pill(decl: MarkzDecl, s: MarkzInputs): String = decl.word("sensy_pill", "k" to fixed(s.aumEur / 1000, 1), "m" to fixed(s.aumBrl / 1_000_000, 2))

    /** The Steps badge: return step, FX step, sheet step. */
    fun stepsBadge(decl: MarkzDecl, s: MarkzInputs): String = decl.word("steps_badge", "bp" to plain(s.yieldStepBps), "fx" to plain(s.fxStepPct), "s" to plain(roundStep(s)))

    /** The footer's implied cross rate. */
    fun crossLine(decl: MarkzDecl, s: MarkzInputs, d: MarkzInputs): String =
        decl.word("footer_cross", "eb" to fixed(eurBrl(s, d), 4), "ub" to fixed(usdBrl(s, d), 2), "eu" to fixed(eurUsd(s, d), 4))

    /** The Sheet step label of the rounding toggle. */
    fun roundLabel(decl: MarkzDecl, s: MarkzInputs): String = decl.word("round_step", "s" to plain(roundStep(s)))

    /** syncSubpages: the Home page's cards, waterfall and FX tiles, by the owner's element id. */
    fun home(decl: MarkzDecl, s: MarkzInputs, d: MarkzInputs): Map<String, String> {
        val f = flow(s, d)
        return linkedMapOf(
            "nav-live-badge" to decl.word("nav_badge", "v" to money(f.eurMom)),
            "home-aum-usd" to "$" + money(s.aumUsd),
            "home-aum-eur" to "€" + money(s.aumEur),
            "home-aum-brl" to "R$ " + money(s.aumBrl),
            "home-mom-usd" to decl.word("home_mom_usd", "v" to money(f.usdMom)),
            "home-mom-eur" to decl.word("home_mom_eur", "v" to money(f.eurMom), "s" to Math.round(f.sheetEurMom).toString()),
            "home-mom-brl" to decl.word("home_mom_brl", "v" to money(f.brlMom)),
            "home-wf-gross" to fixed(s.centerYieldPct, 2) + "%",
            "home-wf-infl" to decl.word("wf_infl_v", "i" to fixed(s.inflationPct, 2), "r" to fixed(f.real * 100, 2)),
            "home-wf-tax" to decl.word("wf_tax_v", "t" to fixed(s.cgTaxPct, 1), "n" to fixed(f.net * 100, 2)),
            "home-wf-annual-eur" to decl.word("wf_annual_v", "v" to money(f.eurMom * 12)),
            "home-fx-eurusd" to fixed(eurUsd(s, d), 4),
            "home-fx-usdbrl" to fixed(usdBrl(s, d), 2),
            "home-fx-eurbrl" to fixed(eurBrl(s, d), 2),
        )
    }

    /** The Sensy Matrix's KPI strip and header figures, by the owner's element id. */
    fun kpis(decl: MarkzDecl, s: MarkzInputs, d: MarkzInputs, m: MarkzMatrix, rounded: Boolean): Map<String, String> {
        val (range, rangeSub) = range(decl, s, m, rounded)
        val tax = s.cgTaxPct / 100
        return linkedMapOf(
            "header-nav-pill" to pill(decl, s),
            "kpi-nav-eur" to "€" + money(s.aumEur),
            "kpi-nav-brl" to "R$ " + upTo(s.aumBrl, 0),
            "kpi-nav-usd" to decl.word("kpi_aum_usd", "v" to money(s.aumUsd)),
            "kpi-baseline" to "€" + money(m.baseline),
            "kpi-baseline-sub" to if (rounded) decl.word("kpi_exact", "v" to money(m.centerExact)) else decl.word("kpi_sheet", "v" to money(m.centerRounded)),
            "kpi-real-yield" to fixed(m.centerRealPct, 2) + "%",
            "kpi-net-real-yield" to decl.word("kpi_net", "v" to fixed(m.centerRealPct * (1 - tax), 2)),
            "kpi-yield-sub" to decl.word("kpi_real_sub", "g" to fixed(s.centerYieldPct, 2), "i" to fixed(s.inflationPct, 2)),
            "kpi-range" to range,
            "kpi-range-sub" to rangeSub,
            "round-step-label" to roundLabel(decl, s),
            "steps-summary-badge" to stepsBadge(decl, s),
            "footer-cross-rate" to crossLine(decl, s, d),
        )
    }

    /** The Markovitz page's target dot on the frontier (SVG units) and where its label sits. */
    fun targetDot(t: MarkzTarget, targetPct: Double): Triple<Double, Double, Double> {
        val k = ((targetPct - t.from) / t.span).coerceIn(0.0, 1.0)
        val cx = t.x0 + k * t.dx
        val cy = t.y0 - k * t.dy
        return Triple(cx, cy, if (cx > t.flipX) cx - 82 else cx + 9)
    }

    /** A frontier portfolio's Sharpe ratio against [rf] (two decimals) and whether it is the matrix's active target (within 0.05 points). */
    fun sharpe(p: MarkzPortfolio, rf: Double): String = fixed((p.expReturn - rf) / p.vol, 2)
    fun isActive(p: MarkzPortfolio, s: MarkzInputs): Boolean = Math.abs(s.centerYieldPct - p.expReturn) < 0.05
}
