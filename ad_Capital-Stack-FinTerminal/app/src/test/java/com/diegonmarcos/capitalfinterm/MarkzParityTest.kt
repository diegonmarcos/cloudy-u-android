package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1832 rule 16 for the owner's page: every element of Portfolio_Returns_Sensitivity_Matrix.html (frozen in src/test/resources/markz-owner-page-1832.json:
 * its element ids, input labels and units, top-row tabs and headings) is covered by MARKZ, each by what draws it: a tab by a MARKZ sub-page, an input by
 * a field of the model with the page's label and unit, an output by the figure the model computes for that id, a heading by a declared word.
 */
class MarkzParityTest {
    private val page = JSONObject(DataFiles.resource("markz-owner-page-1832.json"))
    private val d = Markz.parse(DataFiles.markzJson)!!
    private val s0 = d.defaults
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private val stacks = ui.getJSONObject("stacks").toString()
    private val entries = Pages.entries(stacks, "markz")
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private val home = Markz.home(d, s0, s0)
    private val kpis = Markz.kpis(d, s0, s0, Markz.matrix(s0, s0, true), true)
    private val matrix = Markz.matrix(s0, s0, true)

    /** How MARKZ draws the owner's element [id]; null = not covered. */
    private fun cover(id: String): String? = when {
        id.startsWith("nav-tab-") || id.startsWith("view-") -> d.tab(id.substringAfter("nav-tab-").substringAfter("view-"))?.let { t -> Pages.entryOf(entries, t.page)?.let { "page " + t.page } }
        id.startsWith("input-") -> if (id in Markz.FIELDS && d.inputs.any { it.id == id } && Markz.shown(s0, s0, id).isNotEmpty()) "field $id" else null
        id in home -> home[id]?.takeIf { it.isNotEmpty() }
        id in kpis -> kpis[id]?.takeIf { it.isNotEmpty() }
        Regex("th-(gross|infl|real)-([0-6])").matches(id) -> matrix.cols.getOrNull(id.last().digitToInt())?.let { "column ${it.idx}" }
        id == "matrix-body" -> if (matrix.rows.size == 7 && matrix.rows.all { it.cells.size == 7 }) "7 x 7" else null
        id == "inspector-scenario" -> Markz.inspector(d, matrix.center).first
        id == "inspector-math" -> Markz.inspector(d, matrix.center).second
        id == "btn-round-250" -> Markz.roundLabel(d, s0)
        id == "btn-round-exact" -> d.word("round_exact").ifEmpty { null }
        id == "btn-toggle-steps" || id == "steps-chevron" || id == "steps-collapse-panel" -> d.word("steps").ifEmpty { null }?.takeIf { d.inputs.count { it.column == "steps" } == 3 }
        id == "toast" || id == "toast-message" -> listOf("toast_copied", "toast_copy_failed", "toast_reset", "toast_loaded").map { d.word(it) }.takeIf { l -> l.all { it.isNotEmpty() } }?.joinToString()
        id == "markovitz-active-badge" -> d.word("mk_badge", "v" to "14.98")
        id == "markovitz-frontier-target-dot" -> Markz.targetDot(d.diagram.target, s0.centerYieldPct).toString()
        id == "markovitz-frontier-target-label" -> d.diagram.target.label.ifEmpty { null }
        id == "markovitz-portfolios-grid" -> d.portfolios.takeIf { it.size == 3 }?.joinToString { it.name }
        else -> null
    }

    @Test fun everyElementOfTheOwnersPageIsCovered() {
        val ids = strs(page.getJSONArray("ids"))
        assertEquals(96, ids.size)
        val missing = ids.filter { cover(it) == null }
        assertEquals("not covered: $missing", emptyList<String>(), missing)
    }

