package com.diegonmarcos.cloudagenda.data

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** The status of a mock row (the Kanban columns, in order). */
enum class MockStatus(val id: String, val label: String) {
    TODO("todo", "To do"),
    DOING("doing", "Doing"),
    BLOCKED("blocked", "Blocked"),
    DONE("done", "Done");

    companion object {
        fun of(id: String?): MockStatus = entries.firstOrNull { it.id == id } ?: TODO
    }
}

/**
 * One row of the mock fixture (assets/mock-tasks.json, FAKE data). [kind] is the section it belongs to (inboxes,
 * backlogs, tasks, library, projects); [startDay] is an offset from today (null = undated), so the views always render.
 */
data class MockTask(
    val id: String,
    val kind: String,
    val title: String,
    val subject: String,
    val status: MockStatus,
    val startDay: Int?,
    val days: Int,
    val progress: Int,
    val parent: String?,
    val detail: String,
) {
    fun start(today: LocalDate): LocalDate? = startDay?.let { today.plusDays(it.toLong()) }
    fun end(today: LocalDate): LocalDate? = start(today)?.plusDays((days.coerceAtLeast(1) - 1).toLong())
}

/** One arc of the round Calendar's month dial: [ring] is the subject's ring (0 = outermost), degrees run clockwise from 12 o'clock. */
data class DialArc(val ring: Int, val task: MockTask, val startDeg: Float, val sweepDeg: Float)

/** A node of the Mindmap: a label and its branches. */
data class MindNode(val label: String, val children: List<MindNode> = emptyList())

/** The pure half of the mock views: parsing the fixture and arranging it for each view. No Android views, no clock of its own. */
object MockTasks {
    const val ASSET = "mock-tasks.json"
    const val ROOT_LABEL = "Tasks (mock)"

    fun parse(json: String): List<MockTask> {
        val arr = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            MockTask(
                id = o.getString("id"), kind = o.optString("kind"), title = o.optString("title"), subject = o.optString("subject"),
                status = MockStatus.of(o.optString("status")), startDay = if (o.has("startDay")) o.optInt("startDay") else null,
                days = o.optInt("days", 1), progress = o.optInt("progress", 0), parent = o.optString("parent").ifBlank { null },
                detail = o.optString("detail"),
            )
        }
    }

    fun load(context: Context): List<MockTask> =
        runCatching { parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

    fun ofKind(items: List<MockTask>, kind: String): List<MockTask> = items.filter { it.kind == kind }

    /** A collection page: rows grouped by subject (name order), each group in start order, undated last. */
    fun bySubject(items: List<MockTask>): List<Pair<String, List<MockTask>>> =
        items.groupBy { it.subject }.toList().sortedBy { it.first.lowercase() }
            .map { (s, rows) -> s to rows.sortedWith(compareBy({ it.startDay == null }, { it.startDay }, { it.title })) }

    /** Kanban: every status column, in order, even when empty. */
    fun byStatus(items: List<MockTask>): List<Pair<MockStatus, List<MockTask>>> =
        MockStatus.entries.map { s -> s to items.filter { it.status == s } }

    /** Calendar: the days of [month], Monday first, null = padding before the 1st. */
    fun monthGrid(month: YearMonth): List<LocalDate?> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    }

    /** Calendar: the rows that cover [day]. */
    fun on(items: List<MockTask>, today: LocalDate, day: LocalDate): List<MockTask> =
        items.filter { t -> val s = t.start(today); val e = t.end(today); s != null && e != null && !day.isBefore(s) && !day.isAfter(e) }

    /** Gantt: the dated rows in start order and the day offsets the chart spans (first start .. last end, inclusive). */
    fun gantt(items: List<MockTask>): Pair<List<MockTask>, IntRange> {
        val dated = items.filter { it.startDay != null }.sortedWith(compareBy({ it.startDay }, { it.title }))
        if (dated.isEmpty()) return dated to 0..0
        return dated to (dated.minOf { it.startDay!! }..dated.maxOf { it.startDay!! + it.days.coerceAtLeast(1) - 1 })
    }

    /** Mindmap: root > subject > task > subtask, built from `parent`. */
    fun mindmap(items: List<MockTask>): MindNode {
        fun node(t: MockTask): MindNode = MindNode(t.title, items.filter { it.parent == t.id }.map(::node))
        val roots = items.filter { it.parent == null || items.none { p -> p.id == it.parent } }
        return MindNode(ROOT_LABEL, roots.groupBy { it.subject }.toList().sortedBy { it.first }.map { (s, rows) -> MindNode(s, rows.map(::node)) })
    }

    /** The real tasks of the database as dated rows, so the Calendar draws them next to the fixture: dated by target (else deadline) to deadline. */
    fun fromSnapshot(snap: Snapshot, today: LocalDate): List<MockTask> = snap.tasks.mapNotNull { t ->
        val start = (t.dateTarget ?: t.dateDeadline)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        val end = t.dateDeadline?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isBefore(start) } ?: start
        MockTask(
            id = "real_" + t.id, kind = "tasks", title = t.name, subject = snap.project(snap.list(t.listId)?.projectId)?.name ?: "",
            status = when (t.status) {
                TaskStatus.ACTION -> MockStatus.TODO
                TaskStatus.WAITING -> MockStatus.DOING
                TaskStatus.HOLD -> MockStatus.BLOCKED
                TaskStatus.COMPLETED, TaskStatus.ARCHIVED -> MockStatus.DONE
            },
            startDay = ChronoUnit.DAYS.between(today, start).toInt(), days = (ChronoUnit.DAYS.between(start, end) + 1).toInt(),
            progress = 0, parent = t.parentId?.let { "real_$it" }, detail = t.details,
        )
    }

    /** The round Calendar's rings: one per subject that has a dated row, in start order (the earliest subject takes the outermost ring). */
    fun rings(items: List<MockTask>): List<String> =
        items.filter { it.startDay != null }.sortedBy { it.startDay }.map { it.subject }.distinct()

    /** The month dial: a task is an arc over the days of [month] it covers (clipped to the month), on its subject's ring. */
    fun dialArcs(items: List<MockTask>, today: LocalDate, month: YearMonth): List<DialArc> {
        val rings = rings(items)
        val n = month.lengthOfMonth()
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        return items.mapNotNull { t ->
            val s = t.start(today) ?: return@mapNotNull null
            val e = t.end(today) ?: return@mapNotNull null
            val a = if (s.isBefore(first)) first else s
            val b = if (e.isAfter(last)) last else e
            if (a.isAfter(b)) null
            else DialArc(rings.indexOf(t.subject), t, (a.dayOfMonth - 1) * 360f / n, (b.dayOfMonth - a.dayOfMonth + 1) * 360f / n)
        }
    }

    /** Backlogs and Projects: the registry's projects (all of them, empty or not) with the fixture rows hanging on each, by project name. */
    fun rowsOf(items: List<MockTask>, kind: String, node: String): List<MockTask> =
        items.filter { it.kind == kind && it.subject == node }.sortedWith(compareBy({ it.startDay == null }, { it.startDay }, { it.title }))
}
