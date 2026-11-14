package com.diegonmarcos.superapp.network

/**
 * #1737 The NG Firewall hub: ONE tile of the Configs > Network group that opens a page listing the network items as icons
 * under three headings ([SECTIONS]). It replaces the #1719 unified Network tile and its bottom nav: every item here is also
 * its own tile in the Network group on the Configs main page (build.json::ui.sections[config] group Network), so nothing is
 * wrapped behind the hub. Pure Kotlin (no Android types): the order, the targets and the "nothing was lost" claim are
 * testable on the JVM. Drawn by [NgFirewallHubFragment].
 *
 * Network group on the Configs page, in order: NG Firewall (this hub) | Wi-Fi, Bluetooth, DNS, VPN | Firewall, OS Perms | Webserver,
 * Proxy. Bluetooth sits right after Wi-Fi (the owner groups them as Wireless).
 */
object NgFirewallHub {
    /** Page id of the hub in build.json::ui.sections[config]. */
    const val PAGE_ID = "ng-firewall"

    /** Target grammar of the shell's one dispatcher (onTileClicked). */
    const val TARGET_WIFI = "page:config/wifi"
    const val TARGET_BLUETOOTH = "page:config/bluetooth"
    const val TARGET_DNS = "page:config/dns"
    const val TARGET_VPN = "section:wg"
    const val TARGET_FIREWALL = "page:config/firewall"
    /** Cloud Store > Access > Android Perms (the Store's EXTRA_PAGE deep link, #1719). */
    const val TARGET_PERMS = "action:open_os_perms"
    const val TARGET_WEBSERVER = "action:open_webserver"
    /** No proxy page exists in the SuperApp: the system network settings (#1737). */
    const val TARGET_PROXY = "action:open_proxy_settings"

    /** One icon of the hub: [icon] is a drawable name (resolved by the host), [target] what a tap does, [page] its build.json page id. */
    data class Item(val id: String, val label: String, val icon: String, val target: String, val page: String)

    data class Section(val title: String, val items: List<Item>)

    val WIFI = Item("wifi", "Wi-Fi", "ic_wifi", TARGET_WIFI, "wifi")
    val BLUETOOTH = Item("bluetooth", "Bluetooth", "ic_bluetooth", TARGET_BLUETOOTH, "bluetooth")
    val DNS = Item("dns", "DNS", "ic_world", TARGET_DNS, "dns")
    val VPN = Item("vpn", "VPN", "ic_wg", TARGET_VPN, "wg")
    val FIREWALL = Item("firewall", "Firewall", "ic_lock", TARGET_FIREWALL, "firewall")
    val PERMS = Item("perms", "OS Perms", "ic_act_key", TARGET_PERMS, "os-perms")
    val WEBSERVER = Item("webserver", "Webserver", "ic_web", TARGET_WEBSERVER, "webserver")
    val PROXY = Item("proxy", "Proxy", "ic_ctl_mobile_data", TARGET_PROXY, "proxy")

    /** The hub's three headings and their icons, in the owner's order. */
    val SECTIONS: List<Section> = listOf(
        Section("Connections", listOf(WIFI, BLUETOOTH, DNS, VPN)),
        Section("Firewalls", listOf(FIREWALL, PERMS)),
        Section("Servers", listOf(WEBSERVER, PROXY)),
    )

    val ITEMS: List<Item> = SECTIONS.flatMap { it.items }

    /** The visible pages of the Configs > Network group, in order (`|` = a separator page), as declared in build.json. */
    val CONFIGS_NETWORK_ORDER: List<String> = listOf(
        "ng-firewall", "|", "wifi", "bluetooth", "dns", "wg", "|", "firewall", "os-perms", "|", "webserver", "proxy",
    )

    /**
     * Rule 16: every page the Network group held before #1719 and where it is reached now. The one icon gone is the explicit
     * owner removal of ADB Shell (#1719; the page lives in Cloud C3 Webserver, its id still resolves).
     */
    val PREVIOUS_PAGES: Map<String, String> = mapOf(
        "firewall" to "Firewall tile on the Configs page and in the hub",
        "dns" to "DNS tile on the Configs page and in the hub",
        "webserver" to "Webserver tile on the Configs page and in the hub",
        "wg" to "VPN tile (formerly Cloud Mesh) on the Configs page and in the hub",
        "apps-access" to "OS Perms tile (Cloud Store > Access > Android Perms); the page stays declared, hidden",
        "wifi" to "Wi-Fi tile on the Configs page and in the hub",
        "bluetooth" to "Bluetooth tile on the Configs page and in the hub",
    )

    /** The page ids that left the grid on purpose (explicit owner removal #1719): their routes still resolve. */
    val REMOVED_ICONS: Set<String> = setOf("adb-shell")
}

/**
 * #1719 NG Firewall's own navigation (the Firewall page, #1737 renamed back from "NG Firewall"): full-screen, replacing the
 * SuperApp's bottom nav (it is a ShellOverride that owns the bottom nav) with its own three views; the shell's Back arrow
 * closes it and restores the shell chrome.
 */
object NgFirewallNav {
    const val STATUS = "status"; const val APPS = "apps"; const val RULES = "rules"
    val TABS: List<Pair<String, String>> = listOf(STATUS to "Status", APPS to "Apps", RULES to "Rules")
    const val DEFAULT = STATUS
    val ICONS: Map<String, String> = mapOf(STATUS to "ic_lock", APPS to "ic_apps", RULES to "ic_settings")
}
