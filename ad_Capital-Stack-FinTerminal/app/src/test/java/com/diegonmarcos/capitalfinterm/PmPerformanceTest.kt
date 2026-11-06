package com.diegonmarcos.capitalfinterm

import java.io.IOException
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1539 the Portfolios and Performance pages: the Top 3, the performance math (simple returns for the windows, CAGR for 3y, the rebalancing rule) on fixture prices, and n/a instead of invented returns. */
class PmPerformanceTest {
    private val perf = PmPortfolios.perf(DataFiles.pmJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val decl = PmPortfolios.parse(DataFiles.pmJson) { id -> Dashboards.byId(dashboards, id)?.entry ?: id }
    private fun d(s: String) = LocalDate.parse(s)
    private fun series(vararg p: Pair<String, Double>) = PriceSeries(p.map { d(it.first) }, p.map { it.second })

    @Test fun top3AreTheThreeLargestNonZeroAllocationsWithTheDeclaredOrderBreakingTies() {
        val legs = listOf(PmLeg("a", 10.0, null), PmLeg("b", 40.0, null), PmLeg("c", 0.0, null), PmLeg("d", 40.0, null), PmLeg("e", 10.0, null))
        assertEquals(listOf("b", "d", "a"), PmPortfolios.top(legs, 3).map { it.name })
        assertEquals("b 40 · d 40 · a 10", PmPortfolios.topText(legs, 3)); assertEquals("x 12.5", PmPortfolios.weight(12.5).let { "x $it" })
        assertEquals(listOf("a"), PmPortfolios.top(listOf(PmLeg("a", 100.0, null), PmLeg("z", 0.0, null)), 3).map { it.name })
    }

    @Test fun thePortfoliosPageHoldsBothTablesWithTheirLegsAndTop3() {
        assertEquals(listOf("Portfolio", "Vol", "Top 3", "See all"), decl.columns); assertEquals(3, decl.top)
        assertEquals(listOf("Portfolios DM vs EM", "Portfolios US Cyclicals"), decl.tables.map { it.title })
        val dmem = decl.portfolios.filter { it.table == "dm_em" }; val cyc = decl.portfolios.filter { it.table == "us_cyclicals" }
        assertEquals(13, dmem.size); assertEquals(8, cyc.size)
        val b = dmem.first { it.id == "mp_60_40" }
        assertEquals(listOf("DM Bonds" to 60.0, "DM Equity" to 40.0), b.legs.map { it.name to it.weight }); assertEquals("DM Bonds 60 · DM Equity 40", PmPortfolios.topText(b.legs, 3))
        assertEquals(listOf("AGG", "URTH"), b.legs.map { it.symbol })
        val g = cyc.first { it.id == "growth" }
        assertEquals(5, g.legs.size); assertTrue(g.legs.all { it.weight == 20.0 && it.symbol == it.name }); assertEquals(3, PmPortfolios.top(g.legs, 3).size)
        assertTrue("a carry trade has no legs", dmem.first { it.id == "pm_d0_carry" }.legs.isEmpty())
        assertNotNull(dmem.first { it.id == "pm_a1_cash" }.noHistory)
        for (p in dmem.filter { it.legs.isNotEmpty() }) assertEquals(p.id, 100.0, p.legs.sumOf { it.weight }, 1e-9)
    }

    @Test fun simpleReturnsOverTheWindowsAndTheLastCloseAsBase() {
        // closes: Jan 2 2026 .. Nov 5 2026 (weekly steps are enough: the windows pick the last close on or before the base date)
        val dates = generateSequence(d("2025-10-01")) { it.plusDays(1) }.takeWhile { !it.isAfter(d("2026-11-05")) }.filter { it.dayOfWeek.value <= 5 }.toList()
        val closes = dates.indices.map { 100.0 + it }
        val p = PmPerformance.path(listOf(100.0 to PriceSeries(dates, closes)), "monthly")!!
        assertEquals(1.0, p.first().second, 0.0)
        val w = PmPerformance.windows(p, perf.windows)
        val at = { day: String -> p.last { !it.first.isAfter(d(day)) }.second }
        val last = p.last().second
        assertEquals(last / p[p.size - 2].second - 1.0, w.getValue("today")!!, 1e-12)
        assertEquals(last / at("2026-10-29") - 1.0, w.getValue("d7")!!, 1e-12)
        assertEquals(last / at("2026-10-06") - 1.0, w.getValue("d30")!!, 1e-12)
        assertEquals(last / p.last { it.first.isBefore(d("2026-11-01")) }.second - 1.0, w.getValue("mtd")!!, 1e-12)
        assertEquals(last / p.last { it.first.isBefore(d("2026-01-01")) }.second - 1.0, w.getValue("ytd")!!, 1e-12)
        assertEquals(last / at("2025-11-05") - 1.0, w.getValue("y1")!!, 1e-12)
        assertNull("the history does not reach three years back: n/a, never a guess", w.getValue("y3"))
    }

    @Test fun threeYearIsTheAnnualisedCagrAndAWindowThePathDoesNotReachIsNull() {
        val dates = generateSequence(d("2022-01-03")) { it.plusDays(1) }.takeWhile { !it.isAfter(d("2026-11-05")) }.filter { it.dayOfWeek.value <= 5 }.toList()
        val closes = dates.indices.map { 100.0 * Math.pow(1.0003, it.toDouble()) }
        val p = PmPerformance.path(listOf(100.0 to PriceSeries(dates, closes)), "none")!!
        val w = PmPerformance.windows(p, perf.windows)
        val base = p.last { !it.first.isAfter(d("2023-11-05")) }.second
        assertEquals(Math.pow(p.last().second / base, 1.0 / 3.0) - 1.0, w.getValue("y3")!!, 1e-12)
        assertTrue("the CAGR is not the simple 3y return", Math.abs(w.getValue("y3")!! - (p.last().second / base - 1.0)) > 0.05)
        val tiny = PmPerformance.path(listOf(100.0 to series("2026-11-03" to 100.0, "2026-11-04" to 110.0, "2026-11-05" to 99.0)), "monthly")!!
        val tw = PmPerformance.windows(tiny, perf.windows)
        assertEquals(-0.1, tw.getValue("today")!!, 1e-12); assertNull(tw.getValue("y1")); assertNull(tw.getValue("y3"))
    }

    @Test fun theRebalancingRuleChangesThePathAsDeclared() {
        val dates = listOf("2026-01-30", "2026-02-02", "2026-02-03", "2026-03-02").map { d(it) }
        val a = PriceSeries(dates, listOf(100.0, 110.0, 121.0, 121.0)); val b = PriceSeries(dates, listOf(100.0, 100.0, 100.0, 120.0))
        val legs = listOf(50.0 to a, 50.0 to b)
        val none = PmPerformance.path(legs, "none")!!.map { it.second }
        assertEquals(listOf(1.0, 1.05, 1.105, 1.205), none.map { Math.round(it * 1000.0) / 1000.0 })
        // monthly: rebalanced to 50/50 at the first close of February (2026-02-02, value 1.05), then held to the next month's first close (2026-03-02)
        val monthly = PmPerformance.path(legs, "monthly")!!.map { it.second }
        assertEquals(1.05, monthly[1], 1e-12)
        assertEquals(1.05 * (0.5 * 121.0 / 110.0 + 0.5 * 100.0 / 100.0), monthly[2], 1e-12)
        assertEquals(1.05 * (0.5 * 121.0 / 110.0 + 0.5 * 120.0 / 100.0), monthly[3], 1e-12)
        val daily = PmPerformance.path(legs, "daily")!!.map { it.second }
        assertEquals(1.05 * (0.5 * 121.0 / 110.0 + 0.5), daily[2], 1e-12)
        assertTrue(monthly[3] != daily[3] || daily[3] != none[3])
    }

    @Test fun missingHistoryOrWrongWeightsGiveNoPathAndNaNotANumber() {
        val s = series("2026-11-03" to 1.0, "2026-11-04" to 2.0)
        assertNull(PmPerformance.path(listOf(50.0 to s, 50.0 to null), "monthly")); assertNull(PmPerformance.path(listOf(60.0 to s, 30.0 to s), "monthly")); assertNull(PmPerformance.path(emptyList(), "monthly"))
        assertNull(PmPerformance.path(listOf(100.0 to series("2026-11-03" to 1.0)), "monthly"))
        val cash = decl.portfolios.first { it.id == "pm_a1_cash" }
        val r = PmPerformance.compute(cash, emptyMap(), perf)
        assertNull(r.asOf); assertTrue(r.values.isEmpty()); assertNotNull(r.na)
        val mp = decl.portfolios.first { it.id == "mp_60_40" }
        val noLeg = PmPerformance.compute(mp, mapOf("AGG" to s), perf)
        assertNull("one leg has no history: the whole portfolio is n/a", noLeg.asOf); assertEquals(perf.word("na_long"), noLeg.na)
    }

    @Test fun theKotlinMathGivesTheNumbersTheSnapshotGeneratorGivesOnTheSharedFixture() {
        val fx = JSONObject(DataFiles.pmFixture("legs-fixture.json"))
        val w = fx.getJSONArray("weights"); val legs = fx.getJSONArray("legs")
        val series = (0 until legs.length()).map { i ->
            val o = legs.getJSONObject(i); val ds = o.getJSONArray("dates"); val cs = o.getJSONArray("closes")
            PriceSeries((0 until ds.length()).map { d(ds.getString(it)) }, (0 until cs.length()).map { cs.getDouble(it) })
        }
        val pairs = series.indices.map { w.getDouble(it) to series[it] }
        val exp = fx.getJSONObject("expected")
        for (rule in listOf("monthly", "daily", "none")) {
            val p = PmPerformance.path(pairs, rule)!!
            val got = PmPerformance.windows(p, perf.windows); val want = exp.getJSONObject(rule)
            for (k in got.keys) {
                val v = want.getJSONObject("values")
                if (v.isNull(k)) assertNull("$rule $k", got[k]) else assertEquals("$rule $k", v.getDouble(k), got.getValue(k)!!, 1e-9)
            }
            assertEquals("$rule vol", want.getDouble("vol1y"), PmPerformance.vol1y(p)!!, 1e-9)
        }
    }

    @Test fun theChartAnswerBecomesDatedCloses() {
        val s = PmPerformance.parseHistory(DataFiles.quotesFixture("yahoo-chart.json"))!!
        assertEquals(30, s.dates.size); assertTrue(s.dates.zipWithNext().all { (a, b) -> a.isBefore(b) }); assertEquals(91.09, s.closes.last(), 1e-6)
        assertNull(PmPerformance.parseHistory("""{"chart":{"result":null}}""")); assertNull(PmPerformance.parseHistory("nope"))
    }

    @Test fun theBakedSnapshotHoldsEveryPortfolioWithAnAsOfDateOrAnNaReason() {
        val snap = PmPerfStore.snapshot(DataFiles.pmPerformanceJson, perf)
        assertEquals(decl.portfolios.map { it.id }.sorted(), snap.keys.sorted())
        for ((id, r) in snap) {
            if (r.na != null) { assertTrue(id, r.na.isNotBlank()); assertTrue(r.values.isEmpty()) }
            else { assertNotNull(id, r.asOf); assertEquals(perf.windows.map { it.id }.toSet(), r.values.keys); assertTrue(r.values.values.filterNotNull().all { it.isFinite() && Math.abs(it) < 20 }) }
        }
        assertNotNull(snap.getValue("pm_a1_cash").na); assertNull(snap.getValue("pm_a1_cash").asOf)
    }

    @Test fun aRefreshComputesFromTheSourcesClosesWithTheDeclaredUserAgentAndNeverFromWhatDidNotAnswer() {
        val asked = ArrayList<String>()
        val http = object : Http {
            override fun get(url: String): String = send(url, emptyMap(), null)
            override fun post(url: String): String = throw IOException("no")
            override fun send(url: String, headers: Map<String, String>, body: String?): String { synchronized(asked) { asked += url + "|" + headers["User-Agent"] }; throw IOException("HTTP 404") }
        }
        val n = PmPerfStore.refresh(perf, decl.portfolios, true, http) { 5_000_000L }
        assertEquals("nothing answered: no portfolio is computed", 0, n)
        assertTrue(asked.isNotEmpty()); assertTrue(asked.all { it.endsWith("|" + perf.userAgent) }); assertTrue(asked.any { it.contains("range=5y") })
    }
}
