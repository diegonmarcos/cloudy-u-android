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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/** Where a world point lands on screen under [scale] and [pan] with the view centred on [center]; and the way back. */
object GraphCamera {
    fun toScreen(wx: Float, wy: Float, center: Offset, pan: Offset, scale: Float) = Offset(center.x + pan.x + wx * scale, center.y + pan.y + wy * scale)
    fun toWorld(p: Offset, center: Offset, pan: Offset, scale: Float) = Offset((p.x - center.x - pan.x) / scale, (p.y - center.y - pan.y) / scale)

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
 * zooms (pinch) and opens a node on tap ([onOpen] gets its id). [selectedId] is drawn ringed and its neighbours' edges brighter. Colours come in
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
    val layout = remember(nodes, edges) { ForceLayout(nodes.map { it.id }, edges) }
    val degree = remember(nodes, edges) { GraphBuilder.degrees(nodes, edges) }
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    var frame by remember(layout) { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1.8f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val style = remember(labelColor) { TextStyle(color = labelColor, fontSize = 10.sp) }

    LaunchedEffect(layout) {
        layout.settle(steps = 60)
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
            .pointerInput(layout) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val ns = (scale * zoom).coerceIn(0.4f, 6f)
                    // keep the world point under the fingers fixed while zooming, then follow the pan
                    val k = ns / scale
                    pan = Offset(centroid.x - c.x - (centroid.x - c.x - pan.x) * k, centroid.y - c.y - (centroid.y - c.y - pan.y) * k) + panChange
                    scale = ns
                }
            }
            .pointerInput(layout) {
                detectTapGestures { tap ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    GraphCamera.hit(layout, GraphCamera.toWorld(tap, c, pan, scale), 28f / scale)?.let(onOpen)
                }
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
            val r = (5f + 1.5f * (degree[node.id] ?: 0).coerceAtMost(8)) * scale.coerceIn(0.6f, 1.6f)
            drawCircle(nodeColor(node), r, p)
            if (node.id == selectedId) drawCircle(labelColor, r + 4f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            if (scale >= 2.4f || node.id == selectedId) {
                drawText(measurer, node.label, Offset(p.x + r + 3f, p.y - 7f), style)
            }
        }
    }
}
