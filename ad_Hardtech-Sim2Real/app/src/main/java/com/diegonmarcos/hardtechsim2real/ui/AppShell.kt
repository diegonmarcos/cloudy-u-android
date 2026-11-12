package com.diegonmarcos.hardtechsim2real.ui

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.hardtechsim2real.Decl
import com.diegonmarcos.hardtechsim2real.R
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "app_shell"
    const val PAGE = "app_page"
    const val TICKER = "holdings_ticker"
    const val QUANTITY = "holdings_quantity"
    const val COST = "holdings_cost"
    fun entry(title: String): String = "entry:$title"
}

/**
 * The shell: the shared first row (Profile at the top right opens Cloud Account, the gear opens the About section), the shared
 * bottom nav, the shared page gradient and the shared scroll collapse, all from libs (#1154, #1157). Which sections exist is
 * build.json::ui, read through [Decl.nav]; what a section's page shows is ui.stacks, drawn by [SectionPage].
 */
@Composable
fun AppShell() {
    val nav = Decl.nav
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_ABOUT) }
    val sec = nav.section(section)
    // #1697 the gear opens the fleet Configs strip: {own pages} | Agents, Perms, Logcat, X-Ray App, About (FleetConfigsScreen draws the tail
    // through FleetConfigsExtensions.stripOrder). The app's former Configs target stays one tap away: General > App.
    var fleetConfigsOpen by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.remember {
        com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.register(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab("holdings", "Holdings", "layers") { _ -> androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { HoldingsPanel() } })
        com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.register(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab("app_page", "App", "info") { _ -> SectionPage(Decl.SECTION_ABOUT) })
    }
    if (fleetConfigsOpen) com.diegonmarcos.superapp.fleetconfigs.FleetConfigsScreen(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage.General, dark = true, onDismiss = { fleetConfigsOpen = false }, onAppConfigs = { section = Decl.SECTION_ABOUT })
    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, null) }.orEmpty(),
            onNavigate = { e -> section = e.sectionId },
            onAccount = rememberAccountLauncher(),
            onConfigs = { fleetConfigsOpen = true },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = nav.islandEntries(),
                selectedId = section,
                onSelect = { section = it.id },
                modifier = Modifier.testTag(Tags.SHELL),
            ) {
                SectionPage(section)
            }
        }
    }
}
