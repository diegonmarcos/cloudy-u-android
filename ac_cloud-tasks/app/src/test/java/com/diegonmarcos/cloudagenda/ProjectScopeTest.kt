package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.MockTasks
import com.diegonmarcos.cloudagenda.data.Project
import com.diegonmarcos.cloudagenda.data.ProjectConfigs
import com.diegonmarcos.cloudagenda.data.ProjectScopes
import com.diegonmarcos.cloudagenda.data.ProjectSelection
import com.diegonmarcos.cloudagenda.data.SampleProjects
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskList
import com.diegonmarcos.cloudagenda.data.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate

/** #1757 the Home selector: the project-filter merge, the status cards, and the per-project calendar config (auto mode, lane order). */
@RunWith(RobolectricTestRunner::class)
class ProjectScopeTest {
    private val items = MockTasks.parse(File("src/debug/assets/mock-tasks.json").readText())
    private val today = LocalDate.of(2026, 11, 4)
    private val configs = SampleProjects.parseConfigs(File("src/main/assets/projects.json").readText())
    private fun ids(l: List<com.diegonmarcos.cloudagenda.data.MockTask>) = l.map { it.id }

    // ── the filter merge ────────────────────────────────────────────────────────────────────────────────

    @Test fun `All keeps every row of every kind, unchanged`() {
        assertEquals(items, ProjectScopes.filter(items, ProjectSelection.ALL))
    }

    @Test fun `every non-task row names a project, so each tab can be viewed per project`() {
        val projects = setOf("Cloudy", "Cloud-Me", "Galaxy")
        for (k in listOf("inboxes", "backlogs", "library", "projects")) for (t in MockTasks.ofKind(items, k)) assertTrue("${t.id} has no project", t.projectName in projects)
        for (t in MockTasks.ofKind(items, "tasks")) assertEquals(t.subject, t.projectName)
    }

    @Test fun `one project alone shows only its rows on each tab`() {
        for (name in listOf("Cloudy", "Cloud-Me", "Galaxy")) {
            val kept = ProjectScopes.filter(items, ProjectSelection(false, setOf(name)))
            assertTrue(kept.isNotEmpty())
            assertTrue(kept.all { it.projectName == name })
            for (k in listOf("inboxes", "backlogs", "tasks", "library", "projects")) assertEquals(
                ids(items.filter { it.kind == k && it.projectName == name }), ids(MockTasks.ofKind(kept, k)),
            )
        }
    }

    @Test fun `several projects merge - the union of the separate views, in the fixture's own order`() {
        val a = ProjectScopes.filter(items, ProjectSelection(false, setOf("Cloudy")))
        val b = ProjectScopes.filter(items, ProjectSelection(false, setOf("Galaxy")))
        val both = ProjectScopes.filter(items, ProjectSelection(false, setOf("Cloudy", "Galaxy")))
        assertEquals((ids(a) + ids(b)).toSet(), ids(both).toSet())
        assertEquals(ids(items.filter { it.id in ids(both) }), ids(both))
        assertEquals(ids(a).size + ids(b).size, ids(both).size)
    }

    @Test fun `None keeps nothing and a row of no project is shown only by All`() {
        assertTrue(ProjectScopes.filter(items, ProjectSelection.NONE).isEmpty())
        assertTrue(ProjectSelection.NONE.none)
        assertTrue(ProjectSelection.ALL.includes(""))
        assertFalse(ProjectSelection(false, setOf("Cloudy")).includes(""))
    }

    @Test fun `the chips toggle - from All a tap drops that project, and covering everything again collapses back to All`() {
        val known = listOf("A", "B", "C")
        val minusB = ProjectSelection.ALL.toggle("B", known)
        assertEquals(ProjectSelection(false, setOf("A", "C")), minusB)
        assertEquals(ProjectSelection.ALL, minusB.toggle("B", known))
        assertEquals(ProjectSelection(false, setOf("A")), ProjectSelection.NONE.toggle("A", known))
        assertEquals(ProjectSelection.NONE, ProjectSelection(false, setOf("A")).toggle("A", known))
        assertEquals(listOf("A", "C"), minusB.resolve(known))
    }

    @Test fun `the selection survives a restart - encode and decode round-trip, odd names included`() {
        for (s in listOf(ProjectSelection.ALL, ProjectSelection.NONE, ProjectSelection(false, setOf("Traveling")), ProjectSelection(false, setOf("a,b", "C d", "e|f")))) {
            assertEquals(s, ProjectSelection.decode(s.encode()))
        }
        assertEquals(ProjectSelection.ALL, ProjectSelection.decode(null))
        assertEquals(ProjectSelection.ALL, ProjectSelection.decode("garbage"))
    }

