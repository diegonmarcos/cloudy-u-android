package com.diegonmarcos.cgomniverse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.Launch
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.cgomniverse.Tabs
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.themebg.themeBackground

/** Galaxy's tabs as the pages see them: the state, and what opening a world, selecting, closing and "+" do. */
class GalaxyTabs(
    val state: Tabs.State,
    val open: (World) -> Unit,
    val select: (String) -> Unit,
    val close: (String) -> Unit,
    val add: () -> Unit,
)

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "app_shell"
    const val PAGE = "app_page"
    const val AVATAR = "avatar"
    const val AVATAR_SCENE = "avatar_scene"
    const val AVATAR_RESET = "avatar_reset"
    const val STORE = "store"
    const val STORE_COUNT = "store_count"
    const val GALAXY = "galaxy"
    const val GALAXY_TABS = "galaxy_tabs"
    const val GALAXY_ADD = "galaxy_add"
    const val GALAXY_EMPTY = "galaxy_empty"
    const val WORLD = "world"
    const val WORLD_WEB = "world_web"
    const val WORLD_RELOAD = "world_reload"
    const val WORLD_DESKTOP = "world_desktop"
    const val WORLD_GPU = "world_gpu"
    const val WORLD_GPU_BROWSER = "world_gpu_browser"
    const val WORLD_GPU_STAY = "world_gpu_stay"
    const val WORLD_BROWSER = "world_browser"
    const val WORLD_VR = "world_vr"
    const val WORLD_FALLBACK = "world_fallback"
    const val WORLD_FALLBACK_BROWSER = "world_fallback_browser"
    const val WORLD_OFFLINE = "world_offline"
    const val WORLD_OFFLINE_BROWSER = "world_offline_browser"
    const val WORLD_OFFLINE_WAYBACK = "world_offline_wayback"
    fun statusBadge(id: String): String = "status_badge:$id"
    fun entry(title: String): String = "entry:$title"
    fun avatarGroup(id: String): String = "avatar_group:$id"
    fun avatarOption(group: String, id: String): String = "avatar_option:$group:$id"
    fun chip(group: String, chip: String): String = "chip:$group:$chip"
    fun world(id: String): String = "world:$id"
    fun openGalaxy(id: String): String = "open_galaxy:$id"
    fun openBrowser(id: String): String = "open_browser:$id"
    fun galaxyTab(id: String): String = "galaxy_tab:$id"
    fun galaxyTabClose(id: String): String = "galaxy_tab_close:$id"
}

/**
 * The shell: the shared first row (Profile at the top right opens Cloud Account, the gear opens the About section), the shared
 * bottom nav, the shared page gradient and the shared scroll collapse, all from libs (#1154, #1157). Which sections exist is
 * build.json::ui, read through [Decl.nav]; what a section's page shows is ui.stacks, drawn by [SectionPage]. Galaxy's tabs ([Tabs])
 * live here and persist on the device: a world opened from the Store or a launch extra adds (or focuses) its tab and shows Galaxy.
 */
@Composable
fun AppShell(startSection: String? = null, startWorld: String? = null, incoming: String? = null, onIncoming: () -> Unit = {}) {
    val nav = Decl.nav
    val start = remember {
        Launch.resolve(startSection, startWorld, nav.sections.map { it.id }.toSet(), Decl.worlds.map { it.id }.toSet(),
            nav.default()?.id ?: Decl.SECTION_ABOUT)
    }
    var section by rememberSaveable { mutableStateOf(start.section) }
    val sec = nav.section(section)
    val ctx = LocalContext.current
    var tabs by remember {
        val saved = Tabs.decode(Web.tabs(ctx), Decl.worlds)
        mutableStateOf(start.worldId?.let { Tabs.open(saved, it) } ?: saved)
    }
    LaunchedEffect(tabs) { Web.setTabs(ctx, Tabs.encode(tabs)) }
    val galaxy = GalaxyTabs(
        state = tabs,
        open = { w -> tabs = Tabs.open(tabs, w.id); section = Decl.SECTION_GALAXY },
        select = { id -> tabs = Tabs.open(tabs, id) },
        close = { id -> tabs = Tabs.close(tabs, id) },
        add = { section = Decl.SECTION_STORE },
    )
    LaunchedEffect(incoming) {
        val w = incoming?.let { id -> Decl.worlds.firstOrNull { it.id == id } } ?: return@LaunchedEffect
        galaxy.open(w); onIncoming()
    }
    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, null) }.orEmpty(),
            onNavigate = { e -> section = e.sectionId },
            onAccount = rememberAccountLauncher(),
            onConfigs = { section = Decl.SECTION_ABOUT },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = nav.islandEntries(),
                selectedId = section,
                onSelect = { section = it.id },
                modifier = Modifier.testTag(Tags.SHELL),
            ) {
                SectionPage(section, galaxy)
            }
        }
    }
}
