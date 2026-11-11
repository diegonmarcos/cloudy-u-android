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
 * #1623 The Configs nav tree and its hub page, the Agents hub (agents-watchdog). Configs is ONE page of six flat top-level
 * groups (the #1610 Device/Setup split removed): Launcher, Network, Apps, Connect, Tools, Configs. The Watchtower hub page is
 * gone (page:config/watchtower and /agents-watchtower are page_aliases of X-Ray Device).
 *
 *   H1 nav tree: six flat groups, Launcher, Network, Apps, Connect, Tools, Configs. The Agents hub and the Watchdog launch both
 *      sit in Tools; X-Ray Device / Logcat Fleet moved to Tools; Bluetooth is a Network page
 *   H2 the Agents hub shows one titled section, Network, derived from the nav (hub_sections name a group now that subgroups are gone)
 *   H3 routing: in-app pages are page:config/<id>; apps / sections / Cloud Store hand-offs keep their declared action
 *   H4 the hub routes to SubgroupHubFragment (not the C3 APKs) and draws a section heading and a Cloud ▸ Apps tile per entry
 *   H5 Cloud ▸ Apps ▸ Configs opens page:config/agents-watchdog where Firewall was
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubgroupHubTest : KitPageHarness() {

    private val config get() = Sections.byId("config")!!
    // #1623 Configs is one page of six flat top-level groups, so a group's members are its visible, non-separator pages.
    private fun grp(name: String) = config.pages.filter { it.group == name && !it.hidden && !it.separator }
    private fun hub(id: String) = SubgroupHubFragment.sectionsFor("config", id)

    @Test
    fun `H1 the Configs nav is the declared tree`() {
        // #1623: one page, six flat top-level groups (the #1610 Device/Setup split removed).
        val runs = config.pages.filter { !it.isAction }.map { it.group to it.subgroup }.distinct()
        assertEquals(listOf(
            "Launcher" to "", "Network" to "", "Apps" to "", "Connect" to "", "Tools" to "", "Configs" to ""), runs)
        // Connect carries Account, Cloudy C3 and Peer Control.
        assertEquals(listOf("Account", "Cloudy C3", "Peer Control"), grp("Connect").map { it.label })
        // Agents is the in-app agents-watchdog hub, in the Tools group (the owner's dual listing kept once, beside the observability pages).
        assertEquals(listOf("agents-watchdog"), config.pages.filter { it.hubSections.isNotEmpty() }.map { it.id })
        assertEquals("Tools" to "", config.pages.first { it.id == "agents-watchdog" }.let { it.group to it.subgroup })
        // Watchdog is the extapp:c3-watchdog tile, also in Tools.
        assertEquals("extapp:c3-watchdog", config.pages.first { it.id == "c3-watchdog" }.action)
        assertEquals("Tools" to "", config.pages.first { it.id == "c3-watchdog" }.let { it.group to it.subgroup })
        // The groups as declared.
        assertEquals(listOf("controls", "panels-ui", "app-folders", "onehand", "notify"), grp("Launcher").map { it.id })
        assertEquals(listOf("c3-watchdog", "xray", "logcat-fleet", "agents-watchdog"), grp("Tools").map { it.id })
        assertEquals(listOf("perms", "logcat", "xray-app", "app-about"), grp("Configs").map { it.id })
        assertEquals(listOf("X-Ray Device", "Logcat Fleet"),
            grp("Tools").filter { it.id == "xray" || it.id == "logcat-fleet" }.map { it.label })
        assertTrue("Bluetooth is a Network page", grp("Network").any { it.id == "bluetooth" })
        assertEquals("firewall", grp("Network").first().id)
        assertTrue("Watchdog is no Network item any more", grp("Network").none { it.id == "agents-watchdog" })
    }

    @Test
    fun `H2 the Agents hub shows Network`() {
        // #1623: agents-watchdog hubs the Network group (hub_sections name the group now that subgroups are flattened away).
        assertEquals(listOf("Network"), hub("agents-watchdog").map { it.title })
        assertEquals(grp("Network").map { it.id }, hub("agents-watchdog")[0].tiles.map { it.id })
        assertTrue(hub("agents-watchdog")[0].tiles.map { it.id }.containsAll(listOf("firewall", "dns")))
    }

    @Test
    fun `H3 pages navigate in-app, apps keep their own declared action`() {
        val by = hub("agents-watchdog").flatMap { it.tiles }.associateBy { it.id }
        assertEquals("page:config/firewall", by.getValue("firewall").target)
        assertEquals("page:config/dns", by.getValue("dns").target)
        assertEquals("page:config/bluetooth", by.getValue("bluetooth").target)
        assertEquals("action:open_webserver", by.getValue("webserver").target)
        assertEquals("action:open_apps_access", by.getValue("apps-access").target)
        assertEquals("section:wg", by.getValue("wg").target)
        for (p in grp("Network")) assertEquals(p.id, Sections.hubTarget("config", p), by.getValue(p.id).target)
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
