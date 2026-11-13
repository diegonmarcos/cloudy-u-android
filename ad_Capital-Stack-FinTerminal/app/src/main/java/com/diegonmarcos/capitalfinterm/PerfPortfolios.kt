package com.diegonmarcos.capitalfinterm

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

/** #1722 one declared weighting scheme of a performance portfolio (data/portfolios.json): its id, the label shown and how the weights are made. */
data class PerfScheme(val id: String, val label: String, val method: String)

/** A benchmark of a portfolio: a symbol of the quote source and the label it is shown under. */
data class PerfBenchmark(val symbol: String, val label: String)

/** One declared performance portfolio: a factor portfolio of EQ > Performance or a bond group of FI. */
data class PerfGroup(
    val key: String, val section: String, val label: String, val kind: String, val what: String, val bucketLabel: String, val unit: String, val perfNote: String,
    val etf: String, val etfName: String, val schemes: List<PerfScheme>, val defaultScheme: String, val benchmarks: List<PerfBenchmark>,
) {
    fun scheme(id: String): PerfScheme? = schemes.firstOrNull { it.id == id }
}

/** The declaration data/portfolios.json: the portfolios, the periods, the two modes and the words of the page. Nothing here is a weight or a figure. */
data class PerfDecl(
    val dataset: String, val periods: List<String>, val defaultPeriod: String, val modes: List<String>, val defaultMode: String, val cardTop: Int,
    val tops: List<Int>, val defaultTop: Int, val bubbleX: String, val bubbleY: String, val groups: List<PerfGroup>, val ui: Map<String, String>,
) {
    fun group(key: String): PerfGroup? = groups.firstOrNull { it.key == key }
    fun word(k: String): String = ui[k].orEmpty()
}

/**
 * One constituent of the runtime dataset: its ticker, its symbol at the quote source ("" = no listed instrument), its name, its bucket (sector, region,
 * currency, fund type or bond group) and its weight in % under each scheme. [basis] says what the symbol's move means: "" the instrument itself, "fx" the
 * currency against the dollar (the symbol is USD per currency, so the move is inverted), "usd" a dollar country (no currency move).
 */
data class PerfConstituent(
    val ticker: String, val symbol: String, val name: String, val bucket: String, val weights: Map<String, Double>, val basis: String, val proxy: String, val note: String,
)

/** Where one scheme's weights come from: the publisher, its address, the date and the share of the fund the publisher lists outside the constituents. */
data class PerfSchemeSource(val source: String, val sourceUrl: String, val asOf: String, val unlisted: Double?)

/** One portfolio of the runtime dataset `perf-portfolios`. */
data class PerfBasket(
    val key: String, val name: String, val kind: String, val source: String, val sourceUrl: String, val asOf: String, val count: Int,
    val constituents: List<PerfConstituent>, val schemeSources: Map<String, PerfSchemeSource>,
) {
    /** The scheme ids the constituents carry a weight for. */
    val schemeIds: Set<String> get() = constituents.flatMap { it.weights.keys }.toSet()
}

/** One line of a portfolio: the constituent, its weight in % under the chosen scheme, its performance over the period in % and its contribution in percentage points. */
data class PerfRow(val c: PerfConstituent, val weight: Double, val perf: Double?, val contribution: Double?)

/** One bar of the attribution waterfall: the bucket (or holding), its contribution in pp, and where its bar starts and ends on the cumulative axis. */
data class PerfStep(val label: String, val value: Double, val start: Double, val end: Double, val total: Boolean = false)

/** One bubble: the constituent's ticker, its performance over the x and the y period (%), and its weight (the bubble's area). */
data class PerfBubble(val ticker: String, val x: Double, val y: Double, val weight: Double)

/**
 * #1722 EQ > Performance and FI: the performance portfolios. Pure rules (no Compose, no network): the declaration and dataset parsers, the lines of a
 * portfolio under a weighting scheme (weight, performance, contribution), the totals, the sort of the constituents table, the attribution waterfall and
 * the bubbles. Nothing here can throw on missing data: an absent dataset is an empty map, an unanswered quote is a null performance.
 */
object PerfPortfolios {
    const val MODE_TABLE = "table"
    const val MODE_GRAPHIC = "graphic"

    const val BASIS_FX = "fx"
    const val BASIS_USD = "usd"

