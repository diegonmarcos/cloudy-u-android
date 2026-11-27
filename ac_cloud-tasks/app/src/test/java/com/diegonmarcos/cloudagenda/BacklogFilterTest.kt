package com.diegonmarcos.cloudagenda

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.cloudagenda.data.Backlog
import com.diegonmarcos.cloudagenda.data.BacklogSource
import com.diegonmarcos.cloudagenda.data.ProjectSelection
import com.diegonmarcos.cloudagenda.ui.Declarations
import com.diegonmarcos.cloudagenda.ui.FilesTags
import com.diegonmarcos.cloudagenda.ui.HomeTags
import com.diegonmarcos.cloudagenda.ui.TasksApp
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** #1974 the Home selection filters the Backlog on the project identity the tickets carry (metadata.scope), and the Files pages read the same data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BacklogFilterTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules = composeChain(compose)

    private val snap = Backlog.parseFixture(File("src/debug/assets/backlog-fixture.json").readText())

    @Test fun `a ticket carries its project identity from metadata scope`() {
        val t = Backlog.parse("""{"id":"1974","subject":"#1974 x","status":"in_progress","metadata":{"scope":{"area":"1","project":"1.1","family":"1.1.1","repos":["cloudy-u-android"],"names":{"project":"Cloudy","family":"Android"}}}}""")!!
        assertEquals("Cloudy", t.project)
        assertEquals(setOf("Cloudy", "1.1", "Android", "1.1.1", "cloudy-u-android"), t.keys)
        assertTrue(t.inSelection(ProjectSelection(false, setOf("Cloudy"))))
        assertTrue(t.inSelection(ProjectSelection(false, setOf("cloudy-u-android"))))
        assertFalse(t.inSelection(ProjectSelection(false, setOf("Galaxy"))))
    }

    @Test fun `the filter keeps the selected projects and an empty selection means all`() {
        assertTrue(snap.tickets.map { it.project }.toSet().containsAll(setOf("Cloudy", "Cloud-Me", "Galaxy")))
        val cloudy = Backlog.filter(snap.tickets, ProjectSelection(false, setOf("Cloudy")))
        assertTrue(cloudy.isNotEmpty() && cloudy.all { it.project == "Cloudy" } && cloudy.size < snap.tickets.size)
        val two = Backlog.filter(snap.tickets, ProjectSelection(false, setOf("Cloudy", "Galaxy")))
        assertEquals(setOf("Cloudy", "Galaxy"), two.map { it.project }.toSet())
        assertEquals(snap.tickets, Backlog.filter(snap.tickets, ProjectSelection.ALL))
        assertEquals(emptyList<Any>(), Backlog.filter(snap.tickets, ProjectSelection.NONE))
        // the persisted form of an empty selection decodes to All
        assertEquals(ProjectSelection.ALL, ProjectSelection.decode(null))
    }

    @Test fun `the RoadMap groups the selected tickets by project`() {
        val all = Backlog.roadmap(snap.tickets)
        assertEquals(setOf("Cloudy", "Cloud-Me", "Galaxy"), all.map { it.name }.toSet())
        assertEquals(snap.tickets.size, all.sumOf { it.total })
        val one = Backlog.roadmap(Backlog.filter(snap.tickets, ProjectSelection(false, setOf("Galaxy"))))
        assertEquals(listOf("Galaxy"), one.map { it.name })
    }

    @Test fun `the assets tree is built from the repo-relative paths`() {
        assertTrue(snap.assets.any { it.dirs == listOf("1842") && it.name == "spec.md" })
        assertTrue(snap.assets.any { it.dirs.isEmpty() && it.name == "README.md" })
    }

    private fun env() = FakeEnv(RuntimeEnvironment.getApplication()).also { it.backlog = BacklogSource { snap } }

    private fun loaded(cond: () -> Boolean) { compose.waitUntil(10_000) { compose.waitForIdle(); cond() } }

    private fun any(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun `the Backlog page lists only the tickets of the Home selection and Home chips come from the tickets`() {
        val e = env()
        compose.setContent { TasksApp(e, Declarations.nav) }
        compose.waitForIdle()
        // Home: the chips include the backlog's project names; pick Galaxy alone
        compose.onNodeWithTag(HomeTags.CHIP_NONE).performClick()
        compose.onNodeWithTag(HomeTags.chip("Galaxy")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BottomNavTags.item("tasks")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(PageTabsTags.tab("backlog")).performClick(); compose.waitForIdle()
        loaded { any(FilesTags.ticket(snap.tickets.first { it.project == "Galaxy" && !it.done }.id)) }   // the backlog is read off the main thread
        for (t in snap.tickets) if (t.project == "Galaxy" && !t.done) assertTrue("#" + t.id, any(FilesTags.ticket(t.id)))
        for (t in snap.tickets) if (t.project != "Galaxy") assertFalse("#" + t.id, any(FilesTags.ticket(t.id)))
        // All: every open ticket is back
        compose.onNodeWithTag(BottomNavTags.item("home")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(HomeTags.CHIP_ALL).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(BottomNavTags.item("tasks")).performClick(); compose.waitForIdle()
        loaded { any(FilesTags.ticket(snap.tickets.first { it.project == "Cloudy" && !it.done }.id)) }
        for (t in snap.tickets) if (!t.done) assertTrue("#" + t.id, any(FilesTags.ticket(t.id)))
    }

    @Test fun `Files has the RoadMap and the Assets pages`() {
        val e = env()
        compose.setContent { TasksApp(e, Declarations.nav) }
        compose.onNodeWithTag(BottomNavTags.item("files")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(PageTabsTags.tab("roadmap")).assertIsDisplayed()
        // #1975 the trip section: the grid tiles, the note and the four checklist cards
        for (l in listOf("Bookings", "Calendar", "Maps", "Docs")) compose.onNodeWithTag(FilesTags.tripLink(l)).assertExists()
        compose.onNodeWithTag(PageTabsTags.tab("assets")).assertIsDisplayed()
        compose.onNodeWithTag(FilesTags.ROADMAP).assertIsDisplayed()
        loaded { any(FilesTags.project("Cloudy")) }
        compose.onNodeWithTag(FilesTags.project("Cloudy")).assertExists()
        compose.onNodeWithTag(PageTabsTags.tab("assets")).performClick(); compose.waitForIdle()
        loaded { any(FilesTags.ASSETS_TREE + ":dir:1842") }
        compose.onNodeWithTag(FilesTags.ASSETS_TREE + ":dir:1842").assertExists()
    }
}
