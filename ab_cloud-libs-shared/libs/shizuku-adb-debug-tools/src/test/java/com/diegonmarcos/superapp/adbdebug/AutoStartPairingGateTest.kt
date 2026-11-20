package com.diegonmarcos.superapp.adbdebug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1834 the automatic chain ends in the Wireless debugging pairing notification, so with the ADB keep-alive OFF no implicit trigger
 * (app start, boot, a command that found the server down, Wireless debugging coming on) may run it; a tap ("button") still may.
 */
class AutoStartPairingGateTest {
    private val implicit = listOf("app-start", "boot", "command", "wireless-debugging-on")

    @Test fun `with keep-alive off no implicit trigger runs the chain`() {
        implicit.forEach { assertFalse("$it must not run with keep-alive off", LocalServerAutoStart.mayRun(it, false)) }
    }

    @Test fun `with keep-alive off an explicit tap still offers it`() {
        assertTrue(LocalServerAutoStart.mayRun("button", false))
    }

    @Test fun `with keep-alive on implicit triggers keep working`() {
        implicit.forEach { assertTrue(LocalServerAutoStart.mayRun(it, true)) }
        assertTrue(LocalServerAutoStart.mayRun("button", true))
    }
}
