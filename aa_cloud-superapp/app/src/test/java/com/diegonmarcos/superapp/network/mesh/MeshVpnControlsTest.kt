package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.network.MeshIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.diegonmarcos.superapp.ui.KitPageHarness

/**
 * #1120 Status > VPN row and Profile > Start-up, state by state: connected, disconnected by the owner, engine absent,
 * Always-on with and without the privileged channel, and the root-cause regression (the Start-up switches are usable).
 * #1797 the status lights sit above the VPN row, so a tap scrolls to the button first, as a finger would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshVpnControlsTest : KitPageHarness() {
    private val decl = MeshDecl.baked

    private fun show(port: FakePort, host: FakeHost = FakeHost(), tab: String = "status"): MeshStore {
        val store = MeshStore(port, decl, host, exec = { it.run() })
        store.open(tab)   // #1928 "controls" is the former Controls page: it opens the Profile tab on Connection
        store.poll()
        showKit { MeshScreen(store, ticker = false) }
        return store
    }

    // ── Status: Connect / Disconnect / Reconnect ──

    @Test fun `connected - Disconnect and Reconnect are live, Connect is not`() {
        show(FakePort(up = true))
        compose.onNodeWithTag(MeshTags.control("vpn_connect")).assertIsNotEnabled()
        compose.onNodeWithTag(MeshTags.control("vpn_disconnect")).assertIsEnabled()
        compose.onNodeWithTag(MeshTags.control("vpn_reconnect")).assertIsEnabled()
        compose.onNodeWithTag("mesh:vpn:intent").assertTextContains("until you tap Disconnect", substring = true)
    }

    @Test fun `disconnected by the owner - Connect is live and the page says nothing reconnects`() {
        val port = FakePort(up = true); val store = show(port)
        compose.onNodeWithTag(MeshTags.control("vpn_disconnect")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("disconnect"), port.calls); assertFalse(port.intentP.wanted)
        compose.onNodeWithTag(MeshTags.control("vpn_connect")).assertIsEnabled()
        compose.onNodeWithTag(MeshTags.control("vpn_disconnect")).assertIsNotEnabled()
        compose.onNodeWithTag("mesh:vpn:intent").assertTextContains("nothing reconnects until you tap Connect", substring = true)
        store.hashCode()
    }

    @Test fun `Connect re-arms the intent`() {
        val port = FakePort(up = false).also { it.intentP = MeshIntent.Policy(wanted = false) }
        show(port)
        compose.onNodeWithTag(MeshTags.control("vpn_connect")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("connect"), port.calls); assertTrue(port.intentP.wanted)
        compose.onNodeWithTag("mesh:vpn:intent").assertTextContains("until you tap Disconnect", substring = true)
    }

    @Test fun `Reconnect restarts the tunnel through the port`() {
        val port = FakePort(up = true); show(port)
        compose.onNodeWithTag(MeshTags.control("vpn_reconnect")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("reconnect"), port.calls)
    }

    @Test fun `engine absent - the three buttons are disabled and the page does not pretend`() {
        show(FakePort(engine = false))
        for (id in listOf("vpn_connect", "vpn_disconnect", "vpn_reconnect")) compose.onNodeWithTag(MeshTags.control(id)).assertIsNotEnabled()
        compose.onNodeWithTag(MeshTags.control("vpn_always_on")).assertIsNotEnabled()
    }

    @Test fun `the supervisor's last sentence is shown`() {
        show(FakePort(up = false).also { it.note = "retrying shortly" })
        compose.onNodeWithTag("mesh:vpn:note").assertTextContains("retrying shortly", substring = true)
    }

    // ── Status: Always-on ──

    @Test fun `Always-on with a privileged channel - the switch changes Android's setting`() {
        val port = FakePort(up = true).also { it.channel = true }; val host = FakeHost(); val store = show(port, host)
        compose.onNodeWithTag(MeshTags.control("vpn_always_on")).assertIsEnabled().performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("alwayson=true"), port.calls); assertTrue(store.aoView.on == true)
        assertTrue("no deep link when the channel did it", host.events.none { it == "vpn" })
    }

    @Test fun `Always-on without a channel - the reason is on screen and the tap opens Android's VPN settings`() {
        val port = FakePort(up = true); val host = FakeHost(); val store = show(port, host)
        compose.onNodeWithTag(MeshTags.control("vpn_always_on")).assertTextContains("No privileged shell", substring = true)
        compose.onNodeWithTag("mesh:vpn:settings").assertExists()
        compose.onNodeWithTag(MeshTags.control("vpn_always_on")).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(port.calls.isEmpty()); assertEquals(listOf("vpn"), host.events)
        assertTrue(store.notice.startsWith("Always-on: No privileged shell"))
    }

    @Test fun `Always-on shows what Android really reports`() {
        show(FakePort(up = true).also { it.channel = true; it.aoOn = true })
        compose.onNodeWithTag(MeshTags.control("vpn_always_on")).assertTextContains("Always-on VPN is on", substring = true)
    }

    // ── Profile: the Start-up switches (the greyed-out bug) ──

    @Test fun `ROOT CAUSE - on the Profile tab no Start-up switch is disabled, and each acts`() {
        val port = FakePort(up = true).also { it.channel = true }
        val store = show(port, tab = "controls")
        for (id in listOf("auto_boot", "auto_wifi", "auto_mobile", "kill_switch")) {
            compose.onNodeWithTag(MeshTags.control(id)).performScrollTo().assertIsEnabled()
        }
        compose.onNodeWithTag(MeshTags.control("auto_boot")).performClick()
        compose.onNodeWithTag(MeshTags.control("auto_wifi")).performClick()
        compose.onNodeWithTag(MeshTags.control("auto_mobile")).performClick()
        compose.onNodeWithTag(MeshTags.control("kill_switch")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("startup.boot=false", "startup.wifi=false", "startup.mobile=false", "lockdown=true"), port.calls)
        assertEquals(MeshIntent.Policy(onBoot = false, onWifi = false, onMobile = false), store.intent)
        assertTrue(store.aoView.lockdown == true)
    }

    @Test fun `the Start-up switches show ON by default`() {
        val store = show(FakePort(up = true), tab = "controls")
        assertEquals(MeshIntent.Policy(), store.intent)
    }

    @Test fun `kill switch without a channel stays tappable, says why and opens Android's VPN settings`() {
        val host = FakeHost(); val port = FakePort(up = true); val store = show(port, host, tab = "controls")
        compose.onNodeWithTag(MeshTags.control("kill_switch")).performScrollTo().assertIsEnabled().assertTextContains("No privileged shell", substring = true)
        compose.onNodeWithTag(MeshTags.control("kill_switch")).performClick()
        compose.waitForIdle()
        assertTrue(port.calls.isEmpty()); assertEquals(listOf("vpn"), host.events)
        assertTrue(store.notice.startsWith("Kill switch:"))
    }

    @Test fun `without the engine the Start-up switches still work - they are the app's own, not the engine's`() {
        val port = FakePort(engine = false)
        show(port, tab = "controls")
        compose.onNodeWithTag(MeshTags.control("auto_boot")).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("startup.boot=false"), port.calls)
    }
}
