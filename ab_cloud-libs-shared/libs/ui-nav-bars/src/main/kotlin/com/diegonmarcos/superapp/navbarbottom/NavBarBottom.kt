package com.diegonmarcos.superapp.navbarbottom

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.ChromeBarVisibility
import com.diegonmarcos.superapp.bottomnav.ChromeScrollPolicy
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.LocalNavBottomClearance
import com.diegonmarcos.superapp.bottomnav.LocalNavTokens
import com.diegonmarcos.superapp.bottomnav.NavTokens
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.currentNavUi
import com.diegonmarcos.superapp.bottomnav.currentScrollPolicy
import com.diegonmarcos.superapp.bottomnav.SearchHtmlTokens
import com.diegonmarcos.superapp.bottomnav.bottomNavInsets
import com.diegonmarcos.superapp.bottomnav.chromeScroll
import com.diegonmarcos.superapp.bottomnav.navHiddenByIme
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse
import com.diegonmarcos.superapp.themebg.themeBackground

/**
 * The bottom nav's numbers and colours (#1154): Cloud Search's look (glass pill, hairline, accent-coloured selection
 * with a lifted icon and a label that appears) at the fleet's cell size. The second row is sized against these
 * (see libs:ui-nav-bars), so they are the reference of "clearly smaller".
 */
public object BottomSpec {
    public val CELL: Dp = 56.dp
    public val ICON: Dp = 24.dp
    public val LABEL: TextUnit = 12.sp
    public val HEIGHT: Dp = 64.dp
    public val RADIUS: Dp = 32.dp
    /** Scrolled down (#1159): the labels of the non-selected items fold away and the bar and its cells shrink; the selected item keeps its label. */
    public val COLLAPSED_HEIGHT: Dp = 52.dp
    public val COLLAPSED_CELL: Dp = 44.dp
    public val SIDE_GUTTER: Dp = SearchHtmlTokens.sideGutter
    public val BOTTOM_MARGIN: Dp = SearchHtmlTokens.bottomMargin
    public val MAX_WIDTH: Dp = 440.dp
    public val INNER_PAD: Dp = SearchHtmlTokens.innerPad
    public val LIFT: Dp = 6.dp
    public val LABEL_BOTTOM: Dp = 4.dp

    /** The width of one cell on a bar of [barWidth] holding [count] items: [CELL], or less when the bar is crowded. */
    public fun cellWidth(barWidth: Dp, count: Int): Dp =
        if (count <= 0) CELL else minOf(CELL, (barWidth - INNER_PAD * 2) / count)

    /** Cloud Search's coloured selection: the accent for the selected item, the slate idle ink for the rest. */
    public fun selectedInk(dark: Boolean): Color = SearchHtmlTokens.accent(dark)
    public fun idleInk(dark: Boolean): Color = SearchHtmlTokens.idle(dark)
    public fun fill(dark: Boolean): Color = SearchHtmlTokens.fill(dark)
    public fun border(dark: Boolean): Color = SearchHtmlTokens.border(dark)
}

/** The bar's test tags: the SAME strings as libs:bottomnav's [BottomNavTags] island, so an app's tests that find an item by them still do. */
public object BottomTags {
    public const val BAR: String = BottomNavTags.ISLAND
    public fun item(id: String): String = BottomNavTags.item(id)
    public fun icon(id: String): String = BottomNavTags.icon(id)
    public fun label(id: String): String = BottomNavTags.label(id)
}

