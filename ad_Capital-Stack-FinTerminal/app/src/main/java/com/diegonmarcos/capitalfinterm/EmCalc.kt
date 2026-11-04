package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/**
 * PM > D1 "EM Bonds CALCULATOR" and D0 "EM Carry Trade" (#1447). One engine: the monthly EUR return on the NAV of a bond paying a gross
 * yield `g` against an inflation (or funding yield) `i`, converted at an FX level, after the capital-gains tax and the exchange spread:
 *   real = (1 + g) / (1 + i) - 1;  net = NAV * real / 12 * (FX centre / FX) * (1 - CG) * (1 - spread).
 * All rates here are fractions (0.1498), the tables show percents. Live inputs (derive em_calc / em_carry): the Brazil and funding
 * 10Y yields and the Germany CPI from the OECD monthly series on DBnomics (the #1397 route), BRL per USD and per EUR from the ECB rates.
 */
object EmCalc {
    const val CALC = "em_calc"
    const val CARRY = "em_carry"

    data class Inputs(
        val navEur: Double = 200000.0, val cg: Double = 0.17, val spread: Double = 0.02, val exchStep: Double = 0.05, val yieldStep: Double = 0.0075,
        val centerDiff: Double = 0.0, val round: Double = 250.0, val n: Int = 3, val ma: Boolean = false,
    )
    /** What the feeds said: yields and inflation as fractions, FX as BRL per USD / per EUR; [fails] the sources that did not answer. */
    data class Live(val yHigh: Double, val yFund: Double?, val infl: Double, val brlUsd: Double, val brlEur: Double, val basis: String, val fails: List<String>)
    class Grid(val yields: List<Double>, val fxUsd: List<Double>, val fxEur: List<Double>, val cells: List<List<Double>>)

    fun real(g: Double, infl: Double): Double = (1 + g) / (1 + infl) - 1
    /** The tax and the spread bite a gain only; a loss is shown as it is. */
    fun netOf(gross: Double, cg: Double, spread: Double): Double = if (gross > 0) gross * (1 - cg) * (1 - spread) else gross
    fun monthlyNet(navEur: Double, g: Double, infl: Double, fxCentre: Double, fx: Double, cg: Double, spread: Double): Double =
        netOf(navEur * real(g, infl) / 12.0 * (fxCentre / fx), cg, spread)
    /** Display rounding to the nearest [step] (half up); a step of 0 or less shows the exact value. */
    fun roundTo(v: Double, step: Double): Double = if (step <= 0) v else Math.floor(v / step + 0.5) * step
    /** `2n + 1` levels centred on [c], [step] apart (relative: c * (1 + k * step); else c + k * step). */
    fun axis(c: Double, step: Double, n: Int, relative: Boolean): List<Double> = (-n..n).map { k -> if (relative) c * (1 + k * step) else c + k * step }

    fun grid(live: Live, i: Inputs): Grid {
        val ys = axis(live.yHigh + i.centerDiff, i.yieldStep, i.n, false)
        val usd = axis(live.brlUsd, i.exchStep, i.n, true)
        val eur = axis(live.brlEur, i.exchStep, i.n, true)
        return Grid(ys, usd, eur, eur.map { fx -> ys.map { g -> roundTo(monthlyNet(i.navEur, g, live.infl, live.brlEur, fx, i.cg, i.spread), i.round) } })
    }
    /** Cell tints -1 (red) .. +1 (green): the distance from the centre cell over the largest distance in the grid. */
    fun heat(cells: List<List<Double>>): List<List<Float?>> {
        val n = cells.size / 2
        val c = cells[n][cells[n].size / 2]
        val span = cells.flatten().maxOf { Math.abs(it - c) }
        return cells.map { r -> r.map { v -> if (span == 0.0) 0f else ((v - c) / span).toFloat() } }
    }

    fun pct(v: Double, d: Int = 2) = String.format(Locale.US, "%.${d}f%%", v * 100)
    fun money(v: Double) = String.format(Locale.US, "%,.2f", v)
    fun fx(v: Double) = String.format(Locale.US, "%.2f", v)

