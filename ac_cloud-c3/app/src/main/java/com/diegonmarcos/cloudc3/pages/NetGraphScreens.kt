package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.safeDrawText
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Test tags of the Network viewer switch and the Graph view. */
object NetTags {
    fun view(page: String, mode: String) = "net_view_${page}_$mode"
    fun graph(page: String) = "net_graph_$page"
    fun hit(id: String) = "net_graph_hit_$id"
    fun sheet(id: String) = "net_graph_sheet_$id"
    fun edgeSheet(id: String) = "net_graph_edge_$id"
    fun laneSheet(id: String) = "net_graph_lane_$id"
    fun neighbour(id: String) = "net_graph_nb_$id"
    const val ROOT = "net_viewer_root"
    const val CANVAS_BOX = "net_graph_canvas_box"
    const val SEARCH = "net_graph_search"
    const val BANNER = "net_graph_banner"
    const val CHIPS = "net_graph_chips"
    const val NOT_READ = "net_graph_not_read"
    const val DEVICE_NOTE = "net_graph_device_note"
    const val NOTE_SHEET = "net_graph_note_sheet"
    const val ISOLATE = "net_graph_isolate"
    const val FIT = "net_graph_fit"
    const val WIDTH = "net_graph_width"
    const val EXPAND_ALL = "net_graph_expand_all"
    const val COLLAPSE_ALL = "net_graph_collapse_all"
    const val LEGEND = "net_graph_legend"
    const val LEGEND_BODY = "net_graph_legend_body"
    const val REREAD = "net_graph_reread"
    const val HITS = "net_graph_hits"
    const val OPEN_TABLE = "net_graph_open_table"
    const val OPEN_CONTAINER = "net_graph_open_container"
    const val REPROBE = "net_graph_reprobe"
    const val EXPAND = "net_graph_expand"
    const val HIGHLIGHT = "net_graph_highlight"
    const val ZOOM = "net_graph_zoom"
    const val SHEET_MORE = "net_graph_sheet_more"
    const val CLOSE = "net_graph_close"
    const val MINIMAP = "net_graph_minimap"
    /** #1955 A rev2.1 the edge chooser (several wires under one tap) and its rows */
    const val CHOOSER = "net_graph_edge_chooser"
    fun choose(edgeId: String) = "net_graph_choose_$edgeId"
}

private fun mono(size: Float, c: Color, w: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, color = c, fontWeight = w)

/** #1955 A rev2.1 Two taps on one node within this many ms are a double tap (the platform's double-tap time). */
private const val DOUBLE_TAP_MS = 300L

/** #1955 A rev2 The style every canvas text is measured AND drawn with (so the measured box is the drawn box). */
fun netTextStyle(sp: Float, bold: Boolean, c: Color = Color.Unspecified) = mono(sp, c, if (bold) FontWeight.Bold else FontWeight.Normal)

/** #1955 A rev2 The scene's measure on a device or under Robolectric: Compose's TextMeasurer, one line, no wrap. */
fun netMeasure(m: TextMeasurer): NetScene.Measure = NetScene.Measure { text, sp, bold ->
    val r = m.measure(text, netTextStyle(sp, bold), maxLines = 1, softWrap = false).size
    r.width.toFloat() to r.height.toFloat()
}

@Composable
private fun Tab(tag: String, label: String, on: Boolean, desc: String? = null, click: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(6.dp)
    Text(label, style = mono(11f, if (on) p.tileInk else p.textPrimary), maxLines = 1,
        modifier = Modifier.padding(end = 6.dp).background(if (on) p.accent else p.surface, shape).border(1.dp, p.hairline, shape)
            .clickable(onClick = click).padding(horizontal = 8.dp, vertical = 4.dp).testTag(tag)
            .then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier))
}

/** [Table|Graph]: the viewer switch above every Network subpage (the declared modes, in order). */
@Composable
fun ViewerSwitch(page: String, modes: List<String>, mode: String, inRow: Boolean = false, onMode: (String) -> Unit) {
    Row(if (inRow) Modifier else Modifier.padding(horizontal = KitStoreRow.padH, vertical = 3.dp)) {
        modes.forEach { m -> Tab(NetTags.view(page, m), m.replaceFirstChar { it.uppercase() }, m == mode) { onMode(m) } }
    }
}

/** #1955 A rev2 In Graph mode the viewer switch sits in the Graph's first toolbar row (one row less of chrome). */
val LocalViewerSwitch = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** The four statuses with a glyph and a word, so colour is never the only signal (the glyphs stay in lists; the canvas draws shapes). */
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
    /** Device-side state (no C3 token); never a VM's fault. Blank when nothing is device-side. */
    val deviceNote: String = "",
)

/**
 * #1955 A rev2 What the sheets print, as pure data (the deferral parity test reads the same lines the sheet shows). Everything the
 * canvas no longer draws (reading text, sub line, edge and bundle labels, lane sub line, column label, the root's why) is here, in full.
 */
object NetSheets {
    data class SheetLine(val k: String, val v: String, val tag: String? = null, val pick: String? = null, val edge: String? = null)

    fun notReadSentence(st: GraphState) = "Live state not read: ${st.notReadWhy.ifBlank { "no source answered yet" }}. Showing the declared topology."
    fun deviceSentence(st: GraphState) = "Device: ${st.deviceNote}. Nodes show \"not read\"."

    /** The root-cause chip: one line ("■ ROOT name · N impacted ›"); the verdict and the full why are the root sheet's first lines. */
    fun rootChip(g: NetGraph.Graph, r: NetLive.Root): String {
        val n = g.node(r.id)
        return NetLabel.rootChip(n?.label ?: r.id, g.nodes.filter { it.ns == n?.ns }.map { it.label }, r.impacted.size)
    }
    fun rootSentence(g: NetGraph.Graph, r: NetLive.Root) = "ROOT CAUSE ${g.node(r.id)?.label ?: r.id} (${NetLive.verdict(r)}): ${r.why}   ${r.impacted.size} impacted"

