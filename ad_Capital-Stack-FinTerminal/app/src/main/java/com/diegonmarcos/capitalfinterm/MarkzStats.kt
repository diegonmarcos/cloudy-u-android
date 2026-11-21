package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.pow
import kotlin.math.sqrt

/** #1832 one long-only portfolio of the optimiser: its weights (fractions, the assets' order), E(Rp) and sigma_p in percent, its Sharpe ratio. */
data class MarkzPort(val w: List<Double>, val er: Double, val vol: Double, val sharpe: Double)

/** #1832 what the optimiser found: the minimum variance, the maximum Sharpe, the frontier (minimum variance at each return level) and the target's portfolio. */
data class MarkzOpt(val mvp: MarkzPort, val maxSharpe: MarkzPort, val frontier: List<MarkzPort>, val match: MarkzPort?, val lo: Double, val hi: Double)

/** #1832 the volatilities of one series under each estimator (percent a year; implied = null when no volatility index is declared or it did not answer). */
data class MarkzVols(val hist: Double, val ewma: Double, val garch: Double, val implied: Double?, val fit: GarchFit? = null) {
    fun of(model: String): Double? = when (model) { "ewma" -> ewma; "garch" -> garch; "implied" -> implied; else -> hist }
}

/** #1832 everything the five substeps show, computed once from the closes read: per series (the assets, then the benchmark) returns, vols, correlations. */
data class MarkzStatsResult(
    val ids: List<String>, val days: Int, val lastDay: String, val returns: List<List<Double>>, val vols: List<MarkzVols>, val corr: List<List<Double>>,
    /** The benchmark's annualised price return over every common day read (the default E(Rm)), percent. */
    val benchmarkAnnual: Double,
    /** Returns dropped as bad prints (in any series) before every estimator: one cleaned return series for all of them. */
    val dropped: Int = 0,
)

/**
 * #1832 the statistics of the Markovitz substeps (data/markz.json `stats`), pure: the closes of a quote-source answer, simple daily returns on the days every
 * series has, the four volatility estimators, Beta, CAPM, the correlation and covariance matrices, and a long-only grid optimiser for the frontier.
 */
object MarkzStats {
    /** A Yahoo chart answer (the quote source of data/quotes.json) -> its daily closes, one per UTC day (the last of a day wins), oldest first. */
    fun closes(chart: String): List<Pair<LocalDate, Double>> = runCatching {
        val r = JSONObject(chart).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = r.getJSONArray("timestamp")
        val cs = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
        val m = java.util.TreeMap<LocalDate, Double>()
        for (i in 0 until minOf(ts.length(), cs.length())) {
            if (cs.isNull(i)) continue
            val v = cs.getDouble(i)
            if (!v.isFinite() || v <= 0) continue
            m[Instant.ofEpochSecond(ts.getLong(i)).atZone(ZoneOffset.UTC).toLocalDate()] = v
        }
        m.entries.map { it.key to it.value }
    }.getOrDefault(emptyList())

    /** The closes of every series on the days all of them have, oldest first: (days, closes per series). */
    fun aligned(series: List<List<Pair<LocalDate, Double>>>): Pair<List<LocalDate>, List<List<Double>>> {
        if (series.isEmpty() || series.any { it.isEmpty() }) return emptyList<LocalDate>() to series.map { emptyList<Double>() }
        val maps = series.map { it.toMap() }
        val days = maps.map { it.keys }.reduce { a, b -> a.intersect(b) }.sorted()
        return days to maps.map { m -> days.map { m.getValue(it) } }
    }

    /** Simple returns of consecutive closes. */
    fun returns(closes: List<Double>): List<Double> = closes.zipWithNext { a, b -> b / a - 1.0 }

    fun mean(x: List<Double>): Double = if (x.isEmpty()) Double.NaN else x.sum() / x.size

    /** The sample covariance (n - 1); NaN with fewer than two pairs. */
    fun cov(a: List<Double>, b: List<Double>): Double {
        val n = minOf(a.size, b.size)
        if (n < 2) return Double.NaN
        val ma = mean(a.take(n)); val mb = mean(b.take(n))
        return (0 until n).sumOf { (a[it] - ma) * (b[it] - mb) } / (n - 1)
    }

    fun variance(a: List<Double>): Double = cov(a, a)

    fun corr(a: List<Double>, b: List<Double>): Double = cov(a, b) / sqrt(variance(a) * variance(b))

    /** Historical SD, annualised, percent. */
    fun histVol(r: List<Double>, annual: Int): Double = sqrt(variance(r) * annual) * 100

    /** RiskMetrics EWMA: sigma^2_t = lambda sigma^2_(t-1) + (1 - lambda) r^2_(t-1), seeded with the sample variance; the last, annualised, percent. */
    fun ewmaVol(r: List<Double>, lambda: Double, annual: Int): Double {
        var v = variance(r)
        for (x in r) v = lambda * v + (1 - lambda) * x * x
        return sqrt(v * annual) * 100
    }

    /** GARCH(1,1) with variance targeting: omega = (1 - alpha - beta) x the sample variance; the last conditional variance, annualised, percent. */
    fun garchVol(r: List<Double>, alpha: Double, beta: Double, annual: Int): Double {
        val lr = variance(r)
        val omega = (1 - alpha - beta) * lr
        var v = lr
        for (x in r) v = omega + alpha * x * x + beta * v
        return sqrt(v * annual) * 100
    }

