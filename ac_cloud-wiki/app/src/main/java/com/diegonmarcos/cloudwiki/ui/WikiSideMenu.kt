package com.diegonmarcos.cloudwiki.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwiki.MenuState
import com.diegonmarcos.cloudwiki.R
import com.diegonmarcos.cloudwiki.TreeNode
import com.diegonmarcos.cloudwiki.WikiMenu
import com.diegonmarcos.cloudwiki.WikiTree
import com.diegonmarcos.superapp.bottomnav.ChromeScrollState
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.railLabel
import com.diegonmarcos.superapp.navbartop2.LocalSubPageRequests
import com.diegonmarcos.superapp.navbartop2.SecondRowIcons
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.abs

/** #1891 the side menu's test tags. */
object MenuTags {
    const val RAIL = "wiki_menu_rail"
    const val TOGGLE = "wiki_menu_toggle"
    const val PANEL = "wiki_menu_panel"
    const val LIST = "wiki_menu_list"
    const val BAR = "wiki_menu_bar"
    const val SEARCH = "wiki_menu_search"
    fun filter(group: String) = "wiki_menu_filter:$group"
    fun expand(levels: Int) = "wiki_menu_expand:$levels"
    fun collapse(levels: Int) = "wiki_menu_collapse:$levels"
    fun row(id: String) = "wiki_menu_row:$id"
    fun rail(id: String) = "wiki_menu_rail:$id"
}

private val RAIL_WIDTH = 56.dp
private val ROW_HEIGHT = 40.dp
private val INDENT = 14.dp
/** Expand / Collapse All is drawn as level 0 here; any other number is that many levels. */
private const val ALL = 0

/**
 * #1891 the wiki's own side menu, WITH the page it navigates ([content]): a rail at the left (the menu toggle and one entry per parent) and,
 * when [open], the menu over the page at [WikiMenu.WIDTH_FRACTION] of the screen width. The menu has its OWN scroll and touch area: its list
 * scrolls by its own drag (never handed to the page or to the chrome's scroll machine), and the page's scroll never takes it away
 * ([WikiMenu.presence]); the page beside it stays live. At its top: Filter (Modern Science | Arcanum Science), Expand (All, 1, 2 levels),
 * Collapse (All, 1, 2 levels) and Search (matches with their ancestors, highlighted). The tree and every change of [menu] are pure
 * ([WikiMenu]); this only draws them.
 */
@Composable
fun WikiSideLayout(
    tree: WikiTree,
    selectedId: String,
    selectedTab: String?,
    menu: MenuState,
    onMenu: (MenuState) -> Unit,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onPick: (TreeNode) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // the first row's title dropdown follows the page the menu shows, and a pick there selects it (as libs:ui-nav-bars's side menu does)
    val requests = LocalSubPageRequests.current
    val key = remember { Any() }
    DisposableEffect(requests, key, selectedId) {
        requests?.reportShown(key, selectedId)
        onDispose { requests?.clearShown(key) }
    }
    LaunchedEffect(requests?.pending, tree) {
        val want = requests?.pending ?: return@LaunchedEffect
        val node = tree.node(want)?.takeIf { it.page != null } ?: return@LaunchedEffect
        requests?.consume()
        if (node.page != selectedId) onPick(node)
    }
    val presence = WikiMenu.presence(open, LocalNavChromeCollapse.current?.state ?: ChromeScrollState.Default)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth = maxWidth * WikiMenu.WIDTH_FRACTION
        Row(Modifier.fillMaxSize()) {
            Rail(tree, selectedId, onToggle = { onOpen(!open) }, onRoot = { n ->
                if (n.page != null && n.children.isEmpty()) onPick(n) else { onMenu(menu.copy(expanded = menu.expanded + n.id)); onOpen(true) }
            })
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
        AnimatedVisibility(presence == WikiMenu.Presence.Open, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
            Panel(tree, selectedId, selectedTab, menu, onMenu, onClose = { onOpen(false) }, onPick = onPick, modifier = Modifier.width(panelWidth).fillMaxHeight())
        }
    }
}