    @Test fun everyTabIsAMarkzSubPageInTheOwnersOrder() {
        val tabs = page.getJSONArray("tabs").let { a -> (0 until a.length()).map { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) } }
        assertEquals(tabs, d.tabs.map { it.id to it.label })
        // the owner's page opens on the Sensy Matrix: so does MARKZ (its Homepage), the page Home of the owner is MARKZ > Overview
        assertEquals("sensy", d.tabs.single { it.default }.id)
        assertEquals("markz-sensy", Pages.homeOverride(entries)?.page)
        for (t in d.tabs) {
            val e = Pages.entryOf(entries, t.page)
            assertNotNull("tab ${t.id}", e)
            assertEquals(Pages.KIND_MARKZ, e!!.kind)
            assertEquals(t.id, e.view)
        }
        val pages = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("id") == "markz" }.getJSONArray("pages")
        val ids = (0 until pages.length()).map { pages.getJSONObject(it).getString("id") }
        assertEquals(d.tabs.map { it.page }, ids.filter { it in d.tabs.map { t -> t.page } })
    }

    @Test fun everyInputHasThePagesLabelAndUnit() {
        val inputs = page.getJSONObject("inputs")
        assertEquals(17, inputs.length())
        assertEquals(inputs.keys().asSequence().toSet(), d.inputs.map { it.id }.toSet())
        assertEquals(Markz.FIELDS.toSet(), d.inputs.map { it.id }.toSet())
        for (f in d.inputs) {
            val want = strs(inputs.getJSONArray(f.id))
            assertEquals(f.id, want, listOf(f.label, f.unit))
        }
        assertEquals(7, d.inputs.count { it.column == "1" }); assertEquals(7, d.inputs.count { it.column == "2" }); assertEquals(3, d.inputs.count { it.column == "steps" })
    }

    @Test fun everyHeadingIsDeclared() {
        val words = d.words.values.toSet() + d.methodology.map { it.title } + d.substeps.map { it.title } + d.title
        for (h in strs(page.getJSONArray("headings"))) assertTrue("heading $h", h in words)
        assertEquals(page.getString("title"), d.title)
    }

    @Test fun theMarkovitzPageKeepsItsStepsPortfoliosAndDiagram() {
        assertEquals(listOf("Step 1", "Step 2", "Step 3", "Step 4", "Step 5"), d.methodology.map { it.step })
        assertEquals(listOf("Minimum Variance (MVP)", "Max Sharpe (Tangency)", "High-Carry Frontier Mix"), d.portfolios.map { it.name })
        assertEquals(listOf(10.60, 13.45, 14.98), d.portfolios.map { it.expReturn })
        assertEquals(listOf(4.80, 7.40, 9.65), d.portfolios.map { it.vol })
        assertEquals(4.25, d.rf, 0.0)
        val g = d.diagram
        assertEquals(420.0 to 220.0, g.width to g.height)
        assertEquals(6, g.lines.size); assertEquals(2, g.curves.size); assertEquals(5, g.points.size); assertEquals(3, g.texts.size)
        assertEquals(listOf("Asset A (Local Bills)", "Asset B (Duration Bonds)", "Asset C (Equities)", "Min Variance", "Max Sharpe"), g.points.map { it.label })
        // the substeps keep the owner's words and links, in the owner's order, and each is a MARKZ page
        assertEquals(listOf("volatility", "beta", "capm", "correlation", "optimization"), d.substeps.map { it.id })
        assertEquals(listOf("markovitz", "volatility", "beta", "capm", "correlation"), d.substeps.map { it.prev.first })
        assertEquals(listOf("beta", "capm", "correlation", "optimization", "sensy"), d.substeps.map { it.next.first })
        for (s in d.substeps) { assertNotNull(d.tab(s.prev.first)); assertNotNull(d.tab(s.next.first)) }
    }

    @Test fun theDefaultsShowThePagesFigures() {
        val f = page.getJSONObject("defaults")
        assertEquals("Net: €" + f.getString("net_eur_mom") + "/mo", home["nav-live-badge"])
        assertEquals("Net MoM: €" + f.getString("net_eur_mom") + " (Sheet: €" + f.getString("sheet_eur_mom") + ")", home["home-mom-eur"])
        assertEquals("Net MoM: $" + f.getString("net_usd_mom"), home["home-mom-usd"])
        assertEquals("Net MoM: R$ " + f.getString("net_brl_mom"), home["home-mom-brl"])
        assertEquals("€" + f.getString("aum_eur"), home["home-aum-eur"])
        assertEquals(f.getString("real"), kpis["kpi-real-yield"])
        assertEquals("€" + f.getString("annual_eur") + " / yr", home["home-wf-annual-eur"])
        assertEquals(f.getString("eurusd"), home["home-fx-eurusd"])
    }
}
