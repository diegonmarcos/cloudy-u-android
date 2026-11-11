package com.diegonmarcos.superapp.navbartop2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.diegonmarcos.superapp.bottomnav.ChromeBarVisibility
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.currentNavUi
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.bottomnav.TabSurface
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors

/**
 * The second row's sizes (#1154). It holds the CHILDREN of the bottom nav's page, so it is drawn clearly smaller
 * than [com.diegonmarcos.superapp.navbarbottom.BottomSpec]: a tab is [TAB_HEIGHT] against the nav's 56dp cell
 * (0.64), its glyph [ICON] against 24dp (0.67) and its name [LABEL] against 12sp (0.75, above the 8sp legibility
 * floor of the fleet). [SCALE_MAX] is the ratio ceiling NavBarTopSecondRowTest holds all three to.
 */
public object SecondRowSpec {
    public val TAB_HEIGHT: Dp = 36.dp
    /** Scrolled down (#1157): icons only, a shorter tab. */
    public val COMPACT_TAB_HEIGHT: Dp = 28.dp
    public val ICON: Dp = 16.dp
    public val LABEL: TextUnit = 9.sp
    public const val SCALE_MAX: Float = 0.75f
    public val STRIP_RADIUS: Dp = 14.dp
    /** The selected tab is a rounded RECTANGLE, never a circle or a full pill. */
    public val TAB_RADIUS: Dp = 10.dp
    public val TAB_MIN_WIDTH: Dp = 40.dp
    public val SELECTED_PAD_H: Dp = 12.dp
    public val IDLE_PAD_H: Dp = 8.dp
    public val STRIP_PAD_V: Dp = 4.dp
    public val SIDE: Dp = 8.dp
    public val TOP_INSET: Dp = 10.dp
    public val BOTTOM_INSET: Dp = 10.dp
}

/** The strip's test tags: the SAME strings as libs:bottomnav's [PageTabsTags], so an app's tests that find a tab by them still do. */
public object SecondRowTags {
    public const val STRIP: String = PageTabsTags.STRIP
    public fun tab(id: String): String = PageTabsTags.tab(id)
    public fun icon(id: String): String = PageTabsTags.icon(id)
    public fun label(id: String): String = PageTabsTags.label(id)
}

/**
 * A sub-page asked for from OUTSIDE the strip (the first row's hierarchy dropdown). The strip of the page shown selects it when
 * it holds it, or the tab that contains it (a container) and leaves the request pending for the strip one level down, so
 * a pick of "Buro > Fin > Accounts" walks the strips without any app state being hoisted.
 */
public class SubPageRequests {
    public var pending: String? by mutableStateOf(null)
        private set
    public fun request(pageId: String) { pending = pageId; lastRequested = pageId }
    public fun consume() { pending = null }
    private var lastRequested: String? by mutableStateOf(null)

    // The page each strip on screen now shows: the first row's title path reads it, so the path follows a pick from a strip or the
    // dropdown without the app hoisting its sub-page state. Strips on one page that show pages of the SAME depth (two pill rows) are
    // ordered by when each last changed, and the page the last request named goes last: the title follows the strip the reader used.
    private val shownBy = androidx.compose.runtime.mutableStateMapOf<Any, String>()
    private val changeOrder = ArrayList<Any>()
    public fun reportShown(strip: Any, pageId: String?) {
        if (pageId == null) { clearShown(strip); return }
        if (shownBy[strip] != pageId) { changeOrder.remove(strip); changeOrder.add(strip); if (pageId != lastRequested) lastRequested = null }
        shownBy[strip] = pageId
    }
    public fun clearShown(strip: Any) { shownBy.remove(strip); changeOrder.remove(strip) }
    /** The ids the strips on screen show (outermost and inner strips alike), the one the reader used last at the end. */
    public fun shownIds(): List<String> {
        val ordered = changeOrder.mapNotNull { shownBy[it] }.ifEmpty { shownBy.values.toList() }
        val last = lastRequested
        return if (last != null && last in ordered) ordered.filter { it != last } + last else ordered
    }
}

/** A strip tells the enclosing [SubPageRequests] which page it shows, for as long as it is composed. */
@Composable
internal fun ReportShownPage(selectedId: String?) {
    val requests = LocalSubPageRequests.current ?: return
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(requests, key, selectedId) {
        requests.reportShown(key, selectedId)
        onDispose { requests.clearShown(key) }
    }
}

/** The enclosing [SubPageRequests]; null outside libs:ui-nav-bars's scaffold (a strip then ignores requests). */
public val LocalSubPageRequests = staticCompositionLocalOf<SubPageRequests?> { null }

/** [id] is this page or sits anywhere below it. */
internal fun NavPage.holds(id: String): Boolean = this.id == id || pages.any { it.holds(id) }

