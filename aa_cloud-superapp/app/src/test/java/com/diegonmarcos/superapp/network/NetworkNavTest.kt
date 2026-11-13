package com.diegonmarcos.superapp.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1719 Configs > Network and its bottom nav.
 *   N1 the bar is Wireless | DNS | VPN | Home | NG Firewall | OS Perms | Webserver, Home the default, 2..8 items
 *   N2 every item and grid icon has a target, the link items open the right place, tab: targets name page tabs
 *   N3 parity (rule 16): every page the Network group held is still declared (hidden), reached from Home / the bar; the only
 *      icon gone is ADB Shell (explicit owner removal), and its page id still resolves
 *   N4 renames: Cloud Mesh -> VPN, Firewall -> NG Firewall (page and Configs row tile), in build.json
 *   N5 NG Firewall takes over the screen (owns toolbar and bottom nav), has its own nav and a Back button, and each view is the engine's screen
 *   N6 the nav is drawn by libs:bottomnav's island with no styling argument (nav-shape N7), the page is routed from SectionPages
 */
class NetworkNavTest {

    private val app = File(System.getProperty("user.dir"))
    private val build = JSONObject(File(app, "build.json").readText())
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

    @Test fun `N1 the bar is the owner's order and Home is the default`() {
        assertEquals(listOf("Wireless", "DNS", "VPN", "Home", "NG Firewall", "OS Perms", "Webserver"), NetworkNav.TABS.map { it.second })
        assertEquals(listOf("wireless", "dns", "vpn", "home", "firewall", "perms", "webserver"), NetworkNav.TAB_IDS)
        assertEquals("home", NetworkNav.DEFAULT)
        assertTrue(NetworkNav.TAB_IDS.size in 2..8)
        assertEquals(NetworkNav.TAB_IDS.toSet(), NetworkNav.TAB_ICONS.keys)
        assertEquals(setOf("wireless", "dns", "vpn", "home"), NetworkNav.PAGE_TABS)
    }

    @Test fun `N2 targets - links open the firewall, Store Access and the Webserver, tabs stay in the page`() {
        assertEquals("page:config/firewall", NetworkNav.linkTarget("firewall"))
        assertEquals("action:open_apps_access", NetworkNav.linkTarget("perms"))
        assertEquals("action:open_webserver", NetworkNav.linkTarget("webserver"))
        for (t in NetworkNav.PAGE_TABS) assertNull(t, NetworkNav.linkTarget(t))
        assertEquals(listOf("Wi-Fi", "Bluetooth"), NetworkNav.WIRELESS_ITEMS.map { it.label })
        assertEquals(listOf("page:config/wifi", "page:config/bluetooth"), NetworkNav.WIRELESS_ITEMS.map { it.target })
        for (i in NetworkNav.HOME_ITEMS) {
            assertTrue(i.id, i.target.startsWith("page:") || i.target.startsWith("action:") || i.target.startsWith(NetworkNav.TAB_PREFIX))
            if (i.target.startsWith(NetworkNav.TAB_PREFIX)) assertTrue(i.id, i.target.removePrefix(NetworkNav.TAB_PREFIX) in NetworkNav.PAGE_TABS)
        }
    }

