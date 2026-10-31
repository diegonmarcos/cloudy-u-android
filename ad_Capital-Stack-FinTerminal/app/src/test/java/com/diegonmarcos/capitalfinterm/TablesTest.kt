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
}
