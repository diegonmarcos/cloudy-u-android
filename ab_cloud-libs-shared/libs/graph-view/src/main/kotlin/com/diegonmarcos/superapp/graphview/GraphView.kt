package com.diegonmarcos.superapp.graphview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/** Where a world point lands on screen under [scale] and [pan] with the view centred on [center]; and the way back. */
object GraphCamera {
    /**
     * Where a node's label starts, or null when it would start outside the canvas. drawText lays the text out in the space
     * left between this origin and the canvas edge; an origin at or past the right or bottom edge leaves negative room and
     * Compose throws ("maxWidth must be >= than minWidth"), which crashed Cloud Wiki on zoom (2026-11-25). Off-screen labels
     * are simply not drawn.
     */
    fun labelOrigin(node: Offset, radius: Float, width: Float, height: Float): Offset? {
        val x = node.x + radius + 3f
        val y = node.y - 7f
        if (!x.isFinite() || !y.isFinite() || width <= 0f || height <= 0f) return null
        if (x >= width - 1f || y >= height - 1f) return null
        return Offset(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
    }

    fun toScreen(wx: Float, wy: Float, center: Offset, pan: Offset, scale: Float) = Offset(center.x + pan.x + wx * scale, center.y + pan.y + wy * scale)
    fun toWorld(p: Offset, center: Offset, pan: Offset, scale: Float) = Offset((p.x - center.x - pan.x) / scale, (p.y - center.y - pan.y) / scale)

    /** Scale and pan that fit every node of [layout] inside a [w] x [h] view with [margin] px around (the view is centred on the origin). */
    fun fit(layout: ForceLayout, w: Float, h: Float, margin: Float = 24f): Pair<Float, Offset> {
        if (layout.n == 0 || w <= 0f || h <= 0f) return 1f to Offset.Zero
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until layout.n) { x0 = minOf(x0, layout.x[i]); x1 = maxOf(x1, layout.x[i]); y0 = minOf(y0, layout.y[i]); y1 = maxOf(y1, layout.y[i]) }
        val bw = maxOf(x1 - x0, 1f); val bh = maxOf(y1 - y0, 1f)
        val sc = minOf((w - 2 * margin) / bw, (h - 2 * margin) / bh).coerceIn(0.1f, 6f)
        return sc to Offset(-(x0 + x1) / 2f * sc, -(y0 + y1) / 2f * sc)
    }

    /** The id of the node nearest to world point [w] within [radius] world units, or null. */
    fun hit(layout: ForceLayout, w: Offset, radius: Float): String? {
        var best: String? = null
        var bestD = radius * radius
        for (i in 0 until layout.n) {
            val dx = layout.x[i] - w.x
            val dy = layout.y[i] - w.y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = layout.ids[i] }
        }
        return best
    }
}

/**
 * The graph: [nodes] joined by [edges], laid out by [ForceLayout] while the view is on screen, drawn on a canvas that pans (one finger),
 * zooms (pinch) and opens a node on tap ([onOpen] gets its id). It opens FITTED to the view, nodes cluster by [GNode.group], and labels stay
 * off until the view is zoomed in, except the best-connected hubs, a node under the finger (the hover of a touch screen) and [selectedId]
 * (drawn ringed, its edges brighter). Colours come in
 * from the caller ([nodeColor], [linkColor], [tagColor], [labelColor]); this module holds none.
 */
