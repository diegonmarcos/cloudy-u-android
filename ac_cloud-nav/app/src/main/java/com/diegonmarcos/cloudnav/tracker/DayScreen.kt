package com.diegonmarcos.cloudnav.tracker

import android.app.TimePickerDialog
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.launch
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class DayTab(val label: String) { DAY("Day"), TRIPS("Trips"), PLACES("Places"), CITIES("Cities"), WORLD("World"), INSIGHTS("Insights") }

object DayTags {
    const val PREV = "day:prev"; const val NEXT = "day:next"; const val PICK = "day:pick"; const val ADD = "day:add"; const val MAP = "day:map"
    const val SUMMARY = "day:summary"; const val LIST = "day:list"
    const val BAR = "day:bar"; const val MAP_DAY = "day:mapday"; const val MAP_DAY_VIEW = "day:mapday:view"
    fun detail(i: Int) = "day:detail:$i"; fun act(i: Int, a: String) = "day:act:$i:$a"
    fun tab(t: DayTab) = "day:tab:${t.name.lowercase()}"
    fun seg(i: Int) = "day:seg:$i"
    fun confirm(i: Int) = "day:confirm:$i"
    fun edit(i: Int) = "day:edit:$i"
    fun missing(i: Int) = "day:missing:$i"
    fun menu(i: Int) = "day:menu:$i"
}

data class DayUi(
    val date: LocalDate, val today: LocalDate, val day: List<DaySeg> = emptyList(), val stats: List<DayStats> = emptyList(),
    val places: List<PlaceTotal> = emptyList(), val cities: List<CityTotal> = emptyList(), val countries: List<CountryTotal> = emptyList(),
    val loading: Boolean = false, val fixes: List<PlaceFix> = emptyList(),
    val trips: TripsResult = TripsResult(null, false, emptyList()), val homeOverride: String? = null,
    val steps: StepsTotal = StepsTotal(null, "not read yet"),
)

class DayActions(
    val onDate: (LocalDate) -> Unit = {}, val onPickDate: () -> Unit = {},
    val onConfirm: (DaySeg.Stay) -> Unit = {}, val onEditPlace: (DaySeg.Stay, String) -> Unit = { _, _ -> },
    val onEditMode: (DaySeg.Move, Mode) -> Unit = { _, _ -> }, val onDelete: (DaySeg) -> Unit = {},
    val onHome: (String?) -> Unit = {},
    val onSplit: (DaySeg, Long) -> Unit = { _, _ -> }, val onAddVisit: (Long, Long, String) -> Unit = { _, _, _ -> },
    /** Cached reverse-geocode of a place whose address is thin (off the main thread); the place itself when nothing is found. */
    val onResolve: suspend (Place) -> Place = { it },
    /** "Open in map": show the place on Cloud Nav's own map. */
    val onOpenMap: (Place) -> Unit = {},
)

object DayFormat {
    fun modeLabel(m: Mode) = if (m == Mode.TRAIN) "Transit" else m.name.lowercase().replaceFirstChar { it.uppercase() }
    fun modeColor(m: Mode): Color = Color(RouteGeo.modeArgb(m))
    fun dateLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"; today.minusDays(1) -> "Yesterday"
        else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth + " " + d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
    }
    fun hours(ms: Long) = "%.1f h".format(Locale.US, ms / 3_600_000.0)
    fun stayTitle(s: DaySeg.Stay): String = when {
        s.missing -> "Missing visit"
        else -> "At " + (s.place?.label ?: "unnamed place")
    }
}

