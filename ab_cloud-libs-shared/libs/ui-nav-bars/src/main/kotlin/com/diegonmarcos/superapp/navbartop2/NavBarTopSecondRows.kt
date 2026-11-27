package com.diegonmarcos.superapp.navbartop2

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.diegonmarcos.superapp.navbartop1.BelowAnchorFit
import com.diegonmarcos.superapp.navbartop1.dropdownAnchor
import com.diegonmarcos.superapp.navbartop1.dropdownBottomReservePx
import com.diegonmarcos.superapp.navbartop1.dropdownMaxHeight
import com.diegonmarcos.superapp.navbartop1.rememberDropdownAnchor
import androidx.compose.ui.window.PopupProperties
import com.diegonmarcos.superapp.bottomnav.ChromeBarVisibility
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.NavGroup
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.currentNavUi
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.TabSurface
import com.diegonmarcos.superapp.themebg.ThemeBackground

/** Test tags of the many-rows second row; one set per group id. */
public object SecondRowGroupTags {
    public fun row(groupId: String): String = "navtop2_group_$groupId"
    /** The group label that opens the index dropdown. */
    public fun index(groupId: String): String = "navtop2_group_index_$groupId"
    public fun divider(groupId: String): String = "navtop2_group_divider_$groupId"
    public fun item(pageId: String): String = "navtop2_group_item_$pageId"
    /** The ONE surface all group rows share. */
    public const val BOX: String = "navtop2_groups_box"
    public const val DROPDOWN: String = "navtop2_group_dropdown"
}


/**
 * THE second top row of an app, in the mode its section declares (`ui.sections[].second_row`):
 *  - [SecondRowMode.OneRow] (default) and [SecondRowMode.SideMenu] (drawn by [NavSectionLayout], not by this bar): [NavBarTopSecondRow] over the section pages;
 *  - [SecondRowMode.ManyRows]: ONE ROW PER GROUP ([NavSection.rowGroups]): the group's label as a clickable index dropdown
 *    listing that group's pages, a "|" divider, then that group's pages as horizontally scrollable icon chips (the selected
 *    one highlighted and named). Rows compact with the shared scroll collapse like [NavBarTopSecondRow].
 * [selectedId] is the shown page's id (a leaf); a pick from the chips or the dropdown calls [onSelect].
 * [onGroupIndex] (#1298) is asked first when a group label is tapped: true = the app opened its own surface for that
 * group (Cloud Me's Proj opens the whole repo outline), so the default page-list dropdown stays closed.
 */
