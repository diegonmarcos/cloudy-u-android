package com.diegonmarcos.superapp.bottomnav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt

/**
 * THE bottom nav of the fleet: one Compose declaration that every app renders (#565).
 *
 * This is a PORT of superapp's View-era CloudBottomNavView + bg_nav_island.xml +
 * bg_bottom_nav_item_checked.xml + color/bottom_nav_content.xml, not a wrapper around them.
 * No AndroidView, no View, no drawable: the old toolkit ends here. The geometry comes from this
 * module's res/values/dimens.xml, which ports the tokens that #462/#473/#477/#498 settled.
 *
 *  - #417 the island is a full pill (radius = height/2), so its ends are true semicircles, and
 *    every item is laid out inside it. Nothing overflows the capsule it sits in.
 *  - #462 the SELECTED item is a pill too. It wraps the icon AND the label (Telegram/Revolut
 *    style), light on the dark bar, and it is the only lit capsule. Selection is shown by that
 *    pill, not by a colour change.
 *  - #473 the end capsules are inset by exactly the capsule's vertical inset, so their arcs are
 *    concentric with the island's. The cells are equal weights, so spacing is even. The label
 *    sits bottom_nav_icon_label_gap below the icon.
 *  - #477 the bottom clearance is ONE dimen plus the live system-bar/display-cutout inset.
 *    The inset is READ (getBottom), never applied through windowInsetsPadding or
 *    consumeWindowInsets, so nothing else in the window loses it.
 *  - #532 [collapsed] drops the labels and leaves an icons-only bar. A Compose shell derives it
 *    from scrolling with [BottomNavCollapse]; a View shell with
 *    [BottomNavIslandView.collapseOnScrollIn], which #673 added after the View shells were found
 *    to be setting [collapsed] nowhere at all, leaving them permanently expanded. Both drivers
 *    decide with [collapseFor], so there is one rule and not one per host style.
 *    The island is fill only, with no stroke layer, so the visible edge IS
 *    the fill's edge. The collapse is ANIMATED by one progress value that shrinks each label's
 *    layout slot, so the bar shrinks with it, and the label carries no clip of its own: the
 *    only thing that hides it is the capsule's pill clip, so the hide line IS the oval edge and
 *    there is no second, rectangular bound for it to vanish at. Under Power Saving or with
 *    animations removed it snaps instead ([barMotionEnabled]).
 *  - #536 the island is bottom_nav_width_fraction (80%) of the width it is given, and it is
 *    centred, so 10% stays clear on each side.
 *  - ACCESSIBILITY. Every item is a Tab with its label (the icon's description while collapsed)
 *    and its selected state, inside a selectable group that carries collection info, so TalkBack
 *    reads "Mail, selected, Tab, 1 of 5". Keyboard / D-pad / switch focus draws a ring in the item's
 *    ink; touch never focuses an item, so a tapped island is pixel for pixel what it was.
 *    NavAccessibilityTest.
 *  - FIT, never scroll. Every item is always on screen ([planIsland]). A bar whose equal cells clear
 *    the full-size floor (48dp up to EQUAL_CELLS_BOTTOM items, bottom_nav_min_cell_width above) is
 *    drawn exactly as it always was. A more crowded bar first widens its island past 80% (down to
 *    bottom_nav_min_side_margin each side) until its cells reach the 48dp touch floor, then, only
 *    if that is still short, narrows its end insets (down to bottom_nav_min_end_inset); its icon,
 *    label and icon-label gap scale down with the cell to the bottom_nav_fit_* minimums. The
 *    capsule's vertical inset, its colours and the focus ring are untouched (the island draws no
 *    badge of its own), and the icon keeps its 24dp slot, so the touch target keeps its full height.
 *
 * The items are INJECTED as [BottomNavEntry]. The bar knows nothing about any app's menu.
 */

/** One nav item as the bar draws it: a stable id, the label (also the collapsed bar's
 *  accessible name), and the glyph. */
public data class BottomNavEntry(
    val id: String,
    val label: String,
    val icon: Painter,
    /** False = a brand icon (#1160, libs:ui-icons UiIcon.tinted): drawn in its own colours, not the bar's ink. */
    val tinted: Boolean = true,
)

