package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserAddons
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.ScrapeEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #802 I7 the add-on registry and the scraper's pure engine. */
class BrowserAddonsTest {

    private val cfg = BrowserConfig.parse(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser").toString())

    @Test
    fun `the shipped add-ons parse, with known permissions, and their rows join the Add-ons section`() {
        assertTrue(cfg.addons.all.isNotEmpty())
        cfg.addons.all.forEach { a ->
            a.permissions.forEach { assertTrue("${a.id}: $it is in the vocabulary", it in BrowserAddons.PERMISSIONS) }
            a.menu.forEach { row ->
                assertEquals("page_tools", row.section)
                assertTrue("${row.id} requires its add-on", "addon:${a.id}" in row.requires)
                assertTrue("${row.id} is in the merged menu", cfg.menu.item(row.id) != null)
            }
        }
    }

    @Test
    fun `enabled follows the setting, else default_enabled, and a missing app disables the rows`() {
        val a = BrowserAddons.parse(JSONArray("""[{"id":"x","default_enabled":true},{"id":"y","requires_package":"com.example.y"}]"""))
        assertTrue(a.enabled("x", null))
        assertFalse(a.enabled("y", null))
        assertTrue(a.enabled("y", setOf("y")))
        assertFalse(a.enabled("x", setOf("y")))
        assertFalse("an unknown id is never enabled", a.enabled("z", setOf("z")))
        val f = a.facts(setOf("x", "y")) { false }
        assertEquals(true, f["addon:x"])
        assertEquals(false, f["addon:y"])
        // the catalogue's addons_enabled default is exactly the default_enabled set
        assertEquals(cfg.addons.all.filter { it.defaultEnabled }.map { it.id }.toSet(), cfg.settings["addons_enabled"]!!.default)
    }

    @Test
    fun `scraped pages merge in order, drop repeats, and cap`() {
        val p1 = listOf(mapOf("text" to "a"), mapOf("text" to "b"))
        val p2 = listOf(mapOf("text" to "b"), mapOf("text" to "c"))
        assertEquals(listOf("a", "b", "c"), ScrapeEngine.merge(listOf(p1, p2), 10).map { it["text"] })
        assertEquals(2, ScrapeEngine.merge(listOf(p1, p2), 2).size)
    }

    @Test
    fun `the plan caps pages and adds the attribute column`() {
        val plan = ScrapeEngine.simple(".titleline a", "href", 50, ".morelink", 5)
        assertEquals(5, plan.maxPages)
        assertEquals(listOf("text", "href"), plan.columns.map { it.name })
        assertEquals("href", plan.columns[1].attr)
        assertEquals(1, ScrapeEngine.simple("a", "", 0, null, 5).maxPages)
        val rows = ScrapeEngine.rows(JSONObject("""{"rows":[{"text":"HN","href":"https://x"},{"text":"Two"}]}"""), plan)
        assertEquals("", rows[1]["href"])
    }

    @Test
    fun `CSV quotes what it must`() {
        val csv = ScrapeEngine.csv(listOf(mapOf("a" to "x,y", "b" to "say \"hi\""), mapOf("a" to "line\nbreak", "b" to "plain")), listOf("a", "b"))
        assertEquals("a,b\n\"x,y\",\"say \"\"hi\"\"\"\n\"line\nbreak\",plain\n", csv)
    }
}
