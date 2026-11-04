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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
private fun MockNotice() {
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

private fun dayLabel(d: LocalDate): String =
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
 * The round calendar (restored from the legacy Cloud Agenda page, assets/tasks.html): a month grid whose days carry a coloured arc
 * per subject, and the dial under it, one concentric ring per subject with every task an arc over the days it spans (the legacy
 * Today Graph's clockArcPath rings, mapped from hours of the day to days of the month because a task has dates, not times).
 * Tap a day to list its tasks. [items] is the fixture plus the real tasks (MockTasks.fromSnapshot).
 */
@Composable
fun CalendarView(items: List<MockTask>, today: LocalDate, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val tasks = MockTasks.ofKind(items, "tasks")
    val month = YearMonth.from(today)
    val cells = MockTasks.monthGrid(month)
    val rings = remember(tasks) { MockTasks.rings(tasks) }
    val arcs = remember(tasks, today) { MockTasks.dialArcs(tasks, today, month) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    val day = LocalDate.parse(selected)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV).testTag(MockTags.page("calendar"))) {
        MockNotice()
        Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year, color = p.textPrimary, fontSize = KitDensity.head, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = KitStoreRow.gap))
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
                                .background(if (d == day) p.surfaceSelected else Color.Transparent).clickable { selected = d.toString() }
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
