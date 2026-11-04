package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** The PM EM Bonds calculator and carry trade (#1447, #1462): the maths against the owner's image, the declarations, and the panels on a fake network. */
class EmCalcTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val today = LocalDate.of(2026, 10, 30)
    // the image's centre: gross 14.98 %, Germany inflation 3.63 %, NAV EUR 200,000 = BRL 1,234,569.80 = USD 224,925.27
    private val live = EmCalc.Live(0.1498, 0.027, 0.0363, 1234569.80 / 224925.27, 1234569.80 / 200000.0, "", emptyList())
    private val inputs = EmCalc.Inputs()

    private class Fake(val answer: (String) -> String?) : Http {
        override fun get(url: String): String = answer(url) ?: throw IOException("no route")
        override fun post(url: String): String = throw IOException("no post")
    }

    @Test fun realYieldIsTheFisherRatioNotTheDifference() {
        assertEquals(0.109524, EmCalc.real(0.1498, 0.0363), 1e-6) // the image's 10.95 %, not 14.98 - 3.63 = 11.35
        assertEquals(0.0878, EmCalc.real(0.1273, 0.0363), 5e-5)   // 8.78 %
    }

    @Test fun centreCellByHandIsTheImagesCentreValue() {
        // 200,000 * (1.1498 / 1.0363 - 1) / 12 = 1,825.40 a month; x (1 - 17 %) x (1 - 2 %) = 1,484.78; shown rounded to 250 as 1,500.00
        val c = EmCalc.monthlyNet(200000.0, 0.1498, 0.0363, live.brlEur, live.brlEur, 0.17, 0.02)
        assertEquals(1825.4 * 0.83 * 0.98, c, 0.5)
        assertEquals(1484.78, c, 0.01)
        assertEquals(1500.0, EmCalc.roundTo(c, 250.0), 0.0)
        assertEquals(c, EmCalc.roundTo(c, 0.0), 0.0) // rounding off shows the exact figure
        assertEquals(1500.0, EmCalc.roundTo(1375.0, 250.0), 0.0) // half up
        assertEquals(1250.0, EmCalc.roundTo(1374.99, 250.0), 0.0)
    }

    @Test fun theWholeGridIsTheImage() {
        val g = EmCalc.grid(live, inputs)
        val image = listOf(
            listOf(1500, 1500, 1750, 1750, 1750, 2000, 2000), listOf(1250, 1500, 1500, 1750, 1750, 1750, 2000), listOf(1250, 1250, 1500, 1500, 1750, 1750, 1750),
            listOf(1250, 1250, 1500, 1500, 1500, 1750, 1750), listOf(1250, 1250, 1250, 1500, 1500, 1500, 1750), listOf(1000, 1250, 1250, 1250, 1500, 1500, 1500),
            listOf(1000, 1000, 1250, 1250, 1500, 1500, 1500),
        )
        assertEquals(image, g.cells.map { r -> r.map { it.toInt() } })
        // #1462 orientation, against the image: the axes ascend (yield left to right, FX top to bottom), the centre is the middle row and column,
        // so the most (2,000) is top right and the least (1,000) bottom left
        assertEquals(g.yields.sorted(), g.yields); assertEquals(g.fxUsd.sorted(), g.fxUsd); assertEquals(g.fxEur.sorted(), g.fxEur)
        assertEquals(live.yHigh, g.yields[3], 1e-12); assertEquals(live.brlUsd, g.fxUsd[3], 1e-12); assertEquals(live.brlEur, g.fxEur[3], 1e-12)
        assertEquals(2000, g.cells[0][6].toInt()); assertEquals(1000, g.cells[6][0].toInt()); assertEquals(2000, g.cells.flatten().maxOrNull()!!.toInt()); assertEquals(1000, g.cells.flatten().minOrNull()!!.toInt())
        for (r in 0..6) for (c in 1..6) assertTrue(g.cells[r][c] >= g.cells[r][c - 1])      // a higher yield never pays less
        for (r in 1..6) for (c in 0..6) assertTrue(g.cells[r][c] <= g.cells[r - 1][c])      // a higher BRL level never pays more EUR
        assertEquals(listOf("4.67", "4.94", "5.21", "5.49", "5.76", "6.04", "6.31"), g.fxUsd.map { EmCalc.fx(it) })
        assertEquals(listOf("5.25", "5.56", "5.86", "6.17", "6.48", "6.79", "7.10"), g.fxEur.map { EmCalc.fx(it) })
        assertEquals(listOf("12.73%", "13.48%", "14.23%", "14.98%", "15.73%", "16.48%", "17.23%"), g.yields.map { EmCalc.pct(it) })
    }

    @Test fun heatIsRedBelowTheCentreGreenAboveAndZeroAtTheCentre() {
        val h = EmCalc.heat(EmCalc.grid(live, inputs).cells)
        assertEquals(0f, h[3][3]!!, 0f)
        assertTrue(h[0][6]!! == 1f && h[6][0]!! == -1f && h[0][4]!! > 0f && h[6][4]!! == 0f)
        assertEquals(0f, EmCalc.heat(listOf(listOf(5.0, 5.0, 5.0), listOf(5.0, 5.0, 5.0), listOf(5.0, 5.0, 5.0)))[1][1]!!, 0f)
    }

    @Test fun lossesAreNotTaxed() {
        assertEquals(-100.0, EmCalc.netOf(-100.0, 0.17, 0.02), 0.0)
        assertEquals(100 * 0.83 * 0.98, EmCalc.netOf(100.0, 0.17, 0.02), 1e-9)
    }

    @Test fun declaredDashboardsGroupByRiskAndTheA1WeightsFollowTheDxyBasket() {
        val pm = dashboards.filter { it.tab == "pm" }.map { it.id }
        assertTrue(pm.containsAll(listOf("pm_a1_cash", "pm_a2_low", "pm_b1_mid", "pm_c1_risky", "pm_d1_em_bonds", "pm_d0_carry")))
        for (id in listOf("pm_a1_cash", "pm_a2_low", "pm_b1_mid", "pm_c1_risky", "pm_d1_em_bonds")) assertEquals(id, 100, Dashboards.byId(dashboards, id)!!.legs.sumOf { it.second })
        val dxy = org.json.JSONObject(DataFiles.universeJson).getJSONObject("fx_matrix").getJSONObject("dxy").getJSONArray("legs")
        val a1 = Dashboards.byId(dashboards, "pm_a1_cash")!!.legs
        assertEquals(dxy.length(), a1.size)
        for (k in 0 until dxy.length()) {
            val l = dxy.getJSONObject(k)
            assertTrue(a1[k].first.startsWith(l.getString("ccy")))
            assertEquals(l.getDouble("weight") * 100, a1[k].second.toDouble(), 1.0) // whole percents of the ICE weight
        }
        assertEquals(listOf(30, 70, 0, 0), Dashboards.byId(dashboards, "pm_a2_low")!!.legs.map { it.second })
        assertEquals(listOf(0, 0, 100), Dashboards.byId(dashboards, "pm_d1_em_bonds")!!.legs.map { it.second })
    }

    @Test fun theMatrixFitsOnePhoneScreenInTheDenseRamp() {
        // 312dp is the card body of a 360dp phone: 9 columns share it, no scrolling; the numerals stay within the ramp's small mono (11) and its floor (7)
        val w = FinLogic.matrixWidths(312f, 9)
        assertEquals(9, w.size); assertEquals(312f, w.sum(), 0.01f)
        val f = FinLogic.matrixFont(w[2], "1,500.00".length)
        assertTrue(f in 7f..11f)
        assertTrue(f * 0.6f * "1,500.00".length <= w[2] + 0.01f)
        assertEquals(11f, FinLogic.matrixFont(100f, 8), 0f) // never larger than the ramp's small size
        assertEquals(7f, FinLogic.matrixFont(10f, 8), 0f)
        // 10 rows of 20dp = 200dp tall
        assertEquals(200, (1 + 9) * 20)
    }

    @Test fun everyPmPortfolioHasARiskTierByEquityShare() {
        val tabs = RiskTabs.parse(DataFiles.dashboardsJson)
        assertEquals(listOf("all", "A", "B", "C", "D"), tabs.map { it.id })
        assertEquals(listOf("All", "A Low", "B Middle", "C Risky", "D High"), tabs.map { it.label })
        assertEquals(listOf("A Low risk", "B Middle risk", "C Risky", "D High risk"), tabs.drop(1).map { it.header })
        val pm = dashboards.filter { it.tab == "pm" && it.id != "portfolio_risk" }
        assertEquals(14, pm.size)
        for (d in pm) {
            assertTrue("${d.id} has no risk tier", d.risk in tabs.drop(1).map { it.id })
            if (d.riskBasis.isEmpty()) assertEquals("${d.id} tier by equity share ${d.equityPct}", RiskTabs.tierOf(tabs, d.equityPct), d.risk)
            else assertTrue("${d.id} declares why", d.riskBasis.isNotBlank())
        }
        // more equity = higher risk: the tier never falls as the equity share rises
        val ordered = pm.filter { it.equityPct >= 0 && it.riskBasis.isEmpty() }.sortedBy { it.equityPct }
        assertEquals(ordered.map { it.risk }.sorted(), ordered.map { it.risk })
        assertEquals("A", Dashboards.byId(dashboards, "mp_70_30")!!.risk); assertEquals("B", Dashboards.byId(dashboards, "mp_60_40")!!.risk)
        assertEquals("C", Dashboards.byId(dashboards, "mp_40_60")!!.risk); assertEquals("D", Dashboards.byId(dashboards, "mp_30_70_DM_EM")!!.risk)
        assertEquals("D", Dashboards.byId(dashboards, "pm_d1_em_bonds")!!.risk)
        // the stacks' section titles are the declared headers, in order, each before its tier's portfolios
        val stack = org.json.JSONObject(DataFiles.buildJson).getJSONObject("ui").getJSONObject("stacks").getJSONArray("pm")
        var tier = ""; val seen = ArrayList<String>()
        for (k in 0 until stack.length()) {
            val e = stack.getJSONObject(k)
            if (e.getString("kind") == "section_title") { seen += e.getString("title"); tier = tabs.first { it.header == e.getString("title") }.id }
            else if (e.getString("dashboard") != "portfolio_risk") assertEquals(e.getString("dashboard"), tier, Dashboards.byId(dashboards, e.getString("dashboard"))!!.risk)
        }
        assertEquals(tabs.drop(1).map { it.header }, seen)
    }

    @Test fun allShowsOneHeaderPerTierAndATierTabItsOwnCardsAlone() {
        val tabs = RiskTabs.parse(DataFiles.dashboardsJson)
        val cards = dashboards.filter { it.tab == "pm" }.map { it.id }
        val riskOf = { id: String -> Dashboards.byId(dashboards, id)!!.risk }
        val all = RiskTabs.groups(cards, { it }, riskOf, tabs, "all")
        assertEquals(listOf("", "A Low risk", "B Middle risk", "C Risky", "D High risk"), all.map { it.first })
        assertEquals(cards.size, all.sumOf { it.second.size })
        assertEquals(listOf("portfolio_risk"), all[0].second)
        val c = RiskTabs.groups(cards, { it }, riskOf, tabs, "C")
        assertEquals(listOf(""), c.map { it.first }); assertEquals(setOf("mp_40_60", "mp_30_70", "pm_c1_risky"), c[0].second.toSet())
        assertEquals(setOf("pm_a1_cash", "pm_a2_low", "mp_70_30"), RiskTabs.groups(cards, { it }, riskOf, tabs, "A")[0].second.toSet())
    }

    @Test fun theCalculatorPanelIsFirstOnTheD1DashboardSoItsMatrixIsOnTheFirstScreen() {
        assertEquals("calc", Dashboards.byId(dashboards, "pm_d1_em_bonds")!!.panels.first().id)
    }

    private fun fake() = Fake { u ->
        when {
            u.contains("db.nomics.world") -> """{"series":{"docs":[
                {"series_code":"BRA.M.IRLT.PA._Z._Z._Z","period":["2026-04","2026-05"],"value":[14.5,14.98]},
                {"series_code":"DEU.M.IRLT.PA._Z._Z._Z","period":["2026-04","2026-05"],"value":[2.6,2.7]},
                {"series_code":"DEU.M.CP.IX._Z._Z._Z","period":["2025-05","2026-05"],"value":[100.0,103.63]}]}}"""
            u.contains("frankfurter") -> """{"rates":{"2026-10-28":{"BRL":5.3,"EUR":0.85},"2026-10-29":{"BRL":5.4888,"EUR":0.8892}}}"""
            else -> null
        }
    }
    private fun load(d: String, p: String, http: Http): PanelData {
        val dash = Dashboards.byId(dashboards, d)!!
        val panel = dash.panels.first { it.id == p }
        return Engine.load(panel, sources.first { it.id == panel.source }, http, today, model = ModelCtx(sources, dash, null, UserLists(), "2026-10-30T12:00Z"))
    }

    @Test fun theCalculatorPanelOnLiveFeedsDrawsTheMatrixWithItsCentreBold() {
        val d = load("pm_d1_em_bonds", "calc", fake())
        assertEquals(listOf("", "Gross Yield", "12.73%", "13.48%", "14.23%", "14.98%", "15.73%", "16.48%", "17.23%"), d.columns)
        assertEquals(9, d.rows.size)
        assertEquals(listOf("", "Inflation") + List(7) { "3.63%" }, d.rows[0])
        assertEquals(listOf("USD", "EUR / (-Infl) Yield", "8.78%"), d.rows[1].take(3))
        assertEquals("10.95%", d.rows[1][5])
        assertEquals(listOf("4.67", "5.25", "1,500.00", "1,500.00", "1,750.00", "1,750.00", "1,750.00", "2,000.00", "2,000.00"), d.rows[2])
        assertEquals(listOf("6.31", "7.10", "1,000.00", "1,000.00", "1,250.00", "1,250.00", "1,500.00", "1,500.00", "1,500.00"), d.rows[8])
        assertEquals(listOf("5.49", "6.17", "1,250.00", "1,250.00", "1,500.00", "1,500.00", "1,500.00", "1,750.00", "1,750.00"), d.rows[5])
        assertTrue(d.heat.size == d.rows.size && d.heat[0].all { it == null } && d.heat[5][5] == 0f)
        assertTrue((5 to 5) in d.bold && (1 to 5) in d.bold && (-1 to 5) in d.bold && (5 to 0) in d.bold && (5 to 1) in d.bold)
        assertTrue(d.servedBy.contains("DBnomics") && d.note.contains("rounded to 250") && d.note.contains("CPI 2026-05"))
    }

    @Test fun theInputsPanelShowsTheNavInThreeCurrenciesTheTaxesAndTheCurves() {
        val d = load("pm_d1_em_bonds", "inputs", fake())
        val m = d.rows.associate { it[0] to it[1] }
        assertEquals("200,000.00", m["NAV (Gross) eur"])
        assertEquals("1,234,569.80".length, m["NAV (Gross) brl"]!!.length)
        assertEquals("17%", m["Tax CG"]); assertEquals("2%", m["Tax Exch Spread"])
        assertEquals("Brazil Gov Bonds 14.98%", m["Gross Yield"]); assertEquals("Germany 3.63%", m["Inflation"])
        assertEquals("Now", m["Center"]); assertEquals("5.0%", m["Exch step"]); assertTrue(m["Yield step"]!!.contains("75 bps"))
    }

    @Test fun theCarryPanelReusesTheEngineAndTheBreakevenWipesTheCarryOut() {
        val d = load("pm_d0_carry", "carry", fake())
        val m = d.rows.associate { it[0] to it[1] }
        assertEquals("12.28%", m["Carry (high-yielder - funding)"])
        val yh = 0.1498; val yf = 0.027
        val be = 1 - (1 + yf) / (1 + yh)
        assertEquals(EmCalc.pct(be), m["Breakeven BRL fall vs EUR, 12M"])
        val flat = EmCalc.monthlyNet(200000.0, yh, yf, 1.0, 1.0, 0.17, 0.02)
        assertEquals(EmCalc.money(flat), m["Net carry EUR per month (after tax and spread)"])
        // a BRL fall of exactly the breakeven leaves nothing: gross income + the FX loss = 0
        assertEquals(0.0, 200000.0 * ((1 + EmCalc.real(yh, yf)) * (1 - be) - 1) / 12.0, 1e-6)
        assertEquals(EmCalc.money(EmCalc.netOf(200000.0 * ((1 + EmCalc.real(yh, yf)) * 0.9 - 1) / 12.0, 0.17, 0.02)), m["Net EUR per month if BRL -10% in 12M"])
        assertNotEquals(m["Net EUR per month if BRL -10% in 12M"], m["Net EUR per month if BRL +10% in 12M"])
    }

    @Test fun frankfurterDownFallsBackToTheEcbRatesOnDbnomics() {
        val base = fake()
        val http = Fake { u ->
            when {
                u.contains("frankfurter") -> null
                u.contains("EXR") -> """{"series":{"docs":[
                    {"series_code":"D.BRL.EUR.SP00.A","period":["2026-10-29","2026-10-30"],"value":[6.1,6.1728]},
                    {"series_code":"D.USD.EUR.SP00.A","period":["2026-10-29","2026-10-30"],"value":[1.12,1.1246]}]}}"""
                else -> base.get(u)
            }
        }
        val d = load("pm_d1_em_bonds", "calc", http)
        assertEquals("6.17", d.rows[5][1]); assertEquals("5.49", d.rows[5][0])
        assertTrue(d.note.contains("Frankfurter unavailable") && d.servedBy == "DBnomics")
    }

    @Test fun noFeedsIsAnErrorStateNamingTheReason() {
        try { load("pm_d1_em_bonds", "calc", Fake { null }); throw AssertionError("expected an error") } catch (e: IOException) { assertTrue(e.message!!.contains("no Brazil 10Y yield")) }
    }
}
