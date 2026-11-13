package com.diegonmarcos.cloudwebserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1724 each requirement's state detection of Keep-alive health, over a fake [Probe]. */
class KeepAliveHealthTest {
    private val all = setOf(
        "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SCHEDULE_EXACT_ALARM",
    )
    private class P(
        override val sdk: Int = 36, override val samsung: Boolean = false, val declared: Set<String>,
        val notif: Boolean? = true, val batt: Boolean? = true, val boot: Boolean? = true, val exact: Boolean? = true,
        val bg: String? = "allow", val on: Boolean = true, val refusal: String = "",
    ) : Probe {
        override fun declared(permission: String) = permission in declared
        override fun notificationsAllowed() = notif
        override fun ignoringBatteryOptimizations() = batt
        override fun bootReceiverEnabled() = boot
        override fun canScheduleExactAlarms() = exact
        override fun runAnyInBackground() = bg
        override fun keepAliveOn() = on
        override fun lastRefusal() = refusal
    }
    private fun row(p: Probe, id: String) = KeepAliveHealth.evaluate(p).first { it.id == id }

    @Test fun `all good is all OK`() {
        val rows = KeepAliveHealth.evaluate(P(declared = all))
        assertTrue(rows.joinToString { "${it.id}=${it.state}" }, rows.none { it.state == HealthState.MISSING })
        assertEquals("all OK", KeepAliveHealth.summary(rows))
    }

    @Test fun `fgs permissions missing from the manifest are MISSING`() {
        assertEquals(HealthState.OK, row(P(declared = all), "fgs").state)
        assertEquals(HealthState.MISSING, row(P(declared = all - "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"), "fgs").state)
        assertEquals(HealthState.MISSING, row(P(declared = all - "android.permission.FOREGROUND_SERVICE"), "fgs").state)
    }

    @Test fun `notifications off is MISSING with a fix on Android 13 plus, never required below`() {
        val r = row(P(declared = all, notif = false), "notifications")
        assertEquals(HealthState.MISSING, r.state); assertEquals(HealthFix.PERMS, r.fix)
        assertEquals(HealthState.OK, row(P(sdk = 31, declared = all, notif = false), "notifications").state)
        assertEquals(HealthState.UNKNOWN, row(P(declared = all, notif = null), "notifications").state)
    }

    @Test fun `battery optimisation exemption`() {
        assertEquals(HealthState.OK, row(P(declared = all, batt = true), "battery_opt").state)
        val m = row(P(declared = all, batt = false), "battery_opt")
        assertEquals(HealthState.MISSING, m.state); assertEquals(HealthFix.PERMS, m.fix)
        assertEquals(HealthState.UNKNOWN, row(P(declared = all, batt = null), "battery_opt").state)
    }

    @Test fun `boot restart needs the permission and an enabled receiver while keep-alive is on`() {
        assertEquals(HealthState.OK, row(P(declared = all, boot = true), "boot").state)
        assertEquals(HealthState.MISSING, row(P(declared = all, boot = false), "boot").state)
        assertEquals(HealthState.MISSING, row(P(declared = all - "android.permission.RECEIVE_BOOT_COMPLETED"), "boot").state)
        assertEquals(HealthState.NA, row(P(declared = all, on = false, boot = false), "boot").state)
    }

    @Test fun `run in background app-op`() {
        assertEquals(HealthState.OK, row(P(declared = all, bg = "allow"), "background").state)
        assertEquals(HealthState.MISSING, row(P(declared = all, bg = "ignore"), "background").state)
        assertEquals(HealthState.MISSING, row(P(declared = all, bg = "default", batt = false), "background").state)
        assertEquals(HealthState.OK, row(P(declared = all, bg = "default", batt = true), "background").state)
        assertEquals(HealthState.UNKNOWN, row(P(declared = all, bg = null), "background").state)
    }

    @Test fun `samsung row only on Samsung and never claims OK without a readable state`() {
        assertTrue(KeepAliveHealth.evaluate(P(declared = all)).none { it.id == "samsung" })
        val s = row(P(samsung = true, declared = all), "samsung")
        assertTrue(s.state != HealthState.OK); assertEquals(HealthFix.PERMS, s.fix)
        assertEquals(HealthState.MISSING, row(P(samsung = true, declared = all, batt = false), "samsung").state)
    }

    @Test fun `exact alarm is declared only by parity and never a failure`() {
        assertTrue(KeepAliveHealth.evaluate(P(declared = all - "android.permission.SCHEDULE_EXACT_ALARM")).none { it.id == "exact_alarm" })
        assertEquals(HealthState.NA, row(P(declared = all, exact = false), "exact_alarm").state)
        assertEquals(HealthState.OK, row(P(declared = all, exact = true), "exact_alarm").state)
    }

    @Test fun `the last refused start is shown as MISSING with its reason`() {
        assertEquals(HealthState.OK, row(P(declared = all), "last_start").state)
        val r = row(P(declared = all, refusal = "Android refused the foreground start"), "last_start")
        assertEquals(HealthState.MISSING, r.state); assertTrue(r.detail.contains("refused"))
    }
}
