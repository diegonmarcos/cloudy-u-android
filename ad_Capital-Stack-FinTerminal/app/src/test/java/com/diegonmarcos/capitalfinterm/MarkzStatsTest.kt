package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * #1832 the Markovitz substeps MARKZ implements (the owner's page left them as descriptions): the closes of a quote-source answer, the four volatility
 * estimators, Beta, CAPM, the correlation and covariance matrices and the long-only optimiser, on series whose answers are known.
 */
class MarkzStatsTest {
    private val decl = Markz.parse(DataFiles.markzJson)!!.stats

    @Test fun theSubstepsReadTheDeclaredInstrumentsFromTheQuoteSource() {
        assertEquals(listOf("LFTS11.SA", "IRFM11.SA", "BOVA11.SA"), decl.assets.map { it.symbol })
        assertEquals("^BVSP", decl.benchmark.symbol)
        assertEquals("^VXEWZ", decl.assets.last().implied)
        assertEquals(listOf("hist", "ewma", "garch", "implied"), decl.volModels.map { it.first })
        assertEquals(252, decl.windowDays)
        assertTrue(decl.alpha + decl.beta < 1.0)
        // the closes come from the app's one quote source (data/quotes.json), as every price board's
        assertTrue(org.json.JSONObject(DataFiles.quotesJson).getJSONObject("source").getString("url").contains("{symbol}"))
    }

    @Test fun closesAreOnePerDayOldestFirstWithoutGaps() {
        val chart = """{"chart":{"result":[{"timestamp":[1763337600,1763424000,1763510400,1763596800],"indicators":{"quote":[{"close":[10.0,null,11.0,12.1]}]}}]}}"""
        val c = MarkzStats.closes(chart)
        assertEquals(3, c.size)
        assertEquals(LocalDate.of(2025, 11, 17), c.first().first)
        assertEquals(listOf(10.0, 11.0, 12.1), c.map { it.second })
        assertTrue(MarkzStats.closes("{}").isEmpty())
        assertEquals(listOf(0.1, 0.1), MarkzStats.returns(listOf(10.0, 11.0, 12.1)).map { Math.round(it * 1e9) / 1e9 })
    }

    @Test fun seriesAreAlignedOnTheDaysTheyAllHave() {
        val d = (1..5).map { LocalDate.of(2026, 1, it) }
        val (days, cl) = MarkzStats.aligned(listOf(d.map { it to 1.0 }, d.drop(1).map { it to 2.0 }))
        assertEquals(d.drop(1), days)
        assertEquals(listOf(4, 4), cl.map { it.size })
        assertTrue(MarkzStats.aligned(listOf(emptyList(), d.map { it to 1.0 })).first.isEmpty())
    }

    @Test fun theEstimatorsOnAKnownSeries() {
        val r = listOf(0.01, -0.01, 0.01, -0.01)
        // sample variance of +-1% is 4/3 x 1e-4; annualised over 252 days, in percent
        val sd = sqrt(4.0 / 3.0 * 1e-4 * 252) * 100
        assertEquals(sd, MarkzStats.histVol(r, 252), 1e-9)
        // EWMA from the sample variance with every r^2 = 1e-4
        var v = 4.0 / 3.0 * 1e-4; repeat(4) { v = 0.94 * v + 0.06 * 1e-4 }
        assertEquals(sqrt(v * 252) * 100, MarkzStats.ewmaVol(r, 0.94, 252), 1e-9)
        // GARCH(1,1) with variance targeting
        val lr = 4.0 / 3.0 * 1e-4; var g = lr; repeat(4) { g = (1 - 0.05 - 0.90) * lr + 0.05 * 1e-4 + 0.90 * g }
        assertEquals(sqrt(g * 252) * 100, MarkzStats.garchVol(r, 0.05, 0.90, 252), 1e-9)
        // a constant-variance series: every estimator agrees with its own long-run variance
        assertEquals(1.0, MarkzStats.corr(r, r), 1e-12)
        assertEquals(-1.0, MarkzStats.corr(r, r.map { -it }), 1e-12)
    }

    @Test fun betaAndCapm() {
        assertEquals(1.5, MarkzStats.beta(0.75, 30.0, 15.0), 1e-12)
        assertEquals(4.25 + 1.5 * (12.0 - 4.25), MarkzStats.capm(4.25, 1.5, 12.0), 1e-12)
        assertEquals(10.0, MarkzStats.annualised(listOf(100.0, 110.0), 1), 1e-9)
        assertTrue(MarkzStats.annualised(listOf(100.0), 252).isNaN())
    }

    @Test fun theOptimiserFindsTheKnownPortfolios() {
        // two uncorrelated assets of 10% and 20% volatility: the minimum variance weights are 80 / 20
        val mu = listOf(5.0, 10.0)
        val cov = MarkzStats.covMatrix(listOf(listOf(1.0, 0.0), listOf(0.0, 1.0)), listOf(10.0, 20.0))
        val o = MarkzStats.optimise(mu, cov, 2.0, 0.01, 6, 9.0)
        assertEquals(0.8, o.mvp.w[0], 1e-9)
        assertEquals(sqrt(0.64 * 100 + 0.04 * 400), o.mvp.vol, 1e-9)
        // the tangency of excess returns 3 and 8 on variances 100 and 400: w ~ (3/100, 8/400) normalised = 60 / 40
        assertEquals(0.6, o.maxSharpe.w[0], 1e-9)
        assertTrue(o.frontier.zipWithNext().all { (a, b) -> a.er <= b.er + 1e-9 && a.vol <= b.vol + 1e-9 })
        assertNotNull(o.match); assertTrue(o.match!!.er >= 9.0 - 1e-9)
        assertNull(MarkzStats.optimise(mu, cov, 2.0, 0.01, 6, 11.0).match)
        assertEquals(5151, MarkzStats.grid(3, 0.01).size)
        assertTrue(MarkzStats.grid(3, 0.01).all { Math.abs(it.sum() - 1.0) < 1e-9 && it.all { w -> w >= 0 } })
    }

    @Test fun computeReadsTheWindowOfEverySeries() {
        val days = (0 until 300).map { LocalDate.of(2025, 1, 1).plusDays(it.toLong()) }
        fun walk(seed: Int, k: Double) = days.mapIndexed { i, d -> d to 100.0 * (1 + k * Math.sin(i * 0.37 + seed)) + i * 0.01 }
        val closes = listOf(walk(1, 0.01), walk(2, 0.02), walk(3, 0.03), walk(3, 0.03))
        val r = MarkzStats.compute(decl, listOf("a", "b", "c", "m"), closes, listOf(null, null, 25.0, null))!!
        assertEquals(252, r.days)
        assertEquals(days.last().toString(), r.lastDay)
        assertEquals(4, r.vols.size)
        assertEquals(25.0, r.vols[2].implied!!, 0.0)
        assertNull(r.vols[0].implied)
        // the last asset and the market are the same series: correlation 1, beta 1
        assertEquals(1.0, r.corr[2][3], 1e-9)
        assertEquals(1.0, MarkzStats.beta(r.corr[2][3], r.vols[2].hist, r.vols[3].hist), 1e-9)
        assertTrue(r.vols.all { it.hist > 0 && it.ewma > 0 && it.garch > 0 })
        assertNull(MarkzStats.compute(decl, listOf("a"), listOf(days.take(2).map { it to 1.0 }), listOf(null)))
    }
}
