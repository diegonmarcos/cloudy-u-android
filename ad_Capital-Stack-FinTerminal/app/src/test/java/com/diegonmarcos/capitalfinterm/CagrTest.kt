package com.diegonmarcos.capitalfinterm

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1786 the long-window periods: the 12-month change and the CAGR over 1 / 12 / 24 / 36 months, (end / start)^(1 / years) - 1 with years = months / 12,
 * never an arithmetic mean; a window the history does not reach is null (n/a), a short or missing history never yields a value.
 */
class CagrTest {
    private val asOf = LocalDate.of(2026, 11, 18)
    private fun near(want: Double, got: Double?, eps: Double = 1e-9) { assertTrue("got $got, want $want", got != null && Math.abs(got - want) < eps) }

    @Test fun cagrIsTheCompoundRateNotTheArithmeticMean() {
        // 100 -> 121 over two years: 10 % a year compounded (the arithmetic mean of the total move would be 10.5 %)
        near(10.0, Cagr.annualised(100.0, 121.0, 2.0))
        // 100 -> 133.1 over three years: 10 % a year
        near(10.0, Cagr.annualised(100.0, 133.1, 3.0))
        // a fall: 100 -> 81 over two years is -10 % a year
        near(-10.0, Cagr.annualised(100.0, 81.0, 2.0))
        // one year: the CAGR is the simple change
        near(7.5, Cagr.annualised(100.0, 107.5, 1.0))
        // one month annualised: (1.01)^12 - 1
        near((Math.pow(1.01, 12.0) - 1.0) * 100.0, Cagr.annualised(100.0, 101.0, 1.0 / 12.0))
    }

    @Test fun invalidInputsAreNull() {
        assertNull(Cagr.annualised(null, 100.0, 1.0))
        assertNull(Cagr.annualised(100.0, null, 1.0))
        assertNull(Cagr.annualised(0.0, 100.0, 1.0))
        assertNull(Cagr.annualised(-5.0, 100.0, 1.0))
        assertNull(Cagr.annualised(100.0, 0.0, 1.0))
        assertNull(Cagr.annualised(100.0, 110.0, 0.0))
        assertNull(Cagr.annualised(100.0, 110.0, -1.0))
        assertNull(Cagr.annualised(Double.NaN, 110.0, 1.0))
        assertNull(Cagr.change(0.0, 1.0))
        near(10.0, Cagr.change(100.0, 110.0))
    }

    /** Daily closes from [from] to the day before [asOf], growing by [daily] a calendar day (weekends included: the base rule does not care). */
    private fun series(from: LocalDate, start: Double, daily: Double): List<Pair<LocalDate, Double>> {
        val out = ArrayList<Pair<LocalDate, Double>>()
        var d = from; var v = start
        while (d.isBefore(asOf)) { out += d to v; d = d.plusDays(1); v *= daily }
        return out
    }

    @Test fun windowsReadTheLastCloseOnOrBeforeTheBaseDate() {
        val cl = listOf(LocalDate.of(2023, 11, 1) to 100.0, LocalDate.of(2024, 11, 18) to 110.0, LocalDate.of(2025, 11, 18) to 121.0, LocalDate.of(2026, 10, 16) to 130.0)
        val w = Cagr.windows(cl, 133.1, asOf)
        near(10.0, w.y1)                                                   // 121 -> 133.1
        near(10.0, w.c12m)                                                 // = the 12-month change
        near(10.0, w.c24m)                                                 // 110 -> 133.1 over 2y
        near((Math.pow(1.331, 1.0 / 3.0) - 1.0) * 100.0, w.c36m)           // base 2023-11-01 (last on or before 2023-11-18)
        near((Math.pow(133.1 / 130.0, 12.0) - 1.0) * 100.0, w.c1m)         // base 2026-10-16 (last on or before 2026-10-18)
    }

    @Test fun shortHistoryIsNaNeverAShorterWindow() {
        // 14 months of history: 1m and 12m answer, 24m and 36m are n/a
        val cl = series(asOf.minusMonths(14), 100.0, 1.0002)
        val w = Cagr.windows(cl, cl.last().second, asOf)
        assertTrue(w.c1m != null && w.y1 != null && w.c12m != null)
        assertNull(w.c24m); assertNull(w.c36m)
        // a history that starts one day after the base date is n/a too
        val late = series(asOf.minusMonths(12).plusDays(1), 100.0, 1.0)
        assertNull(Cagr.windows(late, 100.0, asOf).y1)
        assertNull(Cagr.windows(late, 100.0, asOf).c12m)
        // exactly on the base date answers
        val exact = series(asOf.minusMonths(12), 100.0, 1.0)
        near(0.0, Cagr.windows(exact, 100.0, asOf).c12m)
    }

