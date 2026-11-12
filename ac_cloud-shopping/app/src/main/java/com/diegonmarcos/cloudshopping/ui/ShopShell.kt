package com.diegonmarcos.cloudshopping.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudshopping.Decl
import com.diegonmarcos.cloudshopping.R
import com.diegonmarcos.cloudshopping.ShopStore
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "shop_shell"
    const val MENU = "shop_menu"
    const val PAGE = "shop_page"
    const val TABS = "shop_tabs"
    const val ROWS = "shop_rows"
    const val EMPTY = "shop_empty"
    const val SEARCH = "shop_search"
    fun row(id: String): String = "shop_row:$id"
}

/**
 * The shell: the shared first row (Profile opens Cloud Account, the gear opens the fleet's Configs General | Perms | About), ONE collapsed
 * left side menu for every page (libs:ui-nav-bars NavSectionLayout over the `shop` section, vertical_side_menu, the owner's five groups) and a bottom
 * nav with exactly one item, Search, drawn without a label. Which pages and groups exist is build.json::ui, read through [Decl.nav]; what a page
 * shows (its filter tabs and rows) is assets/shop/shop.json, drawn by [ShopPage].
 */
@Composable
fun ShopShell() {
    val ctx = LocalContext.current
    val data = remember { ShopStore.load(ctx.applicationContext) }
    val nav = Decl.nav
    val shop = nav.section(Decl.SECTION_SHOP) ?: return
    val groupOf = remember { Decl.groupOf() }
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_SHOP) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val searching = section == Decl.SECTION_SEARCH
    val pageId = page?.takeIf { p -> shop.allPages().any { it.id == p } } ?: shop.pages.first().id
    val entries = nav.islandEntries().map { if (data.bottomLabels) it else it.copy(label = "") }
    BackHandler(enabled = searching) { section = Decl.SECTION_SHOP }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = (if (searching) nav.section(Decl.SECTION_SEARCH)?.label else shop.pages.firstOrNull { it.id == pageId }?.label) ?: stringResource(R.string.app_name),
            hierarchy = titleHierarchy(shop, if (searching) null else pageId),
            // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
            sections = nav.sections,
            onNavigate = { e -> section = Decl.SECTION_SHOP; if (e.depth > 0) page = e.id },
            onAccount = rememberAccountLauncher(),
            onConfigs = { FleetConfigsEntries.open(ctx, FleetConfigsPage.General) },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = entries,
                selectedId = section.takeIf { s -> entries.any { it.id == s } },
                onSelect = { section = it.id },
                modifier = Modifier.testTag(Tags.SHELL),
            ) {
                // The collapsed side menu stays in the Search view too: a pick there leaves the search for that page.
                NavSectionLayout(
                    shop, if (searching) null else pageId,
                    onSelect = { section = Decl.SECTION_SHOP; page = it.id },
                    modifier = Modifier.fillMaxSize().testTag(Tags.MENU),
                ) {
                    ShopPage(data, groupOf, shop, if (searching) null else pageId)
                }
            }
        }
    }
}
