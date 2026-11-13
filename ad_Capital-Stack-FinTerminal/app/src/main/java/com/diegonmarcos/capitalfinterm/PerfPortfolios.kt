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
    /** #1727 the fund(s) the portfolio is meant to follow when the generator reads another fund of the same index or factor ("" = the same). */
    val intended: String = "",
) {
    fun scheme(id: String): PerfScheme? = schemes.firstOrNull { it.id == id }
}

/** The declaration data/portfolios.json: the portfolios, the periods, the two modes and the words of the page. Nothing here is a weight or a figure. */
data class PerfDecl(
    val dataset: String, val periods: List<String>, val defaultPeriod: String, val modes: List<String>, val defaultMode: String, val cardTop: Int,
    val tops: List<Int>, val defaultTop: Int, val bubbleX: String, val bubbleY: String, val groups: List<PerfGroup>, val ui: Map<String, String>,
    /** #1727 the flow panel: how many buckets and how many constituents it draws before folding the rest. */
    val sankeyBuckets: Int = 6, val sankeyHoldings: Int = 10,
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
    /**
     * #1727 a sovereign ([PerfPortfolios.BASIS_SOV]): [fx] is the symbol of USD per the country's currency ("" = a dollar country) and [proxyCcy] says what
     * the bond fund [symbol] is priced in: "local" (its move is the bond return in local currency) or "usd" (its move is already the dollar return).
     */
    val fx: String = "", val proxyCcy: String = "",
)

/**
 * #1727 a sovereign's figure in its three parts, in %: the bond return in local currency, the currency against the dollar, the unhedged dollar return;
 * [approx] when the bond return is the yield and duration approximation of the sovereign-returns source instead of a listed fund's move.
 */
data class PerfParts(val bond: Double?, val fx: Double?, val usd: Double?, val approx: Boolean)

/** One node of the flow panel: its label, its weight in %, its column (0 the portfolio, 1 a bucket, 2 a constituent) and where it sits on the 0..1 height. */
data class FlowNode(val label: String, val value: Double, val col: Int, val y0: Double, val y1: Double, val row: PerfRow? = null)

/** One ribbon of the flow panel: from node [from] to node [to], its weight, and the span it takes on each of the two nodes (0..1 height). */
data class FlowLink(val from: Int, val to: Int, val value: Double, val sy0: Double, val sy1: Double, val ty0: Double, val ty1: Double)

