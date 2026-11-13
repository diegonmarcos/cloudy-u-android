package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.shellbroker.AuditLog
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1522 the page as drawn: the six sections in the declared order, the two logs collapsed until tapped, one primary action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h2000dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbShellBrokerPageTest : KitPageHarness() {

    private val state = AdbPageState(
        status = ShellStatus(true, false, "none", "", 1_000L, "wireless debugging is off"), wireless = false, paired = true, port = "37123",
        callers = listOf(AdbShellModel.CallerRow("a.store", "3 rules", 5L)),
        audit = listOf(AuditLog.Entry(5L, "a.store", "id -u", "ok", 0, 4, "binder")),
    )

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun count(r: ComposeTestRule, tag: String) = r.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun `sections are drawn in order and the logs start collapsed`() {
        showKit { AdbShellContent(state, AdbPageActions()) }
        val tops = AdbShellModel.ORDER.map { top("adb:section:${it.id}") }
        assertEquals("section order", tops.sorted(), tops)
        compose.onNodeWithTag("adb:body:status").assertIsDisplayed()
        compose.onNodeWithTag("adb:body:callers").assertIsDisplayed()
        assertEquals("audit log collapsed", 0, count(compose, "adb:body:audit"))
        assertEquals("keeper log collapsed", 0, count(compose, "adb:body:keeper_log"))
        assertEquals("no audit rows while collapsed", 0, count(compose, "adb:audit"))
        assertEquals("one primary action", 1, count(compose, "adb:primary"))
    }

    @Test fun `tapping a collapsed header opens it`() {
        showKit { AdbShellContent(state, AdbPageActions()) }
        compose.onNodeWithTag("adb:section:audit").performClick()
        compose.waitForIdle()
        assertTrue(count(compose, "adb:audit") == 1)
        compose.onNodeWithTag("adb:section:audit").performClick()
        compose.waitForIdle()
        assertEquals(0, count(compose, "adb:audit"))
    }
}
