package com.diegonmarcos.cloudc3.pages

import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlin.math.roundToInt

/**
 * #1955 A Places a [NetGraph.Graph] as a [MailStack.Plan] the one [PlanCanvas] draws. Pure and deterministic: no x/y is typed anywhere.
 *
 * Swimlanes are horizontal bands stacked top to bottom (the graph's lanes); columns are the declared layers left to right; inside a
 * lane and column nodes stack in graph order. A collapsed cluster is drawn as ONE aggregate (a synthetic `agg:<cluster>` node, or the
 * tree node that heads it) and its members' edges become one weighted edge per drawn pair; edges inside one aggregate are absorbed.
 * [parity] proves every graph node and edge is drawn exactly once, as itself or inside exactly one aggregate or bundle.
 */
object NetGraphLayout {

    data class Extras(
        val laneRects: Map<String, MailStack.Rect>,
        val clusterRects: Map<String, MailStack.Rect>,
        val bundleLabels: List<Pair<Offset, String>>,
        /** hidden node id to the drawn aggregate that stands for it */
        val memberOf: Map<String, String>,
        /** drawn aggregate id to the cluster it stands for */
        val aggregates: Map<String, String>,
        /** plan edge id to the graph edge ids it stands for (one for a plain edge) */
        val edgeMembers: Map<String, List<String>>,
        /** aggregate id to the graph edge ids drawn inside it */
        val absorbed: Map<String, List<String>>,
        /** #1955 A rev2 the frames (lanes, VM frames, nested clusters) with their reserved header band, outermost first */
        val frames: List<Frame> = emptyList(),
        /** drawn node id to the innermost frame that holds it */
        val frameOf: Map<String, String> = emptyMap(),
        /** column captions: label and world x span (empty = no caption strip) */
        val columns: List<Pair<String, Pair<Float, Float>>> = emptyList(),
        /** plan edge id to the bundle label it carried on the canvas before rev2 (the edge sheet prints it) */
        val bundleOf: Map<String, String> = emptyMap(),
        /** frame node id to the graph edge ids drawn as containment (no wire): counted once by [parity] */
        val contained: Map<String, List<String>> = emptyMap(),
        /** plan edges that are only declared `depends`: drawn dashed only when one end is selected or Isolate is on */
        val onDemand: Set<String> = emptySet(),
        /** #1955 A rev2.1 drawn node id to the name it shows: its label without the dot-suffix its column shares, never blank (kind + id), never another node's */
        val names: Map<String, String> = emptyMap(),
        /** drawn nodes whose full name needs more than the room their layout has (the only names the scene may shorten) */
        val capped: Set<String> = emptySet(),
        /** plan edge id to its bus route (world polyline, from its `from` end to its `to` end); absent = the curve */
        val routes: Map<String, List<Offset>> = emptyMap(),
    )

    /** One frame: world rect, header band height (world), header text, the node it stands for ("" = a lane), nesting depth. */
    data class Frame(val id: String, val rect: MailStack.Rect, val header: Float, val label: String, val sub: String, val owner: String, val depth: Int)

    data class Layout(val plan: MailStack.Plan, val extras: Extras, val unit: Float = 1f)

    fun aggId(clusterId: String) = "agg:$clusterId"

    /** The smallest default scale at which node and edge labels are drawn. */
    const val READABLE_SCALE = 0.6f

    /** The clusters collapsed when the page opens: a tree cluster from the declared open depth, any other one over `collapse_over` members. */
    fun defaultCollapsed(g: NetGraph.Graph, v: NetGraph.Viewers): Set<String> {
        val over = v.num(g.page, "collapse_over", v.collapseOver.toFloat()).toInt()
        return g.clusters.filter { c -> if (c.head != null) !c.openByDefault else c.members.size > over }.map { it.id }.toSet()
    }

    private fun rank(h: MailStack.Health) = when (h) { MailStack.Health.BAD -> 0; MailStack.Health.WARN -> 1; MailStack.Health.UNKNOWN -> 2; MailStack.Health.OK -> 3 }

    /** The worst of [hs]; an empty list is UNKNOWN. */
    fun worst(hs: List<MailStack.Health>): MailStack.Health = hs.minByOrNull(::rank) ?: MailStack.Health.UNKNOWN

