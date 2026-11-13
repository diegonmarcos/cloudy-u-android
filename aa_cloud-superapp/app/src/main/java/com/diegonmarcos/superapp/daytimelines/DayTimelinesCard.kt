package com.diegonmarcos.superapp.daytimelines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.system.LowPowerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos

/** Test tags of the day-timelines card (#1734). */
object DayTimelinesTags {
    const val CARD = "day-timelines"
    const val HEADER = "day-timelines:header"
    const val LANES = "day-timelines:lanes"
    const val DAY = "day-timelines:day"
    const val TEMP = "day-timelines:temp"
    const val MOON = "day-timelines:moon"
    const val SKY = "day-timelines:sky"
    const val NOW = "day-timelines:now"
    const val AXIS = "day-timelines:axis"
    const val LEGEND = "day-timelines:legend"
    const val STATUS = "day-timelines:status"
    const val JSON = "day-timelines:json"
    fun icon(hour: Int) = "day-timelines:icon:$hour"
    fun sky(id: String) = "day-timelines:sky:$id"
}

/**
 * The "day timelines" card (#1734), ONE shared composable drawn in two places: the Weather badge's expanded view on the Notify page
 * and the launcher's date dropdown (Calendar section, right after the Agenda). Four thin 24 h bars share one "now" marker:
 * day/night from the real solar events, hourly temperature (relative min to max) with weather icons where the condition changes,
 * the Moon above / below the horizon with rise / set arrows and its phase at transit, and the sky band (Sun and Moon signs,
 * planets, each at its meridian transit). A 00-24 axis, a legend row and a tap that flips to the JSON the bars are drawn from.
 *
 * It paints at once from what is on the phone (no network), then refreshes; a failed refresh keeps the cache and says how old it is.
 */
@Composable
fun DayTimelinesCard(modifier: Modifier = Modifier, textColor: Color = Color.White, dimColor: Color = Color(0x99FFFFFF)) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf<DayRepo.Snapshot?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        snap = withContext(Dispatchers.IO) { runCatching { DayRepo.snapshot(ctx, allowFetch = false) }.getOrNull() }
        snap = withContext(Dispatchers.IO) { runCatching { DayRepo.snapshot(ctx, allowFetch = true) }.getOrNull() } ?: snap
    }
    LaunchedEffect(Unit) {
        while (true) { delay(LowPowerMode.scaled(60_000L)); nowMs = System.currentTimeMillis() }
    }
    DayTimelinesBody(snap, nowMs, modifier, textColor, dimColor)
}

private val MONO = FontFamily.Monospace
private val GOLD = Color(0xFFFFD166)
private val MOON_LIT = Color(0xFFE6ECFF)
private val MOON_DARK = Color(0xFF2A2F55)
private val AMBER = Color(0xFFFFB74D)
private val LANE = 22.dp
private val BAR = 6.dp

/** The card for a given [snap]; split from [DayTimelinesCard] so it can be drawn (and tested) with a fixed snapshot and clock. */
@Composable
internal fun DayTimelinesBody(
    snap: DayRepo.Snapshot?, nowMs: Long, modifier: Modifier = Modifier, textColor: Color = Color.White, dimColor: Color = Color(0x99FFFFFF),
) {
    var json by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable { json = !json }.testTag(DayTimelinesTags.CARD)) {
        if (snap == null) {
            Text("Day timelines: loading", color = dimColor, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.testTag(DayTimelinesTags.STATUS))
        } else {
            val m = snap.model
            Row(Modifier.fillMaxWidth().testTag(DayTimelinesTags.HEADER), verticalAlignment = Alignment.CenterVertically) {
                Text(m.header, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = MONO, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(m.place, color = dimColor, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp))
            }
            Box(Modifier.height(6.dp))
            if (json) {
                val text = remember(snap) { runCatching { DayTimelines.toJson(m, snap.status).toString(2) }.getOrElse { "JSON unavailable: ${it.message}" } }
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(text, color = Color(0xFF7DDC8B), fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.testTag(DayTimelinesTags.JSON))
                }
                Text("tap to go back to the timelines", color = dimColor, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.padding(top = 4.dp))
            } else {
                Lanes(m, nowMs)
                Axis(dimColor)
                LegendRow(m.legend, textColor)
            }
            val notes = listOfNotNull(snap.status.line(), snap.locationNote).joinToString(" | ")
            Text(notes, color = if (snap.status.kind == DataStatus.Kind.LIVE && snap.locationNote == null) dimColor else AMBER, fontSize = 10.sp,
                fontFamily = MONO, modifier = Modifier.padding(top = 4.dp).testTag(DayTimelinesTags.STATUS))
        }
    }
}

