package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.launcher.PermAccess.Sensor
import com.diegonmarcos.superapp.launcher.PermAccess.SensorState
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import com.diegonmarcos.superapp.shellclient.ShellResult as SR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1673 the Permission Access Status row in the home status block.
 *   P1 the row sits under the resources line (RAM / storage / CPU / battery) on the date's line, right-aligned (#1721)
 *   P2 status-block parity: every element the strip drew before is still drawn
 *   P3 the watch registers its callbacks only while started (visible) and releases all of them when stopped (hidden)
 *   P4 the strip's lifecycle starts the watch on ON_START and releases it on ON_STOP
 *   P5 the dashboard draws every group, the +N overflow, the empty state and the no-access state with its Configs > Perms button
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermAccessStripTest : KitPageHarness() {

    @Before fun petsOff() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LauncherSettingsPrefs(app).setToggle("pets_anim", false)
        ShellFxState.refresh(app)
    }

    private fun showStrip(): StatusStripModel {
        val model = StatusStripModel(compose.activity)
        showKit { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.wrapContentSize()) { StatusStripBody(model, compose.activity) } }
        return model
    }

    private fun b(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun `P1 the row is under the resources line, on the date line`() {
        showStrip()
        val row = b("permaccess:row")
        val res = listOf("strip:ram", "strip:storage", "strip:cpu").map { b(it) }
        // #1721 the row sits under the resources line, never on it (on a camera-cutout phone it shares the date's line: StatusStripComposeTest S1c)
        val resBottom = res.maxOf { it.bottom }
        assertTrue("row top ${row.top} is above the resources line bottom $resBottom", row.top >= resBottom - 0.5f)
        val clock = b("strip:clock")
        assertTrue("row ${row.left} runs into the clock ${clock.right}", row.left >= clock.right)
        val rightEdge = maxOf(res.maxOf { it.right }, b("strip:battery").right)
        assertEquals("row is right-aligned with the status block", rightEdge, row.right, 3f * compose.activity.resources.displayMetrics.density)
        for (s in Sensor.values()) assertTrue(compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("icon ${s.name}") { n ->
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("permaccess:${s.name.lowercase()}:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().size == 1)
    }

    @Test fun `P2 status block parity`() {
        val model = showStrip()
        for (t in listOf("strip:ram", "strip:storage", "strip:cpu", "strip:battery", "strip:clock", "strip:row")) compose.onNodeWithTag(t, useUnmergedTree = true).assertExists()
        for (id in model.chips.keys) compose.onNodeWithTag("strip:$id", useUnmergedTree = true).assertExists()
    }

    @Test fun `P3 callbacks only while visible, all released when hidden`() {
        val w = PermAccessWatch(compose.activity, brokerRead = null)
        assertEquals(0, w.registered)
        assertFalse(w.running)
        compose.runOnUiThread { w.start() }
        assertTrue(w.running)
        assertTrue("no platform callback was registered on start", w.registered >= 1)
        // Without WATCH_APPOPS the launcher cannot see other apps' location use: never claimed idle.
        assertEquals(SensorState.UNKNOWN, w.location)
        compose.runOnUiThread { w.start() } // idempotent
        val n = w.registered
        compose.runOnUiThread { w.stop() }
        assertFalse(w.running)
        assertEquals("a callback survived stop()", 0, w.registered)
        for (s in Sensor.values()) assertEquals(SensorState.UNKNOWN, w.state(s))
        compose.runOnUiThread { w.start() }
        assertEquals(n, w.registered)
        compose.runOnUiThread { w.stop() }
    }

    @Test fun `P4 the strip lifecycle starts and releases the watch`() {
        val model = StatusStripModel(compose.activity)
        showKit { StripLifecycle(model, onStart = { model.start() }, onResume = {}, onStop = { model.stop() }) }
        assertTrue(model.permAccess.running)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse("the watch kept running behind another app", model.permAccess.running)
        assertEquals(0, model.permAccess.registered)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue(model.permAccess.running)
    }

    @Test fun `P5 dashboard groups, overflow, empty and no-access`() {
        val now = System.currentTimeMillis()
        val uses = (1..5).map { PermAccess.Use("camera", "com.example.p$it", 1, now - it * 60_000L, false) }
        var dash by androidx.compose.runtime.mutableStateOf<PermAccess.Dashboard>(PermAccess.dashboard(uses, now))
        var perms = 0
        showKit { androidx.compose.runtime.key(dash) { DashboardSheet(compose.activity, { null }, onPerms = { perms++ }, initial = dash) } }
        for (g in PermAccess.GROUPS) compose.onNodeWithTag("permaccess:group:${g.id}", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("permaccess:overflow:camera", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("permaccess:group:camera", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("permaccess:app:camera:com.example.p5", useUnmergedTree = true).assertExists()

        compose.runOnUiThread { dash = PermAccess.dashboard(emptyList(), now) }
        compose.waitForIdle()
        compose.onNodeWithTag("permaccess:empty", useUnmergedTree = true).assertExists()

        compose.runOnUiThread { dash = PermAccess.Dashboard.NoAccess("no shell") }
        compose.waitForIdle()
        compose.onNodeWithTag("permaccess:noaccess", useUnmergedTree = true).assertExists()
        // The no-access block lists every group as unknown, so the button sits below the bubble's fold: scroll to it as a user would.
        compose.onNodeWithTag("permaccess:openperms", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, perms)
    }

    @Test fun `P6 #1721 no access points at the Webserver ADB Shell page, with the owner's real reason`() {
        var adb = 0
        showKit { DashboardSheet(compose.activity, { null }, onPerms = {}, initial = PermAccess.Dashboard.NoAccess(
            PermAccessUi.whyNot(com.diegonmarcos.superapp.shellclient.ShellResult.NeedsArm)), onAdb = { adb++ }) }
        compose.onNodeWithTag("permaccess:noaccess", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("permaccess:openadb", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, adb)
        for (stale in listOf("armed for the SuperApp", "SuperApp's ADB shell", "Configs > Perms > ADB"))
            assertFalse("stale in-process wording: $stale", PermAccess.NO_ACCESS_HELP.contains(stale) || PermAccessUi.whyNot(com.diegonmarcos.superapp.shellclient.ShellResult.NeedsArm).contains(stale))
        assertTrue(PermAccess.NO_ACCESS_HELP.contains("Cloud C3 Webserver"))
    }

    @Test fun `P7 #1721 every owner answer maps to a reason, an Ok with data is data`() {
        assertTrue(PermAccessUi.whyNot(SR.Denied("not allowlisted")).contains("refused"))
        assertTrue(PermAccessUi.whyNot(SR.Failed("timeout", true)).contains("did not complete"))
        assertTrue(PermAccessUi.whyNot(SR.Ok(1, "", "boom", "adb")).contains("exit 1"))
        val d = PermAccessUi.load({ null }); assertTrue(d is PermAccess.Dashboard.NoAccess)
    }
}
