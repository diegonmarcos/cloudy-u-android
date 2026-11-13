package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.ChartPoint
import com.diegonmarcos.capitalfinterm.FinLogic
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fmtAxis(v: Double): String = when {
    abs(v) >= 10000 -> String.format(Locale.US, "%,.0f", v)
    abs(v) >= 100 -> String.format(Locale.US, "%.0f", v)
    abs(v) >= 10 -> String.format(Locale.US, "%.1f", v)
    else -> String.format(Locale.US, "%.2f", v)
}

/**
 * The chart header (spec 2.4): title at the left (replaced by the hovered value and date during a crosshair gesture), range
 * chips at the right. Curves have tenors on the x axis, so no chips.
 */
@Composable
fun FinChartHeader(title: String, hover: String?, ranges: List<String>, range: String, onRange: (String) -> Unit, stale: Boolean) {
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().height(FinDim.title), verticalAlignment = Alignment.CenterVertically) {
        if (hover != null) Text(hover, Modifier.weight(1f), color = p.accent, style = FinType.numSmall, maxLines = 1)
        else Text(title, Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.White, style = FinType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ranges.forEach { r -> FinChip(r, r == range, Modifier.height(24.dp)) { onRange(r) } }
        }
        if (stale) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Schedule, "stale", Modifier.padding(start = 8.dp).width(16.dp).height(16.dp), tint = p.amber)
    }
}

/**
 * Line / curve chart body (spec 2.4): 200dp, 2dp accent stroke over a 12 % -> 0 % area, 4 gridlines with y labels inside the
 * right edge (48dp), 3-5 x labels, no dots; the last value is a 6dp dot and a pill at the right edge. A curve's x axis is its
 * tenors, evenly spaced. Long-press and drag: a crosshair snapped to the nearest point, reported through [onHover].
 */