    @Test fun `the database part of the filter keeps the selected projects with their lists and tasks`() {
        val snap = Snapshot(
            projects = listOf(Project("p1", "Home", 0), Project("p2", "Work", 1)),
            lists = listOf(TaskList("l1", "p1", "L1", 0), TaskList("l2", "p2", "L2", 0)),
            tasks = listOf(Task("t1", "l1", name = "a"), Task("t2", "l2", name = "b")),
        )
        assertEquals(snap, ProjectScopes.filter(snap, ProjectSelection.ALL))
        val only = ProjectScopes.filter(snap, ProjectSelection(false, setOf("Work")))
        assertEquals(listOf("Work"), only.projects.map { it.name })
        assertEquals(listOf("t2"), only.tasks.map { it.id })
        assertTrue(ProjectScopes.filter(snap, ProjectSelection.NONE).tasks.isEmpty())
    }

    // ── the status cards ────────────────────────────────────────────────────────────────────────────────

    @Test fun `a card counts open, in progress, done, overdue and the next due`() {
        val snap = Snapshot(
            projects = listOf(Project("p1", "Trip", 0, sample = true), Project("p2", "Empty", 1)),
            lists = listOf(TaskList("l1", "p1", "L", 0)),
            tasks = listOf(
                Task("a", "l1", name = "late", status = TaskStatus.ACTION, dateDeadline = "2026-11-01"),
                Task("b", "l1", name = "soon", status = TaskStatus.ACTION, dateDeadline = "2026-11-06"),
                Task("c", "l1", name = "later", status = TaskStatus.HOLD, dateTarget = "2026-11-09", dateDeadline = "2026-11-10"),
                Task("d", "l1", name = "doing", status = TaskStatus.WAITING, dateDeadline = "2026-11-20"),
                Task("e", "l1", name = "finished", status = TaskStatus.COMPLETED, dateDeadline = "2026-10-01"),
                Task("f", "l1", name = "undated", status = TaskStatus.ACTION),
                Task("g", "l1", parentId = "a", name = "sub of late"),
            ),
        )
        val rows = MockTasks.fromSnapshot(snap, today, includeUndated = true)
        val names = ProjectScopes.names(rows, snap)
        assertEquals(listOf("Trip", "Empty"), names)
        val cards = ProjectScopes.statuses(rows, snap, names, today).associateBy { it.name }
        val trip = cards.getValue("Trip")
        assertEquals(4, trip.open)         // late, soon, later (blocked), undated; subtasks are not counted
        assertEquals(1, trip.inProgress)
        assertEquals(1, trip.done)
        assertEquals(1, trip.overdue)
        assertEquals("soon", trip.nextDueTitle)
        assertEquals(LocalDate.of(2026, 11, 6), trip.nextDue)
        assertTrue(trip.sample)
        assertEquals("p1", trip.projectId)
        val empty = cards.getValue("Empty")
        assertEquals(listOf(0, 0, 0, 0), listOf(empty.open, empty.inProgress, empty.done, empty.overdue))
        assertNull(empty.nextDue)
        assertFalse(empty.sample)
        assertEquals(6, ProjectScopes.total(listOf(trip, empty)).total + 0 - 1 + 1 - 1 + 1 - 0 + 0 - 0 + 0 - 0 + 0 - 0 + 0 - 0 + 0 - 0 + 0 - 0 + 0)
    }

    @Test fun `the fixture projects get a card each, even when they have no dated task`() {
        val names = ProjectScopes.names(items, Snapshot())
        assertEquals(listOf("Cloud-Me", "Cloudy", "Galaxy"), names)
        val cards = ProjectScopes.statuses(items, Snapshot(), names, today)
        assertEquals(names, cards.map { it.name })
        assertEquals(MockTasks.ofKind(items, "tasks").count { it.parent == null }, cards.sumOf { it.total })
    }

    // ── the per-project calendar config ─────────────────────────────────────────────────────────────────

    @Test fun `Traveling switches the calendar to the Gantt Calendar by itself, All and other projects do not`() {
        val known = listOf("Project Zero", "Traveling", "Cloudy")
        assertEquals("gantt", configs.autoMode(ProjectSelection(false, setOf("Traveling")), known))
        assertEquals("gantt", configs.autoMode(ProjectSelection(false, setOf("Traveling", "Cloudy")), known))
        assertNull(configs.autoMode(ProjectSelection.ALL, known))
        assertNull(configs.autoMode(ProjectSelection(false, setOf("Project Zero")), known))
        assertNull(configs.autoMode(ProjectSelection.NONE, known))
        assertNull(ProjectConfigs.EMPTY.autoMode(ProjectSelection(false, setOf("Traveling")), known))
        // matched by name, case does not matter
        assertEquals("gantt", configs.autoMode(ProjectSelection(false, setOf("traveling")), listOf("traveling")))
    }

    @Test fun `the lane order of Traveling is data - Lodging, Transportation, Events - read from the project data`() {
        val known = listOf("Project Zero", "Traveling")
        assertEquals(listOf("Lodging", "Transportation", "Events"), configs.laneOrder(ProjectSelection(false, setOf("Traveling")), known))
        assertEquals(listOf("Lodging", "Transportation", "Events"), configs.laneOrder(ProjectSelection.ALL, known))
        assertTrue(configs.laneOrder(ProjectSelection(false, setOf("Project Zero")), known).isEmpty())
    }
}
