package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/**
 * The per-item tables (data/dashboards.json `derive`): `performance` (one row per symbol, columns from [Performance.row]),
 * `portfolio_performance` (a model portfolio's legs through declared proxies, daily rebalanced, then [Performance.row]) and
 * `yields_matrix` (country x tenor, spot or forward via [Forward], GDP-weighted World / DM / EM averages).
 *
 * Every item is fetched on its own: the container's /v1 route first (fetch.primary.api with {each}), then the panel's own source,
 * then each declared fallback. A route that declares `symbol_map` serves only the items it maps (its symbol for that item); one
 * without serves every item under the item's own name. A missing value is "—" and its reason goes to the panel note.
 */
object Tables {
    const val PERFORMANCE = "performance"
    const val PORTFOLIO = "portfolio_performance"
    const val MATRIX = "yields_matrix"
    const val EM_USD = "em_usd"
    const val WORLD_BONDS = "world_bonds"
    const val RISK = "risk_compare"
    const val FX_PPP = "fx_ppp"
    val DERIVES: Set<String> = setOf(PERFORMANCE, PORTFOLIO, MATRIX, EM_USD, WORLD_BONDS, RISK, FX_PPP)

    /** Extra (non-return) columns a performance table may declare after the return columns. */
    const val COL_VOL = "Vol 1Y"
    const val COL_MDD = "MaxDD 1Y"

    /** One item's answer: what came back, the source name that served it (empty: none) and why not, when nothing did. */
    data class ItemGot(val got: Got, val by: String, val why: String)

    /** Nothing answered for any item: an error state (with the first reason), never a table of dashes. */
    private fun <T : Map<String, ItemGot>> some(m: T): T {
        if (m.values.all { it.got.isEmpty }) throw IOException(m.values.firstOrNull()?.why ?: "no data")
        return m
    }

    fun load(panel: Panel, source: Source, f: JSONObject, http: Http, today: LocalDate, c: ModelCtx): PanelData = when (panel.derive) {
        PERFORMANCE -> performance(panel, f, some(Engine.eachOf(f, today, c.user).associateWith { gather(it, source, f, http, today, c) }))
        PORTFOLIO -> {
            val proxies = f.optJSONObject("proxies") ?: JSONObject()
            val legs = c.dash?.legs.orEmpty()
            portfolio(panel, f, legs, some(legs.associate { (name, _) -> name to Catalog.str(proxies, name).let { sym -> if (sym.isEmpty()) ItemGot(Got(), "", "no proxy declared") else gather(sym, source, f, http, today, c) } }))
        }
        MATRIX -> matrixOne(f, http, c)?.let { m -> matrix(panel, f, m.got, m) }
            ?: matrix(panel, f, some(strings(f.optJSONArray("each")).associateWith { gather(it, source, f, http, today, c) }))
        EM_USD -> emUsd(panel, f, http, c)
        RISK -> {
            val proxies = f.optJSONObject("proxies") ?: JSONObject()
            val sets = legSets(f)
            riskCompare(panel, f, sets, some(sets.flatten().map { it.first }.distinct().associateWith { name ->
                Catalog.str(proxies, name).let { sym -> if (sym.isEmpty()) ItemGot(Got(), "", "no proxy declared") else gather(sym, source, f, http, today, c) }
            }))
        }
        FX_PPP -> fxPpp(panel, f, http, today, c)
        WORLD_BONDS -> spreads(load(panel.copy(derive = MATRIX), source, f, http, today, c), f)
        else -> throw IOException("unknown derive ${panel.derive}")
    }

    // ------------------------------------------------------------------ FX vs PPP (#1374)