    fun laneText(g: NetGraph.Graph, laneId: String): Pair<String, String> {
        val gl = g.lanes.firstOrNull { it.id == laneId }
        return (gl?.label ?: laneId).ifBlank { g.page } to gl?.sub.orEmpty()
    }

    fun node(n: NetGraph.GNode, g: NetGraph.Graph, r: NetLive.Reading, roots: List<NetLive.Root>, impacted: Set<String>, prim: MailStack.NodePrim?): List<SheetLine> = buildList {
        roots.firstOrNull { it.id == n.id }?.let { rt ->
            add(SheetLine("root cause", "${NetLive.verdict(rt)}, impacts ${rt.impacted.size}"))
            add(SheetLine("why", rt.why))
        }
        add(SheetLine("reading", r.word)); add(SheetLine("source", r.source + if (r.at > 0) "  at ${clock(r.at)}" else ""))
        if (r.why.isNotBlank() && r.why != r.word) add(SheetLine("read error", r.why))
        roots.firstOrNull { n.id in it.impacted }?.let { add(SheetLine("impacted via", g.node(it.id)?.label ?: it.id)) }
        prim?.let { add(SheetLine("summary", MailStack.healthLabel(it.health) + if (it.sub.isBlank()) "" else "  " + it.sub)) }
        val (ll, ls) = laneText(g, n.lane)
        add(SheetLine("lane", ll + if (ls.isBlank()) "" else "  $ls"))
        g.columns.getOrNull(n.column)?.let { add(SheetLine("column", it)) }
        val ins = g.inEdges(n.id); val outs = g.outEdges(n.id)
        add(SheetLine("in-edges", "${ins.size}"))
        ins.forEach { e -> add(SheetLine("  <-", "${g.node(e.from)?.label ?: e.from}  ${e.label}", NetTags.neighbour(e.from), edge = e.id)) }
        add(SheetLine("out-edges", "${outs.size}"))
        outs.forEach { e -> add(SheetLine("  ->", "${g.node(e.to)?.label ?: e.to}  ${e.label}", NetTags.neighbour(e.to), edge = e.id)) }
        NetGraph.sheet(n, g).forEach { (k, v) -> if (k != "in" && k != "out") add(SheetLine(k, v)) }
        val dependents = NetLive.dependsOn(g).filterValues { n.id in it }.keys
        add(SheetLine("impacts", "${dependents.size} depend on this"))
        dependents.take(20).forEach { d -> add(SheetLine("  ", g.node(d)?.label ?: d, pick = d)) }
        if (n.id in impacted && roots.none { it.id == n.id }) add(SheetLine("note", "impacted: a node it depends on is failing"))
    }

    fun cluster(c: NetGraph.GCluster, g: NetGraph.Graph, readings: Map<String, NetLive.Reading>, prim: MailStack.NodePrim?): List<SheetLine> = buildList {
        prim?.let { add(SheetLine("summary", MailStack.healthLabel(it.health) + if (it.sub.isBlank()) "" else "  " + it.sub)) }
        val (ll, ls) = laneText(g, c.lane)
        add(SheetLine("lane", ll + if (ls.isBlank()) "" else "  $ls"))
        c.members.forEach { m ->
            val r = readings[m] ?: NetLive.DECLARED
            add(SheetLine(statusGlyph(r.health), (g.node(m)?.label ?: m) + "  " + r.word, NetTags.neighbour(m), pick = m))
        }
    }

    fun edge(e: NetGraph.GEdge, members: List<String>, prim: MailStack.EdgePrim?, bundle: String?, g: NetGraph.Graph, readings: Map<String, NetLive.Reading>): List<SheetLine> = buildList {
        val r = readings[e.id] ?: NetLive.DECLARED
        add(SheetLine("label", e.label)); add(SheetLine("kind", e.kind)); add(SheetLine("reading", r.word)); add(SheetLine("source", r.source))
        if (members.size > 1) add(SheetLine("bundle", "${members.size} edges"))
        prim?.takeIf { it.label.isNotBlank() && it.label != e.label }?.let { add(SheetLine("wire", it.label)) }
        bundle?.let { add(SheetLine("trunk", it)) }
        members.filter { it != e.id }.forEach { m -> (g.edges + g.depends).firstOrNull { it.id == m }?.let { o ->
            add(SheetLine("  member", "${g.node(o.from)?.label ?: o.from} -> ${g.node(o.to)?.label ?: o.to}  ${o.label}")) } }
        add(SheetLine("dependency", when (e.dep) { NetGraph.Dep.FROM_NEEDS_TO -> "from needs to"; NetGraph.Dep.TO_NEEDS_FROM -> "to needs from"; else -> "none" }))
        e.rows.forEach { ref -> add(SheetLine("table", "${ref.section}: ${ref.from} -> ${ref.to}  ${ref.label}" + if (ref.extra.isBlank()) "" else "  ${ref.extra}")) }
        e.bind.forEach { add(SheetLine("bound to", it)) }
        add(SheetLine("path", ""))
        pathThrough(g, e).forEach { id ->
            val h = readings[id]?.health ?: MailStack.Health.UNKNOWN
            add(SheetLine("  ${statusGlyph(h)}", g.node(id)?.label ?: id, pick = id))
        }
    }

    fun frame(f: NetGraphLayout.Frame, g: NetGraph.Graph, l: NetGraphLayout.Layout): List<SheetLine> = buildList {
        add(SheetLine("frame", f.label)); if (f.sub.isNotBlank()) add(SheetLine("detail", f.sub))
        if (g.columns.isNotEmpty()) add(SheetLine("columns", g.columns.joinToString(" | ")))
        val inside = l.extras.frameOf.filterValues { it == f.id }.keys
        add(SheetLine("nodes", "${inside.size}"))
        inside.forEach { id -> add(SheetLine("  ", l.plan.nodes.firstOrNull { it.id == id }?.label ?: id, pick = id)) }
        if (f.owner.isNotEmpty()) l.extras.contained[f.owner].orEmpty().forEach { eid ->
            g.edges.firstOrNull { it.id == eid }?.let { e -> add(SheetLine("  contains", "${g.node(e.to)?.label ?: e.to}  ${e.label}")) }
        }
    }
}

