package com.diegonmarcos.cloudnav.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class ExportFormat { JSON, KMZ }

/** #1863 the Export / Import page's actions (the import's own pickers stay in [DayActions]). Ranges are epoch ms [from, to). */
class DataActions(
    val onExport: (ExportFormat, Pair<Long, Long>?) -> Unit = { _, _ -> }, val onRestore: () -> Unit = {},
    val onDedupePreview: () -> Unit = {}, val onDedupeConfirm: (DedupePlan) -> Unit = {}, val onDedupeCancel: () -> Unit = {},
    val onRangePreview: (Long, Long) -> Unit = { _, _ -> }, val onRangeConfirm: (Long, Long) -> Unit = { _, _ -> }, val onRangeCancel: () -> Unit = {},
    val onPickRange: ((LocalDate, LocalDate) -> Unit) -> Unit = {},
    val onDeleteAll: () -> Unit = {}, val onRefresh: () -> Unit = {},
)

object ExploredTags {
    const val WEEK = "explored:week"; const val MONTH = "explored:month"; const val LEGEND = "explored:legend"
    fun day(d: LocalDate) = "explored:cal:$d"
    fun table(id: String) = "explored:table:$id"; fun sort(table: String, col: String) = "explored:table:$table:sort:$col"
    fun row(table: String, i: Int) = "explored:table:$table:row:$i"; fun last(table: String, i: Int) = "explored:table:$table:last:$i"
    const val EXPORT_JSON = "data:export:json"; const val EXPORT_KMZ = "data:export:kmz"; const val EXPORT_RANGE = "data:export:range"; const val RESTORE = "data:restore"
    const val DEDUPE = "data:dedupe"; const val DEDUPE_OK = "data:dedupe:ok"; const val DELETE_RANGE = "data:delete:range"; const val DELETE_RANGE_OK = "data:delete:range:ok"
    const val DELETE_ALL = "data:delete:all"; const val DELETE_ALL_OK1 = "data:delete:all:ok1"; const val DELETE_ALL_OK2 = "data:delete:all:ok2"
    const val REFRESH = "data:refresh"; const val STATUS = "data:status"; const val COUNTS = "data:counts"
}

private fun dayBounds(d: LocalDate, zone: ZoneId) = DayTimeline.dayStart(d, zone) to DayTimeline.dayStart(d.plusDays(1), zone)

/** A day's blocks: the cached digest's, or the live day list for the shown date (always fresher). */
private fun blocksOf(ui: DayUi, d: LocalDate, zone: ZoneId): List<Block> {
    if (d == ui.date && ui.day.isNotEmpty()) { val (a, b) = dayBounds(d, zone); return DaySegments.blocks(ui.day, a, minOf(b, maxOf(a, System.currentTimeMillis()))) }
    return ui.digestOf(d)?.blocks ?: emptyList()
}

@Composable
private fun NavHeader(title: String, sub: String, onPrev: () -> Unit, onNext: (() -> Unit)?) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrev) { Text("<", color = p.textPrimary) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = p.textSecondary, fontSize = 11.sp)
        }
        TextButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text(">", color = if (onNext != null) p.textPrimary else p.textSecondary) }
    }
}

