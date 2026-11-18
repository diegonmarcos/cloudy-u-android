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
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
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
    const val BOARD = "canvas_board"
}

/**
 * The shell: the shared first row (Profile opens Cloud Account, the gear opens the fleet's Configs General | Perms | About) over the page of the
 * selected section, and the shared bottom nav Draw | Play. Which sections exist is build.json::ui, read through [Decl.nav]. Both pages keep their
 * state in the shell, so a drawing and a mix survive a switch. #1787 a section with two or more pages (Draw: Draw | Board) shows them as the
 * fleet's second row (NavBarTopSecondRow); the title hierarchy names the page.
 */
@Composable
fun CanvasShell(start: CanvasStart = CanvasStart()) {
    val ctx = LocalContext.current
    val decl = Decl.nav
    var selected by rememberSaveable { mutableStateOf(start.section?.takeIf { decl.section(it) != null } ?: decl.default()?.id ?: Decl.SECTION_DRAW) }
    val section = decl.section(selected) ?: return
    // the open page of each section with sibling pages (Draw: draw | board), kept across section switches
    var pages by rememberSaveable { mutableStateOf(mapOf<String, String>().let { m -> start.page?.let { m + (selected to it) } ?: m }) }
    val page = pages[selected]?.takeIf { p -> section.pages.any { it.id == p } } ?: section.pages.firstOrNull()?.id
    val draw = remember { DrawController(ctx.applicationContext) }
    val play = remember { PlayController(ctx.applicationContext, start.demo) }
    val enhance = remember { EnhanceController(ctx.applicationContext, draw) }
    val board = remember { BoardController(ctx.applicationContext) }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavBarTopFirstRowScaffold(
                    title = section.label,
                    hierarchy = titleHierarchy(section, page.takeIf { section.pages.size > 1 }),
                    sections = decl.sections,
                    onNavigate = { e ->
                        if (e.depth == 0 && decl.section(e.sectionId) != null) selected = e.sectionId
                        else if (e.depth >= 1 && decl.section(e.sectionId)?.pages?.any { it.id == e.id } == true) { selected = e.sectionId; pages = pages + (e.sectionId to e.id) }
                    },
                    onAccount = rememberAccountLauncher(),
                    onConfigs = { FleetConfigsEntries.open(ctx, FleetConfigsPage.General) },
                    dark = true,
                ) {
                    Column(Modifier.fillMaxSize().testTag(Tags.SHELL)) {
                        if (section.pages.size > 1) NavBarTopSecondRow(
                            pages = section.pages,
                            selectedId = page,
                            onSelect = { p -> pages = pages + (selected to p.id) },
                            underTopChrome = false,
                            dark = true,
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (selected) {
                                Decl.SECTION_PLAY -> PlayScreen(play, start, Modifier.testTag(Tags.PLAY))
                                Decl.SECTION_ENHANCE -> EnhanceScreen(enhance, start, Modifier.testTag(Tags.ENHANCE))
                                Decl.SECTION_MUSIC -> MusicScreen(Modifier.testTag(Tags.MUSIC))
                                else -> if (page == Decl.PAGE_BOARD) BoardScreen(board, Modifier.testTag(Tags.BOARD)) else DrawScreen(draw, start, Modifier.testTag(Tags.DRAW))
                            }
                        }
                    }
                }
            }
            // #1787 the Music Enhancer placeholder is greyed by dimming its glyph INSIDE the island (DimPainter). The old veil was a
            // translucent app-background box over the last 22% of the whole bar slot, insets included, which drew a dark block in the
            // bottom-right corner of every page (outside the island, over the gesture bar).
            NavBarBottom(
                entries = decl.islandEntries().map { e -> if (e.id == Decl.SECTION_MUSIC) e.copy(icon = DimPainter(e.icon, PLACEHOLDER_ALPHA)) else e },
                selectedId = selected,
                onSelect = { e -> selected = e.id },
                dark = true,
            )
        }
    }
}

/** How visible a placeholder's glyph is in the bottom nav (the Music Enhancer entry, coming soon). */
const val PLACEHOLDER_ALPHA = 0.38f

/** A painter drawn at [alpha]: greys one bottom-nav glyph without painting anything outside the island. The tint (colour filter) passes through. */
class DimPainter(private val inner: androidx.compose.ui.graphics.painter.Painter, private val alpha: Float) : androidx.compose.ui.graphics.painter.Painter() {
    private var filter: androidx.compose.ui.graphics.ColorFilter? = null
    override val intrinsicSize: androidx.compose.ui.geometry.Size get() = inner.intrinsicSize
    override fun applyColorFilter(colorFilter: androidx.compose.ui.graphics.ColorFilter?): Boolean { filter = colorFilter; return true }
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() { with(inner) { draw(size, alpha, filter) } }
}
