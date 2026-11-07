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
import androidx.compose.ui.test.assertCountEquals
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The regression guard for what #1563 removed (rule 16: never remove): on the SI tab the AI tooling stays in the composer
 * ((+) attach, Task, Agent, Model, Effort, Mode, MCP, More; the model catalogue with OpenRouter search; image in and out inline),
 * and the left rail is the user's chat SESSIONS, not topic categories. Rendered from the real libs:ai surface under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-420dpi")
class SiChatToolsTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var created = 0

    private fun render(topics: List<ForumTopic> = ForumFixture.richTopics(), tools: Boolean = true) {
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
        compose.onNodeWithText("Release planning").assertIsDisplayed()
        compose.onNodeWithTag(ForumTags.topic("math")).performClick()
        compose.onNodeWithText("Proofs").assertIsDisplayed()
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

    @Test fun composerCarriesTheWholeAiToolRow() {
        render()
        compose.onNodeWithTag(ForumTags.TOOLS_BAR).assertExists()
        for (ctl in listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more")) {
            compose.onNodeWithTag(AiTags.ctl(ctl)).assertExists()
        }
    }

    @Test fun plusMenuOffersFilesImageAndCamera() {
        render()
        compose.onNodeWithTag(AiTags.ctl("plus")).performClick()
        for (kind in listOf("files", "images", "camera")) compose.onNodeWithTag(AiTags.attach(kind)).assertExists()
        compose.onNodeWithTag(AiTags.NEW_CHAT).assertExists()
    }

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
