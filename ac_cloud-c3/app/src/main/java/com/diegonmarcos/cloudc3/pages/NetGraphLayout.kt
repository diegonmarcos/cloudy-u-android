package com.diegonmarcos.cloudc3.pages

import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.superapp.uikit.KitPalette

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
    )

    data class Layout(val plan: MailStack.Plan, val extras: Extras)

    fun aggId(clusterId: String) = "agg:$clusterId"

    /** The smallest default scale at which node and edge labels are drawn. */
    const val READABLE_SCALE = 0.6f

    /** The clusters collapsed when the page opens: a tree cluster from the declared open depth, any other one over `collapse_over` members. */
    fun defaultCollapsed(g: NetGraph.Graph, v: NetGraph.Viewers): Set<String> =
        g.clusters.filter { c -> if (c.head != null) !c.openByDefault else c.members.size > v.collapseOver }.map { it.id }.toSet()

    private fun rank(h: MailStack.Health) = when (h) { MailStack.Health.BAD -> 0; MailStack.Health.WARN -> 1; MailStack.Health.UNKNOWN -> 2; MailStack.Health.OK -> 3 }

    /** The worst of [hs]; an empty list is UNKNOWN. */
    fun worst(hs: List<MailStack.Health>): MailStack.Health = hs.minByOrNull(::rank) ?: MailStack.Health.UNKNOWN

    fun layout(
        g: NetGraph.Graph, v: NetGraph.Viewers, collapsed: Set<String>, p: KitPalette,
        status: Map<String, MailStack.Health> = emptyMap(),
        /** world unit in px: 1 for the JVM tests, the screen density on a device, so a node is as large as its text (declared sizes are dp). */
        unit: Float = 1f,
    ): Layout {
        val nodeW = v.nodeW * unit; val nodeH = v.nodeH * unit; val gapX = v.gapX * unit; val gapY = v.gapY * unit; val laneHeader = v.laneHeader * unit
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
        val laneOrder = (g.lanes.map { it.id } + items.values.map { it.lane }).distinct()
        val colW = nodeW + gapX
        val rowH = nodeH + gapY
        val rects = mutableMapOf<String, MailStack.Rect>()
        val laneRects = LinkedHashMap<String, MailStack.Rect>()
        val maxCol = (items.values.maxOfOrNull { it.column } ?: 0).coerceAtLeast(g.columns.size - 1)
        var y = 0f
        laneOrder.forEach { lane ->
            val inLane = items.values.filter { it.lane == lane }
            if (inLane.isEmpty() && g.lanes.none { it.id == lane }) return@forEach
            val byCol = inLane.groupBy { it.column }
            val tallest = byCol.values.maxOfOrNull { it.size } ?: 0
            val top = y + laneHeader
            byCol.forEach { (col, list) -> list.forEachIndexed { i, it -> rects[it.id] = MailStack.Rect(col * colW, top + i * rowH, col * colW + nodeW, top + i * rowH + nodeH) } }
            val h = laneHeader + tallest.coerceAtLeast(1) * rowH + gapY
            laneRects[lane] = MailStack.Rect(-gapX / 4, y, maxCol * colW + nodeW + gapX / 4, y + h)
            y += h
        }
        val nodes = items.values.map { it ->
            MailStack.NodePrim(it.id, it.label, it.sub, rects.getValue(it.id), it.health, MailStack.healthColor(it.health, p), it.kind)
        }
        val clusterRects = g.clusters.filter { it.id !in collapsed }.mapNotNull { c ->
            val rs = c.members.mapNotNull { rects[it] }
            if (rs.isEmpty()) null else c.id to MailStack.Rect(rs.minOf { it.l } - 4f, rs.minOf { it.t } - 4f, rs.maxOf { it.r } + 4f, rs.maxOf { it.b } + 4f)
        }.toMap()

        // edges: each graph edge to its drawn pair; one plan edge per pair (weight = members); a pair inside one aggregate is absorbed
        val absorbed = mutableMapOf<String, MutableList<String>>()
        val pairs = LinkedHashMap<Pair<String, String>, MutableList<NetGraph.GEdge>>()
        (g.edges + g.depends).forEach { e ->
            val a = drawnEnd(e.from); val b = drawnEnd(e.to)
            if (a == b) absorbed.getOrPut(a) { mutableListOf() } += e.id
            else pairs.getOrPut(a to b) { mutableListOf() } += e
        }
        val edgeMembers = LinkedHashMap<String, List<String>>()
        val prims = pairs.mapNotNull { (ab, es) ->
            val f = rects[ab.first] ?: return@mapNotNull null
            val t = rects[ab.second] ?: return@mapNotNull null
            val plain = es.size == 1 && ab.first == es[0].from && ab.second == es[0].to
            val id = if (plain) es[0].id else "bundle:${ab.first}->${ab.second}"
            edgeMembers[id] = es.map { it.id }
            val back = pairs.containsKey(ab.second to ab.first)
            val off = if (back) (if (ab.first < ab.second) -6f else 6f) else 0f
            val pts = route(f, t, off)
            val h = worst(es.map { status[it.id] ?: MailStack.Health.UNKNOWN })
            val label = if (es.size == 1) es[0].label else "${es.size} ${es[0].kind.replace('_', ' ')}"
            MailStack.EdgePrim(id, ab.first, ab.second, label, pts[0], pts[1], pts[2], pts[3], h, MailStack.healthColor(h, p),
                es.all { it.dashed }, es.size.toFloat())
        }
        // bundling (expanded clusters): >= bundle_min plain edges from one cluster to one target share their last control points
        val bundleLabels = mutableListOf<Pair<Offset, String>>()
        val clusterOfNode = g.nodes.associate { it.id to it.cluster }
        val bundled = prims.groupBy { (clusterOfNode[it.from].orEmpty()) to it.to }.filter { (k, es) -> k.first.isNotBlank() && es.size >= v.bundleMin }
        val shared = bundled.flatMap { (_, es) ->
            val p2 = Offset(es.map { it.p2.x }.average().toFloat(), es.map { it.p2.y }.average().toFloat())
            bundleLabels += p2 to "${es.size} ${es[0].label.ifBlank { "edges" }}"
            es.map { it.id to p2 }
        }.toMap()
        val finalEdges = prims.map { e -> shared[e.id]?.let { e.copy(p2 = it, label = "") } ?: e }
        val all = nodes.map { it.rect } + laneRects.values
        val bounds = if (all.isEmpty()) MailStack.Rect(0f, 0f, 1f, 1f) else MailStack.Rect(all.minOf { it.l }, all.minOf { it.t }, all.maxOf { it.r }, all.maxOf { it.b })
        return Layout(MailStack.Plan(nodes, finalEdges, bounds),
            Extras(laneRects, clusterRects, bundleLabels, memberOf, aggregates + items.keys.filter { it.startsWith("agg:") }.associateWith { it.removePrefix("agg:") },
                edgeMembers, absorbed))
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
        (g.edges + g.depends).forEach { e -> if ((count[e.id] ?: 0) != 1) add("edge ${e.id} drawn ${count[e.id] ?: 0} times") }
        (inPlan.keys - l.extras.edgeMembers.keys).forEach { add("edge $it drawn but not declared") }
    }
}
