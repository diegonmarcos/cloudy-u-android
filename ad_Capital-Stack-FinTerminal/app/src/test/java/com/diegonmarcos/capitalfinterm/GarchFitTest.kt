package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.sqrt

/** #1832 the per-asset GARCH(1,1) maximum-likelihood fit: known parameters recovered, constraints, short-history fallback, determinism, cache, budget. */
class GarchFitTest {
    private val decl = Markz.parse(DataFiles.markzJson)!!.stats

    /** Daily closes of a simulated GARCH(1,1) (log returns, seeded: the same series every run). */
    private fun sim(n: Int, omega: Double, alpha: Double, beta: Double, seed: Long, start: LocalDate = LocalDate.of(2018, 1, 1)): List<Pair<LocalDate, Double>> {
        val rnd = java.util.Random(seed)
        var v = omega / (1 - alpha - beta); var p = 100.0
        val out = ArrayList<Pair<LocalDate, Double>>()
        out += start to p
        for (i in 1..n) {
            val e = rnd.nextGaussian() * sqrt(v)
            p *= exp(e); v = omega + alpha * e * e + beta * v
            out += start.plusDays(i.toLong()) to p
        }
        return out
    }

    @Test fun recoversTheParametersOfASimulatedSeries() {
        val f = GarchFitter.fit(sim(4000, 2e-6, 0.08, 0.90, 11), decl.alpha, decl.beta)
        assertTrue(f.fitted)
        assertEquals(4000, f.n)
        assertEquals(0.08, f.alpha, 0.04)
        assertEquals(0.90, f.beta, 0.05)
        assertEquals(0.98, f.persistence, 0.03)
        assertEquals(2e-6 / (1 - 0.98), f.longRunVar, 2e-4 * 0.5)   // long-run daily variance 1e-4 within a loose band
        assertTrue(f.logLik.isFinite() && f.logLik > 0)
        assertEquals(LocalDate.of(2018, 1, 1), f.from)
    }

    @Test fun theConstraintsHoldOnEverySeries() {
        val series = listOf(
            sim(1500, 2e-6, 0.08, 0.90, 1), sim(1500, 1e-5, 0.0, 0.0, 2),        // white noise: no GARCH effect
            sim(1500, 1e-7, 0.15, 0.849, 3), sim(800, 5e-6, 0.30, 0.20, 4),       // near-integrated, and short-memory
        )
        for (s in series) {
            val f = GarchFitter.fit(s, decl.alpha, decl.beta)
            assertTrue(f.omega > 0); assertTrue(f.alpha >= 0); assertTrue(f.beta >= 0)
            assertTrue("alpha + beta = ${f.persistence}", f.persistence < 1.0)
            assertTrue(f.halfLife >= 0 && f.longRunVar > 0 && f.longRunVol(252) > 0)
        }
    }

    @Test fun aShortHistoryFallsBackToTheDefaultsAndSaysSo() {
        val f = GarchFitter.fit(sim(GarchFitter.MIN_OBS - 1, 2e-6, 0.08, 0.90, 5), decl.alpha, decl.beta)
        assertFalse(f.fitted)
        assertEquals(decl.alpha, f.alpha, 0.0); assertEquals(decl.beta, f.beta, 0.0)
        assertEquals(GarchFitter.MIN_OBS - 1, f.n)
        val d = Markz.parse(DataFiles.markzJson)!!
        assertEquals("defaults (short history)", d.word("st_g_defaults"))
        val rows = MarkzScreen.garchRows(d, listOf(MarkzVols(1.0, 1.0, 1.0, null, f)), 252)
        assertEquals("defaults (short history)", rows.single { it[0] == d.word("st_g_source") }[1])
        // exactly MIN_OBS returns is enough
        assertTrue(GarchFitter.fit(sim(GarchFitter.MIN_OBS, 2e-6, 0.08, 0.90, 5), decl.alpha, decl.beta).fitted)
    }

