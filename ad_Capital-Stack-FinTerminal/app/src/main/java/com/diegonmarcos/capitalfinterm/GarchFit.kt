package com.diegonmarcos.capitalfinterm

import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * #1832 one asset's GARCH(1,1): sigma^2_t = omega + alpha (r_(t-1) - mu)^2 + beta sigma^2_(t-1) on daily log returns (decimals, not percent).
 * [fitted] false = the declared defaults (variance targeting) because the history was shorter than [GarchFitter.MIN_OBS].
 */
data class GarchFit(
    val omega: Double, val alpha: Double, val beta: Double, val mu: Double, val logLik: Double, val n: Int,
    val from: LocalDate?, val to: LocalDate?, val fitted: Boolean,
    /** "fitted", "capped" (persistence at its cap), "targeted" (omega from the realised variance: the fit failed the sanity gate) or "defaults". */
    val basis: String = if (fitted) "fitted" else "defaults",
    /** Returns dropped as bad prints (splits, a jump reversed the next day) before the fit. */
    val dropped: Int = 0,
    /** The annualised volatility of the returns kept, percent (what the long-run vol is gated against). */
    val realisedVol: Double = Double.NaN,
) {
    val persistence: Double get() = alpha + beta
    /** Days for a variance shock to halve: ln(1/2) / ln(alpha + beta); infinite when the persistence is 0 (no memory) is reported as 0. */
    val halfLife: Double get() = if (persistence <= 0.0) 0.0 else ln(0.5) / ln(persistence)
    val longRunVar: Double get() = omega / (1.0 - persistence)
    fun longRunVol(annual: Int): Double = sqrt(longRunVar * annual) * 100

    /** The one-step-ahead conditional variance after filtering [r] (log returns, decimals), seeded with their sample variance. */
    fun lastVariance(r: List<Double>): Double {
        var v = GarchFitter.sampleVar(r, mu)
        for (x in r) { val e = x - mu; v = omega + alpha * e * e + beta * v }
        return v
    }

    /** The GARCH volatility forecast of [r], annualised, percent. */
    fun vol(r: List<Double>, annual: Int): Double = sqrt(lastVariance(r) * annual) * 100
}

/**
 * #1832 the maximum-likelihood fit of GARCH(1,1) to an asset's own daily log returns. Gaussian quasi-likelihood; parameters reparametrised so the
 * constraints hold by construction (omega = exp(c) > 0; alpha + beta = 0.9999 sigmoid(a) < 1; alpha = share x persistence, beta = the rest, both >= 0);
 * Nelder-Mead from several fixed starts, then a restart from the best: no randomness, so the same closes give the same parameters. Fits are cached by a
 * hash of the data, so a refit happens only when the history changes.
 */
object GarchFitter {
    /** Fewer log returns than this: the defaults are used and the asset is marked as such. */
    const val MIN_OBS = 500
    const val MAX_PERSISTENCE = 0.995
    /** A return beyond this (log) is a bad print or a split on a fund. */
    const val MAX_ABS_RETURN = 0.25
    /** A jump beyond this that the next day reverses (the two sum to under a quarter of it) is a bad print. */
    const val JUMP = 0.10
    /** The long-run vol must stay within [1/GATE, GATE] x the realised vol, else the omega is variance-targeted. */
    const val GATE = 2.0

    /** How many optimiser runs happened (a cache hit does not count). */
    @Volatile var fits: Int = 0; private set
    private val cache = ConcurrentHashMap<String, GarchFit>()

    /** Trading days a year the realised vol is annualised with: the daily vol times sqrt(ANNUAL). */
    const val ANNUAL = 252

    fun clearCache() { cache.clear() }

    fun sampleVar(r: List<Double>, mu: Double): Double = if (r.isEmpty()) 0.0 else r.sumOf { (it - mu) * (it - mu) } / r.size

    fun logReturns(closes: List<Double>): List<Double> = closes.zipWithNext { a, b -> ln(b / a) }

