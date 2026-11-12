package com.diegonmarcos.capitalfinterm

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

/** #1663 one constituent of an index: its ticker, its symbol at the quote source, its name and its weight in the index, in %. */
data class IndexHolding(val ticker: String, val symbol: String, val name: String, val sector: String, val weight: Double)

/** One index of the runtime dataset `index-holdings`: its constituents by descending weight, the publisher and the date of the weights. */
data class IndexBasket(
    val key: String, val name: String, val country: String, val indexSymbol: String, val source: String, val asOf: String, val holdings: List<IndexHolding>,
) {
    val weightSum: Double get() = holdings.sumOf { it.weight }
}

/** A declared index of a dashboard that opens its holdings breakdown (data/dashboards.json `index_breakdown`): the dataset key and the row labels it answers to. */
data class IndexLink(val key: String, val label: String, val country: String, val match: List<String>)

/** One line of the breakdown: the holding, its performance over the period in % (null = the quote source has not answered) and its contribution in percentage points. */
data class BreakdownRow(val holding: IndexHolding, val perf: Double?, val contribution: Double?)

/** A tile of the treemap, in the units of the box it was laid out in. */
data class TreeRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val area: Double get() = w * h
}

/**
 * #1663 EQ > World Indices Equity > an index: the performance breakdown by holding. Pure rules (no Compose, no network): the dataset parser, the declared
 * links, the table (holding, weight %, performance over the period, contribution) and the treemap (tile AREA = weight, tile COLOUR = performance).
 * Nothing here can throw on missing data: an absent dataset is an empty map, an unanswered quote is a null performance.
 */
object IndexHoldings {
    /** The runtime dataset (data/fleet/app/<package>/index-holdings.json, served by Cloud Store through libs:fleet-data-client). */
    const val DATASET = "index-holdings"

    /** The periods the quote source gives: a day, 5 and 21 trading days, the year to date. */
    val PERIODS: List<String> = listOf("1D", "1W", "1M", "YTD")
    const val DEFAULT_PERIOD = "1D"

    /** How many holdings are shown: the largest 50 or 100, or every constituent (0). */
    val TOPS: List<Int> = listOf(50, 100, 0)
    const val DEFAULT_TOP = 100

    val COLUMNS: List<String> = listOf("Holding", "Weight %", "Perf %", "Contrib pp")
    val COL_TYPES: List<String> = listOf("text", "num", "pct", "chg")

    /** The dataset's indices by key; an unreadable body, a missing block or a holding without a symbol or a positive weight is left out. */
    fun parse(json: String): Map<String, IndexBasket> {
        val ix = runCatching { JSONObject(json).optJSONObject("indices") }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, IndexBasket>()
        for (key in ix.keys()) {
            val o = ix.optJSONObject(key) ?: continue
            val arr = o.optJSONArray("holdings") ?: continue
            val hs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { h ->
                val w = h.optDouble("weight", Double.NaN)
                val sym = h.optString("symbol")
                if (!w.isFinite() || w <= 0.0 || sym.isBlank()) null
                else IndexHolding(h.optString("ticker").ifBlank { sym }, sym, h.optString("name"), h.optString("sector"), w)
            }.sortedByDescending { it.weight }
            if (hs.isEmpty()) continue
            out[key] = IndexBasket(key, o.optString("name").ifBlank { key }, o.optString("country"), o.optString("index_symbol"), o.optString("source"), o.optString("as_of"), hs)
        }
        return out
    }

