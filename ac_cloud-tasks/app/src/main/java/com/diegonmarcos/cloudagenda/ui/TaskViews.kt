package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import com.diegonmarcos.cloudagenda.data.BarEvent
import com.diegonmarcos.cloudagenda.data.CalendarBars
import com.diegonmarcos.cloudagenda.data.CalendarStyles
import com.diegonmarcos.cloudagenda.data.WeekBars
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.BuildConfig
import com.diegonmarcos.cloudagenda.data.CalendarMath
import com.diegonmarcos.cloudagenda.data.CalendarMode
import com.diegonmarcos.cloudagenda.data.MindNode
import com.diegonmarcos.cloudagenda.data.MockStatus
import com.diegonmarcos.cloudagenda.data.MockTask
import com.diegonmarcos.cloudagenda.data.MockTasks
import com.diegonmarcos.cloudagenda.data.Structure
import com.diegonmarcos.cloudagenda.data.TaskTree
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitFileTree
import com.diegonmarcos.superapp.uikit.KitFileTreeInk
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitFileTree
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

object MockTags {
    const val NOTICE = "mock_notice"
    const val DIAL = "mock_dial"
    fun page(id: String) = "mock_page_$id"
    fun expand(kind: String) = "mock_expand_$kind"
    fun collapse(kind: String) = "mock_collapse_$kind"
    fun tree(kind: String) = "tree:$kind"
    fun row(id: String) = "mock_row_$id"
    fun bar(id: String) = "mock_bar_$id"
    fun card(id: String) = "mock_card_$id"
    fun column(status: String) = "mock_column_$status"
    fun day(iso: String) = "mock_day_$iso"
    fun ring(subject: String) = "mock_ring_$subject"
    fun node(label: String) = "mock_node_$label"
    fun mode(id: String) = "mock_mode_$id"
    fun style(id: String) = "mock_style_$id"
    fun week(iso: String) = "mock_week_$iso"
    fun seg(id: String, weekIso: String) = "mock_seg_${id}_$weekIso"
    fun lane(name: String) = "mock_lane_$name"
    fun yearMonth(month: Int) = "mock_year_month_$month"
    fun yearDay(iso: String) = "mock_year_day_$iso"
    fun pick(month: Int) = "mock_pick_$month"
    const val BARS = "mock_bars"
    const val PEEK = "mock_bar_peek"
    const val PREV_YEAR = "mock_prev_year"
    const val NEXT_YEAR = "mock_next_year"
    const val PREV_MONTH = "mock_prev_month"
    const val NEXT_MONTH = "mock_next_month"
    const val TITLE = "mock_cal_title"
    const val PAGER = "mock_cal_pager"
    const val YEAR = "mock_year"
    const val PICK_PREV = "mock_pick_prev"
    const val PICK_NEXT = "mock_pick_next"
    const val PICK_YEAR = "mock_pick_year"
}

/** The Cloud Store's status ink, so a task reads like an app row in the Store: To do violet, Doing amber, Blocked red, Done green. */
fun statusInk(s: MockStatus): Color = when (s) {
    MockStatus.TODO -> KitStoreRow.MISSING
    MockStatus.DOING -> KitStoreRow.UPDATE
    MockStatus.BLOCKED -> KitStoreRow.BAD
    MockStatus.DONE -> KitStoreRow.UP
}

/** The ring colours of the round Calendar (one per subject), all from the Store's palette. */
private val RingInk = listOf(KitStoreRow.UP, KitStoreRow.UPDATE, KitStoreRow.MISSING, KitStoreRow.BLUE_FILL, KitStoreRow.AMBER_FILL, KitStoreRow.BAD)
private fun ringInk(i: Int): Color = RingInk[((i % RingInk.size) + RingInk.size) % RingInk.size]

/** The one line that says the data is fake; every mock page opens with it. */
@Composable
internal fun MockNotice() {
    val p = LocalKitPalette.current
    Text("Mock data: only the items are fake (assets/mock-tasks.json); the structure is Cloud Me's.", color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.testTag(MockTags.NOTICE))
}