@Composable
fun NetGraphScreen(
    page: String, st: GraphState, v: NetGraph.Viewers,
    onOpenTable: (NetGraph.GNode) -> Unit, onOpenContainer: (String) -> Unit, onReprobe: (NetGraph.GNode) -> Unit, onReread: () -> Unit,
    initialOpen: String? = null,
) {
    val p = LocalKitPalette.current
    val g = st.graph
    val kb = LocalSoftwareKeyboardController.current
    var collapsed by remember(g.page) { mutableStateOf(NetGraphLayout.defaultCollapsed(g, v)) }
    var query by remember { mutableStateOf("") }
    var hitAt by remember { mutableStateOf(0) }
    var hitsOpen by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf(initialOpen) }
    var openEdge by remember { mutableStateOf<String?>(null) }
    var openFrame by remember { mutableStateOf<String?>(null) }
    var openNote by remember { mutableStateOf<String?>(null) }
    var choose by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastTap by remember { mutableStateOf(0L to "") }
    var expanded by remember { mutableStateOf(false) }
    var legend by remember { mutableStateOf(false) }
    var isolate by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Set<String>>(emptySet()) }
    val viewState = remember { mutableStateOf<MailStack.View?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val root = st.roots.firstOrNull()
    val impacted = root?.impacted.orEmpty()
    val status = remember(st) { st.readings.mapValues { it.value.health } }
    val density = LocalDensity.current.density
    val sp = remember(v) { NetScene.Spec.of(v) }
    val layout = remember(g, collapsed, status, p, density) { NetGraphLayout.layout(g, v, collapsed, p, status, density) }
    val hits = remember(g, query) { graphSearch(g, query) }
    val sheetOpen = open != null || openEdge != null || openFrame != null || openNote != null || choose.isNotEmpty()
    // #1955 A rev2.1 the minimap fades to 35 % 1.5 s after the last gesture (every pan, zoom or jump sets a new view)
    var faded by remember { mutableStateOf(false) }
    LaunchedEffect(viewState.value) { faded = false; delay(1500); faded = true }
    val miniAlpha by animateFloatAsState(if (faded) 0.35f else 1f, label = "minimap")
    val peekPx = 132f * density
    fun opening(w: Float, h: Float) = NetGraphLayout.openFor(layout, v, w, h, density, root?.id)
    fun current() = viewState.value ?: opening(box.width.toFloat().coerceAtLeast(1f), box.height.toFloat().coerceAtLeast(1f))
    fun centre(id: String, minScale: Float = 0f) {
        val drawnId = layout.extras.memberOf[id] ?: id
        val r = layout.plan.nodes.firstOrNull { it.id == drawnId }?.rect ?: return
        val cur = current().let { if (it.scale < minScale) MailStack.View(minScale, it.ox, it.oy) else it }
        // centred in the canvas ABOVE the peek sheet
        viewState.value = NetGraphLayout.centreAbove(cur, r, box.width.toFloat(), box.height.toFloat(), peekPx)
    }
    fun openNode(id: String) {
        // a hidden member opens with its cluster expanded (search expands the cluster that holds a match)
        g.clusters.filter { id in it.members && it.id in collapsed }.forEach { c -> collapsed = collapsed - c.id }
        open = id; openEdge = null; openFrame = null; openNote = null; choose = emptyList(); expanded = false
        kb?.hide()
    }
    fun pick(id: String) { openNode(id); hitsOpen = false; centre(id, 1f) }
    Column(Modifier.fillMaxSize().testTag(NetTags.graph(page) + "_screen")) {
        // row 1: the viewer switch, then Legend and Re-read (the live time is the Re-read label)
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row { LocalViewerSwitch.current?.invoke() }
            Row {
                Tab(NetTags.LEGEND, "Legend", legend) { legend = !legend }
                Tab(NetTags.REREAD, "↻ ${st.liveAt.ifBlank { "Re-read" }}", false, "Re-read, live ${st.liveAt.ifBlank { "-" }}", onReread)
            }
        }
        // row 2: search, Fit, Width, +all, -all
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(query, { query = it; hitAt = 0; hitsOpen = true }, singleLine = true, cursorBrush = SolidColor(p.accent), textStyle = mono(12f, p.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { hits.getOrNull(hitAt)?.let { pick(it.id) } ?: kb?.hide() }),
                modifier = Modifier.weight(1f).padding(end = 6.dp).border(1.dp, p.hairline, RoundedCornerShape(6.dp)).padding(6.dp).testTag(NetTags.SEARCH),
                decorationBox = { inner -> if (query.isEmpty()) Text("search", style = mono(12f, p.textSecondary)); inner() })
            Tab(NetTags.FIT, "Fit", false) { viewState.value = MailStack.fit(layout.plan.bounds, box.width.toFloat(), box.height.toFloat()) }
            Tab(NetTags.WIDTH, "Width", false) { viewState.value = NetGraphLayout.fitWidth(layout.plan.bounds, box.width.toFloat()) }
            Tab(NetTags.EXPAND_ALL, "+all", false) { collapsed = emptySet() }
            Tab(NetTags.COLLAPSE_ALL, "-all", false) { collapsed = g.clusters.map { it.id }.toSet() }
        }
        // the chip row: root cause, Isolate, dismiss, not read, device note; one line, absent when empty
        val chips = (root != null && !dismissed) || !st.anyRead || st.deviceNote.isNotBlank()
        // one line by construction: every chip is maxLines = 1 (no height cap that would hide a wrapped chip)
        if (chips) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = 2.dp).testTag(NetTags.CHIPS),
            verticalAlignment = Alignment.CenterVertically) {
            if (root != null && !dismissed) {
                val shape = RoundedCornerShape(6.dp)
                Text(NetSheets.rootChip(g, root), style = mono(10f, p.bad, FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)
                        .padding(end = 6.dp).border(1.dp, p.bad, shape).clickable { pick(root.id) }.padding(horizontal = 6.dp, vertical = 4.dp)
                        .semantics { contentDescription = NetSheets.rootSentence(g, root) }.testTag(NetTags.BANNER))
                Tab(NetTags.ISOLATE, "Isolate", isolate) { isolate = !isolate }
                Tab(NetTags.CLOSE + "_banner", "x", false) { dismissed = true }
            }
            if (!st.anyRead) Chip(NetTags.NOT_READ, "? live not read ›", p.warn, NetSheets.notReadSentence(st)) { open = null; openEdge = null; openFrame = null; openNote = "not_read" }
            if (st.deviceNote.isNotBlank()) Chip(NetTags.DEVICE_NOTE, "! no device token ›", p.warn, NetSheets.deviceSentence(st)) { open = null; openEdge = null; openFrame = null; openNote = "device" }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { box = it }.testTag(NetTags.CANVAS_BOX)) {
            val measurer = rememberTextMeasurer()
            val measure = remember(measurer) { netMeasure(measurer) }
            val focus: Set<String>? = when {
                highlight.isNotEmpty() -> highlight
                open != null -> open!!.let { o ->
                    val d = layout.extras.memberOf[o] ?: o
                    (layout.plan.edges.filter { it.from == d || it.to == d }.flatMap { listOf(it.from, it.to) } + o + d).toSet()
                }
                openEdge != null -> layout.plan.edges.firstOrNull { it.id == openEdge }?.let { setOf(it.from, it.to) }
                isolate && root != null -> impacted + root.id
                query.isNotBlank() -> hits.map { it.id }.toSet()
                else -> null
            }
            val legendRect = if (legend) MailStack.Rect(box.width - 248f * density, 0f, box.width.toFloat(), 228f * density) else null
            val hitRows = if (query.isNotBlank() && hits.isNotEmpty() && hitsOpen) minOf(hits.size, 4) else 0
            val hitRect = if (hitRows > 0) MailStack.Rect(0f, 0f, box.width.toFloat(), (28f + hitRows * 36f) * density) else null
            PlanCanvas(layout.plan, NetTags.graph(page), Modifier.fillMaxSize(),
                viewState = viewState,
                initialView = { w, h -> opening(w, h) },
                pulses = false,
                description = MailStack.drawnSummary(layout.plan),
                drawScene = { view ->
                    val mini = NetScene.minimapFor(layout, view, size.width, size.height, density, sp, sheetOpen, listOfNotNull(legendRect, hitRect))
                    val sc = NetScene.build(layout, g, v, view, size.width, size.height, density,
                        NetScene.State(open, hits.map { it.id }.toSet(), root?.id, impacted, focus, listOfNotNull(mini, legendRect, hitRect)), measure, status)
                    drawNetScene(sc, p, measurer, density, st.readings)
                },
                onBackground = { at, view ->
                    // a wire (16 dp tolerance, stub or bus included); two or more within 6 dp open the chooser; else a frame header or nothing
                    val es = NetScene.hitEdges(layout, view, at, 16f * density, 6f * density)
                    open = null; openNote = null; expanded = false
                    when {
                        es.size >= 2 -> { choose = es; openEdge = null; openFrame = null }
                        es.size == 1 -> { openEdge = es[0]; choose = emptyList(); openFrame = null }
                        else -> {
                            val mini = NetScene.minimapFor(layout, view, box.width.toFloat(), box.height.toFloat(), density, sp, sheetOpen, listOfNotNull(legendRect, hitRect))
                            val sc = NetScene.build(layout, g, v, view, box.width.toFloat(), box.height.toFloat(), density, NetScene.State(hud = listOfNotNull(mini)), measure, status)
                            openEdge = null; choose = emptyList(); openFrame = NetScene.hitHeader(sc, at)
                        }
                    }
                },
            ) { id ->
                // #1955 A rev2.1 a second tap on the same node within the double-tap time: an aggregate expands in place, any other node
                // is zoomed to 1.5x and centred above its sheet (the first tap opened it; single taps are never delayed)
                val now = android.os.SystemClock.uptimeMillis()
                val cid = layout.extras.aggregates[id]
                if (lastTap.second == id && now - lastTap.first <= DOUBLE_TAP_MS) {
                    lastTap = 0L to ""
                    if (cid != null && cid in collapsed) { collapsed = collapsed - cid; open = null }
                    else layout.plan.nodes.firstOrNull { it.id == id }?.let { n ->
                        viewState.value = NetGraphLayout.centreAbove(MailStack.View(1.5f, 0f, 0f), n.rect, box.width.toFloat(), box.height.toFloat(), peekPx)
                    }
                } else {
                    lastTap = now to id
                    openNode(id); centre(id)
                }
            }
            val view = current()
            if (!sheetOpen) NetScene.minimapFor(layout, view, box.width.toFloat(), box.height.toFloat(), density, sp, false, listOfNotNull(legendRect, hitRect))?.let { r ->
                MiniMap(layout, view, box, r, miniAlpha) { at -> viewState.value = current().centerOn(MailStack.Rect(at.x, at.y, at.x, at.y), box.width.toFloat(), box.height.toFloat()) }
            }
            if (hitRows > 0) HitList(hits, hitAt, layout, st.readings, Modifier.align(Alignment.TopStart),
                onPrev = { hitAt = (hitAt - 1 + hits.size) % hits.size; pick(hits[hitAt].id) },
                onNext = { hitAt = (hitAt + 1) % hits.size; pick(hits[hitAt].id) }) { n -> hitAt = hits.indexOf(n).coerceAtLeast(0); pick(n.id) }
            if (legend) Legend(Modifier.align(Alignment.TopEnd))
            val maxSheet = with(LocalDensity.current) { (box.height * 0.6f).toDp() }.coerceAtLeast(132.dp)
            Box(Modifier.align(Alignment.BottomCenter)) {
                val ctl = SheetCtl(expanded, maxSheet) { expanded = !expanded }
                open?.let { id ->
                    val cid = layout.extras.aggregates[id]
                    val prim = layout.plan.nodes.firstOrNull { it.id == id }
                    if (cid != null && id.startsWith("agg:")) {
                        val c = g.clusters.first { it.id == cid }
                        ClusterSheet(c, g, st.readings, prim, ctl, onExpand = { collapsed = collapsed - c.id; open = null }, onPick = { openNode(it) }, onClose = { open = null })
                    } else g.node(id)?.let { n ->
                        NetNodeSheet(n, g, st.readings[n.id] ?: NetLive.DECLARED, st.roots, impacted, prim, ctl,
                            onPick = { openNode(it) }, onEdge = { openEdge = it; open = null },
                            onOpenTable = { onOpenTable(n) }, onOpenContainer = onOpenContainer, onReprobe = { onReprobe(n) },
                            onHighlight = { highlight = if (highlight.isEmpty()) closure(g, n.id) else emptySet() },
                            onExpand = g.clusters.firstOrNull { it.head == n.id && it.id in collapsed }?.let { c -> { collapsed = collapsed - c.id } },
                            onZoom = { val o = opening(box.width.toFloat(), box.height.toFloat()); viewState.value = MailStack.View(o.scale * 2f, o.ox, o.oy); centre(n.id) },
                            onClose = { open = null })
                    }
                }
                openEdge?.let { eid ->
                    val members = layout.extras.edgeMembers[eid].orEmpty()
                    val e = (g.edges + g.depends).firstOrNull { it.id == eid } ?: (g.edges + g.depends).firstOrNull { it.id == members.firstOrNull() }
                    if (e != null) NetEdgeSheet(e, "${g.node(e.from)?.label ?: e.from} -> ${g.node(e.to)?.label ?: e.to}",
                        NetSheets.edge(e, members, layout.plan.edges.firstOrNull { it.id == eid }, layout.extras.bundleOf[eid], g, st.readings),
                        st.readings, ctl, onPick = { openNode(it) },
                        onHighlight = { highlight = if (highlight.isEmpty()) pathThrough(g, e).toSet() else emptySet() }, onClose = { openEdge = null })
                }
                openFrame?.let { fid ->
                    layout.extras.frames.firstOrNull { it.id == fid }?.let { f ->
                        SheetFrame(NetTags.laneSheet(fid), p.hairline, f.label, "", p.textSecondary, ctl, { openFrame = null }) {
                            NetSheets.frame(f, g, layout).forEach { l -> Line(l.k, l.v, l.tag, l.pick?.let { id -> { openNode(id) } }) }
                        }
                    }
                }
                if (choose.isNotEmpty()) SheetFrame(NetTags.CHOOSER, p.hairline, "${choose.size} wires here", "", p.textSecondary, ctl, { choose = emptyList() }) {
                    choose.forEach { eid ->
                        val pe = layout.plan.edges.firstOrNull { it.id == eid }
                        val from = pe?.let { g.node(it.from)?.label ?: layout.plan.nodes.firstOrNull { n -> n.id == it.from }?.label ?: it.from }.orEmpty()
                        val to = pe?.let { g.node(it.to)?.label ?: layout.plan.nodes.firstOrNull { n -> n.id == it.to }?.label ?: it.to }.orEmpty()
                        Line("wire", "$from -> $to  ${pe?.label.orEmpty()}", NetTags.choose(eid)) { openEdge = eid; choose = emptyList() }
                    }
                }
                openNote?.let { k ->
                    SheetFrame(NetTags.NOTE_SHEET, p.warn, if (k == "device") "This device" else "Live state", "", p.warn, ctl, { openNote = null }) {
                        Text(if (k == "device") NetSheets.deviceSentence(st) else NetSheets.notReadSentence(st), style = mono(11f, p.textPrimary))
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(tag: String, label: String, ink: Color, desc: String, click: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Text(label, style = mono(10f, ink), maxLines = 1,
        modifier = Modifier.padding(end = 6.dp).border(1.dp, ink, shape).clickable(onClick = click).padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics { contentDescription = desc }.testTag(tag))
}

/** The search hits as an overlay under the field: "2/7" with previous and next, then up to 4 rows (status, FULL name, frame). */
@Composable
private fun HitList(hits: List<NetGraph.GNode>, at: Int, l: NetGraphLayout.Layout, readings: Map<String, NetLive.Reading>, modifier: Modifier,
                    onPrev: () -> Unit, onNext: () -> Unit, onPick: (NetGraph.GNode) -> Unit) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(6.dp)).testTag(NetTags.HITS)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${at + 1}/${hits.size} ", style = mono(11f, p.textSecondary))
            Tab("net_graph_prev", "<", false, click = onPrev)
            Tab("net_graph_next", ">", false, click = onNext)
        }
        Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
            hits.take(60).forEach { n ->
                val h = readings[n.id]?.health ?: MailStack.Health.UNKNOWN
                val frame = l.extras.frameOf[l.extras.memberOf[n.id] ?: n.id]?.let { fid -> l.extras.frames.firstOrNull { it.id == fid }?.label }.orEmpty()
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { onPick(n) }.padding(horizontal = 8.dp, vertical = 4.dp).testTag(NetTags.hit(n.id)),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(statusGlyph(h) + " ", style = mono(11f, MailStack.healthColor(h, p)))
                    Text(n.label, style = mono(11f, p.textPrimary), maxLines = 1, modifier = Modifier.weight(1f))
                    Text(frame, style = mono(10f, p.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
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

/** The status colour of a node outline or a status shape (unknown and declared read as hairline / secondary). */
private fun statusInk(h: MailStack.Health, p: KitPalette) = when (h) {
    MailStack.Health.OK -> p.ok; MailStack.Health.WARN -> p.warn; MailStack.Health.BAD -> p.bad; MailStack.Health.UNKNOWN -> p.textSecondary
}

/** The status shape (10 dp in a node): up circle, degraded triangle, down square with a cross cut, unknown ring, declared dashed ring. */
fun DrawScope.statusShape(h: MailStack.Health, declared: Boolean, c: Offset, size: Float, p: KitPalette, alpha: Float = 1f, bg: Color = p.surfaceSelected) {
    val r = size / 2
    val ink = statusInk(h, p).copy(alpha = alpha * (if (declared) 0.6f else 1f))
    when {
        declared -> drawCircle(ink, r - size * 0.075f, c, style = Stroke(size * 0.15f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(size * 0.25f, size * 0.2f))))
        h == MailStack.Health.OK -> drawCircle(ink, r, c)
        h == MailStack.Health.WARN -> drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y + r); lineTo(c.x - r, c.y + r); close() }, ink)
        h == MailStack.Health.BAD -> {
            drawRect(ink, Offset(c.x - r, c.y - r), Size(size, size))
            drawLine(bg, Offset(c.x - r * 0.6f, c.y - r * 0.6f), Offset(c.x + r * 0.6f, c.y + r * 0.6f), size * 0.15f)
            drawLine(bg, Offset(c.x + r * 0.6f, c.y - r * 0.6f), Offset(c.x - r * 0.6f, c.y + r * 0.6f), size * 0.15f)
        }
        else -> drawCircle(ink, r - size * 0.075f, c, style = Stroke(size * 0.15f))
    }
}

/** #1955 A rev2 Draws one [NetScene.Scene]: dot grid, frames, wires, nodes, the caption strip, pill plates, then the texts at their boxes. */
fun DrawScope.drawNetScene(sc: NetScene.Scene, p: KitPalette, measurer: TextMeasurer, density: Float, readings: Map<String, NetLive.Reading>) {
    val dp = density
    if (sc.tier != NetScene.Tier.FAR) {
        val step = 24f * dp
        val pts = ArrayList<Offset>()
        var y = step / 2
        while (y < size.height) { var x = step / 2; while (x < size.width) { pts += Offset(x, y); x += step }; y += step }
        drawPoints(pts, PointMode.Points, p.hairline.copy(alpha = 0.12f), strokeWidth = 2f * dp, cap = StrokeCap.Round)
    }
    sc.frames.forEach { f ->
        val a = if (f.dim) 0.25f else 1f
        val r = f.rect
        drawRoundRect(p.hairline.copy(alpha = a), Offset(r.l, r.t), Size(r.r - r.l, r.b - r.t), CornerRadius(8f * dp), style = Stroke(1f * dp))
        f.header?.let { h -> drawRect(p.hairline.copy(alpha = 0.10f * a), Offset(h.l, h.t), Size(h.r - h.l, h.b - h.t)) }
    }
    sc.wires.forEach { w ->
        val bad = w.health == MailStack.Health.BAD || w.health == MailStack.Health.WARN
        val c = (if (bad) statusInk(w.health, p).copy(alpha = 0.9f) else p.textSecondary.copy(alpha = 0.55f)).let { if (w.dim) it.copy(alpha = it.alpha * 0.25f) else it }
        // a bus-routed wire is its polyline with 6 dp rounded corners; any other wire its curve
        val path = if (w.pts.size >= 2) roundedPolyline(w.pts, 6f * dp) else Path().apply { moveTo(w.p0.x, w.p0.y); cubicTo(w.p1.x, w.p1.y, w.p2.x, w.p2.y, w.p3.x, w.p3.y) }
        drawPath(path, c, style = Stroke(w.width, pathEffect = if (w.dashed) PathEffect.dashPathEffect(floatArrayOf(6f * dp, 4f * dp)) else null))
        if (w.arrow > 0f) {
            val tip = if (w.pts.size >= 2) w.pts.last() else w.p3
            val back = if (w.pts.size >= 2) w.pts[w.pts.size - 2] else w.p2
            val dx = tip.x - back.x; val dy = tip.y - back.y
            val l = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val ux = dx / l; val uy = dy / l; val k = w.arrow
            drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(tip.x - ux * k + uy * k * 0.5f, tip.y - uy * k - ux * k * 0.5f); lineTo(tip.x - ux * k - uy * k * 0.5f, tip.y - uy * k + ux * k * 0.5f); close() }, c)
        }
    }
    sc.nodes.forEach { n ->
        val a = if (n.dim) 0.25f else 1f
        val b = n.box
        val sz = Size(b.r - b.l, b.b - b.t)
        // the box of the node's kind (5): hub 8 dp and a heavy outline, stadium, rounded 6 dp, square 2 dp, chamfered 6 dp
        val hub = n.shape == "hub"
        val rad = CornerRadius(when (n.shape) { "hub" -> 8f * dp; "stadium" -> (b.b - b.t) / 2; "square" -> 2f * dp; "chamfer" -> 0f; else -> 6f * dp })
        if (n.kind == "frame") {
            // a frame's head node is its header band: tinted, no box outline (the frame draws the outline)
            drawRect(p.hairline.copy(alpha = 0.18f * a), Offset(b.l, b.t), sz)
            if (sz.height >= 12f * dp && !n.cut) statusShape(n.health, false, Offset(n.dotX, (b.t + b.b) / 2), 10f * dp, p, a)
            if (n.root) drawRect(p.bad, Offset(b.l, b.t), sz, style = Stroke(2f * dp))
            if (n.match || n.selected) drawRect(p.accent, Offset(b.l, b.t), sz, style = Stroke(2f * dp))
            return@forEach
        }
        val chamfer = if (n.shape == "chamfer") 6f * dp else 0f
        fun box(dx: Float, dy: Float, ink: Color, style: androidx.compose.ui.graphics.drawscope.DrawStyle) =
            if (chamfer > 0f) drawPath(chamfered(b.l + dx, b.t + dy, b.r + dx, b.b + dy, chamfer), ink, style = style)
            else drawRoundRect(ink, Offset(b.l + dx, b.t + dy), sz, rad, style = style)
        if (n.aggregate) box(3f * dp, 3f * dp, p.hairline.copy(alpha = a), Stroke(1.5f * dp))
        box(0f, 0f, p.surfaceSelected.copy(alpha = if (n.dim) 0.5f else 1f), androidx.compose.ui.graphics.drawscope.Fill)
        val declared = readings[n.id]?.source == NetLive.DECLARED.source && !n.aggregate
        val outline = if (n.health == MailStack.Health.UNKNOWN) p.hairline else statusInk(n.health, p)
        box(0f, 0f, outline.copy(alpha = a), Stroke((if (hub) 2.5f else 1.5f) * dp, pathEffect = if (declared) PathEffect.dashPathEffect(floatArrayOf(5f * dp, 3f * dp)) else null))
        if (sz.height >= 14f * dp && !n.cut) statusShape(n.health, declared, Offset(n.dotX, (b.t + b.b) / 2), 10f * dp, p, a)
        if (n.root) {
            drawRoundRect(p.bad, Offset(b.l, b.t), sz, rad, style = Stroke(2f * dp))
            drawRoundRect(p.bad, Offset(b.l - 3f * dp, b.t - 3f * dp), Size(sz.width + 6f * dp, sz.height + 6f * dp), CornerRadius(rad.x + 3f * dp), style = Stroke(2f * dp))
            if (!n.cut) {
                val c = Offset(b.r, b.t)
                drawCircle(p.bad, 7f * dp, c)
                drawLine(p.surface, Offset(c.x, c.y - 3.5f * dp), Offset(c.x, c.y + 1f * dp), 1.8f * dp)
                drawCircle(p.surface, 1f * dp, Offset(c.x, c.y + 3.2f * dp))
            }
        } else if (n.impacted) {
            drawRoundRect(p.warn.copy(alpha = a), Offset(b.l, b.t), sz, rad, style = Stroke(1.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * dp, 4f * dp))))
        }
        if (n.match) drawRoundRect(p.accent, Offset(b.l - 3f * dp, b.t - 3f * dp), Size(sz.width + 6f * dp, sz.height + 6f * dp), CornerRadius(rad.x + 3f * dp), style = Stroke(2f * dp))
        if (n.selected) drawRoundRect(p.accent, Offset(b.l, b.t), sz, rad, style = Stroke(2f * dp))
    }
    sc.caption?.let { c ->
        drawRect(p.surface, Offset(c.l, c.t), Size(c.r - c.l, c.b - c.t))
        drawLine(p.hairline, Offset(c.l, c.b), Offset(c.r, c.b), 1f * dp)
    }
    sc.plates.forEach { r ->
        drawRoundRect(p.surface, Offset(r.l, r.t), Size(r.r - r.l, r.b - r.t), CornerRadius(4f * dp))
        drawRoundRect(p.hairline, Offset(r.l, r.t), Size(r.r - r.l, r.b - r.t), CornerRadius(4f * dp), style = Stroke(1f * dp))
    }
    val dimmed = sc.nodes.filter { it.dim }.map { it.id }.toSet() + sc.frames.filter { it.dim }.map { it.id }.toSet()
    sc.texts.forEach { t ->
        val base = when (t.cls) {
            NetScene.Cls.NAME, NetScene.Cls.PILL, NetScene.Cls.ANCHOR -> p.textPrimary
            NetScene.Cls.METRIC, NetScene.Cls.HEADER -> p.textSecondary
            NetScene.Cls.CAPTION -> p.accent.copy(alpha = 0.8f)
        }
        val ink = if (t.owner in dimmed) base.copy(alpha = if (t.cls == NetScene.Cls.HEADER) 0.6f else 0.25f) else base
        safeDrawText(measurer, t.text, Offset(t.rect.l, t.rect.t), netTextStyle(t.sp, t.bold, ink), maxLines = 1, softWrap = false)
    }
}

