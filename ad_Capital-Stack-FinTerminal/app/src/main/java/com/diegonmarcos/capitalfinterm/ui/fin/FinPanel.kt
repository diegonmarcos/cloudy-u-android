package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinFormat
import kotlinx.coroutines.delay
import java.util.Locale

/** The four states every component is drawn in (spec 2): one panel answer, or why there is none. */
sealed interface FinState<out T> {
    data object Loading : FinState<Nothing>
    data class Ok<T>(val data: T, val asOf: String, val fetchedAt: String, val servedBy: String, val stale: Boolean) : FinState<T>
    data class Failed(val source: String, val reason: String) : FinState<Nothing>
    data class Empty(val reason: String) : FinState<Nothing>
}

/** Freshness of a panel's data, the footer dot (spec 2.11). */
enum class Fresh { FRESH, STALE, ERROR, NONE }

/** One skeleton block, shimmering 1.2 s (static under reduce-motion), spec 5.3. */
@Composable
fun FinSkeleton(width: Dp?, height: Dp, modifier: Modifier = Modifier, radius: Dp = 4.dp) {
    val p = LocalFinPalette.current
    val still = LocalReduceMotion.current
    val shift = if (still) 0f else {
        val t = rememberInfiniteTransition(label = "shimmer")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "x").value
    }
    val m = (if (width == null) modifier.fillMaxWidth() else modifier.width(width)).height(height).clip(RoundedCornerShape(radius))
    Box(m.drawBehind {
        drawRect(p.skeleton)
        if (!still) {
            val w = size.width
            val x = -w + shift * 2 * w
            drawRect(Brush.horizontalGradient(listOf(p.skeleton, p.skeletonHi, p.skeleton), startX = x, endX = x + w))
        }
    }.testTag("fin:skeleton"))
}

/** A flat terminal card (spec 1.4): 12dp radius, surface fill, 1dp hairline, no shadow. */
@Composable
fun FinCard(modifier: Modifier = Modifier, pad: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalFinPalette.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(FinDim.cardRadius)).background(p.surface)
            .border(1.dp, p.hairline, RoundedCornerShape(FinDim.cardRadius))
            .padding(if (pad) FinDim.cardPad else 0.dp),
        content = content,
    )
}

/** A panel's 32dp title row: the uppercase title, and at its right the amber clock when stale (spec 2 common rules). */
@Composable
fun FinTitleRow(title: String, stale: Boolean = false, staleNote: String = "", trailing: @Composable (() -> Unit)? = null) {
    val p = LocalFinPalette.current
    var tip by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().height(FinDim.title), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(Locale.ROOT), Modifier.weight(1f), color = p.text, style = FinType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke()
            if (stale) Icon(Icons.Outlined.Schedule, "stale", Modifier.padding(start = 8.dp).size(16.dp).clickable { tip = !tip }.testTag("fin:stale"), tint = p.amber)
        }
        if (tip && staleNote.isNotBlank()) Text(staleNote, color = p.amber, style = FinType.caption)
    }
}

/** 6dp status dot. */
@Composable
fun FinDot(color: Color, modifier: Modifier = Modifier) { Box(modifier.size(6.dp).clip(CircleShape).background(color)) }

/**
 * The one-line source footer (spec 2.11): freshness dot, fetched time, the data's own date when it is not today, the source as
 * a link, and "via ..." when another route (the mesh container) served it. A tap anywhere opens the footer actions.
 */
@Composable
fun FinFooter(panelId: String, fresh: Fresh, fetchedAt: String, asOf: String, source: String, servedBy: String, onTap: () -> Unit) {
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().height(FinDim.footer).clickable(onClick = onTap).testTag("fin:footer:$panelId"), verticalAlignment = Alignment.CenterVertically) {
        val dot = when (fresh) { Fresh.FRESH -> p.up; Fresh.STALE -> p.amber; Fresh.ERROR -> p.down; Fresh.NONE -> null }
        if (dot != null) { FinDot(dot); Spacer(Modifier.width(6.dp)) }
        if (fetchedAt.isNotBlank()) Text(fetchedAt, color = if (fresh == Fresh.STALE) p.amber else p.text3, style = FinType.numSmall)
        val today = java.time.LocalDate.now().toString()
        if (asOf.isNotBlank() && !asOf.startsWith(today)) Text("  ·  as of ${asOf.take(10)}", color = p.text3, style = FinType.caption, maxLines = 1)
        if (source.isNotBlank()) {
            Text("  ·  ", color = p.text3, style = FinType.caption)
            Text(FinFormat.shortSource(source), color = p.accent, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.padding(start = 2.dp).size(10.dp), tint = p.accent)
        }
        Spacer(Modifier.weight(1f))
        if (servedBy.isNotBlank()) Text("via ${FinFormat.shortSource(servedBy)}", color = p.text3, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).width(120.dp), textAlign = TextAlign.End)
    }
}

