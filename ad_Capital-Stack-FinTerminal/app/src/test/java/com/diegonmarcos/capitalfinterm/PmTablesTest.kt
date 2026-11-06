package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1478 the PM tables (data/pm.json): Vol and Profiles (the two tables of the Portfolios page since #1539), declared and consistent with the dashboards. */
class PmTablesTest {
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val tabs = RiskTabs.parse(DataFiles.dashboardsJson)
    private val nameOf = { id: String -> Dashboards.byId(dashboards, id)!!.entry }
    private val vol = PmTables.parse(DataFiles.pmJson, "vol", nameOf)!!
    private fun n(r: PmRow, i: Int) = r.cells[i].toInt()

    @Test fun volIsThePortfolioTableWithTheOwnersColumnsAndNames() {
        assertEquals(listOf("Portfolio", "DM", "DM\nBonds", "DM\nEquity", "EM", "EM\nBonds", "EM\nEquity", "Swap", "Vol"), vol.columns)
        assertEquals(
            listOf("DM Cash B100", "DM E30B70", "DM E40B60", "DM E60B40", "DM E70B30", "DM E40B40 · EM E10B10", "EM B100", "DM E50B10 · EM E30B10",
                "DM E70 · EM E30", "DM E60 · EM E40", "DM E40 · EM E60", "DM E30 · EM E70", "EM Carry EUR→BRL"),
            vol.rows.map { it.name },
        )
        assertNull("A2 is dropped (identical to DM E30B70)", Dashboards.byId(dashboards, "pm_a2_low"))
        for (r in vol.rows) assertEquals(r.name, vol.columns.size - 1, r.cells.size)
    }

    @Test fun everyAllocationAddsUpAndMatchesItsDashboard() {
        for (r in vol.rows.filter { it.dashboard != "pm_d0_carry" }) {
            val d = Dashboards.byId(dashboards, r.dashboard)!!
            assertEquals(r.name, 100, n(r, 0) + n(r, 3))
            assertEquals(r.name, n(r, 0), n(r, 1) + n(r, 2))
            assertEquals(r.name, n(r, 3), n(r, 4) + n(r, 5))
            assertEquals(r.name, d.emPct, n(r, 3))
            assertEquals(r.name, d.equityPct, n(r, 2) + n(r, 5))
        }
    }

    @Test fun swapIsYesNoForEmPortfoliosAndBlankForDmOnlyOnes() {
        for (r in vol.rows) {
            val em = Dashboards.byId(dashboards, r.dashboard)!!.market == "EM"
            assertEquals(r.name, if (em) "No" else "", r.cells[6])
        }
    }

    @Test fun levelsFilterTheRowsAndLevelThreeIsOrderedByVol() {
        val risk = { id: String -> Dashboards.byId(dashboards, id)!!.risk }
        assertEquals(vol.rows, PmTables.filter(vol.rows, RiskTabs.ALL, risk))
        fun of(t: String) = PmTables.filter(vol.rows, t, risk).map { it.dashboard }
        assertEquals(listOf("pm_a1_cash", "mp_70_30"), of("dm_low"))
        assertEquals(listOf("mp_60_40", "mp_40_60", "mp_30_70"), of("dm_mid"))
        assertEquals(listOf("pm_b1_mid", "pm_d1_em_bonds", "pm_c1_risky"), of("em_mid"))
        assertEquals(listOf("mp_70_30_DM_EM", "mp_60_40_DM_EM", "mp_40_60_DM_EM", "mp_30_70_DM_EM", "pm_d0_carry"), of("em_high"))
        val v = PmTables.filter(vol.rows, "em_mid", risk).map { it.cells.last().toDouble() }
        assertEquals(v.sorted(), v)
        assertEquals(tabs.drop(1).map { it.id }, listOf("dm_low", "dm_mid", "em_mid", "em_high"))
    }

    @Test fun commoditiesAreNoLongerAPmTableAndHeatStillScalesAgainstTheLargestWeight() {
        assertNull("#1539 the PM Commodities table is gone", PmTables.parse(DataFiles.pmJson, "commodities"))
        val rows = listOf(PmRow("A", "", listOf("50", "10", "x")), PmRow("B", "", listOf("25", "40", "y")))
        val h = PmTables.heat(rows, 1..2)
        assertEquals(1f, h.flatten().filterNotNull().maxOrNull()!!, 0f); assertEquals(1f, h[0][1]!!, 1e-6f); assertEquals(0.2f, h[0][2]!!, 1e-6f); assertEquals(0.5f, h[1][1]!!, 1e-6f); assertEquals(0.8f, h[1][2]!!, 1e-6f)
        assertNull(h[0][0]); assertNull(h[0][3])
        assertEquals(emptyList<Float?>(), PmTables.heat(rows, null)[0])
    }

    @Test fun profilesAreTheEightCycleProfiles() {
        val (head, groups) = PmTables.profiles(DataFiles.pmJson, "profiles")!!
        assertEquals(listOf("Growth", "Value", "Quality", "Cyclical", "Defensive", "Dividend", "Momentum", "Small-cap"), groups.map { it.label })
        for (g in groups) { assertTrue(g.id, g.leads.isNotBlank() && g.rows.isNotEmpty()); g.rows.forEach { assertEquals(head.columns.size, it.size) } }
        assertNull(PmTables.profiles(DataFiles.pmJson, "vol"))
    }

    @Test fun matrixLabelWidthsAreDeclaredPerTable() {
        val w = FinLogic.matrixWidths(300f, 9, vol.labels)
        assertEquals(9, w.size); assertEquals(300f, w.sum(), 0.01f)
        assertEquals(108f, w[0], 0.01f)
        assertEquals(FinLogic.matrixWidths(312f, 9), FinLogic.matrixWidths(312f, 9, FinLogic.MATRIX_LABELS))
    }
}
