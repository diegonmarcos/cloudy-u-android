package com.diegonmarcos.capitalfinterm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import org.json.JSONObject

/** Daily closes of one instrument: dates ascending and the close of each. */
data class PriceSeries(val dates: List<LocalDate>, val closes: List<Double>)

/** The computed performance of one portfolio: [values] by window id (null = n/a), the realised 1y volatility in %, the as-of date; or [na] the reason it could not be computed. */
data class PmResult(val asOf: LocalDate?, val values: Map<String, Double?>, val vol1y: Double?, val na: String?)

/**
 * #1539 the performance of the PM portfolios from REAL delayed daily closes of their legs' proxies: the weighted portfolio value path rebalanced by the declared rule (default monthly),
 * then simple returns for the windows (Today, Last 7d, Last 30d, MTD, YTD, Last 1y) and the annualised CAGR for the Last 3y. A window the history does not reach, or a leg with no
 * history, is null (n/a): nothing is estimated. scripts/gen-pm-performance.py holds the same math for the baked snapshot (the shared fixture proves they agree).
 */
object PmPerformance {
    /** Yahoo chart answer -> dates (exchange-local day of each bar) and closes; null without bars. */
    fun parseHistory(text: String): PriceSeries? {
        val r = runCatching { JSONObject(text).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) }.getOrNull() ?: return null
        val ts = r.optJSONArray("timestamp") ?: return null
        val cs = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return null
        val off = r.optJSONObject("meta")?.optLong("gmtoffset") ?: 0L
        val pts = (0 until minOf(ts.length(), cs.length())).filter { !cs.isNull(it) }.map { Instant.ofEpochSecond(ts.getLong(it) + off).atZone(ZoneOffset.UTC).toLocalDate() to cs.getDouble(it) }
        val dedup = pts.groupBy { it.first }.map { (d, v) -> d to v.last().second }.sortedBy { it.first }
        return if (dedup.isEmpty()) null else PriceSeries(dedup.map { it.first }, dedup.map { it.second })
    }

    /**
     * The portfolio value path (start 1.0) over the dates every leg has a close on. [legs] are (weight in percent, series). [rule]: daily = back to the target weights at every close,
     * monthly = at the first close of each calendar month, none = buy and hold. Null when a leg has no history, the weights do not add to 100, or fewer than two common dates exist.
     */
    fun path(legs: List<Pair<Double, PriceSeries?>>, rule: String): List<Pair<LocalDate, Double>>? {
        if (legs.isEmpty() || legs.any { it.second == null }) return null
        if (Math.abs(legs.sumOf { it.first } - 100.0) > 1e-6) return null
        val maps = legs.map { (_, s) -> s!!.dates.zip(s.closes).toMap() }
        val dates = maps.map { it.keys }.reduce { a, b -> a.intersect(b) }.sorted()
        if (dates.size < 2 || maps.any { m -> dates.any { (m[it] ?: 0.0) <= 0.0 } }) return null
        val w = legs.map { it.first / 100.0 }
        var units = w.indices.map { w[it] / maps[it].getValue(dates[0]) }
        val out = ArrayList<Pair<LocalDate, Double>>(); out += dates[0] to 1.0
        for (t in 1 until dates.size) {
            val v = units.indices.sumOf { units[it] * maps[it].getValue(dates[t]) }
            out += dates[t] to v
            val reb = when (rule) { "daily" -> true; "monthly" -> dates[t].month != dates[t - 1].month; else -> false }
            if (reb) units = w.indices.map { v * w[it] / maps[it].getValue(dates[t]) }
        }
        return out
    }

    /** Return of the window ending at the last point: simple (end / base - 1) or, for a CAGR window, (end / base)^(1/years) - 1. Null when the history does not reach the base date. */
    fun windows(path: List<Pair<LocalDate, Double>>, ws: List<PmWindow>): Map<String, Double?> {
        val (asOf, last) = path.last()
        fun before(d: LocalDate) = path.lastOrNull { it.first.isBefore(d) }?.second
        fun onOrBefore(d: LocalDate) = if (path.first().first.isAfter(d)) null else path.lastOrNull { !it.first.isAfter(d) }?.second
        return ws.associate { w ->
            val base = when {
                w.id == "today" -> path.getOrNull(path.size - 2)?.second
                w.id == "mtd" -> before(asOf.withDayOfMonth(1))
                w.id == "ytd" -> before(asOf.withDayOfYear(1))
                w.days > 0 -> onOrBefore(asOf.minusDays(w.days.toLong()))
                w.years > 0 -> onOrBefore(asOf.minusYears(w.years.toLong()))
                else -> null
            }
            w.id to (if (w.cagr) Performance.cagr(base, last, w.years.toDouble()) else Performance.simple(base, last))
        }
    }

    /** Realised volatility of the last year: the standard deviation of the daily returns of the last 252 closes, annualised by sqrt(252), in %; null under 200 returns. */
    fun vol1y(path: List<Pair<LocalDate, Double>>): Double? {
        val p = path.takeLast(253).map { it.second }
        val r = p.zipWithNext { a, b -> b / a - 1.0 }
        if (r.size < 200) return null
        val m = r.average()
        return sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1)) * sqrt(252.0) * 100.0
    }

    fun compute(p: PmPortfolio, hist: Map<String, PriceSeries?>, decl: PmPerfDecl): PmResult {
        p.noHistory?.let { return PmResult(null, emptyMap(), null, it) }
        if (p.legs.isEmpty()) return PmResult(null, emptyMap(), null, decl.word("na_long"))
        val path = path(p.legs.map { it.weight to it.symbol?.let { s -> hist[s] } }, decl.rebalance) ?: return PmResult(null, emptyMap(), null, decl.word("na_long"))
        return PmResult(path.last().first, windows(path, decl.windows), vol1y(path), null)
    }

    fun format(v: Double?): String = if (v == null || !v.isFinite()) "" else String.format(Locale.ROOT, "%+.2f", v * 100.0)
}

