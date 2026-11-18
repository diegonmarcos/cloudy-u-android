package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1786 rule 16 field parity of the reorganisation (the period set, the Homepage boxes, the CB overview, EQ > Data's Commodities / Commodity Prices /
 * Exchange Rates moved to HG > Markets, PM opening on Performance) against the frozen baseline src/test/resources/nav-parity-baseline-1786.json (the
 * declarations before #1786): every page keeps its id, label and icon and stays in its section unless ui.moved moves it (ticket 1786); every stack entry
 * keeps every field; every redirect still resolves; every dashboard keeps every panel with its title, source, view, columns and instruments; the
 * period list only grows; the former PM Homepage stays reachable; the US Cyclicals group keeps its eight profiles.
 */
class Reorg1786ParityTest {
    private val base = JSONObject(DataFiles.resource("nav-parity-baseline-1786.json"))
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private val sections = arr(ui.getJSONArray("sections"))
    private val moved = arr(ui.getJSONObject("moved").getJSONArray("pages")).filter { it.optString("ticket") == "1786" }.associateBy { it.getString("page") }
    private fun sectionOf(page: String): String? = sections.firstOrNull { s -> arr(s.optJSONArray("pages")).any { it.getString("id") == page } }?.getString("id")
    private fun pageOf(page: String): JSONObject? = sections.flatMap { arr(it.optJSONArray("pages")) }.firstOrNull { it.getString("id") == page }
    /** Where a baseline item of [section] lives now: the section ui.moved sends its page to, else the same. */
    private fun now(section: String, page: String): String = moved[page]?.getString("to") ?: section

    @Test fun theBottomNavIsUnchanged() {
        assertEquals(strs(base.getJSONArray("bottom_nav")), strs(ui.getJSONArray("bottom_nav")))
    }

    @Test fun everyPageKeepsItsLabelAndIconAndOnlyMovesAsDeclared() {
        val pages = arr(base.getJSONArray("pages"))
        assertEquals(61, pages.size)
        for (b in pages) {
            val id = b.getString("page")
            val p = pageOf(id)
            assertNotNull("page $id is gone", p)
            assertEquals("page $id lost its label", b.getString("label"), p!!.getString("label"))
            assertEquals("page $id lost its icon", b.getString("icon"), p.getString("icon"))
            assertEquals("page $id moved without ui.moved (ticket 1786)", now(b.getString("section"), id), sectionOf(id))
        }
        assertEquals(setOf("commodities", "commodity-prices", "fx-rates"), moved.keys)
        for (m in moved.values) { assertEquals("eq", m.getString("from")); assertEquals("hg", m.getString("to")); assertEquals("Markets", m.getString("group")) }
    }

    @Test fun everyStackEntryKeepsEveryField() {
        val olds = arr(base.getJSONArray("entries"))
        assertEquals(76, olds.size)
        val today = ui.getJSONObject("stacks").let { s -> s.keys().asSequence().toList().flatMap { k -> arr(s.optJSONArray(k)).map { k to it } } }
        for (b in olds) {
            val kind = b.getString("kind")
            val hit = today.firstOrNull { (_, e) -> e.optString("kind") == kind && e.optString("title") == b.optString("title") && e.optString("page") == b.optString("page") && e.optString("dashboard") == b.optString("dashboard") }
            assertNotNull("entry ${b.optString("title").ifEmpty { kind }} (${b.getString("section")}) is gone", hit)
            val (sec, e) = hit!!
            val page = b.optString("page").ifEmpty { if (kind == Pages.KIND_DASHBOARD && !b.optBoolean("detail")) Pages.slugOf(b.optString("dashboard")) else "" }
            if (kind != Pages.KIND_SECTION_TITLE) assertEquals("entry ${b.optString("title")} is under $sec", now(b.getString("section"), page), sec)
            for (k in b.keys()) {
                if (k == "section") continue
                assertEquals("entry ${b.optString("title")} lost or changed $k", b.get(k).toString(), e.opt(k)?.toString())
            }
        }
    }

    @Test fun everyRedirectStillOpensALivePage() {
        val now = ui.getJSONArray("redirects").toString()
        for (r in arr(base.getJSONArray("redirects"))) {
            val got = Pages.redirect(now, r.getString("from"))
            assertNotNull("redirect ${r.getString("from")} is gone", got)
            val to = r.optString("to", Pages.PAGE_HOME)
            assertEquals("redirect ${r.getString("from")} opens another page", to, got!!.second)
            assertEquals("redirect ${r.getString("from")} does not follow its page", now(r.getString("tab"), to), got.first)
            if (to != Pages.PAGE_HOME) assertEquals(got.first, sectionOf(to))
        }
    }

    @Test fun everyDashboardKeepsEveryPanelColumnAndInstrument() {
        val dash = Dashboards.parse(DataFiles.dashboardsJson)
        var panels = 0
        for (b in arr(base.getJSONArray("dashboards"))) {
            val d = Dashboards.byId(dash, b.getString("id"))
            assertNotNull("dashboard ${b.getString("id")} is gone", d)
            assertEquals(b.getString("entry"), d!!.entry)
            assertEquals("dashboard ${d.id} sits under ${d.tab}", now(b.getString("tab"), Pages.slugOf(d.id)), d.tab)
            for (bp in arr(b.getJSONArray("panels"))) {
                val p = d.panels.firstOrNull { it.id == bp.getString("id") }
                assertNotNull("panel ${d.id}/${bp.getString("id")} is gone", p)
                assertEquals(bp.getString("title"), p!!.title)
                assertEquals(bp.getString("source"), p.source)
                assertEquals(bp.getString("view"), p.view)
                assertEquals("panel ${d.id}/${p.id} lost a column", strs(bp.getJSONArray("columns")), p.columns)
                assertEquals("panel ${d.id}/${p.id} lost an instrument", strs(bp.getJSONArray("each")), strs(p.fetch?.optJSONArray("each")))
                panels++
            }
        }
        assertEquals(101, panels)
    }

    @Test fun thePeriodListOnlyGrows() {
        val was = strs(base.getJSONArray("perf_periods"))
        val decl = PerfPortfolios.parseDecl(DataFiles.portfoliosJson)!!
        assertEquals(listOf("1D", "1W", "1M", "YTD"), was)
        assertEquals("the old periods stay first, in order", was, decl.periods.take(was.size))
        assertEquals(Cagr.LONG_PERIODS, decl.periods.drop(was.size))
    }

    @Test fun pmOpensOnPerformanceAndTheFormerHomepageStaysReachable() {
        val entries = Pages.entries(ui.getJSONObject("stacks").toString(), "pm")
        assertEquals("pm-performance", Pages.homeOverride(entries)?.page)
        val grid = Pages.entryOf(entries, "pm-index")
        assertEquals(Pages.KIND_HOME_GRID, grid?.kind)
        // the former Homepage's cards are all still built (it shows them as PM > Index)
        val triples = arr(sections.first { it.getString("id") == "pm" }.getJSONArray("pages")).map { Triple(it.getString("id"), it.getString("label"), it.getString("icon")) }
        assertEquals(listOf("pm-portfolios", "pm-performance"), Pages.homeCards(triples, entries).map { it.pageId })
        // a section without a home_page keeps its card grid
        assertEquals(null, Pages.homeOverride(Pages.entries(ui.getJSONObject("stacks").toString(), "eq")))
    }

    @Test fun usCyclicalsKeepsItsProfilesAndAddsTheTwoIndices() {
        val (_, groups) = PmTables.profiles(DataFiles.pmJson, "profiles")!!
        assertEquals(listOf("Growth", "Value", "Quality", "Cyclical", "Defensive", "Dividend", "Momentum", "Small-cap"), groups.map { it.label }.take(8))
        assertEquals(listOf("S&P 500", "Nasdaq"), groups.map { it.label }.drop(8))
        val ports = PmPortfolios.parse(DataFiles.pmJson) { it }.portfolios.filter { it.table == "us_cyclicals" }
        assertTrue(ports.any { p -> p.legs.map { it.symbol } == listOf("^GSPC") })
        assertTrue(ports.any { p -> p.legs.map { it.symbol } == listOf("^IXIC") })
    }
}
