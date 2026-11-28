package com.diegonmarcos.clocktools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.clocktools.ClockLook
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * #1986 the reference look in Compose: the black backdrop with its indigo and purple glow, the Clock's dark colour scheme,
 * the icon island, the slim switch, the pills and the overflow dots. Every colour and size is a ClockLook token (core).
 */

/** A ClockLook colour. */
internal fun look(c: Long): Color = Color(c)

/** #1987 the Clock subpage a link asks for (ClockLink.pending), observed. */
@Composable
internal fun rememberClockLink(): String? = com.diegonmarcos.clocktools.ClockLink.pending.collectAsState().value

/** True inside a frame that already drew the backdrop (FullClock draws one under its island). */
internal val LocalClockBackdrop = compositionLocalOf { false }

/** The dark scheme every Clock page (but the World clock) is drawn in: Material parts (dialogs, chips, fields) follow it. */
internal val ClockScheme = darkColorScheme(
    primary = look(ClockLook.ACCENT), onPrimary = look(ClockLook.ON_DAY),
    primaryContainer = look(ClockLook.ACCENT), onPrimaryContainer = look(ClockLook.ON_ACCENT),
    secondaryContainer = look(ClockLook.PILL), onSecondaryContainer = look(ClockLook.PILL_TEXT),
    tertiary = look(ClockLook.HAND), tertiaryContainer = look(ClockLook.GLOW_PURPLE),
    background = look(ClockLook.BACKGROUND), onBackground = look(ClockLook.TITLE),
    surface = look(ClockLook.SHEET), onSurface = look(ClockLook.TITLE),
    surfaceVariant = look(ClockLook.LAP), onSurfaceVariant = look(ClockLook.DETAIL),
    surfaceContainerHigh = look(ClockLook.SHEET), surfaceContainerHighest = look(ClockLook.BAR), surfaceContainer = look(ClockLook.SHEET),
    outline = look(ClockLook.UNDERLINE), outlineVariant = look(ClockLook.DIVIDER),
)

/** Black, the indigo glow high, the purple glow low (both tall ellipses fading to black). */
fun Modifier.clockBackdrop(): Modifier = drawBehind { drawGlow() }

internal fun DrawScope.drawGlow() {
    drawRect(look(ClockLook.BACKGROUND))
    fun glow(c: Long, at: Triple<Float, Float, Float>) {
        val centre = Offset(size.width * at.first, size.height * at.second)
        val r = size.height * at.third
        scale(scaleX = 0.62f, scaleY = 1f, pivot = centre) {
            drawCircle(Brush.radialGradient(listOf(look(c), look(c).copy(alpha = 0.55f), Color.Transparent), centre, r), r, centre)
        }
    }
    glow(ClockLook.GLOW_INDIGO, ClockLook.GLOW_INDIGO_AT)
    glow(ClockLook.GLOW_PURPLE, ClockLook.GLOW_PURPLE_AT)
}

/** A Clock page: the dark scheme, and the backdrop unless the frame around already drew it. */
@Composable
fun ClockFrame(modifier: Modifier = Modifier, glow: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val drawn = LocalClockBackdrop.current
    MaterialTheme(colorScheme = ClockScheme, typography = MaterialTheme.typography) {
        CompositionLocalProvider(LocalClockBackdrop provides true) {
            Box(if (drawn) modifier else if (glow) modifier.clockBackdrop() else modifier.background(look(ClockLook.BACKGROUND)), content = content)
        }
    }
}

