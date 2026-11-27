package com.diegonmarcos.cloudc3.pages

import androidx.compose.ui.geometry.Offset
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * #1955 A rev2 The display list of one Network Graph view: computed, then drawn. [build] is pure; the canvas only loops over the
 * [Scene] and the tests assert on the same list the phone draws. Every canvas text belongs to one class and sits in space reserved for
 * it (a node's content rect, a frame header band, the caption strip) or was placed by the collision placer; anything else is not drawn.
 * Text has a fixed size in sp (zoom changes WHAT is drawn, never how big). Coordinates are screen px; [density] px per dp.
 */
object NetScene {
    enum class Tier { FAR, MID, NEAR }
    enum class Cls { NAME, METRIC, HEADER, CAPTION, PILL, ANCHOR }

    data class TextBox(val id: String, val cls: Cls, val rect: MailStack.Rect, val text: String, val sp: Float, val bold: Boolean, val owner: String, val full: String)
    data class NodeBox(
        val id: String, val box: MailStack.Rect, val content: MailStack.Rect, val health: MailStack.Health, val kind: String,
        val aggregate: Boolean, val root: Boolean, val impacted: Boolean, val match: Boolean, val selected: Boolean, val dim: Boolean,
        /** the box is cut by the caption strip (only its lower part is drawn) */
        val cut: Boolean = false,
    )
    data class FrameBox(val id: String, val rect: MailStack.Rect, val header: MailStack.Rect?, val depth: Int, val owner: String, val dim: Boolean)
    data class Wire(val id: String, val p0: Offset, val p1: Offset, val p2: Offset, val p3: Offset, val health: MailStack.Health, val dashed: Boolean,
                    val width: Float, val arrow: Float, val dim: Boolean)
    data class Scene(
        val tier: Tier, val canvas: MailStack.Rect, val caption: MailStack.Rect?, val hud: List<MailStack.Rect>,
        val frames: List<FrameBox>, val nodes: List<NodeBox>, val wires: List<Wire>, val texts: List<TextBox>,
        /** pill plates (screen rects), drawn under their text */
        val plates: List<MailStack.Rect> = emptyList(),
    )

    /** What changes the scene besides the layout and the view. */
    data class State(
        val selected: String? = null, val matches: Set<String> = emptySet(), val root: String? = null, val impacted: Set<String> = emptySet(),
        /** graph ids kept at full strength; everything else dims (selection, search, isolate, highlight). Null = nothing dims. */
        val focus: Set<String>? = null,
        /** overlay rects in screen px (minimap, legend, hit list): no canvas text may touch them */
        val hud: List<MailStack.Rect> = emptyList(),
        /** the tier forced by a test (null = by scale) */
        val tier: Tier? = null,
    )

    /** The measured size of a text in px at [sp] (the device passes Compose's TextMeasurer, so the box is the drawn box). */
    fun interface Measure { fun size(text: String, sp: Float, bold: Boolean): Pair<Float, Float> }

    /** The declared numbers (`viewers.scene` in data/ops_maps.json); the defaults only keep an old declaration drawable. */
    data class Spec(
        val farBelow: Float = 0.6f, val nearFrom: Float = 1.4f,
        val nameSp: Float = 12f, val metricSp: Float = 10f, val headerSp: Float = 10f, val captionSp: Float = 10f, val pillSp: Float = 10f,
        val captionH: Float = 18f, val shape: Float = 10f, val pad: Float = 6f, val padR: Float = 10f, val padV: Float = 2f,
        val capName: Int = 12, val capNameWide: Int = 18, val capNameNear: Int = 24, val capMetric: Int = 28, val capPill: Int = 14,
        val capAnchor: Int = 10, val capHeader: Int = 40, val capCaption: Int = 12, val wideFrom: Float = 140f,
        val maxNames: Int = 40, val maxPills: Int = 12, val maxPillsSelected: Int = 8, val maxAnchors: Int = 12,
        val pillPad: Float = 6f, val pillH: Float = 18f, val obstaclePad: Float = 4f, val rootPad: Float = 6f, val wireClear: Float = 10f, val wireSample: Float = 12f,
        val minimapW: Float = 72f, val minimapHMin: Float = 48f, val minimapHMax: Float = 96f, val minimapInset: Float = 8f,
        val headerInset: Float = 6f,
    ) {
        companion object {
            fun of(v: NetGraph.Viewers): Spec {
                val d = Spec()
                fun f(k: String, x: Float) = v.num(null, "scene.$k", x)
                fun i(k: String, x: Int) = v.num(null, "scene.$k", x.toFloat()).toInt()
                return Spec(f("far_below", d.farBelow), f("near_from", d.nearFrom), f("name_sp", d.nameSp), f("metric_sp", d.metricSp), f("header_sp", d.headerSp),
                    f("caption_sp", d.captionSp), f("pill_sp", d.pillSp), f("caption_h", d.captionH), f("shape", d.shape), f("pad", d.pad), f("pad_r", d.padR), f("pad_v", d.padV),
                    i("cap_name", d.capName), i("cap_name_wide", d.capNameWide), i("cap_name_near", d.capNameNear), i("cap_metric", d.capMetric), i("cap_pill", d.capPill),
                    i("cap_anchor", d.capAnchor), i("cap_header", d.capHeader), i("cap_caption", d.capCaption), f("wide_from", d.wideFrom),
                    i("max_names", d.maxNames), i("max_pills", d.maxPills), i("max_pills_selected", d.maxPillsSelected), i("max_anchors", d.maxAnchors),
                    f("pill_pad", d.pillPad), f("pill_h", d.pillH), f("obstacle_pad", d.obstaclePad), f("root_pad", d.rootPad), f("wire_clear", d.wireClear), f("wire_sample", d.wireSample),
                    f("minimap_w", d.minimapW), f("minimap_h_min", d.minimapHMin), f("minimap_h_max", d.minimapHMax), f("minimap_inset", d.minimapInset), f("header_inset", d.headerInset))
            }
        }
    }

    fun tierOf(s: Float, sp: Spec): Tier = when { s < sp.farBelow -> Tier.FAR; s >= sp.nearFrom -> Tier.NEAR; else -> Tier.MID }

    fun hit(a: MailStack.Rect, b: MailStack.Rect) = a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b
    fun inflate(r: MailStack.Rect, d: Float) = MailStack.Rect(r.l - d, r.t - d, r.r + d, r.b + d)
    fun inside(a: MailStack.Rect, b: MailStack.Rect) = a.l >= b.l && a.r <= b.r && a.t >= b.t && a.b <= b.b
    private fun screen(v: MailStack.View, r: MailStack.Rect): MailStack.Rect {
        val a = v.toScreen(Offset(r.l, r.t)); val b = v.toScreen(Offset(r.r, r.b)); return MailStack.Rect(a.x, a.y, b.x, b.y)
    }

    /** The minimap rect (screen px), or null when the view shows the whole graph or a sheet is open. */
    fun minimap(bounds: MailStack.Rect, v: MailStack.View, w: Float, h: Float, density: Float, sp: Spec, sheetOpen: Boolean): MailStack.Rect? {
        if (sheetOpen || w <= 0f || h <= 0f) return null
        val sb = screen(v, bounds)
        if (sb.l >= -1f && sb.t >= -1f && sb.r <= w + 1f && sb.b <= h + 1f) return null
        val mw = sp.minimapW * density
        val ratio = (bounds.b - bounds.t) / (bounds.r - bounds.l).coerceAtLeast(1f)
        val mh = (mw * ratio).coerceIn(sp.minimapHMin * density, sp.minimapHMax * density)
        val inset = sp.minimapInset * density
        return MailStack.Rect(w - inset - mw, h - inset - mh, w - inset, h - inset)
    }

    /** The metric line of a node at NEAR (first non-blank; capped): aggregates count their non-OK members, hubs their peers. */
    fun metric(n: MailStack.NodePrim, l: NetGraphLayout.Layout, g: NetGraph.Graph, members: List<String>, status: Map<String, MailStack.Health>): String {
        if (n.id in l.extras.aggregates) {
            val hs = members.map { status[it] ?: MailStack.Health.UNKNOWN }
            val bad = listOf(MailStack.Health.BAD to "down", MailStack.Health.WARN to "degraded", MailStack.Health.UNKNOWN to "unknown")
                .mapNotNull { (h, w) -> hs.count { it == h }.takeIf { it > 0 }?.let { "$it $w" } }
            return if (bad.isEmpty()) "${hs.size} up" else bad.joinToString(" · ")
        }
        if (n.kind == "hub") return "hub · ${g.edges.count { it.from == n.id || it.to == n.id }} peers"
        return NetLabel.firstSegment(n.sub)
    }

    fun build(
        l: NetGraphLayout.Layout, g: NetGraph.Graph, v: NetGraph.Viewers, view: MailStack.View, w: Float, h: Float, density: Float,
        st: State, measure: Measure, status: Map<String, MailStack.Health> = emptyMap(),
    ): Scene {
        val sp = Spec.of(v)
        val dp = density
        val s = view.scale
        val tier = st.tier ?: tierOf(s, sp)
        val canvas = MailStack.Rect(0f, 0f, w, h)
        val hasCaptions = l.extras.columns.isNotEmpty() && tier != Tier.FAR
        val caption = if (hasCaptions) MailStack.Rect(0f, 0f, w, sp.captionH * dp) else null
        val top = caption?.b ?: 0f
        val usable = MailStack.Rect(0f, top, w, h)
        val hud = st.hud
        val texts = mutableListOf<TextBox>()
        fun free(r: MailStack.Rect) = inside(r, usable) && hud.none { hit(r, it) }
        fun clipTop(r: MailStack.Rect): MailStack.Rect? = if (r.b <= top) null else if (r.t >= top) r else r.copy(t = top)

        // members: drawn id to every graph id it stands for
        val members = HashMap<String, MutableList<String>>()
        l.plan.nodes.forEach { members[it.id] = mutableListOf(it.id) }
        l.extras.memberOf.forEach { (hid, d) -> members.getOrPut(d) { mutableListOf(d) } += hid }
        val drawnOf = { id: String -> l.extras.memberOf[id] ?: id }
        val focusDrawn = st.focus?.map(drawnOf)?.toSet()
        val rootDrawn = st.root?.let(drawnOf)
        val matchDrawn = st.matches.map(drawnOf).toSet()
        val selDrawn = st.selected?.let(drawnOf)

        // frames (clipped under the caption strip; a header band wholly under it is not reserved)
        val frames = l.extras.frames.mapNotNull { f ->
            val r = clipTop(screen(view, f.rect)) ?: return@mapNotNull null
            val hb = screen(view, MailStack.Rect(f.rect.l, f.rect.t, f.rect.r, f.rect.t + f.header))
            val header = clipTop(hb)?.takeIf { it.t == hb.t }
            val dim = focusDrawn != null && f.owner.isNotEmpty() && f.owner !in focusDrawn && (members[f.owner].orEmpty().none { it in (st.focus ?: emptySet()) })
            FrameBox(f.id, r, header, f.depth, f.owner, dim)
        }
        // nodes
        val nodes = l.plan.nodes.mapNotNull { n ->
            val full = screen(view, n.rect)
            if (full.r < 0f || full.b < 0f || full.l > w || full.t > h) return@mapNotNull null
            val box = clipTop(full) ?: return@mapNotNull null
            val ms = members[n.id].orEmpty()
            val content = MailStack.Rect(full.l + (sp.pad * 2 + sp.shape) * dp, full.t + sp.padV * dp, full.r - sp.padR * dp, full.b - sp.padV * dp)
            NodeBox(n.id, box, content, n.health, n.kind, n.id in l.extras.aggregates, n.id == rootDrawn,
                ms.any { it in st.impacted }, n.id in matchDrawn, n.id == selDrawn,
                focusDrawn != null && n.id !in focusDrawn && ms.none { it in st.focus!! }, cut = box.t > full.t)
        }
        val nodeBy = nodes.associateBy { it.id }

        // wires
        val wires = l.plan.edges.map { e ->
            val wBase = when (tier) { Tier.FAR -> 1f; Tier.MID -> 1.25f; Tier.NEAR -> 1.5f }
            val width = (if (e.weight > 1f) (1.25f + ln(e.weight)).coerceAtMost(4f) else wBase) * dp
            val dim = focusDrawn != null && (e.from !in focusDrawn || e.to !in focusDrawn)
            Wire(e.id, view.toScreen(e.p0), view.toScreen(e.p1), view.toScreen(e.p2), view.toScreen(e.p3), e.health, e.dashed, width,
                if (tier == Tier.FAR) 0f else 6f * dp, dim)
        }

        // node text (class a): inside the content rect, measured and ellipsised, or not drawn
        if (tier != Tier.FAR) {
            val wide = { n: MailStack.NodePrim -> (n.rect.r - n.rect.l) / (l.unit.coerceAtLeast(0.0001f)) >= sp.wideFrom }
            val cap = { id: String ->
                val n = l.plan.nodes.first { it.id == id }
                if (tier == Tier.NEAR) sp.capNameNear else if (wide(n)) sp.capNameWide else sp.capName
            }
            val peers = l.plan.nodes.groupBy { it.rect.l to (l.extras.frameOf[it.id] ?: "") }
            val peersOf = HashMap<String, List<String>>()
            peers.values.forEach { ns -> val names = ns.map { it.label }; ns.forEach { peersOf[it.id] = names } }
            val shorts = NetLabel.shortAll(l.plan.nodes.associate { it.id to it.label }, { peersOf[it].orEmpty() }, cap)
            val prio = l.plan.nodes.sortedWith(compareBy<MailStack.NodePrim>(
                { if (it.id == rootDrawn) 0 else if (it.id == selDrawn) 1 else if (it.id in matchDrawn) 2 else if (it.id in l.extras.aggregates || it.kind == "hub" || it.kind == "root") 3 else 4 },
                { it.rect.t }, { it.rect.l }))
            var names = 0
            val drawnNames = HashMap<String, String>()
            prio.forEach { n ->
                if (names >= sp.maxNames) return@forEach
                val nb = nodeBy[n.id] ?: return@forEach
                val c = nb.content
                if (c.r - c.l <= 0f || !free(nb.box) || !free(c)) return@forEach
                var nameTxt = NetLabel.fit(shorts[n.id].orEmpty(), c.r - c.l) { measure.size(it, sp.nameSp, true).first }
                // two different names that the measured fit made equal: the later one keeps its last 3 characters, else it is not drawn
                if (drawnNames[nameTxt]?.let { it != n.label } == true)
                    nameTxt = NetLabel.fit(NetLabel.middle(n.label, cap(n.id)), c.r - c.l) { measure.size(it, sp.nameSp, true).first }
                if (nameTxt.isEmpty() || drawnNames[nameTxt]?.let { it != n.label } == true) return@forEach
                drawnNames[nameTxt] = n.label
                val (nw, nh) = measure.size(nameTxt, sp.nameSp, true)
                if (nh > c.b - c.t) return@forEach
                var metricBox: TextBox? = null
                var nameTop = c.t + (c.b - c.t - nh) / 2
                if (tier == Tier.NEAR) {
                    val m = NetLabel.end(metric(n, l, g, members[n.id].orEmpty(), status), sp.capMetric)
                    val mt = NetLabel.fit(m, c.r - c.l) { measure.size(it, sp.metricSp, false).first }
                    if (mt.isNotEmpty()) {
                        val (mw, mh) = measure.size(mt, sp.metricSp, false)
                        val gap = 2f * dp
                        if (nh + gap + mh <= c.b - c.t) {
                            nameTop = c.t + (c.b - c.t - (nh + gap + mh)) / 2
                            metricBox = TextBox("metric:${n.id}", Cls.METRIC, MailStack.Rect(c.l, nameTop + nh + gap, c.l + mw, nameTop + nh + gap + mh), mt, sp.metricSp, false, n.id, m)
                        }
                    }
                }
                texts += TextBox("name:${n.id}", Cls.NAME, MailStack.Rect(c.l, nameTop, c.l + nw, nameTop + nh), nameTxt, sp.nameSp, true, n.id, n.label)
                metricBox?.let { texts += it }
                names++
            }
        }

        // frame headers (class b): inside the band the layout reserved, the visible part of it
        frames.forEach { f ->
            val band = f.header ?: return@forEach
            val ff = l.extras.frames.first { it.id == f.id }
            val label = ff.label + if (ff.sub.isBlank()) "" else " · " + ff.sub
            val x0 = maxOf(band.l, 0f) + sp.headerInset * dp
            val x1 = minOf(band.r, w) - sp.headerInset * dp
            if (x1 - x0 <= 0f) return@forEach
            val t = NetLabel.fit(NetLabel.end(label, sp.capHeader), x1 - x0) { measure.size(it, sp.headerSp, true).first }
            if (t.isEmpty()) return@forEach
            val (tw, th) = measure.size(t, sp.headerSp, true)
            if (th > band.b - band.t) return@forEach
            val r = MailStack.Rect(x0, band.t + (band.b - band.t - th) / 2, x0 + tw, band.t + (band.b - band.t - th) / 2 + th)
            if (!inside(r, canvas) || r.t < top || hud.any { hit(r, it) }) return@forEach
            texts += TextBox("header:${f.id}", Cls.HEADER, r, t, sp.headerSp, true, f.id, label)
        }

        // column captions (class c): the sticky strip, one per column, within the column's screen span
        if (caption != null) l.extras.columns.forEachIndexed { i, (label, span) ->
            val x0 = maxOf(view.toScreen(Offset(span.first, 0f)).x, 0f) + 2f * dp
            val x1 = minOf(view.toScreen(Offset(span.second, 0f)).x, w) - 2f * dp
            if (x1 - x0 <= 0f) return@forEachIndexed
            val t = NetLabel.fit(NetLabel.end(label.uppercase(), sp.capCaption), x1 - x0) { measure.size(it, sp.captionSp, false).first }
            if (t.isEmpty()) return@forEachIndexed
            val (tw, th) = measure.size(t, sp.captionSp, false)
            val y = caption.t + 1f * dp
            if (y + th > caption.b - 2f * dp) return@forEachIndexed
            val r = MailStack.Rect(x0, y, x0 + tw, y + th)
            if (hud.any { hit(r, it) }) return@forEachIndexed
            texts += TextBox("caption:$i", Cls.CAPTION, r, t, sp.captionSp, false, "caption", label)
        }

        // floating labels (class d): the collision placer
        val obstacles = mutableListOf<MailStack.Rect>()
        nodes.forEach { obstacles += inflate(it.box, (if (it.root) sp.rootPad else sp.obstaclePad) * dp) }
        frames.forEach { f -> f.header?.let { obstacles += it } }
        caption?.let { obstacles += it }
        obstacles += hud
        texts.forEach { obstacles += inflate(it.rect, 1f * dp) }
        val plates = mutableListOf<MailStack.Rect>()
        fun placeable(r: MailStack.Rect, own: MailStack.Rect?): Boolean =
            inside(r, canvas) && obstacles.none { hit(r, it) } && (own == null || !hit(r, inflate(own, 2f * dp)))
        if (tier == Tier.FAR) {
            val anchors = l.plan.nodes.filter { it.id == rootDrawn || it.kind == "hub" || it.kind == "root" || it.id in l.extras.aggregates }
                .sortedWith(compareBy<MailStack.NodePrim>({ if (it.id == rootDrawn) 0 else 1 }, { it.id }))
            var n = 0
            anchors.forEach { a ->
                if (n >= sp.maxAnchors) return@forEach
                val nb = nodeBy[a.id] ?: return@forEach
                val t0 = NetLabel.end(a.label, sp.capAnchor)
                if (t0.isEmpty()) return@forEach
                val (tw, th) = measure.size(t0, sp.pillSp, false)
                val g4 = 4f * dp
                val b = nb.box
                val cands = listOf(
                    MailStack.Rect(b.r + g4, b.cy - th / 2, b.r + g4 + tw, b.cy + th / 2),
                    MailStack.Rect(b.cx - tw / 2, b.b + g4, b.cx + tw / 2, b.b + g4 + th),
                    MailStack.Rect(b.cx - tw / 2, b.t - g4 - th, b.cx + tw / 2, b.t - g4),
                    MailStack.Rect(b.l - g4 - tw, b.cy - th / 2, b.l - g4, b.cy + th / 2))
                val own = inflate(b, (if (nb.root) sp.rootPad else sp.obstaclePad) * dp)
                val others = obstacles.filter { it != own }
                val spot = cands.firstOrNull { r -> inside(r, canvas) && others.none { hit(r, it) } && !hit(r, inflate(b, 2f * dp)) && nodes.none { o -> o.id != a.id && hit(r, inflate(o.box, sp.obstaclePad * dp)) } }
                    ?: return@forEach
                texts += TextBox("anchor:${a.id}", Cls.ANCHOR, spot, t0, sp.pillSp, false, a.id, a.label)
                obstacles += inflate(spot, sp.obstaclePad * dp)
                n++
            }
        } else {
            val selEdges = if (selDrawn != null) l.plan.edges.filter { it.from == selDrawn || it.to == selDrawn } else emptyList()
            val pool = when {
                selDrawn != null -> selEdges
                tier == Tier.NEAR -> l.plan.edges
                else -> emptyList()
            }
            val max = if (selDrawn != null) sp.maxPillsSelected else sp.maxPills
            val rootPath = rootDrawn?.let { r -> st.impacted.map(drawnOf).toSet() + r }.orEmpty()
            val order = pool.sortedWith(compareBy<MailStack.EdgePrim>(
                { if (it.from in rootPath && it.to in rootPath) 0 else 1 },
                { if (it.health == MailStack.Health.BAD || it.health == MailStack.Health.WARN) 0 else 1 }, { it.id }))
            val samples = l.plan.edges.associate { e ->
                val a = view.toScreen(e.p0); val d = view.toScreen(e.p3)
                val len = sqrt((a.x - d.x) * (a.x - d.x) + (a.y - d.y) * (a.y - d.y))
                val k = (len / (sp.wireSample * dp)).toInt().coerceIn(4, 400)
                e.id to (0..k).map { view.toScreen(MailStack.bezier(e, it / k.toFloat())) }
            }
            val shown = mutableListOf<Pair<String, Set<String>>>()
            var placed = 0
            order.forEach { e ->
                if (placed >= max) return@forEach
                val t0 = NetLabel.pill(e.label, sp.capPill)
                if (t0.isEmpty()) return@forEach
                if (shown.any { (txt, ends) -> txt == t0 && (e.from in ends || e.to in ends) }) return@forEach
                val (tw, th) = measure.size(t0, sp.pillSp, false)
                val pw = tw + 2 * sp.pillPad * dp; val ph = maxOf(sp.pillH * dp, th + 2f * dp)
                val spot = listOf(0.5f, 0.38f, 0.62f, 0.26f, 0.74f).firstNotNullOfOrNull { t ->
                    val m = view.toScreen(MailStack.bezier(e, t))
                    val plate = MailStack.Rect(m.x - pw / 2, m.y - ph / 2, m.x + pw / 2, m.y + ph / 2)
                    val clear = samples.filterKeys { it != e.id }.values.all { pts -> pts.all { q -> (q.x - m.x) * (q.x - m.x) + (q.y - m.y) * (q.y - m.y) >= (sp.wireClear * dp) * (sp.wireClear * dp) } }
                    plate.takeIf { placeable(it, null) && clear }
                } ?: return@forEach
                plates += spot
                obstacles += inflate(spot, sp.obstaclePad * dp)
                val tr = MailStack.Rect(spot.cx - tw / 2, spot.cy - th / 2, spot.cx + tw / 2, spot.cy + th / 2)
                texts += TextBox("pill:${e.id}", Cls.PILL, tr, t0, sp.pillSp, false, e.id, e.label)
                shown += t0 to setOf(e.from, e.to)
                placed++
            }
        }
        return Scene(tier, canvas, caption, hud, frames, nodes, wires, texts, plates)
    }

    /** The frame whose header band is under the screen point (the lane or transport sheet), or null. */
    fun hitHeader(sc: Scene, at: Offset): String? = sc.frames.lastOrNull { f -> f.header?.contains(at) == true }?.id

    /** The wire nearest the screen point within [tolPx], sampled along its curve; null when none. */
    fun hitEdge(l: NetGraphLayout.Layout, view: MailStack.View, at: Offset, tolPx: Float): String? = MailStack.hitEdge(l.plan, view, at, tolPx)
}
