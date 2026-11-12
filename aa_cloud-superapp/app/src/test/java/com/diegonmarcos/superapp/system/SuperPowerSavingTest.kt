package com.diegonmarcos.superapp.system

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.configs.AdbToggle
import com.diegonmarcos.superapp.shellbroker.Allowlist
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** #1689 only previously-on grants are released and restored; real state; fallbacks; broker scope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SuperPowerSavingTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val list = Allowlist.parse(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())
    private fun rule(c: String) = (list.decide(ShellBroker.SELF, c) as? Allowlist.Decision.Allow)?.rule?.id

    @Test fun `only on grants are released, only remembered ones restored`() {
        assertEquals(SuperPowerSaving.Plan(true, false), SuperPowerSaving.releasePlan(adminOn = true, listenerOn = false))
        assertEquals(SuperPowerSaving.Plan(false, true), SuperPowerSaving.restorePlan(releasedAdmin = false, releasedListener = true))
    }

    @Test fun `turning on with nothing granted releases nothing and remembers that`() {
        val r = SuperPowerSaving.turnOn(ctx)
        assertTrue(SuperPowerSaving.isOn(ctx))
        assertFalse(SuperPowerSaving.releasedAdmin(ctx)); assertFalse(SuperPowerSaving.releasedListener(ctx))
        assertTrue(r.lines.any { it == "Device admin: was off, left off" }); assertTrue(r.lines.any { it == "Accessibility: untouched" })
        val off = SuperPowerSaving.turnOff(ctx)
        assertFalse(SuperPowerSaving.isOn(ctx)); assertTrue(off.needs.isEmpty())
    }

    @Test fun `steps reflect the real grants and carry own-component commands`() {
        val s = SuperPowerSaving.steps(ctx)
        assertEquals(false, s[0].on); assertEquals(false, s[1].on)
        assertEquals(AdbToggle.Direction.ENABLE, s[0].direction)
        assertTrue(s[0].enable.single().startsWith("dpm set-active-admin --user 0 ${ctx.packageName}/"))
        assertTrue(s[1].disable.single().startsWith("cmd notification disallow_listener ${ctx.packageName}/"))
    }

    @Test fun `broker allows the four lines for own components only`() {
        val pkg = ShellBroker.SELF
        assertEquals("superpower-self", rule("dpm set-active-admin --user 0 $pkg/.system.LockScreenAdminReceiver"))
        assertEquals("roles-release-self", rule("dpm remove-active-admin --user 0 $pkg/.system.LockScreenAdminReceiver"))
        assertEquals("superpower-self", rule("cmd notification allow_listener $pkg/com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService"))
        assertEquals("superpower-self", rule("cmd notification disallow_listener $pkg/.notificationcenter.PhoneNotificationListenerService"))
        assertEquals(null, rule("dpm set-active-admin --user 0 com.other/.Admin"))
        assertEquals(null, rule("cmd notification disallow_listener com.other/.L"))
        assertTrue(rule("cmd notification allow_listener com.other/.L") != "superpower-self")
    }

    @Test fun `paused reasons are shown where the grants are used`() {
        val m = "src/main/java/com/diegonmarcos/superapp/"
        assertTrue(File(m + "system/ScreenLocker.kt").readText().contains("SuperPowerSaving.pausedReason(ctx)"))
        assertTrue(File(m + "launcher/AggregatorPanels.kt").readText().contains("SuperPowerSaving.pausedReason(ctx)"))
        val f = File(m + "configs/PermissionsFragment.kt").readText()
        assertTrue(f.contains("SuperPowerCard()") && f.contains("openFallbacks"))
        assertTrue(File("build.json").readText().contains("\"id\": \"super_power_saving\""))
    }
}
