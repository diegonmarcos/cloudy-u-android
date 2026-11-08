package com.diegonmarcos.cloudsuperintel.forum

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.centerY
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudsuperintel.Data
import com.diegonmarcos.superapp.ai.AiAttachHost
import com.diegonmarcos.superapp.ai.AiForumChat
import com.diegonmarcos.superapp.ai.AiSessionGroups
import com.diegonmarcos.superapp.ai.AiTags
import com.diegonmarcos.superapp.ai.ForumGlyphs
import com.diegonmarcos.superapp.ai.ForumSessions
import com.diegonmarcos.superapp.ai.ForumTags
import com.diegonmarcos.superapp.ai.ForumTools
import com.diegonmarcos.superapp.ai.ForumTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The regression guard for what #1563 removed (rule 16: never remove): on the SI tab the AI tooling stays in the composer
 * ((+) attach, Task, Specialist, Agent, Model, Effort, Mode, Repos, Plugins, MCP buckets, More; the model catalogue with OpenRouter
 * search; image in and out inline), and the left rail is the user's chat SESSIONS, not topic categories. The layout is: row 1 the
 * full-width top bar (hamburger, session pill, Usage & Cost, Context, Files), row 2 the specialists, then the sessions rail beside
 * the thread, whose input row is (+) input Voice Camera Enter and whose toolbar has two rows. Rendered from the real libs:ai surface under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-420dpi")
class SiChatToolsTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var created = 0

    private var topicsHolder: List<ForumTopic> = emptyList()

    private fun render(topics: List<ForumTopic> = ForumFixture.richTopics(), tools: Boolean = true, groups: AiSessionGroups? = null) {
        topicsHolder = topics
        compose.setContent {
            var persona by remember { mutableStateOf("si") }
            var current by remember { mutableStateOf("s-release") }
            AiForumChat(
                topics = topics, selectedId = persona, onSelect = { persona = it },
                theme = ForumFixture.theme(dark = true), icon = ForumGlyphs,
                adminColor = ForumFixture.adminColor(true), zone = ForumFixture.zone, now = ForumFixture.now,
                sessions = ForumSessions(ForumFixture.sessionList(), current, { current = it.id; opened += it.id }, { created++ }, groups = groups),
                tools = if (tools) ForumTools(ForumFixture.composerDecl, attach = AiAttachHost()) else null,
            )
        }
    }

    @Test fun leftRailIsTheSessionsNotTheTopics() {
        render()
        compose.onNodeWithTag(ForumTags.SESSIONS_RAIL).assertIsDisplayed()
        // The topics rail (Mat, Phy, ...) is gone from the left, and so is the persona chip strip: specialists are the Specialist tool.
        compose.onAllNodesWithTag(ForumTags.RAIL).assertCountEquals(0)
        compose.onAllNodesWithTag(ForumTags.TOPIC_STRIP).assertCountEquals(0)
        compose.onAllNodesWithTag(ForumTags.topic("math")).assertCountEquals(0)
        ForumFixture.sessionList().forEach { compose.onNodeWithTag(ForumTags.session(it.id)).assertExists() }
        compose.onNodeWithTag(ForumTags.NEW_SESSION).assertIsDisplayed()
    }

    @Test fun tappingASessionOpensItAndNewStartsOne() {
        render()
        compose.onNodeWithTag(ForumTags.session("s-proofs")).performClick()
        compose.onNodeWithTag(ForumTags.NEW_SESSION).performClick()
        assertEquals(listOf("s-proofs"), opened)
        assertEquals(1, created)
    }

    @Test fun choosingAPersonaSwitchesTheThreadAndItsTitle() {
        render()
        compose.onNodeWithText("Release planning", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(AiTags.ctl("specialist")).performScrollTo().performClick()
        compose.onNodeWithTag(AiTags.specialist("math")).performClick()
        compose.onNodeWithText("Proofs", substring = true).assertIsDisplayed()
    }

    @Test fun headerIsStyledWithAvatarTitleAndWhoIsInTheThread() {
        render()
        compose.onNodeWithTag(ForumTags.HEADER).assertIsDisplayed()
        compose.onNodeWithTag(ForumTags.HEADER_AVATAR).assertIsDisplayed()
        // participants · model · count
        assertTrue(compose.onAllNodesWithTag(ForumTags.HEADER).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun backgroundIsDrawnBehindTheMessages() {
        render()
        compose.onNodeWithTag(ForumTags.BACKDROP).assertExists()
    }

    /** A node's rectangle in the root, unclipped (a control scrolled out of its row still has its place). */
    private fun rect(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
    /** The left edge of the one divider in toolbar row 1 (the toolbar has four in all: one here, three in row 2). */
    private fun row1DividerLeft(): Float {
        val r1 = rect(AiTags.toolbarRow(1))
        return compose.onAllNodesWithTag(AiTags.TOOLBAR_DIVIDER).fetchSemanticsNodes().map { Rect(it.positionInRoot, it.size.toSize()) }.single { it.center.y in r1.top..r1.bottom }.left
    }

    private fun left(tag: String) = rect(tag).left
    private fun top(tag: String) = rect(tag).top
    private fun bottom(tag: String) = rect(tag).bottom
    private fun centerY(tag: String) = rect(tag).center.y

    @Test fun composerCarriesTheWholeAiToolRow() {
        render()
        compose.onNodeWithTag(ForumTags.TOOLS_BAR).assertExists()
        for (ctl in listOf("plus", "task", "specialist", "agent", "model", "effort", "mode", "skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more")) {
            compose.onNodeWithTag(AiTags.ctl(ctl)).assertExists()
        }
    }

    @Test fun topBarIsTheWholeFirstRowWithHamburgerSessionPillAndTheThreeButtons() {
        render()
        compose.onNodeWithTag(ForumTags.TOP_BAR).assertIsDisplayed()
        for (tag in listOf(ForumTags.MENU, ForumTags.SESSION_PILL, ForumTags.USAGE, ForumTags.CONTEXT, ForumTags.FILES)) compose.onNodeWithTag(tag).assertIsDisplayed()
        // "<session> · <repo/Model>" in the pill.
        compose.onNodeWithText("Release planning · fake/model").assertIsDisplayed()
        // Left to right: hamburger, pill, Usage & Cost, Context Breakdown, Files in this chat.
        val order = listOf(ForumTags.MENU, ForumTags.HEADER, ForumTags.USAGE, ForumTags.CONTEXT, ForumTags.FILES).map { left(it) }
        assertEquals(order.sorted(), order)
        // The bar is the full first row: it starts at the very top and spans the whole surface width.
        val surface = rect(ForumTags.SURFACE)
        val bar = rect(ForumTags.TOP_BAR)
        assertEquals(surface.top, bar.top, 1f)
        assertEquals(surface.width, bar.width, 1f)
    }

    @Test fun sessionsRailStartsDirectlyBelowTheTopBarWithNoSpecialistsRowBetween() {
        render()
        val bar = bottom(ForumTags.TOP_BAR)
        // The sessions rail does NOT reach the top: it starts right under the top bar, and the thread beside it.
        assertTrue(top(ForumTags.SESSIONS_RAIL) >= bar - 1f)
        assertTrue(top(ForumTags.SESSIONS_RAIL) > top(ForumTags.TOP_BAR))
        assertTrue(top(ForumTags.SESSIONS_RAIL) - bar < 8f)
        // The standalone specialists chip row is gone; specialists are reached through the Specialist tool only.
        compose.onAllNodesWithTag(ForumTags.TOPIC_STRIP).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.ctl("specialist")).assertExists()
    }

    @Test fun dividerStandsBetweenSpecialistAndAgent() {
        render()
        val d = row1DividerLeft()
        assertTrue(left(AiTags.ctl("specialist")) < d && d < left(AiTags.ctl("agent")))
        // Directly between them: nothing else sits in between.
        for (other in listOf("task", "model", "effort", "mode")) assertTrue(other, left(AiTags.ctl(other)) < left(AiTags.ctl("specialist")) || left(AiTags.ctl(other)) > left(AiTags.ctl("agent")))
    }

    private val prefs get() = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("ai_composer", android.content.Context.MODE_PRIVATE)
    private val row1Key = "ai_composer_collapsed.cloud-superintel.row1"
    private val row2Key = "ai_composer_collapsed.cloud-superintel.row2"

    @Test fun eachToolbarRowCollapsesAndExpandsOnItsOwnAndRemembersIt() {
        render()
        compose.onNodeWithTag(AiTags.ctl("task")).assertExists()
        compose.onNodeWithTag(AiTags.ctl("kg_repos")).assertExists()
        // Collapse row 1 only.
        compose.onNodeWithTag(AiTags.rowToggle(1)).performClick()
        compose.onAllNodesWithTag(AiTags.ctl("task")).assertCountEquals(0)
        compose.onAllNodesWithTag(AiTags.ctl("mode")).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.ctl("kg_repos")).assertExists()
        assertTrue(prefs.getBoolean(row1Key, false)); assertFalse(prefs.getBoolean(row2Key, false))
        // Collapse row 2 as well, then expand row 1 again: independent.
        compose.onNodeWithTag(AiTags.rowToggle(2)).performClick()
        compose.onAllNodesWithTag(AiTags.ctl("kg_repos")).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.rowToggle(1)).performClick()
        compose.onNodeWithTag(AiTags.ctl("task")).assertExists()
        compose.onAllNodesWithTag(AiTags.ctl("more")).assertCountEquals(0)
        assertFalse(prefs.getBoolean(row1Key, true)); assertTrue(prefs.getBoolean(row2Key, false))
        compose.onNodeWithTag(AiTags.rowToggle(2)).performClick()
        for (c in listOf("skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more")) compose.onNodeWithTag(AiTags.ctl(c)).assertExists()
    }

    @Test fun aRowCollapsedInAPreviousSessionStartsCollapsed() {
        prefs.edit().putBoolean(row2Key, true).commit()
        render()
        compose.onNodeWithTag(AiTags.ctl("task")).assertExists()
        compose.onAllNodesWithTag(AiTags.ctl("kg_repos")).assertCountEquals(0)
        compose.onAllNodesWithTag(AiTags.ctl("more")).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.rowToggle(2)).assertExists()
    }

    @Test fun hamburgerOpensTheDrawerWithEveryChatSearchableAndNew() {
        render()
        compose.onAllNodesWithTag(ForumTags.ALLCHATS).assertCountEquals(0)
        compose.onNodeWithTag(ForumTags.MENU).performClick()
        compose.onNodeWithTag(ForumTags.ALLCHATS).assertIsDisplayed()
        compose.onNodeWithTag(ForumTags.ALLCHATS_NEW).assertExists()
        ForumFixture.sessionList().forEach { compose.onNodeWithTag(ForumTags.allChat(it.id)).assertExists() }
        compose.onNodeWithTag(ForumTags.ALLCHATS_SEARCH).performTextInput("proof")
        compose.onNodeWithTag(ForumTags.allChat("s-proofs")).assertExists()
        for (id in listOf("s-release", "s-energy", "s-law")) compose.onAllNodesWithTag(ForumTags.allChat(id)).assertCountEquals(0)
        compose.onNodeWithTag(ForumTags.allChat("s-proofs")).performClick()
        assertEquals(listOf("s-proofs"), opened)
        compose.onAllNodesWithTag(ForumTags.ALLCHATS).assertCountEquals(0)
    }

    @Test fun newInTheDrawerStartsAChatAndCloses() {
        render()
        compose.onNodeWithTag(ForumTags.MENU).performClick()
        compose.onNodeWithTag(ForumTags.ALLCHATS_NEW).performClick()
        assertEquals(1, created)
        compose.onAllNodesWithTag(ForumTags.ALLCHATS).assertCountEquals(0)
    }

    @Test fun sessionsAreOrganisedInPersistentCollapsibleGroups() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        render(groups = AiSessionGroups.prefs(app, "t"))
        compose.onNodeWithTag(ForumTags.MENU).performClick()
        compose.onNodeWithTag(ForumTags.allChatsTab("groups")).performClick()
        // Create a group.
        compose.onNodeWithTag(ForumTags.NEW_GROUP).performClick()
        compose.onNodeWithTag(ForumTags.GROUP_FIELD).performTextInput("Work")
        compose.onNodeWithTag(ForumTags.GROUP_ADD).performClick()
        compose.onNodeWithTag(ForumTags.group("g1")).assertExists()
        // Assign a chat to it from the chat's menu.
        compose.onNodeWithTag(ForumTags.chatMenu("s-proofs")).performClick()
        compose.onNodeWithTag(ForumTags.moveTo("s-proofs", "g1")).performClick()
        var reread = AiSessionGroups.prefs(app, "t")
        assertEquals(listOf("Work"), reread.groups.map { it.name })
        assertEquals("g1", reread.groupOf("s-proofs"))
        // The rail follows the open chat's group: the open chat is ungrouped, so the grouped one is not on the rail.
        compose.onAllNodesWithTag(ForumTags.session("s-proofs")).assertCountEquals(0)
        compose.onNodeWithTag(ForumTags.session("s-release")).assertExists()
        // Collapse the group: its chat leaves the list; the collapsed state persists. Expand it back.
        compose.onNodeWithTag(ForumTags.group("g1")).performClick()
        compose.onAllNodesWithTag(ForumTags.allChat("s-proofs")).assertCountEquals(0)
        reread = AiSessionGroups.prefs(app, "t")
        assertTrue(reread.groups.single().collapsed)
        compose.onNodeWithTag(ForumTags.group("g1")).performClick()
        compose.onNodeWithTag(ForumTags.allChat("s-proofs")).assertExists()
        assertTrue(!AiSessionGroups.prefs(app, "t").groups.single().collapsed)
        // Move it back out.
        compose.onNodeWithTag(ForumTags.chatMenu("s-proofs")).performClick()
        compose.onNodeWithTag(ForumTags.ungroup("s-proofs")).performClick()
        assertEquals(null, AiSessionGroups.prefs(app, "t").groupOf("s-proofs"))
    }

    @Test fun drawerClosesOnTapOutsideAndOnTheCloseButtonAndHasAllGroupsArchivedTabs() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        render(groups = AiSessionGroups.prefs(app, "t2"))
        compose.onNodeWithTag(ForumTags.MENU).performClick()
        for (t in listOf("all", "groups", "archived")) compose.onNodeWithTag(ForumTags.allChatsTab(t)).assertExists()
        // Tap outside the drawer (on the scrim, right of it) closes it.
        compose.onNodeWithTag(ForumTags.ALLCHATS_SCRIM).performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 10f, centerY)) }
        compose.onAllNodesWithTag(ForumTags.ALLCHATS).assertCountEquals(0)
        // The close button does too.
        compose.onNodeWithTag(ForumTags.MENU).performClick()
        compose.onNodeWithTag(ForumTags.ALLCHATS_CLOSE).performClick()
        compose.onAllNodesWithTag(ForumTags.ALLCHATS).assertCountEquals(0)
    }

    @Test fun archivingMovesAChatToTheArchivedTabAndItPersists() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        render(groups = AiSessionGroups.prefs(app, "t3"))
        compose.onNodeWithTag(ForumTags.MENU).performClick()
        // All tab is first and lists every chat.
        ForumFixture.sessionList().forEach { compose.onNodeWithTag(ForumTags.allChat(it.id)).assertExists() }
        compose.onNodeWithTag(ForumTags.chatMenu("s-law")).performClick()
        compose.onNodeWithTag(ForumTags.archive("s-law")).performClick()
        compose.onAllNodesWithTag(ForumTags.allChat("s-law")).assertCountEquals(0)
        compose.onAllNodesWithTag(ForumTags.session("s-law")).assertCountEquals(0)
        compose.onNodeWithTag(ForumTags.allChatsTab("archived")).performClick()
        compose.onNodeWithTag(ForumTags.allChat("s-law")).assertExists()
        assertTrue(AiSessionGroups.prefs(app, "t3").isArchived("s-law"))
    }

    @Test fun topBarActionsAreIconOnlyWithContentDescriptions() {
        render()
        compose.onNodeWithTag(ForumTags.USAGE).assertContentDescriptionEquals("Usage & Cost")
        compose.onNodeWithTag(ForumTags.CONTEXT).assertContentDescriptionEquals("Context Breakdown")
        compose.onNodeWithTag(ForumTags.FILES).assertContentDescriptionEquals("Files in this chat")
        // No text label is drawn for them.
        for (label in listOf("Usage & Cost", "Context Breakdown", "Files in this chat")) compose.onAllNodesWithText(label).assertCountEquals(0)
    }

    @Test fun inputRowIsPlusInputVoiceCameraEnter() {
        render()
        val row = listOf(AiTags.ctl("plus"), ForumTags.INPUT, ForumTags.MIC, ForumTags.CAMERA, ForumTags.SEND)
        for (tag in row) compose.onNodeWithTag(tag).assertExists()
        val xs = row.map { left(it) }
        assertEquals(xs.sorted(), xs)
        // All on the one row, below the thread and above the toolbar.
        val y = rect(ForumTags.INPUT_ROW)
        for (tag in row) assertTrue(tag, centerY(tag) in y.top..y.bottom)
        assertTrue(y.bottom <= top(ForumTags.TOOLS_BAR) + 1f)
    }

    @Test fun toolbarRowOneIsTaskSpecialistThenAgentModelEffortMode() {
        render()
        val row = listOf("task", "specialist", "agent", "model", "effort", "mode").map { AiTags.ctl(it) }
        val xs = row.map { left(it) }
        assertEquals(xs.sorted(), xs)
        val r1 = rect(AiTags.toolbarRow(1))
        for (tag in row) assertTrue(tag, centerY(tag) in r1.top..r1.bottom)
        // A divider stands between (Task)(Specialist) and (Agent).
        assertTrue(left(AiTags.ctl("specialist")) < row1DividerLeft() && row1DividerLeft() < left(AiTags.ctl("agent")))
        // The (+) is not on the toolbar any more.
        assertTrue(centerY(AiTags.ctl("plus")) < r1.top)
    }

    @Test fun toolbarRowTwoIsSkillsStoreAppsReposInfraPerfMoreWithThreeDividers() {
        render()
        val ids = listOf("skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more")
        val row = ids.map { AiTags.ctl(it) }
        val xs = row.map { left(it) }
        assertEquals(xs.sorted(), xs)
        val r2 = rect(AiTags.toolbarRow(2))
        for (tag in row) assertTrue(tag, centerY(tag) in r2.top..r2.bottom)
        // Row 2 is a NEW row under row 1.
        assertTrue(r2.top >= bottom(AiTags.toolbarRow(1)) - 1f)
        // Three visible dividers in row 2 (after Plugins-Skills, after KG-Cloud-apps, after Plugins-Perf), each with real size, plus the one in row 1.
        val dividers = compose.onAllNodesWithTag(AiTags.TOOLBAR_DIVIDER).fetchSemanticsNodes()
        assertEquals(4, dividers.size)
        val inRow2 = dividers.map { Rect(it.positionInRoot, it.size.toSize()) }.filter { it.center.y in r2.top..r2.bottom }.sortedBy { it.left }
        assertEquals(3, inRow2.size)
        for (d in inRow2) assertTrue(d.width > 0f && d.height > 0f)
        fun x(id: String) = left(AiTags.ctl(id))
        assertTrue(x("skills") < inRow2[0].left && inRow2[0].left < x("kg_store"))
        assertTrue(x("kg_apps") < inRow2[1].left && inRow2[1].left < x("kg_repos"))
        assertTrue(x("perf") < inRow2[2].left && inRow2[2].left < x("more"))
        // The legacy single MCP chip and the old four-bucket chips are gone.
        for (old in listOf("mcp", "mcp_kg", "mcp_user", "mcp_ext", "mcp_infra", "repos")) compose.onAllNodesWithTag(AiTags.ctl(old)).assertCountEquals(0)
    }

    @Test fun costIsOnTheTopBarNotOnTheToolbar() {
        render()
        compose.onAllNodesWithTag(AiTags.ctl("usage")).assertCountEquals(0)
        compose.onAllNodesWithTag(AiTags.ctl("cost")).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.ctl("more")).performScrollTo().performClick()
        // Not in More either: Usage & Cost is the top bar's button, which opens the reported usage.
        compose.onAllNodesWithTag(AiTags.more("usage")).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.more("export")).assertExists()
    }

    @Test fun usageContextAndFilesButtonsOpenTheirViews() {
        render()
        compose.onNodeWithTag(ForumTags.USAGE).performClick()
        compose.onNodeWithTag(ForumTags.DIALOG).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag(ForumTags.CONTEXT).performClick()
        compose.onNodeWithTag(ForumTags.DIALOG).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag(ForumTags.FILES).performClick()
        compose.onNodeWithTag(ForumTags.DIALOG).assertExists()
    }

    @Test fun cloudyPrincipalsIsASkillPluginAndHeadroomAPerformancePlugin() {
        render()
        compose.onNodeWithTag(AiTags.ctl("skills")).performScrollTo().performClick()
        for (id in listOf("principles", "caveman")) compose.onNodeWithTag(AiTags.morePlugin(id)).assertExists()
        for (id in listOf("skills", "memory")) compose.onNodeWithTag(AiTags.more(id)).assertExists()
        compose.onNodeWithText("Cloudy Principals", substring = true).assertExists()
        compose.onAllNodesWithText("Principles", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag(AiTags.morePlugin("headroom")).assertCountEquals(0)
    }

    @Test fun headroomAndRtkAreUnderPluginsPerformance() {
        render()
        compose.onNodeWithTag(AiTags.ctl("perf")).performScrollTo().performClick()
        for (id in listOf("headroom", "rtk")) compose.onNodeWithTag(AiTags.morePlugin(id)).assertExists()
        compose.onAllNodesWithTag(AiTags.morePlugin("principles")).assertCountEquals(0)
    }

    @Test fun pluginsLeaveTheMoreSheetButEverythingElseStays() {
        render()
        compose.onNodeWithTag(AiTags.ctl("more")).performScrollTo().performClick()
        for (id in listOf("headroom", "rtk", "caveman", "principles")) compose.onAllNodesWithTag(AiTags.morePlugin(id)).assertCountEquals(0)
        for (id in listOf("export", "retry", "undo", "system", "shortcuts", "about")) compose.onNodeWithTag(AiTags.more(id)).assertExists()
    }

    private fun bucket(ctl: String, present: List<String>, absent: List<String>) {
        render()
        compose.onNodeWithTag(AiTags.ctl(ctl)).performScrollTo().performClick()
        for (n in present) compose.onNodeWithTag(AiTags.mcp(n)).assertExists()
        for (n in absent) compose.onAllNodesWithTag(AiTags.mcp(n)).assertCountEquals(0)
    }

    @Test fun kgCloudInfraHoldsTheOneInfraMcp() = bucket("kg_infra", listOf("cloudy-c3-mcp"), listOf("cloudy-all-mcp", "google-workspace-mcp"))

    @Test fun kgCloudAppsHoldTheUserFacingCloudyMcps() = bucket(
        "kg_apps", listOf("cloudy-all-mcp", "cloudy-apps-drive-mcp", "cloudy-apps-communication-mcp", "cloud-superapp-mcp", "cloud-mattermost-mcp"),
        listOf("cloudy-c3-mcp", "google-personal-mcp"),
    )

    @Test fun kgStoreOpensAFullTabbedPageNotASheet() {
        render()
        compose.onAllNodesWithTag(AiTags.KGSTORE_PAGE).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.ctl("kg_store")).performScrollTo().performClick()
        compose.onNodeWithTag(AiTags.KGSTORE_PAGE).assertIsDisplayed()
        // It replaces the thread like the Model Catalogue does (no input, no message list), under the same top bar.
        compose.onAllNodesWithTag(ForumTags.INPUT).assertCountEquals(0)
        compose.onNodeWithTag(ForumTags.TOP_BAR).assertIsDisplayed()
        compose.onNodeWithTag(AiTags.KGSTORE_SEARCH).assertExists()
        compose.onNodeWithTag(AiTags.KGSTORE_CLOSE).assertExists()
        val tabs = listOf("web", "releases", "workspace", "personal", "other")
        for (t in tabs) compose.onNodeWithTag(AiTags.kgTab(t)).assertExists()
        assertEquals(tabs.map { left(AiTags.kgTab(it)) }.sorted(), tabs.map { left(AiTags.kgTab(it)) })
        // The page is as wide as the thread, i.e. a page and not a dropdown.
        assertTrue(rect(AiTags.KGSTORE_PAGE).width > rect(ForumTags.SURFACE).width / 2f)
        // Web Search tab: the web switch and public sources; a source toggles and persists on the chat.
        compose.onNodeWithTag(AiTags.kgItem("web-search")).assertExists()
        compose.onNodeWithTag(AiTags.kgItem("fetch")).performClick()
        assertEquals(setOf("fetch"), topicChat().sourcesOn)
        compose.onNodeWithTag(AiTags.kgTab("workspace")).performClick()
        compose.onNodeWithTag(AiTags.kgItem("google-workspace-mcp")).assertExists()
        compose.onAllNodesWithTag(AiTags.kgItem("fetch")).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.kgTab("personal")).performClick()
        compose.onNodeWithTag(AiTags.kgItem("google-personal-mcp")).assertExists()
        compose.onNodeWithTag(AiTags.kgTab("releases")).performClick()
        compose.onNodeWithTag(AiTags.kgItem("releases")).assertExists()
        // The chip counts what is on, and Close returns to the chat.
        compose.onNodeWithTag(AiTags.KGSTORE_CLOSE).performClick()
        compose.onAllNodesWithTag(AiTags.KGSTORE_PAGE).assertCountEquals(0)
        compose.onNodeWithTag(ForumTags.INPUT).assertExists()
    }

    @Test fun kgStoreSearchLooksAcrossEveryTab() {
        render()
        compose.onNodeWithTag(AiTags.ctl("kg_store")).performScrollTo().performClick()
        compose.onNodeWithTag(AiTags.KGSTORE_SEARCH).performTextInput("gmail")
        compose.onNodeWithTag(AiTags.kgItem("google-workspace-mcp")).assertExists()
        compose.onNodeWithTag(AiTags.kgItem("google-personal-mcp")).assertExists()
        compose.onAllNodesWithTag(AiTags.kgItem("fetch")).assertCountEquals(0)
    }

    @Test fun kgStoreOtherTabIsDeclaredOrListedInternetMcpsOnly() {
        render()
        compose.onNodeWithTag(AiTags.ctl("kg_store")).performScrollTo().performClick()
        compose.onNodeWithTag(AiTags.kgTab("other")).performClick()
        // Nothing Cloudy is offered here: the user-facing and infra MCPs live in their own pickers.
        compose.onAllNodesWithTag(AiTags.kgItem("cloudy-all-mcp")).assertCountEquals(0)
        compose.onAllNodesWithTag(AiTags.kgItem("cloudy-c3-mcp")).assertCountEquals(0)
    }

    @Test fun reposPickerOffersTheDeclaredRepositories() {
        render()
        compose.onNodeWithTag(AiTags.ctl("kg_repos")).performScrollTo().performClick()
        for (r in ForumFixture.composerDecl.repos) compose.onNodeWithTag(AiTags.repo(r)).assertExists()
        compose.onNodeWithTag(AiTags.repo("cloudy-t-infra")).performClick()
        assertEquals(setOf("cloudy-t-infra"), topicChat().reposOn)
    }

    @Test fun specialistPickerSwitchesThePersona() {
        render()
        compose.onNodeWithTag(AiTags.ctl("specialist")).performScrollTo().performClick()
        for (id in listOf("si", "math", "physics")) compose.onNodeWithTag(AiTags.specialist(id)).assertExists()
        compose.onNodeWithTag(AiTags.specialist("math")).performClick()
        compose.onNodeWithText("Proofs", substring = true).assertIsDisplayed()
    }

    /** The composer controls of the previous layout, each with a home in the new one: nothing was dropped. */
    @Test fun noPriorComposerControlWasLost() {
        val prior = listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more")
        val home = mapOf("mcp" to listOf("kg_store", "kg_apps", "kg_infra"))
        render()
        for (c in prior) for (n in home[c] ?: listOf(c)) compose.onNodeWithTag(AiTags.ctl(n)).assertExists()
        // (+) menu: the attach kinds and New chat.
        compose.onNodeWithTag(AiTags.ctl("plus")).performClick()
        for (kind in listOf("files", "images", "camera")) compose.onNodeWithTag(AiTags.attach(kind)).assertExists()
        compose.onNodeWithTag(AiTags.NEW_CHAT).assertExists()
        // Emoji, Voice and Send of the old row stay.
        for (tag in listOf(ForumTags.EMOJI, ForumTags.MIC, ForumTags.SEND)) compose.onNodeWithTag(tag).assertExists()
    }

    private fun topicChat() = topicsHolder.first().chat

    @Test fun modelButtonOpensTheCatalogueWithOpenRouterSearch() {
        render()
        compose.onNodeWithTag(AiTags.ctl("model")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(AiTags.CATALOGUE_PAGE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(AiTags.CAT_SEARCH).assertExists()
    }

    @Test fun taskButtonOpensTheTaskCatalogue() {
        val topics = ForumFixture.richTopics()
        topics.first().chat.tasks = com.diegonmarcos.superapp.ai.AiDesign.loadTasks(org.robolectric.RuntimeEnvironment.getApplication())
        render(topics)
        compose.onNodeWithTag(AiTags.ctl("task")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(AiTags.TASK_PAGE).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun imageInAndImageOutRenderInlineWithTheModelBadge() {
        render()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(AiTags.image("in")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(AiTags.image("out")).fetchSemanticsNodes().isNotEmpty()
        }
        // The answer's picture carries Save, Share and Use as input; the bubble carries its agent · model badge.
        for (action in listOf("save", "share", "reuse")) compose.onNodeWithTag(AiTags.imageAction("out", action)).assertExists()
        compose.onNodeWithTag(ForumTags.modelBadge(3)).assertExists()
        compose.onNodeWithTag(ForumTags.adminBadge(3)).assertExists()
    }

    @Test fun attachedFileShowsAChipAndTurnsTheMicIntoSend() {
        val topics = ForumFixture.richTopics()
        render(topics)
        compose.onNodeWithTag(ForumTags.MIC).assertIsDisplayed()
        compose.runOnIdle { topics.first().chat.attach(ForumFixture.image("pending")) }
        compose.onNodeWithTag(AiTags.chip("pending")).assertExists()
        compose.onNodeWithTag(ForumTags.SEND).assertIsDisplayed()
    }

    @Test fun typingTurnsMicIntoSendLikeBefore() {
        render()
        compose.onNodeWithTag(ForumTags.INPUT).performTextInput("hello")
        compose.onNodeWithTag(ForumTags.SEND).assertIsDisplayed()
    }

    @Test fun streamingEmptyAndErrorStatesStayInTheRichThread() {
        val topics = ForumFixture.richTopics()
        topics.first { it.id == "si" }.chat.sending = true
        topics.first { it.id == "si" }.chat.error = "gateway down"
        render(topics)
        compose.onNodeWithTag(ForumTags.THINKING).assertExists()
        compose.onNodeWithTag(ForumTags.ERROR).assertExists()
        compose.onNodeWithTag(AiTags.ctl("specialist")).performScrollTo().performClick()
        compose.onNodeWithTag(AiTags.specialist("physics")).performClick()
        compose.onNodeWithTag(ForumTags.EMPTY).assertIsDisplayed()
    }

    @Test fun composerToolsAreAbsentWhenTheHostDoesNotAskForThem() {
        // The plain forum composer stays available (additive): no tools = no tool row.
        render(tools = false)
        compose.onAllNodesWithTag(ForumTags.TOOLS_BAR).assertCountEquals(0)
    }

    /** The bottom nav declared in build.json::ui keeps the AI Tools next to the five items it had: nothing was dropped. */
    @Test fun bottomNavKeepsEveryItemAndTheAiTools() {
        assertEquals(listOf("notebook", "cgcs", "si", "tools", "ais", "cost"), Data.bottomNav())
        assertEquals(listOf("tools_models", "tools_pickers", "tools_tasks", "tools_mcp"), Data.pages("tools"))
    }

    @Test fun layoutHoldsInAColumnAboveABottomBar() {
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().testTag("host")) {
                    AiForumChat(
                        ForumFixture.richTopics(), "si", {}, ForumFixture.theme(true), icon = ForumGlyphs, zone = ForumFixture.zone, now = ForumFixture.now,
                        sessions = ForumSessions(ForumFixture.sessionList(), "s-release", {}, {}),
                        tools = ForumTools(ForumFixture.composerDecl, attach = AiAttachHost()),
                    )
                }
            }
        }
        compose.onNodeWithTag(ForumTags.SURFACE).assertIsDisplayed()
    }
}
