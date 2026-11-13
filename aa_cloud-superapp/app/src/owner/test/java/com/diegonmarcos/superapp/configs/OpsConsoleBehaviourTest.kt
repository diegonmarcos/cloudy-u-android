package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.diegonmarcos.superapp.perms.PermCatalogue
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import com.diegonmarcos.superapp.perms.StatusFilter
import com.diegonmarcos.superapp.shellclient.AdbLink
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1550 behaviour of the redesigned pages:
 *   T1 a destructive command (declared rule) is held for confirmation and never runs until confirmed; Cancel drops it
 *   T2 a safe command runs at once and gets an entry with timestamp, status and duration
 *   T3 history buttons recall commands; a new tab is selectable; the session header shows the true adb state, not the broker's
 *   P1 a KPI tile filters the list by its state, a second tap clears it
 *   P2 Select shows the bulk bar; tapping rows counts them; Clear empties the selection
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h4000dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpsConsoleBehaviourTest : KitPageHarness() {
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun has(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun entries() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("term entry") { n ->
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("term:entry:") == true }, useUnmergedTree = true).fetchSemanticsNodes().size

    private val brokerOnly = ShellStatus(true, true, "local-server", "ok", 1_000L, adb = AdbLink.WIRELESS_OFF)

    @Test fun `T1 destructive commands wait for confirmation`() {
        showKit { AdbTerminal(brokerOnly, onReconnect = {}) }
        compose.onNodeWithTag("term:input").performTextInput("pm clear com.example.x")
        compose.onNodeWithTag("term:run").performClick(); compose.waitForIdle()
        assertEquals("held, not run", 1, count("term:confirm"))
        assertEquals(0, entries())
        assertTrue(has("wipes an app's data"))
        compose.onNodeWithTag("term:confirm-cancel").performClick(); compose.waitForIdle()
        assertEquals(0, count("term:confirm")); assertEquals(0, entries())
    }

    @Test fun `T2 a safe command runs with timestamp, status and duration`() {
        showKit { AdbTerminal(brokerOnly, onReconnect = {}) }
        assertEquals("empty state", 1, count("term:empty"))
        compose.onNodeWithTag("term:input").performTextInput("getprop ro.build.version.sdk")
        compose.onNodeWithTag("term:run").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("term:status:1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, entries())
        assertTrue("the command line is shown", has("$ getprop ro.build.version.sdk"))
        assertEquals(0, count("term:confirm"))
    }

    @Test fun `T3 header truth, history and tabs`() {
        showKit { AdbTerminal(brokerOnly, onReconnect = {}) }
        assertTrue("the header says adb is off although the broker is up", has("ADB: Wireless debugging is off"))
        assertTrue(has("via local-server"))
        compose.onNodeWithTag("term:input").performTextInput("id")
        compose.onNodeWithTag("term:run").performClick(); compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("term:run"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("term:hist-up").performClick(); compose.waitForIdle()
        assertTrue("recalled", has("id"))
        compose.onNodeWithTag("term:newtab").performClick(); compose.waitForIdle()
        assertEquals(1, count("term:tab:2"))
        assertEquals("the new tab starts empty", 1, count("term:empty"))
    }

    private val cat = PermCatalogue.parse(File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json").readText())
    private val rows = cat.entries.mapIndexed { i, e -> PermRowState(e, PermStatus(if (i % 2 == 0) PermState.GRANTED else PermState.DENIED, "")) }

    private fun perms() {
        val st = mutableStateOf(StatusFilter.ALL)
        showKit {
            Column {
                PermsSummaryBar(rows, compose.activity, st) {}
                PermsCenterSection(rows = rows, highlight = null, channelName = null, adbResults = emptyList(), adbRunning = false, shellNote = null,
                    onClearHighlight = {}, onGrant = {}, onShell = {}, onRunAdb = {}, onOpenAdbShell = {}, ctx = compose.activity, statusState = st, wide = false)
            }
        }
    }

    private fun rowsShown() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("perm row") { n ->
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("perms:row:") == true }, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun `P1 a KPI tile filters by its state`() {
        perms()
        val all = rowsShown()
        assertEquals(cat.entries.size, all)
        compose.onNodeWithTag("perms:kpi:granted").performClick(); compose.waitForIdle()
        val granted = rowsShown()
        assertTrue("filtered: $granted of $all", granted in 1 until all)
        compose.onNodeWithTag("perms:kpi:granted").performClick(); compose.waitForIdle()
        assertEquals("second tap clears", all, rowsShown())
    }

    @Test fun `P2 multi-select with a bulk bar`() {
        perms()
        assertEquals(0, count("perms:bulkbar"))
        compose.onNodeWithTag("perms:select").performClick(); compose.waitForIdle()
        assertEquals(1, count("perms:bulkbar"))
        val ids = cat.entries.take(2).map { it.id }
        for (id in ids) { compose.onNodeWithTag("perms:selectbox:$id", useUnmergedTree = true).performClick(); compose.waitForIdle() }
        assertTrue(has("2 selected"))
        compose.onNodeWithTag("perms:select-clear").performClick(); compose.waitForIdle()
        assertTrue(has("0 selected"))
    }
}