/** Where the Performance page reads its numbers: the baked snapshot (data/pm-performance.json), then what the last refresh computed from fresh closes (the later as-of wins). A refresh runs when the page opens or is pulled; a symbol asked within the TTL is not asked again. */
object PmPerfStore {
    const val SNAPSHOT = "pm-performance.json"
    private val live = ConcurrentHashMap<String, PmResult>()
    private val asked = ConcurrentHashMap<String, Long>()
    private val hist = ConcurrentHashMap<String, PriceSeries?>()

    fun snapshot(json: String, decl: PmPerfDecl): Map<String, PmResult> {
        val r = JSONObject(json).getJSONObject("portfolios")
        return r.keys().asSequence().associateWith { id ->
            val o = r.getJSONObject(id)
            if (o.has("na")) PmResult(null, emptyMap(), null, o.getString("na"))
            else PmResult(LocalDate.parse(o.getString("asof")), decl.windows.associate { w -> w.id to (if (o.getJSONObject("values").isNull(w.id)) null else o.getJSONObject("values").getDouble(w.id)) }, if (o.isNull("vol1y")) null else o.getDouble("vol1y"), null)
        }
    }

    fun merged(baked: Map<String, PmResult>): Map<String, PmResult> = baked + live.filter { (k, v) -> v.asOf != null && (baked[k]?.asOf?.let { v.asOf >= it } ?: true) }

    /** Fetches the closes of every proxy the portfolios need (within the TTL once), computes each portfolio and keeps the results; returns how many portfolios have numbers. Blocking. */
    fun refresh(decl: PmPerfDecl, portfolios: List<PmPortfolio>, force: Boolean, http: Http = UrlHttp(maxBytes = 8_000_000), now: () -> Long = System::currentTimeMillis): Int {
        val t = now()
        val symbols = portfolios.flatMap { it.legs }.mapNotNull { it.symbol }.distinct().filter { force || t - (asked[it] ?: 0L) >= decl.ttlSeconds * 1000L }
        val pool = Executors.newFixedThreadPool(6)
        symbols.forEach { s ->
            pool.execute {
                val url = decl.historyUrl.replace("{symbol}", java.net.URLEncoder.encode(s, "UTF-8"))
                val series = runCatching { PmPerformance.parseHistory(http.send(url, mapOf("User-Agent" to decl.userAgent), null)) }.getOrNull()
                asked[s] = t
                if (series != null) hist[s] = series
            }
        }
        pool.shutdown(); pool.awaitTermination(3, TimeUnit.MINUTES)
        var n = 0
        for (p in portfolios) { val r = PmPerformance.compute(p, hist, decl); if (r.asOf != null) { live[p.id] = r; n++ } }
        return n
    }
}
