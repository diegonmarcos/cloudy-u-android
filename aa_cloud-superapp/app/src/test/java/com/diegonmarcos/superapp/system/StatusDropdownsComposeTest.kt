package com.diegonmarcos.superapp.system

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The right dropdowns draw their declared groups.
 *
 *   C1 the System bubble has a block, titled and headed by its icon, for each of its six groups
 *   C2 the Battery sections draw both since-last-charge blocks
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusDropdownsComposeTest : KitPageHarness() {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `C1 the System bubble draws the six icon groups`() {
        showKit { SystemInfoPopup.SystemInfoBubble(ctx) }
        for (id in listOf("memory", "storage", "cpu", "graphics", "running_services", "memory_3h")) compose.onNodeWithTag("sysdd:group:$id").assertExists()
        for (t in listOf("Memory", "Storage", "CPU", "Graphics", "Running Services", "Last 3h Memory Usage")) compose.onNodeWithText(t).assertExists()
        for (id in listOf("memory", "storage", "cpu", "graphics")) compose.onNodeWithTag("sysdd:icon:$id").assertExists()
    }

    @Test
    fun `C2 the Battery sections draw both since-last-charge blocks`() {
        showKit { androidx.compose.foundation.layout.Column { StatusDropdowns.BatterySinceCharge(ctx) } }
        compose.onNodeWithTag("batdd:group:since_charge_usage").assertExists()
        compose.onNodeWithTag("batdd:group:since_charge_apps").assertExists()
        compose.onNodeWithText("Since Last Charge - Usage Infos").assertExists()
        compose.onNodeWithText("Since Last Charge - Per-App Usage").assertExists()
    }
}
