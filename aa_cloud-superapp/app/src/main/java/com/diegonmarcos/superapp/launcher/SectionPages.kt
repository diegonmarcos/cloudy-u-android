package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.rss.RssFeedFragment
import com.diegonmarcos.superapp.settings.LauncherConfigFragment
import com.diegonmarcos.superapp.settings.LauncherPresetsFragment
import com.diegonmarcos.superapp.c3.C3HealthFragment
import com.diegonmarcos.superapp.cal.CalendarMonthFragment
import com.diegonmarcos.superapp.cal.CalendarAgendaFragment
import com.diegonmarcos.superapp.network.WireGuardFragment
import com.diegonmarcos.superapp.recentapps.RecentAppsFragment

import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.chat.ChatPages
import com.diegonmarcos.superapp.mail.MailPages
import com.diegonmarcos.superapp.ops.OpsPages
// libs:wallet MOVED to ac_cloud-wallet (constellation APK). No WalletFragment here.

/**
 * Per-section page registry — now driven by [Sections] (which mirrors
 * `build.json::ui.sections[].pages[]`). The Kotlin side only owns the
 * **factory mapping** from a section's `pageId` to the actual Fragment
 * subclass; the *list* of pages and their order come from build.json.
 *
 * Adding a new section's pages:
 *   1. `pages: [{id, label, upstream}]` in build.json::ui.sections[X].
 *   2. Implement page Fragments in libs:<x>/.
 *   3. Add a `when` branch in [factoryFor] below for the new section id.
 */
object SectionPages {

    data class Page(val id: String, val label: String, val iconName: String = "", val action: String = "", val isAction: Boolean = false, val factory: () -> Fragment)

    /** The section's pages as listed to the user. Pass [includeHidden] when
     *  RESOLVING a target rather than listing children: a hidden page (Labs'
     *  c3, quant, …) still has to answer to `page:cloud/quant`. */
    fun pagesFor(sectionId: String, includeHidden: Boolean = false): List<Page> {
        val section = Sections.byId(sectionId) ?: return emptyList()
        return (if (includeHidden) section.allPages else section.pages).map { p ->
            Page(p.id, p.label, p.iconName ?: "", p.action, p.isAction, factory = {
                factoryFor(sectionId, p.id, p.label, p.url, p.tabs, p.hubSections)
            })
        }
    }

