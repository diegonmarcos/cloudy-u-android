package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the system-info bubble (RAM / Storage tap) in Compose, was a PopupWindow of TextViews.
 *
 *   S1 it draws the rows the old bubble drew (Device, Android, App, Uptime, then the Memory, Storage and CPU groups)
 *   S2 it never outgrows the screen (it scrolls)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemInfoPopupComposeTest : KitPageHarness() {

    @Test
    fun `S1 the bubble draws its rows`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        showKit { SystemInfoPopup.SystemInfoBubble(ctx) }
        // Header caps (Device/Android/App/Uptime) draw verbatim; the Memory/Storage/CPU
        // groups survive the Profile-Scan + 13-tracker reorg under richer labels — "06  Memory"
        // / "RAM pressure", "03  Storage" / "Storage (app APIs)", "CPU load + top processes" — so
        // match as substrings: the contract is that the bubble still surfaces each, not its old exact label.
        for (row in listOf("Device", "Android", "App", "Memory", "Storage", "CPU", "Uptime"))
            assertTrue("no '$row' row", compose.onAllNodesWithText(row, substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `S2 the bubble scrolls inside the screen`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        showKit { SystemInfoPopup.SystemInfoBubble(ctx) }
        val h = compose.onNodeWithTag("sysinfo:bubble").fetchSemanticsNode().size.height
        assertTrue("bubble is $h px", h <= ctx.resources.displayMetrics.heightPixels)
    }
}
