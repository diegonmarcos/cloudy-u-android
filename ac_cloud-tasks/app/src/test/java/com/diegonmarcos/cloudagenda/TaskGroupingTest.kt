package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.DueBucket
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskGrouping
import com.diegonmarcos.cloudagenda.data.TaskNode
import com.diegonmarcos.cloudagenda.data.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Tasks > List groups by due date (the deadline, else the target). */
class TaskGroupingTest {
    private val today = LocalDate.of(2026, 11, 4)
    private fun node(id: String, deadline: String? = null, target: String? = null, status: TaskStatus = TaskStatus.ACTION) =
        TaskNode(Task(id, "l", name = id, dateDeadline = deadline, dateTarget = target, status = status), emptyList())

    @Test fun `tasks fall in overdue, today, next 7 days, later, no date and done, in that order`() {
        val nodes = listOf(
            node("none"), node("later", "2026-12-01"), node("week", "2026-11-10"), node("today", "2026-11-04"), node("late", "2026-11-01"),
            node("fin", "2026-11-01", status = TaskStatus.COMPLETED), node("tgt", target = "2026-11-05"),
        )
        val g = TaskGrouping.byDue(nodes, today)
        assertEquals(listOf(DueBucket.OVERDUE, DueBucket.TODAY, DueBucket.WEEK, DueBucket.LATER, DueBucket.NONE, DueBucket.DONE), g.map { it.first })
        assertEquals(listOf("tgt", "week"), g.first { it.first == DueBucket.WEEK }.second.map { it.task.id })
    }

    @Test fun `an empty list has no groups`() {
        assertEquals(emptyList<Any>(), TaskGrouping.byDue(emptyList(), today))
    }
}
