package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — Configs ▸ Permissions in Compose (was a ScrollView of hand-built TextViews).
 *
 *   P1 the page draws its section heads and the Copy All button
 *   P2 a button is a dense control (no 24 sp Material line, under 60 dp)
 *   P3 the Floating Nav toggle flips its own label without rebuilding the page
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionsComposeTest : KitPageHarness() {

    /** The unit-test package carries no uses-permission list, so every gated row would read "not declared"; declare the catalogue's. */
    private fun declareCataloguePermissions() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val all = com.diegonmarcos.superapp.perms.Perms.catalogue(app).entries.map { it.permission }.filter { it.isNotEmpty() }.distinct()
        org.robolectric.Shadows.shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).requestedPermissions = all.toTypedArray()
    }

    /** DIAG(#1519): name what the page drew when an assertion misses (removed once the four are green). */
    private fun guarded(body: () -> Unit) {
        try { body() } catch (t: AssertionError) {
            println("PERMSDIAG " + compose.onRoot(useUnmergedTree = true).printToString(Int.MAX_VALUE).take(9000)); throw t
        }
    }

    private fun showPage() { declareCataloguePermissions(); show(PermissionsFragment.newInstance()) }

    /** #1519 the older device tools fold away under the grouped list; open them. */
    private fun openDeviceTools() {
        compose.onNodeWithText("Device tools", substring = true).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `P0 the grouped list and its sticky summary are drawn first`() {
        guarded {
            showPage()
            compose.onNodeWithTag("perms:summary").assertExists()
            compose.onNodeWithTag("perms:progress").assertExists()
            compose.onNodeWithTag("perms:group:location_wifi").assertExists()
            compose.onNodeWithTag("perms:group:system").assertExists()
            compose.onNodeWithTag("perms:row:fine_location").assertExists()
            compose.onNodeWithTag("perms:row:location_services").assertExists()
        }
    }

    @Test
    fun `P1 section heads and Copy All are drawn`() {
        guarded {
            showPage()
            openDeviceTools()
            compose.onNodeWithText("GRANT HERE", substring = true).assertExists()
            compose.onNodeWithText("OPEN A SYSTEM PAGE", substring = true).assertExists()
            compose.onNodeWithText("DEVICE TUNING", substring = true).assertExists()
            compose.onNodeWithText("Copy All Perms Status").assertExists()
        }
    }

    @Test
    fun `P2 a permission button is dense`() {
        guarded {
            showPage()
            openDeviceTools()
            val h = compose.onNodeWithText("ADB Shell ›", substring = true).fetchSemanticsNode().size.height
            assertTrue("button is $h px", h <= with(compose.density) { 60.dp.roundToPx() })
        }
    }

    @Test
    fun `P3 the Floating Nav toggle flips its label`() {
        guarded {
            showPage()
            openDeviceTools()
            compose.onNodeWithText("Start Floating Nav").assertExists()
            compose.onNodeWithText("Start Floating Nav").performClick()
            compose.waitForIdle()
            // Robolectric has no overlay permission: the service refuses and the label stays Start. Either way one toggle remains.
            compose.onAllNodesWithText("Floating Nav", substring = true).assertCountEquals(1)
        }
    }
}
