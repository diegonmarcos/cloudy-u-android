package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1832 MARKZ's arithmetic is the owner's page's (Portfolio_Returns_Sensitivity_Matrix.html): at its DEFAULTS ($100,000, USD/BRL 5.93, EUR/BRL 6.50,
 * 14.98% gross, 3.63% inflation, 17% tax, 2% spread) the figures are the ones the page computes, and every field behaves as the page's handler does.
 */
class MarkzTest {
    private val d = Markz.parse(DataFiles.markzJson)!!
    private val s0 = d.defaults

    @Test fun theDefaultsAreThePagesBaseline() {
        assertEquals(100000.0, s0.aumUsd, 0.0)
        assertEquals(593000.0, s0.aumBrl, 0.0)
        assertEquals(6.50 / 5.93, s0.centerEurUsd, 1e-12)
        assertEquals(Markz.ANCHOR_USD, s0.aumAnchor)
        assertEquals("91,230.77", Markz.money(s0.aumEur))
        val f = Markz.flow(s0, s0)
        assertEquals(10.95, f.real * 100, 0.005)
        assertEquals(9.09, f.net * 100, 0.005)
        assertEquals("4,492.23", Markz.money(f.brlMom))
        assertEquals("742.39", Markz.money(f.usdMom))
        assertEquals("677.29", Markz.money(f.eurMom))
        assertEquals(750.0, f.sheetEurMom, 0.0)
    }

    @Test fun theHomePageShowsTheOverviewOfThePage() {
        val h = Markz.home(d, s0, s0)
        assertEquals("Net: €677.29/mo", h["nav-live-badge"])
        assertEquals("$100,000.00", h["home-aum-usd"])
        assertEquals("€91,230.77", h["home-aum-eur"])
        assertEquals("R$ 593,000.00", h["home-aum-brl"])
        assertEquals("Net MoM: $742.39", h["home-mom-usd"])
        assertEquals("Net MoM: €677.29 (Sheet: €750)", h["home-mom-eur"])
        assertEquals("Net MoM: R$ 4,492.23", h["home-mom-brl"])
        assertEquals("14.98%", h["home-wf-gross"])
        assertEquals("−3.63% → 10.95% Real", h["home-wf-infl"])
        assertEquals("−17.0% → 9.09% Net", h["home-wf-tax"])
        assertEquals("€8,127.48 / yr", h["home-wf-annual-eur"])
        assertEquals("1.0961", h["home-fx-eurusd"])
        assertEquals("5.93", h["home-fx-usdbrl"])
        assertEquals("6.50", h["home-fx-eurbrl"])
    }

    @Test fun theMatrixIsSevenReturnsBySevenFxShocks() {
        val m = Markz.matrix(s0, s0, rounded = false)
        assertEquals(listOf("12.73", "13.48", "14.23", "14.98", "15.73", "16.48", "17.23"), m.cols.map { Markz.fixed(it.grossPct, 2) })
        assertEquals(listOf("8.78", "9.50", "10.23", "10.95", "11.68", "12.40", "13.12"), m.cols.map { Markz.fixed(it.realPct, 2) })
        assertTrue(m.cols.all { it.inflPct == 3.63 })
        assertEquals(listOf("5.52", "5.85", "6.17", "6.50", "6.83", "7.15", "7.47"), m.rows.map { Markz.fixed(it.eurBrl, 2) })
        assertEquals(listOf("5.04", "5.34", "5.63", "5.93", "6.23", "6.52", "6.82"), m.rows.map { Markz.fixed(it.usdBrl, 2) })
        assertEquals("638.85", Markz.money(m.rows[0].cells[0].display))
        assertEquals("954.77", Markz.money(m.rows[0].cells[6].display))
        assertEquals("472.20", Markz.money(m.rows[6].cells[0].display))
        assertEquals("677.29", Markz.money(m.center.display))
        assertTrue(m.center.isCenter)
        assertEquals(1, m.rows.sumOf { r -> r.cells.count { it.isCenter } })
        val r = Markz.matrix(s0, s0, rounded = true)
        assertEquals(750.0, r.center.display, 0.0)
        assertEquals(500.0, r.min, 0.0); assertEquals(1000.0, r.max, 0.0)
        assertTrue(r.rows.all { row -> row.cells.all { it.display % 250.0 == 0.0 } })
    }

