package com.diegonmarcos.superapp.jsoncanvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The words the host app supplies (the lib ships no string resources, so each app translates them). */
data class CanvasLabels(val fit: String = "Fit", val open: String = "Open", val close: String = "Close", val file: String = "File", val link: String = "Link", val group: String = "Group", val empty: String = "This canvas has no nodes.")

object CanvasTags { const val VIEW = "jsoncanvas:view"; const val FIT = "jsoncanvas:fit"; const val SHEET = "jsoncanvas:sheet"; const val OPEN = "jsoncanvas:open" }

private fun col(c: String?, fallback: Color): Color = CanvasColors.resolve(c)?.let { Color(it) } ?: fallback

/** Pan, pinch zoom, double-tap or the Fit button to fit, tap a node for its detail. [onOpen] gets a link node's URL or a file node's path. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsonCanvasView(canvas: Canvas, modifier: Modifier = Modifier, labels: CanvasLabels = CanvasLabels(), onOpen: (String) -> Unit = {}) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tf by remember { mutableStateOf<ViewTransform?>(null) }
    var selected by remember { mutableStateOf<CanvasNode?>(null) }
    val bounds = remember(canvas) { CanvasGeometry.bounds(canvas) }
    val measurer = rememberTextMeasurer()
    val order = remember(canvas) { CanvasGeometry.drawOrder(canvas) }
    val fitNow = { tf = CanvasGeometry.fit(bounds, size.width.toDouble(), size.height.toDouble()) }
    if (tf == null && size.width > 0) fitNow()
    val t = tf ?: ViewTransform(1.0, 0.0, 0.0)
    val fg = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val textStyle = TextStyle(color = fg)

    Box(modifier.fillMaxSize().clipToBounds()) {
        if (canvas.nodes.isEmpty()) Text(labels.empty, Modifier.align(Alignment.Center))
        Canvas(
            Modifier.fillMaxSize().testTag(CanvasTags.VIEW).onSizeChanged { size = it }
                .pointerInput(canvas) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val cur = tf ?: return@detectTransformGestures
                        tf = CanvasGeometry.pan(CanvasGeometry.zoom(cur, zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble()), pan.x.toDouble(), pan.y.toDouble())
                    }
                }
                .pointerInput(canvas) {
                    detectTapGestures(
                        onDoubleTap = { fitNow() },
                        onTap = { p -> tf?.let { cur -> val w = cur.toWorld(p.x.toDouble(), p.y.toDouble()); selected = CanvasGeometry.hitTest(canvas, w.x, w.y) } },
                    )
                },
        ) {
            for (n in order) drawNode(n, t, measurer, textStyle, fg, surface)
            for (e in canvas.edges) drawEdge(canvas, e, t, measurer, fg)
        }
        FilledTonalButton(onClick = { fitNow() }, Modifier.align(Alignment.TopEnd).padding(8.dp).testTag(CanvasTags.FIT)) { Text(labels.fit) }
    }

    selected?.let { n ->
        ModalBottomSheet(onDismissRequest = { selected = null }, modifier = Modifier.testTag(CanvasTags.SHEET)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                when (n) {
                    is CanvasNode.Text -> MarkdownLiteView(n.text)
                    is CanvasNode.Group -> Text("${labels.group}: ${n.label.orEmpty()}", fontWeight = FontWeight.Bold)
                    is CanvasNode.File -> { Text("${labels.file}: ${n.file}", fontWeight = FontWeight.Bold); n.subpath?.let { Text(it) } }
                    is CanvasNode.Link -> { Text("${labels.link}: ${n.url}", fontWeight = FontWeight.Bold) }
                }
                val target = when (n) { is CanvasNode.Link -> n.url; is CanvasNode.File -> n.file; else -> null }
                if (target != null) Button(onClick = { onOpen(target) }, Modifier.padding(top = 12.dp).testTag(CanvasTags.OPEN)) { Text(labels.open) }
                TextButton(onClick = { selected = null }) { Text(labels.close) }
            }
        }
    }
}

private fun DrawScope.drawNode(n: CanvasNode, t: ViewTransform, m: TextMeasurer, style: TextStyle, fg: Color, surface: Color) {
    val s = t.scale.toFloat()
    val tl = t.toScreen(Pt(n.x, n.y))
    val topLeft = Offset(tl.x.toFloat(), tl.y.toFloat())
    val sz = Size((n.width * s).toFloat(), (n.height * s).toFloat())
    if (topLeft.x > size.width || topLeft.y > size.height || topLeft.x + sz.width < 0 || topLeft.y + sz.height < 0) return
    val accent = col(n.color, fg.copy(alpha = 0.5f))
    val r = CornerRadius(8f * s.coerceIn(0.5f, 1.5f))
    if (n is CanvasNode.Group) {
        drawRoundRect(accent.copy(alpha = 0.10f), topLeft, sz, r)
        drawRoundRect(accent, topLeft, sz, r, style = Stroke(width = 1.5f))
        n.label?.takeIf { it.isNotBlank() && s > 0.12f }?.let { l ->
            val fs = (14f * s).coerceIn(9f, 28f)
            val tx = topLeft.x + 6f; val ty = topLeft.y + 4f
            if (tx < size.width - 8f && ty < size.height - 8f && tx > -10000f) {
                val lay = m.measure(l, style.copy(fontSize = fs.sp, fontWeight = FontWeight.Bold, color = accent), maxLines = 1, constraints = Constraints(maxWidth = sz.width.toInt().coerceAtLeast(1)))
                drawText(lay, topLeft = Offset(tx, ty))
            }
        }
        return
    }
    drawRoundRect(surface, topLeft, sz, r)
    drawRoundRect(accent.copy(alpha = 0.18f), topLeft, sz, r)
    drawRoundRect(accent, topLeft, sz, r, style = Stroke(width = 2f))
    val label = when (n) { is CanvasNode.Text -> n.text; is CanvasNode.File -> n.file; is CanvasNode.Link -> n.url; else -> "" }
    if (s < 0.2f || label.isBlank()) return
    val fs = (13f * s).coerceIn(7f, 30f)
    val pad = 8f * s.coerceAtMost(1f)
    val w = (sz.width - 2 * pad).toInt(); val h = (sz.height - 2 * pad).toInt()
    if (w < 8 || h < 8) return
    val shown = if (n is CanvasNode.Text) n.text.lines().joinToString("\n") { it.trimStart('#', ' ').replace("**", "").replace("`", "") } else label
    val lay: TextLayoutResult = m.measure(shown, style.copy(fontSize = fs.sp, fontWeight = if (n is CanvasNode.Text && n.text.trimStart().startsWith("#")) FontWeight.Bold else null),
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, constraints = Constraints(maxWidth = w, maxHeight = h))
    drawText(lay, topLeft = Offset(topLeft.x + pad, topLeft.y + pad))
}

private fun DrawScope.drawEdge(c: Canvas, e: CanvasEdge, t: ViewTransform, m: TextMeasurer, fg: Color) {
    val cv = CanvasGeometry.curve(c, e) ?: return
    fun o(p: Pt) = t.toScreen(p).let { Offset(it.x.toFloat(), it.y.toFloat()) }
    val color = col(e.color, fg.copy(alpha = 0.75f))
    val path = Path().apply { val a = o(cv.p0); moveTo(a.x, a.y); val b = o(cv.c1); val d = o(cv.c2); val z = o(cv.p3); cubicTo(b.x, b.y, d.x, d.y, z.x, z.y) }
    drawPath(path, color, style = Stroke(width = 2f))
    val head = 14f * t.scale.toFloat().coerceIn(0.6f, 1.4f)
    fun arrow(at: Pt, dir: Pt) {
        val tip = o(at); val dx = dir.x.toFloat(); val dy = dir.y.toFloat()
        val l = Offset(tip.x - dx * head - dy * head * 0.5f, tip.y - dy * head + dx * head * 0.5f)
        val r = Offset(tip.x - dx * head + dy * head * 0.5f, tip.y - dy * head - dx * head * 0.5f)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(r.x, r.y); close() }, color)
    }
    if (e.toEnd == EndShape.Arrow) arrow(cv.p3, cv.endDir)
    if (e.fromEnd == EndShape.Arrow) arrow(cv.p0, Pt(-cv.startDir.x, -cv.startDir.y))
    e.label?.takeIf { it.isNotBlank() && t.scale > 0.2 }?.let { l ->
        val mid = o(cv.mid)
        val lay = m.measure(l, TextStyle(color = fg, fontSize = (12f * t.scale.toFloat()).coerceIn(8f, 20f).sp), maxLines = 1, constraints = Constraints(maxWidth = (size.width.toInt() / 2).coerceAtLeast(1)))
        val tl = Offset(mid.x - lay.size.width / 2f, mid.y - lay.size.height / 2f)
        if (tl.x > -10000f && tl.x < size.width && tl.y < size.height) {
            drawRoundRect(Color(0xCC000000), tl.copy(x = tl.x - 3f, y = tl.y - 1f), Size(lay.size.width + 6f, lay.size.height + 2f), CornerRadius(4f))
            drawText(lay, color = Color.White, topLeft = tl)
        }
    }
}
