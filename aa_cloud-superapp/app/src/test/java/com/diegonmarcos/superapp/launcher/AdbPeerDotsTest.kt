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
import com.diegonmarcos.superapp.shellclient.AdbLink
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
 *   D4 (#1550) the dot is the adb TRANSPORT, not the broker: broker up + Wireless debugging off is red
 *   D3 (#1522) the strip model's ADB dot follows the broker's live status: green only on a successful round trip
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbPeerDotsTest : KitPageHarness() {

    private val up = ShellStatus(true, true, "embedded-adb", "ok", 1_000L, adb = AdbLink.CONNECTED)
    private val down = ShellStatus(true, false, "none", "", 1_000L, "wireless debugging is off", adb = AdbLink.WIRELESS_OFF)
    /** #1550 the reported case: the broker answers through the local server, Wireless debugging is off. */
    private val brokerOnly = ShellStatus(true, true, "local-server", "ok", 1_000L, adb = AdbLink.WIRELESS_OFF)

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
        assertEquals("#1550 broker up, wireless debugging off: not green", listOf(AdbPeerDots.DOWN), AdbPeerDots.tints(brokerOnly))
        assertTrue(AdbPeerDots.describe(brokerOnly).contains("Wireless debugging is off") && AdbPeerDots.describe(brokerOnly).contains("shell broker up"))
        assertEquals("unknown transport is grey", listOf(AdbPeerDots.OFF), AdbPeerDots.tints(ShellStatus(true, true, "x", "", 1L)))
    }

    /** #1721 WebS / ADB / SSHd: no red/green; filled = connected, outline = disconnected (the real verdict, never a proxy). */
    @Test fun `D5 connected is a filled dot, disconnected an outline dot, no red or green`() {
        for (t in listOf(AdbPeerDots.UP, AdbPeerDots.DOWN, AdbPeerDots.OFF)) {
            val c = androidx.compose.ui.graphics.Color(t)
            assertTrue("tint ${Integer.toHexString(t)} is neutral (white), not red/green", c.red == c.green && c.green == c.blue)
        }
        assertTrue(AdbPeerDots.isFilled(AdbPeerDots.tints(up).single()))
        assertTrue(!AdbPeerDots.isFilled(AdbPeerDots.tints(down).single()))
        assertTrue("brokerOnly (wireless debugging off) is not filled", !AdbPeerDots.isFilled(AdbPeerDots.tints(brokerOnly).single()))
        assertTrue("not checked is not filled", !AdbPeerDots.isFilled(AdbPeerDots.tint(null)))
        for (st in listOf(com.diegonmarcos.superapp.network.LocalDoors.State.ON)) assertTrue(AdbPeerDots.isFilled(StripDoors.dot(st)))
        for (st in listOf(com.diegonmarcos.superapp.network.LocalDoors.State.OFF, com.diegonmarcos.superapp.network.LocalDoors.State.UNKNOWN)) assertTrue(!AdbPeerDots.isFilled(StripDoors.dot(st)))
    }

    @Test fun `D5b the drawn dot says connected or disconnected`() {
        val model = StatusStripModel(compose.activity)
        model.noteBroker(up, pin = true)
        showKit { Column(Modifier.wrapContentSize()) { StatusStripBody(model, compose.activity) } }
        val sd = compose.onNodeWithTag("strip:adb:dot:0", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { null }
        assertEquals("connected", sd)
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

    @Test fun `D3 the dot colour follows the live status`() {
        fun dot(st: ShellStatus): List<Int>? {
            val m = StatusStripModel(compose.activity)
            m.noteBroker(st, pin = true)
            return m.chips.getValue(com.diegonmarcos.superapp.network.NetworkSections.ADB).peerDots
        }
        assertEquals(listOf(AdbPeerDots.UP), dot(up))
        assertEquals(listOf(AdbPeerDots.DOWN), dot(down))
        // a reason alone never turns it green: only a successful round trip does
        assertEquals(listOf(AdbPeerDots.DOWN), dot(ShellStatus(true, false, "embedded-adb", "ok", 1_000L, "id -u failed", adb = AdbLink.UNREACHABLE)))
        assertEquals("#1550 broker up through the local server, wireless debugging off", listOf(AdbPeerDots.DOWN), dot(brokerOnly))
    }
}
