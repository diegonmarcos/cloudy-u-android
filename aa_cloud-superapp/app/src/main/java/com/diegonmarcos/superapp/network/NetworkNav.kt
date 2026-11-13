package com.diegonmarcos.superapp.network

/**
 * #1719 Configs > Network: the ONE declaration of its bottom nav and of every item the section holds, drawn by
 * [NetworkNavFragment] (libs:bottomnav's island) and read by the parity tests. Pure Kotlin (no Android types), so the
 * order, the targets and the "nothing was lost" claim are all testable on the JVM.
 *
 * The bar, in order: Wireless, DNS, VPN, Home, NG Firewall, OS Perms, Webserver. Home (the icon grid of every Network
 * item) is the default tab. DNS and VPN are pages hosted in the tab. NG Firewall, OS Perms and Webserver are links: NG
 * Firewall takes over the whole screen ([NgFirewallNav]); the other two open another fleet app.
 */
object NetworkNav {
    const val WIRELESS = "wireless"; const val DNS = "dns"; const val VPN = "vpn"; const val HOME = "home"
    const val FIREWALL = "firewall"; const val PERMS = "perms"; const val WEBSERVER = "webserver"

    /** The bar's items in order: id and label. */
    val TABS: List<Pair<String, String>> = listOf(
        WIRELESS to "Wireless", DNS to "DNS", VPN to "VPN", HOME to "Home",
        FIREWALL to "NG Firewall", PERMS to "OS Perms", WEBSERVER to "Webserver",
    )
    val TAB_IDS: List<String> = TABS.map { it.first }
    const val DEFAULT = HOME

    /** Tabs that show a page inside the Network page; the rest are links that open elsewhere and never become the selected tab. */
    val PAGE_TABS: Set<String> = setOf(WIRELESS, DNS, VPN, HOME)

    /** Target grammar of the shell's one dispatcher (onTileClicked), plus `tab:<id>` which this page answers itself. */
    const val TAB_PREFIX = "tab:"
    fun tabTarget(id: String) = TAB_PREFIX + id
    const val TARGET_WIFI = "page:config/wifi"
    const val TARGET_BLUETOOTH = "page:config/bluetooth"
    const val TARGET_FIREWALL = "page:config/firewall"
    /** Cloud Store > Access (the Store's own Access section; its Android Perms part has no deep link of its own yet). */
    const val TARGET_PERMS = "action:open_apps_access"
    const val TARGET_WEBSERVER = "action:open_webserver"

    /** One icon of a grid: [icon] is a drawable name (resolved by the host), [target] what a tap does. */
    data class Item(val id: String, val label: String, val icon: String, val target: String)

    val WIFI = Item("wifi", "Wi-Fi", "ic_wifi", TARGET_WIFI)
    val BLUETOOTH = Item("bluetooth", "Bluetooth", "ic_bluetooth", TARGET_BLUETOOTH)

    /** The Wireless tab: Wi-Fi and Bluetooth, each opening its existing page (all their tabs intact). */
    val WIRELESS_ITEMS: List<Item> = listOf(WIFI, BLUETOOTH)

    /** The Home tab: every Network item as one icon grid. */
    val HOME_ITEMS: List<Item> = listOf(
        WIFI, BLUETOOTH,
        Item("dns", "DNS", "ic_world", tabTarget(DNS)),
        Item("vpn", "VPN", "ic_wg", tabTarget(VPN)),
        Item("firewall", "NG Firewall", "ic_lock", TARGET_FIREWALL),
        Item("perms", "OS Perms", "ic_key", TARGET_PERMS),
        Item("webserver", "Webserver", "ic_web", TARGET_WEBSERVER),
    )

    /** The icon of each bar item (a drawable name). */
    val TAB_ICONS: Map<String, String> = mapOf(
        WIRELESS to "ic_wifi", DNS to "ic_world", VPN to "ic_wg", HOME to "ic_home",
        FIREWALL to "ic_lock", PERMS to "ic_key", WEBSERVER to "ic_web",
    )

    /** What a bar item does when tapped: select the tab, or open its target (the link tabs). */
    fun linkTarget(tab: String): String? = when (tab) {
        FIREWALL -> TARGET_FIREWALL
        PERMS -> TARGET_PERMS
        WEBSERVER -> TARGET_WEBSERVER
        else -> null
    }

    /**
     * Rule 16: every page the Network section held before #1719 (build.json::ui.sections[config] group Network) and where it
     * is reached now. The one entry that is gone is the explicit owner removal: ADB Shell icon (the page lives in Cloud C3
     * Webserver; the Webserver link covers it). Read by NetworkNavParityTest and the shell parity test.
     */
    val PREVIOUS_PAGES: Map<String, String> = mapOf(
        "firewall" to "NG Firewall bar item and Home icon",
        "dns" to "DNS bar item (the page itself) and Home icon",
        "webserver" to "Webserver bar item and Home icon",
        "wg" to "VPN bar item (the page itself, formerly Cloud Mesh) and Home icon",
        "apps-access" to "OS Perms bar item and Home icon (Cloud Store > Access)",
        "wifi" to "Wireless tab icon and Home icon",
        "bluetooth" to "Wireless tab icon and Home icon",
    )

    /** The page ids that left the grid on purpose (explicit owner removal #1719): their routes still resolve. */
    val REMOVED_ICONS: Set<String> = setOf("adb-shell")
}

/**
 * #1719 NG Firewall's own navigation: full-screen, replacing the SuperApp's bottom nav (it is a ShellOverride that owns the
 * toolbar and the bottom nav) with its own three views and a Back button that closes it and restores the shell chrome.
 */
object NgFirewallNav {
    const val STATUS = "status"; const val APPS = "apps"; const val RULES = "rules"
    val TABS: List<Pair<String, String>> = listOf(STATUS to "Status", APPS to "Apps", RULES to "Rules")
    const val DEFAULT = STATUS
    val ICONS: Map<String, String> = mapOf(STATUS to "ic_lock", APPS to "ic_apps", RULES to "ic_settings")
}