    @Test fun theSameDataGivesBitIdenticalParametersAndIsCachedByItsHash() {
        val s = sim(1200, 2e-6, 0.08, 0.90, 6)
        GarchFitter.clearCache()
        val before = GarchFitter.fits
        val a = GarchFitter.fit(s, decl.alpha, decl.beta)
        assertEquals(before + 1, GarchFitter.fits)
        assertTrue(a === GarchFitter.fit(s.toList(), decl.alpha, decl.beta))      // same data: the cache answers, no refit
        assertEquals(before + 1, GarchFitter.fits)
        GarchFitter.clearCache()
        assertEquals(a, GarchFitter.fit(s, decl.alpha, decl.beta))                // refit from scratch: identical, no randomness
        // the history updates (one more day): a different hash, so a refit
        GarchFitter.fit(s + (s.last().first.plusDays(1) to s.last().second * 1.01), decl.alpha, decl.beta)
        assertEquals(before + 3, GarchFitter.fits)
    }

    @Test fun fittingEveryAssetStaysWithinTheBudget() {
        GarchFitter.clearCache()
        val all = (1..4).map { sim(1260, 2e-6 * it, 0.05 + 0.01 * it, 0.90, 20L + it) }   // the three assets and the benchmark, five years
        val t0 = System.nanoTime()
        all.forEach { GarchFitter.fit(it, decl.alpha, decl.beta) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("fitting took $ms ms", ms < 5000)
    }

    @Test fun theVolatilityTableUsesTheFittedParameters() {
        val days = (0 until 700).map { LocalDate.of(2023, 1, 1).plusDays(it.toLong()) }
        val s = sim(699, 2e-6, 0.08, 0.90, 9, days[0])
        val r = MarkzStats.compute(decl, listOf("a", "m"), listOf(s, s), listOf(null, null))!!
        val fit = r.vols[0].fit!!
        assertTrue(fit.fitted)
        assertEquals(fit.vol(GarchFitter.logReturns(s.map { it.second }), 252), r.vols[0].garch, 1e-9)
        val short = MarkzStats.compute(decl, listOf("a", "m"), listOf(s.take(300), s.take(300)), listOf(null, null))!!
        assertFalse(short.vols[0].fit!!.fitted)
    }

    @Test fun aSplitOrABadPrintIsDroppedBeforeTheFit() {
        val base = sim(1500, 2e-6, 0.08, 0.90, 31)
        // a 3:2 style split (a persistent level jump of +43%) and a print that is wrong for one day and corrected the next
        val bad = base.mapIndexed { i, (d, c) -> d to (if (i >= 800) c * 1.53 else c) * (if (i == 1200) 1.4 else 1.0) }
        val (r, dropped) = GarchFitter.cleanReturns(bad.map { it.second })
        assertEquals(3, dropped)                       // the split return, and both legs of the reversed print
        assertEquals(1500 - 3, r.size)
        val clean = GarchFitter.fit(base, decl.alpha, decl.beta)
        val f = GarchFitter.fit(bad, decl.alpha, decl.beta)
        assertEquals(3, f.dropped); assertEquals(0, clean.dropped)
        assertEquals(clean.realisedVol, f.realisedVol, clean.realisedVol * 0.05)   // the realised vol is not inflated by the bad prints
        assertTrue(f.longRunVol(252) < 3 * clean.longRunVol(252))
    }

    /** A near-integrated GARCH(1,1) from a fixed LCG + Box-Muller (the same numbers in the python port the case was chosen with). */
    private fun lcgSeries(seed: ULong, n: Int, omega: Double, alpha: Double, beta: Double, v0: Double): List<Pair<LocalDate, Double>> {
        var st = seed
        fun u(): Double { st = st * 6364136223846793005uL + 1442695040888963407uL; return (st shr 11).toDouble() / 9007199254740992.0 }
        var v = v0; var p = 100.0
        val out = arrayListOf(LocalDate.of(2018, 1, 1) to p)
        for (i in 1..n) {
            val u1 = 1 - u(); val u2 = u()
            val e = sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2) * sqrt(v)
            p *= exp(e); v = omega + alpha * e * e + beta * v
            out += LocalDate.of(2018, 1, 1).plusDays(i.toLong()) to p
        }
        return out
    }

