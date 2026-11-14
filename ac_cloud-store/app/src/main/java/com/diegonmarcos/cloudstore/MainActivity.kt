package com.diegonmarcos.cloudstore

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import com.diegonmarcos.superapp.ai.AiFleetChatPanel
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.compose.AndroidFragment
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StorePages
import com.diegonmarcos.superapp.appstore.StoreImport
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.updater.Updater

/**
 * #865 The three store pages SuperApp shows under Config, as sections of the fleet island (#868):
 * Cloud (the constellation fleet, with the Commits and CI-CD panels under its action buttons), Phone (installed apps), Search (Cloudy | Android | Torrent sources), Torrent (the downloader) and Access (#866). They are
 * build.json::ui.sections and ui.bottom_nav; [NAV] reads them, the island draws them. Settings is the
 * fifth section but no longer an island item: it is the page the top bar's Settings > Configs opens.
 *
 * The first row is the fleet's AppTopBar (libs:bottomnav), declared in build.json::ui.top_bar and read
 * as [TOP_BAR]: a hamburger whose drawer is the page's ([StoreDrawer]), the title, AI and Settings.
 *
 * The pages are libs:appstore's Fragments, hosted with AndroidFragment. All
 * three stay composed and the hidden ones are sized to zero, so switching
 * tabs keeps a running download list and its scroll position.
 */
class MainActivity : AppCompatActivity() {

    /** The tab an Intent asked for; read by the composition. */
    private var requested by mutableStateOf<String?>(null)

