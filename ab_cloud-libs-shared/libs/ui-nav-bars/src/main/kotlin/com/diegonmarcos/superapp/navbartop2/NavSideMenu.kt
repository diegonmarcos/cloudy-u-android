package com.diegonmarcos.superapp.navbartop2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.bottomnav.ChromeScrollState
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.LocalNavModeStore
import com.diegonmarcos.superapp.bottomnav.NavModeStore
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.currentNavUi
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.SideMenuItem
import com.diegonmarcos.superapp.bottomnav.SideMenuPresence
import com.diegonmarcos.superapp.bottomnav.TabSurface
import com.diegonmarcos.superapp.bottomnav.effective
import com.diegonmarcos.superapp.bottomnav.rememberNavModePrefs
import com.diegonmarcos.superapp.bottomnav.railLabel
import com.diegonmarcos.superapp.bottomnav.sideMenuItems
import com.diegonmarcos.superapp.bottomnav.sideMenuPresence
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors

/** The side menu's sizes. The panel is an OPAQUE theme surface ([ThemeColors.island]); the scrim sits over the page behind the overlay. */
public object SideMenuSpec {
    /** The semi-collapsed rail: an icon with the first letters of the name below it. */
    public val RAIL_WIDTH: Dp = 60.dp
    public val EXPANDED_WIDTH: Dp = 232.dp
    public val ITEM_HEIGHT: Dp = 44.dp
    public val RAIL_ITEM_HEIGHT: Dp = 52.dp
    public val ICON: Dp = 20.dp
    public val INDENT: Dp = 14.dp
    public val RADIUS: Dp = 14.dp
    public val SIDE: Dp = 6.dp
    /** Under this width the expanded menu overlays the page (with a scrim) instead of docking beside it. */
    public val NARROW_BELOW: Dp = 600.dp
    public const val RAIL_LETTERS: Int = 3
}

/** The side menu's test tags. */
public object SideMenuTags {
    public const val MENU: String = "navside_menu"
    public const val TOGGLE: String = "navside_toggle"
    public const val SCRIM: String = "navside_scrim"
    public const val OVERLAY: String = "navside_overlay"
    public const val DOCK: String = "navside_dock"
    public fun item(id: String): String = "navside_item_$id"
    public fun icon(id: String): String = "navside_icon_$id"
    public fun label(id: String): String = "navside_label_$id"
    public fun rail(id: String): String = "navside_rail_$id"
    public fun heading(id: String): String = "navside_heading_$id"
}

