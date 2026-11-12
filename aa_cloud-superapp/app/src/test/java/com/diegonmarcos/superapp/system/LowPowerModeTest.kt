package com.diegonmarcos.superapp.system

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1687 the low-power mode follows the system state, pauses / slows the listed work and resumes on exit. */
class LowPowerModeTest {
    @After fun reset() = LowPowerMode.resetForTest()

    private val decl: JSONObject by lazy {
        val groups = JSONObject(File("build.json").readText()).getJSONObject("ui").getJSONObject("control_panel").getJSONArray("groups")
        (0 until groups.length()).flatMap { g -> groups.getJSONObject(g).optJSONArray("controls")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .single { it.optString("id") == "power_saving" }
    }

    @Test fun `system power save on pauses animation and slows polling, off resumes`() {
        LowPowerMode.config = LowPowerMode.Config.parse(decl.getJSONObject("low_power_mode"))
        assertFalse(LowPowerMode.active)
        assertEquals(1000L, LowPowerMode.scaled(1000L))
        val heard = ArrayList<Boolean>()
        val stop = LowPowerMode.observe { heard += it }
        LowPowerMode.onSystemState(true)
        assertTrue(LowPowerMode.active); assertTrue(LowPowerMode.pausesAnimation)
        assertEquals(1000L * LowPowerMode.config.pollMultiplier, LowPowerMode.scaled(1000L))
        assertTrue(LowPowerMode.statusText().contains("ON"))
        LowPowerMode.onSystemState(false)
        assertFalse(LowPowerMode.active); assertFalse(LowPowerMode.pausesAnimation)
        assertEquals(1000L, LowPowerMode.scaled(1000L))
        assertEquals(listOf(true, false), heard)
        stop()
    }

    @Test fun `not following the system keeps everything normal`() {
        LowPowerMode.setFollowSystem(false)
        LowPowerMode.onSystemState(true)
        assertFalse(LowPowerMode.active)
        assertEquals(500L, LowPowerMode.scaled(500L))
    }

    @Test fun `config is declared and parsed`() {
        val c = LowPowerMode.Config.parse(decl.getJSONObject("low_power_mode"))
        assertTrue(c.pollMultiplier > 1); assertTrue(c.pauseAnimation); assertTrue(c.slows.isNotEmpty())
    }

    @Test fun `the listed work asks the mode`() {
        val m = "src/main/java/com/diegonmarcos/superapp/"
        for (f in listOf("notificationcenter/NetworkBadgeService.kt", "floatingnav/FloatingNavService.kt", "launcher/ResourcesPanel.kt",
                "launcher/BadgeCards.kt", "system/StatusDropdowns.kt", "ShellActivity.kt"))
            assertTrue("$f must scale its loop", File(m + f).readText().contains("LowPowerMode.scaled("))
        val home = File(m + "launcher/themes/cloud/Home3DFragment.kt").readText()
        assertTrue(home.contains("autoRotate = autoRotate && !lowPower") && home.contains("observeAutoRotate"))
    }
}
