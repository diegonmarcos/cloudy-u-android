package com.diegonmarcos.superapp.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1737 (corrects #1719) Configs > Network: individual icons on the Configs main page, plus the NG Firewall hub.
 *   N1 the hub lists Connections (Wi-Fi, DNS, VPN), Firewalls (Firewall, OS Perms), Servers (Webserver, Proxy), in that order
 *   N2 every hub item has a dispatchable target that matches the page it stands for; OS Perms = Store Access > Android Perms
 *   N3 parity (rule 16): every page the Network group ever held is still declared; the visible group is in the owner's order with `|`
 *      separators and no unified Network page; the only icon gone is ADB Shell (explicit owner removal #1719), its id still resolves
 *   N4 renames: Cloud Mesh stays VPN, the firewall is "Firewall" again (page and Configs row tile), the hub is "NG Firewall"
 *   N5 the Firewall page keeps its own full-screen nav (Status | Apps | Rules) and the one Back control (the shell arrow)
 *   N6 the Firewall nav is the fleet island with no styling argument; the hub and the firewall are routed from SectionPages; the
 *      unified Network page and its bottom-nav wrapper are gone
 *   N7 the Proxy item: no proxy page exists in the app, so it opens the system network settings through one declared action
 */
class NgFirewallHubTest {

    /** user.dir is the app MODULE (aa_cloud-superapp/app); build.json sits one level up. */
    private val app = File(System.getProperty("user.dir"))
    private val project = app.parentFile
    private val buildText = File(project, "build.json").readText()
    private val build = JSONObject(buildText)
    private val kt = File(app, "src/main/java/com/diegonmarcos/superapp")
    private fun src(rel: String) = File(kt, rel).readText().replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)(?<=\\s|^)//.*$"), "")

    private fun pages(): List<JSONObject> {
        val sections = build.getJSONObject("ui").getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val s = sections.getJSONObject(i)
            if (s.getString("id") == "config") {
                val ps = s.getJSONArray("pages")
                return (0 until ps.length()).map { ps.getJSONObject(it) }
            }
        }
        error("no config section")
    }

    @Test fun `N1 the hub has the owner's three headings and icons in order`() {
        assertEquals(listOf("Connections", "Firewalls", "Servers"), NgFirewallHub.SECTIONS.map { it.title })
        assertEquals(listOf(listOf("Wi-Fi", "Bluetooth", "DNS", "VPN"), listOf("Firewall", "OS Perms"), listOf("Webserver", "Proxy")),
            NgFirewallHub.SECTIONS.map { s -> s.items.map { it.label } })
        assertEquals(8, NgFirewallHub.ITEMS.size)
        assertEquals(NgFirewallHub.ITEMS.size, NgFirewallHub.ITEMS.map { it.id }.toSet().size)
    }

    @Test fun `N2 targets - each hub icon opens what its Configs tile opens`() {
        val by = pages().associateBy { it.getString("id") }
        for (i in NgFirewallHub.ITEMS) {
            assertTrue(i.id, i.target.startsWith("page:config/") || i.target.startsWith("action:") || i.target.startsWith("section:"))
            val page = by.getValue(i.page)
            val own = page.optString("action").ifBlank { "page:config/${i.page}" }
            assertEquals("${i.id} opens what its Configs tile opens", own, i.target)
            assertEquals(i.label, page.getString("label"))
        }
        assertEquals("action:open_os_perms", NgFirewallHub.PERMS.target)
        assertEquals("action:open_webserver", NgFirewallHub.WEBSERVER.target)
        assertTrue(src("ShellActivity.kt").contains("CloudStoreHandoff.open(this, \"access\", \"android\")"))
        val store = File(project, "../ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt")
        if (store.exists()) assertTrue(store.readText().contains("EXTRA_PAGE") && store.readText().contains("TAB_ACCESS"))
    }

    @Test fun `N3 parity - the Network group is individual icons in the owner's order, nothing lost but the ADB Shell icon`() {
        val all = pages().filter { it.optString("group") == "Network" }
        val byId = all.associateBy { it.getString("id") }
        for (id in NgFirewallHub.PREVIOUS_PAGES.keys + NgFirewallHub.REMOVED_ICONS) assertTrue("$id is still declared", id in byId || pages().any { it.getString("id") == id })
        assertFalse("no unified Network page", pages().any { it.getString("id") == "network" })
        val visible = all.filter { !it.optBoolean("hidden") }.map { if (it.optBoolean("separator")) "|" else it.getString("id") }
        assertEquals(NgFirewallHub.CONFIGS_NETWORK_ORDER, visible)
        assertEquals(listOf("NG Firewall", "|", "Wi-Fi", "Bluetooth", "DNS", "VPN", "|", "Firewall", "OS Perms", "|", "Webserver", "Proxy"),
            all.filter { !it.optBoolean("hidden") }.map { it.getString("label") })
        // every previous page reaches a tile: its own, or (Apps Access) the OS Perms tile and the Agents hub listing
        for (id in listOf("firewall", "dns", "webserver", "wg", "wifi", "bluetooth")) assertFalse("$id is visible", byId.getValue(id).optBoolean("hidden"))
        assertTrue(byId.getValue("apps-access").optBoolean("hidden"))
        assertEquals("apps-access", byId.getValue("ng-firewall").getJSONArray("hub_pages").getString(0))
        // no ADB Shell icon; the page id still resolves, hidden, with its referrer
        assertTrue(byId.getValue("adb-shell").optBoolean("hidden"))
        assertFalse("no ADB Shell icon in the hub", NgFirewallHub.ITEMS.any { it.id.contains("adb") })
        assertEquals(setOf("adb-shell"), NgFirewallHub.REMOVED_ICONS)
        assertEquals(setOf("adb-shell", "apps-access"), byId.getValue("ng-firewall").getJSONArray("reaches").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
        // wifi and bluetooth keep every tab
        assertEquals(5, byId.getValue("wifi").getJSONArray("tabs").length())
        assertEquals(7, byId.getValue("bluetooth").getJSONArray("tabs").length())
        assertEquals("action:open_apps_access", byId.getValue("apps-access").getString("action"))
        assertEquals("action:open_webserver", byId.getValue("webserver").getString("action"))
    }

    @Test fun `N4 names - VPN, Firewall and the NG Firewall hub`() {
        val all = pages().associateBy { it.getString("id") }
        assertEquals("VPN", all.getValue("wg").getString("label"))
        assertEquals("section:wg", all.getValue("wg").getString("action"))   // the id and the route are unchanged
        assertEquals("Firewall", all.getValue("firewall").getString("label"))
        assertEquals("NG Firewall", all.getValue("ng-firewall").getString("label"))
        assertTrue(pages().none { it.optString("label") == "Cloud Mesh" })
        assertTrue(buildText.contains("\"id\": \"configs-firewall\",\n                \"label\": \"Firewall\",\n                \"icon\": \"ic_lock\",\n                \"target\": \"page:config/firewall\""))
    }

    @Test fun `N5 the Firewall page owns its screen with its own nav and one Back`() {
        val s = src("network/NgFirewallFragment.kt")
        assertFalse("the shell's top row stays (PageTopInsetTest T2)", s.contains("ownsToolbar"))
        assertTrue(s.contains("override fun ownsBottomNav(): Boolean = true"))
        assertTrue(s.contains("BackHandler") && s.contains("override fun tryHandleBack()") && s.contains("close()"))
        assertFalse("no second Back control inside the Firewall", s.contains("TAG_BACK") || s.contains("\u2039 Back"))
        assertTrue(s.contains("FirewallDialog.newInstance(tab).apply { showsDialog = false }"))
        assertEquals(listOf("Status", "Apps", "Rules"), NgFirewallNav.TABS.map { it.second })
        assertEquals(NgFirewallNav.TABS.map { it.first }.toSet(), NgFirewallNav.ICONS.keys)
        val dialog = File(project, "../ab_cloud-libs-shared/libs/firewall/src/main/java/com/diegonmarcos/superapp/firewall/FirewallDialog.kt").readText()
        for (sec in listOf("SECTION_ALL", "SECTION_STATUS", "SECTION_APPS", "SECTION_RULES")) assertTrue(sec, dialog.contains("const val $sec"))
        assertTrue(dialog.contains("?: SECTION_ALL"))   // the default view is the whole previous screen
    }

    @Test fun `N6 routing - the Firewall nav is the fleet island, the hub and the firewall are routed, the Network wrapper is gone`() {
        val s = src("network/NgFirewallFragment.kt")
        assertTrue(s.contains("BottomNavIslandView(c)"))
        for (bad in listOf("TabRow", "NavigationBar", "BottomNavigationView", "TabLayout", "colorScheme", "iconSize")) assertFalse(bad, s.contains(bad))
        assertFalse("the hub is a plain page: the shell's nav stays", src("network/NgFirewallHubFragment.kt").contains("ShellOverride"))
        val sp = src("launcher/SectionPages.kt")
        assertTrue(sp.contains("pageId == \"ng-firewall\"") && sp.contains("NgFirewallHubFragment.newInstance()"))
        assertTrue(sp.contains("NgFirewallFragment.newInstance()"))
        assertFalse(sp.contains("NetworkNavFragment"))
        assertFalse(File(kt, "network/NetworkNavFragment.kt").exists() || File(kt, "network/NetworkNav.kt").exists())
    }

    @Test fun `N7 Proxy opens the system network settings through one declared action`() {
        assertEquals("action:open_proxy_settings", NgFirewallHub.TARGET_PROXY)
        val shell = src("ShellActivity.kt")
        assertTrue(shell.contains("actionType == \"open_proxy_settings\""))
        assertTrue(shell.contains("ACTION_WIRELESS_SETTINGS") && shell.contains("ACTION_SETTINGS"))
        val proxy = pages().first { it.getString("id") == "proxy" }
        assertEquals("action:open_proxy_settings", proxy.getString("action"))
        // the app declares no page of its own for a proxy: the only `proxy` page id is this tile
        assertEquals(1, pages().count { it.getString("id").contains("proxy") })
    }
}
