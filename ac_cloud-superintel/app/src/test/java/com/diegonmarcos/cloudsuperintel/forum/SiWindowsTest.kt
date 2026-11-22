package com.diegonmarcos.cloudsuperintel.forum

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudsuperintel.ui.WindowTags
import com.diegonmarcos.cloudsuperintel.ui.siWindows
import com.diegonmarcos.superapp.ai.AiAttachHost
import com.diegonmarcos.superapp.ai.AiForumChat
import com.diegonmarcos.superapp.ai.ForumGlyphs
import com.diegonmarcos.superapp.ai.ForumSessions
import com.diegonmarcos.superapp.ai.ForumTags
import com.diegonmarcos.superapp.ai.ForumTools
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1854 The top-right Cost, Context and Files icons open the app's own windows (a dialog with a title, a close button and a dense body),
 * not the shared text dialogs; without windows the shared dialog stays (additive). Rendered from the real libs:ai surface under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-420dpi")
class SiWindowsTest {
    @get:Rule val compose = createComposeRule()

    private fun render(own: Boolean) {
        val topics = ForumFixture.richTopics()
        compose.setContent {
            var current by remember { mutableStateOf("s-release") }
            AiForumChat(
                topics = topics, selectedId = "si", onSelect = {},
                theme = ForumFixture.theme(dark = true), icon = ForumGlyphs,
                adminColor = ForumFixture.adminColor(true), zone = ForumFixture.zone, now = ForumFixture.now,
                sessions = ForumSessions(ForumFixture.sessionList(), current, { current = it.id }, {}),
                tools = ForumTools(ForumFixture.composerDecl, attach = AiAttachHost(), windows = if (own) siWindows(null) else null),
            )
        }
    }

    @Test fun contextOpensItsOwnWindowWithTheStackedBarAndEverySlice() {
        render(own = true)
        compose.onNodeWithTag(ForumTags.CONTEXT).performClick()
        compose.onNodeWithTag(WindowTags.CONTEXT).assertIsDisplayed()
        compose.onNodeWithTag(WindowTags.CTX_BAR).assertIsDisplayed()
        for (id in listOf("system", "tools", "skills", "memory", "messages", "files", "free")) compose.onNodeWithTag(WindowTags.CTX_ROW + id).assertExists()
        compose.onAllNodesWithTag(ForumTags.DIALOG).assertCountEquals(0)
        compose.onNodeWithTag(WindowTags.CLOSE).performClick()
        compose.onAllNodesWithTag(WindowTags.CONTEXT).assertCountEquals(0)
    }

    @Test fun filesOpensItsOwnWindowWithTheFiltersAndTheRows() {
        render(own = true)
        compose.onNodeWithTag(ForumTags.FILES).performClick()
        compose.onNodeWithTag(WindowTags.FILES).assertIsDisplayed()
        for (f in listOf("all", "attached", "generated", "notebook")) compose.onNodeWithTag(WindowTags.FILE_FILTER + f).assertExists()
        compose.onNodeWithTag(WindowTags.FILE_ROW + "quarterly-report.pdf").assertExists()
        compose.onNodeWithTag(WindowTags.FILE_FILTER + "generated").performClick()
        compose.onAllNodesWithTag(WindowTags.FILE_ROW + "quarterly-report.pdf").assertCountEquals(0)
        compose.onNodeWithTag(WindowTags.FILE_ROW + "summary.md").assertExists()
    }

    @Test fun costOpensItsWindowAndCloses() {
        render(own = true)
        compose.onNodeWithTag(ForumTags.USAGE).performClick()
        compose.onNodeWithTag(WindowTags.COST).assertIsDisplayed()
        compose.onNodeWithTag(WindowTags.CLOSE).performClick()
        compose.onAllNodesWithTag(WindowTags.COST).assertCountEquals(0)
    }

    @Test fun withoutWindowsTheSharedDialogStays() {
        render(own = false)
        compose.onNodeWithTag(ForumTags.CONTEXT).performClick()
        compose.onNodeWithTag(ForumTags.DIALOG).assertExists()
        compose.onAllNodesWithTag(WindowTags.CONTEXT).assertCountEquals(0)
    }
}
