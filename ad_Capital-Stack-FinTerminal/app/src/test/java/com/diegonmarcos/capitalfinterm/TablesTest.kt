package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** The per-item tables (Tables.kt) on the real declarations and a fake network, plus the pure maths each one rests on. */
class TablesTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val today = LocalDate.of(2026, 10, 30)
    private val api = ApiRoute(sources.first { it.primary }, "http://mesh-base")

    private class Fake(val answer: (String) -> String?) : Http {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun get(url: String): String {
            asked += url
            return answer(url) ?: throw IOException("no route")
        }

        override fun post(url: String): String = throw IOException("no post")
    }

    private fun load(d: String, p: String, http: Http, api: ApiRoute? = this.api): PanelData {
        val dash = Dashboards.byId(dashboards, d)!!
        val panel = dash.panels.first { it.id == p }
        return Engine.load(panel, sources.first { it.id == panel.source }, http, today, model = ModelCtx(sources, dash, api, UserLists(), "2026-10-30T12:00Z"))
    }

    private fun v1Bars(vararg b: Pair<String, Double>) = """{"bars":[${b.joinToString(",") { """{"t":"${it.first}","c":${it.second}}""" }}]}"""
    private fun cboeBars(vararg b: Pair<String, Double>) = """{"data":[${b.joinToString(",") { """{"date":"${it.first}","open":${it.second},"high":${it.second},"low":${it.second},"close":${it.second}}""" }}]}"""
    private fun d(s: String) = LocalDate.parse(s)

    // ------------------------------------------------------------------ performance

    @Test fun performanceRowsComeFromTheContainerThroughPerformanceRow() {
        val http = Fake { u -> if (u.startsWith("http://mesh-base/v1/history?symbol=XLK")) v1Bars("2025-10-30" to 100.0, "2026-10-30" to 110.0) else null }
        val data = load("eq_dm_us", "sectors", http)
        val xlk = data.rows.first { it[0] == "XLK Technology" }
        assertEquals(listOf("Name", "Last") + Performance.COLUMNS, data.columns)
        assertEquals("+10.00%", xlk[2 + Performance.COLUMNS.indexOf("1Y")])
        assertEquals(FinFormat.DASH, xlk[2 + Performance.COLUMNS.indexOf("10Y")])
        assertTrue(data.note, data.note.contains("Technology (XLK) ") && data.note.contains("history from 2025-10-30"))
        // an item nobody answers is a row of dashes with its reason in the note, never dropped
        val xlf = data.rows.first { it[0] == "XLF Financials" }
        assertTrue(xlf.drop(1).all { it == FinFormat.DASH })
        assertTrue(data.note, data.note.contains("Financials (XLF): ") && data.note.contains("no answer"))
        assertEquals(12, data.rows.size)
        // one-line names: ticker + short label in the cell, the declared full name in the detail view, row for row
        assertEquals(data.rows.size, data.details.size)
        assertEquals("Technology (XLK)", data.details[data.rows.indexOf(xlk)])
        assertTrue(data.rows.map { it[0] }.toString(), data.rows.all { it[0].length <= 5 + 1 + Tables.SHORT_MAX })
    }

    @Test fun shortNamesPutTheTickerFirstAndCutLongLabels() {
        assertEquals("XLK Technology", Tables.shortName("Technology (XLK)"))
        assertEquals("XLY Cons Disc", Tables.shortName("Consumer Discretionary (XLY)"))
        assertEquals("XLC Comm Serv", Tables.shortName("Communication Services (XLC)"))
        assertEquals("XLV Health Care", Tables.shortName("Health Care (XLV)"))
        assertEquals("DAX", Tables.shortName("DAX"))
        assertEquals("Emer Mark", Tables.shortName("Emerging Markets Bonds"))
    }

    @Test fun whenTheMeshIsDownTheDirectSourceAnswersUnderTheMappedSymbol() {
        val http = Fake { u -> if (u.endsWith("/charts/historical/EWG.json")) cboeBars("2026-09-30" to 50.0, "2026-10-30" to 55.0) else null }
        val data = load("world_equity_indices", "global", http, api = null)
        val dax = data.rows.first { it[0] == "DAX" }
        assertEquals(Engine.sig(55.0), dax[1])
        assertEquals("+10.00%", dax[2 + Performance.COLUMNS.indexOf("MTD")])
        assertTrue(http.asked.none { it.contains("mesh-base") })
        assertEquals(32, data.rows.size)
        // the energy item with no direct proxy is never asked of Cboe
        val e = Fake { null }
        // and when no item answers at all the panel is an error state, not a table of dashes
        try { load("commodities", "energy_perf", e, api = null); org.junit.Assert.fail("no item answered") } catch (x: IOException) { assertTrue(x.message.orEmpty().contains("no answer")) }
        assertFalse(e.asked.toString(), e.asked.any { it.contains("HO=F") })
        assertTrue(e.asked.any { it.endsWith("/charts/historical/USO.json") })
    }

    @Test fun trailingYearsAndRiskColumns() {
        val s = listOf(d("2000-10-30") to 100.0, d("2025-10-30") to 400.0, d("2026-10-30") to 400.0)
        assertEquals(Math.pow(4.0, 1.0 / 25) - 1, Performance.trailing(s, 25)!!, 1e-12)
        assertNull(Performance.trailing(s, 30))
        assertEquals(-0.25, Tables.maxDrawdown1y(listOf(d("2026-01-01") to 100.0, d("2026-02-01") to 120.0, d("2026-03-01") to 90.0, d("2026-04-01") to 100.0))!!, 1e-12)
        assertEquals(0.0, Tables.maxDrawdown1y(listOf(d("2026-01-01") to 100.0, d("2026-02-01") to 120.0))!!, 0.0)
        val flat = (0 until 30).map { d("2026-01-01").plusDays(it.toLong()) to 100.0 }
        assertEquals(0.0, Tables.vol1y(flat)!!, 1e-12)
        assertNull(Tables.vol1y(flat.take(10)))
        assertEquals(Performance.trailing(s, 25), Tables.column("25Y", s, Performance.row(s)))
    }

    // ------------------------------------------------------------------ model portfolio

    @Test fun portfolioIsRebalancedDailyNotBoughtAndHeld() {
        val a = listOf(d("2026-01-01") to 100.0, d("2026-01-02") to 110.0, d("2026-01-03") to 121.0)
        val b = listOf(d("2026-01-01") to 100.0, d("2026-01-02") to 100.0, d("2026-01-03") to 100.0)
        val v = Tables.rebalanced(listOf(60.0 to a, 40.0 to b))
        assertEquals(106.0, v[1].second, 1e-9)
        assertEquals(106.0 * (0.6 * 1.1 + 0.4), v[2].second, 1e-9) // buy and hold would be 112.6
        // only the dates every leg has
        assertEquals(2, Tables.rebalanced(listOf(50.0 to a, 50.0 to b.drop(1))).size)
        assertTrue(Tables.rebalanced(listOf(50.0 to a, 50.0 to emptyList())).isEmpty())
    }

    @Test fun modelPortfolioPerformanceRowsArePortfolioThenLegs() {
        val http = Fake { u ->
            when {
                u.contains("symbol=AGG") -> v1Bars("2026-10-29" to 100.0, "2026-10-30" to 101.0)
                u.contains("symbol=ACWI") -> v1Bars("2026-10-29" to 100.0, "2026-10-30" to 110.0)
                else -> null
            }
        }
        val data = load("mp_60_40", "perf", http)
        assertEquals(listOf("Portfolio 60/40", "AGG Bonds 60%", "ACWI Equity 40%"), data.rows.map { it[0] })
        assertEquals(listOf("Portfolio 60/40, daily rebalanced", "Bonds 60% (AGG)", "Equity 40% (ACWI)"), data.details)
        assertEquals(Engine.sig(100.0 * (0.6 * 1.01 + 0.4 * 1.10)), data.rows[0][1])
        assertEquals(listOf("Name", "Last") + Performance.COLUMNS + listOf(Tables.COL_VOL, Tables.COL_MDD), data.columns)
    }

    @Test fun riskComparisonStacksEachPortfolioHeadlineOnTheSameProxies() {
        val http = Fake { u ->
            when {
                u.contains("symbol=AGG") -> v1Bars("2026-10-29" to 100.0, "2026-10-30" to 101.0)
                u.contains("symbol=ACWI") -> v1Bars("2026-10-29" to 100.0, "2026-10-30" to 110.0)
                else -> null
            }
        }
        val data = load("mp_40_60", "risk", http)
        assertEquals(listOf("Portfolio 40/60", "Portfolio 0/100"), data.rows.map { it[0] })
        assertEquals(Engine.sig(100.0 * (0.4 * 1.01 + 0.6 * 1.10)), data.rows[0][1])
        assertEquals(Engine.sig(110.0), data.rows[1][1])
        assertEquals(listOf("Name", "Last", "1Y", "3Y", "10Y", Tables.COL_VOL, Tables.COL_MDD), data.columns)
    }

    // ------------------------------------------------------------------ yields matrix

    @Test fun averagesAreGdpWeightedOverTheCountriesWithAValue() {
        assertEquals((30507 * 4.0 + 4744 * 2.0) / (30507 + 4744), Tables.weighted(listOf(30507.0 to 4.0, 4744.0 to 2.0))!!, 1e-12)
        assertEquals(2.0, Tables.weighted(listOf(30507.0 to null, 4744.0 to 2.0))!!, 1e-12)
        assertNull(Tables.weighted(listOf(1.0 to null)))
    }

    @Test fun spotCellsAreExactTenorsAndForwardCellsComeFromForward() {
        val c = listOf(1.0 to 3.0, 2.0 to 4.0, 10.0 to 5.0)
        assertEquals(4.0, Tables.cell(c, 2.0, false, 1.0)!!, 0.0)
        assertNull(Tables.cell(c, 5.0, false, 1.0))
        assertEquals(Forward.rate(c, 1.0, 2.0), Tables.cell(c, 2.0, true, 1.0)!!, 1e-12)
        assertNull(Tables.cell(emptyList(), 2.0, true, 1.0))
    }

    @Test fun theMatrixHasAveragesThenEveryCountryAndSaysWhyACellIsEmpty() {
        val curve = { y: Double -> """{"asof":"2026-10-29","points":[{"key":"3M","label":"3M","tenor_years":0.25,"value":$y},{"key":"10Y","label":"10Y","tenor_years":10,"value":${y + 1}}]}""" }
        val http = Fake { u ->
            when {
                u == "http://mesh-base/v1/curves/US" -> curve(4.0)
                u == "http://mesh-base/v1/curves/DE" -> curve(2.0)
                u == "http://mesh-base/v1/curves/BR" -> curve(10.0)
                else -> null
            }
        }
        val data = load("yields_matrix", "spot", http)
        assertEquals(listOf("Country", "Cov", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y"), data.columns)
        assertEquals(listOf("World avg", "DM avg", "EM avg"), data.rows.take(3).map { it[0] })
        assertEquals(25, data.rows.size)
        // US + DE hold 61 % of the DM GDP: the DM average is shown; BR alone is 6 % of EM and US+DE+BR 40 % of World: "—"
        val dm = (30507 * 4.0 + 4744 * 2.0) / (30507 + 4744)
        assertEquals(String.format(java.util.Locale.US, "%.2f", dm), data.rows[1][2])
        assertEquals(listOf("40%", "61%", "6%"), data.rows.take(3).map { it[1] })
        assertEquals(FinFormat.DASH, data.rows[2][2])
        assertEquals(FinFormat.DASH, data.rows[0][2])
        assertTrue(data.note, data.note.contains("insufficient coverage: World avg 40%, EM avg 6% < 60%"))
        assertEquals("33%", data.rows.first { it[0] == "United States" }[1])
        assertEquals(FinFormat.DASH, data.rows.first { it[0] == "United States" }[3])
        // China has no curve: every tenor is "—" (its Cov cell is its share of World GDP)
        assertTrue(data.rows.first { it[0] == "China" }.drop(2).all { it == FinFormat.DASH })
        assertTrue(data.note, data.note.contains("no curve: ") && data.note.contains("CN ("))
        assertTrue(data.note, data.note.contains("US 6M/1Y/2Y/3Y/5Y/7Y/20Y/30Y"))
        val fwd = load("yields_matrix", "fwd", http)
        val us = listOf(0.25 to 4.0, 10.0 to 5.0)
        assertEquals(String.format(java.util.Locale.US, "%.2f", Forward.rate(us, 1.0, 10.0)), fwd.rows.first { it[0] == "United States" }[9])
        assertTrue(fwd.note.startsWith("1y-forward"))
    }

    @Test fun anAverageOfTheUsAloneIsInsufficientCoverageNotTheUsYield() {
        // the CI-emulator case (mesh off, only the Treasury curve answers): World / DM must not repeat the US
        val http = Fake { u -> if (u == "http://mesh-base/v1/curves/US") """{"tenors":["3M","10Y"],"curve":{"country":"US","group":"dm","gdp_weight":0.53,"tenors":[{"tenor":"3M","value":4.16,"date":"2026-10-30","source":"treasury","method":"par"},{"tenor":"10Y","value":4.4,"date":"2026-10-30","source":"treasury","method":"par"},{"tenor":"6M","value":null,"date":null,"source":null,"method":"no free source declared for this cell"}]}}""" else null }
        val data = load("yields_matrix", "spot", http)
        assertEquals("4.16", data.rows.first { it[0] == "United States" }[2])
        assertEquals("4.40", data.rows.first { it[0] == "United States" }[9])
        for (r in data.rows.take(3)) assertTrue(r.toString(), r.drop(2).all { it == FinFormat.DASH })
        assertEquals(listOf("33%", "53%", "0%"), data.rows.take(3).map { it[1] })
        assertTrue(data.note, data.note.contains("insufficient coverage: World avg 33%, DM avg 53%, EM avg 0% < 60%"))
        assertEquals(0.6, Dashboards.byId(dashboards, "yields_matrix")!!.panels.first { it.id == "spot" }.fetch!!.getDouble("min_coverage_gdp"), 0.0)
    }

    @Test fun coverageIsTheGdpShareWithAValueAndGatesTheAverage() {
        val v = listOf(60.0 to 1.0, 40.0 to null)
        assertEquals(0.6, Tables.coverage(v), 1e-12)
        assertEquals(1.0, Tables.gatedAverage(v, 0.6)!!, 1e-12)
        assertNull(Tables.gatedAverage(listOf(59.0 to 1.0, 41.0 to null), 0.6))
        assertEquals(0.0, Tables.coverage(listOf(0.0 to 1.0)), 0.0)
    }

    @Test fun oneMatrixCallFillsEveryCountryAndSaysWhyEachCellIsEmpty() {
        val row = { cc: String, g: String, gdp: Double, cells: String -> """{"country":"$cc","group":"$g","gdp_usd":$gdp,"tenors":[$cells]}""" }
        val v = { t: String, x: Double -> """{"tenor":"$t","value":$x,"date":"2026-10-30","source":"s","method":"m"}""" }
        val n = { t: String, why: String -> """{"tenor":"$t","value":null,"date":null,"source":null,"method":"$why"}""" }
        val body = """{"asof":"2026-10-30","fill":{"cells":40,"filled":5,"rate":0.125},"rows":[""" + listOf(
            row("US", "dm", 30.0e12, listOf(v("3M", 4.0), v("10Y", 5.0)).joinToString(",")),
            row("DE", "dm", 5.0e12, listOf(n("3M", "no free source declared for this cell"), v("10Y", 3.4)).joinToString(",")),
            row("FR", "dm", 3.4e12, listOf(n("3M", "no free source declared for this cell"), v("10Y", 4.44)).joinToString(",")),
            row("CN", "em", 19.5e12, listOf(n("3M", "no free source declared for this cell"), v("10Y", 1.75)).joinToString(",")),
            row("EA", "reference", 0.0, v("10Y", 3.3)),
        ).joinToString(",") + "]}"
        val http = Fake { u -> if (u == "http://mesh-base/v1/curves/matrix") body else null }
        val data = load("yields_matrix", "spot", http)
        assertEquals(listOf("http://mesh-base/v1/curves/matrix"), http.asked)
        assertEquals("3.40", data.rows.first { it[0] == "Germany" }[9])
        assertEquals("4.44", data.rows.first { it[0] == "France" }[9])
        assertEquals("1.75", data.rows.first { it[0] == "China" }[9])
        assertEquals(FinFormat.DASH, data.rows.first { it[0] == "Germany" }[2])
        assertTrue(data.note, data.note.contains("13% of cells filled"))
        assertTrue(data.note, data.note.contains("no free source declared for this cell: DE 3M, FR 3M, CN 3M"))
        // the GDP weights are the container's (USD bn): US 30000 + DE 5000 + FR 3400 of DM
        val dm10 = (30000 * 5.0 + 5000 * 3.4 + 3400 * 4.44) / (30000 + 5000 + 3400.0)
        assertTrue(data.rows[1].toString(), data.rows[1][9] == FinFormat.DASH || data.rows[1][9] == String.format(java.util.Locale.US, "%.2f", dm10))
        // no row of the reference group, every declared country still a row
        assertEquals(25, data.rows.size)
    }

    @Test fun aMatrixCallWithoutRowsFallsBackToOneCallPerCountry() {
        assertNull(Tables.parseOne("""{"rows":[]}""", listOf("US"), "x"))
        val http = Fake { u -> if (u == "http://mesh-base/v1/curves/US") """{"curve":{"tenors":[{"tenor":"3M","value":4.1}]}}""" else null }
        val data = load("yields_matrix", "spot", http)
        assertEquals("4.10", data.rows.first { it[0] == "United States" }[2])
        assertTrue(http.asked.first() == "http://mesh-base/v1/curves/matrix")
    }

    @Test fun emUsdBondsAreOneRowPerCountryWithTheReasonForEachEmptyCell() {
        val body = """{"method":"local 10Y total return in USD","rows":[{"country":"BR","currency":"BRL","tenor":"10Y","yield_last":{"month":"2026-09","value":13.1},"usd_return_annualised":{"1y":0.0812,"10y":0.031,"25y":null},"null_reason":null},{"country":"SA","currency":null,"tenor":"10Y","yield_last":null,"usd_return_annualised":{"1y":null,"10y":null,"25y":null},"null_reason":"no free 10-year government yield series for this country"}]}"""
        val http = Fake { u -> if (u == "http://mesh-base/v1/bonds/em-usd") body else null }
        val data = load("yields_matrix", "em_usd", http)
        assertEquals(listOf("Country", "Ccy", "Yield", "1Y", "10Y", "25Y"), data.columns)
        assertEquals(listOf("BR", "BRL", "13.10", "+8.12%", "+3.10%", FinFormat.DASH), data.rows[0])
        assertTrue(data.rows[1].drop(1).all { it == FinFormat.DASH })
        assertTrue(data.note, data.note.contains("BR (history shorter than 25Y)") && data.note.contains("SA (no free 10-year"))
        assertEquals("2026-09", data.asOf)
    }

    @Test fun theEmUsdPanelIsRoutedToTheContainerAndSaysMeshOffWithoutIt() {
        val dash = Dashboards.byId(dashboards, "yields_matrix")!!
        val panel = dash.panels.first { it.id == "em_usd" }
        val fin = sources.first { it.primary }
        assertTrue(Priority.api(panel, dash, sources, mapOf(fin.fleetEndpoint to "http://m"), { true }) != null)
        val e = try { load("yields_matrix", "em_usd", Fake { null }, api = null); null } catch (x: IOException) { x }
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("mesh off"))
    }

    @Test fun theDeclaredMatrixIsTheOwnersCountryList() {
        val f = Dashboards.byId(dashboards, "yields_matrix")!!.panels.first { it.id == "spot" }.fetch!!
        val g = f.getJSONObject("groups")
        val dm = (0 until g.getJSONArray("DM").length()).map { g.getJSONArray("DM").getString(it) }
        assertEquals(listOf("US", "DE", "FR", "IT", "ES", "GB", "JP", "CA", "AU", "CH", "NL", "SE"), dm)
        assertEquals(10, g.getJSONArray("EM").length())
        assertEquals("3M", Tables.tenors(f).first().label)
        assertEquals("/v1/curves/{each}", f.getJSONObject("primary").getString("api"))
        assertEquals("30Y", Tables.tenors(f).last().label)
    }

    @Test fun worldBondsSpreadsAreThe10yOverThe2yTheBundAndTheUstInBp() {
        val m = PanelData(listOf("Country", "Cov", "2Y", "10Y"), listOf(
            listOf("United States", "52%", "4.56", "4.94"), listOf("Germany", "9%", "2.10", "2.60"), listOf("Italy", "4%", "—", "3.75")), emptyList(), emptyList(), "2026-10-30", "")
        val f = org.json.JSONObject("""{"labels":{"US":"United States","DE":"Germany","IT":"Italy"}}""")
        val d = Tables.spreads(m, f)
        assertEquals(Tables.SPREAD_COLS, d.columns)
        assertEquals(listOf("United States", "4.56", "4.94", "+38", "+234", "0"), d.rows[0])
        assertEquals(listOf("Germany", "2.10", "2.60", "+50", "0", "-234"), d.rows[1])
        assertEquals(listOf("Italy", "—", "3.75", "—", "+115", "-119"), d.rows[2])
    }

    // ------------------------------------------------------------------ FX vs PPP (#1374)

    @Test fun pppIsCarriedByRelativeInflationAndTheYearAgoWindowIsRealAdjusted() {
        assertEquals(3.4 * 1.10 / 1.05, FxPpp.pppToday(3.4, 100.0, 110.0, 200.0, 210.0), 1e-12)
        assertEquals(7.0 * 1.04 / 1.02, FxPpp.realAdjust(7.0, 100.0, 104.0, 100.0, 102.0), 1e-12)
        assertEquals(-50.0, FxPpp.pct(3.4, 6.8), 1e-12)
        val fx = listOf("2025-10-01" to 7.0, "2025-10-30" to 7.2, "2026-10-01" to 6.9, "2026-10-30" to 7.1)
        assertEquals(7.0, FxPpp.windowAvg(fx, d("2026-10-30"), 30)!!, 1e-12)
        val y = { v: List<Pair<Int, Double>> -> v.map { it.first.toString() to it.second } }
        // PPP base 2023 carried to 2025: CN prices +3%, US +5%
        val r = FxPpp.row(org.json.JSONObject("""{"cc":"CN","ccy":"CNY","label":"Chinese yuan","group":"em"}"""), fx, y(listOf(2023 to 3.5)),
            y(listOf(2023 to 100.0, 2024 to 101.0, 2025 to 103.0)), y(listOf(2023 to 140.0, 2024 to 143.0, 2025 to 147.0)), 30)
        val ppp = FxPpp.pppToday(3.5, 100.0, 103.0, 140.0, 147.0)
        assertEquals(ppp, r.getDouble("ppp"), 1e-12)
        assertEquals(FxPpp.pct(ppp, 7.1), r.getDouble("over_under_pct"), 1e-12)
        val ago = FxPpp.realAdjust(7.1, 101.0, 103.0, 143.0, 147.0)
        assertEquals(ago, r.getDouble("avg_window_year_ago_real"), 1e-12)
        assertEquals(FxPpp.pct(ago, 7.0), r.getDouble("real_change_pct"), 1e-12)
        val sa = FxPpp.row(org.json.JSONObject("""{"cc":"SA","ccy":"SAR","no_fx":"pegged, no ECB rate"}"""), emptyList(), emptyList(), emptyList(), emptyList(), 30)
        assertEquals("pegged, no ECB rate", sa.getJSONObject("null_reason").getString("spot"))
        val t = Tables.fxPppTable(Dashboards.byId(dashboards, "fx")!!.panels.first { it.id == "ppp" }, org.json.JSONArray().put(r).put(sa), "test")
        assertEquals(listOf("CNY", "7.1000", Engine.num(ppp, 4), FinFormat.signed(FxPpp.pct(ppp, 7.1), 1)), t.rows[0].take(4))
        assertTrue(t.note.contains("SAR spot: pegged, no ECB rate"))
    }

    // ------------------------------------------------------------------ G20 FX & PPP Matrix (#1396): the same fixture as fxmatrix.rs and fxmatrix.test.ts

    @Test fun fxMatrixRealMovingAveragesPppBigMacDiffsAndDxy() {
        val j = { s: String -> org.json.JSONObject(s) }
        assertEquals(7.0 * 1.10 / 1.05, FxMatrix.rppp(7.0, 100.0, 110.0, 200.0, 210.0), 1e-12)
        assertEquals(50.14348112, FxMatrix.dxy(50.14348112, listOf(0.576 to 1.0, 0.424 to 1.0)), 1e-12)
        assertEquals(50.14348112 * Math.pow(0.9, 0.6) * Math.pow(150.0, 0.4), FxMatrix.dxy(50.14348112, listOf(0.6 to 0.9, 0.4 to 150.0)), 1e-9)
        assertEquals(20.0, FxMatrix.diff(7.2, 6.0), 1e-12)
        val fx = listOf("2023-10-15" to 6.0, "2025-10-20" to 7.0, "2026-10-30" to 7.2)
        val y = { v: List<Pair<Int, Double>> -> v.map { it.first.toString() to it.second } }
        val cpi = y(listOf(2022 to 100.0, 2024 to 106.0, 2025 to 110.0)); val us = y(listOf(2022 to 200.0, 2024 to 210.0, 2025 to 214.0))
        val bm = listOf("2026-01-01" to 25.0, "2026-07-01" to 26.5); val bmUs = listOf("2026-07-01" to 6.22)
        val r = FxMatrix.row(j("""{"ccy":"CNY","cc":"CN","a3":"CHN","seg":"BRICS","label":"Chinese yuan"}"""), fx, y(listOf(2024 to 3.5)), cpi, us, bm, bmUs, 30, listOf(1, 3))
        val ma1 = 7.0 * (110.0 / 106.0) / (214.0 / 210.0); val ma3 = 6.0 * (110.0 / 100.0) / (214.0 / 200.0)
        val macro = 3.5 * (110.0 / 106.0) / (214.0 / 210.0); val retail = 26.5 / 6.22
        assertEquals(7.2, r.getDouble("today"), 1e-12)
        assertEquals(ma1, r.getDouble("ma_1y"), 1e-12)
        assertEquals(ma3, r.getDouble("ma_3y"), 1e-12)
        assertEquals(macro, r.getDouble("macro_ppp"), 1e-12)
        assertEquals(retail, r.getDouble("retail_ppp"), 1e-12)
        assertEquals((7.2 / ma1 - 1) * 100, r.getDouble("diff_ma_1y"), 1e-9)
        assertEquals((7.2 / ma3 - 1) * 100, r.getDouble("diff_ma_3y"), 1e-9)
        assertEquals((7.2 / macro - 1) * 100, r.getDouble("diff_macro_ppp"), 1e-9)
        assertEquals((7.2 / retail - 1) * 100, r.getDouble("diff_retail_ppp"), 1e-9)
        assertEquals(0, r.getJSONObject("null_reason").length())
        // a currency whose Big Mac series stopped, with no rate: every empty cell says why
        val ru = FxMatrix.row(j("""{"ccy":"RUB","cc":"RU","a3":"RUS","no_fx":"no rate"}"""), emptyList(), emptyList(), emptyList(), emptyList(), listOf("2022-01-01" to 135.0), bmUs, 30, listOf(1, 3))
        val w = ru.getJSONObject("null_reason")
        assertEquals("no rate", w.getString("today"))
        assertTrue(w.getString("retail_ppp").contains("last 2022-01-01"))
        assertTrue(w.has("macro_ppp") && ru.isNull("diff_retail_ppp"))
        // the market quote is 1 / today
        assertEquals(1 / 7.2, FxMatrix.row(j("""{"ccy":"EUR","market_quote":true}"""), fx, emptyList(), cpi, us, bm, bmUs, 30, listOf(1)).getDouble("market_quote"), 1e-12)
        val eur = j("""{"ccy":"EUR","today":0.9,"ma_1y":0.95,"null_reason":{}}"""); val jpy = j("""{"ccy":"JPY","today":150.0,"null_reason":{"ma_1y":"short"}}""")
        val d = FxMatrix.dxyRow(j("""{"constant":50.14348112,"legs":[{"ccy":"EUR","weight":0.6},{"ccy":"JPY","weight":0.4}]}"""), mapOf("EUR" to eur, "JPY" to jpy), listOf(1))
        assertEquals(50.14348112 * Math.pow(0.9, 0.6) * Math.pow(150.0, 0.4), d.getDouble("today"), 1e-9)
        assertEquals("needs the JPY leg (short)", d.getJSONObject("null_reason").getString("ma_1y"))
        assertEquals(2, FxMatrix.bigMac("date,iso_a3,currency_code,name,local_price\n2026-07-01,KOR,KRW,\"Korea, South\",5700\n2026-07-01,USA,USD,United States,6.22\n").size)
        assertEquals(listOf("2026-10-28" to 80.5307), FxMatrix.cbr("""<ValCurs><Record Date="28.10.2026" Id="R01235"><Nominal>1</Nominal><Value>80,5307</Value></Record></ValCurs>"""))
        val t = Tables.fxMatrixTable(Dashboards.byId(dashboards, "fx")!!.panels.first { it.id == "ppp_matrix" }, org.json.JSONObject(),
            org.json.JSONArray().put(r).put(ru), "test", emptyList())
        assertEquals(listOf("CNY", "BRICS", "7.2000"), t.rows[0].take(3))
        assertEquals(FinFormat.signed((7.2 / retail - 1) * 100, 1), t.rows[0][10])
        assertTrue(t.note.contains("RUB today: no rate"))
    }

    // ------------------------------------------------------------------ G20 Sovereign 10Y Bonds (#1397): the same fixture as g20bonds.rs and g20bonds.test.ts

    @Test fun g20BondsMovingAverageFxAnnualisationNetAndReal() {
        val j = { s: String -> org.json.JSONObject(s) }
        val end = LocalDate.parse("2026-10-30")
        val y = listOf("2023-09" to 3.5, "2023-11" to 9.9, "2025-10" to 3.9, "2026-09" to 4.1, "2026-10-10" to 4.0, "2026-10-20" to 4.2, "2026-10-30" to 4.4)
        val fx = listOf("2023-10-31" to 4.0, "2025-10-30" to 5.0, "2026-10-30" to 6.0)
        val cpi = G20Bonds.cpiYoY(listOf("2025-09-01" to 100.0, "2026-09-01" to 103.0))!!
        assertEquals(3.0, cpi.first, 1e-12); assertEquals("2026-09", cpi.second)
        val d0 = G20Bonds.ma(y, end, 30, 3)!!; assertEquals(4.2, d0.first, 1e-12); assertEquals("daily", d0.second)
        assertEquals(4.1 to "monthly 2026-09", G20Bonds.ma(y, end, 30, 4))
        assertEquals(3.5 to "monthly 2023-09", G20Bonds.ma(y, end.minusDays(1095), 30, 3))
        assertEquals(null, G20Bonds.ma(y, LocalDate.parse("2020-01-01"), 30, 3))
        assertEquals(20.0, G20Bonds.dep(6.0, 5.0, 1), 1e-12)
        assertEquals((Math.cbrt(1.5) - 1) * 100, G20Bonds.dep(6.0, 4.0, 3), 1e-12)
        val r = G20Bonds.row(j("""{"cc":"XX","ccy":"XXX","flag":"F","name":"Testland","bench":"TB"}"""), y, fx, cpi, end, 30, 3)
        val f3 = (Math.cbrt(1.5) - 1) * 100
        assertEquals(4.2, r.getDouble("y0"), 1e-12); assertEquals(3.9, r.getDouble("y1"), 1e-12); assertEquals(3.5, r.getDouble("y3"), 1e-12)
        assertEquals(20.0, r.getDouble("fx1"), 1e-12); assertEquals(f3, r.getDouble("fx3"), 1e-12)
        assertEquals(4.2 - 20.0, r.getDouble("net1"), 1e-12); assertEquals(4.2 - f3, r.getDouble("net3"), 1e-12)
        assertEquals(4.2 - 20.0 - 3.0, r.getDouble("real1"), 1e-12); assertEquals(4.2 - f3 - 3.0, r.getDouble("real3"), 1e-12)
        assertEquals("monthly 2025-10", r.getJSONObject("basis").getString("y1"))
        assertEquals(0, r.getJSONObject("null_reason").length())
        val us = G20Bonds.row(j("""{"cc":"US","ccy":"USD"}"""), y, emptyList(), cpi, end, 30, 3)
        assertEquals(0.0, us.getDouble("fx3"), 0.0); assertEquals(4.2 - 3.0, us.getDouble("real1"), 1e-12)
        val ru = G20Bonds.row(j("""{"cc":"RU","ccy":"RUB","no_fx":"no rate"}"""), emptyList(), emptyList(), null, end, 30, 3)
        val w = ru.getJSONObject("null_reason")
        assertEquals("no rate", w.getString("fx1")); assertTrue(w.getString("y0").startsWith("no 10Y yield")); assertTrue(w.has("cpi") && w.has("real3") && ru.isNull("net1"))
        val t = Tables.g20Table(Dashboards.byId(dashboards, "world_bonds")!!.panels.first { it.id == "g20_real" }, org.json.JSONObject(), org.json.JSONArray().put(r).put(ru), "test", emptyList())
        assertEquals(listOf("F Testland", "TB", FinFormat.signed(4.2 - 23.0, 2), "4.20"), t.rows[0].take(4))
        assertEquals(t.rows[0][2], t.rows[0][11])
        assertTrue(t.note.contains("RU fx1: no rate") && t.note.contains("XX y1: monthly 2025-10"))
    }
}
