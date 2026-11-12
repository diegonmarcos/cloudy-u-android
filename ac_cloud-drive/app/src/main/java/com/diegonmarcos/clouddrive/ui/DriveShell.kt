package com.diegonmarcos.clouddrive.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.appTopBarLabels
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy

/**
 * #579 the shell (cloud-drive-redesign.md §2): the tab content above the fleet's
 * bottom-nav island. The tabs are build.json::ui.bottom_nav (sections, #868) — id, label, icon — in declared
 * order; the selected one is saved across process death; the island collapses to icons
 * on scroll and reads its own bottom inset while the content consumes it once (the
 * libs:bottomnav BottomNavBar pattern, with this app's entries). Re-tapping the
 * selected tab is handed to the screen as [onReselect].
 *
 * The dispatch on a tab id is the ONE place Kotlin names the ids; test-drive-shell.sh
 * diffs it against the declaration in both directions.
 *
 * #603 [select] is a DECLARED tab id a screen asked for (an Apps tile's route): the
 * shell selects it once and calls [onSelected] so the request is not re-applied on
 * every recomposition. An id that is not declared is ignored, never a blank shell.
 */
@Composable
fun DriveShell(select: String? = null, onSelected: () -> Unit = {}, content: @Composable (tabId: String, reselectTick: Int) -> Unit) {
    val tabs = Declarations.tabs
    var selected by rememberSaveable { mutableStateOf(Declarations.defaultTab.takeIf { d -> tabs.any { it.id == d } } ?: tabs.firstOrNull()?.id ?: "") }
    var reselectTick by rememberSaveable { mutableStateOf(0) }
    val entries = Declarations.nav.islandEntries()
    LaunchedEffect(select) {
        // #1537 a legacy id (the deleted Home) is resolved to its new tab first.
        if (select != null) Declarations.resolveRoute(select, "").first.let { if (Declarations.routable(it)) selected = it }
        if (select != null) onSelected()
    }

    // The fleet's own host: the page above the island, the scroll collapse and the inset handling.
    // Every page below scrolls inside it; DriveChromeCollapseTest proves at runtime that a swipe collapses and restores the chrome.
    val host: @Composable () -> Unit = {
        NavBarBottomHost(
            entries = entries,
            selectedId = selected,
            onSelect = { entry -> if (entry.id == selected) reselectTick++ else selected = entry.id },
            modifier = Modifier.testTag(DriveTags.SHELL),
        ) {
            // #1149 the hamburger's left side menu: every destination, the two the top bar also opens included.
            TopBarDrawer { close ->
                Declarations.nav.bottomSections().forEach { sec -> DrawerRow(sec.label, sec.icon, selected == sec.id, DriveTags.drawerItem(sec.id)) { selected = sec.id; close() } }
                Declarations.nav.sections.filter { sec -> sec.id !in tabs.map { it.id } }.forEach { sec -> DrawerRow(sec.label, sec.icon, selected == sec.id, DriveTags.drawerItem(sec.id)) { selected = sec.id; close() } }
            }
            Box(Modifier.fillMaxSize().testTag(DriveTags.CONTENT)) {
                AnimatedContent(targetState = selected, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "drive_tab") { id ->
                    Box(Modifier.fillMaxSize().testTag(DriveTags.tab(id))) {
                        if (Declarations.routable(id)) content(id, reselectTick)
                        else EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
                    }
                }
            }
        }
    }
    // The fleet's first row (libs:ui-nav-bars, declared by ui.top_bar): the round Settings button at the top right,
    // a dropdown of Profile (Cloud Account) and Configs (this app's Configs tab). It owns the top inset, which the
    // pages' own toolbar islands then find already consumed.
    val topBar = Declarations.topBar
    if (topBar == null) host() else {
        val labels = appTopBarLabels().let { it.copy(account = stringResource(R.string.top_bar_profile)) }
        Box(Modifier.fillMaxSize().windowInsetsPadding(topIslandInsets())) {
            // #1154 the first row is libs:ui-nav-bars's: plain icons, the open page's title as the island, whose dropdown
            // is the open destination's tree (the same one the side menu lists); a pick below it asks the destination's second row.
            val section = Declarations.nav.section(selected)
            NavBarTopFirstRowScaffold(
                title = section?.label ?: stringResource(R.string.app_name),
                hierarchy = section?.let { titleHierarchy(it, null) }.orEmpty(),
                // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
                sections = Declarations.nav.sections,
                onNavigate = { e -> if (Declarations.routable(e.sectionId)) selected = e.sectionId },
                onAccount = rememberAccountLauncher(),
                onConfigs = { if (Declarations.routable(CONFIGS_TAB)) selected = CONFIGS_TAB },
                onAi = { if (Declarations.routable(AI_TAB)) selected = AI_TAB },
                labels = labels,
                content = host,
            )
        }
    }
}

private const val CONFIGS_TAB = Declarations.SECTION_CONFIGS
private const val AI_TAB = Declarations.SECTION_AI

/** One row of the side menu: the declared glyph and label; the open destination is bold. */
@Composable
private fun DrawerRow(label: String, icon: String, current: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = DriveMetrics.tap).clickable(onClick = onClick).padding(horizontal = DriveMetrics.padWide, vertical = DriveMetrics.gap).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconCatalog.vectorOrDefault(icon), contentDescription = null, Modifier.size(DriveMetrics.glyphIcon))
        Spacer(Modifier.width(DriveMetrics.padWide))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
    }
}
