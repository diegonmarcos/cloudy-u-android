@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.clocktools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.clocktools.ClockEngine
import com.diegonmarcos.clocktools.ClockLogic
import com.diegonmarcos.clocktools.ClockPlaces
import com.diegonmarcos.clocktools.ClockTools
import com.diegonmarcos.clocktools.R
import com.diegonmarcos.clocktools.WorldTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * The World clock (#768, #1377, #1423) as a time converter (#1667): a reference time that is live, or any date and time
 * picked in the default city (Now returns to live); the default city first in the table, changed from its own row or set
 * from any other row; and under every row a 00-24 h day bar over the same instants (WorldTable), so a vertical line at the
 * reference crosses each bar at that city's local hour. Everything else of the old page stays: the live seconds, the date
 * and zone line, the map (when the host draws one), the per-city day shift / offset / UTC / day-night line, move up / down,
 * remove, and Add city.
 */

/** The value [load] returns, null until it does (retried every few seconds off the main thread: a runtime dataset may not be there yet). */
@Composable
internal fun <T : Any> rememberLoaded(key: Any, load: () -> T?): T? {
    var value by remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        while (value == null) {
            value = withContext(Dispatchers.IO) { runCatching(load).getOrNull() }
            if (value == null) delay(3_000)
        }
    }
    return value
}