/**
 * THE second top row of every app (#1154): the sub-pages of the bottom nav's page as small tabs. The free width is
 * shared SpaceEvenly, so one tab sits centred and several have equal gaps between them and to both borders. The
 * selected tab is a rounded rectangle filled with the SuperApp violet ([ThemeColors.tabSelected]); every tab shows its
 * name next to its glyph (#1159), and scrolled down only the selected one does. [iconFor] may override a page's glyph; by default it is the page's declared icon (SecondRowIcons).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun NavBarTopSecondRow(
    pages: List<NavPage>,
    selectedId: String?,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
    /** The app's light/dark mode: picks the shared palette (libs:theme-background). An app passes no colour. */
    dark: Boolean = true,
    onReselect: (NavPage) -> Unit = {},
    /** True: the strip clears the status bar and cutout (it sits at the very top of the window). */
    underTopChrome: Boolean = true,
    flushTop: Boolean = false,
    /** A page that is light (a white sheet): draws the light palette whatever [dark] says. Null = [dark]'s. */
    surface: TabSurface? = null,
    iconFor: @Composable (NavPage) -> Painter? = { null },
    /** Scrolled down (#1159): only the selected tab keeps its name and every tab is shorter. Default: the chrome's shared scroll state. */
    compact: Boolean = LocalNavChromeCollapse.current?.collapsed ?: false,
    /** HIDDEN scroll state (a downward drag in progress): the whole row is away. Default: the chrome's shared state. */
    hidden: Boolean = LocalNavChromeCollapse.current?.hidden ?: false,
) {
    // #1369 an app that declares nav_ui.actions = "drawer" keeps its launch pages in the Clean drawer's actions row, not here.
    val shown = NavUiDecl.stripPages(currentNavUi(), pages)
    if (shown.isEmpty()) return
    ReportShownPage(selectedId)
    val requests = LocalSubPageRequests.current
    val pending = requests?.pending
    LaunchedEffect(pending, shown, selectedId) {
        val want = pending ?: return@LaunchedEffect
        val target = shown.firstOrNull { it.holds(want) } ?: return@LaunchedEffect
        if (target.id == want) { requests?.consume(); if (target.id != selectedId) onSelect(target) }
        else if (target.id != selectedId) onSelect(target)
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
    val scroll = rememberScrollState()
    val tabHeight by animateDpAsState(if (compact) SecondRowSpec.COMPACT_TAB_HEIGHT else SecondRowSpec.TAB_HEIGHT, label = "secondrow_tab_height")
    ChromeBarVisibility(hidden = hidden) {
    BoxWithConstraints(modifier.semantics { testTagsAsResourceId = true }.fillMaxWidth().padding(top = top, bottom = SecondRowSpec.BOTTOM_INSET, start = SecondRowSpec.SIDE, end = SecondRowSpec.SIDE)) {
    Row(
        Modifier.fillMaxWidth()
            .testTag(SecondRowTags.STRIP)
            .clip(strip).background(palette.tabStrip, strip).border(1.dp, palette.islandBorder, strip)
            // A crowded row scrolls rather than clipping a tab; the free width of a roomy one is shared SpaceEvenly.
            .horizontalScroll(scroll)
            .padding(vertical = SecondRowSpec.STRIP_PAD_V)
            .widthIn(min = maxWidth)
            .selectableGroup()
            .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = shown.size) },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEachIndexed { i, page ->
            // #1630 a declared group boundary: a "|" character (not a tab) just before this page.
            if (i > 0 && page.separatorBefore) Text("|", Modifier.testTag("secondrow_sep_" + page.id), color = palette.inkDim)
            SecondRowTab(
                page, i, shown.size, page.id == selectedId,
                // #1159 at rest EVERY tab shows its name; scrolled down only the selected one does, and the tab is shorter.
                named = !compact || page.id == selectedId,
                palette = palette, tabHeight = tabHeight, tab = tab, iconFor = iconFor,
                onClick = { if (page.id == selectedId) onReselect(page) else onSelect(page) },
            )
        }
    }
    }
    }
}

/** One tab of a strip: its icon, its name when [named], the selected one a filled rounded rectangle. Shared by every second-row mode. */
@Composable
internal fun SecondRowTab(
    page: NavPage,
    index: Int,
    count: Int,
    on: Boolean,
    named: Boolean,
    palette: ThemeColors,
    tabHeight: Dp,
    tab: androidx.compose.ui.graphics.Shape,
    iconFor: @Composable (NavPage) -> Painter?,
    onClick: () -> Unit,
) {
    val ink = if (on) palette.onTabSelected else palette.inkDim
    Row(
        Modifier.defaultMinSize(minWidth = SecondRowSpec.TAB_MIN_WIDTH).height(tabHeight)
            .clip(tab)
            .then(if (on) Modifier.background(palette.tabSelected, tab) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                role = Role.Tab
                selected = on
                contentDescription = page.label
                collectionItemInfo = CollectionItemInfo(0, 1, index, 1)
            }
            .padding(horizontal = if (on) SecondRowSpec.SELECTED_PAD_H else SecondRowSpec.IDLE_PAD_H)
            .testTag(SecondRowTags.tab(page.id)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // #1157 a tab ALWAYS draws its icon: the host's painter, else the declared Material symbol (never a letter).
        val painter = iconFor(page) ?: rememberVectorPainter(SecondRowIcons.vector(page.icon))
        Icon(painter, null, Modifier.size(SecondRowSpec.ICON).testTag(SecondRowTags.icon(page.id)), tint = ink)
        AnimatedVisibility(
            named, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                page.label, Modifier.padding(start = 6.dp).testTag(SecondRowTags.label(page.id)),
                color = ink, fontSize = SecondRowSpec.LABEL, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