    /**
     * The matrix as the owner's image: header row = Gross Yield and the yields ascending left to right (the centre in the middle column); then Inflation, then
     * the USD level, "EUR / (-Infl) Yield" and the real yields; then one row per FX level, ascending top to bottom (BRL per USD, BRL per EUR, the cells).
     * Higher yield (right) = more, a higher BRL level (down) = less EUR, so the green 2,000 sits top right and the red 1,000 bottom left.
     */
    fun matrix(panel: Panel, live: Live, i: Inputs, by: String, asOf: String): PanelData {
        val g = grid(live, i)
        val h = heat(g.cells)
        val c = i.n
        val head = listOf("", "Gross Yield") + g.yields.map { pct(it) }
        val rows = listOf(listOf("", "Inflation") + g.yields.map { pct(live.infl) }, listOf("USD", "EUR / (-Infl) Yield") + g.yields.map { pct(real(it, live.infl)) }) +
            g.cells.mapIndexed { r, row -> listOf(fx(g.fxUsd[r]), fx(g.fxEur[r])) + row.map { money(it) } }
        val none = List(head.size) { null as Float? }
        val note = "Monthly EUR net return on a ${money(i.navEur)} NAV after ${pct(i.cg, 0)} capital-gains tax and ${pct(i.spread, 0)} exchange spread" +
            (if (i.round > 0) ", rounded to ${i.round.toInt()}" else "") + ". Real yield = (1 + gross) / (1 + inflation) - 1; rows (down) move the BRL level up by ${pct(i.exchStep, 1)}, columns (right) the gross yield up by ${pct(i.yieldStep)}." +
            (if (live.basis.isEmpty()) "" else " ${live.basis}.") + (if (live.fails.isEmpty()) "" else " — " + live.fails.joinToString(" · "))
        return PanelData(head, rows, emptyList(), emptyList(), asOf, note, servedBy = by,
            heat = listOf(none, none) + h.map { r -> listOf<Float?>(null, null) + r }, bold = listOf(-1 to c + 2, 0 to c + 2, 1 to c + 2, c + 2 to 0, c + 2 to 1, c + 2 to c + 2))
    }