/** #1863 Week: seven day columns (Mon..Sun), each a 24 h box with its segments as event rectangles; tap a day to open it. */
@Composable
fun WeekTabView(ui: DayUi, a: DayActions, zone: ZoneId) {
    val p = LocalKitPalette.current
    val mon = ui.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val days = (0..6).map { mon.plusDays(it.toLong()) }
    val tracked = days.count { ui.digestOf(it)?.tracks == true || (it == ui.date && ui.day.isNotEmpty()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag(ExploredTags.WEEK), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { NavHeader("Week of $mon", "$tracked of 7 days with tracks", { a.onDate(ui.date.minusWeeks(1)) },
            if (days.last() < ui.today) ({ a.onDate(minOf(ui.date.plusWeeks(1), ui.today)) }) else null) }
        item { StateLegend(Modifier.testTag(ExploredTags.LEGEND)) }
        item {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(22.dp).height(330.dp).padding(top = 30.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    for (h in listOf("0", "6", "12", "18", "24")) Text(h, color = p.textSecondary, fontSize = 9.sp)
                }
                for (d in days) {
                    val dg = ui.digestOf(d); val (s, e) = dayBounds(d, zone)
                    val isSel = d == ui.date; val future = d.isAfter(ui.today)
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp).testTag(ExploredTags.day(d)).clickable(enabled = !future) { a.onOpenDay(d) },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val weekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
                        Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + if (d == ui.today) " •" else "",
                            color = if (d == ui.today) p.accent else if (weekend) p.textSecondary.copy(alpha = 0.7f) else p.textSecondary, fontSize = 10.sp)
                        Text(d.dayOfMonth.toString(), color = if (isSel) p.accent else p.textPrimary, fontWeight = if (isSel) FontWeight.Bold else null, fontSize = 13.sp)
                        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(4.dp)).background(p.surfaceSelected)
                            .border(if (isSel) 1.dp else 0.dp, if (isSel) p.accent else Color.Transparent, RoundedCornerShape(4.dp))) {
                            BlockColumn(blocksOf(ui, d, zone), s, e, Modifier.fillMaxSize())
                        }
                        Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(if (dg?.tracks == true) p.accent else Color.Transparent))
                        // polish: the sleep city and the day's distance under each column
                        Text(dg?.stats?.sleepCity ?: "", color = stateColor(SegState.SLEEP), fontSize = 8.sp, maxLines = 1)
                        Text(dg?.stats?.summary?.totalDistanceM?.takeIf { it > 0 }?.let { TableSort.km(it) } ?: "", color = p.textSecondary, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
        item { KitSectionHeader("Days", "Distance, sleep and visits per day; tap to open the day.") }
        items(days.filter { !it.isAfter(ui.today) }.reversed(), key = { it.toString() }) { d -> DaySummaryLine(ui, d, a) }
    }
}

