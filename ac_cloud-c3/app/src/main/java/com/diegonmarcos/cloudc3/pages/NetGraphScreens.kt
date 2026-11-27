package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.safeDrawText

/** Test tags of the Network viewer switch and the Graph view. */
object NetTags {
    fun view(page: String, mode: String) = "net_view_${page}_$mode"
    fun graph(page: String) = "net_graph_$page"
    fun hit(id: String) = "net_graph_hit_$id"
    fun sheet(id: String) = "net_graph_sheet_$id"
    fun edgeSheet(id: String) = "net_graph_edge_$id"
    fun neighbour(id: String) = "net_graph_nb_$id"
    const val SEARCH = "net_graph_search"
    const val BANNER = "net_graph_banner"
    const val NOT_READ = "net_graph_not_read"
    const val ISOLATE = "net_graph_isolate"
    const val FIT = "net_graph_fit"
    const val WIDTH = "net_graph_width"
    const val EXPAND_ALL = "net_graph_expand_all"
    const val COLLAPSE_ALL = "net_graph_collapse_all"
    const val LEGEND = "net_graph_legend"
    const val LEGEND_BODY = "net_graph_legend_body"
    const val REREAD = "net_graph_reread"
    const val OPEN_TABLE = "net_graph_open_table"
    const val OPEN_CONTAINER = "net_graph_open_container"
    const val REPROBE = "net_graph_reprobe"
    const val EXPAND = "net_graph_expand"
    const val HIGHLIGHT = "net_graph_highlight"
    const val CLOSE = "net_graph_close"
    const val MINIMAP = "net_graph_minimap"
}

private fun mono(size: Float, c: Color, w: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, color = c, fontWeight = w)

@Composable
private fun Tab(tag: String, label: String, on: Boolean, click: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(6.dp)
    Text(label, style = mono(11f, if (on) p.tileInk else p.textPrimary),
        modifier = Modifier.padding(end = 6.dp).background(if (on) p.accent else p.surface, shape).border(1.dp, p.hairline, shape)
            .clickable(onClick = click).padding(horizontal = 10.dp, vertical = 4.dp).testTag(tag))
}

/** [Table|Graph]: the viewer switch above every Network subpage (the declared modes, in order). */
@Composable
fun ViewerSwitch(page: String, modes: List<String>, mode: String, onMode: (String) -> Unit) {
    Row(Modifier.padding(horizontal = KitStoreRow.padH, vertical = 3.dp)) {
        modes.forEach { m -> Tab(NetTags.view(page, m), m.replaceFirstChar { it.uppercase() }, m == mode) { onMode(m) } }
    }
}

/** The four statuses with a glyph and a word, so colour is never the only signal. */
fun statusGlyph(h: MailStack.Health): String = when (h) { MailStack.Health.OK -> "o"; MailStack.Health.WARN -> "!"; MailStack.Health.BAD -> "x"; MailStack.Health.UNKNOWN -> "?" }
fun statusWord(h: MailStack.Health): String = when (h) { MailStack.Health.OK -> "up"; MailStack.Health.WARN -> "degraded"; MailStack.Health.BAD -> "down"; MailStack.Health.UNKNOWN -> "unknown" }

/** Search over label, sub-line and id, case-insensitive, in graph order. */
fun graphSearch(g: NetGraph.Graph, q: String): List<NetGraph.GNode> {
    val t = q.trim().lowercase()
    if (t.isEmpty()) return emptyList()
    return g.nodes.filter { n -> n.label.lowercase().contains(t) || n.sub.lowercase().contains(t) || n.id.lowercase().contains(t) }
}

/** The ordered hop chain through [e]: back along in-edges to a node with none, forward along out-edges to a node with none (first edge each step). */
fun pathThrough(g: NetGraph.Graph, e: NetGraph.GEdge): List<String> {
    val back = mutableListOf<String>(); var at = e.from; val seen = mutableSetOf(e.from, e.to)
    while (true) { val i = g.inEdges(at).firstOrNull { it.from !in seen } ?: break; back.add(0, i.from); seen += i.from; at = i.from }
    val fwd = mutableListOf<String>(); at = e.to
    while (true) { val o = g.outEdges(at).firstOrNull { it.to !in seen } ?: break; fwd += o.to; seen += o.to; at = o.to }
    return back + listOf(e.from, e.to) + fwd
}