private fun dateOf(ms: Long): String = if (ms <= 0L) "" else Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

// ── the collection pages (Inboxes, Backlogs, Library, Projects): Cloud Me's tree, in the Store's dense rows ─────────────

/**
 * One tab = Cloud Me's own tree for it (Structure), drawn with the shared KitFileTree: every node is a folder even when empty (it then
 * says "Nothing here yet"), the mock items hang under their node, Expand all / Collapse all on top.
 */
@Composable
fun CollectionPage(kind: String, structure: Structure, items: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val entries = remember(structure, items, kind, today) { TaskTree.entries(structure, items, kind, today) }
    val open = remember(kind) { mutableStateListOf<String>() }
    val rows = KitFileTree.rows(entries, open.toSet(), TaskTree.rootOrder(structure, kind), declaredOrder = true)
    val ink = KitFileTreeInk(p.textPrimary, p.textSecondary, p.accent)
    Column(modifier.fillMaxSize().testTag(MockTags.page(kind))) {
        Box(Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) { MockNotice() }
        if (entries.isEmpty()) {
            KitEmptyState("Nothing declared", "Cloud Me's declaration for this tab did not load.")
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.tight)) {
                KitTextButton({ open.clear(); open.addAll(KitFileTree.containers(entries)) }, Modifier.testTag(MockTags.expand(kind))) { Text("Expand all", color = p.accent) }
                KitTextButton({ open.clear() }, Modifier.testTag(MockTags.collapse(kind))) { Text("Collapse all", color = p.accent) }
            }
            LazyColumn(Modifier.fillMaxSize().testTag(MockTags.tree(kind))) {
                kitFileTree(
                    rows, ink, size = { "items" }, date = ::dateOf, tagPrefix = MockTags.tree(kind),
                    onToggle = { k -> if (k in open) open.remove(k) else open.add(k) }, onOpen = {},
                )
            }
        }
    }
}

