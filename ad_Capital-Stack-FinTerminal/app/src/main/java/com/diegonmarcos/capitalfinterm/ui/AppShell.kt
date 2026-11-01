package com.diegonmarcos.capitalfinterm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.BuildConfig
import com.diegonmarcos.capitalfinterm.Decl
import com.diegonmarcos.capitalfinterm.FinNavStack
import com.diegonmarcos.capitalfinterm.FinSearchIndex
import com.diegonmarcos.capitalfinterm.Pages
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.capitalfinterm.ui.fin.FinSearch
import com.diegonmarcos.capitalfinterm.ui.fin.LocalFinPalette
import com.diegonmarcos.capitalfinterm.ui.fin.LocalReduceMotion
import com.diegonmarcos.superapp.bottomnav.LocalNavUiDefault
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRows
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "app_shell"
    const val PAGE = "app_page"
    const val TICKER = "holdings_ticker"
    const val QUANTITY = "holdings_quantity"
    const val COST = "holdings_cost"
    const val ADD_SHEET = "holdings_add_sheet"
    const val CONTROL = "container_control"
    const val MESH_ONLY = "container_mesh_only"
    fun action(id: String): String = "container_action:$id"
    fun entry(title: String): String = "entry:$title"
    fun panel(id: String): String = "panel:$id"
    fun source(id: String): String = "source:$id"
    fun key(id: String): String = "key:$id"
}

/**
 * The shell (UI spec 4): the shared first row and bottom island from libs, in the mode build.json::ui.nav_ui declares (Clean: the
 * hamburger drawer lists the tab's dashboards, generated from ui.stacks), each tab with its own back stack ([FinNavStack]: the tab's
 * list, the default dashboard, a quote pushed on top), the search overlay, the flat terminal canvas (the fleet gradient only behind
 * About and Config). Which sections exist is build.json::ui through [Decl.nav]; what a section shows is ui.stacks, drawn by [SectionPage].
 */
