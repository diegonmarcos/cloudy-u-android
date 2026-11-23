package com.diegonmarcos.cloudnav.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TrackerTags {
    const val TIMELINE = "tracker:timeline"; const val PREV = "tracker:prev"; const val NEXT = "tracker:next"
    const val STEPS = "tracker:steps"; const val STEPS_CHART = "tracker:steps:chart"; const val STEPS_GRANT = "tracker:steps:grant"
    const val SLEEP = "tracker:sleep"; const val SLEEP_CHART = "tracker:sleep:chart"
    const val STRIPS = "tracker:timeline:strips"; const val TOTALS = "tracker:timeline:totals"
    fun modeIcon(i: Int) = "tracker:timeline:row:$i:mode"
    fun row(i: Int) = "tracker:timeline:row:$i"
    fun rowDetail(i: Int) = "tracker:timeline:row:$i:detail"
    fun stepsRow(date: String) = "tracker:steps:row:$date"
    fun sleepRow(date: String) = "tracker:sleep:row:$date"
}

object TrackerFormat {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun time(ms: Long, zone: ZoneId): String = hm.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun span(r: TimelineRow, zone: ZoneId): String = "${time(r.startMs, zone)}-${time(r.endMs, zone)}"
    fun distance(m: Long) = if (m >= 1000) "%.1f km".format(Locale.US, m / 1000.0) else "$m m"
    fun coords(lat: Double?, lon: Double?): String = if (lat != null && lon != null) "%.5f, %.5f".format(Locale.US, lat, lon) else "no coordinates"
    fun hours(h: Double) = "%.1f h".format(Locale.US, h)
    fun n(v: Long) = "%,d".format(Locale.US, v)
}