    /** The sort keys of the constituents table. */
    const val SORT_TICKER = "ticker"
    const val SORT_NAME = "name"
    const val SORT_BUCKET = "bucket"
    const val SORT_WEIGHT = "weight"
    const val SORT_PERF = "perf"
    const val SORT_CONTRIB = "contrib"
    val SORTS: List<String> = listOf(SORT_TICKER, SORT_NAME, SORT_BUCKET, SORT_WEIGHT, SORT_PERF, SORT_CONTRIB)

    private fun strings(o: JSONObject, key: String): List<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    /** The declaration; null when the body is not the declared shape (the page then says so). */
    fun parseDecl(json: String): PerfDecl? = runCatching {
        val o = JSONObject(json)
        val gs = o.getJSONArray("groups")
        val groups = (0 until gs.length()).mapNotNull { gs.optJSONObject(it) }.map { g ->
            val ss = g.optJSONArray("schemes")
            val bs = g.optJSONArray("benchmarks")
            PerfGroup(
                key = g.getString("key"), section = g.optString("section"), label = g.optString("label"), kind = g.optString("kind"), what = g.optString("what"),
                bucketLabel = g.optString("bucket_label").ifEmpty { "Group" }, unit = g.optString("unit"), perfNote = g.optString("perf_note"),
                etf = g.optString("etf"), etfName = g.optString("etf_name"),
                schemes = if (ss == null) emptyList() else (0 until ss.length()).mapNotNull { ss.optJSONObject(it) }.map { PerfScheme(it.optString("id"), it.optString("label"), it.optString("method")) },
                defaultScheme = g.optString("default_scheme"),
                benchmarks = if (bs == null) emptyList() else (0 until bs.length()).mapNotNull { bs.optJSONObject(it) }.map { PerfBenchmark(it.optString("symbol"), it.optString("label")) },
            )
        }
        val ui = o.optJSONObject("ui") ?: JSONObject()
        val bubble = o.optJSONObject("bubble") ?: JSONObject()
        val tops = o.optJSONArray("tops")
        PerfDecl(
            dataset = o.getString("dataset"), periods = strings(o, "periods").ifEmpty { IndexHoldings.PERIODS }, defaultPeriod = o.optString("default_period").ifEmpty { "YTD" },
            modes = strings(o, "modes").ifEmpty { listOf(MODE_TABLE, MODE_GRAPHIC) }, defaultMode = o.optString("default_mode").ifEmpty { MODE_TABLE },
            cardTop = o.optInt("card_top", 5), tops = if (tops == null) listOf(0) else (0 until tops.length()).map { tops.optInt(it) }, defaultTop = o.optInt("default_top", 0),
            bubbleX = bubble.optString("x").ifEmpty { "1M" }, bubbleY = bubble.optString("y").ifEmpty { "YTD" }, groups = groups,
            ui = ui.keys().asSequence().associateWith { ui.optString(it) },
        )
    }.getOrNull()

    /** The dataset's portfolios by key; an unreadable body, a missing block or a constituent without a ticker or any positive weight is left out. */
    fun parse(json: String): Map<String, PerfBasket> {
        val ps = runCatching { JSONObject(json).optJSONObject("portfolios") }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, PerfBasket>()
        for (key in ps.keys()) {
            val o = ps.optJSONObject(key) ?: continue
            val arr = o.optJSONArray("constituents") ?: continue
            val cs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { c ->
                val t = c.optString("ticker")
                val w = c.optJSONObject("weights")
                val ws = LinkedHashMap<String, Double>()
                if (w != null) for (k in w.keys()) { val v = w.optDouble(k, Double.NaN); if (v.isFinite() && v > 0.0) ws[k] = v }
                if (t.isBlank() || ws.isEmpty()) null
                else PerfConstituent(t, c.optString("symbol"), c.optString("name").ifBlank { t }, c.optString("bucket"), ws, c.optString("basis"), c.optString("proxy"), c.optString("note"))
            }
            if (cs.isEmpty()) continue
            val ss = o.optJSONObject("scheme_sources")
            val sources = LinkedHashMap<String, PerfSchemeSource>()
            if (ss != null) for (k in ss.keys()) {
                val s = ss.optJSONObject(k) ?: continue
                sources[k] = PerfSchemeSource(s.optString("source"), s.optString("source_url"), s.optString("as_of"), if (s.has("unlisted")) s.optDouble("unlisted").takeIf { it.isFinite() } else null)
            }
            out[key] = PerfBasket(key, o.optString("name").ifBlank { key }, o.optString("kind"), o.optString("source"), o.optString("source_url"), o.optString("as_of"), o.optInt("count", cs.size), cs, sources)
        }
        return out
    }