/** Everything the Graph shows for one page: the graph, its readings, the roots, the live time and what failed to read. */
data class GraphState(
    val graph: NetGraph.Graph, val readings: Map<String, NetLive.Reading>, val roots: List<NetLive.Root>,
    val anyRead: Boolean, val notReadWhy: String, val liveAt: String,
)

@Composable
fun NetGraphScreen(
    page: String, st: GraphState, v: NetGraph.Viewers,
    onOpenTable: (NetGraph.GNode) -> Unit, onOpenContainer: (String) -> Unit, onReprobe: (NetGraph.GNode) -> Unit, onReread: () -> Unit,
    initialOpen: String? = null,
) {
    val p = LocalKitPalette.current
    val g = st.graph
    var collapsed by remember(g.page) { mutableStateOf(NetGraphLayout.defaultCollapsed(g, v)) }
    var query by remember { mutableStateOf("") }
    var hitAt by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(initialOpen) }
    var openEdge by remember { mutableStateOf<String?>(null) }
    var legend by remember { mutableStateOf(false) }
    var isolate by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Set<String>>(emptySet()) }
    val viewState = remember { mutableStateOf<MailStack.View?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val root = st.roots.firstOrNull()
    val impacted = root?.impacted.orEmpty()
    val status = remember(st) { st.readings.mapValues { it.value.health } }
    val layout = remember(g, collapsed, status, p) { NetGraphLayout.layout(g, v, collapsed, p, status) }
    val hits = remember(g, query) { graphSearch(g, query) }
    val matchIds = hits.map { layout.extras.memberOf[it.id] ?: it.id }.toSet()
    fun centre(id: String) {
        val drawnId = layout.extras.memberOf[id] ?: id
        val r = layout.plan.nodes.firstOrNull { it.id == drawnId }?.rect ?: return
        val cur = viewState.value ?: NetGraphLayout.fitWidth(layout.plan.bounds, box.width.toFloat().coerceAtLeast(1f))
        viewState.value = cur.centerOn(r, box.width.toFloat(), box.height.toFloat())
    }
    fun openNode(id: String) {
        // a hidden member opens with its cluster expanded (search expands the cluster that holds a match)
        g.clusters.filter { id in it.members && it.id in collapsed }.forEach { c -> collapsed = collapsed - c.id }
        open = id; openEdge = null
    }
    Column(Modifier.fillMaxSize().testTag(NetTags.graph(page) + "_screen")) {
        if (!st.anyRead) Text("Live state not read: ${st.notReadWhy.ifBlank { "no source answered yet" }}. Showing the declared topology.",
            style = mono(11f, p.warn), modifier = Modifier.padding(KitStoreRow.padH, 3.dp).testTag(NetTags.NOT_READ))
        if (root != null && !dismissed) {
            Row(Modifier.fillMaxWidth().background(p.bad.copy(alpha = 0.12f)).padding(horizontal = KitStoreRow.padH, vertical = 4.dp).testTag(NetTags.BANNER),
                verticalAlignment = Alignment.CenterVertically) {
                Text("! ROOT CAUSE  ${g.node(root.id)?.label ?: root.id} (${NetLive.verdict(root)}): ${root.why}   ${root.impacted.size} impacted",
                    style = mono(11f, p.bad, FontWeight.Bold), modifier = Modifier.weight(1f).clickable { openNode(root.id) })
                Tab(NetTags.ISOLATE, "Isolate", isolate) { isolate = !isolate }
                Tab(NetTags.CLOSE + "_banner", "x", false) { dismissed = true }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(query, { query = it; hitAt = 0 }, singleLine = true, cursorBrush = SolidColor(p.accent), textStyle = mono(12f, p.textPrimary),
                modifier = Modifier.width(150.dp).border(1.dp, p.hairline, RoundedCornerShape(6.dp)).padding(6.dp).testTag(NetTags.SEARCH))
            if (hits.isNotEmpty()) {
                Text(" ${hitAt + 1}/${hits.size} ", style = mono(11f, p.textSecondary))
                Tab("net_graph_prev", "<", false) { hitAt = (hitAt - 1 + hits.size) % hits.size; openNode(hits[hitAt].id); centre(hits[hitAt].id) }
                Tab("net_graph_next", ">", false) { hitAt = (hitAt + 1) % hits.size; openNode(hits[hitAt].id); centre(hits[hitAt].id) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab(NetTags.FIT, "Fit", false) { viewState.value = MailStack.fit(layout.plan.bounds, box.width.toFloat(), box.height.toFloat()) }
            Tab(NetTags.WIDTH, "Width", false) { viewState.value = NetGraphLayout.fitWidth(layout.plan.bounds, box.width.toFloat()) }
            Tab(NetTags.EXPAND_ALL, "+all", false) { collapsed = emptySet() }
            Tab(NetTags.COLLAPSE_ALL, "-all", false) { collapsed = g.clusters.map { it.id }.toSet() }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab(NetTags.LEGEND, "Legend", legend) { legend = !legend }
            Tab(NetTags.REREAD, "Re-read", false, onReread)
            Text("live ${st.liveAt.ifBlank { "-" }}", style = mono(11f, p.textSecondary))
        }
        if (hits.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH)) {
            hits.take(30).forEach { n -> Tab(NetTags.hit(n.id), n.label, n.id == open) { openNode(n.id); centre(n.id) } }
        }
        if (legend) Legend()
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }) {
            val drawnSummary = MailStack.drawnSummary(layout.plan)
            val density = LocalDensity.current.density
            val measurer = rememberTextMeasurer()
            val dim = when {
                highlight.isNotEmpty() -> highlight
                isolate && root != null -> impacted + root.id
                query.isNotBlank() -> matchIds
                else -> null
            }
            PlanCanvas(layout.plan, NetTags.graph(page), Modifier.fillMaxSize(),
                onEdge = { id -> openEdge = id; open = null },
                viewState = viewState,
                initialView = { w, _ -> NetGraphLayout.fitWidth(layout.plan.bounds, w) },
                pulses = layout.plan.edges.size <= v.pulseMaxEdges,
                under = { view ->
                    layout.extras.laneRects.entries.forEachIndexed { i, (lane, r) ->
                        val tl = view.toScreen(Offset(r.l, r.t)); val br = view.toScreen(Offset(r.r, r.b))
                        drawRect(if (i % 2 == 0) p.hairline.copy(alpha = 0.10f) else p.hairline.copy(alpha = 0.04f), tl, Size(br.x - tl.x, br.y - tl.y))
                        val gl = g.lanes.firstOrNull { it.id == lane }
                        val head = (gl?.label ?: lane).ifBlank { g.page } + (gl?.sub?.takeIf { it.isNotBlank() }?.let { "  $it" } ?: "") +
                            (if (root != null && g.node(root.id)?.lane == lane) "  x ROOT" else "")
                        safeDrawText(measurer, head, Offset(tl.x + 6f * density, tl.y + 4f * density), mono(10f, p.textSecondary, FontWeight.Bold), maxLines = 1, softWrap = false)
                    }
                    g.columns.forEachIndexed { c, label ->
                        val x = view.toScreen(Offset(c * (v.nodeW + v.gapX), 0f)).x
                        safeDrawText(measurer, label.uppercase(), Offset(x, 2f * density), mono(9f, p.accent), maxLines = 1, softWrap = false)
                    }
                    layout.extras.clusterRects.forEach { (_, r) ->
                        val tl = view.toScreen(Offset(r.l, r.t)); val br = view.toScreen(Offset(r.r, r.b))
                        drawRoundRect(p.accent.copy(alpha = 0.5f), tl, Size(br.x - tl.x, br.y - tl.y), CornerRadius(6f * density),
                            style = Stroke(1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                    }
                    layout.extras.bundleLabels.forEach { (at, label) ->
                        safeDrawText(measurer, label, view.toScreen(at), mono(9f, p.textSecondary), maxLines = 1, softWrap = false)
                    }
                },
                over = { view ->
                    layout.plan.nodes.forEach { n ->
                        val tl = view.toScreen(Offset(n.rect.l, n.rect.t)); val br = view.toScreen(Offset(n.rect.r, n.rect.b))
                        val sz = Size(br.x - tl.x, br.y - tl.y)
                        val members = layout.extras.aggregates[n.id]?.let { cid -> g.clusters.firstOrNull { it.id == cid }?.members }.orEmpty() + n.id
                        if (dim != null && members.none { it in dim }) drawRect(p.surface.copy(alpha = 0.65f), tl, sz)
                        if (root != null && root.id in members) {
                            drawRoundRect(p.bad, Offset(tl.x - 4f * density, tl.y - 4f * density), Size(sz.width + 8f * density, sz.height + 8f * density), CornerRadius(10f * density), style = Stroke(2f * density))
                            drawRoundRect(p.bad, Offset(tl.x - 8f * density, tl.y - 8f * density), Size(sz.width + 16f * density, sz.height + 16f * density), CornerRadius(12f * density), style = Stroke(2f * density))
                            safeDrawText(measurer, "ROOT", Offset(br.x - 34f * density, tl.y - 16f * density), mono(10f, p.bad, FontWeight.Bold), maxLines = 1, softWrap = false)
                        } else if (members.any { it in impacted }) {
                            drawRoundRect(p.warn, tl, sz, CornerRadius(8f * density), style = Stroke(2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
                        }
                        if (n.id in matchIds) drawRoundRect(p.accent, Offset(tl.x - 3f * density, tl.y - 3f * density), Size(sz.width + 6f * density, sz.height + 6f * density), CornerRadius(10f * density), style = Stroke(2.5f * density))
                        // the status glyph and word on every node: colour is never the only signal
                        val word = st.readings[n.id]?.word ?: if (n.kind == "cluster") "" else "declared"
                        if (view.scale >= 0.45f) safeDrawText(measurer, "${statusGlyph(n.health)} ${statusWord(n.health)}" + if (word.isBlank()) "" else " · $word",
                            Offset(tl.x + 12f * density * view.scale, br.y - 14f * density * view.scale.coerceIn(0.7f, 1.4f)), mono(8f * view.scale.coerceIn(0.8f, 1.4f), n.color), maxLines = 1, softWrap = false)
                    }
                },
                description = drawnSummary,
            ) { id -> openNode(layout.extras.aggregates[id]?.let { _ -> id } ?: id) }
            MiniMap(layout, viewState.value ?: NetGraphLayout.fitWidth(layout.plan.bounds, box.width.toFloat().coerceAtLeast(1f)), box,
                Modifier.align(Alignment.BottomEnd)) { at ->
                val cur = viewState.value ?: NetGraphLayout.fitWidth(layout.plan.bounds, box.width.toFloat())
                viewState.value = cur.centerOn(MailStack.Rect(at.x, at.y, at.x, at.y), box.width.toFloat(), box.height.toFloat())
            }
            Box(Modifier.align(Alignment.BottomCenter)) {
                open?.let { id ->
                    val cid = layout.extras.aggregates[id]
                    if (cid != null && id.startsWith("agg:")) {
                        val c = g.clusters.first { it.id == cid }
                        ClusterSheet(c, g, st.readings, onExpand = { collapsed = collapsed - c.id; open = null }, onPick = { openNode(it) }, onClose = { open = null })
                    } else g.node(id)?.let { n ->
                        NetNodeSheet(n, g, st.readings[n.id] ?: NetLive.DECLARED, st.roots, impacted,
                            onPick = { openNode(it) }, onEdge = { openEdge = it; open = null },
                            onOpenTable = { onOpenTable(n) }, onOpenContainer = onOpenContainer, onReprobe = { onReprobe(n) },
                            onHighlight = { highlight = if (highlight.isEmpty()) closure(g, n.id) else emptySet() },
                            onExpand = g.clusters.firstOrNull { it.head == n.id && it.id in collapsed }?.let { c -> { collapsed = collapsed - c.id } },
                            onClose = { open = null })
                    }
                }
                openEdge?.let { eid ->
                    val members = layout.extras.edgeMembers[eid].orEmpty()
                    val e = (g.edges + g.depends).firstOrNull { it.id == eid } ?: (g.edges + g.depends).firstOrNull { it.id == members.firstOrNull() }
                    if (e != null) NetEdgeSheet(e, members.size, g, st.readings, onPick = { openNode(it) },
                        onHighlight = { highlight = if (highlight.isEmpty()) pathThrough(g, e).toSet() else emptySet() }, onClose = { openEdge = null })
                }
            }
        }
    }
}

/** Upstream and downstream closure of a node over the graph's edges (the long-press highlight). */
fun closure(g: NetGraph.Graph, id: String): Set<String> {
    val out = mutableSetOf(id)
    fun walk(next: (String) -> List<String>) { val todo = ArrayDeque(listOf(id)); while (todo.isNotEmpty()) next(todo.removeFirst()).forEach { if (out.add(it)) todo += it } }
    walk { x -> g.outEdges(x).map { it.to } }
    walk { x -> g.inEdges(x).map { it.from } }
    return out
}

@Composable
private fun Legend() {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = 3.dp).border(1.dp, p.hairline, RoundedCornerShape(6.dp)).padding(6.dp).testTag(NetTags.LEGEND_BODY)) {
        listOf(MailStack.Health.OK, MailStack.Health.WARN, MailStack.Health.BAD, MailStack.Health.UNKNOWN).forEach { h ->
            Text("${statusGlyph(h)} ${statusWord(h)}", style = mono(11f, MailStack.healthColor(h, p)))
        }
        Text("double red ring + ROOT = root cause; dashed amber outline = impacted via the root", style = mono(10f, p.textSecondary))
        Text("dashed wire = planned or fallback, or a declared dependency; grey 'declared' = no live binding", style = mono(10f, p.textSecondary))
        Text("trunk thickness = how many table edges it stands for; dashed box = an open cluster", style = mono(10f, p.textSecondary))
    }
}

@Composable
private fun MiniMap(l: NetGraphLayout.Layout, view: MailStack.View, box: IntSize, modifier: Modifier, onJump: (Offset) -> Unit) {
    val b = l.plan.bounds
    val worldH = (b.b - b.t) * view.scale
    if (box.height <= 0 || worldH <= 1.5f * box.height) return
    val p = LocalKitPalette.current
    val w = 96.dp
    val ratio = ((b.b - b.t) / (b.r - b.l).coerceAtLeast(1f)).coerceIn(0.3f, 3f)
    Canvas(modifier.padding(6.dp).size(w, w * ratio).background(p.surface.copy(alpha = 0.9f)).border(1.dp, p.hairline).testTag(NetTags.MINIMAP)
        .pointerInput(l) { detectTapGestures { at -> onJump(Offset(b.l + at.x / size.width * (b.r - b.l), b.t + at.y / size.height * (b.b - b.t))) } }) {
        val sx = size.width / (b.r - b.l).coerceAtLeast(1f); val sy = size.height / (b.b - b.t).coerceAtLeast(1f)
        l.plan.nodes.forEach { n -> drawRect(n.color, Offset((n.rect.l - b.l) * sx, (n.rect.t - b.t) * sy), Size(((n.rect.r - n.rect.l) * sx).coerceAtLeast(1f), ((n.rect.b - n.rect.t) * sy).coerceAtLeast(1f))) }
        val tl = view.toWorld(Offset(0f, 0f)); val br = view.toWorld(Offset(box.width.toFloat(), box.height.toFloat()))
        drawRect(p.accent, Offset((tl.x - b.l) * sx, (tl.y - b.t) * sy), Size((br.x - tl.x) * sx, (br.y - tl.y) * sy), style = Stroke(1.5f))
    }
}

@Composable
private fun Line(k: String, v: String, tag: String? = null, onTap: (() -> Unit)? = null) {
    val p = LocalKitPalette.current
    Row(Modifier.padding(vertical = 1.dp).then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Text(k, style = mono(11f, p.textSecondary), modifier = Modifier.width(92.dp))
        Text(v.ifBlank { "-" }, style = mono(11f, if (onTap != null) p.accent else p.textPrimary))
    }
}

@Composable
private fun SheetFrame(tag: String, border: Color, title: String, status: String, statusInk: Color, onClose: () -> Unit, body: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).background(p.surface).border(1.dp, border, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        .verticalScroll(rememberScrollState()).padding(12.dp).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = mono(14f, p.textPrimary, FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(status, style = mono(12f, statusInk))
            Text("  close", style = mono(12f, p.accent), modifier = Modifier.clickable(onClick = onClose).padding(8.dp).testTag(NetTags.CLOSE))
        }
        body()
    }
}

/** The sheet of one node: status and its reading, every field of the table rows it stands for, its edges (tappable), dependencies, impact, actions. */
@Composable
fun NetNodeSheet(
    n: NetGraph.GNode, g: NetGraph.Graph, r: NetLive.Reading, roots: List<NetLive.Root>, impacted: Set<String>,
    onPick: (String) -> Unit, onEdge: (String) -> Unit, onOpenTable: () -> Unit, onOpenContainer: (String) -> Unit, onReprobe: () -> Unit,
    onHighlight: () -> Unit, onExpand: (() -> Unit)?, onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    val ink = MailStack.healthColor(r.health, p)
    SheetFrame(NetTags.sheet(n.id), ink, n.label, "${statusGlyph(r.health)} ${statusWord(r.health)}", ink, onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            Tab(NetTags.OPEN_TABLE, "Open in Table", false, onOpenTable)
            n.bind.firstOrNull { it.startsWith("container:") }?.let { b -> Tab(NetTags.OPEN_CONTAINER, "Open container", false) { onOpenContainer(b.removePrefix("container:")) } }
            Tab(NetTags.REPROBE, "Re-probe", false, onReprobe)
            Tab(NetTags.HIGHLIGHT, "Highlight", false, onHighlight)
            onExpand?.let { Tab(NetTags.EXPAND, "Expand", false, it) }
        }
        Line("reading", r.word); Line("source", r.source + if (r.at > 0) "  at ${clock(r.at)}" else "")
        roots.firstOrNull { it.id == n.id }?.let { Line("root cause", "${NetLive.verdict(it)}, impacts ${it.impacted.size}") }
        roots.firstOrNull { n.id in it.impacted }?.let { Line("impacted via", g.node(it.id)?.label ?: it.id) }
        val ins = g.inEdges(n.id); val outs = g.outEdges(n.id)
        Line("in-edges", "${ins.size}")
        ins.forEach { e -> Line("  <-", "${g.node(e.from)?.label ?: e.from}  ${e.label}", NetTags.neighbour(e.from)) { onEdge(e.id) } }
        Line("out-edges", "${outs.size}")
        outs.forEach { e -> Line("  ->", "${g.node(e.to)?.label ?: e.to}  ${e.label}", NetTags.neighbour(e.to)) { onEdge(e.id) } }
        NetGraph.sheet(n, g).forEach { (k, v) -> if (k != "in" && k != "out") Line(k, v) }
        val dependents = NetLive.dependsOn(g).filterValues { n.id in it }.keys
        Line("impacts", "${dependents.size} depend on this")
        dependents.take(20).forEach { d -> Line("  ", g.node(d)?.label ?: d) { onPick(d) } }
        if (n.id in impacted && roots.none { it.id == n.id }) Line("note", "impacted: a node it depends on is failing")
    }
}