/**
 * THE bottom nav of every app (#1154). Selection is Cloud Search's: the selected item's icon and label take the
 * accent colour, the icon lifts and the label rises in; the others stay in the idle ink. There is no gray circle.
 * The bar is a rounded glass pill; its cells shrink evenly when more than fit at [BottomSpec.CELL]. [collapsed] drops
 * the labels (the scroll-collapse of the old island).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun NavBarBottom(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
    /** Scrolled down (BottomNavCollapse, #1159): the non-selected labels fold away and the bar shrinks; the selected label stays. */
    collapsed: Boolean = LocalNavChromeCollapse.current?.collapsed ?: false,
    /** HIDDEN scroll state (a downward drag in progress): the whole bar is away. Default: the chrome's shared state. */
    hidden: Boolean = LocalNavChromeCollapse.current?.hidden ?: false,
) {
    // #1160 the keyboard HIDES the bar (it used to be lifted above it); the host gives the room back to the content.
    if (navHiddenByIme()) return
    // Clean mode (NavUiMode): compact and icons only, a smaller bar, so the reading area gets the room (sizes: NavTokens.clean*).
    val clean = !NavUiDecl.plan(currentNavUi().mode).bottomLabels
    val tokens = LocalNavTokens.current ?: NavTokens()
    ChromeBarVisibility(hidden = hidden) {
    Box(
        // the bar exposes its tags as resource ids wherever it is hosted (the runtime nav walk, cloud-android-nav-walk.py, reads them)
        modifier.semantics { testTagsAsResourceId = true }.fillMaxWidth()
            .windowInsetsPadding(bottomNavInsets().only(WindowInsetsSides.Bottom))
            .padding(bottom = if (clean) tokens.cleanBottomMargin else BottomSpec.BOTTOM_MARGIN, start = BottomSpec.SIDE_GUTTER, end = BottomSpec.SIDE_GUTTER),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(BottomSpec.RADIUS)
        BoxWithConstraints(Modifier.widthIn(max = BottomSpec.MAX_WIDTH).fillMaxWidth()) {
            val cell = BottomSpec.cellWidth(maxWidth, entries.size)
            val barHeight by animateDpAsState(
                if (clean) tokens.cleanBarHeight else if (collapsed) BottomSpec.COLLAPSED_HEIGHT else BottomSpec.HEIGHT, label = "navbottom_height",
            )
            val cellHeight = if (clean) tokens.cleanCellHeight else if (collapsed) BottomSpec.COLLAPSED_CELL else BottomSpec.CELL
            Row(
                Modifier.fillMaxWidth().height(barHeight).testTag(BottomTags.BAR)
                    .clip(shape).background(BottomSpec.fill(dark)).border(1.dp, BottomSpec.border(dark), shape)
                    .padding(horizontal = BottomSpec.INNER_PAD)
                    .selectableGroup()
                    .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = entries.size) },
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEachIndexed { index, e ->
                    val on = e.id == selectedId
                    val ink = if (on) BottomSpec.selectedInk(dark) else BottomSpec.idleInk(dark)
                    // #1159 at rest EVERY item shows its label; scrolled down only the selected one does.
                    val labelled = !clean && (!collapsed || on)
                    val lift by animateDpAsState(if (labelled) -BottomSpec.LIFT else 0.dp, label = "navbottom_lift")
                    val show by animateFloatAsState(if (labelled) 1f else 0f, label = "navbottom_label")
                    Box(
                        // #1415 no clip: a CircleShape clip cut the label's ends ("Contacts" on a six-item bar). The touch
                        // feedback keeps its circle as an unbounded ripple of the cell's radius.
                        Modifier.size(cell, cellHeight).testTag(BottomTags.item(e.id))
                            .selectable(
                                selected = on, interactionSource = null,
                                indication = androidx.compose.material3.ripple(bounded = false, radius = minOf(cell, cellHeight) / 2),
                                role = Role.Tab, onClick = { onSelect(e) },
                            )
                            .semantics { collectionItemInfo = CollectionItemInfo(0, 1, index, 1); if (clean) contentDescription = e.label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.offset(y = lift)) {
                            Icon(e.icon, contentDescription = null, tint = if (e.tinted) ink else Color.Unspecified, modifier = Modifier.size(if (clean) tokens.cleanIconSize else BottomSpec.ICON).testTag(BottomTags.icon(e.id)))
                        }
                        // The label fades with the state and leaves the tree once faded out (a hidden label is not a node).
                        if (show > 0f) Text(
                            e.label, color = ink, fontSize = BottomSpec.LABEL, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = BottomSpec.LABEL_BOTTOM)
                                .testTag(BottomTags.label(e.id))
                                .graphicsLayer { alpha = show },
                        )
                    }
                }
            }
        }
    }
    }
}

