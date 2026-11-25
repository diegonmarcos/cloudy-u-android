package com.diegonmarcos.cloudwiki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.diegonmarcos.superapp.graphview.GraphCamera
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwiki.Body
import com.diegonmarcos.cloudwiki.Decl
import com.diegonmarcos.cloudwiki.HomeIndex
import com.diegonmarcos.cloudwiki.LibraryTree
import com.diegonmarcos.cloudwiki.PlanetCamera
import com.diegonmarcos.cloudwiki.Planetary
import com.diegonmarcos.cloudwiki.R
import com.diegonmarcos.cloudwiki.TreeNode
import com.diegonmarcos.cloudwiki.WikiData
import com.diegonmarcos.cloudwiki.WikiTree
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.graphview.GraphView
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.min

/** #1891 Home's test tags. */
object HomeTags {
    const val HOME = "wiki_home"
    const val MODES = "wiki_home_modes"
    const val LIST = "wiki_home_list"
    const val TABLE = "wiki_home_table"
    const val GRAPH = "wiki_home_graph"
    const val PLANET = "wiki_home_planet"
    fun row(id: String) = "wiki_home_row:$id"
    fun column(c: HomeIndex.Column) = "wiki_home_col:${c.name}"
}

/**
 * #1891 Home: the index visualiser of the whole library ([LibraryTree.build] over the side menu's declaration, the dataset and the Chronos
 * simulators): a summary, then the view mode tabs List | Table | Graph | 3D. Every view draws every node of the one tree; a tap opens the
 * node's page (a dataset page opens its topic on that tab). Drawn from the declaration alone until the dataset arrives.
 */
@Composable
fun HomePage(data: WikiData?, wiki: NavSection, nav: WikiNav, onTopic: (String) -> Unit) {
    val p = LocalKitPalette.current
    val chronos = Decl.chronos
    val tree = remember(wiki, data, chronos) { LibraryTree.build(wiki, data, chronos, home = false) }
    var mode by rememberSaveable { mutableStateOf(HomeIndex.Mode.Outline.id) }
    val open: (TreeNode) -> Unit = { n -> val pg = n.page; if (pg != null) { n.tab?.let { nav.tabs[pg] = it }; onTopic(pg) } }
    val labels = mapOf(
        HomeIndex.Mode.Outline to stringResource(R.string.home_list), HomeIndex.Mode.Table to stringResource(R.string.home_table),
        HomeIndex.Mode.Graph to stringResource(R.string.home_graph), HomeIndex.Mode.Planet to stringResource(R.string.home_planet),
    )
    Column(Modifier.fillMaxSize().testTag(HomeTags.HOME)) {
        NavBarTopSecondRow(
            pages = HomeIndex.Mode.entries.map { NavPage(it.id, labels.getValue(it), it.icon) },
            selectedId = mode,
            onSelect = { mode = it.id },
            modifier = Modifier.testTag(HomeTags.MODES),
            underTopChrome = false,
        )
        val pages = tree.nodes.count { it.kind == TreeNode.Kind.PAGE || it.kind == TreeNode.Kind.SIMULATOR }
        KitStoreCaption(
            stringResource(R.string.home_summary, tree.nodes.size, tree.roots.size, pages, tree.maxLevel + 1, tree.nodes.sumOf { it.words }),
            Modifier.fillMaxWidth().padding(KitStoreRow.padH, KitStoreRow.tight), color = p.textSecondary,
        )
        when (HomeIndex.Mode.entries.firstOrNull { it.id == mode } ?: HomeIndex.Mode.Outline) {
            HomeIndex.Mode.Outline -> IndexList(tree, open)
            HomeIndex.Mode.Table -> IndexTable(tree, open)
            HomeIndex.Mode.Graph -> IndexGraph(tree, open)
            HomeIndex.Mode.Planet -> IndexPlanet(tree, open)
        }
    }
}

/** List: every node indented by its level, with its kind, its number of children and its words. */
@Composable
private fun IndexList(tree: WikiTree, open: (TreeNode) -> Unit) {
    val p = LocalKitPalette.current
    LazyColumn(Modifier.fillMaxSize().testTag(HomeTags.LIST)) {
        items(tree.nodes, key = { it.id }) { n ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = n.page != null) { open(n) }
                    .padding(start = KitStoreRow.padH + 16.dp * n.level, end = KitStoreRow.padH, top = 5.dp, bottom = 5.dp).testTag(HomeTags.row(n.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (n.children.isEmpty()) "•" else "▾", color = p.textSecondary, fontSize = KitStoreRow.body * 1.3f, modifier = Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.label, color = p.textPrimary, fontSize = if (n.level == 0) KitStoreRow.title * 1.3f else KitStoreRow.body * 1.3f,
                        fontWeight = if (n.level <= 1) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    KitStoreCaption(listOfNotNull(kindLabel(n.kind), n.children.size.takeIf { it > 0 }?.let { "$it ▸" }, n.words.takeIf { it > 0 }?.let { "$it w" },
                        n.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }).joinToString(" · "), color = p.textSecondary)
                }
            }
        }
    }
}