    fun layout(
        g: NetGraph.Graph, v: NetGraph.Viewers, collapsed: Set<String>, p: KitPalette,
        status: Map<String, MailStack.Health> = emptyMap(),
        /** world unit in px: 1 for the JVM tests, the screen density on a device, so a node is as large as its text (declared sizes are dp). */
        unit: Float = 1f,
    ): Layout {
        val nodeW = v.nodeW * unit; val nodeH = v.nodeH * unit; val gapX = v.num(g.page, "gap_x", v.gapX) * unit; val gapY = v.gapY * unit; val laneHeader = v.laneHeader * unit
        val framePad = v.num(g.page, "frame_pad", 8f) * unit; val frameGap = v.num(g.page, "frame_gap", 12f) * unit
        val closed = g.clusters.filter { it.id in collapsed }
        fun repr(c: NetGraph.GCluster) = c.head ?: aggId(c.id)
        val hiddenBy = mutableMapOf<String, MutableList<NetGraph.GCluster>>()
        closed.forEach { c -> c.members.forEach { m -> hiddenBy.getOrPut(m) { mutableListOf() } += c } }
        // a node is hidden when a collapsed cluster holds it; it is stood for by the collapsed cluster whose own representative is not hidden
        val memberOf = mutableMapOf<String, String>()
        val aggregates = mutableMapOf<String, String>()
        g.nodes.forEach { n ->
            val cs = hiddenBy[n.id] ?: return@forEach
            val top = cs.firstOrNull { repr(it) !in hiddenBy } ?: cs.first()
            memberOf[n.id] = repr(top)
        }
        closed.filter { repr(it) !in hiddenBy }.forEach { aggregates[repr(it)] = it.id }
        fun drawnEnd(id: String): String {
            g.clusters.firstOrNull { it.id == id }?.let { c -> return if (c.id in collapsed) memberOf[c.members.first()] ?: repr(c) else drawnEnd(c.members.first()) }
            return memberOf[id] ?: id
        }

        // slots: what is drawn in each lane and column, in graph order (an aggregate takes its first member's slot)
        data class Item(val id: String, val label: String, val sub: String, val kind: String, val lane: String, val column: Int, val health: MailStack.Health)
        val items = LinkedHashMap<String, Item>()
        g.nodes.forEach { n ->
            val at = memberOf[n.id]
            if (at == null) {
                val agg = aggregates[n.id]?.let { cid -> g.clusters.first { it.id == cid } }
                val h = if (agg != null) worst(listOf(status[n.id] ?: MailStack.Health.UNKNOWN) + agg.members.map { status[it] ?: MailStack.Health.UNKNOWN })
                    else status[n.id] ?: MailStack.Health.UNKNOWN
                val sub = if (agg != null) "${agg.members.size} inside, ${counts(agg.members, status)}" else n.sub
                items[n.id] = Item(n.id, n.label, sub, n.kind, n.lane, n.column, h)
            } else if (at !in items && at.startsWith("agg:")) {
                val c = g.clusters.first { aggId(it.id) == at }
                items[at] = Item(at, c.label, counts(c.members, status), "cluster", c.lane, c.column,
                    worst(c.members.map { status[it] ?: MailStack.Health.UNKNOWN }))
            }
        }
        // #1955 A rev2.1 each node's name and the width it needs at the fixed name size: a node grows to it, up to the room its layout has
        val sp = NetScene.Spec.of(v)
        val fullName = items.mapValues { (id, it) -> it.label.ifBlank { "${it.kind} $id" } }
        val colPeers = items.values.groupBy { it.lane to it.column }.mapValues { (_, l) -> l.map { fullName.getValue(it.id) } }
        val names = NetLabel.bases(fullName, { id -> items[id]?.let { colPeers[it.lane to it.column] }.orEmpty() }, Int.MAX_VALUE)
        fun need(id: String): Float = sp.needDp(names.getValue(id).length) * unit
        /** what a node's name asks for: its full name, at most the name cap */
        fun wantRaw(id: String): Float = minOf(need(id), sp.needDp(sp.capName) * unit)
        /** ... never less than the standard node */
        fun wantW(id: String): Float = maxOf(nodeW, wantRaw(id))
        /** the room each grown node has (world); a node without an entry has its drawn width as its room */
        val room = HashMap<String, Float>()
        /** the width a node is drawn at: the room the layout found for its name */
        fun drawnW(r: Float) = r
        val laneOrder = (g.lanes.map { it.id } + items.values.map { it.lane }).distinct()
        val colW = nodeW + gapX
        val rowH = nodeH + gapY
        val rects = mutableMapOf<String, MailStack.Rect>()
        val laneRects = LinkedHashMap<String, MailStack.Rect>()
        val maxCol = (items.values.maxOfOrNull { it.column } ?: 0).coerceAtLeast(g.columns.size - 1)
        val frames = mutableListOf<Frame>()
        val frameOf = mutableMapOf<String, String>()
        var columns: List<Pair<String, Pair<Float, Float>>> = emptyList()
        val contained = LinkedHashMap<String, MutableList<String>>()
        var straight = false
        fun laneFrame(lane: String, r: MailStack.Rect) {
            val gl = g.lanes.firstOrNull { it.id == lane }
            laneRects[lane] = r
            frames += Frame("lane:$lane", r, laneHeader, (gl?.label ?: lane).ifBlank { g.page }, gl?.sub.orEmpty(), "", 0)
        }

        // edges first (the layouts read who is wired to whom): each graph edge to its drawn pair; a pair inside one aggregate is absorbed;
        // #1955 A rev2 both directions of one pair (a duplex peering, a depends beside it) are ONE wire
        val absorbed = mutableMapOf<String, MutableList<String>>()
        val pairs = LinkedHashMap<Pair<String, String>, MutableList<NetGraph.GEdge>>()
        (g.edges + g.depends).forEach { e ->
            val a = drawnEnd(e.from); val b = drawnEnd(e.to)
            if (a == b) absorbed.getOrPut(a) { mutableListOf() } += e.id
            else pairs.getOrPut(if (pairs.containsKey(b to a)) b to a else a to b) { mutableListOf() } += e
        }
        val nbrs = HashMap<String, MutableSet<String>>()
        pairs.keys.forEach { (a, b) -> nbrs.getOrPut(a) { mutableSetOf() } += b; nbrs.getOrPut(b) { mutableSetOf() } += a }
        val order = items.keys.withIndex().associate { it.value to it.index }

        /** Columns of [list] left to right, each ordered by the barycentre of its placed neighbours and row-aligned to them (straight wires). */
        fun layered(list: List<Item>, colOf: (Item) -> Int, xOf: (Int) -> Float, wOf: (Int) -> Float, top: Float): Float {
            val cols = list.groupBy(colOf).toSortedMap()
            val cy = HashMap<String, Float>()
            fun place(c: Int, l: List<Item>) {
                val want = l.associate { it.id to nbrs[it.id].orEmpty().mapNotNull { n -> cy[n] }.takeIf { it.isNotEmpty() }?.average()?.toFloat() }
                val sorted = l.sortedWith(compareBy<Item>({ want[it.id] ?: Float.MAX_VALUE }, { order[it.id] ?: 0 }))
                var cur = top
                val tops = sorted.map { it ->
                    val d = want[it.id]
                    val t = if (d == null) cur else maxOf(cur, d - nodeH / 2)
                    cur = t + rowH; it to t
                }
                val over = tops.mapNotNull { (it, t) -> want[it.id]?.let { d -> t - (d - nodeH / 2) } }
                val shift = if (over.isEmpty()) 0f else minOf(over.average().toFloat(), (tops.firstOrNull()?.second ?: top) - top).coerceAtLeast(0f)
                tops.forEach { (it, t) -> rects[it.id] = MailStack.Rect(xOf(c), t - shift, xOf(c) + wOf(c), t - shift + nodeH); cy[it.id] = t - shift + nodeH / 2 }
            }
            cols.forEach { (c, l) -> place(c, l) }
            // a first column smaller than the next (gates) is centred on what it feeds
            val keys = cols.keys.toList()
            if (keys.size > 1 && cols.getValue(keys[0]).size < cols.getValue(keys[1]).size) { cols.getValue(keys[0]).forEach { cy.remove(it.id) }; place(keys[0], cols.getValue(keys[0])) }
            return list.mapNotNull { rects[it.id]?.b }.maxOrNull() ?: top
        }

        fun placeLanes() {
            var y = 0f
            laneOrder.forEach { lane ->
                val inLane = items.values.filter { it.lane == lane }
                if (inLane.isEmpty() && g.lanes.none { it.id == lane }) return@forEach
                val byCol = inLane.groupBy { it.column }
                val tallest = byCol.values.maxOfOrNull { it.size } ?: 0
                val top = y + laneHeader + framePad / 2
                byCol.forEach { (col, list) -> list.forEachIndexed { i, it -> rects[it.id] = MailStack.Rect(col * colW, top + i * rowH, col * colW + nodeW, top + i * rowH + nodeH); frameOf[it.id] = "lane:$lane" } }
                val h = laneHeader + framePad / 2 + tallest.coerceAtLeast(1) * rowH - gapY + framePad
                laneFrame(lane, MailStack.Rect(-framePad, y, maxCol * colW + nodeW + framePad, y + h))
                y += h + frameGap
            }
            columns = g.columns.mapIndexed { c, label -> label to (c * colW to c * colW + colW) }
        }

        /** layered / star / dns_bands: one frame per lane; columns row-aligned; declared grid lanes three across; dns_bands folds 4 columns to two bands of 2. */
        fun placeLayered(fold: Boolean) {
            val grid = v.strs(g.page, "grid_lanes").toSet()
            val across = v.num(g.page, "grid_across", 3f).toInt().coerceAtLeast(1)
            val wideW = v.num(g.page, "wide_w", 158f) * unit; val wideGap = v.num(g.page, "wide_gap", 40f) * unit
            val contentW = if (fold) 2 * wideW + wideGap + 2 * framePad else maxCol * colW + nodeW
            // #1955 A rev2.1 column widths: each column as wide as its longest name needs; when they do not all fit, the declared yield
            // columns give way first (WireGuard's roaming clients), then the widest share one level; room left over widens the gaps
            val cols = (0..maxCol).toList()
            val colWs = allocate(cols.associateWith { c -> items.values.filter { it.column == c && it.lane !in grid }.maxOfOrNull { wantRaw(it.id) } ?: nodeW },
                contentW - maxCol * gapX, v.strs(g.page, "yield_columns").mapNotNull { it.toIntOrNull() }, sp.needDp(NetLabel.MIN_CHARS + 1) * unit, nodeW)
            val gapC = if (maxCol > 0) (contentW - cols.sumOf { colWs.getValue(it).toDouble() }.toFloat()) / maxCol else 0f
            val colX = HashMap<Int, Float>().also { m -> var x = 0f; cols.forEach { c -> m[c] = x; x += colWs.getValue(c) + gapC } }
            var y = 0f
            laneOrder.forEach { lane ->
                val inLane = items.values.filter { it.lane == lane }
                if (inLane.isEmpty() && g.lanes.none { it.id == lane }) return@forEach
                val at = frames.size
                val top = y + laneHeader + framePad / 2
                var bottom = top
                when {
                    lane in grid -> {
                        val gap = if (across > 1) ((contentW - across * nodeW) / (across - 1)).coerceAtLeast(gapY) else 0f
                        inLane.forEachIndexed { i, it -> val r = i / across; val c = i % across
                            rects[it.id] = MailStack.Rect(c * (nodeW + gap), top + r * rowH, c * (nodeW + gap) + nodeW, top + r * rowH + nodeH) }
                        bottom = inLane.mapNotNull { rects[it.id]?.b }.maxOrNull() ?: top
                        inLane.forEach { frameOf[it.id] = "lane:$lane" }
                    }
                    fold -> {
                        var by = top
                        inLane.groupBy { it.column / 2 }.toSortedMap().forEach { (b, list) ->
                            val bid = "band:$lane:$b"
                            val bAt = frames.size
                            val inTop = by + laneHeader + framePad / 2
                            val bBottom = layered(list, { it.column % 2 }, { c -> framePad + c * (wideW + wideGap) }, { wideW }, inTop) + framePad
                            val label = listOfNotNull(g.columns.getOrNull(2 * b), g.columns.getOrNull(2 * b + 1)).joinToString(" → ")
                            frames.add(bAt, Frame(bid, MailStack.Rect(0f, by, contentW, bBottom), laneHeader, label, "", "", 1))
                            list.forEach { frameOf[it.id] = bid }
                            bottom = bBottom; by = bBottom + frameGap
                        }
                    }
                    else -> {
                        bottom = layered(inLane, { it.column }, { c -> colX.getValue(c) }, { c -> drawnW(colWs.getValue(c)) }, top)
                        inLane.forEach { room[it.id] = colWs.getValue(it.column) }
                        inLane.forEach { frameOf[it.id] = "lane:$lane" }
                    }
                }
                val h = bottom + framePad - y
                laneFrame(lane, MailStack.Rect(-framePad, y, contentW + framePad, y + h))
                frames.add(at, frames.removeAt(frames.size - 1))
                y += h + frameGap
            }
            if (!fold) columns = g.columns.mapIndexed { c, label -> label to ((colX[c] ?: (c * colW)) to (colX[c] ?: (c * colW)) + (colWs[c] ?: nodeW) + gapC) }
        }

        /** radial: the hub in the centre, every other node on an ellipse round it (spokes then clients, by name); the lanes become strips under the ring. */
        fun placeRadial(): Boolean {
            val hub = items.values.firstOrNull { it.kind == "hub" } ?: return false
            val ring = items.values.filter { it.id != hub.id }.sortedWith(compareBy<Item>({ when (it.kind) { "spoke" -> 0; "client" -> 1; else -> 2 } }, { it.label }))
            if (ring.isEmpty() || ring.size > v.num(g.page, "ring_max", 8f).toInt()) return false
            val rx = v.num(g.page, "ring_rx", 134f) * unit; val ry = v.num(g.page, "ring_ry", 170f) * unit
            val hw = v.num(g.page, "hub_w", 120f) * unit; val hh = v.num(g.page, "hub_h", 40f) * unit
            val contentW = v.num(g.page, "content_w", 388f) * unit
            val cx = contentW / 2; val cyR = ry + nodeH / 2
            val hubR = MailStack.Rect(cx - hw / 2, cyR - hh / 2, cx + hw / 2, cyR + hh / 2)
            rects[hub.id] = hubR
            val at = ring.indices.map { i ->
                val a = -Math.PI / 2 + 2 * Math.PI * i / ring.size
                Offset(cx + rx * kotlin.math.cos(a).toFloat(), cyR + ry * kotlin.math.sin(a).toFloat())
            }
            // #1955 A rev2.1 each ring node as wide as its name wants, inside the content and 12 dp clear of the hub and of each other
            val r = ring.mapIndexed { i, it -> minOf(wantW(it.id), 2 * minOf(at[i].x, contentW - at[i].x)) }.toMutableList()
            fun box(i: Int) = MailStack.Rect(at[i].x - r[i] / 2, at[i].y - nodeH / 2, at[i].x + r[i] / 2, at[i].y + nodeH / 2)
            fun hGap(a: MailStack.Rect, b: MailStack.Rect) = maxOf(b.l - a.r, a.l - b.r)
            fun vGap(a: MailStack.Rect, b: MailStack.Rect) = maxOf(b.t - a.b, a.t - b.b)
            for (pass in 0 until 64) {
                var moved = false
                ring.indices.forEach { i ->
                    val gh = hGap(box(i), hubR)
                    if (vGap(box(i), hubR) < gapY && gh < gapY && r[i] > nodeW) { r[i] = maxOf(nodeW, r[i] - 2 * (gapY - gh)); moved = true }
                    for (j in i + 1 until ring.size) {
                        val g2 = hGap(box(i), box(j))
                        if (vGap(box(i), box(j)) >= gapY || g2 >= gapY || (r[i] <= nodeW && r[j] <= nodeW)) continue
                        var d = gapY - g2
                        val di = minOf(d, r[i] - nodeW).coerceAtLeast(0f); r[i] -= di; d -= di
                        r[j] = maxOf(nodeW, r[j] - d); moved = true
                    }
                }
                if (!moved) break
            }
            ring.forEachIndexed { i, it ->
                room[it.id] = r[i]
                val w = drawnW(r[i])
                rects[it.id] = MailStack.Rect(at[i].x - w / 2, at[i].y - nodeH / 2, at[i].x + w / 2, at[i].y + nodeH / 2)
            }
            var y = cyR + ry + nodeH / 2 + frameGap
            laneOrder.forEach { lane -> laneFrame(lane, MailStack.Rect(0f, y, contentW, y + laneHeader)); y += laneHeader + frameGap }
            straight = true
            return true
        }

        /** clusters: a node that heads drawn children is drawn as that cluster's frame (the node is the header band); leaves three across. */
        fun placeClusters() {
            val treeKinds = v.strs(g.page, "tree_kinds").ifEmpty { listOf("tree") }.toSet()
            val hubKinds = v.strs(g.page, "hub_kinds").toSet()
            val leafW = v.num(g.page, "leaf_w", nodeW / unit) * unit
            val contentW = v.num(g.page, "content_w", 388f) * unit
            val hw = v.num(g.page, "hub_w", 120f) * unit; val hh = v.num(g.page, "hub_h", 40f) * unit
            val parent = LinkedHashMap<String, String>()
            pairs.values.forEach { es ->
                es.filter { it.kind in treeKinds }.forEach { e ->
                    val a = drawnEnd(e.from); val b = drawnEnd(e.to)
                    if (a in items && b in items && a != b && b !in parent) {
                        var x: String? = a; var cyc = false
                        while (x != null) { if (x == b) { cyc = true; break }; x = parent[x] }
                        if (!cyc) parent[b] = a
                    }
                }
            }
            val kids = items.keys.groupBy { parent[it] }
            val hubs = items.values.filter { it.kind in hubKinds && parent[it.id] == null }
            fun kidsOf(id: String) = kids[id].orEmpty()
            fun place(id: String, x: Float, top: Float, w: Float, depth: Int): Float {
                val fid = "frame:$id"
                val at = frames.size
                rects[id] = MailStack.Rect(x, top, x + w, top + laneHeader)
                frameOf[id] = fid
                var cy = top + laneHeader + gapY
                val inner = x + framePad; val iw = w - 2 * framePad
                val (framed, leaves) = kidsOf(id).partition { kidsOf(it).isNotEmpty() }
                framed.forEach { c -> cy = place(c, inner, cy, iw, depth + 1) + frameGap }
                if (leaves.isNotEmpty()) {
                    val n = maxOf(1, ((iw + gapY) / (leafW + gapY)).toInt())
                    // #1955 A rev2.1 the leaves share the whole row (a name gets the room the grid has, not the declared minimum)
                    val lw = minOf(maxOf(leafW, (iw - (n - 1) * gapY) / n), iw)
                    leaves.forEachIndexed { i, c -> val r = i / n; val k = i % n
                        rects[c] = MailStack.Rect(inner + k * (lw + gapY), cy + r * rowH, inner + k * (lw + gapY) + lw, cy + r * rowH + nodeH); frameOf[c] = fid }
                    cy += ((leaves.size + n - 1) / n) * rowH - gapY
                } else cy -= frameGap
                val bottom = cy + framePad
                val it0 = items.getValue(id)
                frames.add(at, Frame(fid, MailStack.Rect(x, top, x + w, bottom), laneHeader, it0.label, it0.sub, id, depth))
                kidsOf(id).forEach { c ->
                    val es = pairs[id to c] ?: pairs[c to id] ?: return@forEach
                    if (es.all { it.kind in treeKinds }) contained.getOrPut(id) { mutableListOf() } += es.map { it.id }
                }
                return bottom
            }
            var y = 0f
            if (hubs.isNotEmpty()) {
                val span = hubs.size * hw + (hubs.size - 1) * gapX
                hubs.forEachIndexed { i, h -> val l = (contentW - span) / 2 + i * (hw + gapX); rects[h.id] = MailStack.Rect(l, y, l + hw, y + hh) }
                y += hh + 2 * gapY
            }
            val tops = items.keys.filter { id -> id !in hubs.map { it.id } && (parent[id] == null || parent[id] in hubs.map { it.id }) }
            val (framed, leaves) = tops.partition { kidsOf(it).isNotEmpty() }
            framed.forEach { id -> y = place(id, 0f, y, contentW, 0) + frameGap }
            if (leaves.isNotEmpty()) {
                val n = maxOf(1, ((contentW + gapY) / (leafW + gapY)).toInt())
                val lw = maxOf(leafW, (contentW - (n - 1) * gapY) / n)
                leaves.forEachIndexed { i, c -> val r = i / n; val k = i % n
                    rects[c] = MailStack.Rect(k * (lw + gapY), y + r * rowH, k * (lw + gapY) + lw, y + r * rowH + nodeH) }
            }
            // a lane per tree root keeps its frame as the lane (the sheets name it)
            frames.filter { it.depth == 0 && it.owner.isNotEmpty() }.forEach { f -> laneRects[items.getValue(f.owner).lane] = f.rect }
        }

        when (v.layoutOf(g.page)) {
            "radial" -> if (!placeRadial()) placeLayered(false)
            "layered", "star" -> placeLayered(false)
            "dns_bands" -> placeLayered(true)
            "clusters" -> placeClusters()
            else -> placeLanes()
        }
        val heads = frames.mapNotNull { it.owner.takeIf { o -> o.isNotEmpty() } }.toSet()
        val nodes = items.values.map { it ->
            MailStack.NodePrim(it.id, it.label, it.sub, rects.getValue(it.id), it.health, MailStack.healthColor(it.health, p), if (it.id in heads) "frame" else it.kind)
        }
        val clusterRects = if (v.layoutOf(g.page) != "lanes") emptyMap() else g.clusters.filter { it.id !in collapsed }.mapNotNull { c ->
            val rs = c.members.mapNotNull { rects[it] }
            if (rs.isEmpty()) null else c.id to MailStack.Rect(rs.minOf { it.l } - 4f, rs.minOf { it.t } - 4f, rs.maxOf { it.r } + 4f, rs.maxOf { it.b } + 4f)
        }.toMap()
        // containment (frame-as-node): the head's tree edges to its direct children are the frame itself, never a wire; counted once
        val inFrames = contained.values.flatten().toSet()
        contained.keys.toList().forEach { owner -> val inside = contained.getValue(owner).distinct(); contained[owner] = inside.toMutableList() }

        val edgeMembers = LinkedHashMap<String, List<String>>()
        val onDemand = mutableSetOf<String>()
        val prims = pairs.mapNotNull { (ab, es) ->
            if (es.all { it.id in inFrames }) return@mapNotNull null
            val f = rects[ab.first] ?: return@mapNotNull null
            val t = rects[ab.second] ?: return@mapNotNull null
            val plain = es.size == 1 && ab.first == es[0].from && ab.second == es[0].to
            val duplex = es.any { drawnEnd(it.from) == ab.second }
            val id = if (plain) es[0].id else if (duplex) "pair:${ab.first}<->${ab.second}" else "bundle:${ab.first}->${ab.second}"
            edgeMembers[id] = es.map { it.id }
            if (es.all { it.declared == "depends" }) onDemand += id
            val pts = if (straight) straightLine(f, t) else route(f, t, 0f)
            val h = worst(es.map { status[it.id] ?: MailStack.Health.UNKNOWN })
            val label = if (es.size == 1) es[0].label else "${es.size} ${es[0].kind.replace('_', ' ')}"
            MailStack.EdgePrim(id, ab.first, ab.second, label, pts[0], pts[1], pts[2], pts[3], h, MailStack.healthColor(h, p),
                es.all { it.dashed }, es.size.toFloat())
        }
        // bundling (expanded clusters): >= bundle_min plain edges from one cluster to one target share their last control points
        val bundleLabels = mutableListOf<Pair<Offset, String>>()
        val bundleOf = mutableMapOf<String, String>()
        val clusterOfNode = g.nodes.associate { it.id to it.cluster }
        val bundled = if (straight) emptyMap() else prims.groupBy { (clusterOfNode[it.from].orEmpty()) to it.to }.filter { (k, es) -> k.first.isNotBlank() && es.size >= v.bundleMin }
        val shared = bundled.flatMap { (_, es) ->
            val p2 = Offset(es.map { it.p2.x }.average().toFloat(), es.map { it.p2.y }.average().toFloat())
            bundleLabels += p2 to "${es.size} ${es[0].label.ifBlank { "edges" }}"
            es.forEach { bundleOf[it.id] = "${es.size} ${es[0].label.ifBlank { "edges" }}" }
            es.map { it.id to p2 }
        }.toMap()
        val bent = prims.map { e -> shared[e.id]?.let { e.copy(p2 = it, label = "") } ?: e }
        val routes = if (straight || v.layoutOf(g.page) !in setOf("layered", "star", "dns_bands")) emptyMap<String, List<Offset>>()
            else busRoutes(bent, rects, v.num(g.page, "bus_min", 3f).toInt(), onDemand)
        // a routed wire keeps its curve controls on its polyline (Mail-style samplers stay on the drawn wire)
        val finalEdges = bent.map { e -> routes[e.id]?.let { r -> e.copy(p0 = r.first(), p1 = r[1.coerceAtMost(r.size - 1)], p2 = r[(r.size - 2).coerceAtLeast(0)], p3 = r.last()) } ?: e }
        // a name is capped when its room is too narrow for it, or it is longer than the scene's character cap for a box that wide
        val capped = items.keys.filter { id ->
            val w = room[id] ?: rects[id]?.let { it.r - it.l } ?: 0f
            need(id) > w + 0.5f || names.getValue(id).length > (if (w / unit >= sp.wideFrom) sp.capNameWide else sp.capName)
        }.toSet()
        val all = nodes.map { it.rect } + laneRects.values + frames.map { it.rect }
        val bounds = if (all.isEmpty()) MailStack.Rect(0f, 0f, 1f, 1f) else MailStack.Rect(all.minOf { it.l }, all.minOf { it.t }, all.maxOf { it.r }, all.maxOf { it.b })
        return Layout(MailStack.Plan(nodes, finalEdges, bounds),
            Extras(laneRects, clusterRects, bundleLabels, memberOf, aggregates + items.keys.filter { it.startsWith("agg:") }.associateWith { it.removePrefix("agg:") },
                edgeMembers, absorbed, frames, frameOf, columns, bundleOf, contained, onDemand, names, capped, routes), unit)
    }

