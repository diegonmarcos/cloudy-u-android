package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserSitePolicy
import com.diegonmarcos.superapp.browser.BrowserTrackers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #900 the page dropdown (top-right of the browser view) holds ONLY what acts on the current page or domain.
 * Everything global moved to a Configs page; this holds the split on the SHIPPED build.json.
 */
class BrowserPageMenuTest {

    private val browser = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser")
    private val cfg = BrowserConfig.parse(browser.toString())

    /** What used to sit in the dropdown but is global: settings, library, tabs, add-on management, default browser, vault. */
    private val global = setOf(
        "settings", "profile", "offline_manage", "storage_manage", "default_browser", "clear_data", "history", "bookmarks",
        "downloads", "addons_manage", "new_tab", "new_private_tab", "tab_groups", "pin_tab", "close_tab",
        "search_with", "search_chat", "vault_open", "vault_set_service", "sites_manage",
    )

    @Test
    fun `the dropdown has no global row and no settings section`() {
        val ids = cfg.menu.items.map { it.id }.toSet()
        assertEquals("global rows in the dropdown: ${ids intersect global}", emptySet<String>(), ids intersect global)
        assertEquals(listOf("page_actions", "addons", "site", "site_perms", "devtools"), cfg.menu.sections.map { it.id })
        assertTrue("no row lives in a settings section", cfg.menu.items.none { it.section == "settings" || it.settingsSection != null })
    }

    @Test
    fun `every row the owner listed for the page or domain is there`() {
        val ids = cfg.menu.items.map { it.id }.toSet()
        listOf("reload", "find", "share", "copy_url", "open_external", "desktop", "zoom", "site_settings", "clear_site_cookies",
            "clear_site_data", "site_autocomplete", "translate", "reader", "bookmark", "save_page", "download_page", "shield_trackers",
            "view_source").forEach { assertTrue("$it is in the page dropdown", it in ids) }
    }

    @Test
    fun `a row that acts on the page is disabled with no page open, and says so`() {
        cfg.menu.rows(emptyMap()).filter { "page" in it.item.requires && it.item.section != "addons" }.forEach {
            assertFalse("${it.item.id} is usable with no page open", it.enabled)
            assertEquals("open a page first", it.why)
        }
        assertTrue("the desktop toggle needs no page", cfg.menu.rows(emptyMap()).first { it.item.id == "desktop" }.enabled)
    }

    @Test
    fun `every global thing is reachable from a Configs page instead`() {
        val rows = cfg.configs.rows.map { it.id }.toSet()
        listOf("bookmarks", "history", "clear_data", "downloads", "offline_manage", "storage_manage", "default_browser", "profile",
            "tab_groups", "new_tab", "new_private_tab", "addons_manage", "sites_manage", "search_with", "vault_open").forEach {
            assertTrue("$it is a Configs row", it in rows)
        }
    }

    @Test
    fun `trackers - a tracker subresource is blocked, the page's own site and the main frame are not`() {
        val t = BrowserTrackers(listOf("doubleclick.net", "Hotjar.com", "net", "  "))
        assertTrue(t.isTracker("ad.doubleclick.net"))
        assertTrue(t.isTracker("doubleclick.net"))
        assertTrue(t.isTracker("static.hotjar.com"))
        assertFalse("a bare TLD in the list matches nothing", t.isTracker("example.net"))
        assertFalse(t.isTracker("notdoubleclick.net"))
        assertTrue(t.shouldBlock("ad.doubleclick.net", "news.example", false))
        assertFalse("the main frame is never blocked", t.shouldBlock("ad.doubleclick.net", "news.example", true))
        assertFalse("opening the tracker's own site on purpose", t.shouldBlock("static.hotjar.com", "hotjar.com", false))
        assertFalse(t.shouldBlock("cdn.example", "news.example", false))
    }

    @Test
    fun `the shipped tracker list is the app's content and parses`() {
        assertTrue(cfg.trackerHosts.size > 20)
        assertTrue(cfg.trackerHosts.all { it.contains('.') && !it.contains('/') && it == it.lowercase() })
    }

    @Test
    fun `zoom is per site, covers subdomains and is clamped`() {
        val rules = mapOf("example.org|zoom" to "150", "a.example.org|zoom" to "9999", "bad.test|zoom" to "x")
        assertEquals(150, BrowserSitePolicy.zoomOf(rules, "www.example.org"))
        assertEquals("the most specific host wins, clamped", BrowserSitePolicy.ZOOM_MAX, BrowserSitePolicy.zoomOf(rules, "a.example.org"))
        assertNull(BrowserSitePolicy.zoomOf(rules, "bad.test"))
        assertNull(BrowserSitePolicy.zoomOf(rules, "other.example"))
    }

    @Test
    fun `hosts with rules are grouped for the Sites page`() {
        val h = BrowserSitePolicy.hostsWithRules(mapOf("a.example|camera" to "allow", "a.example|zoom" to "120", "b.example|trackers" to "allow"))
        assertEquals(listOf("a.example", "b.example"), h.keys.toList())
        assertEquals(mapOf("camera" to "allow", "zoom" to "120"), h.getValue("a.example"))
    }
}