    /** The declared links of a dashboard (`index_breakdown: [{key, label, country, match[]}]`); none when it declares none. */
    fun links(dashboard: JSONObject): List<IndexLink> {
        val arr = dashboard.optJSONArray("index_breakdown") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val key = o.optString("key")
            if (key.isBlank()) return@mapNotNull null
            val m = o.optJSONArray("match")
            IndexLink(key, o.optString("label").ifBlank { key }, o.optString("country"), if (m == null) emptyList() else (0 until m.length()).map { m.optString(it) })
        }
    }

    /** The link a row label (or symbol) of a panel opens, or null: the row is an ordinary one. */
    fun linkFor(links: List<IndexLink>, label: String?): IndexLink? {
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return null
        return links.firstOrNull { k -> k.label.equals(l, ignoreCase = true) || k.match.any { it.equals(l, ignoreCase = true) } }
    }

    /** The quote's % change over [period]; null when the source did not give it. */
    fun perf(q: Quote?, period: String): Double? = when (period) {
        "1D" -> q?.pct
        "1W" -> q?.w1
        "1M" -> q?.m1
        "YTD" -> q?.ytd
        else -> null
    }?.takeIf { it.isFinite() }

    /** The key of data/quotes.json `heat` (the % change that is a full tint) for [period]. */
    fun heatKey(period: String): String = when (period) { "1W" -> "w1"; "1M" -> "m1"; "YTD" -> "ytd"; else -> "pct" }

    /** A holding's contribution to the index over the period, in percentage points: weight % x performance % / 100. */
    fun contribution(weight: Double, perf: Double?): Double? = perf?.let { weight * it / 100.0 }

    /** The [top] largest holdings (every one when [top] <= 0) with their performance from [quotes] (by symbol) and their contribution. */
    fun rows(basket: IndexBasket, quotes: Map<String, Quote>, period: String, top: Int): List<BreakdownRow> {
        val shown = if (top <= 0) basket.holdings else basket.holdings.take(top)
        return shown.map { h -> perf(quotes[h.symbol], period).let { BreakdownRow(h, it, contribution(h.weight, it)) } }
    }

    /** What the shown rows leave out of the index: the weight in % up to 100 (the smaller holdings, and what the publisher lists as cash). Never negative. */
    fun restWeight(rows: List<BreakdownRow>): Double = max(0.0, 100.0 - rows.sumOf { it.holding.weight })

    /** The sum of the known contributions (percentage points) and the weight in % they cover; null while no quote has answered. */
    fun total(rows: List<BreakdownRow>): Pair<Double, Double>? {
        val known = rows.filter { it.contribution != null }
        if (known.isEmpty()) return null
        return known.sumOf { it.contribution!! } to known.sumOf { it.holding.weight }
    }

    private fun f2(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
    private fun s2(v: Double?): String = v?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: FinFormat.DASH

    /** The table's cells, one line per shown holding in weight order: [ticker and name, weight %, performance %, contribution pp], then the rest of the index when any. */
    fun table(rows: List<BreakdownRow>, restLabel: String): List<List<String>> {
        val out = rows.map { r -> listOf(label(r.holding), f2(r.holding.weight), s2(r.perf), s2(r.contribution)) }
        val rest = restWeight(rows)
        return if (rest >= 0.005) out + listOf(listOf(restLabel, f2(rest), FinFormat.DASH, FinFormat.DASH)) else out
    }

    /** "AAPL Apple Inc": the ticker first, so a narrow column still names the holding. */
    fun label(h: IndexHolding): String = if (h.name.isBlank() || h.name.equals(h.ticker, ignoreCase = true)) h.ticker else h.ticker + " " + h.name

    /**
     * The tile colour's position on the scale: -1 (the full down colour) .. 0 (flat) .. +1 (the full up colour), the performance over [scale] %
     * clamped; null (no quote) and a non-positive scale are 0, drawn neutral.
     */
    fun heat(perf: Double?, scale: Double): Float {
        if (perf == null || !perf.isFinite() || !(scale > 0.0)) return 0f
        return (perf / scale).coerceIn(-1.0, 1.0).toFloat()
    }

    /** How far a tile's fill goes from the neutral surface toward the up / down colour: a visible floor for any move, full at the clamp; 0 when flat or unknown. */
    fun blend(heat: Float): Float = if (heat == 0f || heat.isNaN()) 0f else (BLEND_FLOOR + (1f - BLEND_FLOOR) * abs(heat)).coerceIn(0f, 1f)
    const val BLEND_FLOOR: Float = 0.35f

    /**
     * The squarified treemap (Bruls, Huizing, van Wijk): one rectangle per weight inside a [width] x [height] box, each AREA proportional to its weight,
     * rows filled along the box's shorter side so the tiles stay close to squares, the largest at the top left. The answer is in the order of [weights];
     * a weight that is not a positive number, or an empty box, gets a zero rectangle.
     */
    fun treemap(weights: List<Double>, width: Double, height: Double): List<TreeRect> {
        val out = MutableList(weights.size) { TreeRect(0.0, 0.0, 0.0, 0.0) }
        val idx = weights.indices.filter { weights[it].isFinite() && weights[it] > 0.0 }.sortedByDescending { weights[it] }
        val total = idx.sumOf { weights[it] }
        if (idx.isEmpty() || !(width > 0.0) || !(height > 0.0) || !(total > 0.0)) return out
        val scale = width * height / total
        var x = 0.0; var y = 0.0; var w = width; var h = height
        var i = 0
        while (i < idx.size) {
            val side = min(w, h)
            if (!(side > 0.0)) break
            var sum = 0.0; var hi = 0.0; var lo = Double.MAX_VALUE; var best = Double.MAX_VALUE
            var j = i
            while (j < idx.size) {
                val a = weights[idx[j]] * scale
                val s = sum + a
                val worst = max(side * side * max(hi, a) / (s * s), s * s / (side * side * min(lo, a)))
                if (j > i && worst > best) break
                sum = s; hi = max(hi, a); lo = min(lo, a); best = worst; j++
            }
            val thick = sum / side
            var off = 0.0
            for (k in i until j) {
                val len = weights[idx[k]] * scale / thick
                out[idx[k]] = if (w >= h) TreeRect(x, y + off, thick, len) else TreeRect(x + off, y, len, thick)
                off += len
            }
            if (w >= h) { x += thick; w -= thick } else { y += thick; h -= thick }
            i = j
        }
        return out
    }
}