    @Test fun theKpisAndTheLinesAroundTheMatrix() {
        val k = Markz.kpis(d, s0, s0, Markz.matrix(s0, s0, true), true)
        assertEquals("AuM: €91.2k (R$0.59M)", k["header-nav-pill"])
        assertEquals("€91,230.77", k["kpi-nav-eur"])
        assertEquals("R$ 593,000", k["kpi-nav-brl"])
        assertEquals("USD Equiv: $100,000.00", k["kpi-nav-usd"])
        assertEquals("€750.00", k["kpi-baseline"])
        assertEquals("Exact: €677.29", k["kpi-baseline-sub"])
        assertEquals("10.95%", k["kpi-real-yield"])
        assertEquals("Net: 9.09%", k["kpi-net-real-yield"])
        assertEquals("14.98% − 3.63% Infl", k["kpi-yield-sub"])
        assertEquals("€500–1,000", k["kpi-range"])
        assertEquals("±15% FX • ±225 bps", k["kpi-range-sub"])
        assertEquals("€250 Step (Sheet)", k["round-step-label"])
        assertEquals("75bps • 5% • €250", k["steps-summary-badge"])
        assertEquals("Implied EUR/BRL: 6.5000 (USD/BRL 5.93 × EUR/USD 1.0961)", k["footer-cross-rate"])
        val e = Markz.kpis(d, s0, s0, Markz.matrix(s0, s0, false), false)
        assertEquals("€677.29", e["kpi-baseline"])
        assertEquals("Sheet MROUND: €750.00", e["kpi-baseline-sub"])
        assertEquals("€472.2–954.77", e["kpi-range"])
    }

    @Test fun theInspectorAndTheCsv() {
        val m = Markz.matrix(s0, s0, true)
        val (head, math) = Markz.inspector(d, m.center)
        assertEquals("Baseline Center: EUR/BRL 6.50 (USD 5.93) • Target 14.98%", head)
        assertEquals("Real: 10.95% → Post-Tax (17%): 9.09% → Net MoM (2.0% spread): €677.29 (Sheet: €750.00)", math)
        assertTrue(Markz.inspector(d, m.rows[0].cells[0]).first.startsWith("Scenario: EUR/BRL 5.52 (USD 5.04) • Target 12.73%"))
        val csv = Markz.csv(d, m).lines()
        assertEquals(8, csv.size)
        assertEquals("USD/BRL,EUR/BRL,12.73% Target (8.78% Real),13.48% Target (9.50% Real),14.23% Target (10.23% Real),14.98% Target (10.95% Real),15.73% Target (11.68% Real),16.48% Target (12.40% Real),17.23% Target (13.12% Real)", csv[0])
        assertEquals("\"5.93\",\"6.50\",\"500.00\",\"500.00\",\"750.00\",\"750.00\",\"750.00\",\"750.00\",\"750.00\"", csv[4])
    }

    @Test fun theHeatmapBucketsAreThePagesEdges() {
        assertEquals(0, Markz.heat(d, 900.0, 677.0, isCenter = true))
        // the scale is baseline / 2500: at a baseline of 2500 the edges are the page's euros
        assertEquals(-3, Markz.heat(d, 2500.0 - 650, 2500.0, false))
        assertEquals(-2, Markz.heat(d, 2500.0 - 500, 2500.0, false))
        assertEquals(-1, Markz.heat(d, 2500.0 - 150, 2500.0, false))
        assertEquals(0, Markz.heat(d, 2500.0 + 149, 2500.0, false))
        assertEquals(1, Markz.heat(d, 2500.0 + 150, 2500.0, false))
        assertEquals(2, Markz.heat(d, 2500.0 + 400, 2500.0, false))
        assertEquals(3, Markz.heat(d, 2500.0 + 650, 2500.0, false))
        assertEquals(-3, Markz.heat(d, 0.0 - 700, 0.0, false))
    }

