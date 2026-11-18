package com.diegonmarcos.capitalfinterm

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1786 the Homepage boxes (data/home-boxes.json): EQ World Indices Equity lists the major economies' indices, every HG page lists its instruments with
 * Last and the 12-month change, the CB overview lists the major economies' yield curves, inflation, real GDP growth and policy rate; each the top 5 first
 * and Show all for every one; the instruments come from where the page already declares them.
 */
class HomeBoxesTest {
    private val decl = HomeBoxes.parse(File(listOf("../data/home-boxes.json", "data/home-boxes.json").first { File(it).isFile }).readText())!!
    private val quotes = QuotesDecl.parse(DataFiles.quotesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private fun pagesOf(section: String): List<String> = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .first { it.getString("id") == section }.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    @Test fun everyHgPageAndWeiHaveABox() {
        assertEquals(5, decl.top)
        val hg = pagesOf("hg").filter { !it.endsWith("-home") }
        for (p in hg) assertNotNull("HG page $p has no box", decl.quoteBoxes[p])
        for (p in hg) assertEquals("HG box $p shows Last and 12M", listOf(HomeBoxes.COL_LAST, Cagr.P12M), decl.quoteBoxes.getValue(p).columns)
        assertEquals(listOf("1M", Cagr.P12M), decl.quoteBoxes.getValue("world-equity-indices").columns)
        // every box belongs to a declared page
        val all = ui.getJSONArray("sections").let { a -> (0 until a.length()).flatMap { i -> a.getJSONObject(i).optJSONArray("pages")?.let { p -> (0 until p.length()).map { p.getJSONObject(it).getString("id") } }.orEmpty() } }
        for (k in decl.quoteBoxes.keys) assertTrue("box $k names no page", k in all)
    }

    @Test fun everyBoxResolvesItsInstrumentsFromWhereThePageDeclaresThem() {
        for ((k, b) in decl.quoteBoxes) {
            val items = HomeBoxes.items(b, quotes, dashboards)
            assertTrue("box $k resolves fewer than its top: ${items.size}", items.isNotEmpty())
            assertEquals("box $k lists a symbol twice", items.size, items.map { it.symbol }.toSet().size)
        }
        // CPX lists every commodity of the Commodity Prices page, FXR every currency with a USD leg, the rates futures box every front of the class
        assertEquals(quotes.commodities.size, HomeBoxes.items(decl.quoteBoxes.getValue("commodity-prices"), quotes, dashboards).size)
        assertEquals(quotes.ccys.count { it.symbol != null }, HomeBoxes.items(decl.quoteBoxes.getValue("fx-rates"), quotes, dashboards).size)
        assertEquals(quotes.classes.first { it.id == "rates" }.roots.count { !it.front.isNullOrBlank() }, HomeBoxes.items(decl.quoteBoxes.getValue("hg-rates"), quotes, dashboards).size)
        // CMDT lists every instrument of the Commodities dashboard's performance panels (all of its data moved with it)
        val cmdt = HomeBoxes.items(decl.quoteBoxes.getValue("commodities"), quotes, dashboards).map { it.symbol }
        for (s in listOf("CL=F", "GC=F", "HG=F", "ZC=F", "BTC-USD")) assertTrue("CMDT box lacks $s", s in cmdt)
    }

    @Test fun weiShowsTheMajorEconomiesFirstThenEveryIndex() {
        val items = HomeBoxes.items(decl.quoteBoxes.getValue("world-equity-indices"), quotes, dashboards)
        assertEquals(listOf("^GSPC", "000300.SS", "^STOXX50E", "^N225", "^FTSE"), HomeBoxes.shown(items, decl.top, all = false).map { it.symbol })
        assertEquals(32, HomeBoxes.shown(items, decl.top, all = true).size)
        assertEquals("S&P 500", items.first().label)
    }

    @Test fun cellsShowNaForMissingDataNeverAFakeValue() {
        val q = Quote(1234.5, 1, "", "", "", 1.0, 0.0, 1.0, 1.0, 2.0, 3.0, null, y1 = 12.34)
        assertEquals("1234.50", HomeBoxes.cell(q, HomeBoxes.COL_LAST))
        assertEquals("+12.3", HomeBoxes.cell(q, Cagr.P12M))
        assertEquals("+2.0", HomeBoxes.cell(q, "1M"))
        assertEquals("n/a", HomeBoxes.cell(q, Cagr.PCAGR_36M))
        assertEquals("n/a", HomeBoxes.cell(null, HomeBoxes.COL_LAST))
        assertEquals("n/a", HomeBoxes.cell(null, Cagr.P12M))
        assertNull(HomeBoxes.change(q, HomeBoxes.COL_LAST))
        assertEquals("0.1235", HomeBoxes.price(0.12345)); assertEquals("41250", HomeBoxes.price(41250.4))
        assertEquals("12M %", HomeBoxes.header(Cagr.P12M, "Last")); assertEquals("Last", HomeBoxes.header(HomeBoxes.COL_LAST, "Last"))
    }

    @Test fun theCbOverviewCoversTheMajorEconomiesAndTheFourIndicatorGroups() {
        assertEquals(listOf("cb-yields", "cb-inflation", "cb-gdp", "cb-policy"), decl.cbBoxes.map { it.id })
        assertEquals(listOf("yield_2y", "yield_5y", "yield_10y", "yield_30y"), decl.cbBoxes.first().columns.map { it.indicator })
        val cb = CbDecl.parse(DataFiles.cbCountriesJson, DataFiles.cbIndicatorsJson)
        val known = cb.full.flatMap { g -> g.items.map { it.id } }.toSet()
        for (b in decl.cbBoxes) for (c in b.columns) assertTrue("box ${b.id} reads the undeclared indicator ${c.indicator}", c.indicator in known)
        val order = HomeBoxes.cbCountries(decl.cbTop, cb.countries)
        assertEquals(listOf("US", "EA", "GB", "JP", "CN"), order.take(decl.top).map { it.id })
        assertEquals("Show all lists every country", cb.countries.size, order.size)
        for (p in decl.cbBoxes.map { it.opens }) assertTrue("CB box opens $p, not a CB page", p in pagesOf("cb"))
    }

    @Test fun cbCellsReadTheLatestPrintAndSayNaWhenThereIsNone() {
        val snap = CbSnapshot.parse(DataFiles.cbSnapshotJson)
        val us2 = HomeBoxes.cbCell(snap.of("US", "yield_2y"), "latest", snap.currentYear)
        assertNotNull(us2.value)
        assertTrue(us2.asOf.isNotEmpty())
        val s = CbSeries("x", "r", "v", "%", "", mapOf(2024 to 1.0, 2025 to 2.0), mapOf(2026 to 3.0), null, emptyList())
        assertEquals(CbCell(2.0, "2025"), HomeBoxes.cbCell(s, "latest", 2026))
        assertEquals(CbCell(3.0, "2026f"), HomeBoxes.cbCell(s, HomeBoxes.MODE_FC0, 2026))
        assertEquals(CbCell(4.5, "2026-10"), HomeBoxes.cbCell(s.copy(ytd = CbYtd(2026, 4.5, "2026-10")), "latest", 2026))
        assertEquals(CbCell(null, ""), HomeBoxes.cbCell(null, "latest", 2026))
        assertEquals("n/a", HomeBoxes.cbText(CbCell(null, "")))
        assertEquals("2.00", HomeBoxes.cbText(CbCell(2.0, "2025")))
        // every major economy has a policy rate and a CPI print in the snapshot
        for (c in listOf("US", "EA", "GB", "JP", "CN")) {
            assertNotNull("$c has no policy rate", HomeBoxes.cbCell(snap.of(c, "policy_rate"), "latest", snap.currentYear).value)
            assertNotNull("$c has no CPI", HomeBoxes.cbCell(snap.of(c, "cpi_headline"), "latest", snap.currentYear).value)
            assertNotNull("$c has no real GDP", HomeBoxes.cbCell(snap.of(c, "real_gdp"), "latest", snap.currentYear).value)
        }
    }

    @Test fun showAllTogglesBetweenTheTopAndEveryRow() {
        val items = (1..12).map { BoxItem("S$it", "L$it") }
        assertEquals(5, HomeBoxes.shown(items, 5, all = false).size)
        assertEquals(12, HomeBoxes.shown(items, 5, all = true).size)
        assertEquals(3, HomeBoxes.shown(items.take(3), 5, all = false).size)
        assertFalse(HomeBoxes.parse("not json") != null)
    }

    @Test fun theEcbYieldCurveSourceParsesItsDailyCsv() {
        val m = CbSources.ecbDaily(DataFiles.cbFixture("ecb-yc.csv"))
        assertEquals(setOf(2026 to 10, 2026 to 11), m.keys)
        assertEquals(listOf(2.05, 2.07), m[2026 to 11])
        assertTrue(CbSources.ecbDaily("<html/>").isEmpty())
        val cb = CbDecl.parse(DataFiles.cbCountriesJson, DataFiles.cbIndicatorsJson)
        assertTrue("the ECB yield curve is a declared source", "ecb_yc" in cb.sources)
    }
}