/**
 * The vertical LEFT SIDE MENU of a section's child pages ([SecondRowMode.SideMenu]). [presence] says how it is drawn: [SideMenuPresence.Expanded]
 * is a list (icon + label, children indented, a heading per declared group, the selected row highlighted); [SideMenuPresence.Rail] is the
 * semi-collapsed rail (each page its icon with the first [SideMenuSpec.RAIL_LETTERS] letters of its name below); [SideMenuPresence.Away]
 * draws nothing. The toggle at the top calls [onToggle]. The first row has no hamburger: this menu is the section's own navigation.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun NavSideMenu(
    section: NavSection,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    presence: SideMenuPresence,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    surface: TabSurface? = null,
    iconFor: @Composable (NavPage) -> Painter? = { null },
) {
    if (presence == SideMenuPresence.Away) return
    val palette = when (surface) {
        TabSurface.Light -> ThemeBackground.colors(false)
        TabSurface.Dark -> ThemeBackground.colors(true)
        null -> ThemeBackground.colors(dark)
    }
    val rail = presence == SideMenuPresence.Rail
    val shape = RoundedCornerShape(SideMenuSpec.RADIUS)
    val width by animateDpAsState(if (rail) SideMenuSpec.RAIL_WIDTH else SideMenuSpec.EXPANDED_WIDTH, label = "sidemenu_width")
    val items = remember(section) { sideMenuItems(section) }
    // The caller's modifier (and so its tag: DOCK / OVERLAY) sits on a wrapper, so the menu keeps its own MENU tag on the column.
    Box(modifier.semantics { testTagsAsResourceId = true }) {
    Column(
        Modifier.width(width).fillMaxHeight()
            .padding(start = SideMenuSpec.SIDE, top = SideMenuSpec.SIDE, bottom = SideMenuSpec.SIDE)
            .testTag(SideMenuTags.MENU)
            .clip(shape).background(palette.island, shape).border(1.dp, palette.islandBorder, shape)
            .verticalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(SideMenuSpec.ITEM_HEIGHT).clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = if (rail) "Expand menu" else "Collapse menu" }
                .testTag(SideMenuTags.TOGGLE),
            contentAlignment = if (rail) Alignment.Center else Alignment.CenterEnd,
        ) {
            Text(if (rail) "›" else "‹", Modifier.padding(horizontal = 14.dp), color = palette.inkDim, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        items.forEach { item ->
            if (item.heading) SideHeading(item, rail, palette)
            else SideRow(item, rail, item.id == selectedId, palette, iconFor, onClick = { item.page?.let(onSelect) })
        }
    }
    }
}

@Composable
private fun SideHeading(item: SideMenuItem, rail: Boolean, palette: ThemeColors) {
    if (rail) {
        Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 10.dp).background(palette.islandBorder).testTag(SideMenuTags.heading(item.id)))
    } else {
        Text(
            item.label.uppercase(), Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 4.dp).testTag(SideMenuTags.heading(item.id)),
            color = palette.inkDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SideRow(item: SideMenuItem, rail: Boolean, on: Boolean, palette: ThemeColors, iconFor: @Composable (NavPage) -> Painter?, onClick: () -> Unit) {
    val ink: Color = if (on) palette.onTabSelected else palette.inkDim
    val tab = RoundedCornerShape(SecondRowSpec.TAB_RADIUS)
    val page = item.page
    val painter = (page?.let { iconFor(it) }) ?: rememberVectorPainter(SecondRowIcons.vector(item.icon))
    val rowModifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp).fillMaxWidth()
        .clip(tab)
        .then(if (on) Modifier.background(palette.tabSelected, tab) else Modifier)
        .clickable(role = Role.Tab, onClick = onClick)
        .semantics { role = Role.Tab; selected = on; contentDescription = item.label }
        .testTag(SideMenuTags.item(item.id))
    if (rail) {
        Column(rowModifier.height(SideMenuSpec.RAIL_ITEM_HEIGHT), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painter, null, Modifier.size(SideMenuSpec.ICON).testTag(SideMenuTags.icon(item.id)), tint = ink)
            Text(
                railLabel(item.label, SideMenuSpec.RAIL_LETTERS), Modifier.testTag(SideMenuTags.rail(item.id)),
                color = ink, fontSize = 9.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            )
        }
    } else {
        Row(rowModifier.height(SideMenuSpec.ITEM_HEIGHT).padding(start = 10.dp + SideMenuSpec.INDENT * item.depth, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter, null, Modifier.size(SideMenuSpec.ICON).testTag(SideMenuTags.icon(item.id)), tint = ink)
            Text(
                item.label, Modifier.padding(start = 12.dp).testTag(SideMenuTags.label(item.id)),
                color = ink, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A section's child-page navigation in whatever mode applies, WITH the page it navigates ([content]). The mode is the section's
 * declared one ([NavSection.rows]) unless the user chose another in the app's Configs ([LocalNavModeStore]):
 *  - one row / two rows: the strip(s) above [content] ([NavBarTopSecondRows]);
 *  - side menu: the menu docked at the left of [content] (the rail on narrow phones, the expanded menu overlaying the page there and
 *    docking beside it on wide screens), following the chrome's scroll machine (away while dragging, the rail when shrunk).
 * A section with at most one page draws no navigation. A page asked for from the first row's dropdown is selected in every mode.
 */
