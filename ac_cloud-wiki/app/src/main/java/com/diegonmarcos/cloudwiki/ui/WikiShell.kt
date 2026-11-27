package com.diegonmarcos.cloudwiki.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudwiki.Chronos
import com.diegonmarcos.cloudwiki.ChronosRepo
import com.diegonmarcos.cloudwiki.Decl
import com.diegonmarcos.cloudwiki.LibraryTree
import com.diegonmarcos.cloudwiki.MenuState
import com.diegonmarcos.cloudwiki.WikiMenu
import com.diegonmarcos.cloudwiki.withChronos
import com.diegonmarcos.cloudwiki.R
import com.diegonmarcos.cloudwiki.WikiStart
import com.diegonmarcos.cloudwiki.WikiStore
import com.diegonmarcos.superapp.bottomnav.LocalNavModeStore
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.currentNavUi
import com.diegonmarcos.superapp.bottomnav.effective
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.rememberNavModePrefs
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "wiki_shell"
    const val MENU = "wiki_menu"
    const val PAGE = "wiki_page"
    const val TABS = "wiki_tabs"
    const val CHIPS = "wiki_chips"
    const val ARTICLE = "wiki_article"
    const val RESULTS = "wiki_results"
    const val GRAPH = "wiki_graph"
    const val LOADING = "wiki_loading"
    /** #1789 a Chronos page: the column, its child-page tabs and the web view. */
    const val CHRONOS = "wiki_chronos"
    const val CHRONOS_TABS = "wiki_chronos_tabs"
    const val CHRONOS_WEB = "wiki_chronos_web"
    /** #1830 the source bar: the clone's commit and time, the refresh mode, Refresh. */
    const val CHRONOS_SOURCE = "wiki_chronos_source"
    const val CHRONOS_MODE = "wiki_chronos_mode"
    const val CHRONOS_REFRESH = "wiki_chronos_refresh"
    /** #1857 a project page (Sacred Geometry): the column and the link from its Markdown to its web explorer. */
    const val PROJECT = "wiki_project"
    const val PROJECT_OPEN = "wiki_project_open"
    /** #1867 a hub page (Sciences, A. Architecture of Sacred Sciences, ...): its intro and its topics. */
    const val HUB = "wiki_hub"
    fun chip(tag: String): String = "wiki_chip:$tag"
    fun result(id: String): String = "wiki_result:$id"
}

/**
 * The shell: the shared first row (Profile opens Cloud Account, the gear opens the fleet's Configs General | Perms | About) over ONE collapsed left
 * side menu for every page (libs:ui-nav-bars NavSectionLayout over the `wiki` section, vertical_side_menu). #1867 three groups, the content repo's
 * parents: Modern Science (hubs Sciences, Humanities, Arts, Explore), Arcanum Science (hubs A, B, C of the taxonomy) and Projects (Chronos,
 * Sacred Geometry); a hub is a page holding its topics, indented under it. There is NO bottom nav.
 * Which topics and groups exist is build.json::ui, read through [Decl.nav]; a topic's subpage tabs, the pages and the tags are the runtime dataset
 * wiki (served by Cloud Store), drawn by [WikiPage]. #1789 the Chronos pages of diegonmarcos/lib-chronos are declared as nested pages under
 * Chronos (build.json::ui, the repo's own hierarchy, indented in the side menu and listed in the title dropdown): picking one shows it
 * in a web view, baked into the APK ([Decl.chronos], [ChronosSimPage]).
 * #1891 Home comes first (the index visualiser, [HomePage]); the side menu is the wiki's own [WikiSideLayout] over [LibraryTree].
 */
@Composable
fun WikiShell(start: WikiStart = WikiStart()) {
    val ctx = LocalContext.current
    val data by remember { WikiStore.observe(ctx.applicationContext) }.collectAsState(initial = null)
    // #1830 the app's own clone of lib-chronos: read what is there, refresh when the mode says so; the side menu follows it
    val repo by ChronosRepo.state.collectAsState()
    LaunchedEffect(Unit) { ChronosRepo.refreshIfDue(ctx.applicationContext, opening = false) }
    val declared = Decl.nav.section(Decl.SECTION_WIKI) ?: return
    // #1891 Home first: the index visualiser of the library, a parent of its own ahead of Modern Science, Arcanum Science and Projects
    val homeLabel = stringResource(R.string.home)
    val wiki = remember(repo.tree, homeLabel) { LibraryTree.withHome(withChronos(declared, Decl.chronos), homeLabel) }
    var page by rememberSaveable { mutableStateOf(start.page ?: start.tab?.substringBefore('.')) }
    val nav = remember { WikiNav().also { n -> start.tab?.let { t -> n.tabs[t.substringBefore('.')] = t }; n.tags.addAll(start.tags) } }
    // #1831 a simulator page is a tab of the Chronos topic: the side menu marks Chronos, the title names the simulator
    val pageId = page?.takeIf { p -> wiki.allPages().any { it.id == p } || Decl.chronos.page(p) != null } ?: HomeIndex.startPage(Decl.homeStart, wiki.pages.map { it.id })
    val menuId = if (Decl.chronos.page(pageId) != null) Chronos.PARENT else pageId

    Box(
        Modifier.fillMaxSize().themeBackground(dark = true)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).union(WindowInsets.navigationBars)),
    ) {
        NavBarTopFirstRowScaffold(
            // a nested page (a Chronos page) titles itself like a topic: its declared label
            title = Decl.chronos.page(pageId)?.label ?: wiki.allPages().firstOrNull { it.id == pageId }?.label ?: stringResource(R.string.app_name),
            hierarchy = titleHierarchy(wiki, menuId),
            // #1483 every declared section is a peer in the title dropdown: a user reaches every page from it
            sections = Decl.nav.sections,
            onNavigate = { e -> if (e.depth > 0) page = e.id },
            onAccount = rememberAccountLauncher(),
            onConfigs = { FleetConfigsEntries.open(ctx, FleetConfigsPage.General) },
            dark = true,
        ) {
            val body: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().testTag(Tags.SHELL)) {
                    WikiPage(data, wiki, pageId, nav, onTopic = { page = it })
                }
            }
            // #1891 the declared side menu is the wiki's own (WikiSideLayout: its own scroll and touch area, never collapsed by the page's
            // scroll, 75% of the width, the Filter / Expand / Collapse / Search bar); a mode the user picked in Configs > UI (one row,
            // two rows, Clean) stays libs:ui-nav-bars's, as before
            val store = LocalNavModeStore.current ?: rememberNavModePrefs()
            val sideMenu = wiki.effective(store).rows == SecondRowMode.SideMenu && NavUiDecl.plan(currentNavUi().mode).secondRow
            if (sideMenu) {
                val tree = remember(wiki, data) { LibraryTree.build(wiki, data) }
                var menu by remember { mutableStateOf(MenuState(expanded = WikiMenu.expandLevels(LibraryTree.build(wiki, null), 2))) }
                val open = store.sideExpanded() ?: false
                WikiSideLayout(
                    tree, menuId, nav.tabs[menuId], menu, onMenu = { menu = it },
                    open = open, onOpen = { store.setSideExpanded(it) },
                    onPick = { n -> n.page?.let { pg -> n.tab?.let { nav.tabs[pg] = it }; page = pg; store.setSideExpanded(false) } },
                    modifier = Modifier.fillMaxSize().testTag(Tags.MENU),
                    content = body,
                )
            } else NavSectionLayout(
                wiki, menuId,
                onSelect = { page = it.id },
                modifier = Modifier.fillMaxSize().testTag(Tags.MENU),
                content = body,
            )
        }
    }
}
