package com.diegonmarcos.cloudc3.pages

import androidx.compose.ui.geometry.Offset
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        /** #1955 A rev2.1 the box of its kind (hub, stadium, rounded, square, chamfer: `viewers.scene.shapes`) and the screen x of its status shape */
        val shape: String = "rounded", val dotX: Float = box.l,
    )
    data class FrameBox(val id: String, val rect: MailStack.Rect, val header: MailStack.Rect?, val depth: Int, val owner: String, val dim: Boolean)
    data class Wire(val id: String, val p0: Offset, val p1: Offset, val p2: Offset, val p3: Offset, val health: MailStack.Health, val dashed: Boolean,
                    val width: Float, val arrow: Float, val dim: Boolean,
                    /** #1955 A rev2.1 a bus-routed wire: its polyline (screen), drawn with rounded corners instead of the curve */
                    val pts: List<Offset> = emptyList())
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
        /** #1955 A rev2.1 the width model a node is sized by (em per character at nameSp, plus slack) and the box of each node kind */
        val nameEm: Float = 0.6f, val nameSlack: Float = 2f, val shapes: Map<String, String> = emptyMap(),
    ) {
        /** The world width (dp) a node needs to show [chars] characters of its name in full. */
        fun needDp(chars: Int): Float = chars * nameSp * nameEm + nameSlack + 2 * pad + shape + padR
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
                    f("minimap_w", d.minimapW), f("minimap_h_min", d.minimapHMin), f("minimap_h_max", d.minimapHMax), f("minimap_inset", d.minimapInset), f("header_inset", d.headerInset),
                    f("name_em", d.nameEm), f("name_slack", d.nameSlack),
                    ((v.raw["scene"] as? JsonObject)?.get("shapes") as? JsonObject).orEmpty().mapNotNull { (k, x) -> (x as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap())
            }
        }
    }

    /** An IP literal or a host:port endpoint: never drawn on the canvas (it is in the sheet). */
    val ENDPOINT = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b(?!/)|[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){3,}|\\S+\\.\\S+:\\d+")

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

    /** #1955 A rev2.2 The [minimap] of [l] in the canvas corner that hides the fewest node boxes (bottom-right first on a tie), under the
     *  caption strip and clear of the [busy] overlays (legend, hit list): a minimap over a node leaves it no room for its name. */
    fun minimapFor(l: NetGraphLayout.Layout, v: MailStack.View, w: Float, h: Float, density: Float, sp: Spec, sheetOpen: Boolean,
                   busy: List<MailStack.Rect> = emptyList()): MailStack.Rect? {
        val r = minimap(l.plan.bounds, v, w, h, density, sp, sheetOpen) ?: return null
        val inset = sp.minimapInset * density
        val top = if (l.extras.columns.isNotEmpty()) sp.captionH * density else 0f
        val mw = r.r - r.l; val mh = r.b - r.t
        val boxes = l.plan.nodes.filter { it.kind != "frame" }.map { screen(v, it.rect) }
        val corners = listOf(r, r.copy(l = inset, r = inset + mw),
            MailStack.Rect(w - inset - mw, top + inset, w - inset, top + inset + mh), MailStack.Rect(inset, top + inset, inset + mw, top + inset + mh))
        return corners.filter { c -> busy.none { hit(c, it) } }.minByOrNull { c -> boxes.count { hit(c, it) } } ?: r
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
        val hud = st.hud
        val texts = mutableListOf<TextBox>()
        fun clipTop(r: MailStack.Rect): MailStack.Rect? = if (r.b <= top) null else if (r.t >= top) r else r.copy(t = top)
        /** #1955 A rev2.2 the side of [r] clear of the HUD rect [o] (2 dp off it) that keeps more width */
        fun clearOf(r: MailStack.Rect, o: MailStack.Rect): MailStack.Rect {
            val a = r.copy(r = minOf(r.r, o.l - 2f * dp)); val b = r.copy(l = maxOf(r.l, o.r + 2f * dp))
            return if (a.r - a.l >= b.r - b.l) a else b
        }
        /** #1955 A rev2.2 the part of a node's content a name may use: on the usable canvas and clear of the HUD (a minimap over a node's
         *  tail leaves its head; a node cut by the canvas edge keeps its name in the part on screen); null when nothing is left */
        fun nameArea(c: MailStack.Rect): MailStack.Rect? {
            var r = MailStack.Rect(maxOf(c.l, 0f), maxOf(c.t, top), minOf(c.r, w), minOf(c.b, h))
            hud.forEach { o -> if (hit(r, o)) r = clearOf(r, o) }
            return r.takeIf { it.r > it.l && it.b > it.t }
        }

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
            // a frame whose head is a drawn node: the node IS the header band (its name is the header text)
            val header = if (f.owner.isNotEmpty() && l.plan.nodes.any { it.id == f.owner }) null else clipTop(hb)?.takeIf { it.t == hb.t }
            val dim = focusDrawn != null && f.owner.isNotEmpty() && f.owner !in focusDrawn && (members[f.owner].orEmpty().none { it in (st.focus ?: emptySet()) })
            FrameBox(f.id, r, header, f.depth, f.owner, dim)
        }
        // nodes
        val nodes = l.plan.nodes.mapNotNull { n ->
            val full = screen(view, n.rect)
            if (full.r < 0f || full.b < 0f || full.l > w || full.t > h) return@mapNotNull null
            val box = clipTop(full) ?: return@mapNotNull null
            val ms = members[n.id].orEmpty()
            val pv = (if (n.kind == "frame") 1f else sp.padV) * dp
            val c0 = MailStack.Rect(full.l + (sp.pad * 2 + sp.shape) * dp, full.t + pv, full.r - sp.padR * dp, full.b - pv)
            // a frame's head node is as wide as the frame: its name stays in the part of the band that is on screen
            val content = if (n.kind == "frame") c0.copy(l = maxOf(c0.l, (sp.pad * 2 + sp.shape) * dp), r = minOf(c0.r, w - sp.padR * dp)) else c0
            NodeBox(n.id, box, content, n.health, n.kind, n.id in l.extras.aggregates, n.id == rootDrawn,
                ms.any { it in st.impacted }, n.id in matchDrawn, n.id == selDrawn,
                focusDrawn != null && n.id !in focusDrawn && ms.none { it in st.focus!! }, cut = box.t > full.t,
                shape = shapeOf(n, g, v, sp), dotX = full.l + (sp.pad + sp.shape / 2) * dp)
        }
        val nodeBy = nodes.associateBy { it.id }

        // wires
        val wires = l.plan.edges.filter { e -> e.id !in l.extras.onDemand || (focusDrawn != null && (e.from in focusDrawn || e.to in focusDrawn) && (selDrawn == null || e.from == selDrawn || e.to == selDrawn)) }.map { e ->
            val wBase = when (tier) { Tier.FAR -> 1f; Tier.MID -> 1.25f; Tier.NEAR -> 1.5f }
            val width = (if (e.weight > 1f) (1.25f + ln(e.weight)).coerceAtMost(4f) else wBase) * dp
            val dim = focusDrawn != null && (e.from !in focusDrawn || e.to !in focusDrawn)
            Wire(e.id, view.toScreen(e.p0), view.toScreen(e.p1), view.toScreen(e.p2), view.toScreen(e.p3), e.health, e.dashed, width,
                if (tier == Tier.FAR) 0f else 6f * dp, dim, l.extras.routes[e.id].orEmpty().map(view::toScreen))
        }

        // node text (class a): inside the content rect, measured and ellipsised, or not drawn
        if (tier != Tier.FAR) {
            val wide = { n: MailStack.NodePrim -> (n.rect.r - n.rect.l) / (l.unit.coerceAtLeast(0.0001f)) >= sp.wideFrom }
            val cap = { id: String ->
                val n = l.plan.nodes.first { it.id == id }
                if (tier == Tier.NEAR) sp.capNameNear else if (wide(n)) sp.capNameWide else sp.capName
            }
            // #1955 A rev2.1 the layout's names: suffix stripped, never blank, never equal to another node's (the box was sized for them)
            val shorts = l.plan.nodes.associate { it.id to (l.extras.names[it.id] ?: it.label.ifBlank { "${it.kind} ${it.id}" }) }
            val prio = l.plan.nodes.sortedWith(compareBy<MailStack.NodePrim>(
                { if (it.id == rootDrawn) 0 else if (it.id == selDrawn) 1 else if (it.id in matchDrawn) 2 else if (it.id in l.extras.aggregates || it.kind == "hub" || it.kind == "root") 3 else 4 },
                { it.rect.t }, { it.rect.l }))
            var names = 0
            val drawnNames = HashMap<String, String>()
            prio.forEach { n ->
                if (names >= sp.maxNames) return@forEach
                val nb = nodeBy[n.id] ?: return@forEach
                if (n.kind == "frame" && nb.cut) return@forEach
                val c = nameArea(nb.content) ?: return@forEach
                val nsp = if (n.kind == "frame") sp.headerSp else sp.nameSp
                val base = shorts.getValue(n.id)
                val full = n.label.ifBlank { base }
                val wOf = { t: String -> measure.size(t, nsp, true).first }
                // the name in full when its box holds it; else the middle ellipsis that keeps its tail (the cap first, then the measured width)
                var nameTxt = NetLabel.fit(NetLabel.cap(base, cap(n.id)), c.r - c.l, middle = true, width = wOf)
                // two different names that the fit made equal: the later one takes another tail, else it is not drawn
                if (drawnNames[nameTxt]?.let { it != full } == true)
                    nameTxt = NetLabel.variants(base, c.r - c.l, wOf).firstOrNull { it.length <= cap(n.id) && drawnNames[it]?.let { o -> o != full } != true }.orEmpty()
                if (nameTxt.isEmpty()) return@forEach
                drawnNames[nameTxt] = full
                val (nw, nh) = measure.size(nameTxt, nsp, true)
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
                texts += TextBox("name:${n.id}", Cls.NAME, MailStack.Rect(c.l, nameTop, c.l + nw, nameTop + nh), nameTxt, nsp, true, n.id, full)
                metricBox?.let { texts += it }
                names++
            }
        }

        // frame headers (class b): inside the band the layout reserved, the visible part of it
        frames.forEach { f ->
            val band = f.header ?: return@forEach
            val ff = l.extras.frames.first { it.id == f.id }
            // endpoint addresses (IPs, host:port) are sheet-only (R5): the header keeps the other segments of the sub line
            val segs = ff.sub.split("  ").map { it.trim() }.filter { it.isNotEmpty() && !ENDPOINT.containsMatchIn(it) }
            val label = ff.label + if (segs.isEmpty()) "" else " · " + segs.joinToString(" · ")
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
                selDrawn != null -> selEdges.filter { e -> wires.any { it.id == e.id } }
                tier == Tier.NEAR -> l.plan.edges.filter { e -> wires.any { it.id == e.id } }
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
                e.id to (0..k).map { view.toScreen(pointAt(l, e, it / k.toFloat())) }
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
                    val m = view.toScreen(pointAt(l, e, t))
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

    /** The wire nearest the screen point within [tolPx], along its curve or its bus route; null when none. */
    fun hitEdge(l: NetGraphLayout.Layout, view: MailStack.View, at: Offset, tolPx: Float): String? = hitEdges(l, view, at, tolPx, 0f).firstOrNull()

    /** #1955 A rev2.1 The box of a node's kind: the page's column shape first, then the declared kind shape, else rounded. */
    fun shapeOf(n: MailStack.NodePrim, g: NetGraph.Graph, v: NetGraph.Viewers, sp: Spec): String {
        if (n.kind == "frame" || n.kind == "cluster") return "rounded"
        val gn = g.node(n.id)
        return gn?.let { v.strs(g.page, "column_shapes").getOrNull(it.column) } ?: sp.shapes[n.kind] ?: "rounded"
    }

    /** #1955 A rev2.1 The world point at [t] (0..1) along a wire: its bus route by length when it has one, else its curve. */
    fun pointAt(l: NetGraphLayout.Layout, e: MailStack.EdgePrim, t: Float): Offset {
        val r = l.extras.routes[e.id]
        if (r.isNullOrEmpty() || r.size < 2) return MailStack.bezier(e, t)
        val seg = r.zipWithNext { a, b -> sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)) }
        var d = seg.sum() * t.coerceIn(0f, 1f)
        seg.forEachIndexed { i, s -> if (d <= s || i == seg.size - 1) { val k = if (s <= 0f) 0f else (d / s).coerceIn(0f, 1f); return Offset(r[i].x + (r[i + 1].x - r[i].x) * k, r[i].y + (r[i + 1].y - r[i].y) * k) }; d -= s }
        return r.last()
    }

    /**
     * #1955 A rev2.1 The wires under a tap: every drawn wire within [nearPx] of the screen point when there are two or more (the edge
     * chooser lists them, nearest first), else the one nearest within [tolPx], else none. Distances are to the drawn polyline or curve.
     */
    fun hitEdges(l: NetGraphLayout.Layout, view: MailStack.View, at: Offset, tolPx: Float, nearPx: Float): List<String> {
        fun dSeg(p: Offset, a: Offset, b: Offset): Float {
            val dx = b.x - a.x; val dy = b.y - a.y; val len = dx * dx + dy * dy
            val k = if (len <= 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len).coerceIn(0f, 1f)
            val qx = a.x + dx * k - p.x; val qy = a.y + dy * k - p.y
            return sqrt(qx * qx + qy * qy)
        }
        val ds = l.plan.edges.map { e ->
            val pts = l.extras.routes[e.id]?.takeIf { it.size >= 2 }?.map(view::toScreen) ?: (0..32).map { view.toScreen(MailStack.bezier(e, it / 32f)) }
            e.id to pts.zipWithNext { a, b -> dSeg(at, a, b) }.minOrNull()!!
        }.sortedBy { it.second }
        val near = ds.filter { it.second <= nearPx }
        if (near.size >= 2) return near.map { it.first }
        return ds.firstOrNull()?.takeIf { it.second <= tolPx }?.let { listOf(it.first) } ?: emptyList()
    }
}
