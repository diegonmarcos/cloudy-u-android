package com.diegonmarcos.cloudagenda

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.robolectric.RuntimeEnvironment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.TaskFilter
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import com.diegonmarcos.cloudagenda.ui.DrawerTags
import com.diegonmarcos.cloudagenda.ui.EditorTags
import com.diegonmarcos.cloudagenda.ui.FilterTags
import com.diegonmarcos.cloudagenda.ui.MockTags
import com.diegonmarcos.cloudagenda.ui.Declarations
import com.diegonmarcos.cloudagenda.ui.SettingsTags
import com.diegonmarcos.cloudagenda.ui.TagTags
import com.diegonmarcos.cloudagenda.ui.TasksApp
import com.diegonmarcos.cloudagenda.ui.TasksTags
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.uikit.KitPartTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole shell, rendered under Robolectric over an in-memory database: the island, the hamburger drawer (projects
 * collapsed by default, expanding to lists), the filter bar, the editor's fields, Settings (Appearance, Agent), and
 * the AI panel. Every id is a tag the code declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksPageTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules = composeChain(compose)

    /** [list] = open Tasks > List, the native database page (the Tasks section itself opens on Calendar, its first declared page). */
    private fun launch(seed: Boolean = true, list: Boolean = true): FakeEnv {
        val env = FakeEnv(RuntimeEnvironment.getApplication(), seed)
        compose.setContent { TasksApp(env, Declarations.nav) }
        if (list) { compose.onNodeWithTag(PageTabsTags.tab("list")).performClick(); compose.waitForIdle() }
        return env
    }

    private fun openSettingsPage(id: String) {
        compose.onNodeWithTag(TopBarTags.CONFIGS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PageTabsTags.tab(id)).performClick()
        compose.waitForIdle()
    }

    private fun rows() = compose.onAllNodes(tagStartsWith("task_row_"))
    private fun subRows() = compose.onAllNodes(tagStartsWith("subtask_row_"))

    // ── chrome and island ───────────────────────────────────────────────────────────────────────────────

    @Test fun `the shell draws the top row and the declared island, and opens on Tasks`() {
        launch(list = false)
        compose.onNodeWithTag(TopBarTags.MENU).assertDoesNotExist()
        compose.onNodeWithTag(TopBarTags.TITLE).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.AI).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.ACCOUNT).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.CONFIGS).assertIsDisplayed()
        for (id in listOf("inboxes", "backlogs", "tasks", "library", "projects")) compose.onNodeWithTag(BottomNavTags.item(id)).assertIsDisplayed()
        compose.onNodeWithTag(BottomNavTags.item("settings")).assertDoesNotExist()
        for (id in listOf("calendar", "list", "gantt", "kanban", "mindmap")) compose.onNodeWithTag(PageTabsTags.tab(id)).assertExists()
        compose.onNodeWithTag(MockTags.page("calendar")).assertIsDisplayed()
    }

    @Test fun `every task view renders the mock fixture`() {
        launch(list = false)
        compose.onNodeWithTag(MockTags.NOTICE).assertIsDisplayed()
        for (id in listOf("gantt", "kanban", "mindmap")) {
            compose.onNodeWithTag(PageTabsTags.tab(id)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(MockTags.page(id)).assertIsDisplayed()
        }
        compose.onNodeWithTag(PageTabsTags.tab("list")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TasksTags.PAGE).assertIsDisplayed()
    }

    @Test fun `the calendar is the round one - the month dial and a ring per project - and a tap on a day lists its tasks`() {
        launch(list = false)
        compose.onNodeWithTag(MockTags.DIAL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MockTags.ring("Cloudy")).performScrollTo().assertExists()
        val iso = java.time.LocalDate.now().toString()
        compose.onNodeWithTag(MockTags.day(iso)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.page("calendar")).assertIsDisplayed()
    }

    @Test fun `the calendar toggles to a one-screen year view of 12 months and a tap opens that month`() {
        launch(list = false)
        compose.onNodeWithTag(MockTags.mode("year")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.YEAR).assertIsDisplayed()
        for (m in 1..12) compose.onNodeWithTag(MockTags.yearMonth(m)).assertIsDisplayed()
        compose.onNodeWithTag(MockTags.yearMonth(3)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MockTags.PAGER).assertIsDisplayed()
        compose.onNodeWithTag(MockTags.day(java.time.LocalDate.now().withMonth(3).withDayOfMonth(1).toString())).assertExists()
    }

    @Test fun `the calendar pages by month and by year with the buttons`() {
        launch(list = false)
        val now = java.time.YearMonth.now()
        fun title() = now.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + now.year
        compose.onNodeWithText(title()).assertIsDisplayed()
        compose.onNodeWithTag(MockTags.NEXT_MONTH).performClick()
        compose.waitForIdle()
        val next = now.plusMonths(1)
        compose.onNodeWithText(next.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + next.year).assertIsDisplayed()
        compose.onNodeWithTag(MockTags.PREV_YEAR).performClick()
        compose.waitForIdle()
        val back = next.minusYears(1)
        compose.onNodeWithText(back.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + back.year).assertIsDisplayed()
    }

    @Test fun `the list is grouped by due date in Store rows, one line per task with an actions menu`() {
        val env = launch()
        assertTrue(compose.onAllNodes(tagStartsWith("tasks_group_")).fetchSemanticsNodes().isNotEmpty())
        val first = env.controller.snapshot.topLevel(env.controller.selectedListId).first()
        compose.onNodeWithTag(TasksTags.actions(first.id)).assertExists()
        compose.onNodeWithTag(TasksTags.check(first.id)).assertExists()
    }

    @Test fun `each collection tab lists its mock rows in the shared card layout`() {
        launch(list = false)
        // Each tab is Cloud Me's tree (a first node of it is a folder row); Expand all opens it down to the mock items.
        for ((id, node, item) in listOf(
            Triple("inboxes", "Mental-Models", "Mock screenshot: sample booking"), Triple("backlogs", "Due", "Mock due item: sample renewal"),
            Triple("library", "Research-Dossiers", "Mock reference: fake template pack"), Triple("projects", "Unix", "Mock family task: sample port"),
        )) {
            compose.onNodeWithTag(BottomNavTags.item(id)).performClick()
            compose.waitForIdle()
            // Inboxes opens on Notes; its synced sources (the tree) are the Synced sub-page.
            if (id == "inboxes") { compose.onNodeWithTag(PageTabsTags.tab("synced")).performClick(); compose.waitForIdle() }
            compose.onNodeWithTag(MockTags.page(id)).assertIsDisplayed()
            compose.onNodeWithTag(MockTags.tree(id) + ":dir:" + node).assertExists()
            compose.onNodeWithTag(MockTags.expand(id)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(MockTags.tree(id) + ":file:" + item).assertExists()
            compose.onNodeWithTag(MockTags.collapse(id)).performClick()
        }
    }

    @Test fun `the first run shows Project Zero's first list with three tasks and their subtasks`() {
        val env = launch()
        compose.onNodeWithTag(TasksTags.HEADER).assertIsDisplayed()
        assertEquals("Project Zero", env.controller.snapshot.projects.first().name)
        rows().assertCountEquals(3)
        subRows().assertCountEquals(6)
    }

    // ── the title dropdown (the former hamburger drawer) ────────────────────────────────────────────────────────────────────────────

    @Test fun `the drawer lists projects collapsed, expands to lists, and a list tap switches the page`() {
        val env = launch()
        val project = env.controller.snapshot.projects.first()
        val lists = env.controller.snapshot.listsOf(project.id)
        compose.onNodeWithTag(TopBarTags.TITLE).performClick()
        compose.onNodeWithTag(DrawerTags.project(project.id)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(DrawerTags.list(lists[0].id)).assertDoesNotExist()
        compose.onNodeWithTag(DrawerTags.list(lists[1].id)).assertDoesNotExist()
        compose.onNodeWithTag(DrawerTags.project(project.id)).performScrollTo().performClick()
        compose.onNodeWithTag(DrawerTags.list(lists[0].id)).assertIsDisplayed()
        compose.onNodeWithTag(DrawerTags.list(lists[1].id)).assertIsDisplayed()
        compose.onNodeWithTag(DrawerTags.list(lists[1].id)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(lists[1].id, env.controller.selectedListId)
        compose.onNodeWithTag(TasksTags.HEADER).assertIsDisplayed()
        rows().assertCountEquals(3)
        compose.onNodeWithText("Project Zero  ›  Step 1").assertIsDisplayed()
    }

    @Test fun `the drawer offers New project through a name dialog`() {
        val env = launch()
        compose.onNodeWithTag(TopBarTags.TITLE).performClick()
        compose.onNodeWithTag(DrawerTags.ADD_PROJECT).performScrollTo().performClick()
        compose.onNodeWithTag(DrawerTags.NAME_INPUT).assertIsDisplayed()
        compose.onNodeWithTag(DrawerTags.NAME_OK).assertIsNotEnabled()
        compose.onNodeWithTag(DrawerTags.NAME_CANCEL).performClick()
        compose.onNodeWithTag(DrawerTags.NAME_INPUT).assertDoesNotExist()
        assertEquals(listOf("Project Zero"), env.controller.snapshot.projects.map { it.name })
    }

    // ── the top filter bar ──────────────────────────────────────────────────────────────────────────────

    @Test fun `filtering by status hides non-matching tasks, persists, and Clear all brings them back`() {
        val env = launch()
        compose.onNodeWithTag(FilterTags.CLEAR).assertDoesNotExist()
        compose.onNodeWithTag(FilterTags.status(TaskStatus.WAITING)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TasksTags.NO_MATCH).assertIsDisplayed()
        rows().assertCountEquals(0)
        assertEquals(TaskFilter(statuses = setOf(TaskStatus.WAITING)), env.prefs.filter)
        compose.onNodeWithTag(FilterTags.status(TaskStatus.ACTION)).performClick()
        compose.waitForIdle()
        rows().assertCountEquals(3)
        compose.onNodeWithTag(FilterTags.CLEAR).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(TaskFilter(), env.prefs.filter)
        compose.onNodeWithTag(FilterTags.CLEAR).assertDoesNotExist()
    }

    @Test fun `filtering by tag shows only the tagged task`() {
        val env = launch()
        val snap = env.controller.snapshot
        val tag = env.repo.addTag("urgent", 0xFFE53935)
        val target = snap.topLevel(env.controller.selectedListId).first()
        env.repo.saveTask(target.copy(tagIds = setOf(tag.id)))
        env.controller.refreshAgent()
        compose.waitForIdle()
        compose.onNodeWithTag(FilterTags.TAGS_BUTTON).performScrollTo().performClick()
        compose.onNodeWithTag(FilterTags.tag(tag.id)).performClick()
        compose.waitForIdle()
        rows().assertCountEquals(1)
        compose.onNodeWithTag("task_row_" + target.id).assertIsDisplayed()
        assertEquals(setOf(tag.id), env.prefs.filter.tagIds)
    }

    @Test fun `a restored filter is applied from the first frame`() {
        val env = FakeEnv(RuntimeEnvironment.getApplication())
        env.controller.updateFilter(TaskFilter(statuses = setOf(TaskStatus.HOLD)))
        compose.setContent { TasksApp(env, Declarations.nav) }
        compose.onNodeWithTag(PageTabsTags.tab("list")).performClick()
        compose.onNodeWithTag(TasksTags.NO_MATCH).assertIsDisplayed()
    }

    // ── the task editor ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `the editor has every field and saves an edit`() {
        val env = launch()
        val target = env.controller.snapshot.topLevel(env.controller.selectedListId).first()
        compose.onNodeWithTag("task_row_" + target.id).performClick()
        for (tag in listOf(EditorTags.NAME, EditorTags.DETAILS, EditorTags.DATE_TARGET, EditorTags.DATE_DEADLINE, EditorTags.LOCATION,
            EditorTags.add(AttachmentKind.MAIL), EditorTags.add(AttachmentKind.IMAGE), EditorTags.add(AttachmentKind.FILE), EditorTags.MANAGE_TAGS)) {
            compose.onNodeWithTag(tag).assertExists()
        }
        for (s in TaskStatus.entries) compose.onNodeWithTag(EditorTags.status(s)).assertExists()
        for (sub in env.controller.snapshot.subtasksOf(target.id)) compose.onNodeWithTag(EditorTags.subtask(sub.id)).assertExists()
        compose.onNodeWithTag(EditorTags.NAME).performTextClearance()
        compose.onNodeWithTag(EditorTags.NAME).performTextInput("Renamed task")
        compose.onNodeWithTag(EditorTags.DETAILS).performTextInput("Some details")
        compose.onNodeWithTag(EditorTags.LOCATION).performTextInput("Berlin")
        compose.onNodeWithTag(EditorTags.status(TaskStatus.HOLD)).performScrollTo().performClick()
        compose.onNodeWithTag(EditorTags.SAVE).performClick()
        compose.waitForIdle()
        val saved = env.controller.snapshot.task(target.id)!!
        assertEquals("Renamed task", saved.name)
        assertEquals("Some details", saved.details)
        assertEquals("Berlin", saved.location)
        assertEquals(TaskStatus.HOLD, saved.status)
    }

    @Test fun `the picker buttons call the host and a returned link joins the draft`() {
        val env = launch()
        val target = env.controller.snapshot.topLevel(env.controller.selectedListId).first()
        compose.onNodeWithTag("task_row_" + target.id).performClick()
        compose.onNodeWithTag(EditorTags.add(AttachmentKind.IMAGE)).performScrollTo().performClick()
        compose.onNodeWithTag(EditorTags.add(AttachmentKind.FILE)).performScrollTo().performClick()
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.FILE), env.pickedKinds)
        env.pickedLink = com.diegonmarcos.cloudagenda.links.newAttachment(AttachmentKind.IMAGE, "content://drive/1", "scan.jpg")
        compose.waitForIdle()
        compose.onNodeWithText("scan.jpg").performScrollTo().assertIsDisplayed()
        assertEquals(null, env.pickedLink)
        compose.onNodeWithTag(EditorTags.SAVE).performClick()
        compose.waitForIdle()
        assertEquals("content://drive/1", env.controller.snapshot.task(target.id)!!.attachments.single().uri)
    }

    @Test fun `a subtask opens in the same editor`() {
        val env = launch()
        val target = env.controller.snapshot.topLevel(env.controller.selectedListId).first()
        val sub = env.controller.snapshot.subtasksOf(target.id).first()
        compose.onNodeWithTag("subtask_row_" + sub.id).performClick()
        compose.onNodeWithTag(EditorTags.NAME).assertExists()
        compose.onNodeWithTag(EditorTags.DATE_DEADLINE).assertExists()
        compose.onNodeWithTag(EditorTags.status(TaskStatus.WAITING)).assertExists()
        compose.onNodeWithTag(EditorTags.SUBTASK_INPUT).assertDoesNotExist()
    }

    // ── tags ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the tag manager opens from the filter bar and closes`() {
        val env = launch()
        compose.onNodeWithTag(FilterTags.TAGS_BUTTON).performScrollTo().performClick()
        compose.onNodeWithTag(FilterTags.MANAGE).performClick()
        compose.onNodeWithTag(TagTags.NEW_NAME).assertIsDisplayed()
        compose.onNodeWithTag(TagTags.NEW_ADD).assertIsNotEnabled()
        compose.onNodeWithTag(TagTags.DONE).performClick()
        compose.onNodeWithTag(TagTags.DIALOG).assertDoesNotExist()
        assertEquals(emptyList<String>(), env.controller.snapshot.tags.map { it.name })
    }

    // ── Profile, Settings, theme, agent ─────────────────────────────────────────────────────────────────

    @Test fun `Profile and Configs are plain icons in the first row (#1154)`() {
        launch()
        compose.onNodeWithTag(TopBarTags.ACCOUNT).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.CONFIGS).assertIsDisplayed()
        compose.onNodeWithTag(TopBarTags.SETTINGS).assertDoesNotExist()
    }

    @Test fun `Settings then Configs opens the declared strip, and the Tasks island item returns`() {
        launch()
        compose.onNodeWithTag(TopBarTags.CONFIGS).performClick()
        compose.waitForIdle()
        // The fleet Configs pattern: General | Perms | About first, then the app's own pages.
        for (id in listOf("general", "perms", "about", "appearance", "agent", "inbox", "feeds")) compose.onNodeWithTag(PageTabsTags.tab(id)).assertExists()
        compose.onNodeWithTag("tasks:configs:page:general").assertIsDisplayed()
        compose.onNodeWithTag(PageTabsTags.tab("appearance")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.APPEARANCE).assertIsDisplayed()
        compose.onNodeWithTag(BottomNavTags.item("tasks")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TasksTags.PAGE).assertIsDisplayed()
    }

    @Test fun `choosing Dark in Appearance is saved`() {
        val env = launch()
        openSettingsPage("appearance")
        compose.onNodeWithTag(KitPartTags.segment(SettingsTags.THEME, "dark")).performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.DARK, env.savedMode)
        compose.onNodeWithTag(KitPartTags.segment(SettingsTags.THEME, "system")).performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.SYSTEM, env.savedMode)
    }

    @Test fun `the Agent page saves its declaration and says no backend is deployed after a run that found none`() {
        val env = launch()
        openSettingsPage("agent")
        for (tag in listOf(SettingsTags.AGENT_ENABLED, KitPartTags.segment(SettingsTags.SOURCE, "cloud-mail"), SettingsTags.ACCOUNT, SettingsTags.MAILBOXES, KitPartTags.segment(SettingsTags.FREQUENCY, "360"),
            SettingsTags.MODEL, SettingsTags.TARGET, SettingsTags.extract("events"), SettingsTags.extract("bookings"),
            SettingsTags.extract("invoices"), SettingsTags.extract("deadlines"), SettingsTags.SAVE)) {
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithTag(SettingsTags.AGENT_ENABLED).performClick()
        compose.onNodeWithTag(SettingsTags.MAILBOXES).performScrollTo().performTextInput("inbox")
        compose.onNodeWithTag(SettingsTags.SAVE).performScrollTo().performClick()
        compose.waitForIdle()
        val saved = env.savedAgent
        assertNotNull(saved)
        assertTrue(saved!!.enabled)
        assertEquals("inbox", saved.mailboxes)
        env.repo.recordAgentRun(1, "backend_not_deployed", "the fleet AI gateway is not reachable")
        env.controller.refreshAgent()
        compose.waitForIdle()
        compose.onNodeWithText("Agent backend not deployed: the fleet AI gateway is not reachable").performScrollTo().assertIsDisplayed()
    }

    // ── the AI panel ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the AI button drops a panel down and a second tap closes it`() {
        launch()
        compose.onNodeWithTag(TopBarTags.AI_PANEL).assertDoesNotExist()
        compose.onNodeWithTag(TopBarTags.AI).performClick()
        compose.onNodeWithTag(TopBarTags.AI_PANEL).assertIsDisplayed()
        compose.onNodeWithTag(com.diegonmarcos.superapp.ai.AiTags.CHAT_INPUT).assertExists()
        compose.onNodeWithTag(TopBarTags.AI).performClick()
        compose.onNodeWithTag(TopBarTags.AI_PANEL).assertDoesNotExist()
    }
}