/** #1955 A rev2.1 A polyline whose inner corners are rounded by [r] px (a bus route). */
fun roundedPolyline(pts: List<Offset>, r: Float): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size - 1) {
        val a = pts[i - 1]; val c = pts[i]; val d = pts[i + 1]
        val l1 = kotlin.math.hypot(c.x - a.x, c.y - a.y); val l2 = kotlin.math.hypot(d.x - c.x, d.y - c.y)
        val k = minOf(r, l1 / 2, l2 / 2)
        if (k <= 0f) { lineTo(c.x, c.y); continue }
        lineTo(c.x - (c.x - a.x) / l1 * k, c.y - (c.y - a.y) / l1 * k)
        quadraticTo(c.x, c.y, c.x + (d.x - c.x) / l2 * k, c.y + (d.y - c.y) / l2 * k)
    }
    lineTo(pts.last().x, pts.last().y)
}

/** #1955 A rev2.1 A box with its four corners cut by [c] px (tiers, auth kinds and gates). */
fun chamfered(l: Float, t: Float, r: Float, b: Float, c: Float): Path = Path().apply {
    moveTo(l + c, t); lineTo(r - c, t); lineTo(r, t + c); lineTo(r, b - c); lineTo(r - c, b); lineTo(l + c, b); lineTo(l, b - c); lineTo(l, t + c); close()
}

