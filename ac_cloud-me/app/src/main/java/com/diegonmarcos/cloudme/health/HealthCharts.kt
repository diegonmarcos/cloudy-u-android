package com.diegonmarcos.cloudme.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudme.R
import java.util.Locale

/**
 * The ONE chart set of the Health pages (Compose Canvas): every page draws its rings, sparklines, bars, lines, stacks, segment bars,
 * min/avg/max strips, D/W/M/Y toggles, mock badges and source cards from here, never a Canvas of its own (test-me-health-tracked T7).
 * Colours: one per metric ([MetricColor]), the same on every page.
 */
object MetricColor {
    val steps = Color(0xFF2DD4BF)
    val active = Color(0xFFA3E635)
    val calories = Color(0xFFFB923C)
    val distance = Color(0xFF38BDF8)
    val heart = Color(0xFFF43F5E)
    val sleep = Color(0xFF818CF8)
    val weight = Color(0xFFFBBF24)
    val water = Color(0xFF22D3EE)
    val bodyfat = Color(0xFFF472B6)
    val hrv = Color(0xFFA78BFA)

    fun of(id: String): Color = when (id) {
        "steps" -> steps; "active" -> active; "calories" -> calories; "distance" -> distance
        "heart", "resting_hr" -> heart; "sleep" -> sleep; "weight" -> weight; "water" -> water
        "bodyfat" -> bodyfat; "hrv" -> hrv; "workouts" -> active
        else -> Color(0xFFA78BFA)
    }

    /** Heart-rate zones, cool to hot. */
    val zones = listOf(Color(0xFF60A5FA), Color(0xFF34D399), Color(0xFFFACC15), Color(0xFFFB923C), Color(0xFFF43F5E))

    /** Sleep stages, deepest first. */
    val stages = linkedMapOf("deep" to Color(0xFF4338CA), "rem" to Color(0xFFC084FC), "light" to Color(0xFF818CF8), "awake" to Color(0xFFFBBF24))
    val stageLabels = mapOf("deep" to "Deep", "rem" to "REM", "light" to "Light", "awake" to "Awake")
}

/** A number for a chart label: no decimals from 100 up, one below, thousands grouped. */
fun chartNum(v: Double): String = when {
    v >= 100 -> "%,.0f".format(Locale.US, v)
    v >= 10 -> "%.1f".format(Locale.US, v).removeSuffix(".0")
    else -> "%.1f".format(Locale.US, v)
}

@Composable private fun grid() = colorResource(R.color.me_outline)
@Composable private fun track() = colorResource(R.color.me_surface_high)

/** The D / W / M / Y segmented toggle. */
@Composable
internal fun RangeToggle(selected: TrackedRange, onPick: (TrackedRange) -> Unit, ranges: List<TrackedRange> = TrackedRange.entries, color: Color = accent()) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(track()).padding(3.dp)) {
        ranges.forEach { r ->
            val on = r == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) color else Color.Transparent)
                    .clickable { onPick(r) }.testTag("me:health:range:${r.short}").padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(r.label, color = if (on) Color(0xFF0B0B10) else ink(), fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
        }
    }
}

data class RingSpec(val progress: Float, val color: Color)