    @Test fun missingHistoryIsNa() {
        val w = Cagr.windows(emptyList(), 100.0, asOf)
        assertEquals(Cagr.Windows(null, null, null, null, null), w)
        assertNull(Cagr.base(listOf(asOf to Double.NaN), asOf, 1))
        assertNull(Cagr.base(series(asOf.minusYears(4), 100.0, 1.0), asOf, 0))
        assertEquals("n/a", Cagr.format(null)); assertEquals("n/a", Cagr.format(Double.NaN)); assertEquals("+10.00", Cagr.format(10.0))
    }

    @Test fun theQuoteCarriesTheWindowsAndThePeriodsReadThem() {
        // a Yahoo chart answer of 1 000 daily closes rising 0.05 % a day (about 2.7 years): 12M, CAGR 1m / 12m / 24m answer, CAGR 36m does not
        val n = 1000
        val end = asOf.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val ts = (0 until n).map { end - (n - 1 - it) * 86_400L }
        val cl = (0 until n).map { 100.0 * Math.pow(1.0005, it.toDouble()) }
        val body = """{"chart":{"result":[{"meta":{"regularMarketPrice":${cl.last()},"regularMarketTime":$end,"currency":"USD"},"timestamp":[${ts.joinToString(",")}],"indicators":{"quote":[{"close":[${cl.joinToString(",")}]}]}}]}}"""
        val q = Quotes.parseChart(body)!!
        assertTrue(q.y1 != null && q.c1m != null && q.c12m != null && q.c24m != null)
        assertNull("1 000 days do not reach 36 months", q.c36m)
        near(q.y1!!, q.c12m, 1e-9)
        assertEquals(q.y1, IndexHoldings.perf(q, Cagr.P12M))
        assertEquals(q.c1m, IndexHoldings.perf(q, Cagr.PCAGR_1M))
        assertEquals(q.c24m, IndexHoldings.perf(q, Cagr.PCAGR_24M))
        assertNull(IndexHoldings.perf(q, Cagr.PCAGR_36M))
        // the CAGR 24m is the compound daily rate over the 24-month span, annualised, not 24 months of arithmetic mean
        val base = Cagr.base(ts.dropLast(1).zip(cl.dropLast(1)).map { java.time.Instant.ofEpochSecond(it.first).atZone(ZoneOffset.UTC).toLocalDate() to it.second }, asOf, 24)!!
        near((Math.pow(cl.last() / base, 0.5) - 1.0) * 100.0, q.c24m, 1e-6)
    }

    @Test fun aSnapshotQuoteWithoutTheNewFieldsReadsNa() {
        val q = QuotesSnapshot.quote(org.json.JSONObject("""{"last":10.0,"asof":1,"pct":1.0,"w1":2.0,"m1":3.0,"ytd":4.0}"""))
        assertNull(q.y1); assertNull(q.c36m)
        assertEquals("n/a", PerfPortfolios.cell(IndexHoldings.perf(q, Cagr.PCAGR_36M), Cagr.PCAGR_36M))
        assertEquals("+3.00", PerfPortfolios.cell(IndexHoldings.perf(q, "1M"), "1M"))
        // a short period without a figure keeps the dash, a long one says n/a
        assertEquals(FinFormat.DASH, PerfPortfolios.cell(null, "1D"))
        assertEquals("n/a", PerfPortfolios.cell(null, Cagr.P12M))
        val full = QuotesSnapshot.quote(org.json.JSONObject("""{"last":10.0,"asof":1,"y1":5.0,"c1m":6.0,"c12m":5.0,"c24m":7.0,"c36m":8.0}"""))
        assertEquals(listOf(5.0, 6.0, 5.0, 7.0, 8.0), Cagr.LONG_PERIODS.map { IndexHoldings.perf(full, it) })
    }

    @Test fun everyEqAndFiPageDeclaresTheFullPeriodSet() {
        val decl = PerfPortfolios.parseDecl(DataFiles.portfoliosJson)!!
        assertEquals(listOf("1D", "1W", "1M", "YTD", "12M", "CAGR 1m", "CAGR 12m", "CAGR 24m", "CAGR 36m"), decl.periods)
        assertEquals(listOf("1M", "12M"), decl.cardPeriods)
        // every declared period is one the quote answers (none silently maps to nothing)
        val q = Quote(1.0, 1, "", "", "", 1.0, 0.0, 1.0, 1.0, 1.0, 1.0, null, 1.0, 1.0, 1.0, 1.0, 1.0)
        for (p in decl.periods) assertEquals(p, 1.0, IndexHoldings.perf(q, p))
        // the quote source asks five years of daily closes: enough for the 36-month window
        assertTrue(QuotesDecl.parse(DataFiles.quotesJson).source.url.contains("range=5y"))
    }
}
