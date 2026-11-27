package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.network.NgFirewallHub
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1623 The Configs nav tree and its hub page, the Agents hub (agents-watchdog). Configs is ONE page of six flat top-level
 * groups (the #1610 Device/Setup split removed): Launcher, Network, Apps, Connect, Tools, Configs. #1943: Connect is C3 with Tools/Profiling
 * merged in, and the Configs group left the Setup grid (its pages are hidden there and listed by the Launcher hub, `hub_pages`). The Watchtower hub page is
 * gone (page:config/watchtower and /agents-watchtower are page_aliases of X-Ray Device).
 *
 *   H1 nav tree: six flat groups, Launcher, Network, Apps, Connect, Tools, Configs. The Agents hub and the Watchdog launch both
 *      sit in Tools; X-Ray Device / Logcat Fleet moved to Tools; Bluetooth is a Network page
 *   H2 the Agents hub shows one titled section, Network, derived from the nav (hub_sections name a group now that subgroups are gone)
 *   H3 routing: in-app pages are page:config/<id>; apps / sections / Cloud Store hand-offs keep their declared action
 *   H4 the hub routes to SubgroupHubFragment (not the C3 APKs) and draws a section heading and a Cloud ▸ Apps tile per entry
 *   H5 Cloud ▸ Apps ▸ Configs row = Quick Actions, UI | NG Firewall | Store, Account | C3 | Profile, About (#1755, was #1745); Watchdog stays on Setup ▸ C3
 *   H6 the Launcher hub (#1755) lists every visible Launcher page, and the #1755 reorg lost no Configs page or Cloud Apps Configs row item (field parity)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubgroupHubTest : KitPageHarness() {

    private val config get() = Sections.byId("config")!!
    // #1623 Configs is one page of six flat top-level groups, so a group's members are its visible, non-separator pages.
    private fun grp(name: String) = config.pages.filter { it.group == name && !it.hidden && !it.separator }
    // #1943 the Configs group's pages are hidden from the Setup grid (so not in `pages`) and drawn by the Launcher hub: read them off allPages.
    private fun grpAll(name: String) = config.allPages.filter { it.group == name && (!it.hidden || it.id in config.pages.flatMap { p -> p.hubPages }) && !it.separator }
    private fun hub(id: String) = SubgroupHubFragment.sectionsFor("config", id)

    @Test
    fun `H1 the Configs nav is the declared tree`() {
        // #1623: one page, six flat top-level groups (the #1610 Device/Setup split removed).
        val runs = config.pages.filter { !it.isAction }.map { it.group to it.subgroup }.distinct()
        assertEquals(listOf(
            "Launcher" to "", "Network" to "", "Apps" to "", "C3" to "", "Configs" to ""), runs)   // #1943 Connect + Profiling = C3; Configs is still a Section.pages group (Home row, Canopus, Sirius), only the Setup grid skips it
        // C3 carries Cloudy C3, Peer Control (#1745), Watchdog, X-Ray Device and Logcat Fleet (#1943: Profiling merged in).
        assertEquals(listOf("Cloudy C3", "Peer Control", "Watchdog", "X-Ray Device", "Logcat Fleet"), grp("C3").map { it.label })
        // #1755 Agents is the in-app agents-watchdog hub, now in the Configs group between Perms and Logcat app (the Tools group is Profiling);
        // the Launcher hub (launcher-hub) is the second hub page.
        assertEquals(listOf("launcher-hub", "agents-watchdog"), config.allPages.filter { it.hubSections.isNotEmpty() }.map { it.id })
        assertEquals("Configs" to "", config.allPages.first { it.id == "agents-watchdog" }.let { it.group to it.subgroup })
        // Watchdog is the extapp:c3-watchdog tile, also in Tools.
        assertEquals("extapp:c3-watchdog", config.pages.first { it.id == "c3-watchdog" }.action)
        assertEquals("C3" to "", config.pages.first { it.id == "c3-watchdog" }.let { it.group to it.subgroup })
        // The groups as declared.
        assertEquals(listOf("launcher-hub", "controls", "panels-ui", "app-folders", "onehand", "notify", "launcher-configs"), grp("Launcher").map { it.id })
        assertEquals(listOf("store", "profile"), grp("Apps").map { it.id })
        assertEquals(listOf("c3", "kde", "c3-watchdog", "xray", "logcat-fleet"), grp("C3").map { it.id })
        assertTrue("every Configs page is hidden_on setup (not `hidden`: the Home row, Canopus arc and Sirius tree keep them)", grp("Configs").size == 6 && grp("Configs").all { "setup" in it.hiddenOn })
        assertEquals(listOf("profile-app", "perms", "agents-watchdog", "logcat", "xray-app", "app-about"), grpAll("Configs").map { it.id })
        assertEquals(listOf("Profile", "Perms", "Agents", "Logcat app", "X-Ray App", "About"), grpAll("Configs").map { it.label })
        assertEquals("extapp:cloud-account", config.allPages.first { it.id == "profile-app" }.action)
        assertEquals(listOf("X-Ray Device", "Logcat Fleet"),
            grp("C3").filter { it.id == "xray" || it.id == "logcat-fleet" }.map { it.label })
        // #1737 the Network group shows the individual icons on the Configs page, in the owner's order, `|` between the sub-groups:
        // NG Firewall (hub) | Wi-Fi Bluetooth DNS VPN | Firewall OS Perms | Webserver Proxy; Bluetooth right after Wi-Fi.
        assertEquals(listOf("ng-firewall", "wifi", "bluetooth", "dns", "wg", "firewall", "os-perms", "webserver", "proxy"), grp("Network").map { it.id })
        assertEquals(listOf("NG Firewall", "Wi-Fi", "Bluetooth", "DNS", "VPN", "Firewall", "OS Perms", "Webserver", "Proxy"), grp("Network").map { it.label })
        assertEquals(NgFirewallHub.CONFIGS_NETWORK_ORDER,
            config.allPages.filter { it.group == "Network" && !it.hidden }.map { if (it.separator) "|" else it.id })
        assertTrue("no unified Network page", config.allPages.none { it.id == "network" })
        assertTrue("Watchdog is no Network item any more", grp("Network").none { it.id == "agents-watchdog" })
    }

    @Test
    fun `H2 the Agents hub shows Network`() {
        // #1623: agents-watchdog hubs the Network group (hub_sections name the group now that subgroups are flattened away).
        assertEquals(listOf("Network"), hub("agents-watchdog").map { it.title })
        // #1737 rule 16: the Agents hub lists the individual items (every old Network page except the removed ADB Shell), plus the NG Firewall hub tile.
        assertEquals(listOf("ng-firewall", "wifi", "bluetooth", "dns", "wg", "firewall", "os-perms", "webserver", "proxy", "apps-access"),
            hub("agents-watchdog")[0].tiles.map { it.id })
    }

    @Test
    fun `H3 pages navigate in-app, apps keep their own declared action`() {
        val by = config.allPages.filter { it.group == "Network" }.associateBy { it.id }
        assertEquals("page:config/ng-firewall", Sections.hubTarget("config", by.getValue("ng-firewall")))
        assertEquals("action:open_os_perms", Sections.hubTarget("config", by.getValue("os-perms")))
        assertEquals("action:open_proxy_settings", Sections.hubTarget("config", by.getValue("proxy")))
        assertEquals("page:config/firewall", Sections.hubTarget("config", by.getValue("firewall")))
        assertEquals("page:config/dns", Sections.hubTarget("config", by.getValue("dns")))
        assertEquals("page:config/bluetooth", Sections.hubTarget("config", by.getValue("bluetooth")))
        assertEquals("action:open_webserver", Sections.hubTarget("config", by.getValue("webserver")))
        assertEquals("action:open_apps_access", Sections.hubTarget("config", by.getValue("apps-access")))
        assertEquals("section:wg", Sections.hubTarget("config", by.getValue("wg")))
    }

    @Test
    fun `H4 Watchdog is the hub fragment and draws its headings and a Cloud Apps tile per entry`() {
        val frag = SectionPages.pagesFor("config", includeHidden = true).first { it.id == "agents-watchdog" }.factory()   // #1943 hidden from Setup, listed by the Launcher hub
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
    fun `H5 Cloud Apps Configs row reads Quick Actions, UI, NG Firewall, Store, Account, C3, Profile, About (1755)`() {
        // #1628 redefined the Cloud > Apps > Configs row (supersedes #1611): Quick Actions, UI | Firewall, WiFi |
        // Store | Account, C3 | Watchdog | About. Watchdog now launches the C3-Watchdog APK (extapp:c3-watchdog)
        // and a Firewall tile (page:config/firewall) is present. The config-section Agents hub page is a separate surface.
        val row = Sections.all().flatMap { it.tileGroups }.single { it.title == "Setup" }.tiles.filter { !it.separator }
        assertEquals(
            listOf("Launcher", "NG Firewall", "Store", "Account", "C3", "Profile", "About"),   // #1929 Quick Actions and UI merged into Launcher (page:config/launcher-hub)
            row.map { it.label })
        // #1755 Watchdog left this row (owner order) and stays visible on the Configs page, Profiling group (rule 16).
        assertTrue("Watchdog launches the C3-Watchdog app from Configs > Profiling", config.pages.any { it.id == "c3-watchdog" && !it.hidden && it.group == "C3" && it.action == "extapp:c3-watchdog" })
        assertTrue("Profile and Account launch the Cloud Account app", row.filter { it.label == "Profile" || it.label == "Account" }.all { it.target == "extapp:cloud-account" })
        assertTrue("NG Firewall opens the hub", row.any { it.label == "NG Firewall" && it.target == "page:config/ng-firewall" })
        // Rule 16: Firewall and Wi-Fi left this row but stay declared and visible (hub + Configs Network group).
        val ids = Sections.all().single { it.id == "config" }.allPages.filter { !it.hidden }.map { it.id }
        assertTrue(ids.containsAll(listOf("firewall", "wifi", "ng-firewall")))
    }

    @Test
    fun `H6 the Launcher hub lists every Launcher icon and the 1755 reorg lost no item`() {
        // The Launcher hub is derived (hub_sections), so it shows exactly the visible pages of the group, one tile each, none dropped.
        // #1943 the Launcher page has two sections: Launcher (no self-link tile) and Configs (what the Setup Configs section held).
        assertEquals(listOf("Launcher", "Configs"), hub("launcher-hub").map { it.title })
        assertEquals(listOf("profile-app", "perms", "agents-watchdog", "logcat", "xray-app", "app-about"), hub("launcher-hub")[1].tiles.map { it.id })
        assertEquals(listOf("Profile", "Perms", "Agents", "Logcat app", "X-Ray App", "About"), hub("launcher-hub")[1].tiles.map { it.label })
        assertEquals("config/launcher-hub", config.pages.first { it.id == "launcher-configs" }.redirect)   // the Configs tile opens the Launcher page (redirect, so its tile target stays unique)
        assertEquals("config" to "launcher-hub", Sections.canonicalPage("config", "launcher-configs"))
        assertEquals(listOf("controls", "panels-ui", "app-folders", "onehand", "notify"), hub("launcher-hub")[0].tiles.map { it.id })
        assertEquals(listOf("Quick Actions", "UI", "Folders", "One-Hand", "Notify"), hub("launcher-hub")[0].tiles.map { it.label })
        assertEquals("page:config/controls", hub("launcher-hub")[0].tiles[0].target)
        // Field parity against the pre-#1755 layout: every visible Configs-page label of that layout is still visible (Logcat is now Logcat app,
        // Tools is Profiling), each in a group; every pre-#1755 Cloud Apps Configs row item is still on the row or on the Configs page (Watchdog).
        val visible = config.allPages.filter { (!it.hidden || it.id in config.pages.flatMap { p -> p.hubPages }) && !it.separator && !it.isAction }.map { it.label }.toSet()   // #1943 incl. the Configs pages the Launcher hub lists
        val before = listOf("Quick Actions", "UI", "Folders", "One-Hand", "Notify", "NG Firewall", "Wi-Fi", "Bluetooth", "DNS", "VPN", "Firewall", "OS Perms",
            "Webserver", "Proxy", "Store", "Cloudy C3", "Peer Control", "Watchdog", "X-Ray Device", "Logcat Fleet", "Agents", "Account", "Perms", "Logcat app",
            "X-Ray App", "About")
        assertTrue("lost: ${before - visible}", visible.containsAll(before))
        val row = Sections.all().flatMap { it.tileGroups }.single { it.title == "Setup" }.tiles.filter { !it.separator }.map { it.label }.toSet()
        for (old in listOf("Quick Actions", "UI", "NG Firewall", "Store", "C3", "Watchdog", "Account", "About"))
            assertTrue("$old is neither on the row nor on the Configs page", old in row || old in visible)
    }
}
