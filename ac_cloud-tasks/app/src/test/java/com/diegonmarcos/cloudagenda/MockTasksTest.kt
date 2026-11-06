package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.MockStatus
import com.diegonmarcos.cloudagenda.data.MockTasks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** The mock fixture (assets/mock-tasks.json) and the pure arrangement of it for each view. */
@RunWith(RobolectricTestRunner::class)
class MockTasksTest {
    private val raw = File("src/debug/assets/mock-tasks.json").readText()
    private val items = MockTasks.parse(raw)
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun `the fixture says it is fake and every kind has rows`() {
        assertTrue(JSONObject(raw).getString("_fake").startsWith("MOCK DATA - FAKE"))
        for (k in listOf("inboxes", "backlogs", "tasks", "library", "projects")) assertTrue("no rows for $k", MockTasks.ofKind(items, k).isNotEmpty())
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    @Test fun `the tasks fill every kanban column and the gantt spans their dates`() {
        val tasks = MockTasks.ofKind(items, "tasks")
        assertEquals(MockStatus.entries, MockTasks.byStatus(tasks).map { it.first })
        assertTrue(MockTasks.byStatus(tasks).all { it.second.isNotEmpty() })
        val (rows, span) = MockTasks.gantt(tasks)
        assertEquals(tasks.size, rows.size)
        assertTrue(span.first < 0 && span.last > 0)
    }

    @Test fun `the mindmap nests subtasks under their task under their subject`() {
        val root = MockTasks.mindmap(MockTasks.ofKind(items, "tasks"))
        assertEquals(MockTasks.ROOT_LABEL, root.label)
        val alpha = root.children.first { it.label == "Cloudy" }
        assertEquals(2, alpha.children.first { it.label == "Ship the fake build" }.children.size)
    }

    @Test fun `the calendar grid starts on Monday and a multi-day task covers its days`() {
        val grid = MockTasks.monthGrid(YearMonth.of(2026, 11))
        assertEquals(6, grid.count { it == null })
        assertEquals(30, grid.count { it != null })
        val review = items.first { it.id == "t2" }
        assertEquals(today.minusDays(2), review.start(today))
        assertTrue(MockTasks.on(items, today, today).any { it.id == "t2" })
        assertTrue(MockTasks.on(items, today, today.plusDays(10)).none { it.id == "t2" })
        assertNull(items.first { it.id == "t6" }.parent)
    }

    @Test fun `the dial puts a task on its subject's ring over the days it covers, clipped to the month`() {
        val tasks = MockTasks.ofKind(items, "tasks")
        val month = YearMonth.of(2026, 11)
        val arcs = MockTasks.dialArcs(tasks, today, month)
        val rings = MockTasks.rings(tasks)
        assertTrue(arcs.all { it.ring >= 0 && it.ring < rings.size })
        val review = arcs.first { it.task.id == "t2" }                      // Nov 2 .. Nov 5 of 30 days
        assertEquals(1 * 360f / 30, review.startDeg, 0.01f)
        assertEquals(4 * 360f / 30, review.sweepDeg, 0.01f)
        assertEquals(rings.indexOf("Cloudy"), review.ring)
        assertTrue(arcs.all { it.startDeg >= 0f && it.startDeg + it.sweepDeg <= 360.01f })
    }

    @Test fun `real tasks join the calendar dated by target to deadline, in their project`() {
        val t = com.diegonmarcos.cloudagenda.data.Task("a", "l1", name = "Pay rent", dateTarget = "2026-11-06", dateDeadline = "2026-11-08")
        val undated = com.diegonmarcos.cloudagenda.data.Task("b", "l1", name = "Someday")
        val snap = com.diegonmarcos.cloudagenda.data.Snapshot(
            projects = listOf(com.diegonmarcos.cloudagenda.data.Project("p1", "Home", 0)),
            lists = listOf(com.diegonmarcos.cloudagenda.data.TaskList("l1", "p1", "Bills", 0)), tasks = listOf(t, undated),
        )
        val rows = MockTasks.fromSnapshot(snap, today)
        assertEquals(1, rows.size)
        assertEquals("Home", rows[0].subject)
        assertEquals(2, rows[0].startDay)
        assertEquals(3, rows[0].days)
        assertTrue(MockTasks.on(rows, today, today.plusDays(4)).isNotEmpty())
    }

    @Test fun `a collection groups by subject in name order`() {
        val g = MockTasks.bySubject(MockTasks.ofKind(items, "inboxes"))
        assertEquals(g.map { it.first }.sorted(), g.map { it.first })
    }
}