    /** The inputs panel: NAV in usd, eur, brl; the taxes; the curves and steps. */
    fun inputs(panel: Panel, live: Live, i: Inputs, by: String, asOf: String): PanelData {
        val usdPerEur = live.brlEur / live.brlUsd
        val rows = listOf(
            listOf("NAV (Gross) usd", money(i.navEur * usdPerEur)), listOf("NAV (Gross) eur", money(i.navEur)), listOf("NAV (Gross) brl", money(i.navEur * live.brlEur)),
            listOf("Tax CG", pct(i.cg, 0)), listOf("Tax Exch Spread", pct(i.spread, 0)),
            listOf("Gross Yield", "Brazil Gov Bonds ${pct(live.yHigh)}"), listOf("Inflation", "Germany ${pct(live.infl)}"),
            listOf("Center", if (i.ma) "MA 7 days" else "Now"), listOf("Center Diff", pct(i.centerDiff)),
            listOf("Exch step", pct(i.exchStep, 1)), listOf("Yield step", pct(i.yieldStep) + " (${Math.round(i.yieldStep * 10000)} bps)"),
            listOf("BRL per USD", fx(live.brlUsd)), listOf("BRL per EUR", fx(live.brlEur)),
        )
        return PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, if (live.fails.isEmpty()) "" else live.fails.joinToString(" · "), servedBy = by)
    }

    /** The carry table: funding vs the high-yielder, the FX-move breakeven and the net return after the same taxes and spread (one engine). */
    fun carry(panel: Panel, live: Live, i: Inputs, by: String, asOf: String): PanelData {
        val yf = live.yFund ?: throw IOException("no funding-currency yield")
        val carry = live.yHigh - yf
        val be = 1 - (1 + yf) / (1 + live.yHigh) // the high-yielder's 12M fall vs the funding currency that wipes the carry out
        val flat = monthlyNet(i.navEur, live.yHigh, yf, 1.0, 1.0, i.cg, i.spread)
        /** The 12M carry (1 + real) compounded with a BRL move [m] vs EUR, as a monthly figure like the calculator's; m = -breakeven gives 0. */
        fun scen(m: Double) = netOf(i.navEur * ((1 + real(live.yHigh, yf)) * (1 + m) - 1) / 12.0, i.cg, i.spread)
        val rows = listOf(
            listOf("Funding yield (EUR, Germany 10Y)", pct(yf)), listOf("High-yielder yield (BRL, Brazil 10Y)", pct(live.yHigh)), listOf("Carry (high-yielder - funding)", pct(carry)),
            listOf("Breakeven BRL fall vs EUR, 12M", pct(be)),
            listOf("Net carry EUR per month (after tax and spread)", money(flat)), listOf("Net carry % a year on NAV", pct(flat * 12 / i.navEur)),
            listOf("Net EUR per month if BRL -10% in 12M", money(scen(-0.10))), listOf("Net EUR per month if BRL +10% in 12M", money(scen(0.10))),
            listOf("BRL per EUR now", fx(live.brlEur)),
        )
        val note = "Funding EUR against BRL on a ${money(i.navEur)} NAV; the same ${pct(i.cg, 0)} capital-gains tax and ${pct(i.spread, 0)} exchange spread as the calculator (EmCalc.monthlyNet). " +
            "Carry = the two 10Y yields, a stand-in for the short rates. A BRL fall larger than the breakeven turns the carry into a loss." +
            (if (live.basis.isEmpty()) "" else " ${live.basis}.") + (if (live.fails.isEmpty()) "" else " — " + live.fails.joinToString(" · "))
        return PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, note, servedBy = by)
    }

    fun inputsOf(f: JSONObject): Inputs {
        val o = f.optJSONObject("inputs") ?: JSONObject()
        val d = Inputs()
        return Inputs(o.optDouble("nav_eur", d.navEur), o.optDouble("cg", d.cg), o.optDouble("spread", d.spread), o.optDouble("exch_step", d.exchStep), o.optDouble("yield_step", d.yieldStep),
            o.optDouble("center_diff", d.centerDiff), o.optDouble("round", d.round), o.optInt("n", d.n), o.optString("center") == "ma7")
    }

    /** Series -> the latest value (the centre "Now"), or the [win]-day average ending at its last point (the centre "MA 7 days"). */
    fun pick(s: List<Pair<String, Double>>, ma: Boolean, win: Long): Pair<Double, String>? {
        val last = s.maxByOrNull { it.first } ?: return null
        if (!ma || last.first.length != 10) return last.second to last.first
        return (FxPpp.windowAvg(s.sortedBy { it.first }, LocalDate.parse(last.first), win) ?: last.second) to "MA $win d to ${last.first}"
    }

    /** The live inputs through the #1397 feeds: OECD monthly 10Y and CPI on DBnomics, ECB rates (Frankfurter). */
    fun fetchLive(f: JSONObject, i: Inputs, http: Http, today: LocalDate, c: ModelCtx): Triple<Live, String, String> {
        val fails = ArrayList<String>(); val used = ArrayList<String>()
        fun src(k: String) = Catalog.byId(c.sources, Catalog.str(f, k))
        val o = f.optJSONObject("oecd") ?: JSONObject()
        val hi = Catalog.str(f, "high_a3"); val fu = Catalog.str(f, "fund_a3"); val ic = Catalog.str(f, "infl_a3")
        val y = HashMap<String, MutableList<Pair<String, Double>>>(); var cpi: Pair<Double, String>? = null
        src("oecd_source")?.let { s ->
            runCatching {
                val ids = listOf(hi + Catalog.str(o, "suffix"), fu + Catalog.str(o, "suffix"), ic + Catalog.str(o, "cpi_suffix")).distinct().joinToString(",") { Catalog.str(o, "prefix") + it }
                for (sd in Providers.dbnomics(http.get(Requests.url(s.endpoint, Catalog.str(o, "path"), listOf("series_ids" to ids, "observations" to "1", "limit" to "100"))))) {
                    val pts = sd.periods.zip(sd.values).mapNotNull { (p, v) -> v?.let { p to it } }
                    if (sd.code.endsWith(Catalog.str(o, "cpi_suffix"))) {
                        if (sd.code.startsWith(ic)) cpi = G20Bonds.cpiYoY(pts)
                    } else {
                        y.getOrPut(sd.code.substringBefore('.')) { ArrayList() }.addAll(pts)
                    }
                }
                used += s.name
            }.onFailure { fails += "${s.name}: ${it.message}" }
        }
        val fxm = HashMap<String, MutableList<Pair<String, Double>>>()
        src("fx_source")?.let { s ->
            runCatching {
                val rates = JSONObject(http.get(Requests.url(s.endpoint, "/v1/${today.minusDays(30)}..$today", listOf("base" to "USD", "symbols" to "BRL,EUR")))).optJSONObject("rates") ?: JSONObject()
                rates.keys().asSequence().sorted().forEach { d -> val m = rates.getJSONObject(d); m.keys().forEach { k -> fxm.getOrPut(k) { ArrayList() } += d to m.getDouble(k) } }
                used += s.name
            }.onFailure { fails += "${s.name}: ${it.message}" }
        }
        // Frankfurter unreachable (a 5xx from some networks): the ECB reference rates on DBnomics, key-free (BRL and USD per EUR -> BRL and EUR per USD)
        val e = f.optJSONObject("ecb_fx")
        if (e != null && (fxm["BRL"].isNullOrEmpty() || fxm["EUR"].isNullOrEmpty())) src("oecd_source")?.let { s ->
            runCatching {
                val pre = Catalog.str(e, "prefix"); val bc = Catalog.str(e, "brl"); val uc = Catalog.str(e, "usd")
                val by = Providers.dbnomics(http.get(Requests.url(s.endpoint, Catalog.str(e, "path"), listOf("series_ids" to "$pre$bc,$pre$uc", "observations" to "1", "metadata" to "0")))).associate { it.code to it.periods.zip(it.values) }
                val u = by[uc].orEmpty().mapNotNull { (p, v) -> v?.let { p to it } }.toMap()
                val from = today.minusDays(30).toString()
                val bl = by[bc].orEmpty().mapNotNull { (p, v) -> if (v != null && p >= from && u.containsKey(p)) Triple(p, v, u.getValue(p)) else null }
                fxm["BRL"] = bl.map { (p, b, d) -> p to b / d }.toMutableList()
                fxm["EUR"] = bl.map { (p, _, d) -> p to 1 / d }.toMutableList()
                used += s.name; fails += "Frankfurter unavailable: BRL and EUR from the ECB reference rates on DBnomics"
            }.onFailure { fails += "${s.name} ECB: ${it.message}" }
        }
        val yh = pick(y[hi].orEmpty(), i.ma, 7); val yf = pick(y[fu].orEmpty(), i.ma, 7)
        val brl = pick(fxm["BRL"].orEmpty(), i.ma, 7); val eur = pick(fxm["EUR"].orEmpty(), i.ma, 7)
        val missing = listOfNotNull(if (yh == null) "no Brazil 10Y yield" else null, if (cpi == null) "no Germany CPI a year apart" else null, if (brl == null || eur == null) "no BRL / EUR rate" else null)
        if (missing.isNotEmpty()) throw IOException((missing + fails).joinToString(" · "))
        val basis = "Yield ${yh!!.second}, CPI ${cpi!!.second}, FX ${brl!!.second}"
        return Triple(Live(yh.first / 100, yf?.first?.div(100), cpi!!.first / 100, brl.first, brl.first / eur!!.first, basis, fails), used.distinct().joinToString(" + "), (fxm["BRL"].orEmpty().maxOfOrNull { it.first } ?: ""))
    }

    fun panel(panel: Panel, f: JSONObject, http: Http, today: LocalDate, c: ModelCtx): PanelData {
        val i = inputsOf(f)
        val (live, by, asOf) = fetchLive(f, i, http, today, c)
        return when {
            panel.derive == CARRY -> carry(panel, live, i, by, asOf)
            Catalog.str(f, "part") == "inputs" -> inputs(panel, live, i, by, asOf)
            else -> matrix(panel, live, i, by, asOf)
        }
    }
}