@Composable
fun WorldClockScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val host = remember { ClockTools.host(ctx) }
    val d = rememberClock()
    val live = rememberNow(ClockLogic.SECOND_MS)
    // #1667 null = live; else the instant picked. Saved across rotation, never stored: the converter is a view.
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    val ref = picked ?: live
    val home = ClockEngine.home(d)
    val homeZone = WorldTable.zoneOrNull(home) ?: ClockEngine.zone()
    val zones = d.zones ?: ClockTools.config(ctx).zones
    val cat = rememberLoaded("places") { host.places(ctx).load(ctx) }
    // Recomputed once a minute while live (the day bars move by minutes), at once when anything else changes.
    val rows = remember(home, zones, ref / ClockLogic.MINUTE_MS, cat) {
        WorldTable.rows(home, zones, ref) { z -> cat?.at(z)?.let { WorldTable.LatLon(it.lat, it.lon) } }
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingHome by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    fun setZones(z: List<String>) { ClockEngine.setZones(ctx, z) }

    LazyColumn(modifier.fillMaxSize().testTag(ClockTags.WORLD), contentPadding = PaddingValues(ClockMetrics.gutter)) {
        item {
            val t = Instant.ofEpochMilli(ref).atZone(homeZone)
            Text(if (picked == null) HHMMSS.format(t) else HHMM.format(t), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
            Text(DAY.format(t) + "  ·  " + homeZone.id, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The converter: the reference time is live, or a date and time in the default city.
            Row(Modifier.fillMaxWidth().padding(top = ClockMetrics.gap), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
                Text(stringResource(R.string.clock_reference), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                AssistChip(onClick = { pickDate = true }, label = { Text(DAY.format(t)) }, modifier = Modifier.testTag(ClockTags.REF_DATE))
                AssistChip(onClick = { pickTime = true }, label = { Text(HHMM.format(t)) }, modifier = Modifier.testTag(ClockTags.REF_TIME))
                TextButton(onClick = { picked = null }, enabled = picked != null, modifier = Modifier.testTag(ClockTags.REF_NOW)) {
                    Text(stringResource(if (picked == null) R.string.clock_live else R.string.clock_now))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = ClockMetrics.gap))
            if (host.hasMap) {
                // #1423 the host's world map: a tap adds or removes the nearest city.
                host.WorldMap(zones, ref, onToggle = { z -> setZones(if (z in zones) zones - z else zones + z) })
                HorizontalDivider(Modifier.padding(vertical = ClockMetrics.gap))
            }
        }
        items(rows, key = { it.zone }) { row ->
            val z = WorldTable.zoneOrNull(row.zone) ?: return@items
            val i = zones.indexOf(row.zone)
            Column(Modifier.fillMaxWidth().padding(vertical = ClockMetrics.small).testTag(ClockTags.row(row.zone))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (row.isDefault) "★ " else "") + ClockLogic.city(row.zone), style = MaterialTheme.typography.titleMedium)
                        val shift = when (ClockLogic.dayShift(z, homeZone, ref)) {
                            1 -> stringResource(R.string.clock_tomorrow) + ", "
                            -1 -> stringResource(R.string.clock_yesterday) + ", "
                            else -> ""
                        }
                        // #1377 the city's UTC offset and whether it is day or night there (#1667: from the sun when its coordinates are known).
                        val day = row.bar.spans.firstOrNull { row.bar.reference >= it.from && row.bar.reference <= it.to }?.day
                            ?: ClockLogic.isDay(z, ref)
                        val sky = if (day) "☀ day" else "☾ night"
                        val head = if (row.isDefault) stringResource(R.string.clock_default_city) else ClockLogic.offsetText(z, homeZone, ref)
                        Text(shift + head + "  ·  " + ClockLogic.utcText(z, ref) + "  ·  " + sky,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(HHMM.format(Instant.ofEpochMilli(ref).atZone(z)), style = MaterialTheme.typography.headlineMedium)
                    if (row.isDefault) {
                        TextButton(onClick = { editingHome = true }, modifier = Modifier.testTag(ClockTags.EDIT_DEFAULT)) { Text("✎") }
                    } else {
                        TextButton(onClick = {
                            // The old default city stays in the table.
                            if (home !in zones) setZones(listOf(home) + zones)
                            ClockEngine.setHome(ctx, row.zone)
                        }, modifier = Modifier.testTag(ClockTags.setDefault(row.zone))) { Text("☆") }
                    }
                    TextButton(onClick = { if (i > 0) setZones(zones.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Text("↑") }
                    TextButton(onClick = { if (i in 0 until zones.size - 1) setZones(zones.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i in 0 until zones.size - 1) { Text("↓") }
                    TextButton(onClick = { setZones(zones - row.zone) }, enabled = i >= 0) { Text("✕") }
                }
                DayBar(row.bar, Modifier.testTag(ClockTags.bar(row.zone)))
                Text(barCaption(row.bar), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedButton(onClick = { adding = true }, Modifier.padding(top = ClockMetrics.gap).testTag(ClockTags.ADD_CITY)) {
                Text(stringResource(R.string.clock_add_city))
            }
        }
    }
    if (adding) ZonePicker(cat, stringResource(R.string.clock_add_city), onDismiss = { adding = false }) { id ->
        adding = false
        if (id !in zones) setZones(zones + id)
    }
    if (editingHome) ZonePicker(cat, stringResource(R.string.clock_change_default), onDismiss = { editingHome = false }) { id ->
        editingHome = false
        ClockEngine.setHome(ctx, id)
    }
    if (pickDate) {
        val zoneNow = Instant.ofEpochMilli(ref).atZone(homeZone)
        val state = rememberDatePickerState(initialSelectedDateMillis = zoneNow.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDate = false
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        picked = WorldTable.reference(homeZone, date, zoneNow.hour * 60 + zoneNow.minute)
                    }
                }) { Text(stringResource(R.string.clock_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.clock_cancel)) } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val zoneNow = Instant.ofEpochMilli(ref).atZone(homeZone)
        val state = rememberTimePickerState(zoneNow.hour, zoneNow.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.clock_pick_time)) },
            text = { TimeInput(state) },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    picked = WorldTable.reference(homeZone, zoneNow.toLocalDate(), state.hour * 60 + state.minute)
                }) { Text(stringResource(R.string.clock_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.clock_cancel)) } },
        )
    }
}

@Composable
private fun barCaption(b: WorldTable.Bar): String {
    val start = WorldTable.hhmm(b.startMinute)
    val sun = when (b.sky) {
        WorldTable.Sky.POLAR_DAY -> stringResource(R.string.clock_polar_day)
        WorldTable.Sky.POLAR_NIGHT -> stringResource(R.string.clock_polar_night)
        WorldTable.Sky.ESTIMATED -> stringResource(R.string.clock_sun_estimated)
        WorldTable.Sky.TIMES -> stringResource(R.string.clock_sun, WorldTable.hhmm(b.rise ?: 0), WorldTable.hhmm(b.set ?: 0))
    }
    return "$start →  ·  $sun"
}

/**
 * #1667 one city's day bar: daylight and night over the shared window, a tick at the city's local 00 / 06 / 12 / 18 h and
 * a line at the reference instant. Drawn on a Canvas: every value comes from WorldTable.Bar, so nothing here can throw.
 */