/** A plain bar chart: [bars] are 0..1 heights, the last bar is highlighted; [labels] sit under the bars. */
@Composable
fun SimpleBarChart(bars: List<Float>, labels: List<String>, tag: String, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    KitCard(modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            bars.forEachIndexed { i, h ->
                Box(Modifier.weight(1f).fillMaxHeight().testTag("$tag:bar:$i"), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(h.coerceIn(0.02f, 1f)).clip(MaterialTheme.shapes.extraSmall)
                        .background(if (i == bars.lastIndex) p.accent else p.textSecondary))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (l in labels) Text(l, Modifier.weight(1f), color = p.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * Tracker > Timeline: every STAY / MOVE of one day, as the provider's `tracker/segments` lists them. #1862: a day/night strip (black to
 * blue over 24 h, sunrise / sunset from the day's first located place, else a fixed curve), the activity strip of the same scale
 * (sleep, idle, stay, move, no data; transport icons on moves; the sleep's city under it) with its legend, a totals line, then one
 * card per row with aligned time columns, state and mode chips and every field the list had (times, place, address, coordinates,
 * mode, distance, steps; start / end / duration and the source when opened).
 */
@Composable
fun TrackerTimelineScreen(date: LocalDate, today: LocalDate, rows: List<TimelineRow>, loading: Boolean, onPrev: () -> Unit, onNext: () -> Unit, zone: ZoneId = ZoneId.systemDefault()) {
    val p = LocalKitPalette.current
    val from = DayTimeline.dayStart(date, zone); val to = DayTimeline.dayStart(date.plusDays(1), zone)
    val blocks = remember(rows, date) { if (rows.isEmpty()) emptyList() else DaySegments.blocksOfRows(rows, from, to) }
    val loc = rows.firstOrNull { it.lat != null && it.lon != null }
    val daylight = remember(date, loc?.lat, loc?.lon) { DaySegments.daylight(date, loc?.lat, loc?.lon, zone) }
    LazyColumn(Modifier.fillMaxSize().background(p.surface).padding(horizontal = 12.dp).testTag(TrackerTags.TIMELINE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("<", Modifier.testTag(TrackerTags.PREV).clickable(onClick = onPrev).padding(12.dp), color = p.accent, style = MaterialTheme.typography.titleLarge)
                Text(DayFormat.dateLabel(date, today) + "  " + date, Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                if (date.isBefore(today)) Text(">", Modifier.testTag(TrackerTags.NEXT).clickable(onClick = onNext).padding(12.dp), color = p.accent, style = MaterialTheme.typography.titleLarge)
            }
        }
        item { KitSectionHeader("Timeline", "Every stay and move of the day: times, coordinates, address, mode, distance and steps.") }
        item {
            KitCard(Modifier.testTag(TrackerTags.STRIPS)) {
                DayNightStrip(daylight, from, to, zone = zone)
                Box(Modifier.height(4.dp))
                ActivityStrip(blocks, from, to)
                StateLegend(daylight = true)
            }
        }
        if (rows.isNotEmpty()) item { TimelineTotals(rows, blocks) }
        if (loading && rows.isEmpty()) item { Text("Loading...", color = p.textSecondary) }
        else if (rows.isEmpty()) item { Text("No locations or activities recorded for this day.", color = p.textSecondary) }
        items(rows.size) { i -> TimelineCard(i, rows[i], zone) }
        item { Box(Modifier.height(16.dp)) }
    }
}

/** The day's totals: moving distance, steps, time per state, the sleep city. */
@Composable
private fun TimelineTotals(rows: List<TimelineRow>, blocks: List<Block>) {
    val p = LocalKitPalette.current
    val moves = rows.filter { it.type == "MOVE" }
    val per = SegState.values().associateWith { st -> blocks.filter { it.state == st }.sumOf { it.durationMs } }
    Row(Modifier.fillMaxWidth().testTag(TrackerTags.TOTALS).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Total("Distance", TrackerFormat.distance(moves.sumOf { it.distanceM }), p.textPrimary)
        Total("Steps", TrackerFormat.n(moves.sumOf { it.steps }), p.textPrimary)
        Total("Moves", moves.size.toString(), stateColor(SegState.MOVE))
        Total("Stays", (rows.size - moves.size).toString(), stateColor(SegState.STAY))
        for (st in listOf(SegState.SLEEP, SegState.IDLE, SegState.STAY, SegState.MOVE)) if ((per[st] ?: 0L) > 0) Total(st.label, StepsFormat.duration(per[st] ?: 0L), stateColor(st))
        DaySegments.sleepCities(blocks).firstOrNull()?.let { Total("Slept in", it.second, stateColor(SegState.SLEEP)) }
    }
}

@Composable
private fun Total(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val p = LocalKitPalette.current
    Column(Modifier.clip(MaterialTheme.shapes.small).background(p.surfaceSelected).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(label, color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** One STAY / MOVE: a time column (start over end), a state bar, the chips (type, mode with its icon, duration), place, address, coordinates, distance and steps. */
@Composable
private fun TimelineCard(i: Int, r: TimelineRow, zone: ZoneId) {
    val p = LocalKitPalette.current
    var open by remember { mutableStateOf(false) }   // #1851 each stay and move opens its detail
    val st = DaySegments.stateOf(r)
    val mode = if (r.type == "MOVE") Mode.parse(r.mode) else null
    KitCard(Modifier.testTag(TrackerTags.row(i)).clickable { open = !open }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(46.dp)) {
                Text(TrackerFormat.time(r.startMs, zone), color = p.textPrimary, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                Text(TrackerFormat.time(r.endMs, zone), color = p.textSecondary, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
            Box(Modifier.width(4.dp).height(44.dp).clip(MaterialTheme.shapes.extraSmall).background(stateColor(st)))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.type + "  " + TrackerFormat.span(r, zone), Modifier.weight(1f), color = if (r.type == "MOVE") p.accent else p.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    if (mode != null) Icon(painterResource(DayIcons.mode(mode)), DayFormat.modeLabel(mode), Modifier.size(16.dp).testTag(TrackerTags.modeIcon(i)), tint = DayFormat.modeColor(mode))
                    Chip(r.mode, if (mode != null) DayFormat.modeColor(mode) else stateColor(st))
                    Chip(StepsFormat.duration(r.endMs - r.startMs), p.textSecondary)
                }
                Text(r.title, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                if (r.address.isNotBlank()) Text(r.address, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text(TrackerFormat.coords(r.lat, r.lon), color = p.textSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (r.type == "MOVE") Text(TrackerFormat.distance(r.distanceM) + "  ·  " + TrackerFormat.n(r.steps) + " steps", color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
                if (r.mode == "sleep") Text("Slept in " + r.city.ifBlank { r.title }, color = stateColor(SegState.SLEEP), style = MaterialTheme.typography.bodySmall)
                if (open) {
                    Text("Start " + StepsFormat.time(r.startMs, zone) + "  ·  End " + StepsFormat.time(r.endMs, zone) + "  ·  " + StepsFormat.duration(r.endMs - r.startMs),
                        Modifier.testTag(TrackerTags.rowDetail(i)), color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
                    Text("State: " + st.label + (if (r.city.isNotBlank()) "  ·  City: " + r.city else ""), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("Source: the one tracker store (fixes, steps, sleep)", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Tracker > Steps: steps per day from `tracker/steps`, a chart (oldest to newest) over a table (newest first). */
@Composable
fun TrackerStepsScreen(rows: List<StepsRow>, status: String, hint: String, needsPermission: Boolean, loading: Boolean, onGrant: () -> Unit) {
    val p = LocalKitPalette.current
    val asc = rows.asReversed()
    TrackerPage(Modifier.testTag(TrackerTags.STEPS)) {
        KitSectionHeader("Steps per day", "From the tracker provider (Health Connect, else this phone's counter).")
        if (status.isNotBlank()) Text(status, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (hint.isNotBlank()) Text(hint, color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
        if (needsPermission) OutlinedButton(onClick = onGrant, modifier = Modifier.testTag(TrackerTags.STEPS_GRANT)) { Text("Grant access") }
        if (loading && rows.isEmpty()) Text("Loading...", color = p.textSecondary)
        if (rows.isNotEmpty()) SimpleBarChart(TrackerRows.bars(asc.map { it.steps.toDouble() }), asc.map { it.date.takeLast(2) }, TrackerTags.STEPS_CHART)
        for (r in rows) Row(Modifier.fillMaxWidth().testTag(TrackerTags.stepsRow(r.date))) {
            Text(r.date, Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(TrackerFormat.n(r.steps) + " steps", color = p.accent, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Tracker > Sleep: sleep per day from `tracker/daily`, a chart over a table. */
@Composable
fun TrackerSleepScreen(rows: List<SleepRow>, loading: Boolean) {
    val p = LocalKitPalette.current
    val asc = rows.asReversed()
    TrackerPage(Modifier.testTag(TrackerTags.SLEEP)) {
        KitSectionHeader("Sleep per day", "The sleep that ended on each date, from the tracker provider.")
        if (loading && rows.isEmpty()) Text("Loading...", color = p.textSecondary)
        if (rows.isNotEmpty()) SimpleBarChart(TrackerRows.bars(asc.map { it.hours }), asc.map { it.date.takeLast(2) }, TrackerTags.SLEEP_CHART)
        for (r in rows) Row(Modifier.fillMaxWidth().testTag(TrackerTags.sleepRow(r.date))) {
            Text(r.date, Modifier.weight(0.4f), color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(r.city, Modifier.weight(0.35f), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(TrackerFormat.hours(r.hours), color = p.accent, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