/**
 * #1160 test seam of [navHiddenByIme]: null (the default) asks the platform whether the soft keyboard is showing; a test provides
 * true / false to stand for it. An app never provides this.
 */
public val LocalNavImeVisible: androidx.compose.runtime.ProvidableCompositionLocal<Boolean?> =
    androidx.compose.runtime.compositionLocalOf { null }

/**
 * #1160 true while the soft keyboard is showing. EVERY shared bottom nav (this island, the search variant, libs:ui-nav-bars)
 * is not drawn then, and its host stops reserving room for it: the keyboard HIDES the bar, it never lifts it above itself.
 */
@Composable
public fun navHiddenByIme(): Boolean = LocalNavImeVisible.current ?: platformImeVisible()

/** The one pill shape. The island and the selected capsule both use it, so the two stadiums
 *  can never drift apart. 50% = half the shorter side, a true semicircle at any size. */
public val bottomNavPillShape: RoundedCornerShape = RoundedCornerShape(percent = 50)

internal const val TAG_ISLAND = "bottomnav_island"
internal const val TAG_CONTENT = "bottomnav_content"
internal fun itemTag(id: String) = "bottomnav_item_$id"
internal fun iconTag(id: String) = "bottomnav_icon_$id"
internal fun labelTag(id: String) = "bottomnav_label_$id"

/** The insets the island clears: system bars AND the display cutout (#477). */
@Composable
public fun bottomNavInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * THE island. Its public contract is entries, the selection, the tap callback and (for a Compose
 * shell that scrolls) [collapsed]: nothing else. The colours, the geometry, the typeface, the
 * system-bar clearance, the press feedback and the haptics are all this module's, so an app cannot
 * draw a different island. See [FleetChrome] for the colours and the window.
 */
@Deprecated("#1154: use libs:ui-nav-bars NavBarBottom; this alias is kept for one release and is removed after it.")
@Composable
public fun BottomNavIsland(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    /** Extra behaviour the HOST hangs on one item's capsule (superapp's long-press fan, #531).
     *  Applied inside the capsule's clip, before its click, so a gesture it consumes wins. The
     *  bar itself stays behaviour-free: an item still does nothing but select on a tap. */
    itemModifier: (BottomNavEntry) -> Modifier = { Modifier },
) {
    BottomNavIslandImpl(entries, selectedId, onSelect, modifier, collapsed, null, itemModifier)
}

/**
 * The one composable every host renders: [BottomNavIsland] (Compose shells), [BottomNavHost] and
 * [BottomNavIslandView] (View shells) all end here, so what they draw is the same tree.
 *
 * [insets] null = clear the live system bars and display cutout through windowInsetsPadding, which
 * leaves out whatever an ancestor already consumed (#477: read, so nothing else loses them). A
 * non-null value is the clearance a View host measured for itself, or a test's injected inset.
 */
@Composable
internal fun BottomNavIslandImpl(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier,
    collapsed: Boolean,
    insets: WindowInsets?,
    itemModifier: (BottomNavEntry) -> Modifier,
) {
    // An app's MaterialTheme must not reach the island: its text style (line height, family,
    // spacing) would leak into the labels through LocalTextStyle, and its colours through the
    // scheme. Both are replaced by the fleet's.
    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
        if (!navHiddenByIme()) IslandContent(entries, selectedId, onSelect, modifier, collapsed, insets, itemModifier)
    }
}

