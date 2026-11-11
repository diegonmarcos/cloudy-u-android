package com.diegonmarcos.superapp.settings

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — Configs ▸ Launcher ▸ Controls in Compose (was a ScrollView of hand-built Views).
 *
 *   L1 the UI scope draws its section headers
 *   L2 a stepper writes the grid width, clamped, and shows it
 *   L3 a switch tile flips the stored value AND repaints in place (no page rebuild, #349)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LauncherControlsComposeTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `L1 the UI scope draws its headers`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI))
        compose.onNodeWithText("Screensaver").assertExists()
        compose.onNodeWithText("Icons per row").assertExists()
    }

    @Test
    fun `L2 a stepper writes the grid width`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI))
        val before = GridColumns.cloud(ctx)
        compose.onNodeWithTag("stepper:Cloud apps:More icons per row", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(GridColumns.clamp(before + 1), GridColumns.cloud(ctx))
    }

    @Test
    fun `L3 a switch tile flips the store and repaints in place`() {
        // The switch boxes left the UI tab: Connectivity / Notifications / Others are on Tools.
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_TOOLS))
        val prefs = LauncherSettingsPrefs(ctx)
        val toolGroups = LauncherSettingsPrefs.Config.groups.filter { it.tab == "tools" }.map { it.id }
        val item = LauncherSettingsPrefs.Config.toggles.first { it.group in toolGroups && it.store.isEmpty() && !it.userOwned }
        val before = prefs.toggle(item)
        compose.onNodeWithTag("toggle:${item.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertNotEquals(before, prefs.toggle(item))
        // The tile is still on screen, drawn from the new value: the page was not rebuilt under the tap.
        compose.onNodeWithTag("toggle:${item.id}").assertExists()
    }

    @Test
    fun `L4 the UI tab holds the UI controls and the three display controls with live status, and no switch box`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_UI))
        for (id in listOf("scale", "font", "zoom")) {
            compose.onNodeWithTag("display:$id:header", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("display:$id:status", useUnmergedTree = true).assertExists()
        }
        assertText("Display Scale", true)
        assertText("Font Size", true)
        assertText("Zoom Size", true)
        compose.onNodeWithTag("display:scale:preset:500", useUnmergedTree = true).assertExists()
        for (g in LauncherSettingsPrefs.Config.groups) assertText(g.label, false)
    }

    @Test
    fun `L5 Tools holds Connectivity, Notifications and Others`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_TOOLS))
        for (g in LauncherSettingsPrefs.Config.groups.filter { it.tab == "tools" }) assertText(g.label, true)
        assertText("Connectivity", true)
        assertText("Notifications", true)
        assertText("Others", true)
    }

    @Test
    fun `L6 Battery Hungers lists the consumers and the battery switch group`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_BATTERY))
        assertText("Launcher Battery Hungers", true)
        assertText("Battery use per app", true)
        compose.onNodeWithTag("battery-hunger", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `L7 All is the union of UI, Battery Hungers and Tools`() {
        show(LauncherConfigFragment.newInstance(LauncherConfigFragment.SCOPE_ALL))
        assertText("Screensaver", true)
        assertText("Display Scale", true)
        assertText("Launcher Battery Hungers", true)
        assertText("Battery use per app", true)
        for (g in LauncherSettingsPrefs.Config.groups) assertText(g.label, true)
    }

    /** Present / absent by text, tolerant of a label that also appears on a tile. */
    private fun assertText(text: String, present: Boolean) {
        val n = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals("'$text' ${if (present) "missing" else "must not be drawn here"} ($n nodes)", present, n > 0)
    }
}
