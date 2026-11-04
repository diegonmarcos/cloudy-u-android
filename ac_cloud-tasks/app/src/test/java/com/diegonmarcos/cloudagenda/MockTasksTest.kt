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
    private val raw = File("src/main/assets/mock-tasks.json").readText()
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
        val alpha = root.children.first { it.label == "Mock Project Alpha" }
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

    @Test fun `a collection groups by subject in name order`() {
        val g = MockTasks.bySubject(MockTasks.ofKind(items, "inboxes"))
        assertEquals(g.map { it.first }.sorted(), g.map { it.first })
    }
}