/** Concentric goal rings, outermost first; a ring past its goal stays full. */
@Composable
internal fun GoalRings(rings: List<RingSpec>, size: Dp = 132.dp, stroke: Dp = 12.dp, center: @Composable () -> Unit = {}) {
    val bg = track()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx(); val gap = w * 0.35f
            rings.forEachIndexed { i, r ->
                val inset = w / 2 + i * (w + gap)
                val box = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                if (box.width <= 0f) return@forEachIndexed
                drawArc(bg, -90f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
                drawArc(r.color, -90f, 360f * r.progress.coerceIn(0f, 1f), false, Offset(inset, inset), box, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        center()
    }
}

/** A small single ring with a centred percentage and a caption below. */
@Composable
internal fun MiniRing(progress: Float?, color: Color, caption: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GoalRings(listOf(RingSpec(progress ?: 0f, color)), 58.dp, 6.dp) {
            Text(if (progress == null) "--" else "${(progress * 100).toInt().coerceAtMost(999)}%", color = ink(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(caption, color = dim(), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
        Text(value, color = ink(), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A 7-ish point trend line with a soft fill and the last point marked; gaps (null) are skipped. */
@Composable
internal fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(30.dp)) {
    val pts = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
    Canvas(modifier) {
        if (pts.isEmpty()) {
            drawLine(color.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            return@Canvas
        }
        val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }.let { if (it - lo < 1e-9) lo + 1 else it }
        val n = (values.size - 1).coerceAtLeast(1)
        fun px(i: Int) = i * size.width / n
        fun py(v: Double) = (size.height - 3f - ((v - lo) / (hi - lo) * (size.height - 6f))).toFloat()
        val line = Path(); val area = Path()
        pts.forEachIndexed { k, (i, v) -> if (k == 0) { line.moveTo(px(i), py(v)); area.moveTo(px(i), size.height); area.lineTo(px(i), py(v)) } else { line.lineTo(px(i), py(v)); area.lineTo(px(i), py(v)) } }
        area.lineTo(px(pts.last().first), size.height); area.close()
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.dp.toPx(), Offset(px(pts.last().first), py(pts.last().second)))
    }
}

private const val AXIS_H = 18f

private fun DrawScope.axisLabels(tm: TextMeasurer, labels: List<String>, slot: Float, color: Color, plotH: Float) {
    if (labels.isEmpty()) return
    val every = when { labels.size <= 12 -> 1; labels.size <= 24 -> 6; else -> 5 }
    labels.forEachIndexed { i, l ->
        if (i % every != 0 && i != labels.lastIndex) return@forEachIndexed
        if (i != labels.lastIndex && labels.size > 12 && labels.lastIndex - i < every / 2 + 1) return@forEachIndexed
        val t = tm.measure(l, TextStyle(color = color, fontSize = 10.sp))
        val x = (i * slot + slot / 2 - t.size.width / 2).coerceIn(0f, (size.width - t.size.width).coerceAtLeast(0f))
        drawText(t, topLeft = Offset(x, plotH + 4.dp.toPx()))
    }
}

private fun DrawScope.goalLine(tm: TextMeasurer, y: Float, label: String, color: Color) {
    drawLine(color, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    val t = tm.measure(label, TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold))
    drawText(t, topLeft = Offset(size.width - t.size.width, (y - t.size.height - 2f).coerceAtLeast(0f)))
}

private fun DrawScope.gridLines(color: Color, plotH: Float) {
    for (i in 0..3) { val y = plotH * i / 3f; drawLine(color.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1f) }
}

/** The y-axis top: the data max or the goal, whichever is higher, with headroom. */
private fun top(values: List<Double?>, goal: Double?): Double = (listOfNotNull(values.filterNotNull().maxOrNull(), goal).maxOrNull() ?: 1.0).let { if (it <= 0) 1.0 else it * 1.12 }

/** Bars, one per bucket, rounded tops, the last bucket highlighted, a dashed goal line and the axis labels. Null buckets draw nothing. */
@Composable
internal fun BarsChart(values: List<Double?>, labels: List<String>, color: Color, goal: Double? = null, height: Dp = 180.dp, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer(); val g = grid(); val lab = dim()
    val hi = top(values, goal)
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(chartNum(hi / 1.12), color = dim(), fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val plotH = size.height - AXIS_H.dp.toPx()
            gridLines(g, plotH)
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val bw = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                if (v == null || v <= 0) return@forEachIndexed
                val bh = (v / hi * plotH).toFloat().coerceAtLeast(2f)
                val c = if (i == values.lastIndex) color else color.copy(alpha = 0.55f)
                drawRoundRect(c, Offset(i * slot + (slot - bw) / 2, plotH - bh), Size(bw, bh), CornerRadius(bw / 3, bw / 3))
            }
            goal?.takeIf { it > 0 }?.let { goalLine(tm, (plotH - it / hi * plotH).toFloat(), "Goal ${chartNum(it)}", Good) }
            axisLabels(tm, labels, slot, lab, plotH)
        }
    }
}

/** A line over the buckets (the line joins the points that exist), soft fill, min and max marked, optional goal. */
@Composable
internal fun TrendLine(values: List<Double?>, labels: List<String>, color: Color, goal: Double? = null, height: Dp = 180.dp, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer(); val g = grid(); val lab = dim(); val surface = colorResource(R.color.me_surface)
    val pts = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
    val lo0 = listOfNotNull(pts.minOfOrNull { it.second }, goal).minOrNull() ?: 0.0
    val hi0 = listOfNotNull(pts.maxOfOrNull { it.second }, goal).maxOrNull() ?: 1.0
    val pad = ((hi0 - lo0) * 0.15).let { if (it < 1e-9) 1.0 else it }
    val lo = lo0 - pad; val hi = hi0 + pad
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(chartNum(hi0), color = dim(), fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val plotH = size.height - AXIS_H.dp.toPx()
            gridLines(g, plotH)
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            fun px(i: Int) = i * slot + slot / 2
            fun py(v: Double) = (plotH - (v - lo) / (hi - lo) * plotH).toFloat()
            goal?.takeIf { it > 0 }?.let { goalLine(tm, py(it), "Goal ${chartNum(it)}", Good) }
            if (pts.isNotEmpty()) {
                val line = Path(); val area = Path()
                pts.forEachIndexed { k, (i, v) -> if (k == 0) { line.moveTo(px(i), py(v)); area.moveTo(px(i), plotH); area.lineTo(px(i), py(v)) } else { line.lineTo(px(i), py(v)); area.lineTo(px(i), py(v)) } }
                area.lineTo(px(pts.last().first), plotH); area.close()
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), endY = plotH))
                drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val dot = if (pts.size > 40) 0f else 3.dp.toPx()
                if (dot > 0) pts.forEach { (i, v) -> drawCircle(color, dot, Offset(px(i), py(v))) }
                val mn = pts.minBy { it.second }; val mx = pts.maxBy { it.second }
                listOf(mn, mx).forEach { (i, v) -> drawCircle(surface, 6.dp.toPx(), Offset(px(i), py(v))); drawCircle(color, 4.5.dp.toPx(), Offset(px(i), py(v))) }
            }
            axisLabels(tm, labels, slot, lab, plotH)
        }
    }
}