    /** The scheme a page shows: the asked one when the group declares it, else the group's default, else its first. */
    fun schemeOf(group: PerfGroup, asked: String?): String =
        group.schemes.firstOrNull { it.id == asked }?.id ?: group.schemes.firstOrNull { it.id == group.defaultScheme }?.id ?: group.schemes.firstOrNull()?.id.orEmpty()

    /** Where the weights of [scheme] come from: the scheme's own source when the dataset names one, else the portfolio's. */
    fun sourceOf(basket: PerfBasket, scheme: String): PerfSchemeSource =
        basket.schemeSources[scheme] ?: PerfSchemeSource(basket.source, basket.sourceUrl, basket.asOf, null)

    /**
     * A constituent's performance over [period] in %: its instrument's move; for a currency ([BASIS_FX], the symbol is USD per currency) the currency's move
     * against the dollar, the inverse of the rate's; 0 for a dollar country; null when it has no listed instrument or the source did not answer.
     */
    fun perf(c: PerfConstituent, quotes: Map<String, Quote>, period: String): Double? {
        if (c.basis == BASIS_USD) return 0.0
        if (c.symbol.isBlank()) return null
        val r = IndexHoldings.perf(quotes[c.symbol], period) ?: return null
        if (c.basis != BASIS_FX) return r
        val f = 1.0 + r / 100.0
        return if (f > 0.0) (1.0 / f - 1.0) * 100.0 else null
    }

    /** The constituents that carry a weight under [scheme], largest first (the [top] largest; every one when [top] <= 0), with performance and contribution. */
    fun rows(basket: PerfBasket, scheme: String, quotes: Map<String, Quote>, period: String, top: Int): List<PerfRow> {
        val all = basket.constituents.mapNotNull { c -> c.weights[scheme]?.takeIf { it > 0.0 }?.let { c to it } }.sortedByDescending { it.second }
        val shown = if (top <= 0) all else all.take(top)
        return shown.map { (c, w) -> perf(c, quotes, period).let { PerfRow(c, w, it, IndexHoldings.contribution(w, it)) } }
    }

    /** The symbols the quote source is asked for [rows]: every listed instrument once. */
    fun symbols(rows: List<PerfRow>): List<String> = rows.map { it.c.symbol }.filter { it.isNotBlank() }.distinct()

    /** The sum of the shown weights in %. */
    fun weightSum(rows: List<PerfRow>): Double = rows.sumOf { it.weight }

    /** The sum of the known contributions (pp) and the weight in % they cover; null while no quote has answered. */
    fun total(rows: List<PerfRow>): Pair<Double, Double>? {
        val known = rows.filter { it.contribution != null }
        if (known.isEmpty()) return null
        return known.sumOf { it.contribution!! } to known.sumOf { it.weight }
    }

    /** The portfolio's return over the period in %: the weighted mean performance of the quoted constituents (their weights rescaled to 100); null while none answered. */
    fun portfolioReturn(rows: List<PerfRow>): Double? = total(rows)?.let { (c, w) -> if (w > 0.0) c * 100.0 / w else null }

    /** How many quoted constituents rose and how many fell over the period. */
    fun advDec(rows: List<PerfRow>): Pair<Int, Int> = rows.count { (it.perf ?: 0.0) > 0.0 } to rows.count { (it.perf ?: 0.0) < 0.0 }

    /** The portfolio's return minus a benchmark's, in pp; null when either is unknown. */
    fun active(portfolio: Double?, benchmark: Double?): Double? = if (portfolio == null || benchmark == null) null else portfolio - benchmark

    /**
     * The constituents table sorted by [key] ([SORTS]); numbers [descending] put the largest first, texts the reverse alphabet. A line without a figure
     * always sorts last, whichever the direction.
     */
    fun sort(rows: List<PerfRow>, key: String, descending: Boolean): List<PerfRow> {
        fun num(r: PerfRow): Double? = when (key) { SORT_WEIGHT -> r.weight; SORT_PERF -> r.perf; SORT_CONTRIB -> r.contribution; else -> null }
        fun text(r: PerfRow): String = when (key) { SORT_NAME -> r.c.name; SORT_BUCKET -> r.c.bucket; else -> r.c.ticker }.lowercase(Locale.ROOT)
        return if (key == SORT_WEIGHT || key == SORT_PERF || key == SORT_CONTRIB) {
            val (known, unknown) = rows.partition { num(it) != null }
            (if (descending) known.sortedByDescending { num(it)!! } else known.sortedBy { num(it)!! }) + unknown
        } else if (descending) rows.sortedByDescending { text(it) } else rows.sortedBy { text(it) }
    }

