package com.diegonmarcos.superapp.settings

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.KitTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #773 — Configs ▸ Launcher ▸ Presets is the first SuperApp page on libs:ui-kit, and the kit's
 * own behaviour is proven here, through its first consumer, under the app's REAL palette and
 * declarations (BuildConfig's launcher_presets / launcher_profiles), never a fixture.
 *
 *   P1 the page draws every declared group, in declared order, each with its declared rows
 *   P2 a sandbox pick writes the store AND moves the selection in place: same fragment, no
 *      rebuild (#349), the tapped tile selected and the previous one not
 *   P3 every tile is announced as a selectable choice, not a bare label
 *   K1 a kit switch row toggles from a tap anywhere on the row, and reports the new value
 *   K2 dismissing a kit confirm dialog never confirms
 *
 * The host is KitPageHarness: a FragmentActivity with the page added the way SectionPages adds
 * it, so KitComposeFragment's ComposeView, its lifecycle and its palette read are the production path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LauncherPresetsComposeTest : KitPageHarness() {

    private fun showPresets(): LauncherPresetsFragment = show(LauncherPresetsFragment.newInstance())

    private fun profileGroup(): LauncherPresets.Group =
        LauncherPresets.groups().single { it.kind == LauncherPresets.KIND_PROFILE }

    @Test
    fun `P1 every declared group is drawn in order with its declared rows`() {
        val page = showPresets()
        val declared = LauncherPresets.groups()
        assertTrue("build.json::ui.launcher_presets is empty — nothing to draw", declared.isNotEmpty())
        assertEquals("the page's groups are not ui.launcher_presets, in order",
            declared.map { it.id }, page.page.map { it.first.id })
        for (g in declared) compose.onNodeWithTag(KitTags.header(g.id)).assertExists()

        val profiles = LauncherProfiles.loadFromBuildConfig()
        assertTrue("ui.launcher_profiles is empty", profiles.size >= 2)
        val group = profileGroup()
        assertEquals("the sandbox rows are not ui.launcher_profiles",
            profiles.map { it.id }, page.page.single { it.first.id == group.id }.second.map { it.id })
        // The label is asserted ON its own tile: a bare text search also finds it in another
        // row's subtitle (Work's says "Same as Personal…").
        for (p in profiles) {
            compose.onNodeWithTag(KitTags.tile("${group.id}:${p.id}")).assert(hasText(p.label, substring = true))
        }
    }

    @Test
    fun `P2 a sandbox pick writes the store and moves the selection in place`() {
        val ctx = RuntimeEnvironment.getApplication()
        val page = showPresets()
        val group = profileGroup()
        val before = LauncherProfilePrefs(ctx).profile
        val target = LauncherProfiles.loadFromBuildConfig().first { it.id != before.id }
        // Negative control: the pick has something to change, so the asserts below cannot pass
        // on a page that ignores taps.
        assertNotEquals(before.id, target.id)
        compose.onNodeWithTag(KitTags.tile("${group.id}:${before.id}")).assertIsSelected()
        compose.onNodeWithTag(KitTags.tile("${group.id}:${target.id}")).assertIsNotSelected()

        compose.onNodeWithTag(KitTags.tile("${group.id}:${target.id}")).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("the pick did not reach LauncherProfilePrefs", target.id, LauncherProfilePrefs(ctx).profile.id)
        compose.onNodeWithTag(KitTags.tile("${group.id}:${target.id}")).assertIsSelected()
        compose.onNodeWithTag(KitTags.tile("${group.id}:${before.id}")).assertIsNotSelected()
        val shown = compose.activity.supportFragmentManager.findFragmentById(android.R.id.content)
        assertSame("the pick rebuilt the page instead of repainting it (#349)", page, shown)
    }

    @Test
    fun `#1506 repaintAll after the fragment is detached is a no-op, not a crash`() {
        val page = showPresets()
        val fm = compose.activity.supportFragmentManager
        // Detach the page the way leaving the screen does: it is no longer attached to a context.
        fm.beginTransaction().remove(page).commitNow()
        assertFalse("precondition: the fragment must be detached", page.isAdded)
        // The Super Saving worker (superSavingTile) posts repaintAll() back to the UI thread after the
        // user has left; before the isAdded guard this reached requireContext() and threw
        // IllegalStateException "not attached to a context", crashing the app. Now it must do nothing.
        page.repaintAll()
    }

    @Test
    fun `P3 every tile is announced as a selectable choice`() {
        val page = showPresets()
        for ((group, tiles) in page.page) for (t in tiles) {
            compose.onNodeWithTag(KitTags.tile("${group.id}:${t.id}")).assertIsSelectable()
        }
    }

    @Test
    fun `K1 a kit switch row toggles from a tap on the row`() {
        val seen = mutableListOf<Boolean>()
        showKit {
            var on by androidx.compose.runtime.remember { mutableStateOf(false) }
            KitSwitchRow("Stars", "Animated backdrop", on, { on = it; seen += it },
                Modifier.testTag(KitTags.row("stars")))
        }
        compose.onNodeWithTag(KitTags.row("stars")).assertIsOff()
        compose.onNodeWithText("Stars").performClick()   // the title, not the switch
        compose.onNodeWithTag(KitTags.row("stars")).assertIsOn()
        compose.onNodeWithTag(KitTags.row("stars")).performClick()
        compose.onNodeWithTag(KitTags.row("stars")).assertIsOff()
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `K2 dismissing a kit confirm dialog never confirms`() {
        var confirmed = 0
        var dismissed = 0
        showKit {
            var open by androidx.compose.runtime.remember { mutableStateOf(true) }
            Text("host")
            if (open) KitConfirmDialog("Reset?", "Forget every switch.", "Reset", "Cancel",
                onConfirm = { confirmed++; open = false }, onDismiss = { dismissed++; open = false })
        }
        compose.onNodeWithTag(KitTags.DIALOG_DISMISS).performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
        assertFalse("the dialog is still up after dismiss",
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(KitTags.DIALOG_CONFIRM)).fetchSemanticsNodes().isNotEmpty())
    }
}