@Composable
private fun IslandContent(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier,
    collapsed: Boolean,
    insets: WindowInsets?,
    itemModifier: (BottomNavEntry) -> Modifier,
) {
    val density = LocalDensity.current
    val tokens = navTokens()
    val widthFraction = tokens.widthFraction
    // Read, never consumed: getBottom is a plain snapshot read that recomposes when the inset
    // changes. It is the Compose form of a non-consuming insets listener.
    val margin = tokens.islandBottomMargin
    val liveClearance = if (insets == null) Modifier.windowInsetsPadding(bottomNavInsets().only(WindowInsetsSides.Bottom))
    else Modifier.padding(bottom = with(density) { insets.getBottom(this).toDp() })
    val haptics = navHaptics()
    val pillInset = tokens.pillInset
    val pad = tokens.itemVerticalPad
    val gap = tokens.iconLabelGap
    val iconSize = tokens.iconSize
    // Every attribute spelled out, so nothing is inherited from an app's typography.
    val labelStyle = TextStyle(
        fontSize = tokens.labelTextSize,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Normal,
    )
    // #532 the collapse animates. remember(collapsed) re-reads Power Saving at every transition, so
    // a battery saver switched on while the shell is open holds the very next collapse still.
    val motion = rememberBarMotion(collapsed)
    val labelShown by animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = if (motion) tween(tokens.collapseMs) else snap(),
        label = "bottomnav_label_shown",
    )

    // #a11y FIT: every item is always on screen, in equal cells. A bar that fits is the island it
    // always was; a crowded one widens, then scales its icon / label / gap down ([planIsland]).
    BoxWithConstraints(modifier.fillMaxWidth().then(liveClearance).padding(bottom = margin), contentAlignment = Alignment.BottomCenter) {
        val plan = planIsland(maxWidth, widthFraction, tokens.endInset, entries.size, tokens)
        // A bar that fits draws the tokens themselves (pixel parity); only a fitted one scales them.
        val cellIcon = if (plan.fitted) lerpDp(tokens.fitIconSize, iconSize, plan.scale) else iconSize
        val cellGap = if (plan.fitted) lerpDp(tokens.fitIconLabelGap, gap, plan.scale) else gap
        val cellLabelStyle = if (plan.fitted) labelStyle.copy(fontSize = lerpSp(tokens.fitLabelTextSize, tokens.labelTextSize, plan.scale)) else labelStyle
        // The smaller glyph stays centred in the full icon slot, so the capsule (and with it the
        // touch target) keeps its full height however far the icon shrinks.
        val iconSlotPad = (iconSize - cellIcon) / 2
        // A fitted label keeps clear of the capsule's round ends (a label as wide as its cell has its
        // outer letters shaved by the pill clip), and a label too long for that width shrinks, alone,
        // toward bottom_nav_fit_label_min_text_size before it ellipsizes. Never hidden: the selected
        // item always keeps its label.
        val labelInset = tokens.fitLabelInset
        val measurer = rememberTextMeasurer()
        // #1356 the room a label may use, clear of the capsule's round ends, in EVERY plan: a full-size bar of 7 items
        // ('Explorer', 'Mounts' at 12sp) had its outer letters shaved by the pill clip. A label that fits draws the
        // tokens unchanged (pixel parity); only one that does not shrinks toward fitLabelMinTextSize, then ellipsizes.
        val labelRoom = with(density) { (plan.cell - tokens.fitLabelInset * 2).toPx() }
        Row(
            Modifier
                .then(if (plan.fitted) Modifier.width(plan.islandWidth) else Modifier.fillMaxWidth(widthFraction))
                .testTag(TAG_ISLAND)
                .clip(bottomNavPillShape)
                .background(tokens.islandFill)
                .padding(horizontal = plan.endInset)
                // TalkBack: a tab list of N, so an item reads "Mail, selected, Tab, 2 of 5".
                .selectableGroup()
                .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = entries.size) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEachIndexed { index, entry ->
                val selected = entry.id == selectedId
                val ink = if (selected) tokens.pillInk else tokens.idleInk
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsFocusedAsState()
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = pillInset)
                        .testTag(itemTag(entry.id))
                        .clip(bottomNavPillShape)
                        .background(if (selected) tokens.pillFill else Color.Transparent)
                        // Keyboard / D-pad / switch focus is drawn as a ring in the item's ink. Touch
                        // never focuses an item, so a tapped island has no ring and its pixels are
                        // exactly what they were.
                        .then(if (focused) Modifier.border(tokens.focusRingWidth, ink, bottomNavPillShape) else Modifier)
                        .then(itemModifier(entry))
                        .selectable(
                            selected = selected,
                            interactionSource = source,
                            indication = FleetIndication,
                            role = Role.Tab,
                            onClick = {
                                // SuperApp's section-change haptic rhythm, on a tap that moves the pill.
                                if (!selected) haptics.sectionChange()
                                onSelect(entry)
                            },
                        )
                        .semantics {
                            collectionItemInfo = CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = index, columnSpan = 1)
                        }
                        .padding(vertical = pad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Icon(
                        entry.icon,
                        // With the label hidden, the icon has to carry the item's name.
                        contentDescription = if (collapsed) entry.label else null,
                        tint = if (entry.tinted) ink else Color.Unspecified,
                        modifier = Modifier
                            .then(if (plan.fitted) Modifier.padding(vertical = iconSlotPad) else Modifier)
                            .size(cellIcon)
                            .testTag(iconTag(entry.id)),
                    )
                    val entryStyle = remember(entry.label, cellLabelStyle, labelRoom, density) {
                        labelStyleToFit(cellLabelStyle, tokens.fitLabelMinTextSize) { style ->
                            measurer.measure(entry.label, style, maxLines = 1, softWrap = false).size.width <= labelRoom
                        }
                    }
                    if (labelShown > 0f) {
                        // Laid out across the whole capsule and centred, not at its own
                        // intrinsic width. A one-line ellipsized paragraph exactly as wide as its
                        // text can round itself into an ellipsis: 'Mail' at 23px came out
                        // ellipsized in a 56px capsule in CI run 36024784089.
                        // The slot (gap + text) shrinks with labelShown and the text is placed at
                        // its top, so the capsule loses height and the label leaves through the
                        // capsule's own pill clip. No clipToBounds here: that would be a
                        // rectangle inside the oval.
                        // A label too long for its cell (a fitted cell, a 2x font) is ellipsized on
                        // screen; its semantics keep the whole label for TalkBack. Labels are never
                        // hidden for width, so the selected item always keeps its label.
                        Text(
                            entry.label,
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, (placeable.height * labelShown).roundToInt()) {
                                        placeable.place(0, 0)
                                    }
                                }
                                .graphicsLayer { alpha = labelShown }
                                .padding(top = cellGap)
                                .fillMaxWidth()
                                .padding(horizontal = labelInset)
                                .testTag(labelTag(entry.id)),
                            color = ink,
                            style = entryStyle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How the island lays out [count] cells in [maxWidth] (#a11y): always every item, in equal cells,
 * never a scroll.
 *
 * [fitted] false = the island as it always was: [widthFraction] of the width, the token end inset,
 * full-size icon and label ([scale] 1). That holds while the equal cell clears [fullSizeCell]: the
 * 48dp touch floor up to [EQUAL_CELLS_BOTTOM] items, [NavTokens.minCellWidth] above, so every bar that
 * did not scroll before draws exactly the same pixels (five items on a 360dp phone: 55.2dp cells).
 *
 * [fitted] true = a crowded bar, in this order:
 *  1. the island widens past the fraction toward full-size cells, keeping
 *     [NavTokens.fitSideMargin] clear on each side (it still floats);
 *  2. cells still under the 48dp touch floor: it widens on, only as far as 48dp needs, down to
 *     [NavTokens.minSideMargin] each side;
 *  3. still short of 48dp: the end insets narrow toward [NavTokens.minEndInset] (the end arcs then
 *     stop being exactly concentric; the capsule's vertical inset does not change);
 *  4. the icon, the label and the icon-label gap scale with [scale], 1 at [fullSizeCell] down to 0
 *     at [NavTokens.fitMinCell] (the bottom_nav_fit_* sizes).
 * On a 360dp phone: 6 items (Cloud Drive) = a 344dp island, 55.3dp cells, type at 94%; 7 items
 * (Mail) = 348dp, 48dp cells; 8 items = 356dp, 2dp end insets, 44dp cells, the one case that cannot
 * reach 48dp (8 x 48 = 384 > 360): its targets keep their full height and do not overlap. The
 * continuity is exact: at the [fullSizeCell] boundary the fitted island is the classic one.
 */
internal data class IslandPlan(
    val fitted: Boolean,
    val islandWidth: Dp,
    val endInset: Dp,
    val cell: Dp,
    val scale: Float,
    val count: Int,
)

/** The narrowest equal cell that still draws full-size type: the touch floor up to
 *  [EQUAL_CELLS_BOTTOM] items (the five-item bar's rule since #473), bottom_nav_min_cell_width above. */
internal fun fullSizeCell(count: Int, tokens: NavTokens): Dp =
    if (count > EQUAL_CELLS_BOTTOM) maxOf(tokens.minCellWidth, tokens.minTouchTarget) else tokens.minTouchTarget

internal fun planIsland(maxWidth: Dp, widthFraction: Float, endInset: Dp, count: Int, tokens: NavTokens): IslandPlan {
    val classic = maxWidth * widthFraction
    if (count <= 0) return IslandPlan(false, classic, endInset, 0.dp, 1f, 0)
    val equal = (classic - endInset * 2) / count
    val full = fullSizeCell(count, tokens)
    if (equal >= full) return IslandPlan(false, classic, endInset, equal, 1f, count)
    // 1. widen toward full-size cells, the island still floating clear of the sides.
    val roomy = maxOf(classic, maxWidth - tokens.fitSideMargin * 2)
    var island = (full * count + endInset * 2).coerceIn(classic, roomy)
    // 2. under the touch floor: widen on, only as far as 48dp needs, down to the side-margin floor.
    if ((island - endInset * 2) / count < tokens.minTouchTarget) {
        val widest = maxOf(roomy, maxWidth - tokens.minSideMargin * 2)
        island = (tokens.minTouchTarget * count + endInset * 2).coerceIn(island, widest)
    }
    // 3. still short: give the end insets up, down to their floor.
    val end = if ((island - endInset * 2) / count >= tokens.minTouchTarget) endInset
    else ((island - tokens.minTouchTarget * count) / 2).coerceIn(minOf(tokens.minEndInset, endInset), endInset)
    val cell = (island - end * 2) / count
    // 4. the type follows the cell.
    val span = (full - tokens.fitMinCell).value
    val scale = if (span <= 0f) 1f else ((cell - tokens.fitMinCell).value / span).coerceIn(0f, 1f)
    return IslandPlan(true, island, end, cell, scale, count)
}

/** [style] as is when the label [fits]; else the largest size, in quarter-sp steps, at which it does,
 *  never below [min] (where it then ellipsizes). Stepped, because glyph advances are hinted: a width
 *  does not scale linearly with the size. */
internal fun labelStyleToFit(style: TextStyle, min: TextUnit, fits: (TextStyle) -> Boolean): TextStyle {
    if (fits(style)) return style
    var size = style.fontSize.value - 0.25f
    while (size > min.value) {
        val smaller = style.copy(fontSize = size.sp)
        if (fits(smaller)) return smaller
        size -= 0.25f
    }
    return style.copy(fontSize = min)
}

internal fun lerpDp(min: Dp, max: Dp, t: Float): Dp = min + (max - min) * t

internal fun lerpSp(min: TextUnit, max: TextUnit, t: Float): TextUnit = (min.value + (max.value - min.value) * t).sp

/**
 * Whether the collapse may animate: off when the reader removed animations (animator duration
 * scale 0) or the device is in the system's battery saver (#501 precedent: animation respects
 * Power Saving). Either alone holds the bar still, and it then snaps between its two shapes.
 */
internal fun barMotionEnabled(animatorDurationScale: Float, powerSaveMode: Boolean): Boolean =
    animatorDurationScale != 0f && !powerSaveMode

/**
 * THE collapse rule of the fleet, one declaration (#673). A downward delta collapses the island to
 * icons, an upward one restores the labels, and no movement changes nothing. Both drivers call
 * this: [BottomNavCollapse] with its nested-scroll delta in a Compose shell, and
 * [BottomNavIslandView.collapseOnScrollIn] with its measured scroll delta in a View shell. A
 * second copy of this `if` is how the two host styles would end up collapsing on different rules.
 */
internal fun collapseFor(collapsed: Boolean, delta: Float): Boolean = when {
    delta > 0f -> true
    delta < 0f -> false
    else -> collapsed
}

/**
 * #532 scroll-collapse for a COMPOSE shell. Attach it with Modifier.nestedScroll(collapse) on the
 * scrolling content and pass [collapsed] to [BottomNavIsland]. Scrolling down the content
 * collapses the bar to icons only, and scrolling back up restores the labels. It only observes: it
 * consumes nothing, so the list still gets every pixel of the scroll.
 *
 * A View-based shell cannot use this — a NestedScrollConnection is reached only from Compose — and
 * uses [BottomNavIslandView.collapseOnScrollIn] instead. Both apply [collapseFor].
 */
public class BottomNavCollapse : NestedScrollConnection {
    /**
     * How a scroll moves [state] ([ChromeScrollPolicy]): the shared scaffolds set it from the nav UI settings in force (scroll-hide on =
     * [ChromeScrollPolicy.Hide], off = [ChromeScrollPolicy.Pinned]); a bare connection keeps the older [ChromeScrollPolicy.Shrink].
     */
    public var policy: ChromeScrollPolicy = ChromeScrollPolicy.Shrink
        set(value) { field = value; if (value == ChromeScrollPolicy.Pinned) state = ChromeScrollState.Default }

    /** The chrome's three-state scroll machine ([ChromeScrollState]); [collapsed] and the other views derive from it. */
    public var state: ChromeScrollState by mutableStateOf(ChromeScrollState.Default)
        private set

    /** Any state but DEFAULT: the bars' non-selected labels are folded away (they are hidden outright while [hidden]). */
    public val collapsed: Boolean get() = state != ChromeScrollState.Default

    /** HIDDEN: a downward drag is in progress, every bar is away. */
    public val hidden: Boolean get() = state == ChromeScrollState.Hidden

    /** The first row is shown only at DEFAULT. */
    public val firstRowVisible: Boolean get() = state == ChromeScrollState.Default

    /** A host that measures its own scroll (a WebView's scroll-change listener): [delta] > 0 is the content travelling UP the screen (reading down). With the touch watched ([onFinger]) a finger-down read is a drag (HIDDEN), else it settles to SHRUNK. */
    public fun onScrollDelta(delta: Float) { step(delta, dragging = fingerTracked && fingerDown, starts = !fingerTracked && delta < 0f) }

    /** A host that knows its finger state (a View shell with a touch listener) feeds the drag and the release itself. */
    public fun onDragDelta(delta: Float) { step(delta, dragging = true, starts = !fingerTracked) }

    /** The finger lifted: the gesture ends (its fling still belongs to it), the next scroll by a finger starts a new one. */
    public fun onRelease() { state = chromeScrollReleaseStep(policy, state); awaitingGesture = true }

    // Per-gesture memory ([chromeGestureStep]): the id of the touch in progress, and the one that hid the bars. With the touch
    // watched ([onFinger]) a finger-down starts a gesture; without a watcher, the first scroll after a release does (a host that
    // reports neither finger nor source ([onScrollDelta]) treats a downward scroll after the release as that gesture's fling and an upward
    // one as a new touch; the nested-scroll path knows: a Fling source never starts one).
    private var gesture = 0
    private var latch = NO_GESTURE
    private var awaitingGesture = true

    private fun step(delta: Float, dragging: Boolean, starts: Boolean = false) {
        if (starts && awaitingGesture && delta != 0f) { gesture++; awaitingGesture = false }
        val r = chromeGestureStep(policy, state, delta, dragging, gesture, latch)
        state = r.state; latch = r.latch
    }

    // Once a host watches the raw touch ([onFinger]), a scroll counts as a DRAG only while the finger is down: a scroll a service
    // injects (an accessibility action, a test's semantics scroll) arrives as "user input" with no finger and must not hide the bars for good.
    private var fingerTracked = false
    private var fingerDown = false

    /** The host's touch watcher: the finger went [down] on the page, or lifted. */
    public fun onFinger(down: Boolean) {
        fingerTracked = true
        if (down && !fingerDown) { gesture++; awaitingGesture = false }
        fingerDown = down
    }

    /** The finger travelled DOWN the screen (reads up): restores the bars, unless this same gesture hid them ([chromeGestureStep]). */
    public fun onGestureUp() { step(-1f, dragging = true) }

    /** A programmatic restore (a new page, an overlay closed): everything comes back, whatever gesture hid the bars. */
    public fun restore() { state = ChromeScrollState.Default; latch = NO_GESTURE }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Negated: a nested-scroll y goes negative as the content travels down, which is the same
        // gesture a View shell measures as a RISING scroll offset. One rule, [chromeScrollOnDelta], so the
        // Compose and View hosts cannot collapse on different definitions (#673). A finger-driven scroll is a drag; a fling/settle is not.
        val byFinger = source == NestedScrollSource.UserInput
        step(-available.y, dragging = byFinger && (!fingerTracked || fingerDown), starts = byFinger && !fingerTracked)
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // The finger is up: HIDDEN settles to SHRUNK (the fling then keeps it there), DEFAULT stays DEFAULT.
        onRelease()
        return Velocity.Zero
    }
}

/**
 * The chrome's scroll states (spec: three-state machine). DEFAULT: every bar full. HIDDEN: a downward drag is in progress, ALL bars
 * (first row, second row, bottom nav, side rail) are away. SHRUNK: the finger lifted after a downward scroll, the first row stays
 * away and the second row, bottom nav and rail return shrunk (non-selected names hidden). Any upward scroll goes back to DEFAULT.
 */
public enum class ChromeScrollState { Default, Hidden, Shrunk }

/** The machine's step on a scroll [delta] (> 0: reading down). [dragging]: the finger is on the screen. */
public fun chromeScrollOnDelta(state: ChromeScrollState, delta: Float, dragging: Boolean): ChromeScrollState = when {
    delta < 0f -> ChromeScrollState.Default
    delta > 0f -> when {
        dragging -> ChromeScrollState.Hidden
        state == ChromeScrollState.Hidden -> ChromeScrollState.Hidden
        else -> ChromeScrollState.Shrunk
    }
    else -> state
}

/** The machine's step when the finger lifts (drag end / fling start): HIDDEN becomes SHRUNK. */
public fun chromeScrollOnRelease(state: ChromeScrollState): ChromeScrollState =
    if (state == ChromeScrollState.Hidden) ChromeScrollState.Shrunk else state

@Composable
public fun rememberBottomNavCollapse(): BottomNavCollapse = remember { BottomNavCollapse() }

// ── the host: a screen ABOVE the island, scroll-collapsing it (#534) ──

/**
 * The fleet's Compose bottom-nav host: [entries] on the shared island, with the screen's
 * [content] laid out ABOVE it (#534). The content ends where the island's clearance begins, so
 * nothing the screen draws can hide under the bar. Scrolling the content collapses the island to
 * icons (#532). What a tap DOES is the app's: [onSelect] gets the tapped entry. cloud-mail's
 * item table and its launch hand-offs live in cloud-mail (it was the first consumer, #565).
 */
@Deprecated("#1154: use libs:ui-nav-bars NavBarBottomHost; this alias is kept for one release and is removed after it.")
@Composable
public fun BottomNavHost(
    entries: List<BottomNavEntry>,
    selectedId: String?,
    onSelect: (BottomNavEntry) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val collapse = rememberBottomNavCollapse()
    val insets = bottomNavInsets()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The island below already clears the bottom system bar, so the content must not clear it
        // a second time: consumed for the content ONLY. The island still reads it (#477).
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (navHiddenByIme()) Modifier else Modifier.consumeWindowInsets(insets.only(WindowInsetsSides.Bottom)))
                .nestedScroll(collapse)
                .testTag(TAG_CONTENT),
        ) { content() }
        BottomNavIsland(entries = entries, selectedId = selectedId, onSelect = onSelect, collapsed = collapse.collapsed)
    }
}
