package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1722 rule 16 parity of the reorganisation (the rates, macro and credit pages moved from FI to CB; FI became the bond groups; EQ became Performance and
 * Data): against the frozen baseline src/test/resources/nav-parity-baseline-1722.json, every page, stack entry, redirect and dashboard panel the app
 * declared before is still declared with the same fields; a page may only MOVE, and only as build.json ui.moved (the old -> new map) says.
 */
class MovedPagesTest {
    private val baseline = JSONObject(DataFiles.resource("nav-parity-baseline-1722.json"))
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private val stacks = ui.getJSONObject("stacks").toString()
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private val sections = arr(ui.getJSONArray("sections"))
    private val moved = arr(ui.getJSONObject("moved").getJSONArray("pages")).associateBy { it.getString("page") }

    /** Where a sub-page id lives today: its section, or null. */
    private fun sectionOf(page: String): String? = sections.firstOrNull { s -> arr(s.optJSONArray("pages")).any { it.getString("id") == page } }?.getString("id")
    private fun pageOf(page: String): JSONObject? = sections.flatMap { arr(it.optJSONArray("pages")) }.firstOrNull { it.getString("id") == page }

    /** The group (the stack's section title) a sub-page is declared under today. */
    private fun groupOf(section: String, page: String): String {
        var group = ""
        for (e in Pages.entries(stacks, section)) {
            if (e.kind == Pages.KIND_SECTION_TITLE) { group = e.title; continue }
            if (Pages.pageIdOf(e) == page) return group
        }
        return "?"
    }