    @Test fun persistenceIsCappedAndSaid() {
        val f = GarchFitter.fit(lcgSeries(2uL, 2000, 1e-8, 0.10, 0.899, 1e-5), decl.alpha, decl.beta)
        assertTrue("persistence ${f.persistence}", f.persistence <= GarchFitter.MAX_PERSISTENCE)
        assertEquals("capped", f.basis)
        val d = Markz.parse(DataFiles.markzJson)!!
        assertEquals("fitted (persistence capped)", d.word("st_g_capped"))
        assertEquals("fitted", GarchFitter.fit(sim(4000, 2e-6, 0.08, 0.90, 11), decl.alpha, decl.beta).basis)
    }

    @Test fun theSanityGateVarianceTargetsALongRunVolOutsideHalfToDouble() {
        val s2 = 1.0   // realised variance (percent^2)
        assertEquals(null, GarchFitter.gate(0.02, 0.05, 0.93, s2))          // long-run variance 1.0: passes
        assertEquals(null, GarchFitter.gate(0.02 * 3.9, 0.05, 0.93, s2))    // vol 1.97x: passes
        assertEquals(0.02, GarchFitter.gate(0.02 * 4.2, 0.05, 0.93, s2)!!, 1e-12)   // vol 2.05x: omega from the realised variance
        assertEquals(0.02, GarchFitter.gate(0.02 * 0.2, 0.05, 0.93, s2)!!, 1e-12)   // vol 0.45x
        assertEquals("fitted (variance-targeted)", Markz.parse(DataFiles.markzJson)!!.word("st_g_targeted"))
        // a targeted fit reports the realised vol as its long-run vol
        val f = GarchFit(0.02e-4 * 1.0, 0.05, 0.93, 0.0, 0.0, 600, null, null, true, "targeted", 0, 15.87)
        assertEquals(sqrt(1e-4 * 252) * 100, f.longRunVol(252), 1e-9)
    }

    @Test fun theAnnualisationIsSqrtOfDaysOnTheDailyVolatility() {
        // a daily variance of 1e-4 is a 1% daily vol; a year of 252 days is 1% x sqrt(252) = 15.87%, not 252 x 1%
        val f = GarchFit(1e-6, 0.0, 0.99, 0.0, 0.0, 600, null, null, true)   // long-run variance 1e-6 / 0.01 = 1e-4
        assertEquals(15.8745, f.longRunVol(252), 1e-3)
        assertEquals(1.0 * Math.sqrt(252.0), sqrt(1e-4) * 100 * sqrt(252.0), 1e-9)
        val rnd = java.util.Random(3); val r = List(2000) { rnd.nextGaussian() * 0.01 }
        assertEquals(15.87, MarkzStats.histVol(r, 252), 1.0)
    }

    @Test fun everyVolModelSeesTheCleanedReturns() {
        val a = sim(400, 2e-6, 0.08, 0.90, 51); val m = sim(400, 2e-6, 0.08, 0.90, 52)
        // a one-day wrong print (+40% then back) in the asset only
        val dirty = a.mapIndexed { i, (d, c) -> d to (if (i == 300) c * 1.4 else c) }
        val clean = MarkzStats.compute(decl, listOf("a", "m"), listOf(a, m), listOf(null, null))!!
        val r = MarkzStats.compute(decl, listOf("a", "m"), listOf(dirty, m), listOf(null, null))!!
        assertEquals(2, r.dropped); assertEquals(0, clean.dropped)
        // the print is gone from the SD, the EWMA, the GARCH and the correlation (the two legs are removed from every series, so they stay aligned)
        assertEquals(clean.vols[0].hist, r.vols[0].hist, clean.vols[0].hist * 0.05)
        assertEquals(clean.vols[0].ewma, r.vols[0].ewma, clean.vols[0].ewma * 0.05)
        assertEquals(clean.vols[0].garch, r.vols[0].garch, clean.vols[0].garch * 0.05)
        assertEquals(clean.corr[0][1], r.corr[0][1], 0.05)
        assertTrue(r.returns.all { w -> w.all { Math.abs(it) < 0.25 } })
        // the raw series (no cleaning) would have a far larger SD
        val raw = MarkzStats.returns(dirty.map { it.second }).takeLast(252)
        assertTrue(MarkzStats.histVol(raw, 252) > 2 * r.vols[0].hist)
    }
}