    /** Beta of an asset on the market with the selected framework's sigmas: rho x sigma_i / sigma_m (= Cov / sigma_m^2). */
    fun beta(rho: Double, sigmaI: Double, sigmaM: Double): Double = rho * sigmaI / sigmaM

    /** CAPM: E(Ri) = Rf + beta (E(Rm) - Rf), percent. */
    fun capm(rf: Double, beta: Double, erm: Double): Double = rf + beta * (erm - rf)

    /** (last / first)^(annual / days) - 1, percent: the annualised return of [closes] over [days] trading days. */
    fun annualised(closes: List<Double>, annual: Int): Double {
        if (closes.size < 2) return Double.NaN
        val days = closes.size - 1
        return ((closes.last() / closes.first()).pow(annual.toDouble() / days) - 1) * 100
    }

    /** Sigma_ij = rho_ij sigma_i sigma_j (percent squared). */
    fun covMatrix(corr: List<List<Double>>, sigma: List<Double>): List<List<Double>> = corr.indices.map { i -> corr.indices.map { j -> corr[i][j] * sigma[i] * sigma[j] } }

    fun portfolio(w: List<Double>, mu: List<Double>, cov: List<List<Double>>, rf: Double): MarkzPort {
        val er = w.indices.sumOf { w[it] * mu[it] }
        val v = w.indices.sumOf { i -> w.indices.sumOf { j -> w[i] * w[j] * cov[i][j] } }
        val vol = sqrt(maxOf(0.0, v))
        return MarkzPort(w, er, vol, if (vol > 0) (er - rf) / vol else Double.NaN)
    }

    /** Every long-only weight vector of [n] assets on a [step] grid summing to 1. */
    fun grid(n: Int, step: Double): List<List<Double>> {
        val k = Math.round(1.0 / step).toInt()
        val out = ArrayList<List<Double>>()
        fun rec(prefix: List<Int>, left: Int) {
            if (prefix.size == n - 1) { out += (prefix + left).map { it.toDouble() / k }; return }
            for (i in 0..left) rec(prefix + i, left - i)
        }
        if (n == 1) return listOf(listOf(1.0))
        rec(emptyList(), k)
        return out
    }

    /**
     * The long-only optimiser: on the [step] grid, the minimum variance portfolio, the maximum Sharpe portfolio, the frontier (the least volatile
     * portfolio returning at least each of [points] levels from the minimum variance's return to the best asset's) and the least volatile portfolio
     * returning at least [target] (null when no portfolio reaches it).
     */
    fun optimise(mu: List<Double>, cov: List<List<Double>>, rf: Double, step: Double, points: Int, target: Double?): MarkzOpt {
        val all = grid(mu.size, step).map { portfolio(it, mu, cov, rf) }
        val mvp = all.minByOrNull { it.vol }!!
        val ms = all.filter { it.sharpe.isFinite() }.maxByOrNull { it.sharpe } ?: mvp
        val hi = mu.maxOrNull() ?: mvp.er
        val lo = mvp.er
        fun least(atLeast: Double): MarkzPort? = all.filter { it.er >= atLeast - 1e-9 }.minByOrNull { it.vol }
        val levels = if (points < 2 || hi <= lo) listOf(lo) else (0 until points).map { lo + (hi - lo) * it / (points - 1) }
        val frontier = levels.mapNotNull { least(it) }.distinct()
        return MarkzOpt(mvp, ms, frontier, target?.let { if (it > hi + 1e-9) null else least(it) }, lo, hi)
    }

    /**
     * The substeps' statistics from the closes of [ids] (the assets, then the benchmark) and the implied volatility indices' last closes ([implied],
     * percent, null = none): returns over the last [MarkzStatsDecl.windowDays] common days, the four volatilities, the correlation matrix, the
     * benchmark's annualised return over every common day. Null when fewer than three common days were read.
     */
    fun compute(decl: MarkzStatsDecl, ids: List<String>, closes: List<List<Pair<LocalDate, Double>>>, implied: List<Double?>): MarkzStatsResult? {
        val (days, cl) = aligned(closes)
        if (days.size < 3) return null
        // one cleaned series for every model: a return that is a bad print in any series is dropped from all of them (the series stay aligned)
        val masks = cl.map { GarchFitter.badMask(it) }
        val drop = BooleanArray(days.size - 1) { i -> masks.any { it[i] } }
        val win = cl.map { c -> returns(c).filterIndexed { i, _ -> !drop[i] }.takeLast(decl.windowDays) }
        val vols = win.mapIndexed { i, r ->
            // GARCH: the parameters fitted to the asset's own whole history (cached by data hash); the declared defaults on the window when it is too short
            val fit = GarchFitter.fit(closes[i], decl.alpha, decl.beta)
            val g = if (fit.fitted) fit.vol(GarchFitter.cleanReturns(closes[i].map { it.second }).first, decl.annualDays) else garchVol(r, decl.alpha, decl.beta, decl.annualDays)
            MarkzVols(histVol(r, decl.annualDays), ewmaVol(r, decl.lambda, decl.annualDays), g, implied.getOrNull(i), fit)
        }
        val corr = win.indices.map { i -> win.indices.map { j -> if (i == j) 1.0 else corr(win[i], win[j]) } }
        return MarkzStatsResult(ids, minOf(days.size - 1 - drop.count { it }, decl.windowDays), days.last().toString(), win, vols, corr, annualised(cl.last(), decl.annualDays), drop.count { it })
    }
}