@Composable
fun DayScreen(ui: DayUi, tab: DayTab, onTab: (DayTab) -> Unit, actions: DayActions, zone: ZoneId = ZoneId.systemDefault()) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxSize().background(p.surface)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in DayTab.values()) {
                val on = t == tab
                Text(t.label, Modifier.testTag(DayTags.tab(t)).clip(CircleShape).background(if (on) p.accent else p.surfaceSelected)
                    .clickable { onTab(t) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = if (on) p.surface else p.textPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        when (tab) {
            DayTab.DAY -> DayTabView(ui, actions, zone)
            DayTab.TRIPS -> TripsTabView(ui, actions)
            DayTab.INSIGHTS -> InsightsTabView(ui)
            DayTab.PLACES -> ListTab("Places", "The places you stayed at, by time spent (last 30 days).", ui.places.map { Triple(it.label, listOfNotNull(it.city, it.country).joinToString(", ") + "  ·  " + it.visits + " visits", DayFormat.hours(it.totalMs)) })
            DayTab.CITIES -> ListTab("Cities", "Cities by time spent (last 30 days).", ui.cities.map { Triple(it.city, listOfNotNull(it.country).joinToString(", ") + "  ·  " + it.days + " days", DayFormat.hours(it.totalMs)) })
            DayTab.WORLD -> ListTab("World", "Countries by time spent (last 30 days).", ui.countries.map { Triple(it.country, it.days.toString() + " days", DayFormat.hours(it.totalMs)) })
        }
    }
}

@Composable
private fun DayTabView(ui: DayUi, a: DayActions, zone: ZoneId) {
    val p = LocalKitPalette.current
    var adding by remember { mutableStateOf(false) }
    var addRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val rules = remember { runCatching { NavConfig.dayRules }.getOrDefault(DayRules()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var open by remember(ui.date) { mutableStateOf<Int?>(null) }
    var mapDay by remember { mutableStateOf(false) }
    val model = remember(ui.day, ui.fixes, ui.date, ui.today) { RouteGeo.build(ui.day, ui.fixes, live = ui.date == ui.today) }
    // a pin tap opens its row and scrolls the list to it (the header items come first)
    val onPin: (Int) -> Unit = { i -> mapDay = false; open = i; scope.launch { listState.animateScrollToItem(HEADER_ITEMS + i) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag(DayTags.LIST), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { DatePicker(ui, a) }
            item { SummaryBar(DayDetails.bar(ui.day, ui.steps)) { mapDay = true } }
            item { RouteMap(model, onPin) }
            item { SummaryRow(ui) }
            if (ui.loading && ui.day.isEmpty()) item { Text("Loading...", color = p.textSecondary) }
            items(ui.day.size, key = { "${ui.day[it].startMs}:${ui.day[it]::class.simpleName}" }) { i ->
                when (val s = ui.day[i]) {
                    is DaySeg.Stay -> StayRow(i, s, a, zone, ui, rules, open == i, { open = if (open == i) null else i }) { addRange = s.startMs to s.endMs; adding = true }
                    is DaySeg.Move -> MoveRow(i, s, a, zone)
                }
            }
            item { Box(Modifier.height(80.dp)) }
        }
        FloatingActionButton(onClick = {
            val last = ui.day.lastOrNull()
            addRange = (last?.startMs ?: 0L) to (last?.endMs ?: 0L); adding = true
        }, Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(DayTags.ADD)) { Text("+") }
    }
    if (mapDay) Dialog(onDismissRequest = { mapDay = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(p.surface).testTag(DayTags.MAP_DAY_VIEW)) {
            if (model.isEmpty) Text("No route for this day", Modifier.align(Alignment.Center), color = p.textSecondary)
            else DayRouteMap(model, Modifier.fillMaxSize(), onPin = onPin)
            KitStoreQuick("Close", KitStoreRow.BLUE_FILL, Modifier.align(Alignment.TopEnd).padding(12.dp)) { mapDay = false }
        }
    }
    val r = addRange
    if (adding && r != null) AddVisitDialog(r, zone, onDismiss = { adding = false }) { s, e, n -> a.onAddVisit(s, e, n); adding = false }
}

/** Items above the first segment row of the day list: date picker, summary bar, map, mode chips. */
private const val HEADER_ITEMS = 4

@Composable
private fun DatePicker(ui: DayUi, a: DayActions) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { a.onDate(ui.date.minusDays(1)) }, modifier = Modifier.testTag(DayTags.PREV)) { Text("<", color = p.textPrimary) }
        TextButton(onClick = a.onPickDate, modifier = Modifier.testTag(DayTags.PICK)) { Text(DayFormat.dateLabel(ui.date, ui.today) + " v", color = p.textPrimary, style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = { if (ui.date < ui.today) a.onDate(ui.date.plusDays(1)) }, modifier = Modifier.testTag(DayTags.NEXT)) { Text(">", color = if (ui.date < ui.today) p.textPrimary else p.textSecondary) }
    }
}

/** Total km, steps, time active and time stopped for the day, plus the "Map day" button. */
@Composable
private fun SummaryBar(b: DayBar, onMap: () -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().testTag(DayTags.BAR)) {
        KitStoreRowLine {
            for ((label, value) in listOf("Total km" to "%.1f".format(Locale.US, b.distanceM / 1000), "Total steps" to (b.steps.count?.let { StepsFormat.n(it) } ?: "n/a"),
                "Time active" to StepsFormat.duration(b.activeMs), "Time stopped" to StepsFormat.duration(b.stoppedMs))) {
                Column(Modifier.weight(1f)) {
                    KitStoreCaption(label, color = p.textSecondary)
                    KitStoreName(value, color = p.textPrimary)
                }
            }
        }
        KitStoreRowLine {
            Box(Modifier.weight(1f)) { b.steps.reason?.let { Text("Steps n/a: $it", color = p.textSecondary, fontSize = KitStoreRow.caption) } }
            KitStoreQuick("Map day", KitStoreRow.BLUE_FILL, Modifier.testTag(DayTags.MAP_DAY), onClick = onMap)
        }
    }
}

