package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudagenda.data.MindNode
import com.diegonmarcos.cloudagenda.data.MockStatus
import com.diegonmarcos.cloudagenda.data.MockTask
import com.diegonmarcos.cloudagenda.data.MockTasks
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

object MockTags {
    const val NOTICE = "mock_notice"
    fun page(id: String) = "mock_page_$id"
    fun row(id: String) = "mock_row_$id"
    fun card(id: String) = "mock_card_$id"
    fun bar(id: String) = "mock_bar_$id"
    fun column(status: String) = "mock_column_$status"
    fun day(iso: String) = "mock_day_$iso"
    fun node(label: String) = "mock_node_$label"
}

private fun statusColor(p: KitPalette, s: MockStatus): Color = when (s) {
    MockStatus.TODO -> p.textSecondary
    MockStatus.DOING -> p.accent
    MockStatus.BLOCKED -> p.bad
    MockStatus.DONE -> p.ok
}

/** The one line that says the data is fake; every mock page opens with it. */
@Composable
private fun MockNotice() {
    val p = LocalKitPalette.current
    Text("Mock data: every row is fake (assets/mock-tasks.json).", color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.testTag(MockTags.NOTICE))
}

// ── the collection pages (Inboxes, Backlogs, Library, Projects): the Cloud Me Tasks row layout ───────────

/** Heading per subject and a card per row (Cloud Me's AgendaScreen layout: accent dot, bold title, date at the right, detail, progress). */
@Composable
fun CollectionPage(kind: String, items: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val groups = MockTasks.bySubject(MockTasks.ofKind(items, kind))
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp).testTag(MockTags.page(kind))) {
        MockNotice()
        if (groups.isEmpty()) KitEmptyState("Nothing here", "The mock fixture has no rows for this page.")
        for ((subject, rows) in groups) {
            Heading(subject)
            rows.forEach { TaskRowCard(it, today) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    val p = LocalKitPalette.current
    Text(text, color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

@Composable
fun TaskRowCard(t: MockTask, today: LocalDate) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(14.dp)
    val accent = statusColor(p, t.status)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(p.surface).border(1.dp, p.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp).testTag(MockTags.row(t.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 10.dp).size(10.dp).clip(CircleShape).background(accent))
            Text(t.title, color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            t.start(today)?.let { Text(dayLabel(it), color = p.textSecondary, fontSize = 12.sp) }
        }
        if (t.detail.isNotBlank()) {
            Text(t.detail, color = p.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        }
        if (t.progress in 1..99) Text("${t.progress}% done", color = accent, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    }
}

private fun dayLabel(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

// ── Tasks > Calendar ─────────────────────────────────────────────────────────────────────────────────────

/** A month grid of the current month; a day with tasks shows their count, and the tasks of every day are listed under the grid. */
@Composable
fun CalendarView(items: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val tasks = MockTasks.ofKind(items, "tasks")
    val month = YearMonth.from(today)
    val cells = MockTasks.monthGrid(month)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp).testTag(MockTags.page("calendar"))) {
        MockNotice()
        Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year, color = p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth()) {
            for (d in java.time.DayOfWeek.values()) Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        for (week in cells.chunked(7)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    val n = day?.let { MockTasks.on(tasks, today, it).size } ?: 0
                    val shape = RoundedCornerShape(8.dp)
                    Column(
                        Modifier.weight(1f).padding(2.dp).height(46.dp).clip(shape)
                            .background(if (day == today) p.surfaceSelected else if (day != null) p.surface else Color.Transparent)
                            .let { if (day != null) it.testTag(MockTags.day(day.toString())) else it },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        if (day != null) {
                            Text(day.dayOfMonth.toString(), color = p.textPrimary, fontSize = 13.sp, fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal)
                            if (n > 0) Box(Modifier.padding(top = 2.dp).clip(CircleShape).background(p.accent).padding(horizontal = 6.dp)) { Text(n.toString(), color = p.tileInk, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
        val dated = cells.filterNotNull().filter { MockTasks.on(tasks, today, it).isNotEmpty() }
        for (day in dated) {
            Heading(dayLabel(day))
            MockTasks.on(tasks, today, day).forEach { TaskRowCard(it, today) }
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
        Text("${dayLabel(today.plusDays(span.first.toLong()))}  to  ${dayLabel(today.plusDays(span.last.toLong()))}", color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        for (t in rows) {
            val offset = (t.startDay!! - span.first).toFloat()
            val len = t.days.coerceAtLeast(1).toFloat()
            val rest = (total - offset - len).coerceAtLeast(0f)
            Text(t.title, color = p.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().height(18.dp).padding(top = 4.dp)) {
                if (offset > 0f) Spacer(Modifier.weight(offset))
                Box(Modifier.weight(len).fillMaxSize().clip(RoundedCornerShape(5.dp)).background(statusColor(p, t.status).copy(alpha = 0.85f)).testTag(MockTags.bar(t.id)))
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
                    Text("${status.label} (${rows.size})", color = statusColor(p, status), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        rows.forEach { t ->
                            val shape = RoundedCornerShape(10.dp)
                            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(shape).background(p.surface).border(1.dp, p.hairline, shape).padding(10.dp).testTag(MockTags.card(t.id))) {
                                Text(t.title, color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(t.subject, color = p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                t.start(today)?.let { Text(dayLabel(it), color = p.textSecondary, fontSize = 11.sp) }
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
            node.label, color = if (depth == 0) p.tileInk else p.textPrimary, fontSize = 13.sp, fontWeight = if (depth < 2) FontWeight.Bold else FontWeight.Normal,
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
