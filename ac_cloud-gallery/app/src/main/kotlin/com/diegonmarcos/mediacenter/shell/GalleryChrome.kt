/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.ui.theme.isDarkTheme
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.themebg.themeBackground

/** The selected sub-page of each tab that has a second row (Maps, Objects), kept while the tab is off screen. */
@Stable
class GalleryPageState {
    private val selected = mutableStateMapOf<String, String>()

    /** The selected page id of [tabId], or [default] (the first declared page) when none was picked. */
    fun pageOf(tabId: String, default: String? = null): String? = selected[tabId] ?: default

    fun select(tabId: String, pageId: String) {
        selected[tabId] = pageId
    }
}

val LocalGalleryPages = compositionLocalOf { GalleryPageState() }

/** Test tags of the Gallery shell (the runtime nav walk finds the bars by the shared tags; these name this app's own parts). */
object GalleryShellTags {
    const val SHELL = "gallery_shell"
    const val PAGES = "gallery_pages"
}

/**
 * The Gallery shell on the shared nav bars: first row (breadcrumb title, AI, Profile, Configs) in
 * NavBarTopFirstRowScaffold, which owns the ONE scroll connection; the bottom nav
 * (Screenshots, Downloads, Camera, Home, Timeline, Maps, Objects) in NavBarBottomHost; and the
 * tab's sub-pages as the second row. [content] is the tab's page; it is the only scroller below
 * the chrome.
 */
@Composable
fun GalleryChrome(
    tab: GalleryTab,
    pages: GalleryPageState,
    onSelectTab: (GalleryTab) -> Unit,
    onConfigs: () -> Unit,
    onSearch: () -> Unit,
    content: @Composable () -> Unit,
) {
    val nav = GalleryDeclarations.nav
    val section = nav.section(tab.id)
    val dark = isDarkTheme()
    val entries = nav.islandEntries()
    val sectionPages = section?.pages.orEmpty()
    val page = pages.pageOf(tab.id, sectionPages.firstOrNull()?.id)
    // #1697 the gear opens the fleet Configs strip (General | Agents, Perms, Logcat, X-Ray App, About); the Gallery's own settings
    // screen (the former gear target) stays one tap away: General > App.
    var fleetConfigsOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (fleetConfigsOpen) com.diegonmarcos.superapp.fleetconfigs.FleetConfigsScreen(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage.General, dark = dark, onDismiss = { fleetConfigsOpen = false }, onAppConfigs = onConfigs)
    CompositionLocalProvider(LocalGalleryPages provides pages) {
        Box(
            Modifier.fillMaxSize().themeBackground(dark)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .testTag(GalleryShellTags.SHELL),
        ) {
            NavBarTopFirstRowScaffold(
                title = section?.label ?: stringResource(R.string.app_name),
                hierarchy = section?.let { titleHierarchy(it, page) }.orEmpty(),
                onNavigate = { e ->
                    GalleryTab.ofId(e.sectionId)?.let(onSelectTab)
                    if (e.depth > 0) pages.select(e.sectionId, e.id)
                },
                onAccount = rememberAccountLauncher(),
                onConfigs = { fleetConfigsOpen = true },
                onAi = onSearch,
                sections = nav.sections,  // #1483 every declared section, bar and off-bar
                dark = dark,
            ) {
                NavBarBottomHost(
                    entries = entries,
                    selectedId = tab.id,
                    onSelect = { GalleryTab.ofId(it.id)?.let(onSelectTab) },
                    dark = dark,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (sectionPages.size > 1) {
                            NavBarTopSecondRow(
                                pages = sectionPages,
                                selectedId = page,
                                onSelect = { pages.select(tab.id, it.id) },
                                modifier = Modifier.fillMaxWidth().testTag(GalleryShellTags.PAGES),
                                dark = dark,
                                underTopChrome = false,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    }
                }
            }
        }
    }
}
