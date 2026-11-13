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
 *   H5 Cloud ▸ Apps ▸ Configs row = Quick Actions, UI | Firewall, WiFi | Store | Account, C3 | Watchdog | About (#1628); Watchdog launches extapp:c3-watchdog
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
        // #1719 the Network group shows ONE page, Network (the page with its own bottom nav); Bluetooth, the firewall and the rest
        // are still Network pages, declared hidden and reached from it (NetworkNavParityTest holds the full list).
        assertEquals(listOf("network"), grp("Network").map { it.id })
        assertTrue("Bluetooth is a Network page", config.allPages.any { it.id == "bluetooth" && it.group == "Network" })
        assertEquals("firewall", config.allPages.first { it.group == "Network" && it.id != "network" }.id)
        assertTrue("Watchdog is no Network item any more", grp("Network").none { it.id == "agents-watchdog" })
    }

    @Test
    fun `H2 the Agents hub shows Network`() {
        // #1623: agents-watchdog hubs the Network group (hub_sections name the group now that subgroups are flattened away).
        assertEquals(listOf("Network"), hub("agents-watchdog").map { it.title })
        assertEquals(grp("Network").map { it.id }, hub("agents-watchdog")[0].tiles.map { it.id })
        // #1719 rule 16: the hub keeps the icons it listed (every old Network page except the removed ADB Shell), beside the Network tile.
        assertEquals(listOf("network", "firewall", "dns", "webserver", "wg", "apps-access", "wifi", "bluetooth"), hub("agents-watchdog")[0].tiles.map { it.id })
    }

    @Test
    fun `H3 pages navigate in-app, apps keep their own declared action`() {
        // #1719 the pages are hidden behind the Network page, so their targets are read from allPages.
        val by = config.allPages.filter { it.group == "Network" }.associateBy { it.id }
        assertEquals("page:config/network", Sections.hubTarget("config", by.getValue("network")))
        assertEquals("page:config/firewall", Sections.hubTarget("config", by.getValue("firewall")))
        assertEquals("page:config/dns", Sections.hubTarget("config", by.getValue("dns")))
        assertEquals("page:config/bluetooth", Sections.hubTarget("config", by.getValue("bluetooth")))
        assertEquals("action:open_webserver", Sections.hubTarget("config", by.getValue("webserver")))
        assertEquals("action:open_apps_access", Sections.hubTarget("config", by.getValue("apps-access")))
        assertEquals("section:wg", Sections.hubTarget("config", by.getValue("wg")))
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
    fun `H5 Cloud Apps Configs row reads Quick Actions, UI, Firewall, WiFi, Store, Account, C3, Watchdog, About (1628)`() {
        // #1628 redefined the Cloud > Apps > Configs row (supersedes #1611): Quick Actions, UI | Firewall, WiFi |
        // Store | Account, C3 | Watchdog | About. Watchdog now launches the C3-Watchdog APK (extapp:c3-watchdog)
        // and a Firewall tile (page:config/firewall) is present. The config-section Agents hub page is a separate surface.
        val row = Sections.all().flatMap { it.tileGroups }.single { it.title == "Configs" }.tiles.filter { !it.separator }
        assertEquals(
            listOf("Quick Actions", "UI", "NG Firewall", "WiFi", "Store", "Account", "C3", "Watchdog", "About"),
            row.map { it.label })
        assertTrue("Watchdog launches the C3-Watchdog app", row.any { it.label == "Watchdog" && it.target == "extapp:c3-watchdog" })
        assertTrue("Firewall opens the Network firewall page", row.any { it.label == "NG Firewall" && it.target == "page:config/firewall" })
    }
}
