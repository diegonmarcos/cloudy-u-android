package com.diegonmarcos.capitalfinterm

/**
 * Forward rates derived from a yield curve. The curve is a list of (maturity in years, yield in percent) sorted by maturity; yields are
 * treated as annually compounded spot rates, linearly interpolated between maturities and flat outside them. The forward rate from
 * [start] for [length] years is f = ((1+y(start+length))^(start+length) / (1+y(start))^start)^(1/length) - 1.
 */
object Forward {
    /** Yield at maturity [t] (years), percent; flat before the first and after the last point. NaN for an empty curve. */
    fun yieldAt(curve: List<Pair<Double, Double>>, t: Double): Double {
        if (curve.isEmpty()) return Double.NaN
        if (t <= curve.first().first) return curve.first().second
        if (t >= curve.last().first) return curve.last().second
        for (i in 1 until curve.size) {
            val (t1, y1) = curve[i - 1]
            val (t2, y2) = curve[i]
            if (t <= t2) return if (t2 == t1) y2 else y1 + (y2 - y1) * (t - t1) / (t2 - t1)
        }
        return curve.last().second
    }

    /** The forward rate, percent, starting [start] years from now for [length] years; NaN when the curve is empty or length <= 0. */
    fun rate(curve: List<Pair<Double, Double>>, start: Double, length: Double): Double {
        if (curve.isEmpty() || length <= 0.0 || start < 0.0) return Double.NaN
        val end = start + length
        val grownEnd = Math.pow(1.0 + yieldAt(curve, end) / 100.0, end)
        val grownStart = if (start == 0.0) 1.0 else Math.pow(1.0 + yieldAt(curve, start) / 100.0, start)
        return (Math.pow(grownEnd / grownStart, 1.0 / length) - 1.0) * 100.0
    }

    /** Spec 9 forward table: per curve tenor, (tenor, spot, the forward starting in [start] years over the same tenor). */
    fun table(curve: List<Pair<Double, Double>>, start: Double): List<Triple<Double, Double, Double>> =
        curve.map { (t, y) -> Triple(t, y, rate(curve, start, t)) }

    /** The [length]-year forward rate at each whole start year 0..[maxStart], as (start, rate percent). */
    fun curve(curve: List<Pair<Double, Double>>, length: Double, maxStart: Int): List<Pair<Double, Double>> =
        (0..maxStart).map { it.toDouble() to rate(curve, it.toDouble(), length) }.filter { it.second.isFinite() }
}
