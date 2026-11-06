package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.MockTasks
import com.diegonmarcos.cloudagenda.data.Structure
import com.diegonmarcos.cloudagenda.data.TaskTree
import com.diegonmarcos.superapp.uikit.KitFileTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate

/**
 * #1461 the tabs hang on Cloud Me's own structure (its vault-rows.json, repos.outline.json and build.json::ui.sections[tasks]), read
 * from those files, never retyped. The owner's exact node names are asserted here; the mock items may only sit inside that structure.
 */
@RunWith(RobolectricTestRunner::class)
class StructureTest {
    private val me = File("../../ac_cloud-me")
    private val structure = Structure.parse(
        File(me, "data/repos/vault-rows.json").readText(), File(me, "data/repos/repos.outline.json").readText(), File(me, "build.json").readText(),
    )
    private val today = LocalDate.of(2026, 11, 4)

    private fun top(kind: String) = structure.tree(kind).map { it.label }
    private fun preorder(kind: String) = structure.paths(kind).map { it.last() }

    @Test fun `Inboxes lists Cloud Me's inboxes, all of them`() {
        assertEquals(listOf("Mental-Models", "Core-Theses", "Raw-Captures", "Reading-Clippings"), top("inboxes").take(4))
        assertEquals(11, top("inboxes").size)
    }

    @Test fun `Backlogs is Due, the backlog folders, then RoadMaps with Tree and its folders`() {
        assertEquals(listOf("Due", "Project-Proposals", "Content-Ideas", "Learning-Queue"), top("backlogs").take(4))
        val names = preorder("backlogs")
        var at = -1
        for (n in listOf("Due", "Project-Proposals", "Content-Ideas", "Learning-Queue", "RoadMaps", "Tree", "Backlog-General", "U Cloudy", "U Cloud-me")) {
            val i = names.drop(at + 1).indexOf(n)
            assertTrue("$n is missing or out of order in $names", i >= 0)
            at += i + 1
        }
        val roadmaps = structure.tree("backlogs").first { it.label == "RoadMaps" }
        assertEquals(listOf("Tree", "Backlog-General"), roadmaps.children.map { it.label }.take(2))
    }

    @Test fun `Library lists the vault's Library folders with their sub-folders`() {
        assertEquals(listOf("Research-Dossiers", "Code-Snippets", "Templates-Patterns"), top("library").take(3))
        assertTrue(structure.paths("library").any { it == listOf("Wisdom", "Spirituality") })
    }

    @Test fun `Projects lists the registry areas Cloud Me shows, with their projects and families`() {
        assertEquals(listOf("Unix", "CG", "Capital", "HardTech", "42", "Projects"), top("projects"))
        assertTrue(structure.paths("projects").any { it == listOf("Unix", "Cloudy", "Android") })
    }

    @Test fun `every mock item sits on a real node, and no node is invented`() {
        val items = MockTasks.parse(File("src/debug/assets/mock-tasks.json").readText())
        for (kind in listOf("inboxes", "backlogs", "library", "projects")) {
            val keys = structure.paths(kind).map(TaskTree::key).toSet()
            for (t in items.filter { it.kind == kind }) assertTrue("${t.id} hangs on '${t.subject}', which is not a $kind node", t.subject in keys)
        }
    }

    @Test fun `every node is a folder even with no items, and says so`() {
        val items = MockTasks.parse(File("src/debug/assets/mock-tasks.json").readText())
        val entries = TaskTree.entries(structure, items, "inboxes", today)
        for (inbox in top("inboxes")) assertTrue("$inbox is not in the tree", entries.any { it.dirs.first() == inbox })
        val empty = entries.filter { it.type == KitFileTree.EMPTY_TYPE }
        assertTrue(empty.isNotEmpty())
        assertTrue(empty.all { it.name == TaskTree.EMPTY_NAME })
        val rows = KitFileTree.rows(entries, emptySet(), TaskTree.rootOrder(structure, "inboxes"), declaredOrder = true)
        assertEquals(top("inboxes"), rows.map { (it as KitFileTree.Row.Dir).name })
        assertEquals(0, (rows.first { (it as KitFileTree.Row.Dir).name == "Core-Theses" } as KitFileTree.Row.Dir).files)
    }
}
