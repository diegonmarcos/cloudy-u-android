package com.diegonmarcos.cloudagenda.data

import java.time.LocalDate

/** The due-date groups of Tasks > List, in the order they are drawn. */
enum class DueBucket(val label: String) {
    OVERDUE("Overdue"), TODAY("Today"), WEEK("Next 7 days"), LATER("Later"), NONE("No date"), DONE("Done")
}

/** #1461 Tasks > List grouping: pure, no clock of its own. The due date is the deadline, else the target. */
object TaskGrouping {
    fun due(t: Task): LocalDate? = (t.dateDeadline ?: t.dateTarget)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun bucket(t: Task, today: LocalDate): DueBucket {
        if (t.status == TaskStatus.COMPLETED || t.status == TaskStatus.ARCHIVED) return DueBucket.DONE
        val d = due(t) ?: return DueBucket.NONE
        return when {
            d.isBefore(today) -> DueBucket.OVERDUE
            d == today -> DueBucket.TODAY
            !d.isAfter(today.plusDays(7)) -> DueBucket.WEEK
            else -> DueBucket.LATER
        }
    }

    /** The non-empty groups in drawing order; inside a group the earliest due first, undated keep their list order. */
    fun byDue(nodes: List<TaskNode>, today: LocalDate): List<Pair<DueBucket, List<TaskNode>>> =
        DueBucket.entries.mapNotNull { b ->
            val rows = nodes.filter { bucket(it.task, today) == b }.sortedBy { due(it.task) ?: LocalDate.MAX }
            if (rows.isEmpty()) null else b to rows
        }
}
