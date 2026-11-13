package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1722 the terminal's command line: every page has a function mnemonic, a mnemonic opens exactly one page, and the chrome's declaration is complete. */
class CommandLineTest {
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private val stacks = ui.getJSONObject("stacks").toString()
    private val sections = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val withPages = sections.filter { (it.optJSONArray("pages")?.length() ?: 0) > 0 }.map { it.getString("id") to it.getString("label") }
    private val functions = Pages.functions(stacks, withPages)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)

    @Test fun everyMnemonicIsUniqueAndUpperCase() {
        assertEquals(functions.size, functions.map { it.fn }.toSet().size)
        for (f in functions) assertTrue(f.fn, f.fn.isNotBlank() && f.fn == f.fn.uppercase() && f.fn.length <= 6 && f.fn.none { it.isWhitespace() })
    }

    @Test fun everySectionAndEverySubPageHasAFunction() {
        val bottom = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(bottom, withPages.map { it.first })
        for ((id, label) in withPages) {
            val home = functions.first { it.section == id && it.dashboard.isEmpty() && it.page.isEmpty() }
            assertEquals(label.uppercase(), home.fn)
            // every sub-page of the one-row nav (after Home) is opened by exactly one function
            val pages = sections.first { it.getString("id") == id }.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }.drop(1)
            val opened = functions.filter { it.section == id }.mapNotNull { f -> if (f.dashboard.isNotEmpty()) Pages.slugOf(f.dashboard) else f.page.ifEmpty { null } }
            for (p in pages) assertEquals("$id/$p", 1, opened.count { it == p })
        }
        // the detail dashboards (PM's model portfolios) are functions too: every dashboard of the app is reachable from the command line
        assertEquals(dashboards.map { it.id }.toSet(), functions.filter { it.dashboard.isNotEmpty() }.map { it.dashboard }.toSet())
    }

    @Test fun aFunctionOpensAPageThatExistsInItsSection() {
        for (f in functions) {
            if (f.dashboard.isNotEmpty()) assertEquals("${f.fn} opens ${f.dashboard}", f.section, Dashboards.byId(dashboards, f.dashboard)?.tab)
            else if (f.page.isNotEmpty()) assertNotNull("${f.fn} opens ${f.page}", Pages.entryOf(Pages.entries(stacks, f.section), f.page))
        }
    }

    @Test fun theCommandLineResolvesWhatIsTyped() {
        assertEquals("fi-gov-dm", Pages.function(functions, "gdm")!!.page)
        assertEquals("fi-gov-dm", Pages.function(functions, "  GDM <GO>")!!.page)
        assertEquals("eq-top", Pages.function(functions, "top go")!!.page)
        assertEquals("yield_curves", Pages.function(functions, "YCRV")!!.dashboard)
        assertEquals("cb", Pages.function(functions, "YCRV")!!.section)
        assertEquals("Rates", Pages.function(functions, "YCRV")!!.group)
        assertEquals("Performance", Pages.function(functions, "MOM")!!.group)
        assertEquals("Data", Pages.function(functions, "WL")!!.group)
        assertEquals("fi", Pages.function(functions, "fi")!!.section)
        assertTrue(Pages.function(functions, "FI")!!.let { it.page.isEmpty() && it.dashboard.isEmpty() })
        assertEquals("my-pm", Pages.function(functions, "my-pm")!!.section)
        assertNull(Pages.function(functions, "NOPE")); assertNull(Pages.function(functions, "")); assertNull(Pages.function(functions, "   "))
        assertEquals("GDM", Pages.commandWord(" gdm<go> ")); assertEquals("GO", Pages.commandWord("go"))
    }

    @Test fun theFactorPortfoliosAndTheBondGroupsHaveTheirMnemonics() {
        val want = mapOf("GRW" to "eq-growth", "VAL" to "eq-value", "QLTY" to "eq-quality", "VOL" to "eq-vol", "TOP" to "eq-top", "CYC" to "eq-cyclical", "DEF" to "eq-defensive",
            "DVD" to "eq-dividend", "MOM" to "eq-momentum", "SML" to "eq-small", "FIA" to "fi-all", "GDM" to "fi-gov-dm", "GEM" to "fi-gov-em", "AAA" to "fi-aaa", "AA" to "fi-aa-a",
            "BBB" to "fi-bbb", "JUNK" to "fi-junk")
        for ((fn, page) in want) assertEquals(fn, page, Pages.function(functions, fn)?.page)
    }

    @Test fun theChromeDeclarationIsComplete() {
        val t = Terminal.parse(DataFiles.terminalJson)!!
        assertTrue(t.monitor.size >= 8)
        assertEquals(t.monitor.size, t.monitor.map { it.symbol }.toSet().size)
        assertTrue(t.monitor.all { it.symbol.isNotBlank() && it.label.isNotBlank() && it.decimals in 0..6 })
        assertEquals(listOf(Terminal.MENU_FUNCTIONS, Terminal.MENU_SEARCH, Terminal.MENU_SOURCES), t.menu.map { it.id })
        assertTrue(t.menu.all { it.number.endsWith(")") && it.label.isNotBlank() })
        for (k in listOf("placeholder", "unknown", "functions_title", "home")) assertTrue(k, t.commandWord(k).isNotBlank())
        for (k in listOf("live", "waiting", "pricing", "currency")) assertTrue(k, t.statusWord(k).isNotBlank())
        assertTrue(Terminal.unknown(t, "XYZ").contains("XYZ"))
        assertEquals("No function XYZ.", Terminal.unknown(null, "XYZ"))
        assertNull(Terminal.parse("not json"))
        assertEquals(FinFormat.DASH, Terminal.price(null, 2))
        assertEquals("6,812.50", Terminal.price(Quote(6812.5, 0L, "", "", "", null, null, null, null, null, null, null), 2))
        assertEquals("104,250", Terminal.price(Quote(104250.4, 0L, "", "", "", null, null, null, null, null, null, null), 0))
    }
}
