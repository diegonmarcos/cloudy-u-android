package com.diegonmarcos.cloudagenda.data

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

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
}
