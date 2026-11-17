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
import com.diegonmarcos.cloudwiki.Decl
import com.diegonmarcos.cloudwiki.R
import com.diegonmarcos.cloudwiki.WikiStart
import com.diegonmarcos.cloudwiki.WikiStore
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
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
    fun chip(tag: String): String = "wiki_chip:$tag"
    fun result(id: String): String = "wiki_result:$id"
}

/**
 * The shell: the shared first row (Profile opens Cloud Account, the gear opens the fleet's Configs General | Perms | About) over ONE collapsed left
 * side menu for every page (libs:ui-nav-bars NavSectionLayout over the `wiki` section, vertical_side_menu, four groups). There is NO bottom nav.
 * Which topics and groups exist is build.json::ui, read through [Decl.nav]; a topic's subpage tabs, the pages and the tags are the runtime dataset
 * wiki (served by Cloud Store), drawn by [WikiPage].
 */
@Composable
fun WikiShell(start: WikiStart = WikiStart()) {
    val ctx = LocalContext.current
    val data by remember { WikiStore.observe(ctx.applicationContext) }.collectAsState(initial = null)
    val wiki = Decl.nav.section(Decl.SECTION_WIKI) ?: return
    var page by rememberSaveable { mutableStateOf(start.page ?: start.tab?.substringBefore('.')) }
    val nav = remember { WikiNav().also { n -> start.tab?.let { t -> n.tabs[t.substringBefore('.')] = t }; n.tags.addAll(start.tags) } }
    val pageId = page?.takeIf { p -> wiki.allPages().any { it.id == p } } ?: wiki.pages.first().id

    Box(
        Modifier.fillMaxSize().themeBackground(dark = true)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).union(WindowInsets.navigationBars)),
    ) {
        NavBarTopFirstRowScaffold(
            title = wiki.pages.firstOrNull { it.id == pageId }?.label ?: stringResource(R.string.app_name),
            hierarchy = titleHierarchy(wiki, pageId),
            // #1483 every declared section is a peer in the title dropdown: a user reaches every page from it
            sections = Decl.nav.sections,
            onNavigate = { e -> if (e.depth > 0) page = e.id },
            onAccount = rememberAccountLauncher(),
            onConfigs = { FleetConfigsEntries.open(ctx, FleetConfigsPage.General) },
            dark = true,
        ) {
            NavSectionLayout(
                wiki, pageId,
                onSelect = { page = it.id },
                modifier = Modifier.fillMaxSize().testTag(Tags.MENU),
            ) {
                Box(Modifier.fillMaxSize().testTag(Tags.SHELL)) {
                    WikiPage(data, wiki, pageId, nav, onTopic = { page = it })
                }
            }
        }
    }
}
