package com.diegonmarcos.cloudme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The three views of the Tasks page. */
enum class TasksView(val label: String) { DAY("Day"), SUBJECT("Subject"), CALENDAR("Calendar") }

object TasksTags {
    fun view(v: TasksView) = "me:tasks:view:${v.name.lowercase()}"
    const val EMPTY = "me:tasks:empty"
    fun row(title: String) = "me:tasks:row:$title"
    const val MONTH = "me:tasks:month"
}

/**
 * Profile > Tasks (ticket #1150): the former Agenda and Todo pages as one, over the same cal engine data
 * ([CalEngineClient]): a Day view (one day's events and tasks, with Overdue on today), a Subject view (open tasks
 * per subject = the engine's collection) and a Calendar month (days with something are marked; a tap picks the day).
 * With no engine or no account every view says it is empty; nothing is invented.
 */
@Composable
fun TasksScreen(modifier: Modifier = Modifier, subject: String = "", openTarget: String = "") {
    val ctx = LocalContext.current
    val engine = remember { CalEngineClient(ctx) }
    var items by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                check(engine.check() is CalEngineClient.Check.Ready) { ctx.getString(R.string.cal_engine_missing_title) }
                TasksModel.items(engine.events(now - 31L * DAY_MS, now + 62L * DAY_MS), engine.todos())
            }
        }
        items = loaded.getOrDefault(emptyList())
        notice = loaded.exceptionOrNull()?.message
    }
    TasksContent(TasksModel.forSubject(items, subject), notice, modifier, openTarget = openTarget)
}

@Composable
fun TasksContent(items: List<TaskItem>, notice: String?, modifier: Modifier = Modifier, today: Long = System.currentTimeMillis(), openTarget: String = "") {
    val onTarget = LocalMeTarget.current
    var view by rememberSaveable { mutableStateOf(TasksView.DAY) }
    var day by remember { mutableLongStateOf(TasksModel.startOfDay(today)) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (v in TasksView.entries) {
                val on = v == view
                Text(v.label, Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(colorResource(if (on) R.color.me_surface_high else R.color.me_surface))
                    .clickable { view = v }.padding(vertical = 8.dp).testTag(TasksTags.view(v)),
                    color = colorResource(if (on) R.color.me_primary else R.color.me_text_dim), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        // Cloud Me reads the engine; writing an event or a task is Cloud Tasks' job (it owns the CalDAV account).
        if (openTarget.isNotBlank()) Text(stringResource(R.string.tasks_open_external, ExternalApps.labelFor(openTarget)), Modifier.padding(top = 10.dp).clickable { onTarget(openTarget) }.padding(vertical = 6.dp),
            color = colorResource(R.color.me_primary), fontSize = 13.sp)
        if (notice != null) Dim(notice)
        when (view) {
            TasksView.DAY -> DayView(items, day, today) { day = it }
            TasksView.SUBJECT -> SubjectView(items)
            TasksView.CALENDAR -> CalendarView(items, day, today) { day = it }
        }
    }
}

@Composable
private fun DayView(items: List<TaskItem>, day: Long, today: Long, onDay: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("<", Modifier.clickable { onDay(TasksModel.addDays(day, -1)) }.padding(8.dp), color = colorResource(R.color.me_primary), fontSize = 18.sp)
        Text(DAY_FMT.format(Date(day)), Modifier.weight(1f), color = colorResource(R.color.me_text), fontSize = 16.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(">", Modifier.clickable { onDay(TasksModel.addDays(day, 1)) }.padding(8.dp), color = colorResource(R.color.me_primary), fontSize = 18.sp)
    }
    Rows(TasksModel.day(items, day, today), "Nothing on this day")
}

@Composable
private fun SubjectView(items: List<TaskItem>) {
    val groups = TasksModel.bySubject(items)
    if (groups.isEmpty()) Empty("No open tasks")
    for ((subject, rows) in groups) {
        Text(subject, Modifier.padding(top = 16.dp, bottom = 2.dp), color = colorResource(R.color.me_text), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        rows.forEach { ItemRow(it) }
    }
}

@Composable
private fun CalendarView(items: List<TaskItem>, day: Long, today: Long, onDay: (Long) -> Unit) {
    var month by remember { mutableLongStateOf(day) }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("<", Modifier.clickable { month = TasksModel.addMonths(month, -1) }.padding(8.dp), color = colorResource(R.color.me_primary), fontSize = 18.sp)
        Text(MONTH_FMT.format(Date(month)), Modifier.weight(1f), color = colorResource(R.color.me_text), fontSize = 16.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(">", Modifier.clickable { month = TasksModel.addMonths(month, 1) }.padding(8.dp), color = colorResource(R.color.me_primary), fontSize = 18.sp)
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(TasksTags.MONTH)) {
        TasksModel.month(items, month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val cell = week.getOrNull(i)
                    val start = cell?.first
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(CircleShape)
                        .background(if (start != null && start == day) colorResource(R.color.me_surface_high) else androidx.compose.ui.graphics.Color.Transparent)
                        .then(if (start != null) Modifier.clickable { onDay(start) } else Modifier),
                        contentAlignment = Alignment.Center) {
                        if (start != null) {
                            Text(DAYNUM_FMT.format(Date(start)), color = colorResource(if (start == TasksModel.startOfDay(today)) R.color.me_primary else R.color.me_text), fontSize = 13.sp)
                            if (cell?.second == true) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).clip(CircleShape).background(colorResource(R.color.me_primary)))
                        }
                    }
                }
            }
        }
    }
    Text(DAY_FMT.format(Date(day)), Modifier.padding(top = 14.dp), color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Rows(TasksModel.day(items, day, today), "Nothing on this day")
}

@Composable
private fun Rows(rows: List<TaskItem>, empty: String) {
    if (rows.isEmpty()) Empty(empty) else rows.forEach { ItemRow(it) }
}

@Composable
private fun ItemRow(i: TaskItem) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(colorResource(R.color.me_surface))
        .border(1.dp, colorResource(R.color.me_outline), shape).padding(horizontal = 14.dp, vertical = 12.dp).testTag(TasksTags.row(i.title))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i.title, Modifier.weight(1f), color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val trailing = when { i.isTask -> "task"; i.allDay -> "all day"; else -> TIME_FMT.format(Date(i.whenMs)) }
            Text(trailing, color = colorResource(R.color.me_text_dim), fontSize = 12.sp)
        }
        if (i.detail.isNotBlank()) Text(i.detail, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        if (i.percent > 0) Text("${i.percent}% done", color = colorResource(R.color.me_primary), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Empty(text: String) = Text(text, Modifier.padding(top = 14.dp).testTag(TasksTags.EMPTY), color = colorResource(R.color.me_text_dim), fontSize = 13.sp)

@Composable
private fun Dim(text: String) = Text(text, Modifier.padding(top = 10.dp), color = colorResource(R.color.me_text_dim), fontSize = 13.sp)

private const val DAY_MS = 24L * 60L * 60L * 1000L
private val DAY_FMT = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val MONTH_FMT = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
private val DAYNUM_FMT = SimpleDateFormat("d", Locale.getDefault())
private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
