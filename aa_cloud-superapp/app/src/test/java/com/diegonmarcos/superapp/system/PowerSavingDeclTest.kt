package com.diegonmarcos.superapp.system

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1687 one declaration, two surfaces, the real state, the channel-unavailable path, the fleet-config key. */
class PowerSavingDeclTest {
    private val raw = File("../build.json").readText()
    private val m = "src/main/java/com/diegonmarcos/superapp/"

    @Test fun `declared exactly once in build json`() {
        assertEquals(1, Regex("\"id\": \"power_saving\"").findAll(raw).count())
        val groups = JSONObject(raw).getJSONObject("ui").getJSONObject("control_panel").getJSONArray("groups")
        val c = (0 until groups.length()).flatMap { g -> groups.getJSONObject(g).optJSONArray("controls")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .single { it.optString("id") == "power_saving" }
        assertTrue(c.getBoolean("perms_surface"))
        assertEquals("settings put global low_power 1", c.getJSONObject("adb").getJSONArray("enable").getString(0))
        assertEquals("settings put global low_power 0", c.getJSONObject("adb").getJSONArray("disable").getString(0))
    }

    @Test fun `both surfaces draw it`() {
        assertTrue(File(m + "configs/DeviceControls.kt").readText().contains("\"power_saving\" to Control("))
        val perms = File(m + "configs/PermissionsFragment.kt").readText()
        assertTrue(perms.contains("PowerSavingCard()") && perms.contains("PowerSaving.CONTROL_ID") && perms.contains("perms_surface"))
    }

    @Test fun `state is the real system state and the unavailable path names the reason and ADB Shell`() {
        val src = File(m + "system/PowerSaving.kt").readText()
        assertTrue(src.contains("isPowerSaveMode") && src.contains("ACTION_POWER_SAVE_MODE_CHANGED"))
        assertTrue(src.contains("AdbToggle.CHANNEL_DOWN"))
        val dc = File(m + "configs/DeviceControls.kt").readText()
        assertTrue(dc.contains("PowerSaving.blockedReason(ctx)") && dc.contains("ShellClient.openArmPage(ctx)"))
    }

    @Test fun `prefs are declared in fleet-config`() {
        val stores = JSONObject(File("../../ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json").readText()).getJSONObject("stores")
        val s = stores.getJSONObject(PowerSaving.PREFS)
        assertTrue(s.getString("doc").contains(PowerSaving.KEY_FOLLOW))
    }

    @Test fun `shell need is declared`() {
        assertTrue(raw.contains("\"id\": \"power-saving\"") && raw.contains("\"settings put global low_power\""))
    }
}