@Composable
public fun NavSectionLayout(
    section: NavSection,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    onReselect: (NavPage) -> Unit = {},
    underTopChrome: Boolean = false,
    flushTop: Boolean = false,
    surface: TabSurface? = null,
    iconFor: @Composable (NavPage) -> Painter? = { null },
    store: NavModeStore? = LocalNavModeStore.current ?: rememberNavModePrefs(),
    content: @Composable () -> Unit,
) {
    val shown = section.effective(store)
    // Clean mode (NavUiMode): no second row and no side menu; the pages are in the left drawer.
    if (section.allPages().size <= 1 || !NavUiDecl.plan(currentNavUi().mode).secondRow) { Box(modifier) { content() }; return }
    if (shown.rows != SecondRowMode.SideMenu) {
        Column(modifier) {
            NavBarTopSecondRows(shown, selectedId, onSelect, Modifier, dark, onReselect, underTopChrome, flushTop, surface, iconFor)
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        return
    }
    SideMenuLayout(shown, selectedId, onSelect, modifier, dark, surface, iconFor, store, content)
}

@Composable
private fun SideMenuLayout(
    section: NavSection,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier,
    dark: Boolean,
    surface: TabSurface?,
    iconFor: @Composable (NavPage) -> Painter?,
    store: NavModeStore?,
    content: @Composable () -> Unit,
) {
    ReportShownPage(selectedId)
    val requests = LocalSubPageRequests.current
    val pending = requests?.pending
    LaunchedEffect(pending, section) {
        val want = pending ?: return@LaunchedEffect
        val target = section.allPages().firstOrNull { it.id == want } ?: return@LaunchedEffect
        requests?.consume()
        if (target.id != selectedId) onSelect(target)
    }
    var localExpanded by remember { mutableStateOf<Boolean?>(null) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth < SideMenuSpec.NARROW_BELOW
        // Never chosen: a wide screen docks the full menu, a narrow phone docks the rail (the expanded menu is a drawer there).
        val expandedPref = (if (store != null) store.sideExpanded() else localExpanded) ?: !narrow
        val toggle: () -> Unit = { val next = !expandedPref; if (store != null) store.setSideExpanded(next) else localExpanded = next }
        val scroll = LocalNavChromeCollapse.current?.state ?: ChromeScrollState.Default
        val presence = sideMenuPresence(expandedPref, scroll)
        // Docked: the rail always, and on a wide screen the expanded menu too; on a narrow one the expanded menu overlays instead.
        val docked = if (presence == SideMenuPresence.Expanded && narrow) SideMenuPresence.Rail else presence
        val overlay = presence == SideMenuPresence.Expanded && narrow
        Row(Modifier.fillMaxSize()) {
            // While the overlay drawer is open the rail is NOT drawn (only its width is kept): one menu at a time, never both.
            if (overlay) Spacer(Modifier.width(SideMenuSpec.RAIL_WIDTH).fillMaxHeight().testTag(SideMenuTags.DOCK))
            else AnimatedVisibility(docked != SideMenuPresence.Away, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                NavSideMenu(section, selectedId, onSelect, docked, toggle, Modifier.testTag(SideMenuTags.DOCK), dark, surface, iconFor)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
        AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = toggle).testTag(SideMenuTags.SCRIM))
        }
        AnimatedVisibility(overlay, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
            NavSideMenu(section, selectedId, { toggleAndSelect(it, onSelect, toggle) }, SideMenuPresence.Expanded, toggle, Modifier.testTag(SideMenuTags.OVERLAY), dark, surface, iconFor)
        }
    }
}

/** A pick from the overlay closes it (back to the rail) and selects. */
private fun toggleAndSelect(page: NavPage, onSelect: (NavPage) -> Unit, toggle: () -> Unit) { onSelect(page); toggle() }