@Composable
private fun Rail(tree: WikiTree, selectedId: String, onToggle: () -> Unit, onRoot: (TreeNode) -> Unit) {
    val colors = ThemeBackground.colors(true)
    val shape = RoundedCornerShape(14.dp)
    val list = rememberLazyListState()
    val on = tree.ancestors(selectedId).firstOrNull() ?: selectedId
    Column(
        Modifier.width(RAIL_WIDTH).fillMaxHeight().padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
            .clip(shape).background(colors.island, shape).border(1.dp, colors.islandBorder, shape).testTag(MenuTags.RAIL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).clickable(onClick = onToggle).semantics { contentDescription = "Menu" }.testTag(MenuTags.TOGGLE), contentAlignment = Alignment.Center) {
            Icon(SecondRowIcons.vector("menu"), null, Modifier.size(20.dp), tint = colors.ink)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).ownScroll(list), state = list, userScrollEnabled = false) {
            items(tree.roots, key = { it.id }) { n ->
                val sel = n.id == on
                val ink = if (sel) colors.onTabSelected else colors.inkDim
                Column(
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .then(if (sel) Modifier.background(colors.tabSelected) else Modifier)
                        .clickable { onRoot(n) }.padding(vertical = 6.dp).semantics { contentDescription = n.label }.testTag(MenuTags.rail(n.id)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(SecondRowIcons.vector(n.icon), null, Modifier.size(20.dp), tint = ink)
                    Text(railLabel(n.label, 3), color = ink, fontSize = KitStoreRow.caption, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Panel(
    tree: WikiTree, selectedId: String, selectedTab: String?, menu: MenuState, onMenu: (MenuState) -> Unit,
    onClose: () -> Unit, onPick: (TreeNode) -> Unit, modifier: Modifier,
) {
    val colors = ThemeBackground.colors(true)
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(14.dp)
    val list = rememberLazyListState()
    val rows = remember(tree, menu) { WikiMenu.rows(tree, menu) }
    // opening the menu shows where the reader is: the page's ancestors open, its row scrolled into view
    LaunchedEffect(Unit) {
        val next = WikiMenu.reveal(tree, menu, selectedTab?.takeIf { tree.node(it) != null } ?: selectedId)
        if (next != menu) onMenu(next)
        val at = WikiMenu.rows(tree, next).indexOfFirst { it.node.id == (selectedTab ?: selectedId) || it.node.id == selectedId }
        if (at > 0) list.scrollToItem(at)
    }
    Column(
        modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp).clip(shape).background(colors.island, shape).border(1.dp, colors.islandBorder, shape)
            // the panel is a touch area of its own: a tap or drag anywhere on it never reaches the page under it
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag(MenuTags.PANEL),
    ) {
        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.menu_title), Modifier.weight(1f), color = colors.ink, fontSize = KitStoreRow.title * 1.2f, fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(ROW_HEIGHT).clickable(onClick = onClose).semantics { contentDescription = "Close menu" }, contentAlignment = Alignment.Center) {
                Icon(SecondRowIcons.vector("chevron_left"), null, Modifier.size(22.dp), tint = colors.inkDim)
            }
        }
        // the top bar: Filter | Expand | Collapse, then Search
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(MenuTags.BAR)) {
            BarGroup(stringResource(R.string.menu_filter)) {
                for (g in WikiMenu.FILTERABLE) {
                    val root = tree.node(com.diegonmarcos.cloudwiki.LibraryTree.GROUP + g) ?: continue
                    BarChip(root.label, on = g !in menu.hidden, tag = MenuTags.filter(g)) { onMenu(WikiMenu.toggleFilter(menu, g)) }
                }
            }
            BarGroup(stringResource(R.string.menu_expand)) {
                for (n in listOf(ALL, 1, 2)) BarChip(levelLabel(n), on = false, tag = MenuTags.expand(n)) {
                    onMenu(menu.copy(expanded = if (n == ALL) WikiMenu.expandAll(tree) else WikiMenu.expandLevels(tree, n)))
                }
            }
            BarGroup(stringResource(R.string.menu_collapse)) {
                for (n in listOf(ALL, 1, 2)) BarChip(levelLabel(n), on = false, tag = MenuTags.collapse(n)) {
                    onMenu(menu.copy(expanded = if (n == ALL) WikiMenu.collapseAll() else WikiMenu.collapseLevels(tree, menu.expanded, n)))
                }
            }
            SearchField(menu.query, onQuery = { onMenu(menu.copy(query = it)) })
            if (menu.query.isNotBlank()) {
                Text(stringResource(R.string.menu_matches, rows.count { it.match != null }), color = colors.inkDim, fontSize = KitStoreRow.caption, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(colors.islandBorder))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).ownScroll(list).testTag(MenuTags.LIST), state = list, userScrollEnabled = false) {
            items(rows, key = { it.node.id }) { r ->
                val n = r.node
                val sel = if (n.tab != null) n.page == selectedId && n.tab == selectedTab else n.page == selectedId && n.page != null
                MenuRow(r, sel, onToggle = { onMenu(WikiMenu.toggle(menu, n.id)) }, onClick = { if (n.page != null) onPick(n) else onMenu(WikiMenu.toggle(menu, n.id)) })
            }
            if (rows.isEmpty()) item { Text(stringResource(R.string.menu_no_match), color = p.textSecondary, fontSize = KitStoreRow.body * 1.2f, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun levelLabel(n: Int): String = when (n) {
    ALL -> stringResource(R.string.menu_all)
    1 -> stringResource(R.string.menu_level_1)
    else -> stringResource(R.string.menu_levels, n)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarGroup(label: String, chips: @Composable () -> Unit) {
    val colors = ThemeBackground.colors(true)
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.align(Alignment.CenterVertically).width(64.dp), color = colors.inkDim, fontSize = KitStoreRow.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
        chips()
    }
}

@Composable
private fun BarChip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val colors = ThemeBackground.colors(true)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(if (on) colors.tabSelected else colors.island, shape).border(1.dp, colors.islandBorder, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp).testTag(tag),
    ) {
        Text(label, color = if (on) colors.onTabSelected else colors.ink, fontSize = KitStoreRow.body, maxLines = 1)
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val colors = ThemeBackground.colors(true)
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).border(1.dp, colors.islandBorder, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SecondRowIcons.vector("search"), null, Modifier.size(18.dp), tint = colors.inkDim)
        Box(Modifier.weight(1f).padding(start = 6.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.menu_search), color = colors.inkDim, fontSize = KitStoreRow.body * 1.2f)
            BasicTextField(
                query, onQuery, Modifier.fillMaxWidth().testTag(MenuTags.SEARCH), singleLine = true,
                textStyle = TextStyle(color = colors.ink, fontSize = KitStoreRow.body * 1.2f), cursorBrush = SolidColor(p.accent),
            )
        }
        if (query.isNotEmpty()) Box(Modifier.clickable { onQuery("") }.padding(start = 6.dp)) {
            Text("✕", color = colors.inkDim, fontSize = KitStoreRow.body * 1.2f)
        }
    }
}

@Composable
private fun MenuRow(r: WikiMenu.Row, selected: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val colors = ThemeBackground.colors(true)
    val p = LocalKitPalette.current
    val n = r.node
    val ink = if (selected) colors.onTabSelected else if (n.kind == TreeNode.Kind.PARENT) colors.ink else colors.inkDim
    val label = buildAnnotatedString {
        val m = r.match
        if (m == null) append(n.label) else {
            append(n.label.substring(0, m.first))
            withStyle(SpanStyle(background = p.accent.copy(alpha = 0.45f), color = colors.ink, fontWeight = FontWeight.Bold)) { append(n.label.substring(m.first, m.last + 1)) }
            append(n.label.substring(m.last + 1))
        }
    }
    val tab = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(horizontal = 4.dp, vertical = 1.dp).fillMaxWidth().clip(tab)
            .then(if (selected) Modifier.background(colors.tabSelected, tab) else Modifier)
            .clickable(onClick = onClick).height(ROW_HEIGHT).padding(start = 4.dp + INDENT * n.level, end = 8.dp)
            .semantics { contentDescription = n.label }.testTag(MenuTags.row(n.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).then(if (n.children.isNotEmpty()) Modifier.clickable(onClick = onToggle) else Modifier), contentAlignment = Alignment.Center) {
            if (n.children.isNotEmpty()) Text(if (r.open) "▾" else "▸", color = colors.inkDim, fontSize = KitStoreRow.body * 1.3f)
        }
        Icon(SecondRowIcons.vector(n.icon), null, Modifier.size(18.dp), tint = ink)
        Text(
            label, Modifier.weight(1f).padding(start = 10.dp), color = ink,
            fontSize = if (n.kind == TreeNode.Kind.PARENT) KitStoreRow.title * 1.15f else KitStoreRow.body * 1.25f,
            fontWeight = if (selected || n.kind == TreeNode.Kind.PARENT) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (n.children.isNotEmpty()) Text(n.children.size.toString(), color = colors.inkDim, fontSize = KitStoreRow.caption)
    }
}

/**
 * The menu's own scroll: a vertical drag on the list moves the list and nothing else (no nested scroll, so neither the page nor the chrome's
 * scroll machine sees it), and a fling decays on the list alone.
 */
@Composable
private fun Modifier.ownScroll(list: LazyListState): Modifier {
    val decay = rememberSplineBasedDecay<Float>()
    return this.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { d -> list.dispatchRawDelta(-d) },
        onDragStopped = { v ->
            list.scroll {
                var last = 0f
                AnimationState(0f, -v).animateDecay(decay) {
                    val delta = value - last
                    last = value
                    val used = scrollBy(delta)
                    if (abs(delta - used) > 0.5f) cancelAnimation()
                }
            }
        },
    )
}
