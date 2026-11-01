package com.diegonmarcos.capitalfinterm

import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.NavUiMode
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1399 the declared nav: one scrollable row per section, a Homepage first listing every sub-page, Market Monitor gone and redirected. */
class SectionHomeTest {
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private val stacks = ui.getJSONObject("stacks").toString()
    private val bottom = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun section(id: String): JSONObject = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == id } }
    private fun pages(id: String): List<Triple<String, String, String>> = section(id).getJSONArray("pages").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Triple(it.getString("id"), it.getString("label"), it.getString("icon")) }
    }

    @Test fun everySectionOpensOnItsHomepage() {
        for (s in bottom) {
            assertEquals(s, Pages.homeId(s), pages(s).first().first)
            assertEquals(s, Pages.homeId(s), FinNavStack.selectedPage(FinNavStack.initial(), s))
        }
        // page ids are unique in the app (nav-shape N12)
        val ids = bottom.flatMap { s -> pages(s).map { it.first } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun theHomepageListsEverySubPageWithIconNameAndDescription() {
        for (s in bottom) {
            val entries = Pages.entries(stacks, s)
            val subPages = entries.map { Pages.pageIdOf(it) }.filter { it.isNotEmpty() }
            val cards = Pages.homeCards(pages(s), entries)
            assertEquals(s, subPages, cards.map { it.pageId })
            assertEquals(s, pages(s).drop(1).map { it.first }, cards.map { it.pageId })
            for (c in cards) assertTrue("$s/${c.pageId}", c.icon.isNotBlank() && c.title.isNotBlank() && c.subtitle.isNotBlank())
        }
    }

    @Test fun marketMonitorIsGoneAndItsDeepLinkOpensTheFiHomepage() {
        assertNull(Dashboards.byId(Dashboards.parse(DataFiles.dashboardsJson), "market_monitor"))
        assertFalse(stacks.contains("market_monitor"))
        assertFalse(pages("fi").any { it.first == "market-monitor" })
        assertEquals("fi" to Pages.PAGE_HOME, Pages.redirect(ui.getJSONArray("redirects").toString(), "market_monitor"))
        assertNull(Pages.redirect(ui.getJSONArray("redirects").toString(), "yield_curves"))
        // its one unique panel (the next 48 hours of high-importance events) lives on FI > Macro
        assertTrue(Dashboards.byId(Dashboards.parse(DataFiles.dashboardsJson), "macro")!!.panels.any { it.id == "next" })
    }

    @Test fun theNavIsOneScrollableRow() {
        for (s in bottom) {
            assertEquals(s, SecondRowMode.OneRow, SecondRowMode.of(section(s).optString("nav_mode")))
            assertTrue(s, section(s).optJSONArray("groups") == null)
        }
        val mode = NavUiMode.of(ui.getJSONObject("nav_ui").optString("mode"))!!
        assertTrue("the second row is drawn", NavUiDecl.plan(mode).secondRow)
        assertTrue(JSONArray(ui.getJSONArray("redirects").toString()).length() > 0)
    }
}
