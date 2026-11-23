package com.diegonmarcos.cloudnav.tracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** #1862 / #1863 the day strips and calendar boxes both pages draw from [DaySegments]' blocks. */
object StripTags {
    const val DAYNIGHT = "strip:daynight"; const val ACTIVITY = "strip:activity"; const val LEGEND = "strip:legend"
    fun moveIcon(i: Int) = "strip:move:$i"; fun sleepCity(i: Int) = "strip:sleep:$i"
}

fun stateColor(s: SegState): Color = Color(s.argb)

/** The black-to-blue day/night bar over 24 h, with sunrise and sunset ticks; "fixed curve" when no location was known. */
@Composable
fun DayNightStrip(d: Daylight, from: Long, to: Long, modifier: Modifier = Modifier, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    val p = LocalKitPalette.current
    val stops = DaySegments.gradient(d, from).map { (f, c) -> f to Color(c) }.toTypedArray()
    Column(modifier.fillMaxWidth().testTag(StripTags.DAYNIGHT)) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(*stops))) {
            Canvas(Modifier.fillMaxSize()) {
                for (h in 1 until 24) { val x = size.width * h / 24f; drawLine(Color.White.copy(alpha = if (h % 6 == 0) 0.35f else 0.12f), Offset(x, 0f), Offset(x, size.height), 1f) }
                for (t in listOfNotNull(d.sunriseMs, d.sunsetMs)) { val x = size.width * DaySegments.x(t, from, to); drawLine(Color(0xFFFBBF24), Offset(x, 0f), Offset(x, size.height), 2f) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (h in listOf("00", "06", "12", "18", "24")) Text(h, Modifier.weight(1f), color = p.textSecondary, fontSize = 9.sp)
        }
        Text("Sunrise " + (d.sunriseMs?.let { StepsFormat.time(it, zone) } ?: "none") + "  ·  Sunset " + (d.sunsetMs?.let { StepsFormat.time(it, zone) } ?: "none") +
            if (d.known) "" else "  ·  fixed curve (no location)", color = p.textSecondary, fontSize = 10.sp)
    }
}

/**
 * The activity bar of the same 24 h scale: one rectangle per block in its state's colour, the transport icon over each move wide enough
 * to hold it, and under each sleep the city where it started.
 */
@Composable
fun ActivityStrip(blocks: List<Block>, from: Long, to: Long, modifier: Modifier = Modifier, height: Int = 18) {
    val p = LocalKitPalette.current
    BoxWithConstraints(modifier.fillMaxWidth().testTag(StripTags.ACTIVITY)) {
        val w = maxWidth
        Column {
            Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(3.dp)).background(stateColor(SegState.NONE))) {
                Canvas(Modifier.fillMaxSize()) {
                    for (b in blocks) {
                        val x0 = size.width * DaySegments.x(b.startMs, from, to); val x1 = size.width * DaySegments.x(b.endMs, from, to)
                        drawRect(stateColor(b.state), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
                    }
                }
                blocks.forEachIndexed { i, b ->
                    val x0 = DaySegments.x(b.startMs, from, to); val x1 = DaySegments.x(b.endMs, from, to)
                    if (b.state == SegState.MOVE && b.mode != null) {
                        val mid = w * ((x0 + x1) / 2f) - 7.dp
                        Icon(painterResource(DayIcons.mode(b.mode)), DayFormat.modeLabel(b.mode),
                            Modifier.offset(x = mid.coerceAtLeast(0.dp)).align(Alignment.CenterStart).size(14.dp).testTag(StripTags.moveIcon(i)), tint = Color.White)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(14.dp)) {
                blocks.forEachIndexed { i, b ->
                    if (b.state == SegState.SLEEP) {
                        val x0 = DaySegments.x(b.startMs, from, to)
                        Text(b.city ?: "unknown city", Modifier.offset(x = (w * x0)).testTag(StripTags.sleepCity(i)), color = stateColor(SegState.SLEEP), fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The colour key of the states (and the night / day ends of the gradient when [daylight]). */
@Composable
fun StateLegend(modifier: Modifier = Modifier, daylight: Boolean = false) {
    val p = LocalKitPalette.current
    Row(modifier.fillMaxWidth().testTag(StripTags.LEGEND), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val items = SegState.values().map { it.label to stateColor(it) } + if (daylight) listOf("Night" to Color(DaySegments.NIGHT_ARGB), "Day" to Color(DaySegments.DAY_ARGB)) else emptyList()
        for ((l, c) in items) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Text(" $l", color = p.textSecondary, fontSize = 10.sp)
        }
    }
}

/** A calendar day box's inside: the blocks stacked top (00:00) to bottom (24:00), Google-Calendar style, with 6-hour lines. */
@Composable
fun BlockColumn(blocks: List<Block>, from: Long, to: Long, modifier: Modifier = Modifier) {
    val grid = LocalKitPalette.current.textSecondary.copy(alpha = 0.18f)
    Canvas(modifier) {
        for (h in listOf(6, 12, 18)) { val y = size.height * h / 24f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
        for (b in blocks) {
            if (b.state == SegState.NONE) continue
            val y0 = size.height * DaySegments.x(b.startMs, from, to); val y1 = size.height * DaySegments.x(b.endMs, from, to)
            val inset = if (b.state == SegState.MOVE) size.width * 0.12f else 1f
            drawRoundRect(stateColor(b.state), Offset(inset, y0), Size((size.width - 2 * inset).coerceAtLeast(1f), (y1 - y0).coerceAtLeast(1.5f)),
                androidx.compose.ui.geometry.CornerRadius(3f, 3f))
        }
    }
}

/** A thin horizontal line of the blocks (the month grid's per-day bar). */
@Composable
fun BlockLine(blocks: List<Block>, from: Long, to: Long, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        for (b in blocks) {
            val x0 = size.width * DaySegments.x(b.startMs, from, to); val x1 = size.width * DaySegments.x(b.endMs, from, to)
            drawRect(stateColor(b.state), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
        }
    }
}

/** A small rounded chip (mode, kind, state). */
@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

/** Spacer of [w] dp, for dense rows. */
@Composable
fun Gap(w: Int) = Box(Modifier.width(w.dp))