    @Test fun theBottomNavIsUnchanged() {
        val was = baseline.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(was, ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun everyOldPageIsReachableAtItsDeclaredPlace() {
        val pages = arr(baseline.getJSONArray("pages"))
        assertEquals(44, pages.size)
        for (b in pages) {
            val id = b.getString("page")
            val was = b.getString("section")
            val now = sectionOf(id)
            assertNotNull("page $id (was under $was) is gone", now)
            val p = pageOf(id)!!
            assertEquals("page $id lost its label", b.getString("label"), p.getString("label"))
            assertEquals("page $id lost its icon", b.getString("icon"), p.getString("icon"))
            val m = moved[id]
            if (m == null) assertEquals("page $id moved from $was to $now without being declared in ui.moved", was, now)
            else {
                assertEquals("ui.moved: $id was not under ${m.getString("from")}", was, m.getString("from"))
                assertEquals("ui.moved: $id is not under ${m.getString("to")}", m.getString("to"), now)
                assertEquals("ui.moved: $id is not in the group ${m.getString("group")}", m.getString("group"), groupOf(now!!, id))
            }
            // the one-row nav of its section opens it: a Homepage card, and the page behind it
            if (!id.endsWith("-home")) {
                val entries = Pages.entries(stacks, now!!)
                val triples = arr(sections.first { it.getString("id") == now }.getJSONArray("pages")).map { Triple(it.getString("id"), it.getString("label"), it.getString("icon")) }
                assertTrue("page $id has no Homepage card under $now", Pages.homeCards(triples, entries).any { it.pageId == id })
                assertNotNull("page $id opens nothing under $now", Pages.entryOf(entries, id))
            }
        }
    }

    @Test fun everyMovedPageIsInTheMapAndTheMapNamesOnlyRealPages() {
        val was = arr(baseline.getJSONArray("pages")).associate { it.getString("page") to it.getString("section") }
        // the eight rates / macro / credit pages left FI for CB
        val toCb = moved.values.filter { it.getString("from") == "fi" && it.getString("to") == "cb" }.map { it.getString("page") }.toSet()
        assertEquals(setOf("yield-curves", "yields-matrix", "forward-rates", "rate-probabilities", "world-bonds", "bonds-market", "macro", "credit"), toCb)
        assertEquals("every old FI page moved to CB", was.filter { it.value == "fi" && !it.key.endsWith("-home") }.keys, toCb)
        for ((page, m) in moved) {
            assertEquals("ui.moved names $page, which was not a page", m.getString("from"), was[page])
            assertEquals(m.getString("to"), sectionOf(page))
        }
        // every old EQ page is in the map with its group: the index performance page under Performance, the rest under Data
        val eq = was.filter { it.value == "eq" && !it.key.endsWith("-home") }.keys
        assertEquals(eq, moved.values.filter { it.getString("from") == "eq" }.map { it.getString("page") }.toSet())
        assertEquals(listOf("world-equity-indices"), moved.values.filter { it.getString("from") == "eq" && it.getString("group") == "Performance" }.map { it.getString("page") })
    }

    @Test fun everyOldEntryKeepsEveryField() {
        val entries = arr(baseline.getJSONArray("entries"))
        assertEquals(56, entries.size)
        val today = sections.map { it.getString("id") }.flatMap { s -> arr(ui.getJSONObject("stacks").optJSONArray(s)).map { s to it } }
        for (b in entries) {
            val kind = b.getString("kind")
            // a group heading is structure, not a page: the old headings may be regrouped (Performance and Data still exist under EQ)
            if (kind == Pages.KIND_SECTION_TITLE) { assertTrue(today.any { it.second.optString("kind") == kind && it.second.optString("title") == b.getString("title") }); continue }
            val hit = today.firstOrNull { (_, e) -> e.optString("kind") == kind && e.optString("title") == b.optString("title") && e.optString("dashboard") == b.optString("dashboard") && e.optString("page") == b.optString("page") }
            assertNotNull("entry ${b.optString("title").ifEmpty { kind }} (was under ${b.getString("section")}) is gone", hit)
            val e = hit!!.second
            for (f in listOf("subtitle", "table", "view")) assertEquals("entry ${b.optString("title")} lost $f", b.optString(f), e.optString(f))
            assertEquals("entry ${b.optString("title")} changed detail", b.optBoolean("detail", false), e.optBoolean("detail", false))
        }
    }

    @Test fun everyOldDashboardAndPanelIsStillDeclared() {
        val olds = arr(baseline.getJSONArray("dashboards"))
        assertEquals(34, olds.size)
        var panels = 0
        for (b in olds) {
            val d = Dashboards.byId(dashboards, b.getString("id"))
            assertNotNull("dashboard ${b.getString("id")} is gone", d)
            assertEquals(b.getString("entry"), d!!.entry)
            // a dashboard's section is its page's section: the same as before unless ui.moved moved the page
            val page = Pages.slugOf(d.id)
            val expected = moved[page]?.getString("to") ?: b.getString("was_tab")
            assertEquals("dashboard ${d.id} sits under ${d.tab}", expected, d.tab)
            for (bp in arr(b.getJSONArray("panels"))) {
                val p = d.panels.firstOrNull { it.id == bp.getString("id") }
                assertNotNull("panel ${d.id}/${bp.getString("id")} is gone", p)
                assertEquals(bp.getString("title"), p!!.title)
                assertEquals(bp.getString("source"), p.source)
                assertEquals(bp.getString("view"), p.view)
                panels++
            }
        }
        assertEquals(101, panels)
    }

    @Test fun everyOldRedirectStillOpensALivePage() {
        val now = ui.getJSONArray("redirects").toString()
        for (r in arr(baseline.getJSONArray("redirects"))) {
            val got = Pages.redirect(now, r.getString("from"))
            assertEquals("redirect ${r.getString("from")}", r.getString("tab") to r.optString("to", Pages.PAGE_HOME), got)
            val to = got!!.second
            if (to != Pages.PAGE_HOME) assertEquals("redirect ${r.getString("from")} points at a page that left ${got.first}", got.first, sectionOf(to))
        }
    }

    @Test fun fiHoldsOnlyTheBondGroupsAndCbHoldsTheRatesAndMacroPages() {
        val fi = Pages.entries(stacks, "fi")
        assertEquals(listOf("All", "Gov Bonds DM", "Gov Bonds EM", "AAA Bonds", "AA-A Bonds", "BBB Bonds", "Junk"), fi.map { it.title })
        assertTrue(fi.all { it.kind == Pages.KIND_PERF_PORTFOLIO })
        assertFalse(dashboards.any { it.tab == "fi" })
        val cb = Pages.entries(stacks, "cb").filter { it.kind != Pages.KIND_SECTION_TITLE }.map { it.title }
        assertEquals(listOf("Countries", "DM / EM / World", "Predictions", "Yield Curves", "Yields Matrix", "Forward Rates", "World Interest Rate Probabilities", "World Bonds", "Bonds Market", "Macro", "Credit"), cb)
        // a moved dashboard's deep link opens it under CB: the shell sends a dashboard to its own section
        for (id in listOf("yield_curves", "yields_matrix", "forward_rates", "rate_probabilities", "world_bonds", "bonds_market", "macro", "credit")) {
            assertEquals(id, "cb", Dashboards.byId(dashboards, id)!!.tab)
            assertEquals(Pages.slugOf(id), FinNavStack.selectedPage(FinNavStack.open(FinNavStack.initial(), id), "cb"))
        }
    }
}