/** The legend: an overlay card at the top-right of the canvas (it never moves the canvas). */
@Composable
private fun Legend(modifier: Modifier) {
    val p = LocalKitPalette.current
    Column(modifier.padding(4.dp).widthIn(max = 240.dp).heightIn(max = 220.dp).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(6.dp))
        .verticalScroll(rememberScrollState()).padding(6.dp).testTag(NetTags.LEGEND_BODY)) {
        listOf(MailStack.Health.OK to false, MailStack.Health.WARN to false, MailStack.Health.BAD to false, MailStack.Health.UNKNOWN to false, MailStack.Health.UNKNOWN to true).forEach { (h, decl) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) { statusShape(h, decl, Offset(size.width / 2, size.height / 2), size.width, p, bg = p.surface) }
                Text("  ${if (decl) "declared (no live binding)" else statusWord(h)}", style = mono(11f, if (decl) p.textSecondary else MailStack.healthColor(h, p)))
            }
        }
        Text("red outline + ring + ! badge: ROOT = root cause", style = mono(10f, p.textSecondary))
        Text("dashed amber outline = impacted via the root", style = mono(10f, p.textSecondary))
        Text("dashed wire = planned, fallback, wstunnel or a declared dependency", style = mono(10f, p.textSecondary))
        Text("thicker wire = a trunk of several table edges", style = mono(10f, p.textSecondary))
        Text("double outline + count = a collapsed group; frames = lanes, VMs, servers", style = mono(10f, p.textSecondary))
        Text("rounded = host or VM, pill = roaming client, square = service, route or rule", style = mono(10f, p.textSecondary))
    }
}