private fun kindLabel(k: TreeNode.Kind): String = k.name.lowercase().replaceFirstChar { it.uppercase() }

private val COLUMNS: List<Pair<HomeIndex.Column, Dp>> = listOf(
    HomeIndex.Column.Title to 200.dp, HomeIndex.Column.Parent to 240.dp, HomeIndex.Column.Level to 52.dp, HomeIndex.Column.Children to 72.dp,
    HomeIndex.Column.Words to 64.dp, HomeIndex.Column.Size to 64.dp, HomeIndex.Column.Updated to 110.dp, HomeIndex.Column.Tags to 260.dp,
    HomeIndex.Column.Kind to 84.dp, HomeIndex.Column.Path to 300.dp,
)

/** Table: one row per node, every column the dataset fills; a header tap sorts by it (again: the other way, a third time: tree order). */
@Composable
private fun IndexTable(tree: WikiTree, open: (TreeNode) -> Unit) {
    val p = LocalKitPalette.current
    val all = remember(tree) { HomeIndex.rows(tree) }
    var sortBy by remember { mutableStateOf<HomeIndex.Column?>(null) }
    var asc by remember { mutableStateOf(true) }
    val rows = remember(all, sortBy, asc) { HomeIndex.sorted(all, sortBy, asc) }
    val total = COLUMNS.fold(0.dp) { a, c -> a + c.second }
    val headers = mapOf(
        HomeIndex.Column.Title to stringResource(R.string.col_title), HomeIndex.Column.Parent to stringResource(R.string.col_parent),
        HomeIndex.Column.Level to stringResource(R.string.col_level), HomeIndex.Column.Children to stringResource(R.string.col_children),
        HomeIndex.Column.Words to stringResource(R.string.col_words), HomeIndex.Column.Size to stringResource(R.string.col_size),
        HomeIndex.Column.Updated to stringResource(R.string.col_updated), HomeIndex.Column.Tags to stringResource(R.string.col_tags),
        HomeIndex.Column.Kind to stringResource(R.string.col_kind), HomeIndex.Column.Path to stringResource(R.string.col_path),
    )
    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).testTag(HomeTags.TABLE)) {
        Column(Modifier.width(total).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().background(p.surface).padding(vertical = 6.dp)) {
                for ((c, w) in COLUMNS) {
                    val mark = if (sortBy == c) (if (asc) " ▲" else " ▼") else ""
                    Text(headers.getValue(c) + mark, Modifier.width(w).clickable {
                        when { sortBy != c -> { sortBy = c; asc = true }; asc -> asc = false; else -> { sortBy = null; asc = true } }
                    }.padding(horizontal = 6.dp).testTag(HomeTags.column(c)), color = p.textPrimary, fontSize = KitStoreRow.body * 1.2f, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { r ->
                    val n = tree.node(r.id)
                    Row(Modifier.fillMaxWidth().clickable(enabled = n?.page != null) { n?.let(open) }.padding(vertical = 4.dp).testTag(HomeTags.row(r.id))) {
                        val cells = listOf(
                            r.title, r.parentPath, r.level.toString(), "${r.children} / ${r.descendants}", r.words.toString(), HomeIndex.size(r.chars),
                            r.updated.ifBlank { "—" }, r.tags.joinToString(" ") { "#$it" }, kindLabel(r.kind), r.path,
                        )
                        COLUMNS.forEachIndexed { i, (_, w) ->
                            Text(cells[i], Modifier.width(w).padding(horizontal = 6.dp), color = if (i == 0) p.textPrimary else p.textSecondary,
                                fontSize = KitStoreRow.body * 1.15f, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.width(total).height(1.dp).background(KitStoreRow.RULE))
                }
            }
        }
    }
}

/** Graph: libs:graph-view's node-link view with one edge per parent and child, clustered and coloured by parent. */
@Composable
private fun IndexGraph(tree: WikiTree, open: (TreeNode) -> Unit) {
    val p = LocalKitPalette.current
    val nodes = remember(tree) { HomeIndex.graphNodes(tree) }
    val edges = remember(tree) { HomeIndex.graphEdges(tree) }
    val colors = remember(tree) { tree.roots.mapIndexed { i, r -> r.group to topicColor(i, tree.roots.size) }.toMap() }
    GraphView(
        nodes = nodes, edges = edges,
        nodeColor = { colors[it.group] ?: p.accent },
        linkColor = p.textPrimary, tagColor = p.textSecondary, labelColor = p.textPrimary,
        onOpen = { id -> tree.node(id)?.let(open) },
        modifier = Modifier.fillMaxSize().testTag(HomeTags.GRAPH),
    )
}

/**
 * 3D: the planetary view ([Planetary], drawn on a Compose Canvas with the orbit camera of #1861's Inventory 3D). The library is the star,
 * each parent a body orbiting it, each node's children orbiting the node. Drag rotates, pinch zooms, a tap opens the body under the finger;
 * the orbits turn until paused.
 */
@Composable
private fun IndexPlanet(tree: WikiTree, open: (TreeNode) -> Unit) {
    val p = LocalKitPalette.current
    var yaw by remember { mutableDoubleStateOf(30.0) }
    var pitch by remember { mutableDoubleStateOf(28.0) }
    var zoom by remember { mutableDoubleStateOf(1.0) }
    var t by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(true) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now -> t += (now - last) / 1e9; last = now }
    }
    val bodies = remember(tree, t) { Planetary.layout(tree, t) }
    val extent = remember(tree) { Planetary.extent(Planetary.layout(tree, 0.0)) }
    val colors = remember(tree) { tree.roots.mapIndexed { i, r -> r.group to topicColor(i, tree.roots.size) }.toMap() }
    fun cam() = PlanetCamera(yaw, pitch, zoom * 0.46 * min(size.width, size.height) / extent, size.width / 2.0, size.height / 2.0)
    val now by rememberUpdatedState(bodies)
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = p.textPrimary, fontSize = KitStoreRow.caption)
    Column(Modifier.fillMaxSize().testTag(HomeTags.PLANET)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH), verticalAlignment = Alignment.CenterVertically) {
            KitStoreCaption(stringResource(R.string.home_planet_hint, bodies.size), Modifier.weight(1f), color = p.textSecondary)
            ViewChip(stringResource(if (playing) R.string.home_pause else R.string.home_play), playing) { playing = !playing }
            ViewChip(stringResource(R.string.home_reset), false) { yaw = 30.0; pitch = 28.0; zoom = 1.0 }
        }
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { size = it }
                .pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> yaw += pan.x * 0.4; pitch = (pitch + pan.y * 0.3).coerceIn(-89.0, 89.0); zoom = (zoom * z).coerceIn(0.3, 14.0) } }
                .pointerInput(Unit) { detectTapGestures { o -> cam().pick(now, o.x.toDouble(), o.y.toDouble())?.let { id -> tree.node(id)?.let(open) } } },
        ) {
            val c = cam()
            val byId = bodies.associateBy { it.id }
            // the orbits of the parents, hubs and topics (one ring per parent); the pages' are left out, they would only blur
            for ((parent, moons) in bodies.groupBy { it.parent }) {
                val first = moons.first()
                if (first.level > 2) continue
                val at: Body? = parent?.let { byId[it] }
                val ring = Planetary.ring(at?.x ?: 0.0, at?.y ?: 0.0, at?.z ?: 0.0, first.orbit, first.level)
                val path = Path()
                ring.forEachIndexed { i, (x, y, z) -> val (sx, sy) = c.project(x, y, z); if (i == 0) path.moveTo(sx.toFloat(), sy.toFloat()) else path.lineTo(sx.toFloat(), sy.toFloat()) }
                drawPath(path, (colors[first.group] ?: p.accent).copy(alpha = 0.22f), style = Stroke(width = 1f))
            }
            drawCircle(p.accent, radius = (14 * c.zoom).toFloat().coerceAtLeast(5f), center = Offset(c.cx.toFloat(), c.cy.toFloat()))
            for (b in c.sorted(bodies)) {
                val (sx, sy) = c.project(b.x, b.y, b.z)
                val r = (b.radius * c.zoom).toFloat().coerceAtLeast(2f)
                val col = colors[b.group] ?: p.accent
                drawCircle(col.copy(alpha = 0.25f), radius = r * 1.6f, center = Offset(sx.toFloat(), sy.toFloat()))
                drawCircle(col, radius = r, center = Offset(sx.toFloat(), sy.toFloat()))
                if (b.level <= 1 || r > 7f) {
                    // #1921 a label starting at or past the canvas edge leaves negative room and drawText throws: skip it
                    GraphCamera.labelOrigin(Offset(sx.toFloat(), sy.toFloat()), r, this.size.width, this.size.height)?.let { at ->
                        drawText(measurer, b.label, topLeft = at, style = label, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** A small toggle of the 3D view (play / pause, reset): filled when on. */
@Composable
private fun ViewChip(text: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.padding(start = KitStoreRow.gap).clip(shape).background(if (on) p.accent else p.surface)
            .border(1.dp, if (on) p.accent else p.hairline, shape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = if (on) p.tileInk else p.textPrimary, fontSize = KitStoreRow.body, maxLines = 1)
    }
}
