package com.diegonmarcos.cloudme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy

/**
 * The shell Compose screen: the fleet's top bar over [banner] and [MeBody] (content above the island).
 *
 * Scrolling the page DOWN hides the top row and collapses the island's labels to icons; scrolling UP brings both
 * back (#1150). One nested-scroll connection ([rememberBottomNavCollapse], carried by MeBody's content slot) drives
 * both, so they can never disagree. The drawer opens by tapping the hamburger only.
 *
 * #1154 the row is libs:ui-nav-bars's (plain icons, the title island with the hierarchy dropdown of the section
 * shown) in the dark palette of libs:theme-background (Cloud Me is dark-only); MainActivity paints the gradient behind the window insets.
 */
@Composable
fun MeShell(
    title: String,
    selectedSectionId: String?,
    onOpen: (String) -> Unit,
    onTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
    /** The page requested inside [selectedSectionId], marked in the title dropdown; null = the section itself. */
    currentPageId: String? = null,
    /** A dropdown pick below the section: show [pageId] of [sectionId]. Defaults to opening the section. */
    onOpenPage: (sectionId: String, pageId: String) -> Unit = { s, _ -> onOpen(s) },
    content: @Composable () -> Unit,
) {
    val settings = Sections.settings()
    val hierarchy = Sections.byId(selectedSectionId)?.let { titleHierarchy(it.nav, currentPageId) }.orEmpty()
    NavBarTopFirstRowScaffold(
        title = title,
        hierarchy = hierarchy,
        // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
        sections = Sections.all().map { it.nav },
        onNavigate = { e -> if (e.depth == 0) onOpen(e.sectionId) else onOpenPage(e.sectionId, e.id) },
        onAccount = { settings.firstOrNull()?.let { onOpen(it.id) } },
        onConfigs = { settings.lastOrNull()?.let { onOpen(it.id) } },
        modifier = modifier,
        onAi = { onTarget(MeTopBar.AI_TARGET) },
        dark = true,
    ) {
        MeDrawer(onOpen)
        Column(Modifier.fillMaxSize()) {
            banner()
            // #1157 the ONE chrome connection of the shared first-row scaffold (it also hides the top row and compacts the second row).
            val collapse = LocalNavChromeCollapse.current ?: rememberBottomNavCollapse()
            MeBody(selectedSectionId, onOpen, onTarget, Modifier.weight(1f), collapse = collapse, content = content)
        }
    }
}