@Composable
private fun MiniMap(l: NetGraphLayout.Layout, view: MailStack.View, box: IntSize, at: MailStack.Rect, alpha: Float, onJump: (Offset) -> Unit) {
    val b = l.plan.bounds
    val p = LocalKitPalette.current
    val d = LocalDensity.current
    Canvas(Modifier.offset { IntOffset(at.l.roundToInt(), at.t.roundToInt()) }.size(with(d) { (at.r - at.l).toDp() }, with(d) { (at.b - at.t).toDp() })
        .alpha(alpha).background(p.surface.copy(alpha = 0.85f)).border(1.dp, p.hairline).testTag(NetTags.MINIMAP)
        .pointerInput(l) { detectTapGestures { q -> onJump(Offset(b.l + q.x / size.width * (b.r - b.l), b.t + q.y / size.height * (b.b - b.t))) } }) {
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

/** Peek (132 dp) or expanded (up to 60 % of the canvas): the sheet's state, toggled by "more" / "less". */
class SheetCtl(val expanded: Boolean, val max: Dp, val toggle: () -> Unit)

@Composable
private fun SheetFrame(tag: String, border: Color, title: String, status: String, statusInk: Color, ctl: SheetCtl, onClose: () -> Unit, body: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().heightIn(max = if (ctl.expanded) ctl.max else 132.dp).background(p.surface).border(1.dp, border, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = mono(13f, p.textPrimary, FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(status, style = mono(11f, statusInk))
            Text(if (ctl.expanded) "  less" else "  more", style = mono(11f, p.accent), modifier = Modifier.clickable(onClick = ctl.toggle).padding(6.dp).testTag(NetTags.SHEET_MORE))
            Text(" close", style = mono(11f, p.accent), modifier = Modifier.clickable(onClick = onClose).padding(6.dp).testTag(NetTags.CLOSE))
        }
        body()
    }
}

/** The sheet of one node: status and its reading first, the actions, then every field of the table rows it stands for, its edges (tappable), dependencies, impact. */
@Composable
fun NetNodeSheet(
    n: NetGraph.GNode, g: NetGraph.Graph, r: NetLive.Reading, roots: List<NetLive.Root>, impacted: Set<String>, prim: MailStack.NodePrim?, ctl: SheetCtl,
    onPick: (String) -> Unit, onEdge: (String) -> Unit, onOpenTable: () -> Unit, onOpenContainer: (String) -> Unit, onReprobe: () -> Unit,
    onHighlight: () -> Unit, onExpand: (() -> Unit)?, onZoom: () -> Unit, onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    val ink = MailStack.healthColor(r.health, p)
    SheetFrame(NetTags.sheet(n.id), ink, n.label, "${statusGlyph(r.health)} ${statusWord(r.health)}", ink, ctl, onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp)) {
            Tab(NetTags.OPEN_TABLE, "Open in Table", false, click = onOpenTable)
            n.bind.firstOrNull { it.startsWith("container:") }?.let { b -> Tab(NetTags.OPEN_CONTAINER, "Open container", false) { onOpenContainer(b.removePrefix("container:")) } }
            Tab(NetTags.REPROBE, "Re-probe", false, click = onReprobe)
            Tab(NetTags.HIGHLIGHT, "Highlight", false, click = onHighlight)
            Tab(NetTags.ZOOM, "Zoom 2x", false, click = onZoom)
            onExpand?.let { Tab(NetTags.EXPAND, "Expand", false, click = it) }
        }
        NetSheets.node(n, g, r, roots, impacted, prim).forEach { l ->
            val tap: (() -> Unit)? = when { l.edge != null -> { { onEdge(l.edge) } }; l.pick != null -> { { onPick(l.pick) } }; else -> null }
            Line(l.k, l.v, l.tag, tap)
        }
    }
}