    /** Which log returns of [closes] are bad prints: |r| > [MAX_ABS_RETURN], or a jump over [JUMP] that the next day reverses (both legs). */
    fun badMask(closes: List<Double>): BooleanArray {
        val r = logReturns(closes)
        val bad = BooleanArray(r.size)
        for (i in r.indices) {
            if (Math.abs(r[i]) > MAX_ABS_RETURN) bad[i] = true
            if (i + 1 < r.size && Math.abs(r[i]) > JUMP && r[i] * r[i + 1] < 0 && Math.abs(r[i] + r[i + 1]) < 0.25 * Math.abs(r[i])) { bad[i] = true; bad[i + 1] = true }
        }
        return bad
    }

    /** The log returns of [closes] without the bad prints, and how many were dropped. */
    fun cleanReturns(closes: List<Double>): Pair<List<Double>, Int> {
        val r = logReturns(closes); val bad = badMask(closes)
        return r.filterIndexed { i, _ -> !bad[i] } to bad.count { it }
    }

    /** A hash of the closes (dates and values) and the defaults: the cache key. */
    fun dataHash(closes: List<Pair<LocalDate, Double>>, alpha: Double, beta: Double, minObs: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$alpha|$beta|$minObs|".toByteArray())
        for ((d, c) in closes) md.update("$d=${java.lang.Double.doubleToLongBits(c)};".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The fit of an asset's own [closes] (oldest first); the defaults [defAlpha]/[defBeta] with variance targeting when there are fewer than [minObs] returns. */
    fun fit(closes: List<Pair<LocalDate, Double>>, defAlpha: Double, defBeta: Double, minObs: Int = MIN_OBS): GarchFit =
        cache.getOrPut(dataHash(closes, defAlpha, defBeta, minObs)) { compute(closes, defAlpha, defBeta, minObs) }

    private fun compute(closes: List<Pair<LocalDate, Double>>, defAlpha: Double, defBeta: Double, minObs: Int): GarchFit {
        val (r, dropped) = cleanReturns(closes.map { it.second })
        val from = closes.firstOrNull()?.first; val to = closes.lastOrNull()?.first
        val mu = if (r.isEmpty()) 0.0 else r.sum() / r.size
        if (r.size < minObs || sampleVar(r, mu) <= 0.0) {
            val lr = sampleVar(r, mu)
            val omega = (1 - defAlpha - defBeta) * lr
            val ll = if (lr > 0) logLik(r.map { (it - mu) * 100 }, omega * 1e4, defAlpha, defBeta) + r.size * ln(100.0) else Double.NaN
            return GarchFit(omega, defAlpha, defBeta, mu, ll, r.size, from, to, false, "defaults", dropped, sqrt(lr * ANNUAL) * 100)
        }
        fits++
        val y = r.map { (it - mu) * 100 }   // percent, demeaned: the optimiser works near unit scale
        val s2 = y.sumOf { it * it } / y.size
        fun unpack(x: DoubleArray): Triple<Double, Double, Double> {
            val pers = MAX_PERSISTENCE / (1 + exp(-x[0].coerceIn(-30.0, 30.0)))
            val share = 1 / (1 + exp(-x[1].coerceIn(-30.0, 30.0)))
            return Triple(exp(x[2].coerceIn(-25.0, 15.0)), pers * share, pers * (1 - share))
        }
        fun nll(x: DoubleArray): Double {
            val (o, a, b) = unpack(x)
            val l = logLik(y, o, a, b)
            return if (l.isFinite()) -l else 1e30
        }
        fun logit(p: Double) = ln(p / (1 - p))
        val starts = listOf(0.90 to 0.10, 0.97 to 0.05, 0.80 to 0.30, 0.99 to 0.08, 0.60 to 0.40).map { (pers, share) ->
            doubleArrayOf(logit(pers / MAX_PERSISTENCE), logit(share), ln((1 - pers) * s2))
        }
        var best: DoubleArray? = null; var bestF = Double.POSITIVE_INFINITY
        for (s in starts) {
            var x = nelderMead(::nll, s, 0.4, 800)
            x = nelderMead(::nll, x, 0.1, 800)
            val f = nll(x)
            if (f < bestF) { bestF = f; best = x }
        }
        val (o0, a, b) = unpack(best!!)
        var o = o0
        var basis = if (a + b >= MAX_PERSISTENCE * 0.999) "capped" else "fitted"
        // sanity gate: the long-run vol against the realised vol of the same returns (variance in percent^2, the same annualisation for both)
        gate(o, a, b, s2)?.let { o = it; basis = "targeted" }
        val ll = logLik(y, o, a, b)
        return GarchFit(o * 1e-4, a, b, mu, ll + r.size * ln(100.0), r.size, from, to, true, basis, dropped, sqrt(s2 * 1e-4 * ANNUAL) * 100)
    }

    /** The variance-targeted omega ((1 - alpha - beta) x [s2]) when the long-run variance omega / (1 - alpha - beta) is outside [1/GATE, GATE] x the realised in vol terms; null = it passes. */
    fun gate(omega: Double, alpha: Double, beta: Double, s2: Double): Double? {
        val longRun = sqrt(omega / (1 - alpha - beta)); val real = sqrt(s2)
        return if (longRun < real / GATE || longRun > real * GATE) (1 - alpha - beta) * s2 else null
    }

    /** The Gaussian log-likelihood of [y] under GARCH(1,1), the variance seeded with the sample variance. */
    fun logLik(y: List<Double>, omega: Double, alpha: Double, beta: Double): Double {
        var v = y.sumOf { it * it } / y.size
        var ll = 0.0
        for (x in y) {
            if (!(v > 0)) return Double.NaN
            ll -= 0.5 * (ln(2 * Math.PI) + ln(v) + x * x / v)
            v = omega + alpha * x * x + beta * v
        }
        return ll
    }

    /** Nelder-Mead (adaptive-free textbook coefficients) minimising [f] from [x0] with an initial simplex step [step]. */
    fun nelderMead(f: (DoubleArray) -> Double, x0: DoubleArray, step: Double, maxIter: Int): DoubleArray {
        val n = x0.size
        val pts = Array(n + 1) { i -> x0.copyOf().also { if (i > 0) it[i - 1] += step } }
        val fv = DoubleArray(n + 1) { f(pts[it]) }
        fun order() {
            val idx = (0..n).sortedBy { fv[it] }
            val p2 = idx.map { pts[it] }; val f2 = idx.map { fv[it] }
            for (i in 0..n) { pts[i] = p2[i]; fv[i] = f2[i] }
        }
        repeat(maxIter) {
            order()
            if (Math.abs(fv[n] - fv[0]) < 1e-12 * (1 + Math.abs(fv[0]))) return pts[0]
            val c = DoubleArray(n) { j -> (0 until n).sumOf { pts[it][j] } / n }
            fun at(t: Double) = DoubleArray(n) { c[it] + t * (pts[n][it] - c[it]) }
            val xr = at(-1.0); val fr = f(xr)
            if (fr < fv[0]) {
                val xe = at(-2.0); val fe = f(xe)
                if (fe < fr) { pts[n] = xe; fv[n] = fe } else { pts[n] = xr; fv[n] = fr }
            } else if (fr < fv[n - 1]) { pts[n] = xr; fv[n] = fr }
            else {
                val xc = if (fr < fv[n]) at(-0.5) else at(0.5); val fc = f(xc)
                if (fc < minOf(fr, fv[n])) { pts[n] = xc; fv[n] = fc }
                else { for (i in 1..n) { pts[i] = DoubleArray(n) { j -> pts[0][j] + 0.5 * (pts[i][j] - pts[0][j]) }; fv[i] = f(pts[i]) } }
            }
        }
        order()
        return pts[0]
    }
}
