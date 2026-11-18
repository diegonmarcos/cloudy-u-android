package com.diegonmarcos.capitalfinterm

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** #1786 one instrument of a Homepage box: its symbol at the quote source and the label it is shown under. */
data class BoxItem(val symbol: String, val label: String)

/** #1786 a quote box (data/home-boxes.json `quote_boxes`): the sub-page it belongs to, where its instruments are declared, their order and its columns. */
data class QuoteBox(val page: String, val from: String, val items: List<BoxItem>, val order: List<String>, val columns: List<String>, val note: String)

/** #1786 one column of a CB overview box: the indicator of data/cb-indicators.json, its label and how the value is read (latest | fc0). */
data class CbBoxCol(val indicator: String, val label: String, val mode: String)

/** #1786 one CB overview box: its id, title, function mnemonic, the sub-page a tap opens, its columns and unit. */
data class CbBox(val id: String, val title: String, val fn: String, val opens: String, val columns: List<CbBoxCol>, val unit: String)

/** #1786 one value of a CB box: the figure (null = n/a) and what it is (the year, or the month the year to date runs through; "2026f" a forecast). */
data class CbCell(val value: Double?, val asOf: String)

/** #1786 data/home-boxes.json. */
data class HomeBoxesDecl(val top: Int, val quoteBoxes: Map<String, QuoteBox>, val cbBoxes: List<CbBox>, val cbTop: List<String>, val ui: Map<String, String>) {
    fun word(k: String): String = ui[k].orEmpty()
}

/**
 * #1786 the Homepage boxes: a card that lists its page's instruments (EQ World Indices Equity, every HG page) or a CB indicator by country (the CB
 * overview), the top [HomeBoxesDecl.top] first and Show all for every one. Pure rules: the declaration parser, where a box's instruments are declared
 * (resolved, never retyped), the shown rows and the cells. Missing data is n/a, never a crash.
 */
