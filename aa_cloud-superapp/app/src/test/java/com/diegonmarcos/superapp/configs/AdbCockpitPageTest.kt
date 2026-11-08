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
 * The Status cockpit as drawn: the six steps in dependency order, the top control bar, Auto-fix, the logs; the active mode's row is not
 * dimmed, the others are; a tapped verb reaches the actions; the log shows severity-tinted lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h2000dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbCockpitPageTest : KitPageHarness() {
    private val facts = ChannelFacts(mode = ChannelMode.LOCAL_SERVER, probedAt = 1L, devOptions = true, wirelessDebug = false, onWifi = true)
    private val inputs = AdbCockpitModel.Inputs(facts, AdbCockpitModel.PermsDigest(2, emptyList()))
    private val log = listOf(ChannelLog.Entry(1_000L, "connect", "Pair with code: FAILED"), ChannelLog.Entry(2_000L, "connect", "Developer options: ok"))

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun count(r: ComposeTestRule, tag: String) = r.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun `the chain is drawn in dependency order under the control bar`() {
        showKit { AdbCockpitContent(inputs, "", "", log, CockpitActions()) }
        for (t in listOf("adbc:header", "adbc:bar", "adbc:autofix", "adbc:chain", "adbc:logs")) compose.onNodeWithTag(t).assertIsDisplayed()
        val tops = AdbCockpitModel.STEP_ORDER.map { top("adbc:step:$it") }
        assertEquals("step order", tops.sorted(), tops)
    }

    @Test fun `verbs reach the actions and the active mode row is the only live mode`() {
        val seen = ArrayList<AdbCockpitModel.Do>()
        var fixed = 0
        showKit { AdbCockpitContent(inputs, "", "", log, CockpitActions(act = { seen += it }, autoFix = { fixed++ })) }
        compose.onNodeWithTag("adbc:act:setup:open_wd").performClick()
        compose.onNodeWithTag("adbc:act:local:local_pair").performClick()
        compose.onNodeWithTag("adbc:toggle").performClick()
        compose.onNodeWithTag("adbc:autofix").performClick()
        compose.waitForIdle()
        assertEquals(listOf(AdbCockpitModel.Do.OPEN_WD, AdbCockpitModel.Do.LOCAL_PAIR, AdbCockpitModel.Do.CONNECT), seen)
        assertEquals(1, fixed)
        assertEquals("one master pill", 1, count(compose, "adbc:master"))
    }

    @Test fun `the mode menu offers the three modes and the log is tinted`() {
        val picked = ArrayList<ChannelMode>()
        showKit { AdbCockpitContent(inputs, "", "", log, CockpitActions(pickMode = { picked += it })) }
        compose.onNodeWithTag("adbc:mode").performClick()
        compose.waitForIdle()
        for (m in AdbCockpitModel.SELECTABLE) compose.onNodeWithTag("adbc:mode:${m.id}").assertIsDisplayed()
        compose.onNodeWithTag("adbc:mode:shizuku").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ChannelMode.SHIZUKU), picked)
        assertEquals(1, count(compose, "adbc:log:err")); assertEquals(1, count(compose, "adbc:log:ok"))
    }
}
