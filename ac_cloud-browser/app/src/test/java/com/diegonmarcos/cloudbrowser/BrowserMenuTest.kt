package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserMenu
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #802 I3 the SHIPPED overflow menu resolves its rows against the page's facts. */
class BrowserMenuTest {

    private val browser = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser")
    private val menu = BrowserConfig.parse(browser.toString()).menu

    @Test
    fun `every declared row survives the parse, in a declared section, in order`() {
        // The declared rows plus each add-on's rows (#802 I7: they join the Add-ons section).
        val addons = browser.optJSONArray("addons")
        // #900 only an add-on's PAGE rows join the dropdown; its global rows (scope global) sit on a Configs page.
        val addonRows = (0 until (addons?.length() ?: 0)).sumOf { i ->
            val m = addons!!.getJSONObject(i).optJSONArray("menu")
            (0 until (m?.length() ?: 0)).count { m!!.getJSONObject(it).optString("scope") != "global" }
        }
        assertTrue("the add-ons contribute page rows", addonRows > 0)
        assertEquals(browser.getJSONObject("menu").getJSONArray("items").length() + addonRows, menu.items.size)
        val ids = menu.sections.map { it.id }
        val declared = browser.getJSONObject("menu").getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(declared, ids)
        assertEquals("#1401 Actions comes first", "page_actions", ids.first())
        menu.items.forEach { assertTrue("${it.id} in ${it.section}", it.section in ids) }
    }

    @Test
    fun `a row whose fact is false is disabled and says why`() {
        val noPage = menu.rows(emptyMap()).associateBy { it.item.id }
        assertFalse(noPage.getValue("find").enabled)
        assertEquals("open a page first", noPage.getValue("find").why)
        // A row with no requirement is always usable (#900: the dropdown has few; desktop site is one).
        assertTrue(noPage.getValue("desktop").enabled)
        assertNull(noPage.getValue("desktop").why)
    }

    @Test
    fun `every requirement must hold, not just the first`() {
        val pageOnly = menu.rows(mapOf("page" to true)).associateBy { it.item.id }
        assertTrue(pageOnly.getValue("find").enabled)
        assertFalse(pageOnly.getValue("back").enabled)                      // needs can_go_back too
        assertEquals("no earlier page in this tab", pageOnly.getValue("back").why)
        val both = menu.rows(mapOf("page" to true, "can_go_back" to true)).associateBy { it.item.id }
        assertTrue(both.getValue("back").enabled)
    }

    @Test
    fun `toggles show their fact, and only api rows are api`() {
        val rows = menu.rows(mapOf("desktop_mode" to true)).associateBy { it.item.id }
        assertEquals(true, rows.getValue("desktop").checked)
        assertEquals(false, rows.getValue("reader").checked)
        assertNull(rows.getValue("share").checked)
        assertTrue(menu.item("find")!!.api)
        assertFalse(menu.item("clear_site_data")!!.api)   // destructive: never over loopback
    }

    @Test
    fun `a row in an undeclared section is dropped, never drawn nowhere`() {
        val m = BrowserMenu.parse(JSONObject("""{"sections":[{"id":"a","label":"A"}],
            "items":[{"id":"x","section":"a"},{"id":"y","section":"ghost"}]}"""))
        assertEquals(listOf("x"), m.items.map { it.id })
    }

    @Test
    fun `user agents and palette are declared, not defaulted`() {
        val cfg = BrowserConfig.parse(browser.toString())
        assertTrue(cfg.userAgents.getValue("desktop").contains("X11"))
        assertEquals(0xFF1A0033.toInt(), cfg.palette.getValue("surface"))
        assertEquals(0x99FFFFFF.toInt(), cfg.palette.getValue("text_secondary"))
    }
}