@Composable
fun AppShell(startDashboard: String? = null) {
    val ctx = LocalContext.current
    val nav = Decl.nav
    val dashboards = remember { AssetData.dashboards(ctx) }
    val homeSection = nav.default()?.id ?: Decl.SECTION_ABOUT
    var section by rememberSaveable { mutableStateOf(homeSection) }
    var stacks by rememberSaveable { mutableStateOf(mapOf<String, List<String>>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    // #1399 every section opens on its Homepage (the stack's `list`); the one-row nav lists Home and every sub-page.
    fun stackOf(sec: String): List<String> = stacks[sec] ?: FinNavStack.initial()
    fun setStack(sec: String, s: List<String>) { stacks = stacks + (sec to s) }
    val stack = stackOf(section)
    val top = stack.last()
    val sec = nav.section(section)
    val finNav = remember(section, stacks) {
        object : FinNav {
            override fun openDashboard(id: String) {
                val owner = dashboards.firstOrNull { it.id == id }?.tab?.takeIf { nav.section(it) != null } ?: section
                section = owner
                setStack(owner, FinNavStack.open(stackOf(owner), id))
            }
            override fun openQuote(symbol: String) { setStack(section, FinNavStack.quote(stackOf(section), symbol)) }
            override fun openSources() { section = Decl.SECTION_CONFIG }
            override fun search() { searching = true }
        }
    }
    /** A sub-page of the one-row nav (or a Homepage card): Home, a dashboard (by its slug) or a non-dashboard page. */
    fun openPage(sec: String, id: String) {
        val e = Pages.entryOf(Pages.entries(Decl.stacks, sec), id)
        setStack(sec, when {
            Pages.isHome(sec, id) || e == null -> FinNavStack.LIST_ONLY
            e.kind == Pages.KIND_DASHBOARD -> FinNavStack.open(stackOf(sec), e.dashboard)
            else -> FinNavStack.openPage(id)
        })
    }
    LaunchedEffect(startDashboard) {
        if (startDashboard == null) return@LaunchedEffect
        // #1399 a removed dashboard's deep link (ui.redirects, e.g. FI Market Monitor) opens its section's Homepage.
        val moved = Pages.redirect(Decl.redirects, startDashboard)
        if (moved != null && nav.section(moved.first) != null) { section = moved.first; openPage(moved.first, moved.second) }
        else if (dashboards.any { it.id == startDashboard }) finNav.openDashboard(startDashboard)
        // a section id (`--es dashboard fi`) opens that section on its Homepage (the CI emulator nav walk)
        else if (nav.section(startDashboard) != null) { section = startDashboard; setStack(startDashboard, FinNavStack.LIST_ONLY) }
    }
    // Back (UI spec 4.4): search -> quote pops -> a sub-page -> the section's Homepage -> the home tab -> exit.
    val back = FinNavStack.back(stack, section == homeSection)
    BackHandler(enabled = back != null) {
        when (val b = back) {
            null -> Unit
            FinNavStack.HOME -> section = homeSection
            else -> setStack(section, b)
        }
    }
    val terminal = section != Decl.SECTION_ABOUT && section != Decl.SECTION_CONFIG
    val p = LocalFinPalette.current
    val canvas = if (terminal) Modifier.background(p.canvas) else Modifier.themeBackground(dark = true)
    CompositionLocalProvider(LocalNavUiDefault provides NavUiDecl.fromBuildConfig(BuildConfig.UI_NAV_UI_B64)) {
        Box(Modifier.fillMaxSize().then(canvas).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
            NavBarTopFirstRowScaffold(
                title = sec?.label ?: stringResource(R.string.app_name),
                hierarchy = sec?.let { titleHierarchy(it, FinNavStack.selectedPage(stack, section)) }.orEmpty(),
                onNavigate = { e ->
                    if (e.depth == 0) {
                        // The breadcrumb's tab word: the tab's dashboard list (UI spec 3); another tab: switch to it.
                        if (e.sectionId == section) setStack(section, FinNavStack.LIST_ONLY) else section = e.sectionId
                    } else { section = e.sectionId; openPage(e.sectionId, e.id) }
                },
                onAccount = rememberAccountLauncher(),
                onConfigs = { section = Decl.SECTION_CONFIG },
                dark = true,
                sections = nav.sections,
                leaf = FinNavStack.symbolOf(top),
            ) {
                NavBarBottomHost(
                    entries = nav.islandEntries(),
                    selectedId = section,
                    onSelect = { section = it.id },
                    modifier = Modifier.testTag(Tags.SHELL),
                ) {
                    val still = LocalReduceMotion.current
                    Column(Modifier.fillMaxSize()) {
                    // #1399 the fleet nav lib's one-row mode: ONE horizontally scrollable row of the section's sub-pages, Home first.
                    if (sec != null && sec.pages.isNotEmpty()) NavBarTopSecondRows(sec, FinNavStack.selectedPage(stack, section), onSelect = { openPage(section, it.id) }, underTopChrome = false)
                    AnimatedContent(
                        targetState = section to top,
                        transitionSpec = {
                            val pushQuote = FinNavStack.symbolOf(targetState.second) != null && targetState.first == initialState.first
                            if (still || !pushQuote) fadeIn(androidx.compose.animation.core.tween(100)) togetherWith fadeOut(androidx.compose.animation.core.tween(100))
                            else slideInHorizontally(androidx.compose.animation.core.tween(200)) { it } togetherWith fadeOut(androidx.compose.animation.core.tween(200))
                        },
                        label = "screen",
                        modifier = Modifier.weight(1f),
                    ) { (s, _) ->
                        SectionPage(s, stackOf(s), finNav, onSelectList = { id -> setStack(s, FinNavStack.open(stackOf(s), id)) }, onOpenConfig = { section = Decl.SECTION_CONFIG },
                            onOpenPage = { id -> openPage(s, id) })
                    }
                    }
                }
            }
        }
    }
    if (searching) {
        val catalogue = remember(searching) {
            val box = EncryptedSecretBox(ctx)
            searchCatalogue(dashboards, com.diegonmarcos.capitalfinterm.Alerts.watchlist(box.get(KEY_WATCHLIST)))
        }
        FinSearch(catalogue, onDismiss = { searching = false }) { item ->
            searching = false
            if (item.kind == FinSearchIndex.KIND_DASHBOARD) finNav.openDashboard(item.target) else finNav.openQuote(item.symbol)
        }
    }
}