@Composable
private fun SummaryRow(ui: DayUi) {
    val p = LocalKitPalette.current
    val sum = Aggregates.summary(ui.day)
    Row(Modifier.fillMaxWidth().testTag(DayTags.SUMMARY).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((m, st) in sum.byMode) {
            Column(Modifier.clip(MaterialTheme.shapes.small).background(p.surfaceSelected).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(DayIcons.mode(m)), DayFormat.modeLabel(m), Modifier.size(16.dp), tint = DayFormat.modeColor(m))
                    Text(DayFormat.modeLabel(m), color = DayFormat.modeColor(m), style = MaterialTheme.typography.labelMedium)
                }
                Text(StepsFormat.distance(st.distanceM), color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(StepsFormat.duration(st.movingMs), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.clip(MaterialTheme.shapes.small).background(p.surfaceSelected).padding(10.dp)) {
            Text("Visits", color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
            Text(sum.visits.toString(), color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** The day's route on a real OSM basemap ([DayRouteMap]): a polyline per move in its mode's colour, mode and category icons, the current position. */
@Composable
private fun RouteMap(model: RouteModel, onPin: (Int) -> Unit) {
    val p = LocalKitPalette.current
    Box(Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium).background(p.surfaceSelected).testTag(DayTags.MAP)) {
        if (model.isEmpty) Text("No route for this day", Modifier.align(Alignment.Center), color = p.textSecondary)
        else DayRouteMap(model, Modifier.fillMaxSize(), onPin = onPin)
    }
}

/** A round glyph: a vector [res] in white on [bg]. */
@Composable
private fun Glyph(res: Int, bg: Color, desc: String, modifier: Modifier = Modifier) {
    Box(modifier.size(30.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(res), desc, Modifier.size(20.dp), tint = Color.White)
    }
}

@Composable
private fun StayRow(i: Int, s: DaySeg.Stay, a: DayActions, zone: ZoneId, ui: DayUi, rules: DayRules, expanded: Boolean, onToggle: () -> Unit, onAddMissing: () -> Unit) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    // the full address is looked up (cached) the first time the row opens
    var place by remember(s.startMs, s.place) { mutableStateOf(s.place) }
    LaunchedEffect(expanded, s.startMs) { val q = place; if (expanded && q != null && q.needsAddress) place = a.onResolve(q) }
    Column(Modifier.fillMaxWidth().testTag(DayTags.seg(i))) {
        KitStoreRowLine(onClick = if (s.missing) null else onToggle) {
            Glyph(if (s.missing) com.diegonmarcos.cloudnav.R.drawable.ic_mode_unknown else DayIcons.stay(s),
                if (s.kind == StayKind.SLEEP) Color(0xFF6366F1) else p.accent, s.place?.poi?.label ?: "Place")
            Column(Modifier.weight(1f).padding(horizontal = KitStoreRow.gap)) {
                KitStoreName(DayFormat.stayTitle(s), color = p.textPrimary)
                s.place?.address?.takeIf { it.isNotBlank() }?.let { KitStoreCaption(it, color = p.textSecondary) }
                KitStoreCaption(StepsFormat.time(s.startMs, zone) + "-" + StepsFormat.time(s.endMs, zone) + "  ·  " + StepsFormat.duration(s.durationMs) + if (s.kind == StayKind.SLEEP) "  ·  sleep" else s.place?.poi?.takeIf { it != PoiCategory.OTHER }?.let { "  ·  " + it.label } ?: "",
                    color = p.textSecondary)
            }
            Box {
                Text("⋮", Modifier.testTag(DayTags.menu(i)).clickable { menu = true }.padding(KitStoreRow.gap), color = p.textPrimary)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit place") }, onClick = { menu = false; edit = true })
                    DropdownMenuItem(text = { Text("Split") }, onClick = { menu = false; splitAt(a, s, zone, ctx) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; a.onDelete(s) })
                }
            }
        }
        if (expanded) StayDetail(i, s, place, a, zone, ui, rules)
        if (s.missing && s.durationMs >= DayTimeline.MISSING_MIN_MS)
            KitStoreRowLine { KitStoreQuick("Add visit", KitStoreRow.BLUE_FILL, Modifier.testTag(DayTags.missing(i)), onClick = onAddMissing) }
        else if (!s.missing && !s.confirmed && s.kind == StayKind.IDLE) KitStoreRowLine {
            Box(Modifier.weight(1f)) { KitStoreCaption("Visited ${s.place?.label ?: "this place"}?", color = p.textPrimary) }
            KitStoreQuick("Yes", KitStoreRow.BLUE_FILL, Modifier.testTag(DayTags.confirm(i))) { a.onConfirm(s) }
            Box(Modifier.padding(start = KitStoreRow.gap)) { KitStoreQuick("Edit", KitStoreRow.AMBER_FILL, Modifier.testTag(DayTags.edit(i))) { edit = true } }
        }
    }
    if (edit) NameDialog("Edit place", s.place?.name ?: s.place?.label ?: "", onDismiss = { edit = false }) { a.onEditPlace(s, it); edit = false }
}

/** The expanded part of a place row: geo code, accuracy, address, times, how you got there, visits, data source, and the three actions. */
@Composable
private fun StayDetail(i: Int, s: DaySeg.Stay, place: Place?, a: DayActions, zone: ZoneId, ui: DayUi, rules: DayRules) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val d = DayDetails.detail(ui.day, i, ui.fixes, rules)
    Column(Modifier.fillMaxWidth().testTag(DayTags.detail(i)).background(p.surfaceSelected)) {
        if (d == null || place == null) {
            KitStoreRowLine { KitStoreCaption("No location was recorded for this stay.", color = p.textSecondary) }
            return@Column
        }
        val address = DayDetails.fullAddress(place, rules).ifBlank { "n/a (no address found yet)" }
        DetailLine("Geo code", d.geoCode, mono = true)
        DetailLine("Accuracy", d.accuracy)
        DetailLine("Address", address)
        DetailLine("Arrival", StepsFormat.time(d.arrivalMs, zone))
        DetailLine("Departure", StepsFormat.time(d.departureMs, zone))
        DetailLine("Time spent", StepsFormat.duration(d.durationMs))
        DetailLine("Arrived by", d.arrivedBy?.let { DayFormat.modeLabel(it) + "  ·  " + StepsFormat.distance(d.arrivedDistanceM ?: 0.0) } ?: "n/a (first place of the day)")
        DetailLine("Visits today", d.visits.toString())
        DetailLine("Data source", d.source + "  ·  " + d.fixCount + " fixes")
        KitStoreRowLine {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
                KitStoreQuick("Open in map", KitStoreRow.BLUE_FILL, Modifier.testTag(DayTags.act(i, "map"))) { a.onOpenMap(place) }
                KitStoreQuick("Copy geo code", KitStoreRow.BLUE_FILL, Modifier.testTag(DayTags.act(i, "geo"))) { copyText(ctx, "Geo code", d.geoCode) }
                KitStoreQuick("Copy full address", KitStoreRow.BLUE_FILL, Modifier.testTag(DayTags.act(i, "address"))) {
                    copyText(ctx, "Address", DayDetails.copyAddress(place, rules).ifBlank { address })
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, mono: Boolean = false) {
    val p = LocalKitPalette.current
    KitStoreRowLine {
        Text(label, Modifier.weight(0.35f), color = p.textSecondary, fontSize = KitStoreRow.caption)
        Text(value, Modifier.weight(0.65f), color = p.textPrimary, fontSize = KitStoreRow.meta,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null)
    }
}

private fun copyText(ctx: android.content.Context, label: String, text: String) {
    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
        .setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    android.widget.Toast.makeText(ctx, "Copied: $text", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun MoveRow(i: Int, m: DaySeg.Move, a: DayActions, zone: ZoneId) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(DayTags.seg(i))) {
        KitStoreRowLine {
            Glyph(DayIcons.mode(m.mode), DayFormat.modeColor(m.mode), DayFormat.modeLabel(m.mode))
            Column(Modifier.weight(1f).padding(horizontal = KitStoreRow.gap)) {
                KitStoreName(DayFormat.modeLabel(m.mode) + "  ·  " + StepsFormat.distance(m.distanceM) + "  ·  " + StepsFormat.time(m.startMs, zone) + "-" + StepsFormat.time(m.endMs, zone), color = p.textPrimary)
                KitStoreCaption(StepsFormat.duration(m.durationMs) + "  ·  %.1f km/h".format(Locale.US, m.avgSpeedMps * 3.6) +
                    if (m.steps > 0) "  ·  " + StepsFormat.n(m.steps) + " steps" else "", color = p.textSecondary)
                val route = listOfNotNull(m.from?.label, m.to?.label).joinToString(" to ")
                if (route.isNotBlank()) KitStoreCaption(route, color = p.textSecondary)
            }
            Box {
                Text("⋮", Modifier.testTag(DayTags.menu(i)).clickable { menu = true }.padding(KitStoreRow.gap), color = p.textPrimary)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit mode") }, onClick = { menu = false; pick = true })
                    DropdownMenuItem(text = { Text("Split") }, onClick = { menu = false; splitAt(a, m, zone, ctx) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; a.onDelete(m) })
                }
            }
        }
    }
    if (pick) AlertDialog(onDismissRequest = { pick = false }, confirmButton = {}, title = { Text("Transport mode") }, text = {
        Column { for (mode in Mode.values().filter { it != Mode.UNKNOWN }) TextButton(onClick = { a.onEditMode(m, mode); pick = false }) { Text(DayFormat.modeLabel(mode)) } }
    })
}

private fun splitAt(a: DayActions, s: DaySeg, zone: ZoneId, ctx: android.content.Context) {
    val start = Instant.ofEpochMilli(s.startMs).atZone(zone)
    TimePickerDialog(ctx, { _, h, min ->
        val at = start.toLocalDate().atTime(h, min).atZone(zone).toInstant().toEpochMilli()
        if (at > s.startMs && at < s.endMs) a.onSplit(s, at)
    }, start.hour, start.minute, true).show()
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun AddVisitDialog(range: Pair<Long, Long>, zone: ZoneId, onDismiss: () -> Unit, onOk: (Long, Long, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add visit") },
        text = { Column {
            Text(StepsFormat.time(range.first, zone) + "-" + StepsFormat.time(range.second, zone))
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Place name") })
        } },
        confirmButton = { TextButton(onClick = { onOk(range.first, range.second, text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TripsTabView(ui: DayUi, actions: DayActions) {
    val p = LocalKitPalette.current
    var editHome by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<LocalDate?>(null) }
    if (editHome) NameDialog("Home city", ui.homeOverride ?: ui.trips.home ?: "", { editHome = false }) { editHome = false; actions.onHome(it) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { KitSectionHeader("Trips", "Consecutive nights away from home, grouped by the city you slept in. Vehicle distance only (walking excluded).") }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Home: " + (ui.trips.home ?: "unknown") + if (ui.trips.owner) " (set by you)" else " (most slept-in city, 90 days)",
                    Modifier.weight(1f).testTag("day:trips:home"), color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { editHome = true }, modifier = Modifier.testTag("day:trips:sethome")) { Text("Set home") }
                if (ui.trips.owner) TextButton(onClick = { actions.onHome(null) }, modifier = Modifier.testTag("day:trips:autohome")) { Text("Auto") }
            }
        }
        if (ui.trips.trips.isEmpty()) item { Text("No trips in the last 90 days.", color = p.textSecondary) }
        items(ui.trips.trips.asReversed(), key = { it.start.toString() }) { t ->
            KitCard(Modifier.testTag("day:trip:${t.start}").clickable { open = if (open == t.start) null else t.start }) {
                Row(Modifier.fillMaxWidth()) {
                    Text(t.cities.joinToString(" › "), Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text("${t.nights} night" + (if (t.nights == 1) "" else "s"), color = p.accent, style = MaterialTheme.typography.labelLarge)
                }
                Text(t.start.toString() + " – " + (if (t.ongoing) "now" else t.end.toString()), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text(if (t.vehicleM.isEmpty()) "No vehicle travel" else t.vehicleM.entries.joinToString("   ") { DayFormat.modeLabel(it.key) + " " + StepsFormat.distance(it.value) },
                    color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
                if (open == t.start) {
                    val model = remember(t) { Trips.route(t) }
                    Box(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.medium).background(p.surfaceSelected).testTag("day:trip:map")) {
                        if (model.isEmpty) Text("No route for this trip", Modifier.align(Alignment.Center), color = p.textSecondary)
                        else DayRouteMap(model, Modifier.fillMaxSize())
                    }
                } else Text("Tap for the route map", color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun InsightsTabView(ui: DayUi) {
    val p = LocalKitPalette.current
    val week = ui.stats.takeLast(7)
    val avgSleep = week.filter { it.sleepMs > 0 }.map { it.sleepMs }.average().let { if (it.isNaN()) 0L else it.toLong() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { KitSectionHeader("Insights", "Sleep and walking, per day.") }
        item { Text("Average sleep (7 days): " + DayFormat.hours(avgSleep), Modifier.testTag("day:insight:sleep"), color = p.textPrimary) }
        item { Bars("Hours slept", week.map { it.date.dayOfMonth.toString() to it.sleepMs / 3_600_000.0 }, "h") }
        item { Bars("Walking distance", week.map { it.date.dayOfMonth.toString() to it.walkM / 1000.0 }, "km") }
    }
}

@Composable
private fun Bars(title: String, v: List<Pair<String, Double>>, unit: String) {
    val p = LocalKitPalette.current
    val max = (v.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(0.1)
    KitCard {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            for ((_, x) in v) Box(Modifier.weight(1f).fillMaxHeight((x / max).toFloat().coerceIn(0.02f, 1f)).clip(MaterialTheme.shapes.extraSmall).background(p.accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((l, x) in v) Column(Modifier.weight(1f)) {
                Text(l, color = p.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text("%.1f".format(Locale.US, x), color = p.textPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Text("Per day, $unit", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ListTab(title: String, sub: String, rows: List<Triple<String, String, String>>) {
    val p = LocalKitPalette.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KitSectionHeader(title, sub) }
        if (rows.isEmpty()) item { Text("Nothing yet.", color = p.textSecondary) }
        items(rows.size) { i ->
            val (a, b, c) = rows[i]
            KitCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(b, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(c, color = p.accent)
                }
            }
        }
    }
}
