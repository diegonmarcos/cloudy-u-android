package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1545 #1548 The Configs nav tree and its hub page, Agents ▸ Watchdog (the Watchtower hub page is gone: its content is the
 * Watchtower - Obsv subgroup, and page:config/watchtower and /agents-watchtower are page_aliases of X-Ray Device).
 *
 *   H1 nav tree: Setup (Connect, Apps, Launcher, Network, Watchtower - Obsv), Agents (Watchdog), Cloudy C3 (C3, Peer Control)
 *      Launcher = Quick Actions, One-Hand, Folders, Notify | Perms, Logcat, X-Ray App, About; Bluetooth is a Network page
 *   H2 Watchdog = two titled sections, Network then Watchtower - Obsv, derived from the nav, in order
 *   H3 routing: in-app pages are page:config/<id>; apps / sections / Cloud Store hand-offs keep their declared action
 *   H4 Watchdog routes to SubgroupHubFragment (not the C3 APKs) and draws a section heading and a Cloud ▸ Apps tile per entry
 *   H5 Cloud ▸ Apps ▸ Configs opens page:config/agents-watchdog where Firewall was
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubgroupHubTest : KitPageHarness() {

    private val config get() = Sections.byId("config")!!
    private fun sub(name: String) = config.pages.filter { it.subgroup == name }
    private fun hub(id: String) = SubgroupHubFragment.sectionsFor("config", id)

    @Test
    fun `H1 the Configs nav is the declared tree`() {
        val runs = config.pages.filter { !it.isAction }.map { it.group to it.subgroup }.distinct()
        assertEquals(listOf(
            "Setup" to "Connect", "Setup" to "Apps", "Setup" to "Launcher", "Setup" to "Network", "Setup" to "Watchtower - Obsv",
            "Agents" to "", "Cloudy C3" to ""), runs)
        assertEquals(listOf("C3", "Peer Control"), config.pages.filter { it.group == "Cloudy C3" }.map { it.label })
        assertEquals(listOf("agents-watchdog"), config.pages.filter { it.group == "Agents" }.map { it.id })
        assertEquals(listOf("controls", "onehand", "app-folders", "notify", "launcher-sep-1", "perms", "logcat", "xray-app", "app-about"),
            sub("Launcher").map { it.id })
        assertEquals(listOf("xray", "logcat-fleet"), sub("Watchtower - Obsv").map { it.id })
        assertEquals(listOf("X-Ray Device", "Logcat Fleet"), sub("Watchtower - Obsv").map { it.label })
        assertTrue("Bluetooth is a Network page", sub("Network").any { it.id == "bluetooth" })
        assertEquals("firewall", sub("Network").first().id)
        assertTrue("Watchdog is no Network item any more", sub("Network").none { it.id == "agents-watchdog" })
    }

    @Test
    fun `H2 Watchdog shows Network and Watchtower - Obsv`() {
        assertEquals(listOf("Network", "Watchtower - Obsv"), hub("agents-watchdog").map { it.title })
        assertEquals(sub("Network").map { it.id }, hub("agents-watchdog")[0].tiles.map { it.id })
        assertEquals(sub("Watchtower - Obsv").map { it.id }, hub("agents-watchdog")[1].tiles.map { it.id })
        assertTrue(hub("agents-watchdog")[0].tiles.map { it.id }.containsAll(listOf("firewall", "dns")))
    }

    @Test
    fun `H3 pages navigate in-app, apps keep their own declared action`() {
        val by = hub("agents-watchdog").flatMap { it.tiles }.associateBy { it.id }
        assertEquals("page:config/firewall", by.getValue("firewall").target)
        assertEquals("page:config/dns", by.getValue("dns").target)
        assertEquals("page:config/xray", by.getValue("xray").target)
        assertEquals("page:config/logcat-fleet", by.getValue("logcat-fleet").target)
        assertEquals("page:config/bluetooth", by.getValue("bluetooth").target)
        assertEquals("action:open_webserver", by.getValue("webserver").target)
        assertEquals("action:open_apps_access", by.getValue("apps-access").target)
        assertEquals("section:wg", by.getValue("wg").target)
        for (p in sub("Network") + sub("Watchtower - Obsv")) assertEquals(p.id, Sections.hubTarget("config", p), by.getValue(p.id).target)
    }

    @Test
    fun `H4 Watchdog is the hub fragment and draws its headings and a Cloud Apps tile per entry`() {
        val frag = SectionPages.pagesFor("config").first { it.id == "agents-watchdog" }.factory()
        assertTrue("routes to the hub: ${frag.javaClass.simpleName}", frag is SubgroupHubFragment)
        show(frag)
        compose.onNodeWithTag(SubgroupHubFragment.TAG_GRID).assertExists()
        for (s in hub("agents-watchdog")) {
            compose.onNodeWithTag(GroupedTilesFragment.groupTag(s.title)).assertExists()
            for (t in s.tiles) compose.onNodeWithTag(GroupedTilesFragment.tileTag(t.target)).assertExists()
        }
    }

    @Test
    fun `H4b the removed Watchtower hub resolves to X-Ray Device, not a 404`() {
        assertTrue("no agents-watchtower page is declared", config.pages.none { it.id == "agents-watchtower" })
        assertEquals("config" to "xray", Sections.canonicalPage("config", "watchtower"))
        assertEquals("config" to "xray", Sections.canonicalPage("config", "agents-watchtower"))
        assertTrue(config.allPages.any { it.id == "xray" })
    }

    @Test
    fun `H5 Cloud Apps Configs opens the Watchdog page where Firewall was`() {
        val row = Sections.all().flatMap { it.tileGroups }.single { it.title == "Configs" }.tiles.filter { !it.separator }
        assertTrue(row.any { it.label == "Watchdog" && it.target == "page:config/agents-watchdog" })
        assertTrue("no Firewall tile left", row.none { it.label == "Firewall" })
    }
}