    /**
     * #1955 A rev2.1 Column widths in [avail]: each column its [want]; when they do not all fit, the [yieldFirst] columns give way first
     * (down to [floor]), then the widest share one level (never under [floor]); when they fit, columns under [std] widen toward it.
     */
    fun allocate(want: Map<Int, Float>, avail: Float, yieldFirst: List<Int>, floor: Float, std: Float): Map<Int, Float> {
        val w = want.mapValues { maxOf(it.value, floor) }.toMutableMap()
        var over = w.values.sum() - avail
        if (over > 0f) {
            yieldFirst.forEach { c -> val x = w[c] ?: return@forEach; val d = minOf(over, x - floor).coerceAtLeast(0f); w[c] = x - d; over -= d }
            if (over > 0f) {
                var lo = floor; var hi = w.values.maxOrNull() ?: floor
                repeat(40) { val m = (lo + hi) / 2; if (w.values.sumOf { minOf(it, m).toDouble() } > avail) hi = m else lo = m }
                w.keys.toList().forEach { c -> w[c] = maxOf(floor, minOf(w.getValue(c), lo)) }
            }
        } else {
            val left = -over
            var lo = w.values.minOrNull() ?: std; var hi = maxOf(lo, std)
            if (w.values.sumOf { (maxOf(it, hi) - it).toDouble() } <= left) lo = hi
            else repeat(40) { val m = (lo + hi) / 2; if (w.values.sumOf { (maxOf(it, m) - it).toDouble() } > left) hi = m else lo = m }
            w.keys.toList().forEach { c -> w[c] = maxOf(w.getValue(c), lo) }
        }
        return w
    }

