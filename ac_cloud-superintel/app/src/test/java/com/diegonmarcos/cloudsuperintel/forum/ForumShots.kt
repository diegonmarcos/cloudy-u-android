package com.diegonmarcos.cloudsuperintel.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.diegonmarcos.superapp.ai.AiForumChat
import com.diegonmarcos.superapp.ai.ForumGlyphs
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * #1563 the forum/topics chat reference screenshots: dark + light, phone + tablet (two-pane). Rendered from the real
 * libs:ai surface (AiForumChat) over the seeded topics, recorded to src/test/snapshots/forum/<name>.png (roborazzi
 * record, no pixel assertion — as ab_cloud-libs-shared/ui-baseline/shots does). The committed PNGs are the reference.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-420dpi")
class ForumPhoneShots : ForumShots("phone")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ForumTabletShots : ForumShots("tablet")

abstract class ForumShots(private val device: String) {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        ShadowLog.stream = System.out
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/snapshots/forum/$device-$name.png")
    }

    private fun forum(dark: Boolean) = shot(if (dark) "dark" else "light") {
        Box(Modifier.fillMaxSize().background(ForumFixture.windowColor(dark))) {
            var sel by remember { mutableStateOf("si") }
            AiForumChat(
                topics = ForumFixture.topics(), selectedId = sel, onSelect = { sel = it },
                theme = ForumFixture.theme(dark), icon = ForumGlyphs,
                adminColor = ForumFixture.adminColor(dark), zone = ForumFixture.zone, now = ForumFixture.now,
                onNewTopic = {}, onCall = {}, onInfo = {}, onMore = {}, onAttach = {},
            )
        }
    }

    @Test fun dark() = forum(dark = true)
    @Test fun light() = forum(dark = false)
}