@Composable
public fun NavBarTopSecondRows(
    section: NavSection,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    onReselect: (NavPage) -> Unit = {},
    underTopChrome: Boolean = true,
    flushTop: Boolean = false,
    surface: TabSurface? = null,
    iconFor: @Composable (NavPage) -> Painter? = { null },
    compact: Boolean = LocalNavChromeCollapse.current?.collapsed ?: false,
    hidden: Boolean = LocalNavChromeCollapse.current?.hidden ?: false,
    onGroupIndex: (NavGroup) -> Boolean = { false },
    /** #1982 pages marked selected besides [selectedId]: rows that filter independently (Cloud C3 Containers: Projects AND VM) each keep their own selection. */
    selectedIds: Set<String> = emptySet(),
) {
    if (section.rows != SecondRowMode.ManyRows) {
        NavBarTopSecondRow(section.pages, selectedId, onSelect, modifier, dark, onReselect, underTopChrome, flushTop, surface, iconFor, compact, hidden)
        return
    }
    val groups = section.rowGroups()
    if (groups.isEmpty()) return
    ReportShownPage(selectedId)
    // A page asked for from the first row's dropdown: select it when one of the groups holds it.
    val requests = LocalSubPageRequests.current
    val pending = requests?.pending
    LaunchedEffect(pending, groups) {
        val want = pending ?: return@LaunchedEffect
        val target = groups.firstNotNullOfOrNull { g -> g.pages.firstOrNull { it.holds(want) } } ?: return@LaunchedEffect
        requests?.consume()
        if (target.id != selectedId) onSelect(target)
    }
    // Clean mode (NavUiMode): the second row is GONE, its pages live in the left drawer; the pick above is still answered.
    if (NavUiDecl.plan(currentNavUi().mode).secondRow.not()) return
    val palette = when (surface) {
        TabSurface.Light -> ThemeBackground.colors(false)
        TabSurface.Dark -> ThemeBackground.colors(true)
        null -> ThemeBackground.colors(dark)
    }
    val density = LocalDensity.current
    val live = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val liveTop = if (underTopChrome && !flushTop) live.getTop(density) else 0
    val top = (if (flushTop) 0.dp else SecondRowSpec.TOP_INSET) + with(density) { liveTop.toDp() }
    val strip = RoundedCornerShape(SecondRowSpec.STRIP_RADIUS)
    val tab = RoundedCornerShape(SecondRowSpec.TAB_RADIUS)
    val tabHeight by animateDpAsState(if (compact) SecondRowSpec.COMPACT_TAB_HEIGHT else SecondRowSpec.TAB_HEIGHT, label = "secondrows_tab_height")
    // Column-aligned "|": the label column is as wide as the WIDEST group label (measured), so the dividers line up like a table column.
    val measurer = rememberTextMeasurer()
    val labelWidthPx = groupLabelColumnWidth(groups.map { measurer.measure(groupIndexText(it), GroupLabelStyle, maxLines = 1, softWrap = false).size.width })
    val labelWidth = with(density) { labelWidthPx.toDp() } + SecondRowSpec.SELECTED_PAD_H * 2
    ChromeBarVisibility(hidden = hidden) {
    Column(modifier.fillMaxWidth().padding(top = top, bottom = SecondRowSpec.BOTTOM_INSET, start = SecondRowSpec.SIDE, end = SecondRowSpec.SIDE)) {
        // ONE shared surface for every group row (not a box per row).
        Column(
            Modifier.fillMaxWidth().testTag(SecondRowGroupTags.BOX)
                .clip(strip).background(palette.tabStrip, strip).border(1.dp, palette.islandBorder, strip)
                .padding(vertical = SecondRowSpec.STRIP_PAD_V),
        ) {
            groups.forEach { g ->
                Row(Modifier.fillMaxWidth().testTag(SecondRowGroupTags.row(g.id)), verticalAlignment = Alignment.CenterVertically) {
                    GroupIndex(g, selectedId, onSelect, palette.island, palette.onIsland, palette.accentOnIsland, tabHeight, labelWidth, onGroupIndex)
                    Text("|", Modifier.testTag(SecondRowGroupTags.divider(g.id)), color = palette.inkDim)
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        g.pages.forEachIndexed { i, page ->
                            val on = page.id == selectedId || page.id in selectedIds
                            // A declared boundary inside the row (`separator_before`): a "|" like the label's.
                            if (i > 0 && page.separatorBefore) Text("|", color = palette.inkDim)
                            SecondRowTab(
                                page, i, g.pages.size, on,
                                // Default state: EVERY page shows its name; only the HIDDEN/SHRUNK states fold the non-selected ones.
                                named = !compact || on,
                                palette = palette, tabHeight = tabHeight, tab = tab, iconFor = iconFor,
                                onClick = { if (on) onReselect(page) else onSelect(page) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

/** The text of a group's index label (name and the dropdown caret). */
internal fun groupIndexText(group: NavGroup): String = group.label + " \u25BE"

/** The label column's width: the widest measured label, so every row's "|" sits at the same x. */
public fun groupLabelColumnWidth(labelWidthsPx: List<Int>): Int = labelWidthsPx.maxOrNull() ?: 0

private val GroupLabelStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

/** The group label: a tap opens the dropdown listing the group's pages (a bounded, plain list: its own scroll is the only one). */
@Composable
private fun GroupIndex(
    group: NavGroup,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    surface: androidx.compose.ui.graphics.Color,
    ink: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    height: Dp,
    width: Dp,
    onGroupIndex: (NavGroup) -> Boolean = { false },
) {
    var open by remember { mutableStateOf(false) }
    // The index grows to its list, capped at the room below it (NavDropdownSizing): no fixed height, scrolls only past the cap.
    val anchor = rememberDropdownAnchor()
    Box(Modifier.dropdownAnchor(anchor)) {
        Box(
            Modifier.width(width).height(height).clip(RoundedCornerShape(SecondRowSpec.TAB_RADIUS))
                .clickable(role = Role.Button) { if (!onGroupIndex(group)) open = true }
                
                .testTag(SecondRowGroupTags.index(group.id)),
            contentAlignment = Alignment.Center,
        ) { Text(groupIndexText(group), color = ink, style = GroupLabelStyle, maxLines = 1, softWrap = false) }
        if (open) Popup(
            popupPositionProvider = dropdownBottomReservePx().let { r -> remember(r) { BelowAnchorFit(r) } },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                color = surface, shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp,
                modifier = Modifier.width(240.dp).heightIn(max = dropdownMaxHeight(anchor)).testTag(SecondRowGroupTags.DROPDOWN),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    group.pages.forEach { p ->
                        val on = p.id == selectedId
                        Text(
                            p.label,
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = false; onSelect(p) }
                                .padding(horizontal = 16.dp, vertical = 12.dp).testTag(SecondRowGroupTags.item(p.id)),
                            color = if (on) accent else ink,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

