package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1521 the home screen's top status shows ADB as ONE dot: the central broker's live status.
 *   D1 green only for a verified (armed) status; red for a failed check; grey before the first answer
 *   D2 the strip draws exactly one dot under the ADB label, and the spoken form carries the reason
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbPeerDotsTest : KitPageHarness() {

    private val up = ShellStatus(true, true, "embedded-adb", "ok", 1_000L)
    private val down = ShellStatus(true, false, "none", "", 1_000L, "wireless debugging is off")

    @Before fun petsOff() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LauncherSettingsPrefs(app).setToggle("pets_anim", false)
        ShellFxState.refresh(app)
    }

    @Test fun `D1 green only when verified`() {
        assertEquals(listOf(AdbPeerDots.UP), AdbPeerDots.tints(up))
        assertEquals(listOf(AdbPeerDots.DOWN), AdbPeerDots.tints(down))
        assertEquals(listOf(AdbPeerDots.OFF), AdbPeerDots.tints(null))
        assertTrue(AdbPeerDots.describe(down).contains("wireless debugging is off"))
    }

    @Test fun `D2 one dot under ADB`() {
        val model = StatusStripModel(compose.activity)
        model.noteBroker(down, pin = true)
        showKit { Column(Modifier.wrapContentSize()) { StatusStripBody(model, compose.activity) } }
        val label = compose.onNodeWithTag("strip:adb").fetchSemanticsNode().boundsInRoot
        val d0 = compose.onNodeWithTag("strip:adb:dot:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the dot sits under the ADB label", d0.top >= label.top && d0.bottom <= label.bottom + 1f)
        assertEquals("exactly one", 0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("strip:adb:dot:1"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
