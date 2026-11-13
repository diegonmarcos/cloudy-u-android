package com.diegonmarcos.superapp.settings

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.bgpolicy.BgPrefsKv
import com.diegonmarcos.superapp.bgpolicy.SuperSaving
import com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime
import com.diegonmarcos.superapp.notificationcenter.PresetsBadge
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.KitTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * #1720 Quick actions > Presets as it is drawn (the production fragment, the real declarations, A37 size): the ADB shell
 * status line at the top with its link, exactly one selected tile per group, a mode on / switch / off cycle with the Off and
 * Re-apply row, the last change with each failed step's reason, and the pending-restore panel.
 * Evidence: app/build/board-shots/presets-*.png (uploaded by the ship workflow as the board-shots artifact).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-night-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetsModesScreenTest : KitPageHarness() {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val kv get() = BgPrefsKv(ctx)

    @Before fun clean() {
        PresetsBadge.inline = true
        kv.put(SuperSaving.K_SNAP, ""); kv.put(ModeSwitch.K_REPORT, "")
        for (m in LauncherModes.load()) kv.put(SuperSaving.pendingKey(m.id), "")
        LauncherModePrefs(ctx).selected = null
    }

    private fun st(armed: Boolean) = ShellStatus(true, armed, if (armed) "local-server" else "", if (armed) "ok" else "down",
        1_700_000_000_000L, if (armed) "" else "connection refused: paired, but not connected")

    private var armed = true
    private fun settle() { Thread.sleep(300); ShadowLooper.idleMainLooper(); compose.waitForIdle() }
    private fun page(): LauncherPresetsFragment {
        val p = LauncherPresetsFragment.newInstance().also { it.readShell = { st(armed) } }
        show(p); settle()
        return p
    }
    private fun repaint(p: LauncherPresetsFragment) { compose.runOnUiThread { p.repaintAll() }; compose.waitForIdle() }
    private fun selected(p: LauncherPresetsFragment, kind: String) = p.page.single { it.first.kind == kind }.second.filter { it.selected }.map { it.id }
    private fun modesGroup() = LauncherPresets.groups().single { it.kind == LauncherPresets.KIND_MODE }.id
    private fun tile(id: String) = compose.onNodeWithTag(KitTags.tile("${modesGroup()}:$id"))

    @Test fun `A1 the ADB shell status line leads the page with the live verdict and the link to the ADB Shell page`() {
        armed = false
        val p = page()
        compose.onNodeWithTag(LauncherPresetsFragment.TAG_ADB).assertExists()
        compose.onNodeWithTag("presets-adb-text").assert(hasText("ADB shell: down - connection refused: paired, but not connected", substring = true))
        compose.onNodeWithTag("presets-adb-text").assert(hasText("will be skipped", substring = true))
        compose.onNodeWithTag("presets-adb-open").assertHasClickAction()
        compose.onNodeWithTag("presets-adb-recheck").assertHasClickAction()
        snap("presets-adb-status-down.png")
        // the next round trip answers: the same line turns to up, with the channel that answered (the Local server profile)
        armed = true; compose.runOnUiThread { p.checkShell() }; settle()
        compose.onNodeWithTag("presets-adb-text").assert(hasText("ADB shell: up via local-server", substring = true))
        snap("presets-adb-status-up.png")
        // the words are the broker status and nothing else
        assertEquals(AdbStatusText.CHECKING, AdbStatusText.of(null).first)
        assertEquals(AdbStatusText.UP, AdbStatusText.of(st(true)).first)
        assertEquals(AdbStatusText.DOWN, AdbStatusText.of(st(false)).first)
        assertTrue(AdbStatusText.of(st(false)).second.contains(st(false).line()))
    }

    @Test fun `C1 exactly one choice per group, and picking a mode deselects the one before`() {
        ContentResolver.setMasterSyncAutomatically(true) // so Super Saving has a step that takes without the shell
        val p = page()
        assertEquals("one sandbox", 1, selected(p, LauncherPresets.KIND_PROFILE).size)
        assertEquals("one theme", 1, selected(p, LauncherPresets.KIND_THEME).size)
        assertTrue("no mode before any pick", selected(p, LauncherPresets.KIND_MODE).isEmpty())
        val ids = LauncherModes.load().map { it.id }
        assertTrue(ids.size >= 4)
        for (id in ids + ids.reversed()) {
            PresetModes.select(ctx, id, shell = null)
            repaint(p)
            assertEquals("after picking $id", listOf(id), selected(p, LauncherPresets.KIND_MODE))
            assertEquals(id == FocusMode.MODE_ID, FocusMode.active(ctx))
            assertEquals(id == SuperSavingRuntime.ENGINE, SuperSavingRuntime.active(ctx))
            for (other in ids) {
                if (other == id) tile(other).performScrollTo().assertIsSelected() else tile(other).performScrollTo().assertIsNotSelected()
            }
            assertEquals(1, selected(p, LauncherPresets.KIND_PROFILE).size); assertEquals(1, selected(p, LauncherPresets.KIND_THEME).size)
        }
        PresetModes.select(ctx, "focus", shell = null); repaint(p)
        tile("focus").performScrollTo()
        snap("presets-single-choice.png")
    }

    @Test fun `M1 a mode on, switch, off cycle - the active tile wears Off and Re-apply, the last change is listed, Off leaves no mode`() {
        val p = page()
        compose.onNodeWithTag("mode-off").assertDoesNotExist()
        PresetModes.select(ctx, "saving_energy", shell = null); repaint(p)
        compose.onNodeWithTag("mode-off").performScrollTo().assertHasClickAction()
        compose.onNodeWithTag("mode-reapply").assertHasClickAction()
        compose.onNodeWithTag("mode-report-head").performScrollTo().assert(hasText("On:", substring = true))
        snap("presets-mode-cycle-1-on.png")
        PresetModes.select(ctx, "power", shell = null); repaint(p)
        compose.onNodeWithTag("mode-report-head").performScrollTo().assert(hasText("previous mode restored", substring = true))
        snap("presets-mode-cycle-2-switched.png")
        val off = PresetModes.off(ctx, shell = null); repaint(p)
        assertTrue(off.message, off.ok)
        assertTrue(selected(p, LauncherPresets.KIND_MODE).isEmpty())
        compose.onNodeWithTag("mode-off").assertDoesNotExist()
        compose.onNodeWithTag("mode-report-head").performScrollTo().assert(hasText("Off:", substring = true))
        snap("presets-mode-cycle-3-off.png")
    }

    @Test fun `R1 a step that did not take is listed with its reason, and a pending restore with its retry`() {
        val p = page()
        val failed = """{"mode":"saving_energy","op":"off","ok":false,"message":"Off did not finish: 1 done, 0 skipped, 1 did not take.","at":1700000000000,
            "steps":[],"restored":[{"id":"battery_saver","label":"System battery saver on","state":"FAILED","note":"low_power reads 1, was 0","adb":true},
            {"id":"launcher","label":"Launcher switches","state":"APPLIED","note":"9 switches put back","adb":false}]}"""
        kv.put(ModeSwitch.K_REPORT, failed)
        kv.put(SuperSaving.pendingKey("focus"), """{"mode":"focus","steps":{"dnd":{"old":"0"}}}""")
        repaint(p)
        compose.onNodeWithTag("mode-step-failed:battery_saver").performScrollTo().assert(hasText("low_power reads 1, was 0", substring = true))
        compose.onNodeWithTag("mode-leave").assertHasClickAction()
        compose.onNodeWithTag("mode-pending:focus").performScrollTo().assert(hasText("dnd", substring = true))
        compose.onNodeWithTag("mode-pending-retry:focus").assertHasClickAction()
        compose.onNodeWithTag("mode-pending-forget:focus").assertHasClickAction()
        assertFalse("the applied steps are folded until asked for", compose.onAllNodes(androidx.compose.ui.test.hasTestTag("mode-step:launcher")).fetchSemanticsNodes().isNotEmpty())
        snap("presets-failed-step-and-pending.png")
    }

    private fun snap(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread<Bitmap> {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/board-shots").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
