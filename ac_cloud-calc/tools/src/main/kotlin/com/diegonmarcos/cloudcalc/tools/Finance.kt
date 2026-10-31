package com.diegonmarcos.cloudcalc.tools

import kotlin.math.abs
import kotlin.math.pow

/**
 * #1377 Calc ▸ Tools ▸ Fin: the time-value-of-money formulas. Rates are fractions per period (0.05 = 5 %), so the
 * caller divides an annual percentage by 100 and by the periods per year. Amounts carry no currency: the screen
 * prints them in the one the user picked.
 */
object Finance {
    /** The level payment that repays [principal] over [n] periods at [rate] per period (annuity-immediate). */
    fun payment(principal: Double, rate: Double, n: Int): Double {
        require(n > 0) { "periods must be positive" }
        return if (rate == 0.0) principal / n else principal * rate / (1 - (1 + rate).pow(-n))
    }

    data class Row(val period: Int, val payment: Double, val interest: Double, val principal: Double, val balance: Double)

    /** The amortization schedule, one row per period; the last payment clears the balance exactly. */
    fun schedule(principal: Double, rate: Double, n: Int): List<Row> {
        val pmt = payment(principal, rate, n)
        var balance = principal
        return (1..n).map { k ->
            val interest = balance * rate
            val pay = if (k == n) balance + interest else pmt
            balance -= pay - interest
            Row(k, pay, interest, pay - interest, if (k == n) 0.0 else balance)
        }
    }

    /** Sums the schedule's rows in groups of [per] periods (a year of monthly rows): period = group number. */
    fun yearly(rows: List<Row>, per: Int): List<Row> = rows.chunked(per).mapIndexed { i, g ->
        Row(i + 1, g.sumOf { it.payment }, g.sumOf { it.interest }, g.sumOf { it.principal }, g.last().balance)
    }

    /** [pv] grown for [n] periods at [rate], plus [deposit] paid at the end of each period. */
    fun futureValue(pv: Double, rate: Double, n: Double, deposit: Double = 0.0): Double {
        val g = (1 + rate).pow(n)
        return pv * g + if (rate == 0.0) deposit * n else deposit * (g - 1) / rate
    }

    fun presentValue(fv: Double, rate: Double, n: Double): Double = fv / (1 + rate).pow(n)

    /** Net present value of [flows], the first at t = 0 (undiscounted). */
    fun npv(rate: Double, flows: List<Double>): Double = flows.foldIndexed(0.0) { t, acc, cf -> acc + cf / (1 + rate).pow(t) }

    /** The internal rate of return: the rate where [npv] is 0, by bisection over (-99.99 %, 10000 %); null when there is no sign change. */
    fun irr(flows: List<Double>): Double? {
        var lo = -0.9999; var hi = 100.0
        var flo = npv(lo, flows); val fhi = npv(hi, flows)
        if (flows.size < 2 || flo.isNaN() || fhi.isNaN() || flo * fhi > 0) return null
        repeat(300) {
            val mid = (lo + hi) / 2
            val f = npv(mid, flows)
            if (abs(f) < 1e-10) return mid
            if (f * flo < 0) hi = mid else { lo = mid; flo = f }
        }
        return (lo + hi) / 2
    }

    /** The deposit per period that, with [current] already saved, reaches [goal] after [n] periods at [rate]; 0 when already there. */
    fun savingsDeposit(goal: Double, current: Double, rate: Double, n: Int): Double {
        require(n > 0) { "periods must be positive" }
        val g = (1 + rate).pow(n)
        val short = goal - current * g
        if (short <= 0) return 0.0
        return if (rate == 0.0) short / n else short * rate / (g - 1)
    }

    /** Compound annual growth rate from [start] to [end] over [years]. */
    fun cagr(start: Double, end: Double, years: Double): Double {
        require(start > 0 && end > 0 && years > 0) { "start, end and years must be positive" }
        return (end / start).pow(1 / years) - 1
    }

    /** What [amount] today costs after [years] of [rate] inflation, and what it is worth then in today's money. */
    fun inflate(amount: Double, rate: Double, years: Double): Double = amount * (1 + rate).pow(years)
    fun deflate(amount: Double, rate: Double, years: Double): Double = amount / (1 + rate).pow(years)
}
