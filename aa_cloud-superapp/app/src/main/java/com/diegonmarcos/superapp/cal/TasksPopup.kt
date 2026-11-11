package com.diegonmarcos.superapp.cal

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitBubble
import com.diegonmarcos.superapp.ui.KitPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Locale

/**
 * The Tasks popup (was "Agenda | Calendar"): what the status bar's clock opens. Everything it is called, opens on and is
 * sized by is [TasksPopupDecl] (build.json::tasks_popup).
 *
 *  - Calendar view (the declared default): the previous, current and next month stacked, today a filled disc, a day with an
 *    event carrying a dot; below them the two tables.
 *  - Agenda view: the same two tables alone, in the larger table height.
 *  - Agenda table: upcoming dated items (date, time, title), from the cal engine's events; tapping a calendar day filters
 *    it to that day, tapping the day again clears the filter. Todo table: the open items of the engine's VTODO mirror.
 *
 * Mirrors the visual shape of BatteryEstimatePopup / NetworkInfoPopup / SystemInfoPopup so the strip popups read as one family.
 */
object TasksPopup {

    // Stopwatch state — kept in static fields so the timer keeps
    // running while the popup is closed. SystemClock.elapsedRealtime
    // is monotonic across sleeps so the elapsed value is accurate
    // even if the user dismisses the popup, locks the screen, and
    // re-opens it minutes later.
    // #1646 snapshot-backed so the launcher bar's readout recomposes the moment the dropdown starts/stops it (one shared state).
    private val stopwatchRunningState = mutableStateOf(false)
    private var stopwatchRunning: Boolean
        get() = stopwatchRunningState.value
        set(v) { stopwatchRunningState.value = v }
    @Volatile private var stopwatchStartElapsedMs: Long = 0L
    @Volatile private var stopwatchAccumulatedMs: Long = 0L

    private fun stopwatchCurrentMs(): Long =
        if (stopwatchRunning)
            stopwatchAccumulatedMs + (SystemClock.elapsedRealtime() - stopwatchStartElapsedMs)
        else
            stopwatchAccumulatedMs

    /** #1646 whether the SHARED stopwatch runs (observable from composition). */
    internal fun isStopwatchRunning(): Boolean = stopwatchRunning

    /** #1646 the SHARED stopwatch's elapsed time now, ms. */
    internal fun stopwatchElapsedMs(): Long = stopwatchCurrentMs()

    /** #1646 the bar readout: m:ss, or h:mm:ss from an hour on. */
    internal fun formatStopwatchBar(ms: Long): String {
        val t = ms / 1000L
        return if (t >= 3600L) "%d:%02d:%02d".format(t / 3600L, (t / 60L) % 60L, t % 60L) else "%d:%02d".format(t / 60L, t % 60L)
    }

    /** Under a Compose anchor whose window bounds are [rect], horizontally SCREEN-centered (the drop matches Battery/SysInfo). */
    fun show(host: View, rect: androidx.compose.ui.unit.IntRect) {
        TasksFeed.attach(host.context)
        KitPopup.show(host, rect, KitPopup.Align.CENTER) { TasksBubble() }
    }

    private val MONO = FontFamily.Monospace
    private val LILAC = Color(0xFFE9D8FD)
    private val DIM = Color(0x77FFFFFF)