internal fun dayLabel(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** One task as a Store line: status glyph, name, subject, status. */
@Composable
private fun TaskLine(t: MockTask, today: LocalDate) {
    val p = LocalKitPalette.current
    KitStoreRowLine(Modifier.testTag(MockTags.row(t.id))) {
        KitStoreDot("●", statusInk(t.status))
        KitStoreName(t.title, Modifier.weight(1f), p.textPrimary)
        KitStoreCaption(t.subject, color = p.textSecondary, modifier = Modifier.widthIn(max = 90.dp).padding(start = KitStoreRow.gap))
        t.start(today)?.let { KitStoreCaption(dayLabel(it), color = p.textSecondary, modifier = Modifier.padding(start = KitStoreRow.gap)) }
    }
}

// ── Tasks > Calendar: the round one ───────────────────────────────────────────────────────────────────────

/** The arcs around a day cell, as the legacy page drew them (tasks.html .arc-1..4: dash length and offset out of a 100.5 circumference). */
private val CellArcs = listOf(0f to 20f, 30f to 25f, 65f to 15f, 75f to 22f)

/**
 * The Calendar page: a Month | Year toggle (the modes are build.json::ui.calendar_views), a year selector (prev / next year, a
 * month / year picker on the title) and, in Month view, a horizontal pager (swipe left / right to change month, [CalendarMath.MIN_YEAR]
 * to [CalendarMath.MAX_YEAR] reachable). Year view is the whole year on one screen: 12 mini months, 3 columns x 4 rows; a tap opens
 * that month in Month view. Month pages are the round calendar restored from the legacy Cloud Agenda page (assets/tasks.html): a
 * month grid whose days carry a coloured arc per project and the dial under it. [items] is the fixture plus the real tasks.
 */
@Composable
fun CalendarView(items: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier, projects: ProjectScope? = null, known: List<String> = emptyList()) {
    val p = LocalKitPalette.current
    val tasks = MockTasks.ofKind(items, "tasks")
    val rings = remember(tasks) { MockTasks.rings(tasks) }
    val modes = remember { CalendarMath.parseModes(BuildConfig.UI_CALENDAR_VIEWS).ifEmpty { listOf(CalendarMode(CalendarMath.MODE_MONTH, "Month"), CalendarMode(CalendarMath.MODE_YEAR, "Year")) } }
    var mode by rememberSaveable { mutableStateOf(CalendarMath.MODE_MONTH) }
    // #1757 the style of the month: Circles Day (default) | Linear Bars | Gantt Calendar. A project config (projects.json: Traveling) switches it
    // by itself when that project becomes the selection; the user's own pick stands until the selection changes again.
    val auto = projects?.configs?.autoMode(projects.selection, known)
    var style by rememberSaveable { mutableStateOf(CalendarStyles.of(auto)) }
    LaunchedEffect(projects?.selection) { if (auto != null) { style = CalendarStyles.of(auto); mode = CalendarMath.MODE_MONTH } }
    val laneConfig = projects?.configs?.laneOrder(projects.selection, known).orEmpty()
    val events = remember(tasks, today) { CalendarBars.events(tasks, today) }
    val allLanes = remember(events, laneConfig) { CalendarBars.laneOrder(events.map { it.lane }, laneConfig) }
    var year by rememberSaveable { mutableStateOf(today.year) }
    var picker by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    val pager = rememberPagerState(CalendarMath.pageOf(YearMonth.from(today))) { CalendarMath.PAGES }
    val scope = rememberCoroutineScope()
    val shown = CalendarMath.monthOf(pager.currentPage)
    val inYear = mode == CalendarMath.MODE_YEAR
    fun go(m: YearMonth) { scope.launch { pager.animateScrollToPage(CalendarMath.pageOf(m)) } }
    fun open(m: YearMonth) { mode = CalendarMath.MODE_MONTH; scope.launch { pager.scrollToPage(CalendarMath.pageOf(m)) } }
    fun byYear(n: Int) { if (inYear) year = CalendarMath.shiftYear(YearMonth.of(year, 1), n).year else go(CalendarMath.shiftYear(shown, n)) }
    Column(modifier.fillMaxSize().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV).testTag(MockTags.page("calendar"))) {
        MockNotice()
        Row(Modifier.fillMaxWidth().padding(vertical = KitStoreRow.tight), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap), verticalAlignment = Alignment.CenterVertically) {
            for (m in modes) {
                val on = m.id == mode
                Text(
                    m.label, color = if (on) p.tileInk else p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(KitDensity.small)).background(if (on) p.accent else p.surface)
                        .clickable { if (m.id == CalendarMath.MODE_YEAR) year = shown.year; mode = m.id }
                        .padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(MockTags.mode(m.id)),
                )
            }
        }
        if (!inYear) Row(Modifier.fillMaxWidth().padding(bottom = KitStoreRow.tight), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap), verticalAlignment = Alignment.CenterVertically) {
            for (m in CalendarStyles.ALL) {
                val on = m.id == style
                Text(
                    m.label, color = if (on) p.tileInk else p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(KitDensity.small)).background(if (on) p.accent else p.surface).border(1.dp, if (on) p.accent else p.hairline, RoundedCornerShape(KitDensity.small))
                        .clickable { style = m.id }.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(MockTags.style(m.id)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KitTextButton({ byYear(-1) }, Modifier.testTag(MockTags.PREV_YEAR)) { Text("«", color = p.accent) }
            if (!inYear) KitTextButton({ go(CalendarMath.shiftMonth(shown, -1)) }, Modifier.testTag(MockTags.PREV_MONTH)) { Text("‹", color = p.accent) }
            Text(
                if (inYear) year.toString() else shown.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + shown.year,
                color = p.textPrimary, fontSize = KitDensity.head, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clickable { picker = true }.testTag(MockTags.TITLE),
            )
            if (!inYear) KitTextButton({ go(CalendarMath.shiftMonth(shown, 1)) }, Modifier.testTag(MockTags.NEXT_MONTH)) { Text("›", color = p.accent) }
            KitTextButton({ byYear(1) }, Modifier.testTag(MockTags.NEXT_YEAR)) { Text("»", color = p.accent) }
        }
        if (inYear) {
            YearView(year, tasks, rings, today, ::open, { byYear(it) }, Modifier.weight(1f).fillMaxWidth())
        } else {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag(MockTags.PAGER)) { page ->
                if (style == CalendarStyles.CIRCLES) MonthPage(CalendarMath.monthOf(page), tasks, rings, today, selected) { selected = it }
                else BarsMonthPage(CalendarMath.monthOf(page), events, allLanes, style == CalendarStyles.GANTT, tasks, today, selected) { selected = it }
            }
        }
    }
    if (picker) MonthYearPicker(if (inYear) year else shown.year, onDismiss = { picker = false }) { m -> picker = false; year = m.year; open(m) }
}