/** The bottom icon island: a dark pill, one icon per page, the selected one on a lighter pill. */
@Composable
fun ClockIsland(ids: List<String>, labels: Map<String, String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
                itemWidth: androidx.compose.ui.unit.Dp = 44.5.dp) {
    Row(
        // scrollable when a narrow screen cannot hold every page (FullClock sizes the items to fit first)
        modifier.height(ClockLook.ISLAND_H.dp).clip(CircleShape).background(look(ClockLook.ISLAND))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 5.75.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ids.forEach { id ->
            val on = id == selected
            Box(
                Modifier.width(itemWidth).height(ClockLook.ISLAND_H.dp)
                    .semantics { contentDescription = labels[id] ?: id; this.selected = on; role = Role.Tab }
                    .clickable { onSelect(id) }.testTag(ClockTags.tab(id)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Box(Modifier.requiredSize(ClockLook.ISLAND_ITEM_W.dp * (itemWidth / 44.5.dp), ClockLook.ISLAND_ITEM_H.dp).clip(CircleShape).background(look(ClockLook.ISLAND_SELECTED)))
                ClockGlyph(id, look(if (on) ClockLook.ISLAND_ICON_SELECTED else ClockLook.ISLAND_ICON), filled = on, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** The island's icons, drawn (no icon assets): alarm clock, globe with a clock, stopwatch, hourglass, interval, moon, tomato. */
@Composable
fun ClockGlyph(id: String, color: Color, filled: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val st = Stroke(w * 0.1f, cap = StrokeCap.Round)
        val c = Offset(w / 2, w / 2)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), w * 0.1f, StrokeCap.Round)
        when (id) {
            "alarms" -> {
                drawCircle(color, w * 0.37f, Offset(w / 2, w * 0.54f), style = st)
                line(0.5f, 0.54f, 0.5f, 0.36f); line(0.5f, 0.54f, 0.64f, 0.54f)
                line(0.08f, 0.2f, 0.2f, 0.08f); line(0.92f, 0.2f, 0.8f, 0.08f)
                line(0.24f, 0.86f, 0.16f, 0.96f); line(0.76f, 0.86f, 0.84f, 0.96f)
            }
            "world" -> {
                drawCircle(color, w * 0.4f, Offset(w * 0.45f, w * 0.48f), style = st)
                drawOval(color, Offset(w * 0.27f, w * 0.08f), Size(w * 0.36f, w * 0.8f), style = st)
                line(0.07f, 0.48f, 0.83f, 0.48f); line(0.45f, 0.08f, 0.45f, 0.88f)
                drawCircle(look(ClockLook.ISLAND), w * 0.25f, Offset(w * 0.76f, w * 0.76f))
                drawCircle(color, w * 0.2f, Offset(w * 0.76f, w * 0.76f), style = Stroke(w * 0.08f))
                line(0.76f, 0.76f, 0.76f, 0.66f); line(0.76f, 0.76f, 0.84f, 0.76f)
            }
            "stopwatch" -> {
                if (filled) drawCircle(color, w * 0.38f, Offset(w / 2, w * 0.58f)) else drawCircle(color, w * 0.38f, Offset(w / 2, w * 0.58f), style = st)
                line(0.36f, 0.06f, 0.64f, 0.06f)
                line(0.84f, 0.22f, 0.92f, 0.14f)
                drawLine(if (filled) look(ClockLook.ISLAND_SELECTED) else color, Offset(w / 2, w * 0.58f), Offset(w / 2, w * 0.36f), w * 0.1f, StrokeCap.Round)
            }
            "timers" -> {
                line(0.2f, 0.06f, 0.8f, 0.06f); line(0.2f, 0.94f, 0.8f, 0.94f)
                val p = Path().apply {
                    moveTo(w * 0.27f, w * 0.08f); lineTo(w * 0.73f, w * 0.08f); lineTo(w * 0.73f, w * 0.22f); lineTo(w * 0.54f, w * 0.5f)
                    lineTo(w * 0.73f, w * 0.78f); lineTo(w * 0.73f, w * 0.92f); lineTo(w * 0.27f, w * 0.92f); lineTo(w * 0.27f, w * 0.78f)
                    lineTo(w * 0.46f, w * 0.5f); lineTo(w * 0.27f, w * 0.22f); close()
                }
                if (filled) {
                    drawPath(p, color)
                    drawPath(Path().apply { moveTo(w * 0.5f, w * 0.62f); lineTo(w * 0.62f, w * 0.8f); lineTo(w * 0.38f, w * 0.8f); close() }, look(ClockLook.ISLAND_SELECTED))
                } else drawPath(p, color, style = Stroke(w * 0.09f))
            }
            "interval" -> {
                drawArc(color, 200f, 250f, false, Offset(w * 0.12f, w * 0.12f), Size(w * 0.76f, w * 0.76f), style = st)
                line(0.16f, 0.26f, 0.12f, 0.08f); line(0.16f, 0.26f, 0.34f, 0.24f)
                line(0.5f, 0.5f, 0.5f, 0.3f); line(0.5f, 0.5f, 0.64f, 0.58f)
            }
            "bedtime" -> {
                val moon = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(c, w * 0.42f))
                }
                if (filled) drawPath(moon, color) else drawPath(moon, color, style = st)
                drawCircle(look(ClockLook.ISLAND), w * 0.36f, Offset(w * 0.72f, w * 0.3f))
            }
            else -> { // pomodoro: a tomato with its leaf
                if (filled) drawCircle(color, w * 0.38f, Offset(w / 2, w * 0.58f)) else drawCircle(color, w * 0.38f, Offset(w / 2, w * 0.58f), style = st)
                line(0.5f, 0.22f, 0.5f, 0.04f); line(0.5f, 0.2f, 0.3f, 0.12f); line(0.5f, 0.2f, 0.7f, 0.12f)
            }
        }
    }
}

/** The reference's slim switch: a 25 x 15.5 dp track, lilac on, grey off, a white thumb. */
@Composable
fun ClockSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(25.dp, 15.5.dp).clip(CircleShape).background(look(if (checked) ClockLook.ACCENT else ClockLook.SWITCH_OFF))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.padding(horizontal = 1.5.dp).size(12.7.dp).clip(CircleShape).background(look(ClockLook.THUMB))) }
}

