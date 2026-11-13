package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.configs.AdbPrereqs.Action
import com.diegonmarcos.superapp.configs.AdbPrereqs.Owner
import com.diegonmarcos.superapp.configs.AdbPrereqs.Status
import com.diegonmarcos.superapp.shellbroker.Allowlist
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1676 ADB Shell > Perms lists ONLY the ADB Shell prerequisites, in chain order:
 *   P1 exactly the declared chain, in order, human steps before automatic ones (adb modes and Shizuku mode)
 *   P2 every row has an owner, at least one action and a why; human rows deep-link to the exact Settings page
 *   P3 status comes from the real reads it is given: null = unknown, never on; adb on only from the round-trip fact
 *   P4 the first failing step is highlighted (and only it)
 *   P5 Grant via adb only once a channel is up, and every grant line is one the shell broker allows the SuperApp to run
 *   P6 parity: every row the previous tab showed (the Perms catalogue) is still reachable in Configs > Perms, and the link is on the page
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h3200dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbPermsChainTest : KitPageHarness() {
    private val pkg = "com.diegonmarcos.superapp"
    private fun facts(mode: ChannelMode = ChannelMode.LOCAL_SERVER, dev: Boolean? = true, wd: Boolean? = true, wifi: Boolean? = true,
                      notif: Boolean? = true, paired: Boolean = true, connected: Boolean = true, server: Boolean = true) =
        ChannelFacts(mode = mode, probedAt = 1L, devOptions = dev, wirelessDebug = wd, onWifi = wifi, notificationsAllowed = notif,
            adbPaired = paired, adbConnected = connected, serverRunning = server)
    private val allOn = AdbPrereqs.Live(true, true, true)
    private fun ids(r: List<AdbPrereqs.Row>) = r.map { it.id }

    @Test fun `P1 exactly the declared chain in order, human first`() {
        assertEquals(AdbPrereqs.ADB_CHAIN, ids(AdbPrereqs.rows(facts(), allOn, pkg)))
        assertEquals(AdbPrereqs.ADB_CHAIN - "local-server", ids(AdbPrereqs.rows(facts(mode = ChannelMode.EMBEDDED_ONLY), allOn, pkg)))
        assertEquals(AdbPrereqs.SHIZUKU_CHAIN, ids(AdbPrereqs.rows(facts(mode = ChannelMode.SHIZUKU), allOn, pkg)))
        for (m in listOf(ChannelMode.LOCAL_SERVER, ChannelMode.EMBEDDED_ONLY, ChannelMode.SHIZUKU)) {
            val owners = AdbPrereqs.rows(facts(mode = m), allOn, pkg).map { it.owner }
            assertEquals("human steps come first in $m", owners.sortedBy { if (it == Owner.HUMAN) 0 else 1 }, owners)
        }
        // nothing ADB Shell does not need: no location / nearby Wi-Fi / camera etc.
        val all = (AdbPrereqs.ADB_CHAIN + AdbPrereqs.SHIZUKU_CHAIN).joinToString()
        for (no in listOf("location", "nearby", "camera", "contacts", "sms")) assertFalse(no, all.contains(no))
    }

    @Test fun `P2 owner, action and why on every row, human rows deep-link to Settings`() {
        val r = AdbPrereqs.rows(facts(dev = false), allOn, pkg)
        for (x in r) { assertTrue(x.id, x.actions.isNotEmpty()); assertTrue(x.id, x.why.isNotBlank()) }
        fun open(id: String) = (r.first { it.id == id }.actions.first() as Action.Open).setup
        assertEquals(com.diegonmarcos.superapp.adbdebug.SetupAction.OPEN_ABOUT_PHONE, open("dev-options"))
        assertEquals(com.diegonmarcos.superapp.adbdebug.SetupAction.OPEN_WIRELESS_DEBUGGING, open("wireless-debugging"))
        assertEquals(com.diegonmarcos.superapp.adbdebug.SetupAction.PAIR, open("paired"))
        assertEquals(Owner.HUMAN, r.first { it.id == "dev-options" }.owner)
        assertEquals(Owner.HUMAN, r.first { it.id == "wireless-debugging" }.owner)
        assertEquals(Owner.AUTO, r.first { it.id == "adb-connected" }.owner)
        assertTrue(r.first { it.id == "write-secure-settings" }.actions.any { it is Action.Grant })
    }

    @Test fun `P3 status from real reads only`() {
        val r = AdbPrereqs.rows(facts(dev = null, connected = false), AdbPrereqs.Live(null, false, null), pkg).associateBy { it.id }
        assertEquals(Status.UNKNOWN, r.getValue("dev-options").status)
        assertEquals(Status.OFF, r.getValue("adb-connected").status)
        assertEquals(Status.UNKNOWN, r.getValue("write-secure-settings").status)
        assertEquals(Status.OFF, r.getValue("dump").status)
        assertEquals(Status.UNKNOWN, r.getValue("battery").status)
        // paired but no round trip: still off
        assertEquals(Status.OFF, AdbPrereqs.rows(facts(paired = true, connected = false), allOn, pkg).first { it.id == "adb-connected" }.status)
    }

    @Test fun `P4 the first failing step is highlighted`() {
        val rows = AdbPrereqs.rows(facts(wd = false, connected = false), allOn, pkg)
        assertEquals("wireless-debugging", AdbPrereqs.firstFailing(rows)?.id)
        assertNull(AdbPrereqs.firstFailing(AdbPrereqs.rows(facts(), allOn, pkg)))
        showKit { AdbPermsChain(rows, null, {}, {}) }
        assertEquals(1, compose.onAllNodes(hasTestTag("adb:prereq:first-fail"), useUnmergedTree = true).fetchSemanticsNodes().size)
        for (id in ids(rows)) compose.onNodeWithTag("adb:prereq:$id").assertExists()
    }

    @Test fun `P5 grant via adb only with a channel up, and every line is allowed`() {
        val down = AdbPrereqs.rows(facts(connected = false, server = false), AdbPrereqs.Live(false, false, false), pkg)
        assertFalse(AdbPrereqs.canGrant(down))
        val up = AdbPrereqs.rows(facts(), AdbPrereqs.Live(false, false, false), pkg)
        assertTrue(AdbPrereqs.canGrant(up))
        val list = Allowlist.parse(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())
        val ctx = RuntimeEnvironment.getApplication()
        val entries = (up + AdbPrereqs.rows(facts(mode = ChannelMode.SHIZUKU), allOn, pkg)).flatMap { it.actions }.filterIsInstance<Action.Grant>().map { it.entry }.toSet()
        assertEquals(AdbPrereqs.GRANT_ENTRIES.toSet(), entries)
        val cat = com.diegonmarcos.superapp.perms.Perms.catalogue(ctx).entries.associateBy { it.id }
        val vars = com.diegonmarcos.superapp.perms.Perms.vars(ctx) + ("pkg" to ShellBroker.SELF)
        for (id in entries) {
            val e = cat[id] ?: error("$id is not a Configs > Perms row")
            val lines = com.diegonmarcos.superapp.perms.AdbScript.lines(listOf(e), vars)
            assertTrue("$id has no adb line", lines.isNotEmpty())
            for (l in lines) assertTrue("refused: ${l.command}", list.decide(ShellBroker.SELF, l.command) is Allowlist.Decision.Allow)
        }
        var ran: Action? = null
        showKit { AdbPermsChain(up, null, { ran = it }, {}) }
        compose.onNodeWithTag("adb:prereq:write-secure-settings:act0").performClick()
        assertTrue(ran is Action.Grant)
    }

    @Test fun `P6 removed rows stay reachable in Configs Perms`() {
        val ctx = RuntimeEnvironment.getApplication()
        val catalogue = readPermRows(ctx, null)
        val previous = pageRows(ctx, catalogue).filter { it.counted }.map { it.entry.id }
        val inConfigs = com.diegonmarcos.superapp.perms.Perms.catalogue(ctx).entries.map { it.id }.toSet()
        for (id in previous) assertTrue("$id no longer reachable in Configs > Perms", id in inConfigs)
        var opened = false
        showKit { AdbPermsChain(AdbPrereqs.rows(facts(), allOn, pkg), null, {}, { opened = true }) }
        compose.onNodeWithTag("adb:perms-open").performClick()
        assertTrue(opened)
    }
}