private fun brush(stops: List<Stop>): Brush? =
    if (stops.size < 2) null else Brush.horizontalGradient(colorStops = stops.map { it.frac to Color(it.argb) }.toTypedArray())

@Composable
private fun Lanes(m: DayModel, nowMs: Long) {
    val nowFrac = ((nowMs - m.dayStartMs).toDouble() / (m.dayEndMs - m.dayStartMs)).toFloat().takeIf { it in 0f..1f }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(DayTimelinesTags.LANES)) {
        val w = maxWidth
        Column {
            // (a) day / night
            Canvas(Modifier.fillMaxWidth().height(LANE).testTag(DayTimelinesTags.DAY)) {
                drawBar(brush(m.dayStops) ?: SolidColor(Color(0xFF0B1026)))
            }
            // (b) temperature + icons where the condition changes
            Box(Modifier.fillMaxWidth().height(LANE).testTag(DayTimelinesTags.TEMP)) {
                Canvas(Modifier.fillMaxWidth().height(LANE)) { drawTemp(m.temp) }
                for (ic in m.temp.icons) Text(ic.cond.glyph(ic.night), fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.width(20.dp).offset(x = at(w, ic.frac, 20.dp), y = 2.dp).testTag(DayTimelinesTags.icon(ic.hour)))
            }
            // (c) moon above / below the horizon, rise / set arrows, phase at transit
            Box(Modifier.fillMaxWidth().height(LANE).testTag(DayTimelinesTags.MOON)) {
                Canvas(Modifier.fillMaxWidth().height(LANE)) {
                    drawBar(SolidColor(MOON_DARK))
                    val y = (size.height - BAR.toPx()) / 2
                    for ((a, b) in m.moon.above) drawRect(Color(0xFF8FA6E8), Offset(size.width * a, y), Size(size.width * (b - a), BAR.toPx()))
                }
                m.moon.rise?.let { Text("▲", color = MOON_LIT, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.width(12.dp).offset(x = at(w, it, 12.dp), y = 5.dp)) }
                m.moon.set?.let { Text("▼", color = MOON_LIT, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.width(12.dp).offset(x = at(w, it, 12.dp), y = 5.dp)) }
                m.moon.transit?.let { MoonDisc(m.moon.phase, Modifier.offset(x = at(w, it, 16.dp), y = 3.dp).size(16.dp)) }
            }
            // (d) sky band: Sun and Moon signs, planets, each at its transit
            Box(Modifier.fillMaxWidth().height(LANE).testTag(DayTimelinesTags.SKY)) {
                Canvas(Modifier.fillMaxWidth().height(LANE)) {
                    drawBar(Brush.horizontalGradient(0f to Color(0xFF020617), 0.25f to Color(0xFF1E1B4B), 0.5f to Color(0xFF312E81), 0.75f to Color(0xFF1E1B4B), 1f to Color(0xFF020617)))
                }
                for (o in m.sky) {
                    val f = o.frac ?: continue
                    val big = o.id == "sun" || o.id == "moon"
                    val label = if (big) o.glyph + o.sign.glyph else o.glyph
                    val wd = if (big) 34.dp else 16.dp
                    Text(label, color = if (o.id == "sun") GOLD else Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                        modifier = Modifier.width(wd).offset(x = at(w, f, wd), y = 4.dp).testTag(DayTimelinesTags.sky(o.id)))
                }
            }
        }
        // the shared "now" marker across all four bars
        if (nowFrac != null) Canvas(Modifier.fillMaxWidth().height(LANE * 4).testTag(DayTimelinesTags.NOW)) {
            val x = size.width * nowFrac
            drawLine(Color(0x55FFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** The x offset that centres an item of width [wd] on [frac] of [w], kept inside the bar. */
private fun at(w: Dp, frac: Float, wd: Dp): Dp = (w * frac - wd / 2).coerceIn(0.dp, (w - wd).coerceAtLeast(0.dp))

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(b: Brush) {
    val h = BAR.toPx()
    drawRoundRect(b, Offset(0f, (size.height - h) / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
}

/** Temperature bar: a grey track, then each run of consecutive hours as hour-to-hour gradients with half-hour caps. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTemp(t: TempBar) {
    val h = BAR.toPx(); val y = (size.height - h) / 2
    drawRoundRect(Color(0x33FFFFFF), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2, h / 2))
    val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(0f, y, size.width, y + h), CornerRadius(h / 2, h / 2))) }
    val half = size.width / 48f
    clipPath(clip) {
        val pts = t.points; val st = t.stops
        for (i in pts.indices) {
            val x = size.width * pts[i].frac
            val c = Color(st[i].argb)
            val prevAdj = i > 0 && pts[i - 1].hour == pts[i].hour - 1
            val nextAdj = i < pts.size - 1 && pts[i + 1].hour == pts[i].hour + 1
            if (!prevAdj) drawRect(c, Offset(x - half, y), Size(half, h))
            if (!nextAdj) drawRect(c, Offset(x, y), Size(half, h))
            if (nextAdj) {
                val x2 = size.width * pts[i + 1].frac
                drawRect(Brush.horizontalGradient(listOf(c, Color(st[i + 1].argb)), startX = x, endX = x2), Offset(x, y), Size(x2 - x, h))
            }
        }
    }
}

/** The Moon's phase silhouette (north-hemisphere view: waxing is lit on the right). */
@Composable
internal fun MoonDisc(phase: Astro.Phase, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2 - 0.5f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(MOON_DARK, r, c)
        val k = cos(Math.toRadians(phase.elongationDeg)).toFloat()
        val ew = abs(r * k).coerceAtLeast(0.01f)
        val lit = Path().apply {
            arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 180f, true)
            arcTo(Rect(c.x - ew, c.y - r, c.x + ew, c.y + r), 90f, if (k > 0) -180f else 180f, false)
            close()
        }
        if (phase.waxing) drawPath(lit, MOON_LIT)
        else withTransform({ scale(-1f, 1f, c) }) { drawPath(lit, MOON_LIT) }
    }
}

@Composable
private fun Axis(dim: Color) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(DayTimelinesTags.AXIS)) {
        val w = maxWidth
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            for (i in 0..8) {
                val x = size.width * i / 8f
                val major = i % 2 == 0
                drawLine(dim, Offset(x, 0f), Offset(x, if (major) size.height else size.height / 2), strokeWidth = 1.dp.toPx())
            }
        }
        for (hr in listOf(0, 6, 12, 18, 24)) Text("%02d:00".format(hr), color = dim, fontSize = 9.sp, fontFamily = MONO, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.width(34.dp).offset(x = at(w, hr / 24f, 34.dp), y = 9.dp))
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun LegendRow(l: Legend, text: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(DayTimelinesTags.LEGEND), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("🌅 " + l.daylight, color = text, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, modifier = Modifier.weight(1f))
            Text("🌡️ " + l.temp, color = text, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("🌙 " + l.moon, color = text, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(l.signs, color = text, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}