/** The content slot of [NavBarBottomHost]: the SAME tag libs:bottomnav's BottomNavHost gives its content. */
public const val NAV_BAR_BOTTOM_CONTENT_TAG: String = "bottomnav_content"

/** #1257 the bar's slot under the content: it keeps the bar's EXPANDED height while the bar collapses or hides. */
public const val NAV_BAR_BOTTOM_SLOT_TAG: String = "bottomnav_slot"

/** #1257 the slot re-measures only while the bar is fully expanded (rest state), so a collapse or a hide never shrinks it. */
public fun navBottomSlotShouldMeasure(collapsed: Boolean, hidden: Boolean): Boolean = !collapsed && !hidden

/**
 * The fleet's bottom-nav host (#1154), the successor of libs:bottomnav's BottomNavHost: [content] above the bar on the shared
 * page gradient (libs:theme-background), ending where the bar's clearance begins, and scrolling the content collapses the bar
 * to icons ([BottomNavCollapse], consuming nothing). What a tap does is the app's. [dark] picks the shared palette; the fleet's
 * apps are dark launchers, so it defaults to dark.
 */
@Composable
public fun NavBarBottomHost(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    // #1157 one connection for the whole chrome: an outer scaffold's, else this host's own (provided to everything inside).
    val inherited = LocalNavChromeCollapse.current
    val own = rememberBottomNavCollapse()
    // scroll-hide on (the default) hides EVERY bar on a downward scroll until an upward one; off pins them.
    own.policy = currentScrollPolicy()
    val collapse = inherited ?: own
    val insets = bottomNavInsets()
    // #1257 THE BAR'S SLOT KEEPS THE BAR'S EXPANDED HEIGHT (its own measure: cell height + margin + the bottom system-bar inset).
    // The bar used to leave the column while a drag was in progress (HIDDEN) and shrink when collapsed, so the content's
    // viewport grew during the drag and shrank again on release: a list scrolled to its end lost its last rows below the
    // new bottom edge every time the owner reached for them. With a constant slot the viewport never changes size; the bar
    // collapses and hides INSIDE its slot. The slot's height is published as LocalNavBottomClearance (read by pages, never typed).
    val density = androidx.compose.ui.platform.LocalDensity.current
    var expandedPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val ime = navHiddenByIme()
    // scroll-hide (the Hide policy): a hidden bar gives its room back, the page reads over it; every other policy keeps the slot (#1257)
    val slotGivenBack = collapse.policy == ChromeScrollPolicy.Hide && collapse.hidden
    val slot = if (ime || slotGivenBack) 0.dp else with(density) { expandedPx.toDp() }
    androidx.compose.runtime.CompositionLocalProvider(LocalNavChromeCollapse provides collapse, LocalNavBottomClearance provides slot) {
    Column(modifier.fillMaxSize().themeBackground(dark)) {
        // The bar clears the bottom system bar itself, so the content must not clear it a second time (consumed for the content ONLY).
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (ime) Modifier else Modifier.consumeWindowInsets(insets.only(WindowInsetsSides.Bottom)))
                .then(if (inherited == null) Modifier.chromeScroll(own) else Modifier)
                .testTag(NAV_BAR_BOTTOM_CONTENT_TAG),
        ) { content() }
        Box(Modifier.fillMaxWidth().heightIn(min = slot).testTag(NAV_BAR_BOTTOM_SLOT_TAG), contentAlignment = Alignment.BottomCenter) {
            NavBarBottom(entries, selectedId, onSelect, dark, collapsed = collapse.collapsed,
                modifier = Modifier.onSizeChanged { sz -> if (navBottomSlotShouldMeasure(collapse.collapsed, collapse.hidden)) expandedPx = sz.height })
        }
    }
    }
}
