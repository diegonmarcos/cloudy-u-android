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
 *   C1 the System bubble has a block, titled and headed by its icon, for each of its declared groups, in two macro buckets (Device Profile Scan, Profiling Trackers 01-13)
 *   C2 the Battery sections draw both since-last-charge blocks
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusDropdownsComposeTest : KitPageHarness() {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `C1 the System bubble draws the two macro buckets, the 13 trackers and their groups`() {
        showKit { SystemInfoPopup.SystemInfoBubble(ctx) }
        for (id in listOf("m1_profile_scan", "m2_trackers", "tr01_performance", "tr02_render", "tr03_storage", "tr04_battery", "tr05_thermal", "tr06_memory", "tr07_notif",
            "tr08_resp", "tr09_crash", "tr10_network", "tr11_process", "tr12_security", "tr13_suspects",
            "memory", "storage", "cpu", "graphics", "thermal", "device", "wakelocks", "df", "diskstats", "app_sizes", "crashes", "net_usage", "net_conn", "net_signal", "oom_adj", "sec_perms",
            "running_services", "jank", "notif_rate", "memory_3h", "meminfo_detail", "anr", "suspects")) compose.onNodeWithTag("sysdd:group:$id").assertExists()
        for (t in listOf("RAM pressure", "Storage (app APIs)", "CPU load + top processes", "Frame timing + gfxinfo", "Running Services", "Last 3h Memory Usage", "Sensors, throttle, cooling devices", "Notification post rate per app", "Ranked culprits"))
            compose.onNodeWithText(t).assertExists()
        // a macro or tracker header has its rule and title but no lines
        compose.onNodeWithTag("sysdd:title:m2_trackers").assertExists()
        compose.onNodeWithTag("sysdd:row:m2_trackers:0").assertDoesNotExist()
        compose.onNodeWithTag("sysdd:row:tr04_battery:0").assertDoesNotExist()
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