    /** The bubble itself, split from [show] so it can be drawn without a window. [source] is where the tables are fed from. */
    @Composable
    internal fun TasksBubble(
        source: TasksSource = TasksFeed,
        today: LocalDate = LocalDate.now(),
        decl: TasksPopupDeclaration = TasksPopupDecl.declared,
    ) {
        val ctx = LocalContext.current
        var view by remember { mutableStateOf(decl.defaultView) }
        var picked by remember { mutableStateOf<LocalDate?>(null) }
        var events by remember { mutableStateOf<List<TaskEvent>>(emptyList()) }
        var todos by remember { mutableStateOf<List<TaskTodo>>(emptyList()) }
        var eventsError by remember { mutableStateOf<String?>(null) }
        var todosError by remember { mutableStateOf<String?>(null) }
        // The two reads are off the main thread (the engine bind blocks); each table says on its own why it is empty.
        LaunchedEffect(source, today) {
            val (from, to) = TasksModel.window(today, decl.monthsBefore, decl.monthsAfter)
            withContext(Dispatchers.IO) {
                runCatching { source.events(from, to) }.fold({ events = it; eventsError = null }, { eventsError = it.message ?: "events unavailable" })
                runCatching { source.todos() }.fold({ todos = it; todosError = null }, { todosError = it.message ?: "to-do items unavailable" })
            }
        }
        val title = stringResource(R.string.tasks_label)
        KitBubble(Modifier.width(decl.widthDp.dp).heightIn(max = decl.maxHeightDp.dp).testTag("cal:bubble"), scroll = rememberScrollState()) {
            // Headline row — left: label + today's date; right: the stopwatch.
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color(0xAAFFFFFF), fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.testTag("cal:title"))
                    Text(SimpleDateFormat("EEEE  d MMM yyyy", Locale.getDefault()).format(Calendar.getInstance().time),
                        color = Color.White, fontSize = 16.sp, fontFamily = MONO, fontWeight = FontWeight.Bold)
                }
                Stopwatch()
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                for ((id, caption) in decl.views) Tab(caption, view == id, Modifier.weight(1f)) { view = id }
            }
            Spacer(Modifier.height(8.dp))
            if (view == TasksPopupDecl.CALENDAR) {
                Months(decl, today, TasksModel.eventDays(events), picked) { d -> picked = if (picked == d) null else d }
                Spacer(Modifier.height(8.dp))
            }
            val h = if (view == TasksPopupDecl.CALENDAR) decl.tableHeightDp else decl.tableHeightAgendaViewDp
            AgendaTable(decl, events, eventsError, today, picked, h) { picked = null }
            Spacer(Modifier.height(8.dp))
            TodoTable(decl, todos, todosError, h) {
                if (decl.appOnRowTap.isNotBlank()) openApp(ctx, decl.appOnRowTap)
            }
        }
    }

    private fun openApp(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** One pill-less tab label: selected = lilac, bold, a faint lilac plate; unselected = dim. */
    @Composable
    private fun Tab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Text(text, fontSize = 13.sp, fontFamily = MONO, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) LILAC else Color(0x77FFFFFF),
            modifier = modifier.background(if (selected) Color(0x33E9D8FD) else Color(0x11FFFFFF))
                .clickable(onClick = onClick).padding(6.dp).testTag("cal:tab:$text"))
    }

    /** The previous, current and next month (the declared count), stacked; one weekday header on top. */
    @Composable
    private fun Months(decl: TasksPopupDeclaration, today: LocalDate, withEvents: Set<LocalDate>, picked: LocalDate?, onDay: (LocalDate) -> Unit) {
        val firstDow: DayOfWeek = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
        Column(Modifier.fillMaxWidth().testTag("cal:months")) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    Text(firstDow.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        color = Color(0x88FFFFFF), fontSize = 11.sp, fontFamily = MONO, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 2.dp))
                }
            }
            for (ym in TasksModel.months(today, decl.monthsBefore, decl.monthsAfter)) {
                val current = ym == java.time.YearMonth.from(today)
                Column(Modifier.fillMaxWidth().testTag("cal:month:$ym")) {
                    Text(ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                        color = if (current) LILAC else Color(0xAAFFFFFF), fontSize = 12.sp, fontFamily = MONO, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    for (week in TasksModel.grid(ym, firstDow).chunked(7)) {
                        Row(Modifier.fillMaxWidth()) {
                            for (day in week) Day(day, decl.monthCellDp, today, day in withEvents, day == picked && day != null, onDay, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Day(day: LocalDate?, cellDp: Int, today: LocalDate, hasEvent: Boolean, picked: Boolean, onDay: (LocalDate) -> Unit, modifier: Modifier) {
        if (day == null) { Spacer(modifier.height(cellDp.dp)); return }
        val isToday = day == today
        Box(modifier.height(cellDp.dp).padding(1.dp)
            .background(if (picked) Color(0x66E9D8FD) else Color.Transparent, CircleShape)
            .clickable { onDay(day) }.testTag("cal:day:$day"), contentAlignment = Alignment.Center) {
            Box(Modifier.let { if (isToday) it.background(LILAC, CircleShape).testTag("cal:today") else it }.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                Text(day.dayOfMonth.toString(), fontSize = 11.sp, fontFamily = MONO, textAlign = TextAlign.Center,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) Color(0xFF111111) else Color(0xDDFFFFFF))
            }
            if (hasEvent) Box(Modifier.align(Alignment.BottomCenter).size(3.dp).background(LILAC, CircleShape).testTag("cal:dot:$day"))
        }
    }

    /** Table frame: its caption, a column header, and a body of the declared height that scrolls. */
    @Composable
    private fun Table(tag: String, caption: String, columns: List<Pair<String, Float>>, heightDp: Int, trailing: @Composable () -> Unit = {}, body: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().background(Color(0x221A0033)).padding(6.dp).testTag("cal:table:$tag")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(caption, color = LILAC, fontSize = 13.sp, fontFamily = MONO, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                trailing()
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for ((c, w) in columns) Text(c, color = DIM, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.weight(w))
            }
            Column(Modifier.fillMaxWidth().height(heightDp.dp).verticalScroll(rememberScrollState())) { body() }
        }
    }

    @Composable
    private fun Note(text: String) {
        Text(text, color = DIM, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.padding(top = 4.dp))
    }

    /** Agenda table: date, time, title. */
    @Composable
    private fun AgendaTable(decl: TasksPopupDeclaration, events: List<TaskEvent>, error: String?, today: LocalDate, picked: LocalDate?, heightDp: Int, clear: () -> Unit) {
        val rows = TasksModel.agenda(events, today, picked, decl.agendaWindowDays, decl.agendaMaxRows)
        val dateFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
        Table("agenda", decl.agendaLabel, listOf("date" to 1.4f, "time" to 0.9f, "title" to 2.2f), heightDp,
            trailing = {
                if (picked != null) Text("${picked.format(dateFmt)}  ✕", color = LILAC, fontSize = 11.sp, fontFamily = MONO,
                    modifier = Modifier.clickable(onClick = clear).padding(2.dp).testTag("cal:filter"))
            }) {
            if (error != null) Note(error)
            else if (rows.isEmpty()) Note(if (picked != null) "no events that day" else "no upcoming events")
            rows.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("cal:agenda:row:$i")) {
                    Text(r.date.format(dateFmt), color = if (r.date == today) LILAC else Color(0xCCFFFFFF), fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1.4f))
                    Text(r.time, color = Color(0xCCFFFFFF), fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(0.9f))
                    Text(r.title, color = Color.White, fontSize = 11.sp, fontFamily = MONO, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2.2f))
                }
            }
        }
    }

    /** Todo table: a checkbox, the title, the due date. The checkbox completes the task only where the source can (declared). */
    @Composable
    private fun TodoTable(decl: TasksPopupDeclaration, todos: List<TaskTodo>, error: String?, heightDp: Int, onRow: () -> Unit) {
        val rows = TasksModel.openTodos(todos, decl.todoMaxRows)
        val dueFmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
        Table("todo", decl.todoLabel, listOf("" to 0.5f, "title" to 3f, "due" to 1.2f), heightDp) {
            if (error != null) Note(error)
            else if (rows.isEmpty()) Note("nothing to do")
            rows.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().clickable(onClick = onRow).testTag("cal:todo:row:$i"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(0.5f)) { Checkbox(checked = false, onCheckedChange = null, enabled = decl.todoComplete, modifier = Modifier.size(28.dp)) }
                    Text(t.summary, color = Color.White, fontSize = 11.sp, fontFamily = MONO, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(3f))
                    Text(t.dueMs?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dueFmt) } ?: "",
                        color = Color(0xCCFFFFFF), fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1.2f))
                }
            }
        }
    }

    /** Top-right stopwatch: elapsed m:ss.t (20 sp bold) and a 22 dp play/stop disc beside it. Wired to the module-level state
     *  above, so the timer keeps running while the popup is closed; long-press the time to reset. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun Stopwatch() {
        var ms by remember { mutableLongStateOf(stopwatchCurrentMs()) }
        var running by remember { mutableStateOf(stopwatchRunning) }
        // 100 ms tick while the bubble is composed; it ends with it.
        LaunchedEffect(Unit) { while (true) { ms = stopwatchCurrentMs(); delay(100L) } }
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatStopwatch(ms), color = LILAC, fontSize = 20.sp, fontFamily = MONO, fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                    // Long-press the time → reset to 0, so the user can start fresh without a third button.
                    stopwatchRunning = false
                    stopwatchAccumulatedMs = 0L
                    stopwatchStartElapsedMs = 0L
                    running = false; ms = 0L
                }).testTag("cal:stopwatch:time"))
            Box(Modifier.padding(start = 8.dp).size(22.dp).clip(CircleShape)
                .background(if (running) Color(0xFFB91C1C) else Color(0xFF16A34A))
                .clickable {
                    if (stopwatchRunning) {
                        stopwatchAccumulatedMs += SystemClock.elapsedRealtime() - stopwatchStartElapsedMs
                        stopwatchRunning = false
                    } else {
                        stopwatchStartElapsedMs = SystemClock.elapsedRealtime()
                        stopwatchRunning = true
                    }
                    running = stopwatchRunning
                    ms = stopwatchCurrentMs()
                }.testTag("cal:stopwatch:toggle"), contentAlignment = Alignment.Center) {
                Text(if (running) "■" else "▶", color = Color.White, fontSize = 10.sp)
            }
        }
    }

    internal fun formatStopwatch(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        val tenths = (ms % 1000L) / 100L
        return "%d:%02d.%d".format(m, s, tenths)
    }

    internal fun toggleStopwatchForTest() {
        if (stopwatchRunning) { stopwatchAccumulatedMs += SystemClock.elapsedRealtime() - stopwatchStartElapsedMs; stopwatchRunning = false }
        else { stopwatchStartElapsedMs = SystemClock.elapsedRealtime(); stopwatchRunning = true }
    }

    internal fun resetStopwatchForTest() { stopwatchRunning = false; stopwatchAccumulatedMs = 0L; stopwatchStartElapsedMs = 0L }
}