    @Test fun everyFieldBehavesAsThePagesHandler() {
        // the AuM follows the currency typed, at the centre rates
        val eur = Markz.edit(s0, s0, Markz.AUM_EUR, 100000.0)
        assertEquals(Markz.ANCHOR_EUR, eur.aumAnchor); assertEquals(650000.0, eur.aumBrl, 1e-6); assertEquals(650000.0 / 5.93, eur.aumUsd, 1e-6)
        val brl = Markz.edit(s0, s0, Markz.AUM_BRL, 1186000.0)
        assertEquals(200000.0, brl.aumUsd, 1e-6)
        // a net return or a monthly figure solves back to the gross target
        assertEquals(16.1155, Markz.edit(s0, s0, Markz.RETURN_NET, 10.0).centerYieldPct, 1e-3)
        assertEquals(20.3880, Markz.edit(s0, s0, Markz.RETURN_EUR_MOM, 1000.0).centerYieldPct, 1e-3)
        val back = Markz.edit(s0, s0, Markz.RETURN_EUR_MOM, 677.2899355943354)
        assertEquals(14.98, back.centerYieldPct, 1e-9)
        assertEquals(14.98, Markz.edit(s0, s0, Markz.RETURN_USD_MOM, 742.39200360256).centerYieldPct, 1e-9)
        assertEquals(14.98, Markz.edit(s0, s0, Markz.RETURN_BRL_MOM, 4492.229164656306).centerYieldPct, 1e-9)
        // the cross: EUR/BRL typed moves EUR/USD, the AuM follows; a zero rate or sheet step is refused
        val cross = Markz.edit(s0, s0, Markz.EUR_BRL, 7.0)
        assertEquals(7.0 / 5.93, cross.centerEurUsd, 1e-12)
        assertEquals("7.00", Markz.shown(cross, s0, Markz.EUR_BRL))
        assertEquals(s0, Markz.edit(s0, s0, Markz.USD_BRL, 0.0))
        assertEquals(s0, Markz.edit(s0, s0, Markz.ROUND_STEP, 0.0))
        assertEquals(100.0, Markz.edit(s0, s0, Markz.YIELD_STEP, 100.0).yieldStepBps, 0.0)
        // every field shows the page's format
        val shown = Markz.FIELDS.associateWith { Markz.shown(s0, s0, it) }
        assertEquals("100,000.00", shown[Markz.AUM_USD]); assertEquals("91,230.77", shown[Markz.AUM_EUR]); assertEquals("593,000", shown[Markz.AUM_BRL])
        assertEquals("9.09", shown[Markz.RETURN_NET]); assertEquals("742.39", shown[Markz.RETURN_USD_MOM]); assertEquals("677.29", shown[Markz.RETURN_EUR_MOM]); assertEquals("4,492.23", shown[Markz.RETURN_BRL_MOM])
        assertEquals("14.98", shown[Markz.GROSS]); assertEquals("3.63", shown[Markz.INFLATION]); assertEquals("17.0", shown[Markz.TAX])
        assertEquals("1.0961", shown[Markz.EUR_USD]); assertEquals("5.93", shown[Markz.USD_BRL]); assertEquals("6.50", shown[Markz.EUR_BRL]); assertEquals("2.0", shown[Markz.SPREAD])
        assertEquals("75", shown[Markz.YIELD_STEP]); assertEquals("5", shown[Markz.FX_STEP]); assertEquals("250", shown[Markz.ROUND_STEP])
    }

    @Test fun theSmartParserReadsWhatThePageReads() {
        assertEquals(100000.0, Markz.parseSmart("100k")!!, 0.0)
        assertEquals(1500000.0, Markz.parseSmart("1.5m")!!, 0.0)
        assertEquals(100000.0, Markz.parseSmart("100,000.00")!!, 0.0)
        assertEquals(14.98, Markz.parseSmart("14,98")!!, 1e-12)
        assertEquals(593000.0, Markz.parseSmart("593,000")!!, 0.0)
        assertEquals(593000.5, Markz.parseSmart("593.000,5")!!, 1e-9)
        assertEquals(0.5, Markz.parseSmart("0,500")!!, 1e-12)
        assertEquals(1234567.0, Markz.parseSmart("1.234.567")!!, 0.0)
        assertEquals(593000.0, Markz.parseSmart("R$ 593,000")!!, 0.0)
        assertEquals(250.0, Markz.parseSmart("€250")!!, 0.0)
        assertNull(Markz.parseSmart("")); assertNull(Markz.parseSmart("  ")); assertNull(Markz.parseSmart("-5")); assertNull(Markz.parseSmart("abc")); assertNull(Markz.parseSmart(null))
        assertEquals(750.0, Markz.mround(677.29, 250.0), 0.0)
        assertEquals(500.0, Markz.mround(624.99, 250.0), 0.0)
        assertEquals(677.29, Markz.mround(677.29, 0.0), 0.0)
    }

    @Test fun theMarkovitzPageFollowsTheTarget() {
        assertEquals(listOf("1.32", "1.24", "1.11"), d.portfolios.map { Markz.sharpe(it, d.rf) })
        assertEquals(listOf(false, false, true), d.portfolios.map { Markz.isActive(it, s0) })
        val (cx, cy, lx) = Markz.targetDot(d.diagram.target, 14.98)
        assertEquals(273.5059, cx, 1e-3); assertEquals(67.8447, cy, 1e-3); assertEquals(cx + 9, lx, 1e-9)
        val far = Markz.targetDot(d.diagram.target, 30.0)
        assertEquals(357.0, far.first, 1e-9); assertEquals(far.first - 82, far.third, 1e-9)
        assertEquals(122.0, Markz.targetDot(d.diagram.target, 0.0).first, 1e-9)
    }
}