/** The sheet of a collapsed cluster: its member list (each opens its node) and Expand. */
@Composable
private fun ClusterSheet(c: NetGraph.GCluster, g: NetGraph.Graph, readings: Map<String, NetLive.Reading>, prim: MailStack.NodePrim?, ctl: SheetCtl,
                         onExpand: () -> Unit, onPick: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    val h = NetGraphLayout.worst(c.members.map { readings[it]?.health ?: MailStack.Health.UNKNOWN })
    SheetFrame(NetTags.sheet(NetGraphLayout.aggId(c.id)), MailStack.healthColor(h, p), c.label, "${statusGlyph(h)} ${statusWord(h)}", MailStack.healthColor(h, p), ctl, onClose) {
        Row(Modifier.padding(vertical = 3.dp)) { Tab(NetTags.EXPAND, "Expand", false, click = onExpand) }
        NetSheets.cluster(c, g, readings, prim).forEach { l -> Line(l.k, l.v, l.tag, l.pick?.let { id -> { onPick(id) } }) }
    }
}

/** The sheet of one edge (or a bundle): from, to, label, kind, status and reading, the hop chain through it, the table lines it stands for. */
@Composable
private fun NetEdgeSheet(e: NetGraph.GEdge, title: String, lines: List<NetSheets.SheetLine>, readings: Map<String, NetLive.Reading>, ctl: SheetCtl,
                         onPick: (String) -> Unit, onHighlight: () -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    val r = readings[e.id] ?: NetLive.DECLARED
    val ink = MailStack.healthColor(r.health, p)
    SheetFrame(NetTags.edgeSheet(e.id), ink, title, "${statusGlyph(r.health)} ${statusWord(r.health)}", ink, ctl, onClose) {
        Row(Modifier.padding(vertical = 3.dp)) { Tab(NetTags.HIGHLIGHT, "Highlight path", false, click = onHighlight) }
        lines.forEach { l -> Line(l.k, l.v, l.tag, l.pick?.let { id -> { onPick(id) } }) }
    }
}

fun clock(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