    /** Section id -> the child page its strip last chose (Compose state, so the page below follows). */
    private val pageChoice = mutableStateMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // #1728 the X-Ray App > Performance benchmarks of this app
        runCatching { StoreBenchmarks.register() }
        // #896 this host draws the child-page strips (build.json::ui sections[].pages) with libs:ui-nav-bars's NavBarTopSecondRow.
        StorePages.hostDrawsStrip = true
        // The Store tab "Fleet Data" renders libs:fleet-data-ui's page inline in the Store's own pane (no separate Activity).
        StorePages.fleetDataPane = { authority, modifier -> com.diegonmarcos.superapp.fleetdata.ui.FleetDataPage(authority, modifier) }
        requested = tabFrom(intent)
        // #1675 another fleet app handed Cloud Store (the one installer of record) an install of a fleet package.
        com.diegonmarcos.superapp.updater.install.StoreHandoff.accept(this, intent)
        // A magnet link or a .torrent opened with this app goes to the engine and lands on the Torrent tab.
        if (TorrentIntake.handle(this, intent)) requested = TAB_TORRENT
        setContent {
            KitDenseMaterial(darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { StoreShell() }
            }
        }
        // #894 / #1515 POST_NOTIFICATIONS is asked at first run and granted in Configs > Perms (libs:perms), never from here.
        // Self-update, as every constellation app does.
        Updater.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFrom(intent)?.let { requested = it }
        com.diegonmarcos.superapp.updater.install.StoreHandoff.accept(this, intent)
        if (TorrentIntake.handle(this, intent)) requested = TAB_TORRENT
    }

    @androidx.compose.runtime.Composable
    private fun StoreShell() {
        var selected by rememberSaveable { mutableStateOf(requested ?: NAV.default()?.id ?: TAB_CLOUD) }
        // A tab named by a later Intent (SuperApp's Open button, a notification tap).
        androidx.compose.runtime.LaunchedEffect(requested) {
            requested?.let { selected = it; requested = null }
        }
        // Configs is not on the island any more (the top bar's Settings > Configs opens it), so Back leaves it for the home section.
        androidx.activity.compose.BackHandler(enabled = selected == TAB_SETTINGS) {
            selected = NAV.default()?.id ?: TAB_CLOUD
        }
        // Search is its own island item but the same libs:appstore page as before (StorePhoneFragment's search page):
        // the fragment follows StorePages, so opening Search selects it and Phone restores its own strip choice.
        androidx.compose.runtime.LaunchedEffect(selected) {
            if (selected == TAB_SEARCH) StorePages.select(TAB_PHONE, StorePhoneFragment.PAGE_SEARCH)
            else if (selected == TAB_PHONE) NAV.section(TAB_PHONE)?.let { choose(TAB_PHONE, pageOf(it)) }
        }
        val shell: @androidx.compose.runtime.Composable () -> Unit = {
            NavBarBottomHost(
                entries = NAV.islandEntries(),
                selectedId = selected,
                onSelect = { selected = it.id },
            ) {
            // #1630 Settings > Configs ends with the fleet-default tail: {own tabs} | Agents, Perms, Logcat, X-Ray App, About.
            val section = NAV.section(selected)?.let { s ->
                if (s.id == TAB_SETTINGS) s.copy(pages = com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(s.pages)) else s
            }
            // The drawer is the page's: the Store's sections, and the open section's own pages.
            TopBarDrawer { close ->
                StoreDrawer(NAV, selected, section?.takeIf { it.pages.size > 1 }?.let { pageOf(it) }, onSection = { selected = it }, onPage = { s, p -> selected = s; choose(s, p) }, close = close)
            }
            // A section with child pages gets the fleet's navigation (Phone: Installed | Declared, Search: Cloudy | Android | Torrent)
            // in the mode it declares or the user chose in Configs > About (one row, two rows, side menu); the page below reads the
            // choice (Phone and Search from StorePages).
            val pageBody: @androidx.compose.runtime.Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                val shown = Modifier.fillMaxSize()
                val hidden = Modifier.size(0.dp)
                AndroidFragment<StoreCloudFragment>(if (selected == TAB_CLOUD) shown else hidden)
                AndroidFragment<StorePhoneFragment>(if (selected == TAB_PHONE || selected == TAB_SEARCH) shown else hidden)
                // Torrent and Access are composed only while shown: the torrent list polls the engine, the mesh probes.
                // (The feeds are panels on the Cloud page now: libs:appstore StoreFeedPanels.)
                if (selected == TAB_TORRENT) TorrentPage()
                if (selected == TAB_ACCESS) section?.let { AccessPage(pageOf(it)) }
                if (selected == TAB_SETTINGS) section?.let { ConfigsPage(pageOf(it)) }
            }
            }
            if (section != null && section.pages.size > 1) {
                NavSectionLayout(section, pageOf(section), onSelect = { choose(section.id, it.id) }, modifier = Modifier.fillMaxSize(), content = pageBody)
            } else pageBody()
            }
        }
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            BatteryPrompt()   // #1078 once, while the app is still battery-optimized
            val topBar = TOP_BAR
            // #1154 the first row is libs:ui-nav-bars's: the open section's name is the island, whose dropdown lists the
            // section and its pages (the same tree the drawer shows); a pick of a page opens it on the section's strip.
            val openSection = NAV.section(selected)
            if (topBar == null) shell() else NavBarTopFirstRowScaffold(
                title = openSection?.label ?: getString(R.string.app_name),
                hierarchy = openSection?.let { titleHierarchy(it, it.takeIf { s -> s.pages.size > 1 }?.let { s -> pageOf(s) }) }.orEmpty(),
                // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
                sections = NAV.sections,
                onNavigate = { e -> selected = e.sectionId; if (e.depth > 0) choose(e.sectionId, e.id) },
                onAccount = rememberAccountLauncher(),
                onConfigs = { selected = TAB_SETTINGS },
                aiPanel = { AiFleetChatPanel(BuildConfig.AI_GATEWAY_URL) },
                content = shell,
            )
        }
    }

    /** The open child page of [section]: the choice made on its strip, else the section's default. */
    private fun pageOf(section: NavSection): String {
        val ids = section.pages.map { it.id }
        return pageChoice[section.id]?.takeIf { it in ids } ?: StorePages.defaultPage(section.id, ids)
    }

    private fun choose(section: String, page: String) {
        pageChoice[section] = page
        StorePages.select(section, page)
    }

    private fun tabFrom(i: Intent?): String? {
        // #570 an inventory handed over by Account (Apply list to Store): the Phone page consumes it on resume.
        i?.getStringExtra(StoreImport.EXTRA_IMPORT)?.let { StoreImport.pending = it }
        // #1736 the SuperApp's Cloud app menu ▸ About: park the package, Store ▸ Cloud opens its About sheet.
        i?.getStringExtra(com.diegonmarcos.superapp.appstore.StoreAbout.EXTRA)?.let { com.diegonmarcos.superapp.appstore.StoreAbout.park(it) }
        if (i?.hasExtra(com.diegonmarcos.superapp.appstore.StoreAbout.EXTRA) == true && i.getStringExtra(EXTRA_TAB) == null) return TAB_CLOUD
        val tab = i?.getStringExtra(EXTRA_TAB)
        // #1719 a deep link to one Access page (SuperApp > Network > OS Perms asks for access + android): the page must be one of Access's.
        if (tab == TAB_ACCESS) i?.getStringExtra(EXTRA_PAGE)?.let { pg ->
            val target = AccessPages.resolve(pg)
            if (NAV.section(TAB_ACCESS)?.pages?.any { it.id == target } == true) choose(TAB_ACCESS, target)
        }
        // SuperApp's apps-mesh page used to open the Mesh tab. Apps Mesh is retired (#1580: one mesh gateway); the gateway's routes are Access > IPC.
        if (tab == LEGACY_MESH) { choose(TAB_ACCESS, AccessPages.resolve(LEGACY_MESH)); return TAB_ACCESS }
        // Feed moved into the Cloud page (Commits and CI-CD panels): an intent asking for the old tab opens Cloud.
        if (tab == LEGACY_FEED) return TAB_CLOUD
        return tab?.takeIf { t -> NAV.section(t) != null }
    }

    companion object {
        /** Intent extra naming the tab to open: [TAB_CLOUD], [TAB_PHONE], [TAB_SEARCH], [TAB_TORRENT] or [TAB_ACCESS]. */
        const val EXTRA_TAB = "tab"
        /** Intent extra naming one page of the tab (with [TAB_ACCESS]: mcp, apis, ipc, identity or android); unknown = the tab's own choice. */
        const val EXTRA_PAGE = "page"
        const val TAB_CLOUD = "cloud"
        const val TAB_PHONE = "phone"
        const val TAB_SEARCH = "search"
        const val TAB_TORRENT = "torrent"
        /** The old Feed tab: its Commits and CI-CD feeds are panels on the Cloud page now. */
        private const val LEGACY_FEED = "feed"
        const val TAB_ACCESS = "access"
        /** The old Mesh tab: Apps Mesh is retired (#1580); an intent asking for `mesh` opens Access > IPC, where the one mesh gateway is. */
        private const val LEGACY_MESH = "mesh"
        const val TAB_SETTINGS = "settings"
        /** build.json::ui as baked into BuildConfig: the island's items and the section ids. */
        val NAV: NavDecl by lazy {
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
        }

        /** build.json::ui.top_bar as baked into BuildConfig: what the first row draws (null = the app declares no top bar). */
        val TOP_BAR: TopBarDecl? by lazy { TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64) }

    }
}
