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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.superapp.ai.AiForumChat
import com.diegonmarcos.superapp.ai.ForumGlyphs
import com.diegonmarcos.superapp.ai.ForumTags
import com.diegonmarcos.superapp.ai.ForumTopic
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom
import com.diegonmarcos.superapp.uiicons.UiIcons
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The forum/topics chat surface behaviour: rail selection, bubbles, receipts, date pills, composer toggle, bottom nav present. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-420dpi")
class ForumChatTest {
    @get:Rule val compose = createComposeRule()

    private fun render(topics: List<ForumTopic> = ForumFixture.topics(), start: String = "si") {
        compose.setContent {
            var sel by remember { mutableStateOf(start) }
            AiForumChat(
                topics = topics, selectedId = sel, onSelect = { sel = it },
                theme = ForumFixture.theme(dark = true), icon = ForumGlyphs,
                adminColor = ForumFixture.adminColor(true), zone = ForumFixture.zone, now = ForumFixture.now,
            )
        }
    }

    @Test fun railSelectionSwitchesThread() {
        render()
        compose.onNodeWithText("# General").assertIsDisplayed()
        compose.onNodeWithTag(ForumTags.topic("math")).performClick()
        compose.onNodeWithText("# Math").assertIsDisplayed()
    }

    @Test fun incomingAndOutgoingBubblesRender() {
        render()
        // index 0 user (outgoing), index 1 assistant (incoming, opens its group -> Admin badge + sender name).
        compose.onNodeWithTag(ForumTags.bubble(0)).assertIsDisplayed()
        compose.onNodeWithTag(ForumTags.bubble(1)).assertIsDisplayed()
        compose.onNodeWithTag(ForumTags.adminBadge(1)).assertIsDisplayed()
        compose.onNodeWithText("SI").assertIsDisplayed()
    }

    @Test fun readReceiptOnAnsweredOutgoing() {
        render()
        // Both user messages are answered -> READ ticks (contentDescription "Read").
        assertTrue(compose.onAllNodesWithContentDescription("Read").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun deliveredReceiptOnUnansweredOutgoing() {
        render(start = "math")
        assertTrue(compose.onAllNodesWithContentDescription("Delivered").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun dateSeparatorsGroupByDay() {
        render()
        // now = 2026-09-20: the 19th is "Yesterday", the 20th "Today".
        compose.onNodeWithTag(ForumTags.separator("Yesterday")).assertIsDisplayed()
        compose.onNodeWithTag(ForumTags.separator("Today")).assertIsDisplayed()
    }

    @Test fun composerShowsMicWithNoTextAndSendWithText() {
        render()
        compose.onNodeWithTag(ForumTags.MIC).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(ForumTags.INPUT).performTextInput("hello")
        compose.onNodeWithTag(ForumTags.SEND).assertIsDisplayed()
    }

    @Test fun bottomNavRemainsPresentBelowTheForum() {
        val entries = listOf(
            BottomNavEntry("notebook", "Notebook", UiIcons.painter("menu_book")),
            BottomNavEntry("si", "SI", UiIcons.painter("auto_awesome")),
            BottomNavEntry("cgcs", "CGCs", UiIcons.painter("hub")),
        )
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    var sel by remember { mutableStateOf("si") }
                    AiForumChat(ForumFixture.topics(), sel, { sel = it }, ForumFixture.theme(true), icon = ForumGlyphs, zone = ForumFixture.zone, now = ForumFixture.now)
                }
                Box(Modifier.fillMaxWidth().testTag("forum_test_bottomnav")) {
                    NavBarBottom(entries, "si", {}, dark = true)
                }
            }
        }
        // The forum lives in the content area AND the bottom nav bar is still present below it.
        compose.onNodeWithTag(ForumTags.SURFACE).assertIsDisplayed()
        compose.onNodeWithTag("forum_test_bottomnav").assertIsDisplayed()
    }
}