/** Stacked bars: each bucket a list of (colour, value) parts bottom-up, optional goal on the stack total. */
@Composable
internal fun StackedBars(stacks: List<List<Pair<Color, Double>>>, labels: List<String>, goal: Double? = null, height: Dp = 180.dp) {
    val tm = rememberTextMeasurer(); val g = grid(); val lab = dim()
    val hi = top(stacks.map { s -> s.sumOf { it.second }.takeIf { it > 0 } }, goal)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(chartNum(hi / 1.12), color = dim(), fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val plotH = size.height - AXIS_H.dp.toPx()
            gridLines(g, plotH)
            if (stacks.isEmpty()) return@Canvas
            val slot = size.width / stacks.size
            val bw = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            stacks.forEachIndexed { i, parts ->
                var y = plotH
                parts.forEach { (c, v) ->
                    val h = (v / hi * plotH).toFloat()
                    if (h > 0) drawRect(c, Offset(i * slot + (slot - bw) / 2, y - h), Size(bw, h))
                    y -= h
                }
            }
            goal?.takeIf { it > 0 }?.let { goalLine(tm, (plotH - it / hi * plotH).toFloat(), "Goal ${chartNum(it)}", Good) }
            axisLabels(tm, labels, slot, lab, plotH)
        }
    }
}

/** One horizontal bar split into proportional coloured parts (sleep stages, HR zones). */
@Composable
internal fun SegmentBar(parts: List<Pair<Color, Double>>, height: Dp = 16.dp) {
    val total = parts.sumOf { it.second }
    val bg = track()
    Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(height).clip(RoundedCornerShape(height / 2))) {
        drawRect(bg)
        if (total <= 0) return@Canvas
        var x = 0f
        parts.forEach { (c, v) -> val w = (v / total * size.width).toFloat(); drawRect(c, Offset(x, 0f), Size(w, size.height)); x += w }
    }
}