    /**
     * #1955 A rev2.1 Bus routing: a node with [busMin] or more wires to one column on one side gets a vertical bus in the gutter next to
     * that column. Each wire leaves its node with a stub to the bus, runs along it to the node's centre line and enters it straight.
     * Buses sharing a gutter are spread across it. Returns plan edge id to its world polyline, from the edge's `from` end to its `to` end.
     */
    fun busRoutes(edges: List<MailStack.EdgePrim>, rects: Map<String, MailStack.Rect>, busMin: Int, skip: Set<String>): Map<String, List<Offset>> {
        class Bus(val hub: String, val left: Boolean, val gl: Float, val gr: Float, val es: List<Pair<MailStack.EdgePrim, String>>)
        val byNode = LinkedHashMap<String, MutableList<MailStack.EdgePrim>>()
        edges.filter { it.id !in skip }.forEach { e -> byNode.getOrPut(e.from) { mutableListOf() } += e; byNode.getOrPut(e.to) { mutableListOf() } += e }
        val all = rects.values
        val taken = HashSet<String>()
        val buses = mutableListOf<Bus>()
        byNode.entries.sortedWith(compareBy<Map.Entry<String, MutableList<MailStack.EdgePrim>>>({ -it.value.size }, { it.key })).forEach { (h, es) ->
            val hr = rects[h] ?: return@forEach
            es.filter { it.id !in taken }.mapNotNull { e -> val o = if (e.from == h) e.to else e.from; rects[o]?.let { Triple(e, o, it) } }
                .filter { (_, _, o) -> o.r <= hr.l - 1f || o.l >= hr.r + 1f }
                .groupBy { (_, _, o) -> if (o.r <= hr.l - 1f) "L" + o.r.roundToInt() else "R" + o.l.roundToInt() }
                .toSortedMap().forEach { (k, grp) ->
                    if (grp.size < busMin) return@forEach
                    val left = k.startsWith("L")
                    val edge = if (left) grp[0].third.r else grp[0].third.l
                    val gl = if (left) edge else all.filter { it.r <= edge - 0.5f }.maxOfOrNull { it.r } ?: (edge - 24f)
                    val gr = if (left) all.filter { it.l >= edge + 0.5f }.minOfOrNull { it.l } ?: (edge + 24f) else edge
                    buses += Bus(h, left, gl, gr, grp.map { it.first to it.second }); grp.forEach { taken += it.first.id }
                }
        }
        val out = LinkedHashMap<String, List<Offset>>()
        buses.groupBy { it.gl.roundToInt() }.toSortedMap().values.forEach { inGutter ->
            val sorted = inGutter.sortedWith(compareBy<Bus>({ rects.getValue(it.hub).cy }, { it.hub }))
            sorted.forEachIndexed { k, b ->
                val x = b.gl + (b.gr - b.gl) * (k + 1) / (sorted.size + 1)
                val hr = rects.getValue(b.hub)
                b.es.forEach { (e, o) ->
                    val orr = rects.getValue(o)
                    val pts = listOf(Offset(if (b.left) orr.r else orr.l, orr.cy), Offset(x, orr.cy), Offset(x, hr.cy), Offset(if (b.left) hr.l else hr.r, hr.cy))
                        .fold(mutableListOf<Offset>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
                    out[e.id] = if (e.from == b.hub) pts.reversed() else pts
                }
            }
        }
        return out
    }

    /** A straight wire from the edge of [f] to the edge of [t] along the line between their centres (controls at thirds). */
    fun straightLine(f: MailStack.Rect, t: MailStack.Rect): List<Offset> {
        fun exit(r: MailStack.Rect, dx: Float, dy: Float): Offset {
            val hx = (r.r - r.l) / 2; val hy = (r.b - r.t) / 2
            val k = minOf(if (dx == 0f) Float.MAX_VALUE else hx / kotlin.math.abs(dx), if (dy == 0f) Float.MAX_VALUE else hy / kotlin.math.abs(dy))
            return Offset(r.cx + dx * k, r.cy + dy * k)
        }
        val dx = t.cx - f.cx; val dy = t.cy - f.cy
        val a = exit(f, dx, dy); val b = exit(t, -dx, -dy)
        return listOf(a, Offset(a.x + (b.x - a.x) / 3, a.y + (b.y - a.y) / 3), Offset(a.x + (b.x - a.x) * 2 / 3, a.y + (b.y - a.y) * 2 / 3), b)
    }

    private fun counts(ids: List<String>, status: Map<String, MailStack.Health>): String {
        val hs = ids.map { status[it] ?: MailStack.Health.UNKNOWN }
        return listOf(MailStack.Health.OK to "up", MailStack.Health.WARN to "degraded", MailStack.Health.BAD to "down", MailStack.Health.UNKNOWN to "unknown")
            .mapNotNull { (h, w) -> hs.count { it == h }.takeIf { it > 0 }?.let { "$it $w" } }.joinToString(" ")
    }

    /** One label to place: its text and the wire it belongs to as a function of t in 0..1 (screen px), in drawing order. */
    class LabelItem(val id: String, val text: String, val at: (Float) -> Offset)

    /**
     * #1955 A Edge and bundle labels in SCREEN space: for each label the first candidate (along the wire, above then below it) whose box
     * touches no node box and no label already placed; a label with no free candidate is not drawn (the edge sheet still names it).
     * [textW] and [textH] are the measured text size, so the result holds at any zoom and density.
     */
    fun placeLabels(items: List<LabelItem>, nodes: Collection<MailStack.Rect>, textW: (String) -> Float, textH: Float, gap: Float = 2f): Map<String, MailStack.Rect> {
        val placed = LinkedHashMap<String, MailStack.Rect>()
        fun hit(a: MailStack.Rect, b: MailStack.Rect) = !(a.r <= b.l || b.r <= a.l || a.b <= b.t || b.b <= a.t)
        val ts = listOf(0.5f, 0.4f, 0.6f, 0.3f, 0.7f, 0.2f, 0.8f, 0.12f, 0.88f)
        items.forEach { it ->
            val w = textW(it.text)
            val spot = ts.firstNotNullOfOrNull { t ->
                val m = it.at(t)
                listOf(m.y - textH - gap, m.y + gap).firstNotNullOfOrNull { top ->
                    val r = MailStack.Rect(m.x - w / 2, top, m.x + w / 2, top + textH)
                    r.takeIf { c -> nodes.none { hit(c, it) } && placed.values.none { hit(c, it) } }
                }
            }
            if (spot != null) placed[it.id] = spot
        }
        return placed
    }

    /** Forward and same-column wires reuse Mail's anchors; a leftward wire is its mirror. [off] separates the two directions of a duplex link. */
    fun route(f: MailStack.Rect, t: MailStack.Rect, off: Float = 0f): List<Offset> {
        val pts = if (t.r <= f.l + 1f) {
            val dx = ((f.l - t.r).coerceAtLeast(40f)) * 0.5f
            listOf(Offset(f.l, f.cy), Offset(f.l - dx, f.cy), Offset(t.r + dx, t.cy), Offset(t.r, t.cy))
        } else MailStack.anchors(f, t)
        return if (off == 0f) pts else pts.map { Offset(it.x, it.y + off) }
    }

    /** The default view: the whole width fills the canvas and the rest pans vertically; never below [minScale], so node text stays
     *  drawn on a narrow screen (labels start at 0.45, edge labels at 0.6) and the rest of the width pans sideways. */
    fun fitWidth(b: MailStack.Rect, w: Float, pad: Float = 16f, minScale: Float = READABLE_SCALE): MailStack.View {
        val bw = (b.r - b.l).coerceAtLeast(1f)
        val s = ((w - 2 * pad) / bw).coerceIn(minScale.coerceAtLeast(MailStack.MIN_SCALE), MailStack.MAX_SCALE)
        return MailStack.View(s, pad - b.l * s, pad - b.t * s)
    }

    /**
     * #1955 A rev2 The opening view of a [w] x [h] px canvas: fit-all when that is at least [minScale] (whole graph, names readable),
     * else fit-width top-aligned with vertical pan; never above [maxScale] (the graph opens at MID). [top] px are reserved (caption
     * strip). When [root] (world rect) is outside the viewport, the view shifts so it is inside.
     */
    fun openView(b: MailStack.Rect, w: Float, h: Float, density: Float, minScale: Float = READABLE_SCALE, maxScale: Float = 1f,
                 top: Float = 0f, root: MailStack.Rect? = null, padDp: Float = 12f): MailStack.View {
        val pad = padDp * density
        val bw = (b.r - b.l).coerceAtLeast(1f); val bh = (b.b - b.t).coerceAtLeast(1f)
        val all = minOf((w - 2 * pad) / bw, (h - top - 2 * pad) / bh)
        var v = if (all >= minScale) {
            val s = all.coerceAtMost(maxScale)
            MailStack.View(s, (w - bw * s) / 2 - b.l * s, top + pad - b.t * s)
        } else {
            val s = ((w - 2 * pad) / bw).coerceIn(minScale, maxScale)
            val ox = if (bw * s <= w - 2 * pad) (w - bw * s) / 2 - b.l * s else pad - b.l * s
            MailStack.View(s, ox, top + pad - b.t * s)
        }
        if (root != null) {
            val a = v.toScreen(Offset(root.l, root.t)); val c = v.toScreen(Offset(root.r, root.b))
            val m = 8f * density
            val dx = when { a.x < m -> m - a.x; c.x > w - m -> (w - m) - c.x; else -> 0f }
            val dy = when { a.y < top + m -> top + m - a.y; c.y > h - m -> (h - m) - c.y; else -> 0f }
            v = MailStack.View(v.scale, v.ox + dx, v.oy + dy)
        }
        return v
    }

    /** #1955 A rev2 The opening view of [l] as the phone opens it (tests call the same): caption strip reserved, root brought in. */
    fun openFor(l: Layout, v: NetGraph.Viewers, w: Float, h: Float, density: Float, root: String? = null): MailStack.View {
        val sp = NetScene.Spec.of(v)
        val top = if (l.extras.columns.isNotEmpty()) sp.captionH * density else 0f
        val rr = root?.let { r -> l.plan.nodes.firstOrNull { it.id == (l.extras.memberOf[r] ?: r) }?.rect }
        return openView(l.plan.bounds, w, h, density, sp.farBelow, 1f, top, rr)
    }

    /** #1955 A rev2 [v] panned so [r] (world) is centred in the part of a [w] x [h] canvas above a [peek] px sheet. */
    fun centreAbove(v: MailStack.View, r: MailStack.Rect, w: Float, h: Float, peek: Float): MailStack.View =
        v.centerOn(r, w, (h - peek).coerceAtLeast(h * 0.4f))

    /** Graph against plan: every node drawn once (itself, or inside exactly one drawn aggregate), every edge once (itself, a bundle of the right weight, or absorbed). Empty = parity. */
    fun parity(g: NetGraph.Graph, l: Layout): List<String> = buildList {
        val drawn = l.plan.nodes.groupingBy { it.id }.eachCount()
        drawn.filter { it.value > 1 }.forEach { add("node ${it.key} drawn ${it.value} times") }
        val ids = g.nodes.map { it.id }.toSet()
        g.nodes.forEach { n ->
            val self = drawn[n.id] ?: 0
            val via = l.extras.memberOf[n.id]
            when {
                self == 1 && via == null -> {}
                self == 0 && via != null && (drawn[via] ?: 0) == 1 -> {}
                else -> add("node ${n.id} drawn $self times, inside ${via ?: "nothing"}")
            }
        }
        drawn.keys.forEach { d -> if (d !in ids && d !in l.extras.aggregates) add("node $d drawn but not declared") }
        val inPlan = l.plan.edges.associateBy { it.id }
        val count = mutableMapOf<String, Int>()
        l.extras.edgeMembers.forEach { (pid, ms) ->
            val prim = inPlan[pid]
            if (prim == null) { add("plan edge $pid missing"); return@forEach }
            if (prim.weight != ms.size.toFloat()) add("bundle $pid weight ${prim.weight} for ${ms.size} edges")
            ms.forEach { count[it] = (count[it] ?: 0) + 1 }
        }
        l.extras.absorbed.values.flatten().forEach { count[it] = (count[it] ?: 0) + 1 }
        l.extras.contained.forEach { (f, es) ->
            if (l.plan.nodes.none { it.id == f } && l.extras.frames.none { it.owner == f }) add("containment frame $f drawn by no node")
            es.forEach { count[it] = (count[it] ?: 0) + 1 }
        }
        (g.edges + g.depends).forEach { e -> if ((count[e.id] ?: 0) != 1) add("edge ${e.id} drawn ${count[e.id] ?: 0} times") }
        (inPlan.keys - l.extras.edgeMembers.keys).forEach { add("edge $it drawn but not declared") }
    }
}
