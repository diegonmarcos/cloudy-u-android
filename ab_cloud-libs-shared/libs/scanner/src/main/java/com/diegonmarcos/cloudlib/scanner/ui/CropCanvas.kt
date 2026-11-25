package com.diegonmarcos.cloudlib.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.scanner.P
import com.diegonmarcos.cloudlib.scanner.Quad
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.hypot
import kotlin.math.min

/**
 * #1908 the crop canvas: the photo fitted into the view with the document's four corners as handles, a magnifier loupe over the
 * finger while one is dragged, and the page outline. The corners live in the photo's pixels; [onQuad] is called when a drag ends
 * and the page is still a convex shape that can be flattened (a bow-tie is put back).
 */
@Composable
fun CropCanvas(bitmap: Bitmap, quad: Quad, onQuad: (Quad) -> Unit, modifier: Modifier = Modifier) {
    val k = LocalKitPalette.current
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    var corners by remember(quad) { mutableStateOf(quad.points) }
    var active by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    fun scale() = min(viewSize.width.toFloat() / bitmap.width, viewSize.height.toFloat() / bitmap.height)
    fun offX() = (viewSize.width - bitmap.width * scale()) / 2f
    fun offY() = (viewSize.height - bitmap.height * scale()) / 2f
    fun toView(p: P) = Offset((p.x * scale() + offX()).toFloat(), (p.y * scale() + offY()).toFloat())
    fun toImage(o: Offset) = P(((o.x - offX()) / scale()).toDouble().coerceIn(0.0, bitmap.width - 1.0), ((o.y - offY()) / scale()).toDouble().coerceIn(0.0, bitmap.height - 1.0))

    Canvas(
        modifier.fillMaxSize().testTag("scanner:crop_canvas").onSizeChanged { viewSize = it }.pointerInput(bitmap, quad) {
            val grab = 44.dp.toPx()
            detectDragGestures(
                onDragStart = { o ->
                    var best = -1; var bd = grab
                    corners.forEachIndexed { i, p -> val d = hypot(toView(p).x - o.x, toView(p).y - o.y); if (d < bd) { bd = d; best = i } }
                    active = best; finger = o
                },
                onDrag = { change, _ ->
                    change.consume()
                    if (active >= 0) { finger = change.position; corners = corners.toMutableList().also { it[active] = toImage(change.position) } }
                },
                onDragEnd = {
                    if (active >= 0) {
                        val q = Quad(corners[0], corners[1], corners[2], corners[3])
                        if (q.isConvex()) onQuad(q) else corners = quad.points
                    }
                    active = -1
                },
                onDragCancel = { active = -1; corners = quad.points },
            )
        },
    ) {
        val s = scale()
        drawImage(img, dstOffset = IntOffset(offX().toInt(), offY().toInt()), dstSize = IntSize((bitmap.width * s).toInt(), (bitmap.height * s).toInt()))
        if (corners.size != 4) return@Canvas
        val pts = corners.map { toView(it) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1..3) lineTo(pts[i].x, pts[i].y); close() }
        // the dimmed outside, then the outline
        val outside = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)); addPath(path); fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd }
        drawPath(outside, Color(0x66000000))
        drawPath(path, k.accent.copy(alpha = 0.12f))
        drawPath(path, k.accent, style = Stroke(width = 2.5.dp.toPx()))
        for ((i, o) in pts.withIndex()) {
            drawCircle(Color.White, radius = if (i == active) 13.dp.toPx() else 11.dp.toPx(), center = o)
            drawCircle(k.accent, radius = if (i == active) 13.dp.toPx() else 11.dp.toPx(), center = o, style = Stroke(width = 3.dp.toPx()))
        }
        if (active >= 0) {
            // the magnifier: 3x of the photo around the dragged corner, in a circle parked at the opposite top corner
            val r = 46.dp.toPx()
            val c = pts[active]
            val centre = Offset(if (c.x < size.width / 2) size.width - r - 12.dp.toPx() else r + 12.dp.toPx(), r + 12.dp.toPx())
            val half = (r / s / 3f).toInt().coerceAtLeast(4)
            val px = corners[active]
            val sx = (px.x.toInt() - half).coerceIn(0, bitmap.width - 1); val sy = (px.y.toInt() - half).coerceIn(0, bitmap.height - 1)
            val sw = min(half * 2, bitmap.width - sx).coerceAtLeast(1); val sh = min(half * 2, bitmap.height - sy).coerceAtLeast(1)
            val loupe = Path().apply { addOval(Rect(centre.x - r, centre.y - r, centre.x + r, centre.y + r)) }
            clipPath(loupe) {
                drawRect(Color.Black, topLeft = Offset(centre.x - r, centre.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                drawImage(img, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                    dstOffset = IntOffset((centre.x - r).toInt(), (centre.y - r).toInt()), dstSize = IntSize((2 * r).toInt(), (2 * r).toInt()))
            }
            drawCircle(k.accent, radius = r, center = centre, style = Stroke(width = 2.5.dp.toPx()))
            drawLine(k.accent, Offset(centre.x - 8.dp.toPx(), centre.y), Offset(centre.x + 8.dp.toPx(), centre.y), strokeWidth = 1.5.dp.toPx())
            drawLine(k.accent, Offset(centre.x, centre.y - 8.dp.toPx()), Offset(centre.x, centre.y + 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
    }
}
