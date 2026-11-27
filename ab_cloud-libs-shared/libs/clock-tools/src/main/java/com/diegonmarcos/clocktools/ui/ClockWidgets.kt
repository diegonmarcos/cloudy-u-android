@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.diegonmarcos.clocktools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clocktools.Wheel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * #1970 the Clock's own drawing parts, in the host app's MaterialTheme (no vendor artwork, no icon assets): the wheel
 * picker with faded neighbours, the round buttons, the stopwatch dial (tick ring + sweep hand), the progress ring, the
 * analog dial of the world clock, pager dots and the ring backgrounds. Every number a wheel shows comes from core Wheel.
 */

/**
 * An endless wheel of [min]..[max]: the middle row is the value, [visible] rows in all, the others faded by distance
 * (Wheel.alpha). Scrolling snaps a row into the middle and reports it; a new [value] from outside (a preset) scrolls to it;
 * tapping a faded row brings it to the middle.
 */
@Composable
fun WheelPicker(
    value: Int,
    min: Int,
    max: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 72.dp,
    visible: Int = 3,
    style: TextStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
    format: (Int) -> String = { "%02d".format(it) },
    tag: String? = null,
) {
    val state = rememberLazyListState(Wheel.firstFor(Wheel.indexOf(value, min, max), visible))
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val rowPx = with(LocalDensity.current) { rowHeight.roundToPx() }
    val centre by remember(min, max, rowPx, visible) {
        derivedStateOf { Wheel.centre(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, rowPx, visible) }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(centre) { Wheel.valueAt(centre, min, max).let { if (it != value) onValue(it) } }
    LaunchedEffect(value) {
        if (!state.isScrollInProgress && Wheel.valueAt(centre, min, max) != value) state.scrollToItem(Wheel.firstFor(Wheel.indexOf(value, min, max), visible))
    }
    LazyColumn(
        state = state, flingBehavior = fling,
        modifier = modifier.height(rowHeight * visible).semantics { contentDescription = format(value) }.let { if (tag != null) it.testTag(tag) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(Wheel.size(min, max) * Wheel.LAPS) { i ->
            Box(
                Modifier.height(rowHeight).fillMaxWidth().clickable { scope.launch { state.animateScrollToItem(Wheel.firstFor(i, visible)) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(format(Wheel.valueAt(i, min, max)), style = style, modifier = Modifier.alpha(Wheel.alpha(i - centre)),
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

/** A wheel with its label above (Hours / Minutes / Seconds). */
@Composable
fun LabelledWheel(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier, tag: String? = null,
                  rowHeight: Dp = 72.dp, style: TextStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold)) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WheelPicker(value, min, max, onValue, Modifier.width(96.dp), rowHeight = rowHeight, style = style, tag = tag)
    }
}

/** The colon between wheels. */
@Composable
fun WheelColon(style: TextStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold)) {
    Text(":", style = style, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 4.dp))
}

/** A large round button (Start, Lap, Reset); [primary] fills it with the theme's primary container. Disabled it is dim. */
@Composable
fun RoundButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, size: Dp = 112.dp) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        color = (if (primary) scheme.primaryContainer else scheme.surfaceVariant).copy(alpha = if (enabled) 1f else 0.45f),
        contentColor = (if (primary) scheme.onPrimaryContainer else scheme.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.5f),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.titleLarge) }
    }
}

/** The stopwatch dial: 60 ticks (every 5th long) round a disc, a hand sweeping once a minute, [content] in the middle. */
@Composable
fun StopwatchDial(elapsedMs: Long, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(scheme.surfaceVariant, r * 0.78f, c)
            drawCircle(scheme.outlineVariant, r * 0.78f, c, style = Stroke(1.5f))
            for (i in 0 until 60) {
                val a = i / 60.0 * 2 * PI - PI / 2
                val major = i % 5 == 0
                val inner = r * (if (major) 0.86f else 0.89f)
                drawLine(
                    scheme.onSurfaceVariant.copy(alpha = if (major) 0.9f else 0.45f),
                    Offset(c.x + (inner * cos(a)).toFloat(), c.y + (inner * sin(a)).toFloat()),
                    Offset(c.x + (r * 0.96f * cos(a)).toFloat(), c.y + (r * 0.96f * sin(a)).toFloat()),
                    strokeWidth = if (major) 5f else 3f, cap = StrokeCap.Round,
                )
            }
            val a = (elapsedMs % 60_000L) / 60_000.0 * 2 * PI - PI / 2
            drawLine(scheme.primary, Offset(c.x - (r * 0.1f * cos(a)).toFloat(), c.y - (r * 0.1f * sin(a)).toFloat()),
                Offset(c.x + (r * 0.98f * cos(a)).toFloat(), c.y + (r * 0.98f * sin(a)).toFloat()), strokeWidth = 6f, cap = StrokeCap.Round)
            drawCircle(scheme.primary, 12f, c)
        }
        content()
    }
}

