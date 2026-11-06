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
 * #1545 #1548 The Configs nav tree and its two hub pages, Agents ▸ Watchdog and Agents ▸ Watchtower.
 *
 *   H1 nav tree: Setup (Connect, Apps, Launcher, Network, Observability), Fleet (C3, Peer Control, Bluetooth), Agents (Watchdog, Watchtower)
 *   H2 Watchdog = two titled sections, Network then Observability; Watchtower = Observability only; each derived from the nav, in order
 *   H3 routing: in-app pages are page:config/<id>; apps / sections / Cloud Store hand-offs keep their declared action
 *   H4 both pages route to SubgroupHubFragment (not the C3 APKs) and draw a section heading and a Cloud ▸ Apps tile per entry
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
            "Setup" to "Connect", "Setup" to "Apps", "Setup" to "Launcher", "Setup" to "Network", "Setup" to "Observability",
            "Fleet" to "", "Agents" to ""), runs)
        assertEquals(listOf("C3", "Peer Control", "Bluetooth"), config.pages.filter { it.group == "Fleet" }.map { it.label })
        assertEquals(listOf("agents-watchdog", "agents-watchtower"), config.pages.filter { it.group == "Agents" }.map { it.id })
        assertEquals(listOf("xray", "logcat", "perms", "app-about"), sub("Observability").map { it.id })
        assertEquals("firewall", sub("Network").first().id)
        assertTrue("Watchdog is no Network item any more", sub("Network").none { it.id == "agents-watchdog" })
    }

    @Test
    fun `H2 Watchdog shows Network and Observability, Watchtower only Observability`() {
        assertEquals(listOf("Network", "Observability"), hub("agents-watchdog").map { it.title })
        assertEquals(sub("Network").map { it.id }, hub("agents-watchdog")[0].tiles.map { it.id })
        assertEquals(sub("Observability").map { it.id }, hub("agents-watchdog")[1].tiles.map { it.id })
        assertEquals(listOf("Observability"), hub("agents-watchtower").map { it.title })
        assertEquals(sub("Observability").map { it.label }, hub("agents-watchtower")[0].tiles.map { it.label })
        assertTrue(hub("agents-watchdog")[0].tiles.map { it.id }.containsAll(listOf("firewall", "dns")))
    }

    @Test
    fun `H3 pages navigate in-app, apps keep their own declared action`() {
        val by = hub("agents-watchdog").flatMap { it.tiles }.associateBy { it.id }
        assertEquals("page:config/firewall", by.getValue("firewall").target)
        assertEquals("page:config/dns", by.getValue("dns").target)
        assertEquals("page:config/xray", by.getValue("xray").target)
        assertEquals("page:config/perms", by.getValue("perms").target)
        assertEquals("action:open_webserver", by.getValue("webserver").target)
        assertEquals("action:open_apps_access", by.getValue("apps-access").target)
        assertEquals("section:wg", by.getValue("wg").target)
        for (p in sub("Network") + sub("Observability")) assertEquals(p.id, Sections.hubTarget("config", p), by.getValue(p.id).target)
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
    fun `H4b Watchtower is the same hub fragment`() {
        val frag = SectionPages.pagesFor("config").first { it.id == "agents-watchtower" }.factory()
        assertTrue("routes to the hub: ${frag.javaClass.simpleName}", frag is SubgroupHubFragment)
        show(frag)
        compose.onNodeWithTag(GroupedTilesFragment.groupTag("Observability")).assertExists()
        compose.onNodeWithTag(GroupedTilesFragment.groupTag("Network")).assertDoesNotExist()
    }

    @Test
    fun `H5 Cloud Apps Configs opens the Watchdog page where Firewall was`() {
        val row = Sections.all().flatMap { it.tileGroups }.single { it.title == "Configs" }.tiles.filter { !it.separator }
        assertTrue(row.any { it.label == "Watchdog" && it.target == "page:config/agents-watchdog" })
        assertTrue("no Firewall tile left", row.none { it.label == "Firewall" })
    }
}