    /**
     * The attribution waterfall of [rows]: one bar per bucket (the sum of its constituents' contributions), the largest moves first, at most [maxBars] - 1
     * bars with the rest folded into [restLabel], then the total. When the constituents do not split into buckets (one bucket, or more than half of the
     * weight in [otherBucket]) the bars are the constituents themselves. Each bar starts where the previous one ended. Empty while no quote has answered.
     */
    fun attribution(rows: List<PerfRow>, maxBars: Int, restLabel: String, totalLabel: String, otherBucket: String = "Other"): List<PerfStep> {
        val known = rows.filter { it.contribution != null }
        if (known.isEmpty()) return emptyList()
        val buckets = known.groupBy { it.c.bucket.ifBlank { otherBucket } }
        val w = known.sumOf { it.weight }
        val byBucket = buckets.size >= 2 && (buckets[otherBucket]?.sumOf { it.weight } ?: 0.0) <= w / 2.0
        val parts: List<Pair<String, Double>> =
            if (byBucket) buckets.map { (k, v) -> k to v.sumOf { it.contribution!! } } else known.map { it.c.ticker to it.contribution!! }
        val ranked = parts.sortedByDescending { abs(it.second) }
        val keep = max(1, maxBars - 1)
        val head = ranked.take(keep).sortedByDescending { it.second }
        val rest = ranked.drop(keep).sumOf { it.second }
        val bars = if (ranked.size > keep) head + (restLabel to rest) else head
        val out = ArrayList<PerfStep>()
        var at = 0.0
        for ((label, v) in bars) { out += PerfStep(label, v, at, at + v); at += v }
        out += PerfStep(totalLabel, at, 0.0, at, total = true)
        return out
    }

    /** The lowest and highest point the waterfall reaches (always including zero): the axis of its chart. */
    fun span(steps: List<PerfStep>): Pair<Double, Double> {
        var lo = 0.0; var hi = 0.0
        for (s in steps) { lo = min(lo, min(s.start, s.end)); hi = max(hi, max(s.start, s.end)) }
        return lo to hi
    }

    /** The bubbles: every shown constituent whose instrument answered for both periods. */
    fun bubbles(basket: PerfBasket, scheme: String, quotes: Map<String, Quote>, xPeriod: String, yPeriod: String, top: Int): List<PerfBubble> =
        rows(basket, scheme, quotes, xPeriod, top).mapNotNull { r ->
            val x = r.perf ?: return@mapNotNull null
            val y = perf(r.c, quotes, yPeriod) ?: return@mapNotNull null
            PerfBubble(r.c.ticker, x, y, r.weight)
        }

    /** The middle value of [values]; null when there is none. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    fun f2(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
    fun f1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    fun s2(v: Double?): String = v?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: FinFormat.DASH
    fun s1(v: Double?): String = v?.let { String.format(Locale.ROOT, "%+.1f", it) } ?: FinFormat.DASH

    /** The cells of the constituents table, one line per row: ticker, name, bucket, weight %, one performance per period, contribution pp. */
    fun cells(rows: List<PerfRow>, quotes: Map<String, Quote>, periods: List<String>): List<List<String>> =
        rows.map { r -> listOf(r.c.ticker, r.c.name, r.c.bucket, f2(r.weight)) + periods.map { s2(perf(r.c, quotes, it)) } + s2(r.contribution) }

    /** The totals line: the shown weight, the portfolio's return per period (the weighted mean of the quoted constituents) and the summed contribution. */
    fun totals(basket: PerfBasket, scheme: String, quotes: Map<String, Quote>, periods: List<String>, period: String, top: Int, label: String): List<String> {
        val shown = rows(basket, scheme, quotes, period, top)
        return listOf(label, "", "", f2(weightSum(shown))) + periods.map { p -> s2(portfolioReturn(rows(basket, scheme, quotes, p, top))) } + s2(total(shown)?.first)
    }

    /** The share of the largest absolute contribution a bar fills (0..1): the contribution bars of the table. */
    fun barShare(contribution: Double?, rows: List<PerfRow>): Float {
        val hi = rows.mapNotNull { it.contribution?.let(::abs) }.maxOrNull() ?: return 0f
        if (contribution == null || !(hi > 0.0)) return 0f
        return (abs(contribution) / hi).coerceIn(0.0, 1.0).toFloat()
    }
}
