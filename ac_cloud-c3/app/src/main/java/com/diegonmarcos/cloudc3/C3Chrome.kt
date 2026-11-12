package com.diegonmarcos.cloudc3

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy

/** Test tags of the shell's chrome. */
object C3Tags {
    const val CONTENT = "c3_content"
    fun drawerSection(id: String) = "c3_drawer_section_$id"
    fun drawerPage(section: String, page: String) = "c3_drawer_page_${section}_$page"
}

/**
 * The shell's chrome: the fleet's shared first row ([NavBarTopFirstRowScaffold], libs:ui-nav-bars; ui.top_bar declares that it shows) over
 * the page and the bottom island. The scaffold draws the hamburger, the title, the AI button and the Settings
 * dropdown; this file only FEEDS it: the AI panel and the Account launch come from the host ([aiPanel], [onAccount]),
 * Settings > Configs is [onConfigs], and the drawer is the full tree of the declared nav ([DrawerTree]).
 *
 * A page that wants its own drawer calls TopBarDrawer while it is shown and takes this one's place.
 */
@Composable
fun C3Chrome(
    selectedTab: String?,
    selectedPage: String?,
    onOpen: (C3Nav.Target) -> Unit,
    onAccount: () -> Unit,
    onConfigs: () -> Unit,
    aiPanel: @Composable (close: () -> Unit) -> Unit,
    decl: TopBarDecl? = Declarations.topBar,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        TopBarDrawer { close ->
            DrawerTree(selectedTab, selectedPage) { target -> close(); onOpen(target) }
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().testTag(C3Tags.CONTENT)) { content() }
            // The fleet's ONE bottom nav (#531/#565), below the content so nothing a page draws can hide under it.
            C3BottomNav.Island(selectedId = selectedTab, onOpen = { onOpen(C3Nav.Target(it)) })
        }
    }
    if (decl == null) {
        Box(modifier.fillMaxSize()) { body() }
    } else {
        // #1154 the first row is libs:ui-nav-bars's: the open section's name is the island, whose dropdown lists the
        // section and its pages (the drawer's tree); a pick opens that place like a drawer row does.
        val section = Declarations.nav.section(selectedTab)
        NavBarTopFirstRowScaffold(
            title = section?.label ?: stringResource(R.string.app_name),
            hierarchy = section?.let { titleHierarchy(it, selectedPage) }.orEmpty(),
            // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
            sections = Declarations.nav.sections,
            onNavigate = { e -> onOpen(C3Nav.Target(e.sectionId, e.id.takeIf { e.depth > 0 })) },
            onAccount = onAccount,
            onConfigs = { onConfigs() },
            aiPanel = aiPanel,
            modifier = modifier,
            content = body,
        )
    }
}

/**
 * The drawer's content: every declared section with its pages beneath it, each tappable. The current section and
 * page are marked selected. Built from [C3Nav.drawerRows], so the tree is the declaration and nothing else.
 */
@Composable
private fun ColumnScope.DrawerTree(tab: String?, page: String?, go: (C3Nav.Target) -> Unit) {
    C3Nav.drawerRows(Declarations.nav).forEach { row ->
        val isSection = row.page == null
        val here = if (isSection) row.section == tab else row.section == tab && row.page == page
        Text(
            row.label,
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { go(C3Nav.Target(row.section, row.page)) }
                .padding(start = (16 + 16 * row.depth).dp, end = 16.dp, top = if (isSection) 10.dp else 6.dp, bottom = if (isSection) 6.dp else 6.dp)
                .testTag(if (isSection) C3Tags.drawerSection(row.section) else C3Tags.drawerPage(row.section, row.page!!))
                .semantics { selected = here },
            color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isSection || here) FontWeight.SemiBold else FontWeight.Normal,
            style = if (isSection) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
        )
    }
}
