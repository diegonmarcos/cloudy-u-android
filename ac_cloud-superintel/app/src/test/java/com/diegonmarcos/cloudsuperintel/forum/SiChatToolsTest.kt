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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudsuperintel.Data
import com.diegonmarcos.superapp.ai.AiAttachHost
import com.diegonmarcos.superapp.ai.AiForumChat
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

    private fun render(topics: List<ForumTopic> = ForumFixture.richTopics(), tools: Boolean = true) {
        topicsHolder = topics
        compose.setContent {
            var persona by remember { mutableStateOf("si") }
            var current by remember { mutableStateOf("s-release") }
            AiForumChat(
                topics = topics, selectedId = persona, onSelect = { persona = it },
                theme = ForumFixture.theme(dark = true), icon = ForumGlyphs,
                adminColor = ForumFixture.adminColor(true), zone = ForumFixture.zone, now = ForumFixture.now,
                sessions = ForumSessions(ForumFixture.sessionList(), current, { current = it.id; opened += it.id }, { created++ }),
                tools = if (tools) ForumTools(ForumFixture.composerDecl, attach = AiAttachHost()) else null,
            )
        }
    }

    @Test fun leftRailIsTheSessionsNotTheTopics() {
        render()
        compose.onNodeWithTag(ForumTags.SESSIONS_RAIL).assertIsDisplayed()
        // The topics rail (Mat, Phy, ...) is gone from the left; the personas are a strip under the header.
        compose.onAllNodesWithTag(ForumTags.RAIL).assertCountEquals(0)
        compose.onNodeWithTag(ForumTags.TOPIC_STRIP).assertExists()
        compose.onNodeWithTag(ForumTags.topic("math")).assertExists()
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
        compose.onNodeWithTag(ForumTags.topic("math")).performClick()
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
    private fun left(tag: String) = rect(tag).left
    private fun top(tag: String) = rect(tag).top
    private fun bottom(tag: String) = rect(tag).bottom
    private fun centerY(tag: String) = rect(tag).center.y

    @Test fun composerCarriesTheWholeAiToolRow() {
        render()
        compose.onNodeWithTag(ForumTags.TOOLS_BAR).assertExists()
        for (ctl in listOf("plus", "task", "specialist", "agent", "model", "effort", "mode", "repos", "skills", "mcp_kg", "mcp_infra", "mcp_user", "mcp_ext", "perf", "more")) {
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

    @Test fun sessionsRailStartsBelowTheTopBarAndTheSpecialistsAreTheSecondRow() {
        render()
        val bar = bottom(ForumTags.TOP_BAR)
        // Row 2: the specialists sit directly under the top bar and above the rail and the thread.
        assertTrue(top(ForumTags.TOPIC_STRIP) >= bar - 1f)
        assertTrue(bottom(ForumTags.TOPIC_STRIP) <= top(ForumTags.SESSIONS_RAIL) + 1f)
        // The sessions rail does NOT reach the top: it starts under the top bar and the specialists.
        assertTrue(top(ForumTags.SESSIONS_RAIL) >= bar)
        assertTrue(top(ForumTags.SESSIONS_RAIL) > top(ForumTags.TOP_BAR))
        assertTrue(top(ForumTags.SESSIONS_RAIL) >= bottom(ForumTags.TOPIC_STRIP) - 1f)
        assertTrue(top(ForumTags.LIST) >= bottom(ForumTags.TOPIC_STRIP) - 1f)
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
        compose.onNodeWithTag(AiTags.TOOLBAR_DIVIDER).assertExists()
        assertTrue(left(AiTags.ctl("specialist")) < left(AiTags.TOOLBAR_DIVIDER) && left(AiTags.TOOLBAR_DIVIDER) < left(AiTags.ctl("agent")))
        // The (+) is not on the toolbar any more.
        assertTrue(centerY(AiTags.ctl("plus")) < r1.top)
    }

    @Test fun toolbarRowTwoIsReposPluginsFourMcpBucketsPerformanceAndMore() {
        render()
        val row = listOf("repos", "skills", "mcp_kg", "mcp_infra", "mcp_user", "mcp_ext", "perf", "more").map { AiTags.ctl(it) }
        val xs = row.map { left(it) }
        assertEquals(xs.sorted(), xs)
        val r2 = rect(AiTags.toolbarRow(2))
        for (tag in row) assertTrue(tag, centerY(tag) in r2.top..r2.bottom)
        // Row 2 is a NEW row under row 1.
        assertTrue(r2.top >= bottom(AiTags.toolbarRow(1)) - 1f)
        // The legacy single MCP chip is gone from the Cloud-SI toolbar (its servers are in the four buckets).
        compose.onAllNodesWithTag(AiTags.ctl("mcp")).assertCountEquals(0)
    }

    @Test fun costIsOnTheTopBarNotOnTheToolbar() {
        render()
        compose.onAllNodesWithTag(AiTags.ctl("usage")).assertCountEquals(0)
        compose.onAllNodesWithTag(AiTags.ctl("cost")).assertCountEquals(0)
        compose.onNodeWithTag(AiTags.ctl("more")).performClick()
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
        compose.onNodeWithTag(AiTags.ctl("skills")).performClick()
        for (id in listOf("principles", "caveman")) compose.onNodeWithTag(AiTags.morePlugin(id)).assertExists()
        for (id in listOf("skills", "memory")) compose.onNodeWithTag(AiTags.more(id)).assertExists()
        compose.onNodeWithText("Cloudy Principals", substring = true).assertExists()
        compose.onAllNodesWithText("Principles", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag(AiTags.morePlugin("headroom")).assertCountEquals(0)
    }

    @Test fun headroomAndRtkAreUnderPluginsPerformance() {
        render()
        compose.onNodeWithTag(AiTags.ctl("perf")).performClick()
        for (id in listOf("headroom", "rtk")) compose.onNodeWithTag(AiTags.morePlugin(id)).assertExists()
        compose.onAllNodesWithTag(AiTags.morePlugin("principles")).assertCountEquals(0)
    }

    @Test fun pluginsLeaveTheMoreSheetButEverythingElseStays() {
        render()
        compose.onNodeWithTag(AiTags.ctl("more")).performClick()
        for (id in listOf("headroom", "rtk", "caveman", "principles")) compose.onAllNodesWithTag(AiTags.morePlugin(id)).assertCountEquals(0)
        for (id in listOf("export", "retry", "undo", "system", "shortcuts", "about")) compose.onNodeWithTag(AiTags.more(id)).assertExists()
    }

    private fun bucket(ctl: String, present: List<String>, absent: List<String>) {
        render()
        compose.onNodeWithTag(AiTags.ctl(ctl)).performClick()
        for (n in present) compose.onNodeWithTag(AiTags.mcp(n)).assertExists()
        for (n in absent) compose.onAllNodesWithTag(AiTags.mcp(n)).assertCountEquals(0)
    }

    @Test fun cloudyInfraMcpsHoldTheOneInfraMcp() = bucket("mcp_infra", listOf("cloudy-c3-mcp"), listOf("cloudy-all-mcp", "google-workspace-mcp"))

    @Test fun cloudyUserFacingMcpsHoldTheNonInfraCloudyOnes() =
        bucket("mcp_user", listOf("cloudy-all-mcp", "cloudy-apps-drive-mcp", "cloudy-cgc-pub-mcp"), listOf("cloudy-c3-mcp", "google-personal-mcp"))

    @Test fun nonCloudyMcpsHoldTheInternetOnes() =
        bucket("mcp_ext", listOf("google-personal-mcp", "google-workspace-mcp"), listOf("cloudy-c3-mcp", "cloudy-all-mcp"))

    @Test fun reposPickerOffersTheDeclaredRepositories() {
        render()
        compose.onNodeWithTag(AiTags.ctl("repos")).performClick()
        for (r in ForumFixture.composerDecl.repos) compose.onNodeWithTag(AiTags.repo(r)).assertExists()
        compose.onNodeWithTag(AiTags.repo("cloudy-t-infra")).performClick()
        assertEquals(setOf("cloudy-t-infra"), topicChat().reposOn)
    }

    @Test fun specialistPickerSwitchesThePersona() {
        render()
        compose.onNodeWithTag(AiTags.ctl("specialist")).performClick()
        for (id in listOf("si", "math", "physics")) compose.onNodeWithTag(AiTags.specialist(id)).assertExists()
        compose.onNodeWithTag(AiTags.specialist("math")).performClick()
        compose.onNodeWithText("Proofs", substring = true).assertIsDisplayed()
    }

    /** The composer controls of the previous layout, each with a home in the new one: nothing was dropped. */
    @Test fun noPriorComposerControlWasLost() {
        val prior = listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more")
        val home = mapOf("mcp" to listOf("mcp_kg", "mcp_infra", "mcp_user", "mcp_ext"))
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
        compose.onNodeWithTag(ForumTags.topic("physics")).performClick()
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
