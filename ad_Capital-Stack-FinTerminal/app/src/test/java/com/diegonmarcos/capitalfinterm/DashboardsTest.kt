package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboards (data/dashboards.json) against build.json's entries and the source catalog. */
class DashboardsTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)

    @Test fun everyDashboardEntryOfBuildJsonHasItsDashboard() {
        val stacks = JSONObject(DataFiles.buildJson).getJSONObject("ui").getJSONObject("stacks")
        var count = 0
        for (tab in stacks.keys()) {
            val arr = stacks.getJSONArray(tab)
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                if (e.optString("kind") != Pages.KIND_DASHBOARD) continue
                count++
                val d = Dashboards.byId(dashboards, e.getString("dashboard"))
                assertNotNull("${e.getString("title")} names no dashboard", d)
                assertEquals(e.getString("title"), d!!.entry)
                assertEquals(tab, d.tab)
            }
        }
        assertEquals(36, count) // #1399 FI Market Monitor removed; #1447 PM A1, B1, C1, D1, D0 (#1478 A2 dropped)
        assertEquals(count, dashboards.size)
    }

    @Test fun everyPanelIsWiredToASourceThatFeedsItsEntry() {
        for (d in dashboards) {
            assertTrue("${d.id} has panels", d.panels.isNotEmpty())
            for (p in d.panels) {
                val s = Catalog.byId(sources, p.source)
                assertNotNull("${d.id}/${p.id} source ${p.source} is not declared", s)
                assertTrue("${d.id}/${p.id}: ${p.source} does not feed ${d.tab}/${d.entry}", s!!.feeds(d.tab, d.entry))
                assertTrue("${d.id}/${p.id} view ${p.view}", p.view in Dashboards.VIEWS)
                assertEquals("${d.id}/${p.id} is live, a placeholder or an allocation", 1, listOf(p.isLive, p.isPlaceholder, p.isStatic).count { it })
                if (p.isLive) {
                    assertTrue("${d.id}/${p.id} fetches live from a key-free source", s.authType == Catalog.AUTH_NONE && s.hasConnector)
                    assertEquals("${d.id}/${p.id} provider matches the source connector", s.connector, p.fetch!!.getString("provider"))
                }
            }
        }
    }

    @Test fun theKeyFreeDashboardsAreLiveAndTheKeyedOnesAreDashed() {
        fun status(id: String) = Dashboards.status(Dashboards.byId(dashboards, id)!!, sources)
        for (id in listOf("yield_curves", "forward_rates", "rate_probabilities", "world_bonds", "world_equity_indices", "fx", "onshore_usd_rates", "news"))
            assertTrue("$id has a live panel", Dashboards.byId(dashboards, id)!!.panels.any { it.isLive })
        assertEquals(DashStatus.LIVE, status("forward_rates"))
        assertEquals(DashStatus.PARTLY_LIVE, status("yield_curves"))
        assertEquals(DashStatus.NEEDS_KEY, status("portfolio_risk"))
        assertEquals(DashStatus.LIVE, status("mp_40_60"))
    }

    @Test fun modelPortfolioLegsSumToOneHundred() {
        val mp = dashboards.filter { it.legs.isNotEmpty() }
        assertEquals(12, mp.size) // the 8 reference portfolios + #1447 A1, B1, C1, D1
        mp.forEach { assertEquals(it.id, 100, it.legs.sumOf { l -> l.second }) }
    }
}
