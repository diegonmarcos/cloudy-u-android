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
                factoryFor(sectionId, p.id, p.label, p.url, p.tabs)
            })
        }
    }

    private fun factoryFor(
        sectionId: String,
        pageId: String,
        label: String,
        url: String = "",
        tabs: List<String> = emptyList(),
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
        sectionId == "config" && pageId == "panels-ui" -> LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI)
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
        sectionId == "config" && pageId == "adb-shell"      -> com.diegonmarcos.superapp.configs.AdbShellFragment.newInstance()
        // Configs ▸ Observability ▸ Logcat: the live logcat viewer, its own tab before About (it no longer lives inside About).
        sectionId == "config" && pageId == "logcat"         -> com.diegonmarcos.superapp.devcontrol.LogcatFragment.newInstance()
        sectionId == "config" && pageId == "wg"             -> WireGuardFragment.newInstance()
        // #740 Configs ▸ Mesh ▸ DNS — the fleet resolver, applied at the Cloud Mesh VPN.
        sectionId == "config" && pageId == "dns"            -> com.diegonmarcos.superapp.network.DnsFragment.newInstance()
        // Configs ▸ Network ▸ Firewall — the engine's own controls screen (master switch, VPN consent, per-app rules)
        // embedded as a page, no copy; About keeps a read-only summary.
        sectionId == "config" && pageId == "firewall"       -> com.diegonmarcos.superapp.firewall.FirewallDialog().apply { showsDialog = false }
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
        sectionId == "config" && pageId == "perms" ->
            com.diegonmarcos.superapp.configs.PermissionsFragment.newInstance()
        // Configs ▸ About ▸ Battery: the battery Source of Truth's full stats page (hidden; opened from
        // About's Battery section, the home-screen battery popup and the Battery badge).
        sectionId == "config" && pageId == "battery"        ->
            com.diegonmarcos.superapp.batterystats.BatteryStatsFragment.newInstance()
        // Configs ▸ Observability ▸ X-Ray (every macro group, Diagnostics and Agents folded in) and ▸ About (the same
        // fragment, only its App & Build and Repo & Releases groups). Old ids about/diagnostics/agents are page_aliases.
        sectionId == "config" && pageId == "xray" ->
            com.diegonmarcos.superapp.devcontrol.DevControlFragment.newInstance(label)
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