@Composable
fun GraphView(
    nodes: List<GNode>,
    edges: List<GEdge>,
    nodeColor: (GNode) -> Color,
    linkColor: Color,
    tagColor: Color,
    labelColor: Color,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
) {
    val layout = remember(nodes, edges) { ForceLayout(nodes.map { it.id }, edges, groups = nodes.map { it.group }) }
    val hubs = remember(nodes, edges) { GraphBuilder.degrees(nodes, edges).entries.sortedByDescending { it.value }.take(maxOf(3, nodes.size / 10)).map { it.key }.toSet() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var fitScale by remember(layout) { mutableFloatStateOf(1f) }
    var fitted by remember(layout) { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<String?>(null) }
    val degree = remember(nodes, edges) { GraphBuilder.degrees(nodes, edges) }
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    var frame by remember(layout) { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val style = remember(labelColor) { TextStyle(color = labelColor, fontSize = 10.sp) }

    // fit-to-screen on open: once the layout has settled enough to have its extent and the view has a size
    LaunchedEffect(layout, viewSize) {
        if (fitted || viewSize.width == 0) return@LaunchedEffect
        layout.settle(steps = 400)
        val (sc, pn) = GraphCamera.fit(layout, viewSize.width.toFloat(), viewSize.height.toFloat())
        fitScale = sc; scale = sc; pan = pn; fitted = true
    }
    LaunchedEffect(layout) {
        var s = 0
        while (s < 900) {
            withFrameNanos { }
            val e = layout.step()
            frame++
            s++
            if (e < 0.05f && layout.alpha <= ForceLayout.ALPHA_MIN + 1e-4f) break
        }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(layout) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val c = Offset(this@pointerInput.size.width / 2f, this@pointerInput.size.height / 2f)
                    val ns = (scale * zoom).coerceIn(0.4f, 6f)
                    // keep the world point under the fingers fixed while zooming, then follow the pan
                    val k = ns / scale
                    pan = Offset(centroid.x - c.x - (centroid.x - c.x - pan.x) * k, centroid.y - c.y - (centroid.y - c.y - pan.y) * k) + panChange
                    scale = ns
                }
            }
            .pointerInput(layout) {
                detectTapGestures(
                    onPress = { at ->
                        val c = Offset(this@pointerInput.size.width / 2f, this@pointerInput.size.height / 2f)
                        pressed = GraphCamera.hit(layout, GraphCamera.toWorld(at, c, pan, scale), 28f / scale)
                        tryAwaitRelease()
                        pressed = null
                    },
                    onTap = { tap ->
                        val c = Offset(this@pointerInput.size.width / 2f, this@pointerInput.size.height / 2f)
                        GraphCamera.hit(layout, GraphCamera.toWorld(tap, c, pan, scale), 28f / scale)?.let(onOpen)
                    },
                )
            },
    ) {
        @Suppress("UNUSED_VARIABLE") val tick = frame // reading the frame counter here redraws the canvas each layout step
        val c = Offset(size.width / 2f, size.height / 2f)
        val index = layout.ids.withIndex().associate { it.value to it.index }
        for (e in edges) {
            val a = index[e.a] ?: continue
            val b = index[e.b] ?: continue
            val near = selectedId != null && (e.a == selectedId || e.b == selectedId)
            val base = if (e.kind == GEdge.Kind.LINK) linkColor else tagColor
            drawLine(
                base.copy(alpha = if (near) 1f else if (e.kind == GEdge.Kind.LINK) 0.55f else 0.25f),
                GraphCamera.toScreen(layout.x[a], layout.y[a], c, pan, scale),
                GraphCamera.toScreen(layout.x[b], layout.y[b], c, pan, scale),
                strokeWidth = (if (e.kind == GEdge.Kind.LINK) 1.6f else 1f) * (1f + 0.3f * (e.weight - 1)),
            )
        }
        for (i in 0 until layout.n) {
            val node = byId[layout.ids[i]] ?: continue
            val p = GraphCamera.toScreen(layout.x[i], layout.y[i], c, pan, scale)
            val r = (3f + 0.5f * (degree[node.id] ?: 0).coerceAtMost(10)) * (scale / fitScale).coerceIn(0.8f, 2.2f)
            drawCircle(nodeColor(node), r, p)
            if (node.id == selectedId) drawCircle(labelColor, r + 4f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            if (node.id == selectedId || node.id == pressed || node.id in hubs || scale >= fitScale * 2.2f) {
                val at = GraphCamera.labelOrigin(p, r, size.width, size.height)
                if (at != null) drawText(measurer, node.label, at, style)
            }
        }
    }
}
