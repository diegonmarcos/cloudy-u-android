package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Status page as drawn: the control card on top, the five group cards in the page's order, the logs last; a tapped verb reaches
 * the actions; the three modes are on the page; the log shows severity-tinted lines. The #1647 layout rules are in AdbStatusPageTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h3200dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbCockpitPageTest : KitPageHarness() {
    private val facts = ChannelFacts(mode = ChannelMode.LOCAL_SERVER, probedAt = 1L, devOptions = true, wirelessDebug = false, onWifi = true)
    private val inputs = AdbCockpitModel.Inputs(facts, AdbCockpitModel.PermsDigest(2, emptyList()))
    private val log = listOf(ChannelLog.Entry(1_000L, "connect", "Pair with code: FAILED"), ChannelLog.Entry(2_000L, "connect", "Developer options: ok"))

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun count(r: ComposeTestRule, tag: String) = r.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun `the cards are drawn in the page's order under the control card`() {
        showKit { AdbCockpitContent(inputs, "", "", log, CockpitActions()) }
        for (t in listOf("adbc:control", "adbc:header", "adbc:bar", "adbc:chain", "adbc:logs")) compose.onNodeWithTag(t).assertIsDisplayed()
        val tops = listOf(top("adbc:control")) + AdbCockpitModel.GROUP_ORDER.map { top("adbc:step:$it") } + top("adbc:logs")
        assertEquals("card order", tops.sorted(), tops)
    }

    @Test fun `verbs reach the actions`() {
        val seen = ArrayList<AdbCockpitModel.Do>()
        showKit { AdbCockpitContent(inputs, "", "", log, CockpitActions(act = { seen += it })) }
        compose.onNodeWithTag("adbc:act:setup:open_wd").performClick()
        compose.onNodeWithTag("adbc:act:local:local_pair").performClick()
        compose.onNodeWithTag("adbc:toggle").performClick()
        compose.onNodeWithTag("adbc:reconnect").performClick()
        compose.waitForIdle()
        assertEquals(listOf(AdbCockpitModel.Do.OPEN_WD, AdbCockpitModel.Do.LOCAL_PAIR, AdbCockpitModel.Do.CONNECT, AdbCockpitModel.Do.RECONNECT), seen)
        assertEquals("one connection label", 1, count(compose, "adbc:master"))
    }

    @Test fun `the mode selector offers the three modes and the log is tinted`() {
        val picked = ArrayList<ChannelMode>()
        showKit { AdbCockpitContent(inputs, "", "", log, CockpitActions(pickMode = { picked += it })) }
        for (m in AdbCockpitModel.SELECTABLE) compose.onNodeWithTag("adbc:mode:${m.id}").assertIsDisplayed()
        compose.onNodeWithTag("adbc:mode:shizuku").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ChannelMode.SHIZUKU), picked)
        assertEquals(1, count(compose, "adbc:log:err")); assertEquals(1, count(compose, "adbc:log:ok"))
    }
}
