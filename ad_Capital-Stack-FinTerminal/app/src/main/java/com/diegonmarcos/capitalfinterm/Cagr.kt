package com.diegonmarcos.capitalfinterm

import java.time.LocalDate
import java.util.Locale

/**
 * #1786 the long-window periods of every performance table (EQ, FI, the Homepage boxes): the 12-month change and the annualised averages over 1, 12, 24
 * and 36 months as CAGR, (end / start)^(1 / years) - 1, never an arithmetic mean. years = months / 12, so the 1-month CAGR is the month's move
 * compounded over a year and the 12-month CAGR equals the 12-month change. The base of a window is the last close on or before the as-of date minus the
 * window; when the series starts after that date the window is null (shown "n/a"), never a value from a shorter history. Pure: no Android, no network.
 */
object Cagr {
    /** The period ids the tables declare (data/portfolios.json `periods`) for the windows of this object; the shorter ones (1D, 1W, 1M, YTD) are the quote's. */
    const val P12M = "12M"
    const val PCAGR_1M = "CAGR 1m"
    const val PCAGR_12M = "CAGR 12m"
    const val PCAGR_24M = "CAGR 24m"
    const val PCAGR_36M = "CAGR 36m"
    val LONG_PERIODS: List<String> = listOf(P12M, PCAGR_1M, PCAGR_12M, PCAGR_24M, PCAGR_36M)

    /** The months of each CAGR window by period id. */
    val CAGR_MONTHS: Map<String, Int> = linkedMapOf(PCAGR_1M to 1, PCAGR_12M to 12, PCAGR_24M to 24, PCAGR_36M to 36)

    /** What a window without enough history shows. */
    const val NA = "n/a"

    /** The 12-month change and the four CAGR windows of one series, in %; null = the history does not reach the window's base. */
    data class Windows(val y1: Double?, val c1m: Double?, val c12m: Double?, val c24m: Double?, val c36m: Double?)

    /** CAGR in %: ((end / start)^(1 / years) - 1) x 100; null when a price is missing, not positive or not finite, or [years] is not positive. */
    fun annualised(start: Double?, end: Double?, years: Double): Double? {
        if (start == null || end == null || !start.isFinite() || !end.isFinite() || start <= 0.0 || end <= 0.0 || !(years > 0.0)) return null
        return (Math.pow(end / start, 1.0 / years) - 1.0) * 100.0
    }

    /** Simple change in %: (end / start - 1) x 100; null on the same conditions as [annualised]. */
    fun change(start: Double?, end: Double?): Double? {
        if (start == null || end == null || !start.isFinite() || !end.isFinite() || start <= 0.0 || end <= 0.0) return null
        return (end / start - 1.0) * 100.0
    }

    /** The base close of a [months]-month window ending at [asOf]: the last close on or before asOf - months; null when [closes] start after that date. */
    fun base(closes: List<Pair<LocalDate, Double>>, asOf: LocalDate, months: Int): Double? {
        if (months <= 0) return null
        val s = closes.filter { it.second.isFinite() }.sortedBy { it.first }
        if (s.isEmpty()) return null
        val d = asOf.minusMonths(months.toLong())
        if (s.first().first.isAfter(d)) return null
        return s.lastOrNull { !it.first.isAfter(d) }?.second
    }

    /** The [months]-month CAGR in % of [closes] ending at [last] on [asOf]; null without enough history. */
    fun cagr(closes: List<Pair<LocalDate, Double>>, last: Double, asOf: LocalDate, months: Int): Double? =
        annualised(base(closes, asOf, months), last, months / 12.0)

    /** Every long window of one series: [last] is the latest price on [asOf]; [closes] the daily closes before it (order does not matter). */
    fun windows(closes: List<Pair<LocalDate, Double>>, last: Double, asOf: LocalDate): Windows = Windows(
        y1 = change(base(closes, asOf, 12), last),
        c1m = cagr(closes, last, asOf, 1),
        c12m = cagr(closes, last, asOf, 12),
        c24m = cagr(closes, last, asOf, 24),
        c36m = cagr(closes, last, asOf, 36),
    )

    /** True for a period id of this object (its missing value reads "n/a"). */
    fun isLong(period: String): Boolean = period in LONG_PERIODS

    /** A signed percent with [decimals] decimals, or "n/a" when [v] is null or not finite. */
    fun format(v: Double?, decimals: Int = 2): String = if (v == null || !v.isFinite()) NA else String.format(Locale.ROOT, "%+.${decimals}f", v)
}
