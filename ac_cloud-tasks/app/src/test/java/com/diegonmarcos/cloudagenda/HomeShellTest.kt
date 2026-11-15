package com.diegonmarcos.cloudagenda

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.cloudagenda.data.ProjectSelection
import com.diegonmarcos.cloudagenda.data.SampleProjects
import com.diegonmarcos.cloudagenda.ui.Declarations
import com.diegonmarcos.cloudagenda.ui.HomeTags
import com.diegonmarcos.cloudagenda.ui.MockTags
import com.diegonmarcos.cloudagenda.ui.TasksApp
import com.diegonmarcos.cloudagenda.ui.TasksTags
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.uikit.KitTags
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

/** #1757 the shell with Home: the selector, the status cards, the persisted selection applied to the other tabs, and the calendar styles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShellTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules = composeChain(compose)

    private fun launch(): FakeEnv {
        val env = FakeEnv(RuntimeEnvironment.getApplication(), true, SampleProjects.parse(File("../data/projects/projects.json").readText()))
        compose.setContent { TasksApp(env, Declarations.nav) }
        return env
    }

    private fun tab(id: String) { compose.onNodeWithTag(BottomNavTags.item(id)).performClick(); compose.waitForIdle() }
    private fun any(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun `Home shows the selector with All and None and a status card per project, the sample flagged`() {
        launch()
        tab("home")
        compose.onNodeWithTag(HomeTags.PAGE).assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.CHIP_ALL).assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.CHIP_NONE).assertIsDisplayed()
        for (n in listOf("Project Zero", "Traveling", "Cloudy", "Cloud-Me", "Galaxy")) {
            compose.onNodeWithTag(HomeTags.chip(n)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(HomeTags.card(n)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag(HomeTags.sample("Traveling")).performScrollTo().assertIsDisplayed()
        assertFalse(any(HomeTags.sample("Cloudy")))
        compose.onNodeWithTag(HomeTags.TOTAL).assertExists()
    }

    @Test fun `the selection is committed to the prefs and applied to Inbox, Backlog, Tasks and Library`() {
        val env = launch()
        tab("home")
        compose.onNodeWithTag(HomeTags.CHIP_NONE).performClick()
        compose.waitForIdle()
        assertEquals(ProjectSelection.NONE, env.prefs.projectSelection)
        compose.onNodeWithTag(HomeTags.chip("Cloudy")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ProjectSelection(false, setOf("Cloudy")), env.prefs.projectSelection)
        // Backlog: only Cloudy's rows hang under their nodes
        tab("backlogs")
        compose.onNodeWithTag(HomeTags.SCOPE_LINE).assertIsDisplayed()
        compose.onNodeWithTag(MockTags.expand("backlogs")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.tree("backlogs") + ":file:Mock due item: sample renewal").assertExists()
        assertFalse(any(MockTags.tree("backlogs") + ":file:Idea: a fake dashboard"))
        // All brings everything back
        tab("home")
        compose.onNodeWithTag(HomeTags.CHIP_ALL).performClick()
        compose.waitForIdle()
        assertEquals(ProjectSelection.ALL, env.prefs.projectSelection)
    }

    @Test fun `selecting Traveling alone opens the Calendar as the Gantt Calendar, All opens it as Circles Day`() {
        launch()
        tab("tasks")
        compose.onNodeWithTag(MockTags.DIAL).performScrollTo().assertExists()
        tab("home")
        compose.onNodeWithTag(HomeTags.CHIP_NONE).performClick()
        compose.onNodeWithTag(HomeTags.chip("Traveling")).performScrollTo().performClick()
        compose.waitForIdle()
        tab("tasks")
        compose.onNodeWithTag(PageTabsTags.tab("calendar")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.BARS).assertExists()
        assertFalse(any(MockTags.DIAL))
        // the user can still pick another style by hand
        compose.onNodeWithTag(MockTags.style("circles")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.DIAL).performScrollTo().assertExists()
        compose.onNodeWithTag(MockTags.style("linear")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.BARS).assertExists()
    }

    @Test fun `the calendar has the three styles and a year view`() {
        launch()
        tab("tasks")
        for (id in listOf("circles", "linear", "gantt")) compose.onNodeWithTag(MockTags.style(id)).assertIsDisplayed()
        compose.onNodeWithTag(MockTags.mode("year")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.YEAR).assertIsDisplayed()
    }

    @Test fun `the sample is deleted from its card and stays gone`() {
        val env = launch()
        assertTrue(env.controller.snapshot.projects.any { it.name == "Traveling" && it.sample })
        tab("home")
        compose.onNodeWithTag(HomeTags.delete("Traveling")).performScrollTo().performClick()
        compose.onNodeWithTag(KitTags.DIALOG_CONFIRM).performClick()
        compose.waitForIdle()
        assertFalse(env.controller.snapshot.projects.any { it.name == "Traveling" })
        assertFalse(any(HomeTags.card("Traveling")))
        assertTrue(env.repo.seedSamples(SampleProjects.parse(File("../data/projects/projects.json").readText()), java.time.LocalDate.now()).isEmpty())
    }

    @Test fun `the Tasks list shows only the selected projects`() {
        val env = launch()
        tab("home")
        compose.onNodeWithTag(HomeTags.CHIP_NONE).performClick()
        compose.onNodeWithTag(HomeTags.chip("Traveling")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ProjectSelection(false, setOf("Traveling")), env.prefs.projectSelection)
        tab("tasks")
        compose.onNodeWithTag(PageTabsTags.tab("list")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TasksTags.PAGE).assertIsDisplayed()
    }
}