    /** fetch.primary.api (/v1/fx/ppp) on the mesh; else the direct sources: ECB reference rates (fetch.fx_source) and World Bank PPP / CPI (fetch.wb). */
    fun fxPpp(panel: Panel, f: JSONObject, http: Http, today: LocalDate, c: ModelCtx): PanelData {
        val api = c.api
        if (api != null) {
            runCatching { JSONObject(http.get(api.base.trimEnd('/') + Catalog.str(f.optJSONObject("primary") ?: JSONObject(), "api"))).getJSONArray("rows") }
                .getOrNull()?.takeIf { it.length() > 0 }?.let { return fxPppTable(panel, it, api.source.name) }
        }
        val rows = f.getJSONArray("rows").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val win = f.optLong("window_days", 30)
        val fxSrc = Catalog.byId(c.sources, Catalog.str(f, "fx_source")) ?: throw IOException("no FX source declared")
        val wb = f.getJSONObject("wb")
        val wbSrc = Catalog.byId(c.sources, Catalog.str(wb, "source")) ?: throw IOException("no World Bank source declared")
        val ccys = rows.filter { it.optString("no_fx").isEmpty() }.map { it.optString("ccy") } // a currency the ECB does not quote is not asked
        val fx = HashMap<String, MutableList<Pair<String, Double>>>()
        val body = JSONObject(http.get(Requests.url(fxSrc.endpoint, "/v1/${today.minusDays(400)}..$today", listOf("base" to "USD", "symbols" to ccys.joinToString(",")))))
        val rates = body.optJSONObject("rates") ?: JSONObject()
        rates.keys().asSequence().sorted().forEach { d -> val m = rates.getJSONObject(d); m.keys().forEach { k -> fx.getOrPut(k) { ArrayList() } += d to m.getDouble(k) } }
        fun wbSeries(code: String): Map<String, List<Pair<String, Double>>> {
            val cc = (rows.map { it.optString("cc") } + "US").joinToString(";")
            val a = JSONArray(http.get(Requests.url(wbSrc.endpoint, "/country/$cc/indicator/$code", listOf("format" to "json", "per_page" to "2000", "date" to "2015:${today.year}"))))
            val out = HashMap<String, MutableList<Pair<String, Double>>>()
            val list = a.optJSONArray(1) ?: JSONArray()
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                if (o.isNull("value")) continue
                out.getOrPut(o.getJSONObject("country").getString("id")) { ArrayList() } += o.getString("date") to o.getDouble("value")
            }
            return out.mapValues { (_, v) -> v.sortedBy { it.first } }
        }
        val ind = wb.getJSONObject("indicators")
        val ppp = wbSeries(ind.getString("ppp"))
        val cpi = wbSeries(ind.getString("cpi"))
        val usCpi = cpi["US"].orEmpty()
        val out = JSONArray()
        rows.forEach { r -> out.put(FxPpp.row(r, fx[r.optString("ccy")].orEmpty(), ppp[r.optString("cc")].orEmpty(), cpi[r.optString("cc")].orEmpty(), usCpi, win)) }
        return fxPppTable(panel, out, listOf(fxSrc.name, wbSrc.name).joinToString(" + "))
    }

    /** The /v1/fx/ppp rows as the table: Currency | Spot | PPP | Over/under % | 30d avg | 30d 1y ago (real) | Real YoY %; each empty cell's reason in the note. */
    fun fxPppTable(panel: Panel, rows: JSONArray, by: String): PanelData {
        fun d(o: JSONObject, k: String, digits: Int) = if (o.isNull(k) || !o.has(k)) FinFormat.DASH else Engine.num(o.getDouble(k), digits)
        fun p(o: JSONObject, k: String) = if (o.isNull(k) || !o.has(k)) FinFormat.DASH else FinFormat.signed(o.getDouble(k), 1)
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }
        fun dg(o: JSONObject) = if (!o.isNull("spot") && o.has("spot") && o.getDouble("spot") >= 100) 1 else 4
        val table = list.map { o -> listOf(o.optString("currency"), d(o, "spot", dg(o)), d(o, "ppp", dg(o)), p(o, "over_under_pct"), d(o, "avg_window", dg(o)), d(o, "avg_window_year_ago_real", dg(o)), p(o, "real_change_pct")) }
        val why = list.flatMap { o -> val w = o.optJSONObject("null_reason") ?: JSONObject(); w.keys().asSequence().map { k -> "${o.optString("currency")} $k: ${w.getString(k)}" }.toList() }
        val note = ("Local currency per USD. Over/under % = PPP / spot - 1 (negative: undervalued); real YoY % = the year-ago window in today's prices / today's window - 1 (positive: gained in real terms)." +
            (if (why.isEmpty()) "" else " — " + why.joinToString(" · "))).trim()
        return PanelData(panel.columns, table, emptyList(), emptyList(), list.mapNotNull { o -> o.optString("spot_date").takeIf { it.isNotEmpty() && it != "null" } }.maxOrNull().orEmpty(), note,
            servedBy = by, details = list.map { it.optString("label") })
    }

    // ------------------------------------------------------------------ 60/40 vs 100/0 risk comparison (spec 9)

    /** fetch.compare: [[[leg, weight %], ...], ...], one leg set per compared portfolio. */
    fun legSets(f: JSONObject): List<List<Pair<String, Int>>> {
        val a = f.optJSONArray("compare") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { s -> (0 until s.length()).map { s.getJSONArray(it).let { l -> l.getString(0) to l.getInt(1) } } } }
    }

    /** Each compared portfolio's headline row (the [portfolio] line: return columns, Vol 1Y, MaxDD 1Y), on the same proxies and dates. */
    fun riskCompare(panel: Panel, f: JSONObject, sets: List<List<Pair<String, Int>>>, got: Map<String, ItemGot>): PanelData {
        val parts = sets.map { portfolio(panel, f, it, got) }
        return PanelData(panel.columns, parts.map { it.rows.first() }, emptyList(), emptyList(), parts.firstOrNull()?.asOf.orEmpty(),
            ("Same ETF proxies, each daily rebalanced; risk = Vol 1Y and MaxDD 1Y. " + parts.firstOrNull()?.note.orEmpty().substringAfter(" — ", "")).trim(),
            servedBy = parts.firstOrNull()?.servedBy.orEmpty(), details = parts.map { it.details.firstOrNull().orEmpty() })
    }

    // ------------------------------------------------------------------ World Bonds merged table (#1370, spec 9 spreads)

    val SPREAD_COLS = listOf("Country", "2Y", "10Y", "2s10s bp", "vs Bund bp", "vs UST bp")

    /** The yields matrix reshaped: 2Y, 10Y, and the 10Y spread over the 2Y, Germany's 10Y (Bund) and the US 10Y (UST), in bp. */
    fun spreads(m: PanelData, f: JSONObject): PanelData {
        val labels = f.optJSONObject("labels") ?: JSONObject()
        val i2 = m.columns.indexOf("2Y")
        val i10 = m.columns.indexOf("10Y")
        fun cell(r: List<String>, i: Int) = if (i < 0) FinFormat.DASH else r.getOrElse(i) { FinFormat.DASH }
        fun num(r: List<String>?, i: Int) = r?.let { FinFormat.parseNum(cell(it, i).takeIf { c -> c != FinFormat.DASH }) }
        fun row(cc: String) = m.rows.firstOrNull { it.firstOrNull() == Catalog.str(labels, cc).ifEmpty { cc } }
        val bund = num(row("DE"), i10)
        val ust = num(row("US"), i10)
        fun bp(a: Double?, b: Double?) = if (a == null || b == null) FinFormat.DASH else FinFormat.signed((a - b) * 100, 0)
        val rows = m.rows.map { r -> val y10 = num(r, i10); listOf(r.firstOrNull().orEmpty(), cell(r, i2), cell(r, i10), bp(y10, num(r, i2)), bp(y10, bund), bp(y10, ust)) }
        return m.copy(columns = SPREAD_COLS, rows = rows, note = (m.note + " Spreads in bp: the 10Y minus the 2Y, minus Germany's 10Y (Bund), minus the US 10Y (UST).").trim())
    }

    // ------------------------------------------------------------------ EM government bonds in USD

    /** fetch.primary.api (/v1/bonds/em-usd) through the mesh, one call; the mesh off or no answer is an error state. */
    fun emUsd(panel: Panel, f: JSONObject, http: Http, c: ModelCtx): PanelData {
        val api = c.api ?: throw IOException("mesh off: the EM USD bond table is served by the FinTerm container only")
        val path = Catalog.str(f.optJSONObject("primary") ?: JSONObject(), "api")
        return emUsdTable(panel, http.get(api.base.trimEnd('/') + path), api.source.name)
    }

    /**
     * {method, rows:[{country, currency, yield_last:{month, value}, usd_return_annualised:{"1y","10y","25y"} (fractions), null_reason}]} as
     * Country | Ccy | Yield | 1Y | 10Y | 25Y (the return columns are the panel's after the third); an empty cell's reason goes to the note.
     */
    fun emUsdTable(panel: Panel, body: String, by: String): PanelData {
        val o = JSONObject(body)
        val a = o.optJSONArray("rows") ?: JSONArray()
        val hs = panel.columns.drop(3)
        val why = ArrayList<String>()
        var asOf = ""
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { r ->
            val cc = Catalog.str(r, "country")
            val y = r.optJSONObject("yield_last")
            val ret = r.optJSONObject("usd_return_annualised") ?: JSONObject()
            y?.let { m -> Catalog.str(m, "month").let { if (it > asOf) asOf = it } }
            val cells = hs.map { h -> ret.opt(h.lowercase()).let { v -> if (v is Number && v.toDouble().isFinite()) String.format(Locale.US, "%+.2f%%", v.toDouble() * 100) else FinFormat.DASH } }
            val nr = Catalog.str(r, "null_reason")
            if (nr.isNotEmpty()) why += "$cc ($nr)"
            else if (cells.any { it == FinFormat.DASH }) why += "$cc (history shorter than ${hs.filterIndexed { i, _ -> cells[i] == FinFormat.DASH }.joinToString("/")})"
            listOf(cc, Catalog.str(r, "currency").ifEmpty { FinFormat.DASH },
                y?.optDouble("value")?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) } ?: FinFormat.DASH) + cells
        }
        if (rows.isEmpty()) throw IOException("${by}: no EM bond row")
        val note = listOfNotNull(Catalog.str(o, "method").ifEmpty { null }, if (why.isEmpty()) null else "— " + why.joinToString(", ")).joinToString(" ")
        return PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, note, servedBy = by)
    }

    // ------------------------------------------------------------------ fetch

    /** The symbol a route uses for [item]: its `symbol_map` entry, the item itself when the route maps nothing, null when it maps others only. */
    fun symbolFor(route: JSONObject, item: String): String? {
        val m = route.optJSONObject("symbol_map") ?: return item
        return Catalog.str(m, item).ifEmpty { null }
    }

    fun gather(item: String, source: Source, f: JSONObject, http: Http, today: LocalDate, c: ModelCtx): ItemGot {
        val why = ArrayList<String>()
        val p = f.optJSONObject("primary")
        if (p != null && Catalog.str(p, "api").isNotEmpty()) {
            val sym = symbolFor(p, item)
            if (c.api == null) why += "mesh off" else if (sym != null) {
                try {
                    val sl = Engine.slots(f, today, listOf(sym)) + ("each" to sym)
                    val g = Feeds.parse(Catalog.str(p, "parse").ifEmpty { "finterm_v1" }, http.get(Requests.url(c.api.base, Catalog.str(p, "api"), filled(p, sl), sl)), Feeds.Opt(f, sym))
                    if (!g.isEmpty) return ItemGot(g, c.api.source.name, "")
                    why += "${c.api.source.name}: empty"
                } catch (e: IOException) {
                    why += "${c.api.source.name}: no answer"
                }
            }
        }
        val routes = ArrayList<Pair<Source, JSONObject>>()
        routes += source to f
        val fbs = f.optJSONArray("fallbacks")
        if (fbs != null) for (i in 0 until fbs.length()) {
            val fb = fbs.optJSONObject(i) ?: continue
            val s = Catalog.byId(c.sources, Catalog.str(fb, "source")) ?: continue
            routes += s to Engine.route(f, fb)
        }
        for ((s, spec) in routes) {
            val sym = symbolFor(spec, item) ?: continue
            try {
                val sl = Engine.slots(spec, today, listOf(sym)) + ("each" to sym)
                val conn = Catalog.str(spec, "provider").ifEmpty { s.connector }
                val g = Feeds.parse(conn, http.get(Requests.url(s.endpoint, Catalog.str(spec, "path"), filled(spec, sl), sl)), Feeds.Opt(spec, sym, s.paths))
                if (!g.isEmpty) return ItemGot(g, s.name, "")
                why += "${s.name}: empty"
            } catch (e: IOException) {
                why += "${s.name}: no answer"
            }
        }
        if (why.isEmpty()) why += "no source for $item"
        return ItemGot(Got(), "", why.joinToString("; "))
    }

    private fun filled(spec: JSONObject, sl: Map<String, String>) = Engine.params(spec).map { (k, v) -> k to sl.entries.fold(v) { a, (n, x) -> a.replace("{$n}", x) } }

    // ------------------------------------------------------------------ performance

    /** The return value of one declared column: a [Performance.COLUMNS] name, a whole-years "NY" (CAGR), or vol / max drawdown over 1y. */
    fun column(col: String, closes: List<Pair<LocalDate, Double>>, row: List<Double?>): Double? {
        val i = Performance.COLUMNS.indexOf(col)
        if (i >= 0) return row[i]
        return when {
            col == COL_VOL -> vol1y(closes)
            col == COL_MDD -> maxDrawdown1y(closes)
            col.endsWith("Y") && col.dropLast(1).toIntOrNull() != null -> Performance.trailing(closes, col.dropLast(1).toInt())
            else -> null
        }
    }

    /** Annualised volatility of daily log returns over the last year (fraction); null with fewer than 20 returns. */
    fun vol1y(closes: List<Pair<LocalDate, Double>>): Double? {
        val s = closes.filter { it.second > 0.0 }.sortedBy { it.first }
        val asOf = s.lastOrNull()?.first ?: return null
        val y = s.filter { !it.first.isBefore(asOf.minusYears(1)) }.map { it.second }
        val r = y.zipWithNext { a, b -> Math.log(b / a) }
        if (r.size < 20) return null
        val m = r.average()
        return Math.sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1) * 252.0)
    }

    /** Largest peak-to-trough fall over the last year, as a negative fraction (0.0 when it only rose); null with no data. */
    fun maxDrawdown1y(closes: List<Pair<LocalDate, Double>>): Double? {
        val s = closes.filter { it.second > 0.0 }.sortedBy { it.first }
        val asOf = s.lastOrNull()?.first ?: return null
        var peak = 0.0
        var dd = 0.0
        s.filter { !it.first.isBefore(asOf.minusYears(1)) }.forEach { (_, v) -> peak = maxOf(peak, v); dd = minOf(dd, v / peak - 1.0) }
        return dd
    }

    fun closes(g: Got): List<Pair<LocalDate, Double>> = g.bars.mapNotNull { b -> runCatching { LocalDate.parse(b.date.take(10)) }.getOrNull()?.let { it to b.c } }
        .distinctBy { it.first }.sortedBy { it.first }

    fun fmt(col: String, v: Double?): String = if (col == COL_VOL && v != null && v.isFinite()) String.format(Locale.US, "%.2f%%", v * 100.0) else Performance.format(v)

    /** Rows: the declared label (full name, ticker in the detail) and last close, then each declared return column. */
    fun performance(panel: Panel, f: JSONObject, got: Map<String, ItemGot>): PanelData {
        val labels = f.optJSONObject("labels") ?: JSONObject()
        val perf = panel.columns.drop(2)
        val missing = ArrayList<String>()
        var asOf = ""
        val by = LinkedHashSet<String>()
        val full = got.keys.map { Catalog.str(labels, it).ifEmpty { it } }
        val rows = got.map { (item, ig) ->
            val cl = closes(ig.got)
            val name = Catalog.str(labels, item).ifEmpty { item }
            val cellName = shortName(name)
            if (cl.isEmpty()) { missing += "$name: ${ig.why}"; return@map listOf(cellName, FinFormat.DASH) + perf.map { FinFormat.DASH } }
            by += ig.by
            if (cl.last().first.toString() > asOf) asOf = cl.last().first.toString()
            val row = Performance.row(cl)
            val short = perf.filter { column(it, cl, row) == null }
            if (short.isNotEmpty()) missing += "$name ${short.joinToString("/")}: history from ${cl.first().first}"
            listOf(cellName, Engine.sig(cl.last().second)) + perf.map { fmt(it, column(it, cl, row)) }
        }
        val note = if (missing.isEmpty()) "" else "— " + missing.joinToString(" · ")
        return PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, note, servedBy = by.filter { it.isNotEmpty() }.joinToString(" + "), details = full)
    }

    /** Longest short label kept whole; longer ones are cut to 4 letters a word ("Consumer Discretionary" -> "Cons Disc"). */
    const val SHORT_MAX = 12

    /**
     * The one-line name cell: a declared "Full Name (TICKER)" becomes "TICKER Short" (ticker first, then [SHORT_MAX] at most);
     * a label with no ticker is shortened the same way. The full label stays in [PanelData.details] for the detail view.
     */
    fun shortName(label: String): String {
        val m = Regex("^(.*?)\\s*\\(([^()]+)\\)\\s*$").find(label.trim())
        val base = (m?.groupValues?.get(1) ?: label).trim()
        val short = if (base.length <= SHORT_MAX) base
            else base.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w -> if (w.length > 4) w.take(4) else w }
                .fold("") { acc, w -> if (acc.isEmpty()) w else if (acc.length + 1 + w.length <= SHORT_MAX) "$acc $w" else acc }
        return if (m == null) short else "${m.groupValues[2].trim()} $short".trim()
    }

    // ------------------------------------------------------------------ model portfolio

    /** A constant-weight portfolio rebalanced daily over the dates every leg has: V0 = 100, Vt = Vt-1 * sum(w * Pt / Pt-1). */
    fun rebalanced(legs: List<Pair<Double, List<Pair<LocalDate, Double>>>>): List<Pair<LocalDate, Double>> {
        if (legs.isEmpty() || legs.any { it.second.isEmpty() }) return emptyList()
        val maps = legs.map { it.second.toMap() }
        val dates = maps.map { it.keys }.reduce { a, b -> a.intersect(b) }.sorted()
        if (dates.isEmpty()) return emptyList()
        val tw = legs.sumOf { it.first }
        val out = ArrayList<Pair<LocalDate, Double>>()
        var v = 100.0
        out += dates[0] to v
        for (i in 1 until dates.size) {
            v *= legs.indices.sumOf { k -> legs[k].first / tw * (maps[k][dates[i]]!! / maps[k][dates[i - 1]]!!) }
            out += dates[i] to v
        }
        return out
    }

    fun portfolio(panel: Panel, f: JSONObject, legs: List<Pair<String, Int>>, got: Map<String, ItemGot>): PanelData {
        val proxies = f.optJSONObject("proxies") ?: JSONObject()
        val perf = panel.columns.drop(2)
        val series = legs.map { (name, w) -> Triple(name, w, closes(got[name]?.got ?: Got())) }
        val port = rebalanced(series.map { it.second.toDouble() to it.third })
        val missing = series.filter { it.third.isEmpty() }.map { "${it.first}: ${got[it.first]?.why.orEmpty()}" }
        fun line(label: String, cl: List<Pair<LocalDate, Double>>, last: String): List<String> =
            if (cl.isEmpty()) listOf(label, FinFormat.DASH) + perf.map { FinFormat.DASH }
            else Performance.row(cl).let { r -> listOf(label, last) + perf.map { fmt(it, column(it, cl, r)) } }
        val title = "Portfolio " + legs.joinToString("/") { it.second.toString() }
        val rows = listOf(line(title, port, if (port.isEmpty()) FinFormat.DASH else Engine.sig(port.last().second))) +
            series.map { (name, w, cl) -> line("${Catalog.str(proxies, name)} $name ${w}%".trim(), cl, cl.lastOrNull()?.let { Engine.sig(it.second) } ?: FinFormat.DASH) }
        val full = listOf("$title, daily rebalanced") + series.map { (name, w, _) -> "$name ${w}% (${Catalog.str(proxies, name)})" }
        val note = (if (port.isNotEmpty()) "Daily rebalanced from ${port.first().first}; legs priced by ETF proxies." else "") +
            (if (missing.isEmpty()) "" else " — " + missing.joinToString(" · "))
        return PanelData(panel.columns, rows, emptyList(), emptyList(), port.lastOrNull()?.first?.toString().orEmpty(), note.trim(),
            servedBy = got.values.map { it.by }.filter { it.isNotEmpty() }.distinct().joinToString(" + "), details = full)
    }

    // ------------------------------------------------------------------ yields matrix

    data class Tenor(val label: String, val years: Double)

    fun tenors(f: JSONObject): List<Tenor> {
        val a = f.optJSONArray("tenors") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { Tenor(it.optString(0), it.optDouble(1)) }
    }

    /** One country's curve as (years, yield %), sorted: v1 points carry tenor_years; a source's own keys are placed by `years`. */
    fun curve(f: JSONObject, g: Got): List<Pair<Double, Double>> {
        val years = f.optJSONObject("years")
        return g.points.map { p -> (if (years != null && years.has(p.key)) years.getDouble(p.key) else p.x) to p.value }
            .filter { it.first > 0.0 && it.second.isFinite() }.distinctBy { it.first }.sortedBy { it.first }
    }

    /** The cell at [t]: spot = the point at that tenor (none: null); forward = [Forward.rate] from [start] years for [t] years. */
    fun cell(curve: List<Pair<Double, Double>>, t: Double, forward: Boolean, start: Double): Double? =
        if (forward) Forward.rate(curve, start, t).takeIf { curve.isNotEmpty() && it.isFinite() }
        else curve.firstOrNull { Math.abs(it.first - t) < 0.02 }?.second

    /** GDP-weighted mean over the countries that have a value (weights renormalised); null when none does. */
    fun weighted(values: List<Pair<Double, Double?>>): Double? {
        val have = values.filter { it.second != null && it.first > 0.0 }
        val w = have.sumOf { it.first }
        return if (have.isEmpty() || w <= 0.0) null else have.sumOf { it.first * it.second!! } / w
    }

    /** The declared default for `min_coverage_gdp`: an average is shown only when its countries with a value hold this GDP share. */
    const val MIN_COVERAGE = 0.6
    const val COL_COV = "Cov"

    /** Share (0..1) of the members' total GDP held by the members that have a value; 0 when the members have no GDP. */
    fun coverage(values: List<Pair<Double, Double?>>): Double {
        val tot = values.filter { it.first > 0.0 }.sumOf { it.first }
        return if (tot <= 0.0) 0.0 else values.filter { it.first > 0.0 && it.second != null }.sumOf { it.first } / tot
    }

    /** [weighted] gated by [coverage]: null (shown "—", insufficient coverage) below [min]. */
    fun gatedAverage(values: List<Pair<Double, Double?>>, min: Double): Double? = if (coverage(values) + 1e-12 < min) null else weighted(values)

    fun pct0(x: Double): String = String.format(Locale.US, "%.0f%%", x * 100.0)

    /**
     * The container's one-call matrix (fetch.primary.matrix_api, /v1/curves/matrix): {asof, fill:{cells, filled, rate}, rows:[{country, group,
     * gdp_usd, gdp_weight, tenors:[{tenor, value|null, date, source, method}]}]}. [why] holds each empty cell's `method` (country -> tenor -> reason).
     */
    data class One(val got: Map<String, ItemGot>, val gdp: Map<String, Double>, val why: Map<String, Map<String, String>>, val fill: Double?, val by: String)

    /** Parse a /v1/curves/matrix body for the declared [each] countries; null when it carries no row of them. */
    fun parseOne(body: String, each: List<String>, by: String): One? {
        val o = JSONObject(body)
        val rows = o.optJSONArray("rows") ?: return null
        val got = LinkedHashMap<String, ItemGot>()
        val gdp = HashMap<String, Double>()
        val why = LinkedHashMap<String, Map<String, String>>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val cc = Catalog.str(r, "country")
            if (cc !in each) continue
            r.optDouble("gdp_usd").takeIf { it.isFinite() && it > 0 }?.let { gdp[cc] = it / 1e9 }
            val ts = r.optJSONArray("tenors") ?: JSONArray()
            val pts = ArrayList<Pt>()
            val miss = LinkedHashMap<String, String>()
            for (j in 0 until ts.length()) {
                val t = ts.optJSONObject(j) ?: continue
                val k = Catalog.str(t, "tenor")
                val y = Feeds.tenorYears(k) ?: continue
                val v = t.opt("value")
                if (v is Number && v.toDouble().isFinite()) pts += Pt(k, k, y, v.toDouble(), null, Catalog.str(t, "date"))
                else miss[k] = Catalog.str(t, "method").ifEmpty { "no value" }
            }
            got[cc] = if (pts.isEmpty()) ItemGot(Got(), "", miss.values.distinct().joinToString("; ").ifEmpty { "no curve" }) else ItemGot(Got(points = pts), by, "")
            why[cc] = miss
        }
        if (got.isEmpty() || got.values.all { it.got.isEmpty }) return null
        val missing = each.filter { it !in got }
        missing.forEach { got[it] = ItemGot(Got(), "", "not in /v1/curves/matrix") }
        val ordered = LinkedHashMap<String, ItemGot>().apply { each.forEach { put(it, got.getValue(it)) } }
        val fill = o.optJSONObject("fill")?.optDouble("rate")?.takeIf { it.isFinite() }
        return One(ordered, gdp, why, fill, by)
    }

    /** One GET of fetch.primary.matrix_api through the mesh; null (the per-country path runs) when undeclared, mesh off, or no usable answer. */
    fun matrixOne(f: JSONObject, http: Http, c: ModelCtx): One? {
        val p = f.optJSONObject("primary") ?: return null
        val path = Catalog.str(p, "matrix_api").ifEmpty { return null }
        val api = c.api ?: return null
        return try {
            parseOne(http.get(api.base.trimEnd('/') + path), strings(f.optJSONArray("each")), api.source.name)
        } catch (e: Exception) {
            null
        }
    }

    /** The empty cells' reasons, grouped: "no free source declared for this cell: FR 3M/6M, IT 3M" (tenors in declared order). */
    fun reasons(why: Map<String, Map<String, String>>, ts: List<String>): List<String> {
        val by = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        for ((cc, m) in why) for (t in ts) m[t]?.let { r -> by.getOrPut(r) { LinkedHashMap() }.getOrPut(cc) { ArrayList() } += t }
        return by.map { (r, ccs) -> "$r: " + ccs.entries.joinToString(", ") { (cc, t) -> "$cc ${t.joinToString("/")}" } }
    }

    fun matrix(panel: Panel, f: JSONObject, got: Map<String, ItemGot>, one: One? = null): PanelData {
        val forward = panel.raw.optString("mode") == "forward"
        val start = f.optDouble("forward_start_years", 1.0)
        val ts = tenors(f)
        val labels = f.optJSONObject("labels") ?: JSONObject()
        val gdp = JSONObject((f.optJSONObject("gdp_usd_bn") ?: JSONObject()).toString()).also { g -> one?.gdp?.forEach { (k, v) -> g.put(k, v) } }
        val groups = f.optJSONObject("groups") ?: JSONObject()
        val curves = got.mapValues { curve(f, it.value.got) }
        val cells = curves.mapValues { (_, cv) -> ts.map { cell(cv, it.years, forward, start) } }
        val min = f.optDouble("min_coverage_gdp", MIN_COVERAGE)
        val hasCov = panel.columns.getOrNull(1) == COL_COV
        fun vals(members: List<String>, i: Int) = members.map { gdp.optDouble(it, 0.0) to cells[it]?.get(i) }
        fun avg(members: List<String>): List<Double?> = ts.indices.map { i -> gatedAverage(vals(members, i), min) }
        // the row's coverage: the best tenor's share (each cell is gated on its own tenor's share)
        fun cov(members: List<String>): Double = ts.indices.maxOfOrNull { i -> coverage(vals(members, i)) } ?: 0.0
        val dm = strings(groups.optJSONArray("DM"))
        val em = strings(groups.optJSONArray("EM"))
        val world = dm + em
        val worldGdp = world.sumOf { gdp.optDouble(it, 0.0) }
        fun fmtRow(label: String, covCell: String, v: List<Double?>) = listOf(label) + (if (hasCov) listOf(covCell) else emptyList()) +
            v.map { x -> if (x == null) FinFormat.DASH else String.format(Locale.US, "%.2f", x) }
        val avgs = listOf("World avg" to world, "DM avg" to dm, "EM avg" to em)
        val thin = avgs.filter { (_, m) -> avg(m).any { it == null } && cov(m) + 1e-12 < min }
            .map { (l, m) -> "$l ${pct0(cov(m))}" }
        val rows = avgs.map { (l, m) -> fmtRow(l, pct0(cov(m)), avg(m)) } +
            got.keys.map { fmtRow(Catalog.str(labels, it).ifEmpty { it }, if (worldGdp > 0) pct0(gdp.optDouble(it, 0.0) / worldGdp) else FinFormat.DASH, cells[it]!!) }
        val noCurve = got.filter { curves[it.key].isNullOrEmpty() }.map { "${it.key} (${it.value.why})" }
        val gaps = if (one != null && !forward) emptyList() else curves.filter { it.value.isNotEmpty() }.mapNotNull { (k, _) ->
            val miss = ts.filterIndexed { i, _ -> cells[k]!![i] == null }.map { it.label }
            if (miss.isEmpty()) null else "$k ${miss.joinToString("/")}"
        }
        val note = listOfNotNull(
            if (forward) "${fmtYears(start)}-forward curve from each spot curve (Forward.rate)." else null,
            if (one?.fill != null) "One call /v1/curves/matrix: ${pct0(one.fill)} of cells filled; GDP from the container (World Bank, USD)." else null,
            "Averages GDP-weighted (IMF, USD), shown only when the countries with a value hold ${pct0(min)} of the group's GDP; Cov = that share (country rows: share of World GDP).",
            if (thin.isEmpty()) null else "— insufficient coverage: " + thin.joinToString(", ") + " < ${pct0(min)}.",
            if (noCurve.isEmpty()) null else "— no curve: " + noCurve.joinToString(", "),
            if (gaps.isEmpty()) null else "— no point at tenor: " + gaps.joinToString(", "),
            if (one == null || forward) null else reasons(one.why.filterKeys { k -> curves[k].orEmpty().isNotEmpty() }, ts.map { it.label }).joinToString(" ") { "— $it." },
        ).joinToString(" ")
        val asOf = got.values.flatMap { it.got.points }.maxOfOrNull { it.date }.orEmpty()
        return PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, note,
            servedBy = got.values.map { it.by }.filter { it.isNotEmpty() }.distinct().joinToString(" + "))
    }

    private fun fmtYears(y: Double) = if (y == Math.floor(y)) "${y.toInt()}y" else "${y}y"

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
}