@Composable
fun FinLineChart(allPoints: List<ChartPoint>, curve: Boolean, height: Dp, panelId: String, onHover: (String?) -> Unit) {
    val p = LocalFinPalette.current
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    // pinch with two fingers zooms a dated series onto its latest points (FinLogic.zoomed); a double tap resets; a curve (tenors) does not zoom
    var zoom by remember(allPoints) { mutableStateOf(1f) }
    val points = if (curve) allPoints else FinLogic.zoomed(allPoints, zoom)
    var hoverIdx by remember(points) { mutableStateOf(-1) }
    if (points.size < 2) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) { Text("No data for this range", color = p.text2, style = FinType.caption) }
        return
    }
    val lo0 = points.minOf { it.y }; val hi0 = points.maxOf { it.y }
    val ticks = remember(points) { FinLogic.ticks(lo0, hi0, 4) }
    val lo = minOf(lo0, ticks.firstOrNull() ?: lo0); val hi = maxOf(hi0, ticks.lastOrNull() ?: hi0)
    val labelStyle = FinType.numSmall.copy(color = p.text2)
    val capStyle = FinType.caption.copy(color = p.text2)
    val pillStyle = FinType.numSmall.copy(color = p.canvas)
    Canvas(
        Modifier.fillMaxWidth().height(height).testTag("fin:chart:$panelId")
            .pointerInput(points) {
                val right = 48.dp.toPx()
                fun idxAt(x: Float): Int = ((x / (size.width - right)) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> hoverIdx = idxAt(o.x); onHover(points[hoverIdx].label + "  " + fmtAxis(points[hoverIdx].y)); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    onDragEnd = { hoverIdx = -1; onHover(null) },
                    onDragCancel = { hoverIdx = -1; onHover(null) },
                    onDrag = { change, _ ->
                        val i = idxAt(change.position.x)
                        if (i != hoverIdx) { hoverIdx = i; onHover(points[i].label + "  " + fmtAxis(points[i].y)); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { hoverIdx = -1; zoom = 1f; onHover(null) }) }
            .pointerInput(curve) {
                if (curve) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size >= 2) {
                            val z = ev.calculateZoom()
                            if (z != 1f) { zoom = (zoom * z).coerceIn(1f, FinLogic.ZOOM_MAX); ev.changes.forEach { it.consume() } }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        val right = 48.dp.toPx(); val bottom = 16.dp.toPx(); val top = 4.dp.toPx()
        val w = size.width - right; val h = size.height - bottom - top
        val span = if (hi - lo > 1e-12) hi - lo else 1.0
        fun y(v: Double) = (top + h - ((v - lo) / span * h)).toFloat()
        fun x(i: Int): Float = if (curve) i * w / (points.size - 1) else ((points[i].x - points.first().x) / (points.last().x - points.first().x).let { if (abs(it) < 1e-12) 1.0 else it } * w).toFloat()
        ticks.forEach { t ->
            if (t < lo - 1e-9 || t > hi + 1e-9) return@forEach
            val yy = y(t)
            drawLine(p.hairline.copy(alpha = 0.3f), Offset(0f, yy), Offset(w, yy), strokeWidth = 1.dp.toPx())
            val m = measurer.measure(fmtAxis(t), labelStyle)
            drawText(m, topLeft = Offset(size.width - m.size.width, (yy - m.size.height / 2f).coerceIn(0f, (size.height - bottom - m.size.height).coerceAtLeast(0f))))
        }
        val nx = if (curve) minOf(points.size, 6) else 4
        (0 until nx).forEach { k ->
            val i = if (nx <= 1) 0 else k * (points.size - 1) / (nx - 1)
            val label = if (curve) points[i].label else points[i].label.take(10).let { if (it.length == 10) it.substring(2) else it }
            val m = measurer.measure(label, capStyle)
            val cx = x(i) - m.size.width / 2f
            drawText(m, topLeft = Offset(cx.coerceIn(0f, (w - m.size.width).coerceAtLeast(0f)), size.height - bottom + 1f))
        }
        val path = Path(); val area = Path()
        points.indices.forEach { i -> val xx = x(i); val yy = y(points[i].y); if (i == 0) { path.moveTo(xx, yy); area.moveTo(xx, top + h); area.lineTo(xx, yy) } else { path.lineTo(xx, yy); area.lineTo(xx, yy) } }
        area.lineTo(x(points.size - 1), top + h); area.close()
        drawPath(area, Brush.verticalGradient(listOf(p.accent.copy(alpha = 0.12f), p.accent.copy(alpha = 0f)), startY = top, endY = top + h))
        drawPath(path, p.accent, style = Stroke(width = 2.dp.toPx()))
        val lx = x(points.size - 1); val ly = y(points.last().y)
        drawCircle(p.accent, 3.dp.toPx(), Offset(lx, ly))
        val pm = measurer.measure(fmtAxis(points.last().y), pillStyle)
        val ph = 20.dp.toPx(); val pw = pm.size.width + 8.dp.toPx()
        val py = (ly - ph / 2).coerceIn(0f, (size.height - bottom - ph).coerceAtLeast(0f))
        drawRoundRect(p.accent, Offset(size.width - pw, py), Size(pw, ph), CornerRadius(4.dp.toPx()))
        drawText(pm, topLeft = Offset(size.width - pw + 4.dp.toPx(), py + (ph - pm.size.height) / 2))
        if (hoverIdx in points.indices) {
            val hx = x(hoverIdx); val hy = y(points[hoverIdx].y)
            drawLine(p.accent, Offset(hx, top), Offset(hx, top + h), strokeWidth = 1.dp.toPx())
            drawLine(p.accent, Offset(0f, hy), Offset(w, hy), strokeWidth = 1.dp.toPx())
            drawCircle(p.accent, 6.dp.toPx(), Offset(hx, hy), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/**
 * Horizontal labelled bars (spec 2.4 bars; checklist 7): label 96dp at the left, a 12dp bar (accent; negative values in down,
 * drawn leftwards from the zero line), the value 64dp at the right in mono. 28dp rows.
 */
@Composable
fun FinBars(points: List<ChartPoint>, unit: String, panelId: String) {
    val p = LocalFinPalette.current
    if (points.isEmpty()) return
    val maxPos = points.maxOf { it.y }.coerceAtLeast(0.0)
    val maxNeg = (-points.minOf { it.y }).coerceAtLeast(0.0)
    val total = (maxPos + maxNeg).let { if (it > 0) it else 1.0 }
    val zero = (maxNeg / total).toFloat()
    Column(Modifier.fillMaxWidth().testTag("fin:bars:$panelId")) {
        points.forEach { pt ->
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pt.label, Modifier.width(96.dp), color = p.text, style = FinType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(1f).height(12.dp).padding(horizontal = 6.dp)) {
                    val zx = size.width * zero
                    val len = (abs(pt.y) / total * size.width).toFloat().coerceAtLeast(2f)
                    if (zero > 0f) drawLine(p.hairline, Offset(zx, 0f), Offset(zx, size.height), strokeWidth = 1.dp.toPx())
                    if (pt.y >= 0) drawRoundRect(p.accent, Offset(zx, 0f), Size(len, size.height), CornerRadius(2.dp.toPx()))
                    else drawRoundRect(p.down, Offset(zx - len, 0f), Size(len, size.height), CornerRadius(2.dp.toPx()))
                }
                Text(fmtAxis(pt.y) + if (unit == "%") "%" else "", Modifier.width(64.dp), color = if (pt.y < 0) p.down else p.text, style = FinType.num, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** A stacked share bar (allocation, 8dp): one segment per leg in accent at falling alpha, with a legend. */
@Composable
fun FinStackBar(legs: List<Pair<String, Double>>) {
    val p = LocalFinPalette.current
    val sum = legs.sumOf { it.second }.let { if (it > 0) it else 1.0 }
    Column {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp))) {
            legs.forEachIndexed { i, (_, v) ->
                Box(Modifier.weight((v / sum).toFloat().coerceAtLeast(0.001f)).fillMaxSize().background(p.accent.copy(alpha = (1f - i * 0.25f).coerceAtLeast(0.25f))))
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            legs.forEach { (l, v) -> Text("$l ${fmtAxis(v / sum * 100)}%", color = p.text2, style = FinType.caption) }
        }
    }
}

/** Chart skeleton: chips row + a 200dp block. */
@Composable
fun FinChartSkeleton(height: Dp) { FinSkeleton(null, height, radius = 8.dp) }
