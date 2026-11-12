package com.diegonmarcos.cloudnav

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.configs.ConfigsFragment
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.cloudnav.places.PlacesFragment
import com.diegonmarcos.cloudnav.routes.NavigationFragment
import com.diegonmarcos.cloudnav.routes.RoutesFragment
import com.diegonmarcos.cloudnav.tracker.NavDeepLink
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitFragmentHostState

/**
 * Cloud Nav shell — minimal Google-Maps-style chrome, in Compose:
 *   • a content area that hosts one page fragment (each page draws its own search UI)
 *   • bottom nav island (libs:ui-nav-bars's NavBarBottomHost): Routes · Navigation · Places · Explored · Tracker
 *
 * Sections + default section are data-driven from build.json::ui.* (decoded
 * by [NavConfig.decl], libs:bottomnav's NavDecl). The shell deliberately owns NO search bar — Routes does
 * multi-stop routing, Navigation a destination field, Places a simple POI
 * lookup with category islands. Each fragment renders the search UI it needs.
 */
class MainActivity : AppCompatActivity() {

    private var currentTab by mutableStateOf("")
    /** Bumped on every switch, so re-selecting the Places tab for a new geo: hand-off still builds a fresh page. */
    private var tabNonce by mutableIntStateOf(0)
    /** A geo: location handed in by another app, consumed by the Places tab on
     *  its next creation (see [fragmentForSection]). */
    private var pendingGeo: GeoTarget? = null
    /** A page inside the next-built section (deep link), consumed once. */
    private var pendingPage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The page fragment is added by the Compose host once it is on screen, so a restored one would have nowhere to attach.
        val savedTab = savedInstanceState?.getString(KEY_TAB)
        super.onCreate(KitFragmentHostState.withoutFragments(savedInstanceState))
        FleetChrome.apply(this)

        // Schedule the periodic GHCR self-update check (data-driven cadence).
        // The Update tab in Configs offers a manual check-now button.
        Updater.start(this)
        // Tracking engine: re-assert the periodic job (a no-op when the engine is off).
        com.diegonmarcos.cloudnav.tracker.EngineScheduler.apply(this)

        if (savedInstanceState == null) {
            // A geo: launch (Cloud Nav declared as a maps app) opens straight on
            // the Places map at the requested location; otherwise the default tab.
            val geo = geoTargetOf(intent)
            val deep = deepLinkOf(intent)
            if (geo != null) { pendingGeo = geo; switchTo(TAB_PLACES) }
            else if (deep != null) openDeepLink(deep)
            else switchTo(defaultSection())
        } else {
            currentTab = savedTab ?: defaultSection()
        }

        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CloudKitTheme(TrackerInk.palette()) { NavShell() } }
        })
    }

    /** A geo: intent arriving while the app is already running (singleTop) —
     *  re-route to the Places map at the new location. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkOf(intent)?.let { openDeepLink(it); return }
        val geo = geoTargetOf(intent) ?: return
        pendingGeo = geo
        switchTo(TAB_PLACES)
    }

    /** `shortcut_action` = `page:<section>/<page>`, e.g. the SuperApp's Health badge: page:tracker/steps. */
    private fun deepLinkOf(intent: Intent?): NavDeepLink.Target? =
        NavDeepLink.parse(intent?.getStringExtra("shortcut_action"))?.takeIf { NavConfig.decl.section(it.section) != null }

    private fun openDeepLink(t: NavDeepLink.Target) {
        pendingPage = t.page
        switchTo(t.section)
    }

    private fun geoTargetOf(intent: Intent?): GeoTarget? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return GeoUri.parse(intent.data?.toString())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab)
    }

    /** The bar is libs:ui-nav-bars's, fed by NavDecl (#868). The app only says which fragment a
     *  section opens; the island's look is the lib's, the same in every app. The page sits above it. */
    @Composable
    private fun NavShell() {
        val ctx = LocalContext.current
        val entries = NavConfig.decl.islandEntries()
        val section = NavConfig.decl.section(currentTab)
        val ai = rememberSuperAppAiLauncher()
        // #1154 the first row: AI hands over to the SuperApp, Profile opens Cloud Account, Configs is this app's Configs section. A pick of a
        // page below a section opens the section (its pages are fragments with their own strip state).
        NavBarTopFirstRowScaffold(
            title = section?.label ?: getString(R.string.app_name),
            hierarchy = section?.let { titleHierarchy(it, null) }.orEmpty(),
            // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
            sections = NavConfig.decl.sections,
            onNavigate = { e -> if (e.sectionId != currentTab) switchTo(e.sectionId) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { if (currentTab != "configs") switchTo("configs") },
            onAi = ai,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        ) {
            TopBarDrawer { close ->
                NavConfig.decl.sections.forEach { sec ->
                    Text(sec.label, Modifier.fillMaxWidth().clickable { if (sec.id != currentTab) switchTo(sec.id); close() }.padding(horizontal = 20.dp, vertical = 14.dp))
                }
            }
            NavBarBottomHost(entries, currentTab, { e -> if (e.id != currentTab) switchTo(e.id) }) {
                FragmentHost(supportFragmentManager, R.id.nav_host_main, currentTab to tabNonce, Modifier.fillMaxSize()) {
                    fragmentForSection(currentTab)
                }
            }
        }
    }

    private fun defaultSection(): String = NavConfig.decl.default()?.id ?: "routes"

    private fun switchTo(tabId: String) {
        currentTab = tabId
        tabNonce++
    }

    private fun fragmentForSection(id: String): Fragment = when (id) {
        "routes"     -> RoutesFragment()
        "navigation" -> NavigationFragment()
        // Consume a pending geo: location once, so a hand-off from another app
        // lands on the map at that point / runs that search.
        "places"     -> pendingGeo?.let { g -> pendingGeo = null; PlacesFragment.forGeo(g) } ?: PlacesFragment()
        "explored"   -> ExploredFragment().withPendingPage()
        "tracker"    -> TrackerFragment().withPendingPage()
        // Configs left the island (#1156, it lives in the top bar); still a declared section, reached by page:configs[/<page>] links.
        "configs"    -> ConfigsFragment()
        else         -> RoutesFragment()
    }

    /** A deep link's page, consumed once by the section it names. */
    private fun PagedSectionFragment.withPendingPage() = also { f ->
        pendingPage?.let { pg -> pendingPage = null; f.arguments = Bundle().apply { putString(PagedSectionFragment.ARG_PAGE, pg) } }
    }

    private companion object {
        const val KEY_TAB = "cloud_nav_tab"
        const val TAB_PLACES = "places"
    }
}
