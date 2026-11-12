package com.diegonmarcos.capitalfinterm

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1663 EQ: Performance / Data sections, DM + EM merged, and the index holdings breakdown (table + treemap) of S&P 500, Nikkei 225, FTSE 100. */
class IndexHoldingsTest {
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private val stacks = ui.getJSONObject("stacks").toString()
    private val dataset: String = listOf("../../data/fleet/app/com.diegonmarcos.capitalfinterm/index-holdings.json", "../data/fleet/app/com.diegonmarcos.capitalfinterm/index-holdings.json")
        .map { File(it) }.first { it.isFile }.readText()
    private val wie = JSONObject(DataFiles.dashboardsJson).getJSONArray("dashboards").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("id") == "world_equity_indices" }

    private fun q(pct: Double?, ytd: Double? = null) = Quote(1.0, 0L, "", "", "", null, null, pct, null, null, ytd, null)

    @Test fun eqHasPerformanceThenDataInTheDeclaredOrder() {
        val entries = Pages.entries(stacks, "eq")
        val titles = entries.map { if (it.kind == Pages.KIND_SECTION_TITLE) "#" + it.title else it.title }
        val perf = titles.indexOf("#Performance"); val data = titles.indexOf("#Data")
        assertTrue(perf in 0 until data)
        val p = titles.subList(perf + 1, data)
        assertTrue(p.indexOf("World Indices Equity") < p.indexOf("Commodities") && p.indexOf("Commodities") < p.indexOf("Exchange Rates"))
        val d = titles.subList(data + 1, titles.size)
        assertEquals(listOf("Fundamentals", "Earnings Calendar", "Quotes", "Watchlist"), d.take(4))
        assertTrue("DM / EM Equity are merged", titles.none { it == "DM Equity (US)" || it == "EM Equity" })
    }

    @Test fun homeGroupsFollowTheSectionTitlesAndKeepEveryCard() {
        val entries = Pages.entries(stacks, "eq")
        val pages = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("id") == "eq" }
            .getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Triple(it.getString("id"), it.getString("label"), it.getString("icon")) } }
        val cards = Pages.homeCards(pages, entries)
        val groups = Pages.homeGroups(cards, entries)
        assertEquals(listOf("Performance", "Data"), groups.map { it.first }.filter { it.isNotEmpty() })
        assertEquals(cards.map { it.pageId }.sorted(), groups.flatMap { it.second }.map { it.pageId }.sorted())
        assertEquals("world-equity-indices", groups.first { it.first == "Performance" }.second.first().pageId)
    }

    @Test fun mergedPanelsLiveInWorldIndicesEquity() {
        val ids = wie.getJSONArray("panels").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertTrue(ids.containsAll(listOf("dm_sectors", "dm_strategies", "dm_screener", "em_sectors", "em_bonds")))
    }

    @Test fun theThreeIndicesOpenTheirBreakdown() {
        val links = IndexHoldings.links(wie)
        assertEquals(listOf("sp500", "nikkei225", "ftse100"), links.map { it.key })
        assertEquals(listOf("USA", "Japan", "UK"), links.map { it.country })
        assertEquals("sp500", IndexHoldings.linkFor(links, "_SPX")?.key)
        assertEquals("nikkei225", IndexHoldings.linkFor(links, "Nikkei 225")?.key)
        assertEquals("ftse100", IndexHoldings.linkFor(links, "^ftse")?.key)
        assertNull(IndexHoldings.linkFor(links, "_NDX"))
        assertNull(IndexHoldings.linkFor(links, null))
        val s = FinNavStack.index(listOf("dash:world_equity_indices"), "sp500")
        assertEquals("sp500", FinNavStack.indexOf(s.last()))
        assertEquals(listOf("dash:world_equity_indices"), FinNavStack.back(s, true))
    }

    @Test fun theRuntimeDatasetHasRealConstituentsWithSourceAndDate() {
        val b = IndexHoldings.parse(dataset)
        assertEquals(setOf("sp500", "nikkei225", "ftse100"), b.keys)
        val min = mapOf("sp500" to 495, "nikkei225" to 225, "ftse100" to 95)
        for ((k, v) in b) {
            assertTrue(k, v.holdings.size >= min.getValue(k))
            assertTrue(k, v.weightSum in 95.0..100.5)
            assertTrue(k, v.source.isNotBlank() && Regex("\\d{4}-\\d{2}-\\d{2}").matches(v.asOf))
            assertEquals(k, v.holdings.sortedByDescending { it.weight }, v.holdings)
        }
        // no price or performance is shipped with the weights
        assertTrue(!dataset.contains("\"pct\"") && !dataset.contains("\"price\""))
    }

    @Test fun tableColumnsAndContribution() {
        assertEquals(listOf("Holding", "Weight %", "Perf %", "Contrib pp"), IndexHoldings.COLUMNS)
        val basket = IndexHoldings.parse(dataset).getValue("sp500")
        val top = basket.holdings[0]
        val rows = IndexHoldings.rows(basket, mapOf(top.symbol to q(2.0, 10.0)), "1D", 10)
        assertEquals(10, rows.size)
        assertEquals(top.weight * 2.0 / 100.0, rows[0].contribution!!, 1e-9)
        assertNull(rows[1].perf); assertNull(rows[1].contribution)
        assertEquals(10.0, IndexHoldings.rows(basket, mapOf(top.symbol to q(2.0, 10.0)), "YTD", 1)[0].perf!!, 1e-9)
        val cells = IndexHoldings.table(rows, "Rest")
        assertEquals(11, cells.size); assertTrue(cells.all { it.size == 4 }); assertEquals("Rest", cells.last()[0])
        assertEquals(top.weight * 2.0 / 100.0, IndexHoldings.total(rows)!!.first, 1e-9)
    }

    @Test fun treemapAreasAreProportionalToWeightsAndInsideTheBox() {
        val w = listOf(7.8, 6.3, 5.0, 2.1, 1.0, 0.4, 0.0, -1.0, Double.NaN)
        val r = IndexHoldings.treemap(w, 300.0, 400.0)
        val total = w.filter { it.isFinite() && it > 0 }.sum()
        w.forEachIndexed { i, x ->
            if (x.isFinite() && x > 0) assertEquals(x / total * 120000.0, r[i].area, 1e-6) else assertEquals(0.0, r[i].area, 0.0)
            assertTrue(r[i].x >= -1e-9 && r[i].y >= -1e-9 && r[i].x + r[i].w <= 300.0 + 1e-6 && r[i].y + r[i].h <= 400.0 + 1e-6)
        }
        assertTrue(IndexHoldings.treemap(emptyList(), 100.0, 100.0).isEmpty())
        assertEquals(0.0, IndexHoldings.treemap(listOf(1.0), 0.0, 100.0)[0].area, 0.0)
    }

    @Test fun colourScaleFollowsPerformance() {
        assertEquals(0f, IndexHoldings.heat(null, 2.0)); assertEquals(0f, IndexHoldings.heat(1.0, 0.0))
        assertEquals(0.5f, IndexHoldings.heat(1.0, 2.0)); assertEquals(-1f, IndexHoldings.heat(-5.0, 2.0)); assertEquals(1f, IndexHoldings.heat(9.0, 2.0))
        assertEquals(0f, IndexHoldings.blend(0f)); assertEquals(1f, IndexHoldings.blend(1f))
        assertTrue(IndexHoldings.blend(0.1f) < IndexHoldings.blend(0.6f))
    }

    @Test fun missingDataNeverThrows() {
        assertTrue(IndexHoldings.parse("").isEmpty()); assertTrue(IndexHoldings.parse("{}").isEmpty()); assertTrue(IndexHoldings.parse("not json").isEmpty())
        assertTrue(IndexHoldings.parse("{\"indices\":{\"x\":{\"holdings\":[{\"symbol\":\"\",\"weight\":1},{\"symbol\":\"A\",\"weight\":-1}]}}}").isEmpty())
        assertTrue(IndexHoldings.links(JSONObject()).isEmpty())
        assertNull(IndexHoldings.total(emptyList()))
        assertEquals(100.0, IndexHoldings.restWeight(emptyList()), 0.0)
        assertNull(IndexHoldings.perf(null, "1D")); assertNull(IndexHoldings.perf(q(Double.NaN), "1D")); assertNull(IndexHoldings.perf(q(1.0), "5Y"))
    }
}
