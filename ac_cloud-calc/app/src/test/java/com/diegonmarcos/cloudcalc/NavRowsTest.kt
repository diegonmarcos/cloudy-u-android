package com.diegonmarcos.cloudcalc

import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** #1409 Calc's second row: two labelled rows, Decimals | Others, the old Tools page redirected, Base first on Convert. */
@RunWith(RobolectricTestRunner::class)
class NavRowsTest {
    private val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui")
    private val sections = ui.getJSONArray("sections").toString()

    @Test fun `Calc draws two labelled rows`() {
        val calc = Declarations.parseNav(sections, "calculator", "calculator").section("calculator")!!
        assertEquals(SecondRowMode.ManyRows, calc.rows)
        val rows = calc.rowGroups()
        assertEquals(listOf("Decimals", "Others"), rows.map { it.label })
        assertEquals(listOf("calc", "latex", "convert", "history"), rows[0].pages.map { it.id })
        assertEquals(listOf("datetime", "sound", "finance", "graph"), rows[1].pages.map { it.id })
        assertEquals(listOf("Calc", "LaTeX", "Convert", "History", "Time & Date", "Sound", "Finance", "Graph"), rows.flatMap { it.pages }.map { it.label })
        assertTrue("no Tools page is left", calc.pages.none { it.id == "tools" })
        // The baked declaration is the one on disk.
        assertEquals(rows.map { g -> g.pages.map { it.id } }, Declarations.nav.section("calculator")!!.rowGroups().map { g -> g.pages.map { it.id } })
    }

    @Test fun `the Tools sections are pages of their own`() {
        assertEquals(listOf("datetime"), Declarations.modesOf("datetime").map { it.id })
        assertEquals(listOf("acoustics"), Declarations.modesOf("sound").map { it.id })
        assertEquals(listOf("finance"), Declarations.modesOf("finance").map { it.id })
        assertTrue(Declarations.modesOf("tools").isEmpty())
        val forms = Declarations.mode("datetime")!!.forms
        assertEquals("Date Converter", forms.first { it.id == "calendars" }.label)
        assertEquals("cycles", forms.first { it.label == "Calendars" }.view)
        assertEquals("base", Declarations.modesOf("convert").first().id)
        assertEquals("base_converter", Declarations.modesOf("convert").first().kind)
    }

    @Test fun `old deep links redirect`() {
        assertEquals(mapOf("tools" to "datetime"), Declarations.redirectsIn(sections))
        assertEquals("datetime", Declarations.resolve("tools"))
        assertEquals("convert", Declarations.resolve("convert"))
        val state = CalcState(null)
        state.tab = "tools"
        assertEquals("datetime", state.tab)
        assertEquals("calculator", state.section)
        state.tab = "graph"
        assertEquals("graph", state.tab)
    }
}