object HomeBoxes {
    const val COL_LAST = "last"
    const val MODE_FC0 = "fc0"
    const val ASSET = "home-boxes.json"

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }

    fun parse(json: String): HomeBoxesDecl? = runCatching {
        val o = JSONObject(json)
        val qb = o.optJSONObject("quote_boxes") ?: JSONObject()
        val boxes = LinkedHashMap<String, QuoteBox>()
        for (k in qb.keys()) {
            if (k.startsWith("_")) continue
            val b = qb.optJSONObject(k) ?: continue
            val its = b.optJSONArray("items")
            val items = if (its == null) emptyList() else (0 until its.length()).mapNotNull { its.optJSONObject(it) }.map { BoxItem(it.optString("symbol"), it.optString("label").ifEmpty { it.optString("symbol") }) }.filter { it.symbol.isNotEmpty() }
            boxes[k] = QuoteBox(k, b.optString("from"), items, strings(b.optJSONArray("order")), strings(b.optJSONArray("columns")).ifEmpty { listOf(COL_LAST, Cagr.P12M) }, b.optString("note"))
        }
        val cb = o.optJSONArray("cb_boxes")
        val cbs = if (cb == null) emptyList() else (0 until cb.length()).mapNotNull { cb.optJSONObject(it) }.map { b ->
            val cs = b.optJSONArray("columns")
            CbBox(b.optString("id"), b.optString("title"), b.optString("fn"), b.optString("opens"),
                if (cs == null) emptyList() else (0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.map { CbBoxCol(it.optString("indicator"), it.optString("label"), it.optString("mode").ifEmpty { "latest" }) },
                b.optString("unit"))
        }
        val ui = o.optJSONObject("ui") ?: JSONObject()
        HomeBoxesDecl(o.optInt("top", 5), boxes, cbs, strings(o.optJSONArray("cb_top")), ui.keys().asSequence().associateWith { ui.optString(it) })
    }.getOrNull()

    /**
     * The instruments of [box], from where its page declares them: `futures:<class>` the class's front contracts, `fx` the currencies (USD has no symbol),
     * `commodities` the commodity fronts, `dashboard:<id>[/<panel>]` the fetch.each of the dashboard's panels (one panel, or every panel with a list) with
     * their labels; else the box's own `items`. Each symbol once, then the `order` symbols moved to the front.
     */
    fun items(box: QuoteBox, quotes: QuotesDecl?, dashboards: List<Dashboard>): List<BoxItem> {
        val f = box.from
        val raw: List<BoxItem> = when {
            f.startsWith("futures:") -> quotes?.classes?.firstOrNull { it.id == f.removePrefix("futures:") }?.roots.orEmpty().mapNotNull { r -> r.front?.takeIf { it.isNotBlank() }?.let { BoxItem(it, r.name) } }
            f == "fx" -> quotes?.ccys.orEmpty().mapNotNull { c -> c.symbol?.let { BoxItem(it, c.id + " · " + c.name) } }
            f == "commodities" -> quotes?.commodities.orEmpty().mapNotNull { c -> quotes?.spec(c.root)?.front?.takeIf { it.isNotBlank() }?.let { BoxItem(it, c.name) } }
            f.startsWith("dashboard:") -> {
                val (id, panel) = f.removePrefix("dashboard:").split("/", limit = 2).let { it[0] to it.getOrNull(1) }
                Dashboards.byId(dashboards, id)?.panels.orEmpty().filter { panel == null || it.id == panel }.flatMap { p ->
                    val each = p.fetch?.optJSONArray("each")
                    val labels = p.fetch?.optJSONObject("labels")
                    if (each == null) emptyList() else (0 until each.length()).map { each.optString(it) }.filter { it.isNotBlank() }.map { s -> BoxItem(s, labels?.optString(s)?.ifEmpty { null } ?: s) }
                }
            }
            else -> emptyList()
        } + box.items
        val unique = raw.distinctBy { it.symbol }
        val first = box.order.mapNotNull { s -> unique.firstOrNull { it.symbol == s } }
        return first + unique.filter { it !in first }
    }

    /** The rows a box shows: the [top] first, or every one when [all]. */
    fun shown(items: List<BoxItem>, top: Int, all: Boolean): List<BoxItem> = if (all || top <= 0) items else items.take(top)

    /** A box column's header: Last, or the period with its unit (1M %, 12M %, CAGR 12m %). */
    fun header(col: String, last: String): String = if (col == COL_LAST) last else "$col %"

    /** The last price with as many decimals as its size needs (4 under 10, 2 under 10 000, else 0). */
    fun price(v: Double): String = String.format(Locale.ROOT, if (v < 10.0) "%.4f" else if (v < 10_000.0) "%.2f" else "%.0f", v)

    /** One cell of a quote box: the last price, or the change over a period in %; "n/a" when the source has no answer or not enough history. */
    fun cell(q: Quote?, col: String): String =
        if (col == COL_LAST) q?.last?.takeIf { it.isFinite() }?.let(::price) ?: Cagr.NA
        else IndexHoldings.perf(q, col)?.let { String.format(Locale.ROOT, "%+.1f", it) } ?: Cagr.NA

    /** The value of a cell for its colour: the period change (the last price has no direction). */
    fun change(q: Quote?, col: String): Double? = if (col == COL_LAST) null else IndexHoldings.perf(q, col)

    /** The symbols a box asks the quote source for, the shown rows first. */
    fun symbols(items: List<BoxItem>): List<String> = items.map { it.symbol }.distinct()

    // ── CB overview ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The latest print of [s]: the year in progress to date when the source is sub-annual (ytd, labelled with the month it runs through), else the last
     * annual actual (labelled with its year); [MODE_FC0] = the forecast for [currentYear] ("<year>f"). Null value = n/a.
     */
    fun cbCell(s: CbSeries?, mode: String, currentYear: Int): CbCell {
        if (s == null) return CbCell(null, "")
        if (mode == MODE_FC0) return CbCell(s.fc[currentYear], if (s.fc[currentYear] == null) "" else "${currentYear}f")
        s.ytd?.let { return CbCell(it.value, it.through.ifEmpty { it.year.toString() }) }
        val y = s.hist.keys.maxOrNull() ?: return CbCell(null, "")
        return CbCell(s.hist[y], y.toString())
    }

    /** The countries of a CB box in order: the declared majors ([HomeBoxesDecl.cbTop]) first, then every other country of [all] in its declared order (the euro area and its members both listed, as on the CB page). */
    fun cbCountries(top: List<String>, all: List<CbCountry>): List<CbCountry> {
        val first = top.mapNotNull { id -> all.firstOrNull { it.id == id } }
        return first + all.filter { it !in first }
    }

    /** A CB cell's text: the value with two decimals, or n/a. */
    fun cbText(c: CbCell): String = c.value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.2f", it) } ?: Cagr.NA
}