/** The month / year picker: year with prev / next, then the twelve months of that year; a pick opens that month in Month view. */
@Composable
private fun MonthYearPicker(startYear: Int, onDismiss: () -> Unit, onPick: (YearMonth) -> Unit) {
    val p = LocalKitPalette.current
    var y by remember { mutableStateOf(startYear) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { KitTextButton(onDismiss) { Text("Close", color = p.accent) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitTextButton({ y = CalendarMath.shiftYear(YearMonth.of(y, 1), -1).year }, Modifier.testTag(MockTags.PICK_PREV)) { Text("‹", color = p.accent) }
                Text(y.toString(), color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag(MockTags.PICK_YEAR))
                KitTextButton({ y = CalendarMath.shiftYear(YearMonth.of(y, 1), 1).year }, Modifier.testTag(MockTags.PICK_NEXT)) { Text("›", color = p.accent) }
            }
        },
        text = {
            Column {
                for (row in CalendarMath.yearGrid(y)) Row(Modifier.fillMaxWidth()) {
                    for (m in row) KitTextButton({ onPick(m) }, Modifier.weight(1f).testTag(MockTags.pick(m.monthValue))) {
                        Text(m.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = p.textPrimary)
                    }
                }
            }
        },
    )
}

/** The whole year on one screen, no scrolling: [CalendarMath.yearGrid] months, each a mini grid marked in the project colours. */
@Composable
private fun YearView(year: Int, tasks: List<MockTask>, rings: List<String>, today: LocalDate, onOpen: (YearMonth) -> Unit, onYear: (Int) -> Unit, modifier: Modifier = Modifier) {
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    // #1757 swipe left / up = next year, right / down = previous year (CalendarMath.swipeYear); a tap on a month still opens it.
    Column(modifier.testTag(MockTags.YEAR).pointerInput(Unit) {
        var dx = 0f
        var dy = 0f
        detectDragGestures(
            onDragStart = { dx = 0f; dy = 0f },
            onDrag = { change, d -> change.consume(); dx += d.x; dy += d.y },
            onDragEnd = { val n = CalendarMath.swipeYear(dx, dy, threshold); if (n != 0) onYear(n) },
        )
    }) {
        for (row in CalendarMath.yearGrid(year)) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (m in row) MiniMonth(m, tasks, rings, today, Modifier.weight(1f).fillMaxHeight().padding(KitStoreRow.tight).clip(RoundedCornerShape(KitDensity.small)).clickable { onOpen(m) }.testTag(MockTags.yearMonth(m.monthValue)))
        }
    }
}