/** A legend row: colour dot, label, value on the right. */
@Composable
internal fun LegendRow(color: Color, label: String, value: String, sub: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, color = ink(), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotBlank()) Text(sub, color = dim(), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 10.dp), maxLines = 1)
        Text(value, color = ink(), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** MIN / AVG / MAX under a chart. */
@Composable
internal fun StatStrip(stats: Stats?, fmt: (Double) -> String, unit: String) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(track()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("MIN" to stats?.min, "AVG" to stats?.avg, "MAX" to stats?.max).forEach { (k, v) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(k, color = dim(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(if (v == null) "--" else "${fmt(v)} $unit".trim(), color = ink(), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A big number with its unit and a caption above. */
@Composable
internal fun BigNumber(caption: String, value: String, unit: String, color: Color) {
    Text(caption.uppercase(), color = dim(), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (unit.isNotBlank()) Text(" $unit", color = dim(), fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
    }
}

/** A metric heading: colour dot and name, with an optional trailing note. */
@Composable
internal fun MetricHead(color: Color, label: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, color = ink(), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (trailing.isNotBlank()) Text(trailing, color = dim(), fontSize = 12.sp)
    }
}

/** The "mock data" badge (Inventory's convention): on every card drawn from [HealthMock]. */
@Composable
internal fun MockBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        HealthMock.BADGE, color = Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.testTag("me:health:mock").clip(shape).border(1.dp, Warn, shape).background(Warn.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * The honest empty state: why the page shows seeded data instead of yours, then each source that feeds it (the Health Connect record,
 * the apps that write it, how to connect), with the grant button when only Cloud Me's permission is missing.
 */
@Composable
internal fun SourceCard(sources: List<HealthSources.Source>, why: String?, onGrant: (() -> Unit)?) {
    HCard(Modifier.testTag("me:health:sources")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Where this data comes from", color = ink(), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (why != null) MockBadge()
        }
        if (why != null) HText("$why The charts on this page show seeded mock data until a source writes to Health Connect.", Warn)
        if (onGrant != null) Pill("Allow in Health Connect", true) { onGrant() }
        sources.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(MetricColor.of(s.id)))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("${s.label}  ·  Health Connect ${s.record.removeSuffix("Record")}", color = ink(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Written by ${s.writers}.", color = dim(), fontSize = 12.sp, lineHeight = 16.sp)
                    if (why != null) Text(s.how, color = dim(), fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

/** A slim top banner on a page that shows mock data: the badge and one line of why, pointing at the source card below. */
@Composable
internal fun MockBanner(why: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(Warn.copy(alpha = 0.10f)).border(1.dp, Warn.copy(alpha = 0.5f), shape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockBadge()
        Text("$why See the sources at the bottom.", color = ink(), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Two tiles per row, the odd one out keeping half width. */
@Composable
internal fun <T> TwoColumns(items: List<T>, gap: Dp = 10.dp, tile: @Composable (T, Modifier) -> Unit) {
    items.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth()) {
            tile(pair[0], Modifier.weight(1f))
            Box(Modifier.width(gap))
            if (pair.size == 2) tile(pair[1], Modifier.weight(1f)) else Box(Modifier.weight(1f))
        }
    }
}

/** Kept for the plan pages (Circadian, Hemodynamics): a free x/y line chart with vertical markers. */
data class ChartSeries(val points: List<Pair<Double, Double>>, val color: Color)

@Composable
internal fun LineChart(series: List<ChartSeries>, markers: List<Double> = emptyList(), height: Int = 160, yMin: Double? = null, yMax: Double? = null) {
    val g = grid()
    val all = series.flatMap { it.points }
    if (all.isEmpty()) return
    val x0 = all.minOf { it.first }; val x1 = all.maxOf { it.first }
    val lo = yMin ?: all.minOf { it.second }; val hi = (yMax ?: all.maxOf { it.second }).let { if (it <= lo) lo + 1 else it }
    Canvas(Modifier.fillMaxWidth().height(height.dp).padding(top = 8.dp)) {
        val w = size.width; val h = size.height
        fun px(x: Double) = if (x1 == x0) w / 2 else ((x - x0) / (x1 - x0) * w).toFloat()
        fun py(y: Double) = (h - ((y - lo) / (hi - lo) * h)).toFloat()
        gridLines(g, h)
        markers.filter { it in x0..x1 }.forEach { drawLine(Warn, Offset(px(it), 0f), Offset(px(it), h), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))) }
        series.forEach { s ->
            val pts = s.points.sortedBy { it.first }
            if (pts.size == 1) drawCircle(s.color, 5f, Offset(px(pts[0].first), py(pts[0].second)))
            else {
                val p = Path()
                pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(px(x), py(y)) else p.lineTo(px(x), py(y)) }
                drawPath(p, s.color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