/** The sheet of a collapsed cluster: its member list (each opens its node) and Expand. */
@Composable
private fun ClusterSheet(c: NetGraph.GCluster, g: NetGraph.Graph, readings: Map<String, NetLive.Reading>, onExpand: () -> Unit, onPick: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    val h = NetGraphLayout.worst(c.members.map { readings[it]?.health ?: MailStack.Health.UNKNOWN })
    SheetFrame(NetTags.sheet(NetGraphLayout.aggId(c.id)), MailStack.healthColor(h, p), c.label, "${statusGlyph(h)} ${statusWord(h)}", MailStack.healthColor(h, p), onClose) {
        Row(Modifier.padding(vertical = 4.dp)) { Tab(NetTags.EXPAND, "Expand", false, onExpand) }
        c.members.forEach { m ->
            val r = readings[m] ?: NetLive.DECLARED
            Line(statusGlyph(r.health), (g.node(m)?.label ?: m) + "  " + r.word, NetTags.neighbour(m)) { onPick(m) }
        }
    }
}

/** The sheet of one edge (or a bundle): from, to, label, kind, status and reading, the hop chain through it, the table lines it stands for. */
@Composable
private fun NetEdgeSheet(e: NetGraph.GEdge, members: Int, g: NetGraph.Graph, readings: Map<String, NetLive.Reading>, onPick: (String) -> Unit, onHighlight: () -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    val r = readings[e.id] ?: NetLive.DECLARED
    val ink = MailStack.healthColor(r.health, p)
    SheetFrame(NetTags.edgeSheet(e.id), ink, "${g.node(e.from)?.label ?: e.from} -> ${g.node(e.to)?.label ?: e.to}", "${statusGlyph(r.health)} ${statusWord(r.health)}", ink, onClose) {
        Row(Modifier.padding(vertical = 4.dp)) { Tab(NetTags.HIGHLIGHT, "Highlight path", false, onHighlight) }
        Line("label", e.label); Line("kind", e.kind); Line("reading", r.word); Line("source", r.source)
        if (members > 1) Line("bundle", "$members edges")
        Line("dependency", when (e.dep) { NetGraph.Dep.FROM_NEEDS_TO -> "from needs to"; NetGraph.Dep.TO_NEEDS_FROM -> "to needs from"; else -> "none" })
        e.rows.forEach { ref -> Line("table", "${ref.section}: ${ref.from} -> ${ref.to}  ${ref.label}" + if (ref.extra.isBlank()) "" else "  ${ref.extra}") }
        e.bind.forEach { Line("bound to", it) }
        Line("path", "")
        pathThrough(g, e).forEach { id ->
            val h = readings[id]?.health ?: MailStack.Health.UNKNOWN
            Line("  ${statusGlyph(h)}", g.node(id)?.label ?: id) { onPick(id) }
        }
    }
}

fun clock(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
