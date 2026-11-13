package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI spec 3 / 6.3 declarations: each tab's default dashboard, panels drawn in order with key rows last, source segments in one card. */
class DashboardsUiTest {
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private fun d(id: String) = Dashboards.byId(dashboards, id)!!

    @Test fun eachTabOpensOnItsDeclaredDefault() {
        assertEquals("fx", Dashboards.defaultOf(dashboards, listOf("crypto", "fx")))
        assertEquals("yield_curves", Dashboards.defaultOf(dashboards, listOf("yield_curves", "forward_rates")))
        // #1399 FI opens on its Homepage: no FI dashboard is declared default; #1722 the rates dashboards moved to CB, which opens on its Homepage too
        assertTrue(dashboards.none { (it.tab == "fi" || it.tab == "cb") && it.isDefault })
        for ((tab, id) in mapOf("eq" to "world_equity_indices", "hg" to "fx", "nw" to "economic_calendar", "pm" to "portfolio_risk")) {
            assertEquals(tab, listOf(id), dashboards.filter { it.tab == tab && it.isDefault }.map { it.id })
        }
    }

    @Test fun keyRowsComeLastAndASegmentIsDrawnInsideItsCard() {
        val yc = d("yield_curves")
        assertEquals(listOf("ust", "euro", "sovereigns"), Dashboards.drawOrder(yc).map { it.id })
        assertEquals(listOf("ust", "ust_official"), Dashboards.segments(yc, yc.panels.first { it.id == "ust" }).map { it.id })
        val fx = d("fx")
        val order = Dashboards.drawOrder(fx)
        assertTrue(order.takeLastWhile { it.isPlaceholder }.isNotEmpty())
        assertTrue(order.dropLastWhile { it.isPlaceholder }.none { it.isPlaceholder })
    }

    @Test fun aKeyRowDeclaredFirstIsStillDrawnLast() {
        val d = Dashboards.parse("""{"dashboards":[{"id":"x","entry":"X","tab":"fi","panels":[
            {"id":"k","title":"K","source":"s","view":"table","placeholder":{"reason":"needs a key"}},
            {"id":"a","title":"A","source":"s","view":"table","fetch":{"provider":"p"}},
            {"id":"b","title":"B","source":"s","view":"table","fetch":{"provider":"p"},"segment_of":"a"}]}]}""").single()
        assertEquals(listOf("a", "k"), Dashboards.drawOrder(d).map { it.id })
        assertEquals(listOf("a", "b"), Dashboards.segments(d, d.panels[1]).map { it.id })
    }

    @Test fun theMarketMonitorEventsPanelMovedToMacro() {
        assertEquals(null, Dashboards.byId(dashboards, "market_monitor"))
        assertEquals("next", d("macro").panels.first().id)
    }

    @Test fun fixedIncomeHoldsNoEquityPanel() {
        // The rates and macro dashboards (FI until #1722, CB since) are fixed income only: none of their panels reads an equity source, and the Market
        // Monitor equity tiles live in EQ (World Equity Indices)
        val equitySources = setOf("cboe")
        val rates = dashboards.filter { it.tab == "cb" && it.id != "bonds_market" }
        assertEquals(listOf("credit", "forward_rates", "macro", "rate_probabilities", "world_bonds", "yield_curves", "yields_matrix"), rates.map { it.id }.sorted())
        assertTrue("no dashboard is left under FI: FI is the bond groups", dashboards.none { it.tab == "fi" })
        for (dd in rates) for (p in dd.panels)
            assertFalse("${dd.id}/${p.id} reads ${p.source} in the rates pages", p.source in equitySources)
        assertTrue(d("world_equity_indices").panels.any { it.source == "cboe" })
    }

    @Test fun noPlaceholderDeclaresADashedLayout() {
        for (dd in dashboards) for (p in dd.panels) if (p.isPlaceholder) {
            assertTrue("${dd.id}/${p.id} still declares placeholder columns", p.placeholderColumns.isEmpty() && p.placeholderRows == 0)
        }
    }
}
