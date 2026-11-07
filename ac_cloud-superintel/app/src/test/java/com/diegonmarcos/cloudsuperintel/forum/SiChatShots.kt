package com.diegonmarcos.cloudsuperintel.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudsuperintel.Data
import com.diegonmarcos.superapp.ai.AiAttachHost
import com.diegonmarcos.superapp.ai.AiForumChat
import com.diegonmarcos.superapp.ai.AiTags
import com.diegonmarcos.superapp.ai.ForumGlyphs
import com.diegonmarcos.superapp.ai.ForumSessions
import com.diegonmarcos.superapp.ai.ForumTools
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom
import com.diegonmarcos.superapp.uiicons.UiIcons
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The SI chat reference screenshots (sessions rail, styled top bar, doodle background, bubbles with receipts and badges, inline
 * images, the composer's AI tool row, and the bottom nav WITH the Tools item): dark + light, phone + tablet, plus the model
 * catalogue the Model button opens. Recorded to src/test/snapshots/si/<device>-<name>.png (no pixel assertion; the committed PNGs
 * are the reference the owner looks at). The bottom nav is built from build.json::ui, so a section dropped from the declaration
 * drops out of the picture too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-420dpi")
class SiChatPhoneShots : SiChatShots("phone")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SiChatTabletShots : SiChatShots("tablet")

abstract class SiChatShots(private val device: String) {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun navEntries(): List<BottomNavEntry> {
        val ui = Data.buildJson().getJSONObject("ui")
        val sections = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("id") }
        val ids = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }
        return ids.map { id -> val s: JSONObject = sections.getValue(id); BottomNavEntry(id, s.getString("label"), UiIcons.painter(s.getString("icon"))) }
    }

    private fun shot(name: String, dark: Boolean, onReady: () -> Unit = {}) {
        ShadowLog.stream = System.out
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ForumFixture.windowColor(dark))) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AiForumChat(
                            topics = ForumFixture.richTopics(), selectedId = "si", onSelect = {},
                            theme = ForumFixture.theme(dark), icon = ForumGlyphs,
                            adminColor = ForumFixture.adminColor(dark), zone = ForumFixture.zone, now = ForumFixture.now,
                            onCall = {}, onInfo = {}, onMore = {},
                            sessions = ForumSessions(ForumFixture.sessionList(), "s-release", {}, {}),
                            tools = ForumTools(ForumFixture.composerDecl, attach = AiAttachHost()),
                        )
                    }
                    NavBarBottom(navEntries(), "si", {}, dark = dark)
                }
            }
        }
        // The pictures decode off the main thread; wait until both are on screen.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(AiTags.image("in")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(AiTags.image("out")).fetchSemanticsNodes().isNotEmpty()
        }
        onReady()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/si/$device-$name.png")
    }

    @Test fun chatDark() = shot("chat-dark", dark = true)
    @Test fun chatLight() = shot("chat-light", dark = false)

    /** The Model button's catalogue (tabs, real model lists, OpenRouter search) over the same chat, dark. */
    @Test fun modelsDark() {
        ShadowLog.stream = System.out
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ForumFixture.windowColor(true))) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AiForumChat(
                            topics = ForumFixture.richTopics(), selectedId = "si", onSelect = {},
                            theme = ForumFixture.theme(true), icon = ForumGlyphs,
                            adminColor = ForumFixture.adminColor(true), zone = ForumFixture.zone, now = ForumFixture.now,
                            sessions = ForumSessions(ForumFixture.sessionList(), "s-release", {}, {}),
                            tools = ForumTools(ForumFixture.composerDecl, attach = AiAttachHost()),
                        )
                    }
                    NavBarBottom(navEntries(), "tools", {}, dark = true)
                }
            }
        }
        compose.onNodeWithTag(AiTags.ctl("model")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(AiTags.CATALOGUE_PAGE).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/si/$device-models-dark.png")
    }
}