@Composable
fun DayBar(bar: WorldTable.Bar, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dayC = Color(0xFFF6C453)
    val nightC = cs.surfaceVariant
    val tick = cs.outline
    val ref = cs.error
    val label = cs.onSurfaceVariant
    Box(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(ClockMetrics.dayBar).semantics { contentDescription = "Day bar" }) {
            val w = size.width
            val h = size.height
            bar.spans.forEach { s ->
                drawRect(if (s.day) dayC else nightC, topLeft = Offset((s.from * w).toFloat(), 0f), size = Size(((s.to - s.from) * w).toFloat().coerceAtLeast(0f), h))
            }
            bar.marks.forEach { m ->
                val x = (m.at * w).toFloat()
                drawLine(tick, Offset(x, if (m.hour == 0) 0f else h * 0.5f), Offset(x, h), strokeWidth = if (m.hour == 0) 2f else 1f)
            }
            val x = (bar.reference * w).toFloat()
            drawLine(ref, Offset(x, 0f), Offset(x, h), strokeWidth = 3f)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        // The bar's own local hour labels, placed by weight between the marks.
        var last = 0.0
        bar.marks.forEach { m ->
            if (m.at - last > 0.0001) Box(Modifier.weight((m.at - last).toFloat()))
            Text("%02d".format(m.hour), fontSize = 9.sp, color = label)
            last = m.at
        }
        if (1.0 - last > 0.0001) Box(Modifier.weight((1.0 - last).toFloat()))
    }
}

@Composable
internal fun ZonePicker(cat: ClockPlaces.Catalogue?, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    // A zone the device's tzdata does not know is not offered.
    val known = remember { ZoneId.getAvailableZoneIds() }
    val cities = remember(query, cat) { cat?.search(query)?.filter { it.zone in known }?.take(200) ?: emptyList() }
    val zones = remember(query, cat) {
        (cat ?: ClockPlaces.Catalogue(emptyList(), known.filter { it.contains('/') }.sorted())).searchZones(query).filter { it in known }.take(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clock_cancel)) } },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.clock_search_city)) })
                LazyColumn(Modifier.height(ClockMetrics.plotHeight).testTag(CITY_LIST_TAG)) {
                    items(cities, key = { "c:" + it.subtitle + it.name + it.zone }) { c ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(c.zone) }.padding(vertical = ClockMetrics.small)) {
                            Text((if (c.capital) "★ " else "") + c.name + "  ·  " + c.subtitle)
                            Text(c.zone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (cities.isNotEmpty()) item { HorizontalDivider(Modifier.padding(vertical = ClockMetrics.small)) }
                    items(zones, key = { "z:$it" }) { id ->
                        Text(ClockLogic.city(id) + "  ·  " + id, Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = ClockMetrics.gap))
                    }
                }
            }
        },
    )
}

const val CITY_LIST_TAG = "clock_city_list"

/**
 * #1668 the whole clock as ONE page with its own tab strip (Cloud Tasks > Tasks > Clock): World clock, Alarms, Timers,
 * Stopwatch, Interval, Bedtime, the same screens Cloud Calc shows as its Clock modes.
 */
@Composable
fun FullClock(modifier: Modifier = Modifier, initialTab: Int = 0) {
    val tabs = listOf(
        "world" to R.string.clock_tab_world, "alarms" to R.string.clock_tab_alarms, "timers" to R.string.clock_tab_timers,
        "stopwatch" to R.string.clock_tab_stopwatch, "interval" to R.string.clock_tab_interval, "bedtime" to R.string.clock_tab_bedtime,
    )
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, tabs.size - 1)) }
    Column(modifier.fillMaxSize().testTag(ClockTags.FULL)) {
        // A chip row, not a second tab bar: the host app's own nav (its top strip) stays the only navigation.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ClockMetrics.gutter, vertical = ClockMetrics.small),
            horizontalArrangement = Arrangement.spacedBy(ClockMetrics.small)) {
            tabs.forEachIndexed { i, (id, label) ->
                FilterChip(selected = i == tab, onClick = { tab = i }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag(ClockTags.tab(id)))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tabs[tab].first) {
                "world" -> WorldClockScreen()
                "alarms" -> AlarmsScreen()
                "timers" -> TimersScreen()
                "stopwatch" -> StopwatchScreen()
                "interval" -> IntervalScreen()
                "bedtime" -> BedtimeScreen()
            }
        }
    }
}