/** The ⋮ at the top right of the Timer and Stopwatch: a menu of the page's extra actions. */
@Composable
fun ClockOverflow(items: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { open = true }.semantics { contentDescription = "More options" }, contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(3.8.dp)) {
                repeat(3) { Box(Modifier.size(2.6.dp).clip(CircleShape).background(look(ClockLook.TITLE))) }
            }
        }
        DropdownMenu(open, { open = false }) {
            items.forEach { (label, act) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; act() }) }
        }
    }
}

/** A dark rounded pill with light text (the timer's presets, the Pomodoro / Interval / Bedtime choices). */
@Composable
fun ClockPill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(ClockLook.PRESET_H.dp).clip(CircleShape).background(look(if (selected) ClockLook.ACCENT else ClockLook.PILL)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = look(if (selected) ClockLook.ON_DAY else ClockLook.PILL_TEXT),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = ClockLook.PRESET_SP.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** A dark rounded card (the alarm list, running timers, laps). */
fun Modifier.clockCard(): Modifier = clip(RoundedCornerShape(ClockLook.SHEET_RADIUS.dp)).background(look(ClockLook.SHEET))

/** A day circle of the alarm sheet: filled lilac when on, a dim ring when off. */
@Composable
fun DayCircle(letter: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(ClockLook.DAY_CIRCLE.dp).clip(CircleShape).background(if (on) look(ClockLook.DAY) else Color.Transparent)
            .border(if (on) 0.dp else 1.dp, look(ClockLook.DIVIDER), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(letter, color = look(if (on) ClockLook.ON_DAY else ClockLook.DETAIL), style = MaterialTheme.typography.labelMedium.copy(fontSize = ClockLook.DAY_SP.sp, fontWeight = FontWeight.Medium)) }
}

/** The stopwatch dial of the reference: 60 ticks (every fifth bright) round a dark disc, the thin indigo hand and its dot. */
internal fun DrawScope.drawStopwatchDial(elapsedMs: Long) {
    val r = min(size.width, size.height) / 2f
    val k = r / ClockLook.DIAL_RING
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(look(ClockLook.SHEET), ClockLook.DIAL_DISC * k, c)
    drawCircle(look(ClockLook.DISC_EDGE), ClockLook.DIAL_DISC * k, c, style = Stroke(1.dp.toPx()))
    for (i in 0 until 60) {
        val a = i / 60.0 * 2 * PI - PI / 2
        val major = ClockLook.majorTick(i)
        val inner = r - ClockLook.TICK_LEN * k
        drawLine(
            look(if (major) ClockLook.TICK_MAJOR else ClockLook.TICK_MINOR),
            Offset(c.x + (inner * cos(a)).toFloat(), c.y + (inner * sin(a)).toFloat()),
            Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()),
            strokeWidth = (if (major) 2.3f else 2f) * k, cap = StrokeCap.Butt,
        )
    }
    val a = (ClockLook.handDegrees(elapsedMs) / 360.0) * 2 * PI - PI / 2
    val tail = 15f * k
    drawLine(look(ClockLook.HAND), Offset(c.x - (tail * cos(a)).toFloat(), c.y - (tail * sin(a)).toFloat()),
        Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()), strokeWidth = 2.6f * k, cap = StrokeCap.Round)
    drawCircle(look(ClockLook.HAND), 5f * k, c)
}

/** The floating Cancel | Save pill of the alarm editor. */
@Composable
fun ClockBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.size(ClockLook.BAR_W.dp, ClockLook.BAR_H.dp).clip(CircleShape).background(look(ClockLook.BAR)), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** The vertical hairline between a row's text and its switch, and between Cancel and Save. */
@Composable
fun ClockHairline(height: androidx.compose.ui.unit.Dp, color: Long = ClockLook.SWITCH_DIVIDER) {
    Box(Modifier.width(1.dp).height(height).background(look(color)))
}