@Composable
private fun DaySummaryLine(ui: DayUi, d: LocalDate, a: DayActions) {
    val p = LocalKitPalette.current
    val st = ui.digestOf(d)?.stats
    Row(Modifier.fillMaxWidth().clickable { a.onOpenDay(d) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(DayFormat.dateLabel(d, ui.today), Modifier.weight(1.3f), color = p.textPrimary, fontSize = 12.sp)
        Text(st?.let { StepsFormat.distance(it.summary.totalDistanceM) } ?: "n/a", Modifier.weight(1f), color = p.textPrimary, fontSize = 12.sp)
        Text(st?.let { DayFormat.hours(it.sleepMs) + " sleep" } ?: "", Modifier.weight(1f), color = stateColor(SegState.SLEEP), fontSize = 12.sp)
        Text(st?.let { "${it.summary.visits} visits" } ?: "", Modifier.weight(0.9f), color = p.textSecondary, fontSize = 12.sp)
        Text(st?.sleepCity ?: "", Modifier.weight(1f), color = p.textSecondary, fontSize = 11.sp, maxLines = 1)
    }
}

/** #1863 Month: a 6 x 7 grid of day boxes (Mon first), each with its segments as a 24 h column, the date and a track dot. */
@Composable
fun MonthTabView(ui: DayUi, a: DayActions, zone: ZoneId) {
    val p = LocalKitPalette.current
    val first = ui.date.withDayOfMonth(1)
    val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (0 until 6).map { w -> (0..6).map { start.plusDays(w * 7L + it) } }.filter { wk -> wk.any { it.month == first.month } }
    val inMonth = generateSequence(first) { it.plusDays(1) }.takeWhile { it.month == first.month }.toList()
    val tracked = inMonth.count { ui.digestOf(it)?.tracks == true }
    val km = inMonth.sumOf { ui.digestOf(it)?.stats?.summary?.totalDistanceM ?: 0.0 }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag(ExploredTags.MONTH), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { NavHeader(first.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + first.year,
            "$tracked days with tracks  ·  " + StepsFormat.distance(km), { a.onDate(first.minusMonths(1)) },
            if (first.plusMonths(1) <= ui.today) ({ a.onDate(minOf(first.plusMonths(1), ui.today)) }) else null) }
        item { StateLegend() }
        item {
            Row(Modifier.fillMaxWidth()) {
                for (d in weeks.first()) Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.weight(1f), color = p.textSecondary, fontSize = 10.sp)
            }
        }
        items(weeks.size) { w ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (d in weeks[w]) {
                    val dg = ui.digestOf(d); val (s, e) = dayBounds(d, zone)
                    val other = d.month != first.month; val future = d.isAfter(ui.today); val isSel = d == ui.date
                    Column(Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(4.dp)).background(p.surfaceSelected.copy(alpha = if (other) 0.35f else 1f))
                        .border(if (isSel) 1.dp else 0.dp, if (isSel) p.accent else Color.Transparent, RoundedCornerShape(4.dp))
                        .testTag(ExploredTags.day(d)).clickable(enabled = !future) { a.onOpenDay(d) }.padding(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.dayOfMonth.toString(), Modifier.weight(1f), color = if (other) p.textSecondary else p.textPrimary, fontSize = 10.sp,
                                fontWeight = if (isSel) FontWeight.Bold else null)
                            if (dg?.tracks == true) Box(Modifier.size(5.dp).clip(CircleShape).background(p.accent))
                        }
                        Row(Modifier.weight(1f)) {
                            BlockColumn(blocksOf(ui, d, zone), s, e, Modifier.weight(1f).fillMaxSize())
                        }
                        val st = dg?.stats
                        if (st != null && (st.summary.totalDistanceM > 0 || st.sleepMs > 0))
                            Text(listOfNotNull(st.summary.totalDistanceM.takeIf { it > 0 }?.let { TableSort.km(it) }, st.sleepMs.takeIf { it > 0 }?.let { "%.0fh".format(Locale.US, it / 3_600_000.0) }).joinToString(" "),
                                color = p.textSecondary, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
        item { Text("A dot marks a day the store holds a track for. Tap a day to open it.", color = p.textSecondary, fontSize = 11.sp) }
    }
}

/** #1863 a sortable dense table (World, Cities, Places): tap a header to sort by it (again to reverse); a last-visit cell opens that day. */
@Composable
fun <T> SortableTable(id: String, title: String, sub: String, cols: List<TableCol<T>>, rows: List<T>, initial: String, lastVisit: ((T) -> LocalDate?)?, onOpenDay: (LocalDate) -> Unit,
                      footer: List<String> = emptyList()) {
    val p = LocalKitPalette.current
    var sortBy by remember(id) { mutableStateOf(initial) }
    var asc by remember(id) { mutableStateOf(false) }
    val col = cols.firstOrNull { it.id == sortBy } ?: cols.first()
    val sorted = remember(rows, sortBy, asc) { TableSort.sort(rows, col, asc) }
    val totalW = cols.sumOf { it.weight.toDouble() }.toFloat()
    val scroll = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp).testTag(ExploredTags.table(id)), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { KitSectionHeader(title, sub) }
        for (f in footer) item { Text(f, color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
        stickyHeaderCompat {
            Row(Modifier.horizontalScroll(scroll).width((totalW * 64).dp).background(p.surfaceSelected).padding(vertical = 6.dp)) {
                for (c in cols) {
                    val on = c.id == col.id
                    Text(c.label + if (on) (if (asc) " ▲" else " ▼") else "", Modifier.weight(c.weight).testTag(ExploredTags.sort(id, c.id))
                        .clickable { if (on) asc = !asc else { sortBy = c.id; asc = !c.numeric && c.id != "last" } }.padding(horizontal = 3.dp),
                        color = if (on) p.accent else p.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (sorted.isEmpty()) item { Text("Nothing yet.", color = p.textSecondary, modifier = Modifier.padding(8.dp)) }
        items(sorted.size) { i ->
            val r = sorted[i]
            Row(Modifier.horizontalScroll(scroll).width((totalW * 64).dp).background(if (i % 2 == 0) p.surfaceSelected.copy(alpha = 0.45f) else Color.Transparent)
                .padding(vertical = 5.dp).testTag(ExploredTags.row(id, i))) {
                for (c in cols) {
                    val isLast = c.id == "last" && lastVisit != null
                    val date = if (isLast) lastVisit?.invoke(r) else null
                    Text(c.text(r), (if (isLast) Modifier.testTag(ExploredTags.last(id, i)).clickable(enabled = date != null) { date?.let(onOpenDay) } else Modifier)
                        .weight(c.weight).padding(horizontal = 3.dp),
                        color = if (isLast && date != null) p.accent else if (c.id == col.id) p.textPrimary else p.textPrimary.copy(alpha = 0.85f),
                        fontWeight = if (c.id == col.id) FontWeight.SemiBold else null, fontSize = 12.sp, maxLines = 1, textAlign = if (c.numeric) androidx.compose.ui.text.style.TextAlign.End else null,
                        textDecoration = if (isLast && date != null) androidx.compose.ui.text.style.TextDecoration.Underline else null)
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

/** A header row kept as an ordinary item (the lazy sticky-header API is experimental in this Compose version). */
private fun androidx.compose.foundation.lazy.LazyListScope.stickyHeaderCompat(content: @Composable () -> Unit) = item { content() }

/** #1863 Export / Import: the Takeout import (unchanged, [ImportTabView], first as before), export, delete duplicates, delete by dates, delete all, refresh. */
@Composable
fun ExportImportView(ui: DayUi, a: DayActions, zone: ZoneId) {
    val p = LocalKitPalette.current
    val d = a.data
    var range by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var delAll by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    val rangeMs = range?.let { ExploredDelete.range(it.first, it.second, zone) }
    ImportTabView(a, before = {
        item { KitSectionHeader("Export / Import", "Export the store, import a Google history, remove duplicates or a date range, delete everything, or recompute the cache.") }
        ui.dataStatus?.let { s -> item { KitCard { Text(s, Modifier.testTag(ExploredTags.STATUS), color = p.textPrimary, style = MaterialTheme.typography.bodyMedium) } } }
    }, after = {
        // the import (unchanged) stays first under the header, where it always was; export and the clean-up actions follow it
        item {
            KitCard(Modifier.testTag(ExploredTags.COUNTS)) {
                Text("Stored data", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(ui.counts.entries.joinToString("  ·  ") { "${it.key.replace('_', ' ')} ${TableSort.n(it.value)}" }.ifBlank { "n/a" }, color = p.textSecondary, fontSize = 12.sp)
                Text("Cache: " + ui.cacheLine, color = p.textSecondary, fontSize = 12.sp)
            }
        }
        item {
            KitCard {
                Text("Export", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("All data, or the chosen dates. JSON keeps every field (and restores here); KMZ (zipped KML) opens in map apps: a placemark per stay, a line per move.",
                    color = p.textSecondary, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { d.onPickRange { f, t -> range = f to t } }, modifier = Modifier.testTag(ExploredTags.EXPORT_RANGE)) {
                        Text(range?.let { if (it.first == it.second) "${it.first}" else "${it.first} to ${it.second}" } ?: "All dates")
                    }
                    if (range != null) TextButton(onClick = { range = null }) { Text("All") }
                    OutlinedButton(onClick = { d.onExport(ExportFormat.JSON, rangeMs) }, modifier = Modifier.testTag(ExploredTags.EXPORT_JSON)) { Text("Export JSON") }
                    OutlinedButton(onClick = { d.onExport(ExportFormat.KMZ, rangeMs) }, modifier = Modifier.testTag(ExploredTags.EXPORT_KMZ)) { Text("Export KMZ") }
                }
                TextButton(onClick = d.onRestore, modifier = Modifier.testTag(ExploredTags.RESTORE)) { Text("Restore a Cloud Nav JSON export") }
            }
        }
        item {
            KitCard {
                Text("Delete duplicates", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("Copies left by repeated or overlapping imports: the same stay, move or position more than once. The richest copy is kept (then the phone's own, then the newest); nothing is removed before you confirm.",
                    color = p.textSecondary, fontSize = 12.sp)
                OutlinedButton(onClick = d.onDedupePreview, modifier = Modifier.testTag(ExploredTags.DEDUPE)) { Text("Find duplicates") }
            }
        }
        item {
            KitCard {
                Text("Delete by dates", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("Pick a date or a range; the preview lists what goes before anything is deleted. Rows across the edges are cut, not removed.", color = p.textSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { d.onPickRange { f, t -> range = f to t; val (x, y) = ExploredDelete.range(f, t, zone); d.onRangePreview(x, y) } },
                        modifier = Modifier.testTag(ExploredTags.DELETE_RANGE)) { Text("Pick dates and preview") }
                }
            }
        }
        item {
            KitCard {
                Text("Delete all", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("Every segment, position, step and sleep record, the imported history and the cache. Your corrections and home city stay. Asked twice.",
                    color = p.textSecondary, fontSize = 12.sp)
                OutlinedButton(onClick = { delAll = 1; typed = "" }, modifier = Modifier.testTag(ExploredTags.DELETE_ALL)) { Text("Delete all data", color = Color(0xFFEF4444)) }
            }
        }
        item {
            KitCard {
                Text("Refresh", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("Drop the cache and recompute every day and every aggregate from the stored data.", color = p.textSecondary, fontSize = 12.sp)
                OutlinedButton(onClick = d.onRefresh, modifier = Modifier.testTag(ExploredTags.REFRESH)) { Text("Refresh now") }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    })
    ui.dedupe?.let { plan -> DedupeDialog(plan, d) }
    ui.rangePreview?.let { (r, plan) ->
        AlertDialog(onDismissRequest = d.onRangeCancel, title = { Text("Delete " + (range?.let { "${it.first} to ${it.second}" } ?: "these dates") + "?") },
            text = { Column {
                Text("${plan.deleteIds.size} segments deleted, ${plan.updates.size} cut at the edges${if (plan.inserts.isNotEmpty()) " (${plan.inserts.size} split)" else ""}")
                Text("${plan.fixIds.size} positions, and the step, sleep and imported rows of the range")
                Text("Nothing outside the range changes.", color = p.textSecondary, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { d.onRangeConfirm(r.first, r.second) }, modifier = Modifier.testTag(ExploredTags.DELETE_RANGE_OK)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = d.onRangeCancel) { Text("Cancel") } })
    }
    if (delAll == 1) AlertDialog(onDismissRequest = { delAll = 0 }, title = { Text("Delete all location data?") },
        text = { Text("This removes every stored segment, position, step and sleep record and the imported history. Export first if you want a copy.") },
        confirmButton = { TextButton(onClick = { delAll = 2 }, modifier = Modifier.testTag(ExploredTags.DELETE_ALL_OK1)) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { delAll = 0 }) { Text("Cancel") } })
    if (delAll == 2) AlertDialog(onDismissRequest = { delAll = 0 }, title = { Text("Really delete everything?") },
        text = { Column {
            Text("Type DELETE to confirm. This cannot be undone.")
            OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("DELETE") })
        } },
        confirmButton = { TextButton(onClick = { delAll = 0; d.onDeleteAll() }, enabled = typed.trim() == "DELETE", modifier = Modifier.testTag(ExploredTags.DELETE_ALL_OK2)) { Text("Delete all", color = Color(0xFFEF4444)) } },
        dismissButton = { TextButton(onClick = { delAll = 0 }) { Text("Cancel") } })
}

/** The duplicates preview: totals, exact vs overlapping, per day and kind, and which copy each removal keeps. */
@Composable
private fun DedupeDialog(plan: DedupePlan, d: DataActions) {
    val p = LocalKitPalette.current
    AlertDialog(onDismissRequest = d.onDedupeCancel, title = { Text(if (plan.total == 0) "No duplicates" else "${plan.total} duplicates") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                item { Text("${plan.exact} exact, ${plan.overlapping} overlapping segments; ${plan.dropFixIds.size} repeated positions", fontSize = 13.sp) }
                item { Text("Per day and kind", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                items(plan.perDay.entries.toList().asReversed()) { (day, kinds) ->
                    Text("$day  " + kinds.entries.joinToString("  ") { "${it.key} ${it.value}" }, fontSize = 12.sp)
                }
                item { Text("Kept copy per removal", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                items(plan.findings.take(200)) { f ->
                    Text(ExploredDedupe.kindLabel(f.drop.seg) + " " + StepsFormat.time(f.drop.seg.startMs, ZoneId.systemDefault()) + "  drop " + f.drop.seg.source +
                        "  keep " + f.keep.seg.source + (f.keep.seg.place?.let { " ($it)" } ?: "") + if (f.exact) "  exact" else "  overlap", fontSize = 11.sp, color = p.textSecondary)
                }
            }
        },
        confirmButton = { if (plan.total > 0) TextButton(onClick = { d.onDedupeConfirm(plan) }, modifier = Modifier.testTag(ExploredTags.DEDUPE_OK)) { Text("Delete ${plan.total}") } },
        dismissButton = { TextButton(onClick = d.onDedupeCancel) { Text(if (plan.total == 0) "Close" else "Cancel") } })
}
