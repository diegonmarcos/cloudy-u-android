package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserSettingsLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #886/#900 as DECLARED: Configs by page (the top tabs), the translate engine setting, Tabs in the island, the strip on by default. */
class BrowserConfigsLayoutTest {

    private val root = JSONObject(File("../build.json").readText())
    private val cfg = BrowserConfig.parse(root.getJSONObject("ui").getJSONObject("browser").toString())

    // #1402/#1443 Sites, Permissions, Autocomplete, Browser (the browser's own General); General | Perms | About come first on the top nav and are the fleet pages the shell draws
    private val pageIds = listOf("sites", "permissions", "autocomplete", "browsing", "headless")

    @Test
    fun `every setting sits on a declared Configs page and every page holds something`() {
        val ids = cfg.settingsSections.map { it.first }
        assertEquals(pageIds, ids)
        val used = cfg.settings.settings.map { it.section }.toSet()
        assertTrue("undeclared pages: ${used - ids.toSet()}", ids.containsAll(used))
        for (id in ids.filter { it != "about" }) assertTrue("page $id is empty",
            cfg.settings.settings.any { it.section == id } || cfg.configs.rowsFor(id).isNotEmpty() || cfg.configs.nativeFor(id).isNotEmpty())
    }

    @Test
    fun `the top tabs of the Configs section are the settings pages, in the same order`() {
        val secs = root.getJSONObject("ui").getJSONArray("sections")
        val configs = (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == "configs" }
        val tabs = configs.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(listOf("general", "perms", "about") + cfg.settingsSections.map { it.first }, tabs)
        assertEquals(listOf("Sites", "Permissions", "Autocomplete", "Browser", "Headless Browser"), cfg.settingsSections.map { it.second })
    }

    @Test
    fun `the page draws its settings in the declared order and an undeclared section is still drawn`() {
        val order = BrowserSettingsLayout.order(cfg.settings.settings, cfg.settingsSections, emptySet())
        assertEquals(cfg.settingsSections.filter { sec -> cfg.settings.settings.any { it.section == sec.first } }.map { it.second },
            order.map { it.second })
        val extra = BrowserSettingsLayout.order(cfg.settings.settings + cfg.settings.settings.first().copy(key = "x", section = "mystery"), cfg.settingsSections, emptySet())
        assertEquals("Mystery", extra.last().second)
    }

    @Test
    fun `a row on no declared page is dropped, and add-on global rows join their page`() {
        assertTrue(cfg.configs.rowsFor("browsing").filter { it.group == "search" }.map { it.id }.containsAll(listOf("search_with", "search_chat")))
        assertTrue(cfg.configs.rowsFor("autocomplete").filter { it.group == "sync" }.map { it.id }.containsAll(listOf("vault_open", "vault_set_service")))
        assertEquals(listOf("addon:search"), cfg.configs.rowsFor("browsing").first { it.id == "search_with" }.requires)
        val none = com.diegonmarcos.superapp.browser.BrowserConfigPages.parse(
            JSONObject("""{"rows":[{"id":"x","label":"X","page":"ghost"},{"id":"y","label":"Y","page":"p"}]}"""),
            listOf("p" to "P"), com.diegonmarcos.superapp.browser.BrowserAddons.EMPTY)
        assertEquals(listOf("y"), none.rows.map { it.id })
    }

    @Test
    fun `#1402 every former page id redirects to the page that holds its content, and every setting keeps its group`() {
        val old = listOf("general", "search", "privacy", "site", "autocomplete", "passwords", "payment", "downloads", "appearance", "tabs", "sync", "advanced")
        val want = mapOf("general" to "browsing", "search" to "browsing", "privacy" to "browsing", "site" to "sites", "passwords" to "autocomplete", "payment" to "autocomplete",
            "downloads" to "browsing", "appearance" to "browsing", "tabs" to "browsing", "sync" to "autocomplete", "advanced" to "browsing")
        old.forEach { assertEquals(it, want[it] ?: it, cfg.configs.pageFor(it)) }
        assertEquals(null, cfg.configs.pageFor("nowhere"))
        assertEquals(listOf("general", "search", "privacy", "site", "downloads", "appearance", "tabs", "advanced"), cfg.configs.groupsFor("browsing").map { it.id })
        assertEquals(listOf("autocomplete", "passwords", "payment", "sync"), cfg.configs.groupsFor("autocomplete").map { it.id })
        cfg.settings.settings.forEach { s ->
            val groups = cfg.configs.groupsFor(s.section).map { it.id }
            assertTrue("${s.key} sits in a group of ${s.section}", groups.isEmpty() || s.group in groups)
        }
        assertEquals(listOf("site_list"), cfg.configs.nativeFor("sites"))
        assertEquals(listOf("perms_defaults"), cfg.configs.nativeFor("permissions"))
        assertEquals("sites", cfg.configs.rows.first { it.id == "sites_manage" }.page)
        assertEquals("permissions", cfg.configs.rows.first { it.id == "site_perms_manage" }.page)
    }

    @Test
    fun `a Configs row needing a fact the page cannot give is disabled and says why`() {
        val facts = emptyMap<String, Boolean>()
        val r = cfg.configs.resolve("browsing", facts, cfg.menu.whys).first { it.id == "default_browser" }
        assertFalse(r.enabled)
        assertEquals("already the default browser, or Android offers no browser role", r.why)
        assertTrue(cfg.configs.resolve("browsing", facts, cfg.menu.whys).first { it.id == "bookmarks" }.enabled)
    }

    @Test
    fun `the translate engine is a setting - on-device ML or OpenRouter - and defaults to on-device`() {
        val s = cfg.settings["translate_engine"]!!
        assertEquals("enum", s.type)
        assertEquals(listOf("on_device", "openrouter"), s.values)
        assertEquals("on_device", s.default)
        assertEquals("browsing", s.section)
        assertEquals("OpenRouter (LLM)", s.valueLabels["openrouter"])
        assertEquals("device", cfg.settings["translate_target"]!!.default)
    }

    @Test
    fun `Tabs is a destination of the island and a section that opens the tab switcher`() {
        val ui = root.getJSONObject("ui")
        val nav = (0 until ui.getJSONArray("bottom_nav").length()).map { ui.getJSONArray("bottom_nav").getString(it) }
        assertEquals(listOf("weby", "cloudy", "browser", "tabs", "domains"), nav)
        val secs = ui.getJSONArray("sections")
        val ids = (0 until secs.length()).map { secs.getJSONObject(it).getString("id") }
        assertTrue(ids.containsAll(nav))
        assertTrue(nav.size <= 5)
    }

    @Test
    fun `the tab strip is on by default and the suggestions feed is declared per engine`() {
        assertEquals(true, cfg.settings["tab_strip"]!!.default)
        assertEquals(true, cfg.settings["search_suggestions"]!!.default)
        assertEquals("duckduckgo", cfg.defaultEngineId)
        assertEquals("https://duckduckgo.com/ac/?q={q}&type=list", cfg.engine("duckduckgo").suggest)
        assertFalse(cfg.engine("qwant").suggest != null)
    }

    @Test
    fun `translate and summarise by topics are in the page menu, translate as a toggle`() {
        val t = cfg.menu.item("translate")!!
        assertEquals("toggle", t.kind); assertEquals("translated", t.checked)
        assertEquals("Summarise page by topics", cfg.menu.item("summarize_topics")!!.label)
        // #1401 both are Page Tools (section id `addons`).
        assertEquals("addons", t.section)
        assertEquals("addons", cfg.menu.item("summarize_topics")!!.section)
    }
}