@Composable
private fun MiniMonth(m: YearMonth, tasks: List<MockTask>, rings: List<String>, today: LocalDate, modifier: Modifier) {
    val p = LocalKitPalette.current
    val marks = remember(m, tasks, today) { CalendarMath.markers(m, tasks, today, rings, CellArcs.size) }
    Column(modifier) {
        Text(m.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = if (m == YearMonth.from(today)) p.accent else p.textPrimary, fontSize = KitStoreRow.meta, fontWeight = FontWeight.Bold)
        for (week in CalendarMath.miniMonth(m)) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (d in week) {
                val subjects = d?.let { marks[it] }.orEmpty()
                Box(
                    Modifier.weight(1f).fillMaxHeight().let { b -> if (d == null) b else b.testTag(MockTags.yearDay(d.toString())) }.drawBehind {
                        val dia = size.minDimension
                        val tl = Offset((size.width - dia) / 2f, (size.height - dia) / 2f)
                        subjects.forEachIndexed { n, ring ->
                            val (off, len) = CellArcs[n]
                            drawArc(ringInk(ring), off / 100.5f * 360f - 90f, len / 100.5f * 360f, false, tl, Size(dia, dia), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (d != null) Text(d.dayOfMonth.toString(), color = p.textPrimary, fontSize = KitStoreRow.caption, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** One month of the pager: the day grid, the dial, the project rings and the selected day's tasks. */
@Composable
private fun MonthPage(month: YearMonth, tasks: List<MockTask>, rings: List<String>, today: LocalDate, selected: String, onSelect: (String) -> Unit) {
    val p = LocalKitPalette.current
    val cells = MockTasks.monthGrid(month)
    val arcs = remember(tasks, today, month) { MockTasks.dialArcs(tasks, today, month) }
    val day = LocalDate.parse(selected)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) {
            for (d in java.time.DayOfWeek.values()) Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        for (week in cells.chunked(7)) {
            Row(Modifier.fillMaxWidth().padding(top = KitStoreRow.tight)) {
                for (i in 0 until 7) {
                    val d = week.getOrNull(i)
                    val subjects = d?.let { MockTasks.on(tasks, today, it).map { t -> rings.indexOf(t.subject) }.distinct().take(CellArcs.size) }.orEmpty()
                    Box(
                        Modifier.weight(1f).height(38.dp).let { m ->
                            if (d == null) m else m.testTag(MockTags.day(d.toString())).clip(RoundedCornerShape(KitDensity.small))
                                .background(if (d == day) p.surfaceSelected else Color.Transparent).clickable { onSelect(d.toString()) }
                        }.drawBehind {
                            val dia = size.minDimension * 0.82f
                            val tl = Offset((size.width - dia) / 2f, (size.height - dia) / 2f)
                            subjects.forEachIndexed { n, ring ->
                                val (off, len) = CellArcs[n]
                                drawArc(ringInk(ring), off / 100.5f * 360f - 90f, len / 100.5f * 360f, false, tl, Size(dia, dia), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (d != null) Text(d.dayOfMonth.toString(), color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }

        // The dial: one ring per subject, a task = an arc over its days, the red dot is today.
        Box(Modifier.fillMaxWidth().padding(vertical = KitStoreRow.padH), contentAlignment = Alignment.Center) {
            val ringBase = p.hairline
            val today12 = (today.dayOfMonth - 1) * 360f / month.lengthOfMonth()
            Canvas(Modifier.size(220.dp).testTag(MockTags.DIAL)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val outer = size.minDimension / 2f - 8.dp.toPx()
                val step = 9.dp.toPx()
                drawCircle(ringBase, outer + 6.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                rings.indices.forEach { r ->
                    val rad = outer - r * step
                    if (rad >= 24.dp.toPx()) drawCircle(ringBase.copy(alpha = 0.35f), rad, c, style = Stroke(1.dp.toPx()))
                }
                for (a in arcs) {
                    val rad = outer - a.ring * step
                    if (rad < 24.dp.toPx()) continue
                    drawArc(ringInk(a.ring).copy(alpha = 0.85f), a.startDeg - 90f, a.sweepDeg, false, Offset(c.x - rad, c.y - rad), Size(rad * 2, rad * 2), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                }
                val t = Math.toRadians((today12 - 90f + 180f / month.lengthOfMonth()).toDouble())
                drawCircle(KitStoreRow.BAD, 4.5.dp.toPx(), Offset(c.x + (outer + 6.dp.toPx()) * Math.cos(t).toFloat(), c.y + (outer + 6.dp.toPx()) * Math.sin(t).toFloat()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = p.textPrimary, fontSize = KitDensity.head, fontWeight = FontWeight.Bold)
                Text("${arcs.size} tasks", color = p.textSecondary, fontSize = KitStoreRow.caption)
            }
        }
        if (rings.isEmpty()) Text("No dated task this month.", color = p.textSecondary, fontSize = KitStoreRow.body, modifier = Modifier.padding(bottom = KitStoreRow.gap))
        rings.forEachIndexed { i, subject ->
            KitStoreRowLine(Modifier.testTag(MockTags.ring(subject))) {
                Box(Modifier.padding(end = KitStoreRow.gap).width(12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ringInk(i)))
                KitStoreName(subject.ifBlank { "No project" }, Modifier.weight(1f), p.textPrimary)
                KitStoreCaption(arcs.count { it.ring == i }.toString(), color = p.textSecondary)
            }
        }

        Text(dayLabel(day), color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = KitStoreRow.padH, bottom = KitStoreRow.tight))
        val onDay = MockTasks.on(tasks, today, day)
        if (onDay.isEmpty()) Text("Nothing is due on this day.", color = p.textSecondary, fontSize = KitStoreRow.body) else onDay.forEach { TaskLine(it, today) }
    }
}

// ── Tasks > Calendar: Linear Bars and Gantt Calendar ──────────────────────────────────────────────────────

private val LaneLabelWidth = 62.dp
private val BarHead = 18.dp
private val BarRow = 17.dp

/**
 * #1757 Linear Bars (and, with [gantt], the Gantt Calendar): the month as week rows of big day boxes. A multi-day event is ONE continuous
 * rectangle across its days that wraps onto the next week row (CalendarBars.week cuts it per week; a square end says it goes on). The Gantt
 * Calendar is the same grid plus a left column of category names where every category is its own lane (the lane order is data-driven:
 * CalendarBars.laneOrder over the project config). The selected day's tasks list under the grid, as in the circles view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarsMonthPage(month: YearMonth, events: List<BarEvent>, allLanes: List<String>, gantt: Boolean, tasks: List<MockTask>, today: LocalDate, selected: String, onSelect: (String) -> Unit) {
    val p = LocalKitPalette.current
    val inMonth = remember(events, month) { CalendarBars.inMonth(events, month) }
    val lanes = remember(inMonth, allLanes, gantt) { allLanes.filter { l -> inMonth.any { it.lane == l } } }
    val weeks = remember(month, inMonth, lanes, gantt) { CalendarBars.month(month, inMonth, if (gantt) lanes else null) }
    val day = LocalDate.parse(selected)
    // a tap or long-press on a bar shows its whole title, lane and dates here (a bar too short for its title keeps an ellipsis)
    var peek by remember { mutableStateOf<BarEvent?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(MockTags.BARS)) {
        Row(Modifier.fillMaxWidth()) {
            if (gantt) Spacer(Modifier.width(LaneLabelWidth))
            for (d in java.time.DayOfWeek.values()) Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        for (w in weeks) BarWeek(w, gantt, allLanes, today, day, onSelect) { peek = it }
        peek?.let { e ->
            Text(
                e.title + "  ·  " + e.lane + "  ·  " + dayLabel(e.start) + (if (e.end != e.start) " to " + dayLabel(e.end) else ""),
                color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(top = KitStoreRow.gap).clip(RoundedCornerShape(KitDensity.small)).background(p.surface).padding(KitStoreRow.gap).testTag(MockTags.PEEK),
            )
        }
        if (inMonth.isEmpty()) Text("No dated task this month.", color = p.textSecondary, fontSize = KitStoreRow.body, modifier = Modifier.padding(vertical = KitStoreRow.gap))
        else if (!gantt) FlowRow(Modifier.fillMaxWidth().padding(top = KitStoreRow.gap), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.padH), verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            for (l in lanes) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = KitStoreRow.tight).width(12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ringInk(allLanes.indexOf(l))))
                Text(l, color = p.textSecondary, fontSize = KitStoreRow.caption)
            }
        }
        Text(dayLabel(day), color = p.textPrimary, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = KitStoreRow.padH, bottom = KitStoreRow.tight))
        val onDay = MockTasks.on(tasks, today, day)
        if (onDay.isEmpty()) Text("Nothing is due on this day.", color = p.textSecondary, fontSize = KitStoreRow.body) else onDay.forEach { TaskLine(it, today) }
    }
}

/** One week row: seven big day boxes, the lane labels of the Gantt Calendar on the left, and the bar pieces laid over the boxes. */
@Composable
private fun BarWeek(w: WeekBars, gantt: Boolean, allLanes: List<String>, today: LocalDate, selected: LocalDate, onSelect: (String) -> Unit, onPeek: (BarEvent) -> Unit) {
    val p = LocalKitPalette.current
    // a week with no event collapses to a compact strip (no tall empty lanes)
    val empty = w.segments.isEmpty()
    val height = if (empty) 44.dp else maxOf(76.dp, BarHead + BarRow * w.rows + 6.dp)
    Row(Modifier.fillMaxWidth().height(height).testTag(MockTags.week(w.weekStart.toString()))) {
        if (gantt) Column(Modifier.width(LaneLabelWidth).fillMaxHeight()) {
            Spacer(Modifier.height(BarHead))
            if (!empty) for ((lane, n) in w.laneRows) Box(Modifier.fillMaxWidth().height(BarRow * n), contentAlignment = Alignment.CenterStart) {
                Text(lane, color = ringInk(allLanes.indexOf(lane)), fontSize = KitStoreRow.caption, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp).testTag(MockTags.lane(lane)))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val col = maxWidth / 7
            Row(Modifier.fillMaxSize()) {
                for (d in w.days) {
                    val box = Modifier.weight(1f).fillMaxHeight().padding(1.dp)
                    if (d == null) Spacer(box) else Box(
                        box.testTag(MockTags.day(d.toString())).clip(RoundedCornerShape(KitDensity.small)).background(if (d == selected) p.surfaceSelected else p.surface.copy(alpha = 0.5f))
                            .border(1.dp, if (d == today) p.accent else p.hairline.copy(alpha = 0.6f), RoundedCornerShape(KitDensity.small)).clickable { onSelect(d.toString()) },
                    ) {
                        Text(d.dayOfMonth.toString(), color = if (d == today) p.accent else p.textPrimary, fontSize = KitStoreRow.body, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(start = 3.dp, top = 1.dp))
                    }
                }
            }
            // Gantt: a hairline between the lanes
            if (gantt && !empty) Box(Modifier.fillMaxSize().drawBehind {
                var y = BarHead.toPx()
                for ((_, n) in w.laneRows) { y += BarRow.toPx() * n; drawLine(p.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
            })
            for (seg in w.segments) {
                val r = 5.dp
                val shape = RoundedCornerShape(
                    topStart = if (seg.fromPrev) 0.dp else r, bottomStart = if (seg.fromPrev) 0.dp else r,
                    topEnd = if (seg.toNext) 0.dp else r, bottomEnd = if (seg.toNext) 0.dp else r,
                )
                Box(
                    Modifier.offset(col * seg.col0 + 1.dp, BarHead + BarRow * seg.row).width(col * (seg.col1 - seg.col0 + 1) - 2.dp).height(BarRow - 2.dp)
                        .clip(shape).background(ringInk(allLanes.indexOf(seg.event.lane)).copy(alpha = if (seg.event.status == MockStatus.DONE) 0.5f else 0.92f))
                        .pointerInput(seg.event.id) { detectTapGestures(onTap = { onPeek(seg.event) }, onLongPress = { onPeek(seg.event) }) }
                        .testTag(MockTags.seg(seg.event.id, w.weekStart.toString())),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // the title sits on the first piece of a bar only, ellipsised at a glyph boundary; the rest of a wrapped bar stays plain colour
                    if (!seg.fromPrev) Text(seg.event.title, color = p.tileInk, fontSize = KitStoreRow.caption, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
        }
    }
}

// ── Tasks > Gantt ────────────────────────────────────────────────────────────────────────────────────────

/** One bar per dated task on a shared day axis: the label above, the bar placed and sized by the task's start and length. */
@Composable
fun GanttView(items: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val (rows, span) = remember(items) { MockTasks.gantt(MockTasks.ofKind(items, "tasks")) }
    val total = (span.last - span.first + 1).coerceAtLeast(1)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).testTag(MockTags.page("gantt"))) {
        MockNotice()
        Text("${dayLabel(today.plusDays(span.first.toLong()))}  to  ${dayLabel(today.plusDays(span.last.toLong()))}", color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.padding(top = 4.dp))
        for (t in rows) {
            val offset = (t.startDay!! - span.first).toFloat()
            val len = t.days.coerceAtLeast(1).toFloat()
            val rest = (total - offset - len).coerceAtLeast(0f)
            Text(t.title, color = p.textPrimary, fontSize = KitStoreRow.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().height(18.dp).padding(top = 4.dp)) {
                if (offset > 0f) Spacer(Modifier.weight(offset))
                Box(Modifier.weight(len).fillMaxSize().clip(RoundedCornerShape(5.dp)).background(statusInk(t.status).copy(alpha = 0.85f)).testTag(MockTags.bar(t.id)))
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
        }
    }
}

// ── Tasks > Kanban ───────────────────────────────────────────────────────────────────────────────────────

/** One column per status, side by side and scrolled sideways; a card per task. */
@Composable
fun KanbanView(items: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val columns = MockTasks.byStatus(MockTasks.ofKind(items, "tasks"))
    Column(modifier.fillMaxSize().padding(top = 12.dp).testTag(MockTags.page("kanban"))) {
        Box(Modifier.padding(horizontal = 16.dp)) { MockNotice() }
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((status, rows) in columns) {
                Column(Modifier.width(220.dp).fillMaxSize().clip(RoundedCornerShape(12.dp)).background(p.surface.copy(alpha = 0.5f)).padding(8.dp).testTag(MockTags.column(status.id))) {
                    Text("${status.label} (${rows.size})", color = statusInk(status), fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        rows.forEach { t ->
                            val shape = RoundedCornerShape(10.dp)
                            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(shape).background(p.surface).border(1.dp, p.hairline, shape).padding(10.dp).testTag(MockTags.card(t.id))) {
                                Text(t.title, color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold)
                                Text(t.subject, color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.padding(top = 2.dp))
                                t.start(today)?.let { Text(dayLabel(it), color = p.textSecondary, fontSize = KitStoreRow.caption) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tasks > Mindmap ──────────────────────────────────────────────────────────────────────────────────────

/** The tasks as a tree growing to the right: root, subjects, tasks, subtasks; scrolls both ways. */
@Composable
fun MindmapView(items: List<MockTask>, modifier: Modifier = Modifier) {
    val root = remember(items) { MockTasks.mindmap(MockTasks.ofKind(items, "tasks")) }
    Column(modifier.fillMaxSize().padding(top = 12.dp).testTag(MockTags.page("mindmap"))) {
        Box(Modifier.padding(horizontal = 16.dp)) { MockNotice() }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) { MindBranch(root, 0) }
    }
}

@Composable
private fun MindBranch(node: MindNode, depth: Int) {
    val p = LocalKitPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(10.dp)
        Text(
            node.label, color = if (depth == 0) p.tileInk else p.textPrimary, fontSize = KitStoreRow.body, fontWeight = if (depth < 2) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.widthIn(max = 150.dp).clip(shape).background(if (depth == 0) p.accent else p.surface).border(1.dp, p.hairline, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp).testTag(MockTags.node(node.label)),
        )
        if (node.children.isNotEmpty()) {
            Box(Modifier.width(14.dp).height(1.dp).background(p.hairline))
            Column(
                Modifier.drawBehind { drawLine(p.hairline, Offset(0f, 14.dp.toPx()), Offset(0f, size.height - 14.dp.toPx()), 1.dp.toPx()) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (c in node.children) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(1.dp).background(p.hairline))
                    MindBranch(c, depth + 1)
                }
            }
        }
    }
}
