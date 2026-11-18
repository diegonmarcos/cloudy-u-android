package com.diegonmarcos.cloudcanvas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.colorResource
import com.diegonmarcos.cloudcanvas.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudcanvas.CanvasStart
import com.diegonmarcos.cloudcanvas.Decl
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "canvas_shell"
    const val DRAW = "canvas_draw"
    const val PLAY = "canvas_play"
    const val ENHANCE = "canvas_enhance"
    const val MUSIC = "canvas_music"
}

/**
 * The shell: the shared first row (Profile opens Cloud Account, the gear opens the fleet's Configs General | Perms | About) over the page of the
 * selected section, and the shared bottom nav Draw | Play. Which sections exist is build.json::ui, read through [Decl.nav]. Both pages keep their
 * state in the shell, so a drawing and a mix survive a switch.
 */
@Composable
fun CanvasShell(start: CanvasStart = CanvasStart()) {
    val ctx = LocalContext.current
    val decl = Decl.nav
    var selected by rememberSaveable { mutableStateOf(start.section?.takeIf { decl.section(it) != null } ?: decl.default()?.id ?: Decl.SECTION_DRAW) }
    val section = decl.section(selected) ?: return
    val draw = remember { DrawController(ctx.applicationContext) }
    val play = remember { PlayController(ctx.applicationContext, start.demo) }
    val enhance = remember { EnhanceController(ctx.applicationContext, draw) }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavBarTopFirstRowScaffold(
                    title = section.label,
                    hierarchy = titleHierarchy(section, null),
                    sections = decl.sections,
                    onNavigate = { e -> if (e.depth == 0 && decl.section(e.sectionId) != null) selected = e.sectionId },
                    onAccount = rememberAccountLauncher(),
                    onConfigs = { FleetConfigsEntries.open(ctx, FleetConfigsPage.General) },
                    dark = true,
                ) {
                    Box(Modifier.fillMaxSize().testTag(Tags.SHELL)) {
                        when (selected) {
                            Decl.SECTION_PLAY -> PlayScreen(play, start, Modifier.testTag(Tags.PLAY))
                            Decl.SECTION_ENHANCE -> EnhanceScreen(enhance, start, Modifier.testTag(Tags.ENHANCE))
                            Decl.SECTION_MUSIC -> MusicScreen(Modifier.testTag(Tags.MUSIC))
                            else -> DrawScreen(draw, start, Modifier.testTag(Tags.DRAW))
                        }
                    }
                }
            }
            Box {
                NavBarBottom(
                    entries = decl.islandEntries(),
                    selectedId = selected,
                    onSelect = { e -> selected = e.id },
                    dark = true,
                )
                // Music Enhancer is a placeholder: its entry (the last one) is greyed out with a veil; a tap still opens its coming-soon page
                if (decl.bottomNav.lastOrNull() == Decl.SECTION_MUSIC) {
                    Row(Modifier.matchParentSize()) {
                        Spacer(Modifier.weight(0.78f))
                        Box(Modifier.weight(0.22f).fillMaxHeight().background(colorResource(R.color.app_background).copy(alpha = 0.6f)))
                    }
                }
            }
        }
    }
}