/** A ring filled to [progress] (0..1) from 12 o'clock, [content] in the middle (Interval, Pomodoro, the sleep timer). */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, content: @Composable BoxScope.() -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val w = 14.dp.toPx()
            val d = min(size.width, size.height) - w
            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** An analog dial of [zone]'s time at [now]: 12 hour marks, hour, minute and second hands. */
@Composable
fun AnalogDial(now: Long, zone: ZoneId, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val t = Instant.ofEpochMilli(now).atZone(zone)
    Canvas(modifier.aspectRatio(1f)) {
        val r = min(size.width, size.height) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(scheme.surfaceVariant, r, c)
        for (i in 0 until 12) {
            val a = i / 12.0 * 2 * PI
            drawLine(scheme.onSurfaceVariant, Offset(c.x + (r * 0.8f * sin(a)).toFloat(), c.y - (r * 0.8f * cos(a)).toFloat()),
                Offset(c.x + (r * 0.92f * sin(a)).toFloat(), c.y - (r * 0.92f * cos(a)).toFloat()), strokeWidth = if (i % 3 == 0) 5f else 2.5f)
        }
        fun hand(turns: Double, len: Float, w: Float, col: Color) {
            val a = turns * 2 * PI
            drawLine(col, c, Offset(c.x + (r * len * sin(a)).toFloat(), c.y - (r * len * cos(a)).toFloat()), strokeWidth = w, cap = StrokeCap.Round)
        }
        hand((t.hour % 12 + t.minute / 60.0) / 12.0, 0.5f, 9f, scheme.onSurface)
        hand((t.minute + t.second / 60.0) / 60.0, 0.75f, 6f, scheme.onSurface)
        hand(t.second / 60.0, 0.85f, 2.5f, scheme.primary)
        drawCircle(scheme.primary, 7f, c)
    }
}

/** Pager dots: [count] dots, [current] lit. */
@Composable
fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (i == current) 0.9f else 0.3f), CircleShape))
        }
    }
}

/** A small calendar glyph, drawn (no icon asset): the alarm sheet's date picker. */
@Composable
fun CalendarGlyph(modifier: Modifier = Modifier) {
    val col = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        drawRoundRect(col, Offset(w * 0.12f, w * 0.2f), Size(w * 0.76f, w * 0.68f), androidx.compose.ui.geometry.CornerRadius(4f), style = Stroke(2.5f))
        drawLine(col, Offset(w * 0.12f, w * 0.38f), Offset(w * 0.88f, w * 0.38f), strokeWidth = 2.5f)
        drawLine(col, Offset(w * 0.32f, w * 0.1f), Offset(w * 0.32f, w * 0.26f), strokeWidth = 2.5f)
        drawLine(col, Offset(w * 0.68f, w * 0.1f), Offset(w * 0.68f, w * 0.26f), strokeWidth = 2.5f)
        for (x in listOf(0.35f, 0.5f, 0.65f)) drawCircle(col, 1.8f, Offset(w * x, w * 0.62f))
    }
}

/** The ring screen's background [id] (ClockDesign.BACKGROUNDS) in the theme's own colours. */
fun ringBackground(id: String, s: ColorScheme): Brush = when (id) {
    "dusk" -> Brush.verticalGradient(listOf(s.background, s.tertiaryContainer))
    "ocean" -> Brush.verticalGradient(listOf(s.background, s.secondaryContainer))
    "forest" -> Brush.verticalGradient(listOf(s.tertiaryContainer, s.secondaryContainer))
    "plain" -> Brush.verticalGradient(listOf(s.background, s.background))
    else -> Brush.verticalGradient(listOf(s.background, s.primaryContainer))
}
