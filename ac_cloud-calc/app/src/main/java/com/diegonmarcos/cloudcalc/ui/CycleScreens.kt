@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudcalc.tools.Cycles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

object CycleTags {
    const val SCREEN = "tools_cycles"
    const val TODAY = "tools_cycles_today"
    fun zoom(id: String) = "tools_cycles_zoom_$id"
    fun strip(id: String) = "tools_cycles_strip_$id"
    fun date(id: String) = "tools_cycles_date_$id"
}

/** The strips' zoom: dp per day. Year shows a whole cycle on a phone, Week about one week. */
private enum class Zoom(val label: String, val dayDp: Float) { Year("Year", 1f), Month("Month", 12f), Week("Week", 52f) }

/**
 * #1409 Time & Date ▸ Calendars: every :tools calendar as one horizontal strip of its cycle cut into its units, Gregorian
 * first. Tapping a day on any strip picks that day; every strip highlights it, prints it in its own terms and scrolls it
 * into view. Today is picked on open.
 */
@Composable
fun CyclesView() {
    var picked by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var zoom by rememberSaveable { mutableStateOf(Zoom.Month) }
    val day = LocalDate.ofEpochDay(picked)
    val mapped by produceState(emptyList<Pair<Cycles.Cycle, Cycles.Pos>>(), picked) {
        value = withContext(Dispatchers.Default) { Cycles.mapDay(day) }
    }
    Column(Modifier.fillMaxSize().testTag(CycleTags.SCREEN)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = CalcMetrics.gutter), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CalcMetrics.small)) {
            Zoom.entries.forEach { z ->
                FilterChip(selected = zoom == z, onClick = { zoom = z }, label = { Text(z.label) }, modifier = Modifier.testTag(CycleTags.zoom(z.name.lowercase())))
            }
            TextButton(onClick = { picked = LocalDate.now().toEpochDay() }, modifier = Modifier.testTag(CycleTags.TODAY)) { Text("Today") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = CalcMetrics.gutter)) {
            items(mapped, key = { it.first.id }) { (cycle, pos) ->
                Strip(cycle, pos, zoom) { picked = cycle.start.plusDays(it.toLong()).toEpochDay() }
            }
        }
    }
}

@Composable
private fun Strip(cycle: Cycles.Cycle, pos: Cycles.Pos, zoom: Zoom, onPick: (Int) -> Unit) {
    val scroll: ScrollState = rememberScrollState()
    val density = LocalDensity.current
    val dayPx = with(density) { zoom.dayDp.dp.toPx() }
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurface)
    // The picked day, centred in the strip's viewport.
    LaunchedEffect(cycle.start, pos.offset, zoom) {
        scroll.animateScrollTo(((pos.offset + 0.5f) * dayPx - scroll.viewportSize / 2f).toInt().coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = CalcMetrics.gutter, vertical = CalcMetrics.small).testTag(CycleTags.strip(cycle.id))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(cycle.name, style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text(Cycles.label(cycle, pos), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(CycleTags.date(cycle.id)))
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Canvas(
                Modifier.width(with(density) { (cycle.length * dayPx).toDp() }).height(44.dp)
                    .pointerInput(cycle.start, dayPx) { detectTapGestures { o -> onPick((o.x / dayPx).toInt().coerceIn(0, cycle.length - 1)) } },
            ) {
                val h = size.height
                cycle.starts().zip(cycle.segments).forEachIndexed { i, (s, seg) ->
                    val x = s * dayPx
                    val w = seg.days * dayPx
                    drawRect(if (i % 2 == 0) colors.surfaceVariant else colors.secondaryContainer, Offset(x, 0f), Size(w, h))
                    drawLine(colors.outline, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
                    if (dayPx >= 4f) for (d in 1 until seg.days) {
                        val tx = x + d * dayPx
                        drawLine(colors.outlineVariant, Offset(tx, h * 0.7f), Offset(tx, h), strokeWidth = 1f)
                    }
                    val text = listOf(seg.label, seg.label.take(3), seg.label.take(1)).firstOrNull { t ->
                        measurer.measure(t, labelStyle).size.width + 6 < w
                    }
                    if (text != null) drawText(measurer, text, Offset(x + 3f, 2f), labelStyle)
                }
                val hx = pos.offset * dayPx
                drawRect(colors.primary, Offset(hx, 0f), Size(dayPx.coerceAtLeast(3f), h))
            }
        }
    }
}