    private fun factoryFor(
        sectionId: String,
        pageId: String,
        label: String,
        url: String = "",
        tabs: List<String> = emptyList(),
        hubSections: List<String> = emptyList(),
    ): Fragment = when {
        // A page that declares `tabs` IS a strip over them — the same
        // SectionTabsFragment a tabbed SECTION wears, so this app has one tab
        // mechanism rather than two to keep in step. Checked before `url` and
        // before every id below, so grouping two pages behind one is a
        // build.json edit here too, not a branch.
        tabs.isNotEmpty() -> SectionTabsFragment.forPage(sectionId, pageId)
        // A page that declares a `url` IS that page. Checked first and by
        // data, so embedding the next one is a build.json edit, not a branch.
        url.isNotBlank() -> WebPageFragment.newInstance(url)
        // #1545 #1548 A page that declares `hub_sections` IS the titled icon grid over those subgroups (Configs ▸ Agents ▸
        // Watchdog), by data like `tabs` and `url`, so another hub is a build.json edit, not a branch.
        hubSections.isNotEmpty() -> SubgroupHubFragment.newInstance(sectionId, pageId)
        sectionId == "mail"  -> MailPages.fragmentFor(pageId)
        // Phone ▸ Apps: installed Android apps, not build.json tile data — so
        // it is an ordinary page here, unlike its `facet: true` sibling Configs.
        sectionId == "phone" && pageId == "apps" ->
            com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment.newInstance()
        sectionId == "chat"  -> ChatPages.fragmentFor(pageId)
        sectionId == "c3"    && pageId == "health"      -> C3HealthFragment.newInstance()
        sectionId == "c3"    && pageId == "dagu"        -> OpsPages.fragmentForDagu()
        // #877 the hidden wg/status page is the Cloud Mesh page opened on its Status tab.
        sectionId == "wg"    && pageId == "status"      -> WireGuardFragment.newInstance("status")
        sectionId == "feed"  && pageId == "all"         -> RssFeedFragment.newInstance()
        // #867 Account is its own app: a button that opens Cloud Account once it is installed, else the page itself.
        sectionId == "config" && pageId == "profile"   -> com.diegonmarcos.superapp.apps.AccountHandoff.page()
        // Launcher's three tabs. The `launcher` id names the strip
        // (build.json::ui.sections[config].pages[launcher].tabs), so each screen
        // needed an id of its own — nothing any of them stores moved with the
        // split, LauncherProfilePrefs / LauncherThemePrefs / LauncherModePrefs and
        // friends key off fixed store names, never off a page id.
        //
        // `presets`, not `profile`: `profile` is already this section's own
        // top-level page (the owner's identity — name, email, WireGuard export)
        // and one section cannot hold two pages under one id.
        //
        // `controls` (#574) is the ONE producer of the switch board: the old
        // Panel ▸ Control grid is hosted inside it as a child fragment, so there
        // is no `control` branch below any more.
        sectionId == "config" && pageId == "presets" -> LauncherPresetsFragment.newInstance()
        // Configs ▸ Setup ▸ Launcher ▸ Controls is a strip over Presets | Panels (`controls` declares
        // `tabs`, so the strip branch at the top answered it); Panels is a strip over UI | Tools,
        // two scopes of the ONE switch-board producer.
        sectionId == "config" && pageId == "panels-all" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_ALL)
        sectionId == "config" && pageId == "panels-ui" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI)
        sectionId == "config" && pageId == "panels-battery" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_BATTERY)
        sectionId == "config" && pageId == "panels-tools" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_TOOLS)
        sectionId == "config" && pageId == "kde"            -> com.diegonmarcos.superapp.kdeconnect.KdeConnectFragment.newInstance()
        // Configs ▸ Fleet ▸ Bluetooth (#1221): the `bluetooth` page declares `tabs`, so the strip branch at the top answered it;
        // these are its five tabs, one fragment over libs:bluetooth's page and the one shared stack.
        sectionId == "config" && pageId == "bluetooth-find" -> com.diegonmarcos.superapp.configs.BluetoothFragment.newInstance(com.diegonmarcos.superapp.bluetooth.BtTab.Find)
        sectionId == "config" && pageId == "bluetooth-analyze" -> com.diegonmarcos.superapp.configs.BluetoothFragment.newInstance(com.diegonmarcos.superapp.bluetooth.BtTab.Analyze)
        sectionId == "config" && pageId == "bluetooth-locate" -> com.diegonmarcos.superapp.configs.BluetoothFragment.newInstance(com.diegonmarcos.superapp.bluetooth.BtTab.Locate)
        sectionId == "config" && pageId == "bluetooth-connect" -> com.diegonmarcos.superapp.configs.BluetoothFragment.newInstance(com.diegonmarcos.superapp.bluetooth.BtTab.Connect)
        sectionId == "config" && pageId == "bluetooth-interact" -> com.diegonmarcos.superapp.configs.BluetoothFragment.newInstance(com.diegonmarcos.superapp.bluetooth.BtTab.Interact)
        sectionId == "config" && pageId == "bluetooth-debug" -> com.diegonmarcos.superapp.configs.BluetoothFragment.newInstance(com.diegonmarcos.superapp.bluetooth.BtTab.Debug)
        sectionId == "config" && pageId == "bluetooth-dev" -> com.diegonmarcos.superapp.configs.BluetoothFragment.newInstance(com.diegonmarcos.superapp.bluetooth.BtTab.Dev)
        // Configs ▸ Setup ▸ Network ▸ WiFi (#1263): the `wifi` page declares `tabs`, so the strip branch at the top answered it;
        // these are its five tabs, one fragment over libs:wifi's page and the one shared controller.
        sectionId == "config" && pageId == "wifi-scan" -> com.diegonmarcos.superapp.configs.WifiFragment.newInstance(com.diegonmarcos.superapp.wifi.WifiTab.Scan)
        sectionId == "config" && pageId == "wifi-channels" -> com.diegonmarcos.superapp.configs.WifiFragment.newInstance(com.diegonmarcos.superapp.wifi.WifiTab.Channels)
        sectionId == "config" && pageId == "wifi-link" -> com.diegonmarcos.superapp.configs.WifiFragment.newInstance(com.diegonmarcos.superapp.wifi.WifiTab.Link)
        sectionId == "config" && pageId == "wifi-tools" -> com.diegonmarcos.superapp.configs.WifiFragment.newInstance(com.diegonmarcos.superapp.wifi.WifiTab.Tools)
        sectionId == "config" && pageId == "wifi-log" -> com.diegonmarcos.superapp.configs.WifiFragment.newInstance(com.diegonmarcos.superapp.wifi.WifiTab.Log)
        // Store's two tabs (#563). The `store` page itself declares `tabs`, so
        // the strip branch at the top already answered it.
        // #865 each Store page is wrapped: once Cloud Store is installed it shows
        // a button that opens Cloud Store on the same tab, else the page itself.
        sectionId == "config" && pageId == "store-cloud"    -> com.diegonmarcos.superapp.apps.CloudStoreHandoff.page(pageId)
        sectionId == "config" && pageId == "store-phone"    -> com.diegonmarcos.superapp.apps.CloudStoreHandoff.page(pageId)
        // Apps Mesh / IPC / Identity are Cloud Store > Access now (one tile, Configs > Network > Apps Access, action open_apps_access).
        // Configs > Network > ADB Shell: the privileged channel's full control (lib AdbShellPage).
        sectionId == "config" && pageId == "adb-shell"      -> com.diegonmarcos.superapp.adbside.AdbSide.page("adb-shell")
        // The `adb-shell` page declares `tabs`, so the strip branch at the top answers it; these are its four tabs (Status cockpit, Shell, Perms, All).
        sectionId == "config" && pageId == "adb-status"     -> com.diegonmarcos.superapp.adbside.AdbSide.page("adb-status")
        sectionId == "config" && pageId == "adb-term"       -> com.diegonmarcos.superapp.adbside.AdbSide.page("adb-term")
        sectionId == "config" && pageId == "adb-perms"      -> com.diegonmarcos.superapp.adbside.AdbSide.page("adb-perms")
        sectionId == "config" && pageId == "adb-all"        -> com.diegonmarcos.superapp.adbside.AdbSide.page("adb-all")
        // Configs ▸ Setup ▸ Launcher ▸ Logcat: the DEDICATED single-app logcat (this app only, online, filters apart from actions).
        sectionId == "config" && pageId == "logcat"         -> com.diegonmarcos.superapp.devcontrol.LogcatFragment.newInstance()
        // Configs ▸ Setup ▸ Watchtower - Obsv ▸ Logcat Fleet: the fleet-wide reader (multi-select apps, per-app view, export).
        sectionId == "config" && pageId == "logcat-fleet"   -> com.diegonmarcos.superapp.devcontrol.FleetLogcatFragment.newInstance()
        sectionId == "config" && pageId == "wg"             -> WireGuardFragment.newInstance()
        // #740 Configs ▸ Mesh ▸ DNS — the fleet resolver, applied at the Cloud Mesh VPN.
        sectionId == "config" && pageId == "dns"            -> com.diegonmarcos.superapp.network.DnsFragment.newInstance()
        // Configs ▸ Network ▸ Firewall — the engine's own controls screen (master switch, VPN consent, per-app rules)
        // embedded as a page, no copy; About keeps a read-only summary.
        // #1719 Firewall (#1737 named Firewall again): full screen, its own nav (Status | Apps | Rules) and Back; each view is the engine's FirewallDialog.
        sectionId == "config" && pageId == "firewall"       -> com.diegonmarcos.superapp.network.NgFirewallFragment.newInstance()
        // #1737 Configs > Network > NG Firewall: the hub page listing the network items under Connections / Firewalls / Servers.
        sectionId == "config" && pageId == "ng-firewall"    -> com.diegonmarcos.superapp.network.NgFirewallHubFragment.newInstance()
        // "myfin" section is GONE — the dashboard moved to Cloud-Me (Buro > Fin)
        // and libs:fin left with it. The tile that deep-linked to it is gone too
        // (the owner dropped Projects Me ▸ MyFin), so nothing here targets
        // Buro > Fin; it is reached from inside Cloud-Me.
        sectionId == "cal"     && pageId == "month"         -> CalendarMonthFragment.newInstance()
        sectionId == "cal"     && pageId == "agenda"        -> CalendarAgendaFragment.newInstance()
        // "wallet" section is dead — tile target extapp:cloud-wallet bypasses openSectionPage.
        // "health" has no pages any more — the MyHealth surface moved to Cloud-Me
        // (Projects > Health); tile target extapp:cloud-me#page:projects/health. The
        // section survives in build.json for its `metrics` taxonomy alone, which
        // Configs > Permissions reads to count the Health Connect grants.
        sectionId == "wg"     && pageId == "config"         -> WireGuardFragment.newInstance()
        sectionId == "config" && pageId == "onehand" ->
            com.diegonmarcos.superapp.configs.OneHandFragment.newInstance()
        // #1553 Configs ▸ Setup ▸ Launcher ▸ Folders — the declarative app-into-folder organiser (Cloud | Phone),
        // its two tables served by the fleet-data client, not baked.
        sectionId == "config" && pageId == "app-folders" ->
            com.diegonmarcos.superapp.configs.FoldersFragment.newInstance()
        sectionId == "config" && pageId == "perms" ->
            com.diegonmarcos.superapp.configs.PermissionsFragment.newInstance()
        // #1596 Configs ▸ Focus Mode — the one Focus switch (FocusMode.active, read by AlertsNotifier to hold back non-urgent alerts).
        sectionId == "config" && pageId == "focus" ->
            com.diegonmarcos.superapp.configs.FocusModeFragment.newInstance()
        // Configs ▸ About ▸ Battery: the battery Source of Truth's full stats page (hidden; opened from
        // About's Battery section, the home-screen battery popup and the Battery badge).
        sectionId == "config" && pageId == "battery"        ->
            com.diegonmarcos.superapp.batterystats.BatteryStatsFragment.newInstance()
        // Configs ▸ Watchtower - Obsv ▸ X-Ray Device (every macro group, Diagnostics and Agents folded in), Launcher ▸ X-Ray App
        // (the same fragment, only the app-scoped groups: APP_XRAY_MACROS) and Launcher ▸ About (only its App & Build and Repo &
        // Releases groups). Old ids about/diagnostics/agents/watchtower are page_aliases.
        sectionId == "config" && pageId == "xray" ->
            com.diegonmarcos.superapp.devcontrol.DevControlFragment.newInstance(label)
        sectionId == "config" && pageId == "xray-app" ->
            com.diegonmarcos.superapp.devcontrol.DevControlFragment.newInstance(label, com.diegonmarcos.superapp.devcontrol.DevControlFragment.APP_XRAY_MACROS)
        sectionId == "config" && pageId == "app-about" ->
            com.diegonmarcos.superapp.devcontrol.DevControlFragment.newInstance(label, com.diegonmarcos.superapp.devcontrol.DevControlFragment.ABOUT_MACROS)
        // "browser" section is dead — tile target extapp:cloud-browser bypasses openSectionPage.
        sectionId == "apptabs"                              ->
            com.diegonmarcos.superapp.apptabs.AppTabsFragment.newInstance()
        sectionId == "recentapps"                           ->
            RecentAppsFragment.newInstance()
        else -> PageContentFragment.newInstance(sectionId, pageId, label)
    }
}