    @Test fun `N3 parity - every previous Network page is still declared and reachable, only the ADB Shell icon is gone`() {
        val all = pages().filter { it.optString("group") == "Network" }.associateBy { it.getString("id") }
        for (id in NetworkNav.PREVIOUS_PAGES.keys + NetworkNav.REMOVED_ICONS) assertTrue("$id is still declared", id in all)
        // the visible Network group is the one Network page; everything else is hidden behind it
        assertEquals(listOf("network"), all.filterValues { !it.optBoolean("hidden") }.keys.toList())
        val reaches = all.getValue("network").getJSONArray("reaches").let { a -> (0 until a.length()).map { a.getString(it) } }
        for (id in NetworkNav.PREVIOUS_PAGES.keys + NetworkNav.REMOVED_ICONS) assertTrue("$id is reached from Network", id in reaches)
        // the Home grid holds an icon for every previous page except the removed ADB Shell
        val homeIds = NetworkNav.HOME_ITEMS.map { it.id }.toSet()
        assertEquals(setOf("wifi", "bluetooth", "dns", "vpn", "firewall", "perms", "webserver"), homeIds)
        assertFalse("no ADB Shell icon", NetworkNav.HOME_ITEMS.any { it.id.contains("adb") } || NetworkNav.TAB_IDS.any { it.contains("adb") })
        assertEquals(setOf("adb-shell"), NetworkNav.REMOVED_ICONS)
        // wifi and bluetooth keep every tab
        assertEquals(5, all.getValue("wifi").getJSONArray("tabs").length())
        assertEquals(7, all.getValue("bluetooth").getJSONArray("tabs").length())
        assertEquals("action:open_apps_access", all.getValue("apps-access").getString("action"))
        assertEquals("action:open_webserver", all.getValue("webserver").getString("action"))
    }

    @Test fun `N4 renames - Cloud Mesh is VPN, Firewall is NG Firewall`() {
        val all = pages().associateBy { it.getString("id") }
        assertEquals("VPN", all.getValue("wg").getString("label"))
        assertEquals("NG Firewall", all.getValue("firewall").getString("label"))
        assertTrue(pages().none { it.optString("label") == "Cloud Mesh" || it.optString("label") == "Firewall" })
        assertEquals("section:wg", all.getValue("wg").getString("action"))   // the id and the route are unchanged
    }

    @Test fun `N5 NG Firewall owns the whole screen with its own nav and Back`() {
        val s = src("network/NgFirewallFragment.kt")
        assertTrue(s.contains("override fun ownsToolbar(): Boolean = true"))
        assertTrue(s.contains("override fun ownsBottomNav(): Boolean = true"))
        assertTrue(s.contains("TAG_BACK") && s.contains("close()"))
        assertTrue(s.contains("FirewallDialog.newInstance(tab).apply { showsDialog = false }"))
        assertEquals(listOf("Status", "Apps", "Rules"), NgFirewallNav.TABS.map { it.second })
        assertEquals(NgFirewallNav.TABS.map { it.first }.toSet(), NgFirewallNav.ICONS.keys)
        val dialog = File(app, "../ab_cloud-libs-shared/libs/firewall/src/main/java/com/diegonmarcos/superapp/firewall/FirewallDialog.kt").readText()
        for (sec in listOf("SECTION_ALL", "SECTION_STATUS", "SECTION_APPS", "SECTION_RULES")) assertTrue(sec, dialog.contains("const val $sec"))
        // the default view is the whole previous screen
        assertTrue(dialog.contains("?: SECTION_ALL"))
    }

    @Test fun `N6 the nav is the fleet island with no styling argument and the page is routed`() {
        for (f in listOf("network/NetworkNavFragment.kt", "network/NgFirewallFragment.kt")) {
            val s = src(f)
            assertTrue(f, s.contains("BottomNavIslandView(c)"))
            for (bad in listOf("TabRow", "NavigationBar", "BottomNavigationView", "TabLayout", "colorScheme", "iconSize")) assertFalse("$f $bad", s.contains(bad))
        }
        assertTrue(src("network/NetworkNavFragment.kt").contains("override fun ownsBottomNav(): Boolean = true"))
        val sp = src("launcher/SectionPages.kt")
        assertTrue(sp.contains("pageId == \"network\"") && sp.contains("NetworkNavFragment.newInstance()"))
        assertTrue(sp.contains("NgFirewallFragment.newInstance()"))
    }

    @Test fun `N7 the Configs row tile is renamed with its target intact`() {
        assertTrue(File(app, "build.json").readText().contains("\"id\": \"configs-firewall\",\n                \"label\": \"NG Firewall\",\n                \"icon\": \"ic_lock\",\n                \"target\": \"page:config/firewall\""))
    }
}