data class Flow(val nodes: List<FlowNode>, val links: List<FlowLink>)

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
    /** #1727 a sovereign: the dollar return of the country's government bonds (a listed fund, else the sovereign-returns source), with its currency leg. */
    const val BASIS_SOV = "sov"
    const val PROXY_USD = "usd"

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
                intended = g.optString("intended"),
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
            sankeyBuckets = o.optJSONObject("sankey")?.optInt("buckets", 6) ?: 6, sankeyHoldings = o.optJSONObject("sankey")?.optInt("holdings", 10) ?: 10,
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
                else PerfConstituent(t, c.optString("symbol"), c.optString("name").ifBlank { t }, c.optString("bucket"), ws, c.optString("basis"), c.optString("proxy"), c.optString("note"), c.optString("fx"), c.optString("proxy_ccy"))
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

    /** The move of a currency against the dollar over [period] in %: the inverse of the move of [symbol] (USD per currency); 0 for a dollar country (no symbol). */
    fun fxMove(symbol: String, quotes: Map<String, Quote>, period: String): Double? {
        if (symbol.isBlank()) return 0.0
        val r = IndexHoldings.perf(quotes[symbol], period) ?: return null
        val f = 1.0 + r / 100.0
        return if (f > 0.0) (1.0 / f - 1.0) * 100.0 else null
    }

    private fun compound(a: Double, b: Double): Double = ((1.0 + a / 100.0) * (1.0 + b / 100.0) - 1.0) * 100.0
    private fun strip(total: Double, part: Double): Double? = (1.0 + part / 100.0).let { if (it > 0.0) ((1.0 + total / 100.0) / it - 1.0) * 100.0 else null }

    /**
     * #1727 a sovereign's figure over [period]: the bond return of its listed fund when the quote source answered (a fund priced in the local currency
     * gives the local return, the dollar return is that compounded with the currency's move; a fund priced in dollars gives the dollar return, the
     * local return is that with the currency's move taken out); else the local return of the sovereign-returns source ([sov]: country to period to %),
     * flagged approximate. Each part is null when what it needs did not answer.
     */
    fun parts(c: PerfConstituent, quotes: Map<String, Quote>, period: String, sov: Map<String, Map<String, Double>> = emptyMap()): PerfParts {
        val fx = fxMove(c.fx, quotes, period)
        val fund = if (c.symbol.isBlank()) null else IndexHoldings.perf(quotes[c.symbol], period)
        if (fund != null) {
            return if (c.proxyCcy == PROXY_USD) PerfParts(fx?.let { strip(fund, it) }, fx, fund, false) else PerfParts(fund, fx, fx?.let { compound(fund, it) }, false)
        }
        val local = sov[c.ticker]?.get(period)?.takeIf { it.isFinite() } ?: return PerfParts(null, fx, null, false)
        return PerfParts(local, fx, fx?.let { compound(local, it) }, true)
    }

    /**
     * A constituent's performance over [period] in %: its instrument's move; for a currency ([BASIS_FX], the symbol is USD per currency) the currency's move
     * against the dollar, the inverse of the rate's; 0 for a dollar country; for a sovereign ([BASIS_SOV]) the dollar return of [parts]; null when it has
     * no listed instrument or the source did not answer.
     */
    fun perf(c: PerfConstituent, quotes: Map<String, Quote>, period: String, sov: Map<String, Map<String, Double>> = emptyMap()): Double? {
        if (c.basis == BASIS_SOV) return parts(c, quotes, period, sov).usd
        if (c.basis == BASIS_USD) return 0.0
        if (c.symbol.isBlank()) return null
        val r = IndexHoldings.perf(quotes[c.symbol], period) ?: return null
        if (c.basis != BASIS_FX) return r
        val f = 1.0 + r / 100.0
        return if (f > 0.0) (1.0 / f - 1.0) * 100.0 else null
    }

    /** The constituents that carry a weight under [scheme], largest first (the [top] largest; every one when [top] <= 0), with performance and contribution. */
    fun rows(basket: PerfBasket, scheme: String, quotes: Map<String, Quote>, period: String, top: Int, sov: Map<String, Map<String, Double>> = emptyMap()): List<PerfRow> {
        val all = basket.constituents.mapNotNull { c -> c.weights[scheme]?.takeIf { it > 0.0 }?.let { c to it } }.sortedByDescending { it.second }
        val shown = if (top <= 0) all else all.take(top)
        return shown.map { (c, w) -> perf(c, quotes, period, sov).let { PerfRow(c, w, it, IndexHoldings.contribution(w, it)) } }
    }

    /** True when the portfolio's constituents are sovereigns: its table shows the bond and the currency leg beside the dollar return. */
    fun isSovereign(basket: PerfBasket): Boolean = basket.constituents.any { it.basis == BASIS_SOV }

    /** The symbols the quote source is asked for [rows]: every listed instrument once. */
    fun symbols(rows: List<PerfRow>): List<String> = rows.flatMap { listOf(it.c.symbol, it.c.fx) }.filter { it.isNotBlank() }.distinct()

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
    fun bubbles(basket: PerfBasket, scheme: String, quotes: Map<String, Quote>, xPeriod: String, yPeriod: String, top: Int, sov: Map<String, Map<String, Double>> = emptyMap()): List<PerfBubble> =
        rows(basket, scheme, quotes, xPeriod, top, sov).mapNotNull { r ->
            val x = r.perf ?: return@mapNotNull null
            val y = perf(r.c, quotes, yPeriod, sov) ?: return@mapNotNull null
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
    fun cells(rows: List<PerfRow>, quotes: Map<String, Quote>, periods: List<String>, sov: Map<String, Map<String, Double>> = emptyMap()): List<List<String>> =
        rows.map { r -> listOf(r.c.ticker, r.c.name, r.c.bucket, f2(r.weight)) + periods.map { s2(perf(r.c, quotes, it, sov)) } + s2(r.contribution) }

    /** The totals line: the shown weight, the portfolio's return per period (the weighted mean of the quoted constituents) and the summed contribution. */
    fun totals(basket: PerfBasket, scheme: String, quotes: Map<String, Quote>, periods: List<String>, period: String, top: Int, label: String, sov: Map<String, Map<String, Double>> = emptyMap()): List<String> {
        val shown = rows(basket, scheme, quotes, period, top, sov)
        return listOf(label, "", "", f2(weightSum(shown))) + periods.map { p -> s2(portfolioReturn(rows(basket, scheme, quotes, p, top, sov))) } + s2(total(shown)?.first)
    }

    /** The share of the largest absolute contribution a bar fills (0..1): the contribution bars of the table. */
    fun barShare(contribution: Double?, rows: List<PerfRow>): Float {
        val hi = rows.mapNotNull { it.contribution?.let(::abs) }.maxOrNull() ?: return 0f
        if (contribution == null || !(hi > 0.0)) return 0f
        return (abs(contribution) / hi).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * #1727 the flow panel (the reference's sankey): the portfolio into its buckets, each bucket into the portfolio's largest constituents. Column 0 is the
     * portfolio (the shown weight), column 1 the [maxBuckets] heaviest buckets with the others folded into [otherLabel], column 2 the [maxHoldings] largest
     * constituents (in the order of their buckets) and one [restLabel] node for everything else. A node's height is its weight; [gap] of the height
     * separates the nodes of a column; a ribbon keeps its weight on both ends, so what leaves a node is what the node weighs. Empty for no row.
     */
    fun flow(rows: List<PerfRow>, maxBuckets: Int, maxHoldings: Int, portfolioLabel: String, otherLabel: String, restLabel: String, gap: Double = 0.012): Flow {
        val total = rows.sumOf { it.weight }
        if (rows.isEmpty() || !(total > 0.0)) return Flow(emptyList(), emptyList())
        val byBucket = rows.groupBy { it.c.bucket.ifBlank { otherLabel } }.map { (k, v) -> k to v }.sortedByDescending { (_, v) -> v.sumOf { it.weight } }
        val keep = byBucket.take(max(1, maxBuckets)).filter { it.first != otherLabel }
        val folded = byBucket.filter { b -> keep.none { it.first == b.first } }.flatMap { it.second }
        val buckets = if (folded.isEmpty()) keep else keep + (otherLabel to folded)
        val top = rows.sortedByDescending { it.weight }.take(max(1, maxHoldings)).toSet()
        val nodes = ArrayList<FlowNode>()
        val links = ArrayList<FlowLink>()
        nodes += FlowNode(portfolioLabel, total, 0, 0.0, 1.0)
        fun stack(values: List<Double>): List<Pair<Double, Double>> {
            val usable = 1.0 - gap * max(0, values.size - 1)
            var at = 0.0
            return values.map { v -> val h = v / total * usable; (at to at + h).also { at += h + gap } }
        }
        val bucketSpans = stack(buckets.map { (_, v) -> v.sumOf { it.weight } })
        val bucketIdx = buckets.mapIndexed { i, (k, v) -> nodes.size.also { nodes += FlowNode(k, v.sumOf { it.weight }, 1, bucketSpans[i].first, bucketSpans[i].second) } }
        // portfolio -> bucket: the portfolio's side is stacked without gaps, in the buckets' order
        var from = 0.0
        buckets.forEachIndexed { i, (_, v) ->
            val h = v.sumOf { it.weight } / total
            links += FlowLink(0, bucketIdx[i], v.sumOf { it.weight }, from, from + h, bucketSpans[i].first, bucketSpans[i].second)
            from += h
        }
        val shown = buckets.flatMap { (_, v) -> v.filter { it in top }.sortedByDescending { it.weight } }
        val rest = total - shown.sumOf { it.weight }
        val leafSpans = stack(shown.map { it.weight } + (if (rest > 1e-9) listOf(rest) else emptyList()))
        val leafIdx = shown.mapIndexed { i, r -> nodes.size.also { nodes += FlowNode(r.c.ticker, r.weight, 2, leafSpans[i].first, leafSpans[i].second, r) } }
        val restIdx = if (rest > 1e-9) nodes.size.also { nodes += FlowNode(restLabel, rest, 2, leafSpans.last().first, leafSpans.last().second) } else -1
        var restAt = if (restIdx >= 0) leafSpans.last().first else 0.0
        buckets.forEachIndexed { i, (_, v) ->
            val (b0, b1) = bucketSpans[i]
            val bw = v.sumOf { it.weight }
            var at = b0
            for (r in v.filter { it in top }.sortedByDescending { it.weight }) {
                val h = (b1 - b0) * r.weight / bw
                val k = shown.indexOf(r)
                links += FlowLink(bucketIdx[i], leafIdx[k], r.weight, at, at + h, leafSpans[k].first, leafSpans[k].second)
                at += h
            }
            val left = bw - v.filter { it in top }.sumOf { it.weight }
            if (left > 1e-9 && restIdx >= 0) {
                val h = (b1 - b0) * left / bw
                val th = (leafSpans.last().second - leafSpans.last().first) * left / rest
                links += FlowLink(bucketIdx[i], restIdx, left, at, at + h, restAt, restAt + th)
                restAt += th
            }
        }
        return Flow(nodes, links)
    }
}
