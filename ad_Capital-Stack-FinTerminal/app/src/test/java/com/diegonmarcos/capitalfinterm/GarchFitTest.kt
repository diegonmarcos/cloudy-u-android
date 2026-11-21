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
}