/** A panel note ("1 series unavailable"): a 20dp amber-dot line above the footer, never a loose paragraph. */
@Composable
fun FinNote(text: String) {
    val p = LocalFinPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = FinDim.note), verticalAlignment = Alignment.CenterVertically) {
        FinDot(p.amber); Spacer(Modifier.width(6.dp))
        Text(text, color = p.text2, style = FinType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Needs a key / no connector" (spec 2 common rules, checklist 1): one 48dp row, key glyph, title, what to do, chevron. */
@Composable
fun FinKeyRow(id: String, title: String, caption: String, onClick: () -> Unit) {
    val p = LocalFinPalette.current
    Row(
        Modifier.fillMaxWidth().height(FinDim.keyRow).clip(RoundedCornerShape(FinDim.cardRadius)).background(p.surface)
            .border(1.dp, p.hairline, RoundedCornerShape(FinDim.cardRadius)).clickable(onClick = onClick).padding(horizontal = 12.dp).testTag("fin:key:$id"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Key, null, Modifier.size(20.dp), tint = p.amber)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, style = FinType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = p.text3)
    }
}

/** Error with no cached data: the panel keeps its [height]; a warning glyph, one caption line, a Retry chip. */
@Composable
fun FinErrorBody(message: String, height: Dp, onRetry: () -> Unit) {
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().height(height).testTag("fin:error"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.WarningAmber, null, Modifier.size(24.dp), tint = p.down)
        Spacer(Modifier.height(4.dp))
        Text(message, color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        FinChip("Retry", selected = false, onClick = onRetry)
    }
}

/** Empty (the source answered, nothing in the window): one 40dp caption line. */
@Composable
fun FinEmptyBody(message: String) {
    val p = LocalFinPalette.current
    Box(Modifier.fillMaxWidth().height(40.dp).testTag("fin:empty"), contentAlignment = Alignment.CenterStart) {
        Text(message, color = p.text2, style = FinType.caption)
    }
}

/** A 32dp chip (spec 1.4): 8dp radius, hairline outline; selected = accent at 20 % with an accent outline and ink. */
@Composable
fun FinChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalFinPalette.current
    val shape = RoundedCornerShape(FinDim.chipRadius)
    Box(
        modifier.height(FinDim.chip).clip(shape).background(if (selected) p.accent.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, if (selected) p.accent else p.hairline, shape).clickable(onClick = onClick).padding(horizontal = 10.dp).testTag("fin:chip:$text"),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) p.accent else p.text2, style = FinType.label) }
}

/**
 * Background flash when a rendered value changes (spec 5.2): transparent -> flashUp/flashDown (100 ms) -> hold 300 ms -> transparent
 * (300 ms). Compares the formatted strings; none under reduce-motion.
 */
@Composable
fun Modifier.finFlash(value: String, dir: FinFormat.Dir): Modifier {
    val p = LocalFinPalette.current
    val still = LocalReduceMotion.current
    val color = remember { Animatable(Color.Transparent) }
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value == last) return@LaunchedEffect
        last = value
        if (still || dir == FinFormat.Dir.FLAT) return@LaunchedEffect
        val to = if (dir == FinFormat.Dir.UP) p.flashUp else p.flashDown
        color.animateTo(to, tween(100, easing = FastOutSlowInEasing))
        delay(300)
        color.animateTo(Color.Transparent, tween(300, easing = FastOutSlowInEasing))
    }
    return this.drawBehind { drawRect(color.value) }
}

/** Text-colour flash (tape, KPI, quote header): up/down -> text over 600 ms. */
@Composable
fun finFlashInk(value: String, dir: FinFormat.Dir, rest: Color): Color {
    val p = LocalFinPalette.current
    val still = LocalReduceMotion.current
    val ink = remember { Animatable(rest) }
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value, rest) {
        if (value == last) { ink.snapTo(rest); return@LaunchedEffect }
        last = value
        if (still || dir == FinFormat.Dir.FLAT) { ink.snapTo(rest); return@LaunchedEffect }
        ink.snapTo(if (dir == FinFormat.Dir.UP) p.up else p.down)
        ink.animateTo(rest, tween(600))
    }
    return ink.value
}

/** A sparkline (spec 2.2): 1.5dp stroke, up when last >= first, a hairline baseline at the first value, no dots, 20 points max. */
@Composable
fun FinSparkline(values: List<Double>, modifier: Modifier = Modifier) {
    val p = LocalFinPalette.current
    if (values.size < 2) return
    val pts = if (values.size <= 20) values else (0 until 20).map { values[it * (values.size - 1) / 19] }
    val color = if (pts.last() >= pts.first()) p.up else p.down
    Canvas(modifier.testTag("fin:spark")) {
        val lo = pts.min(); val hi = pts.max(); val span = if (hi - lo > 1e-12) hi - lo else 1.0
        fun y(v: Double) = (size.height - ((v - lo) / span * size.height)).toFloat()
        val step = size.width / (pts.size - 1)
        drawLine(p.hairline, Offset(0f, y(pts.first())), Offset(size.width, y(pts.first())), strokeWidth = 1.dp.toPx())
        val path = Path()
        pts.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** A signed change in mono with its direction colour (the sign carries direction for colour-blind users). */
@Composable
fun FinChange(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = FinType.num, align: TextAlign = TextAlign.End) {
    val p = LocalFinPalette.current
    Text(text, modifier, color = if (text == FinFormat.DASH) p.text3 else p.dir(FinFormat.dirOf(text)), style = style, textAlign = align, maxLines = 1)
}
