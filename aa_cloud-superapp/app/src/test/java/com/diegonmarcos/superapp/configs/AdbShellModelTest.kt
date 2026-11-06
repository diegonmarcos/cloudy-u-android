package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.shellbroker.AuditLog
import com.diegonmarcos.superapp.shellclient.ShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1522 the SuperApp ADB Shell page's pure rules.
 *   M1 the six sections come in the owner's order; only the two logs start collapsed
 *   M2 the one primary action is Pair until paired, then Re-arm
 *   M3 the status card carries the exact reason and the check time
 *   M4 callers join their allowlist summary to the newest audited call; the audit table is newest first
 */
class AdbShellModelTest {
    private fun e(ts: Long, caller: String) = AuditLog.Entry(ts, caller, "id -u", "ok", 0, 5, "binder")

    @Test fun `M1 section order and collapsed defaults`() {
        assertEquals(listOf("status", "actions", "keeper", "callers", "audit", "keeper_log", "console"), AdbShellModel.ORDER.map { it.id })
        assertEquals(setOf("status", "actions", "keeper", "callers", "console"), AdbShellModel.initiallyOpen())
        assertEquals(listOf("audit", "keeper_log"), AdbShellModel.ORDER.filter { it.collapsed }.map { it.id })
        assertTrue("at most one short help line per section", AdbShellModel.ORDER.all { it.help.length <= 80 && !it.help.contains('\n') })
    }

    @Test fun `M2 one primary action`() {
        assertEquals(AdbShellModel.Primary.PAIR, AdbShellModel.primary(paired = false))
        assertEquals(AdbShellModel.Primary.REARM, AdbShellModel.primary(paired = true))
    }

    @Test fun `M3 status card shows reason and time`() {
        val down = ShellStatus(true, false, "none", "", 1_000L, "wireless debugging is off")
        assertEquals(false, AdbShellModel.verdict(down))
        assertTrue(AdbShellModel.detail(down).contains("wireless debugging is off") && AdbShellModel.detail(down).contains("checked"))
        assertEquals(true, AdbShellModel.verdict(ShellStatus(true, true, "embedded-adb", "ok", 1_000L)))
        assertNull(AdbShellModel.verdict(null))
        assertEquals("37123", AdbShellModel.port("192.168.1.5:37123"))
        assertNull(AdbShellModel.port("192.168.1.5"))
        assertNull(AdbShellModel.port(null))
    }

    @Test fun `M4 callers and audit rows`() {
        val rows = AdbShellModel.callers(listOf("a.store" to "3 rules", "b.drive" to "1 rules, stdin"), listOf(e(10, "a.store"), e(30, "a.store"), e(20, "z.other")))
        assertEquals(listOf(30L, null), rows.map { it.lastCallAt })
        assertEquals(listOf("3 rules", "1 rules, stdin"), rows.map { it.summary })
        assertEquals(listOf(30L, 20L, 10L), AdbShellModel.auditRows(listOf(e(10, "a"), e(20, "b"), e(30, "c"))).map { it.ts })
        assertEquals(2, AdbShellModel.auditRows((1L..9L).map { e(it, "a") }, max = 2).size)
        assertEquals("diegonmarcos.cloudstore", AdbShellModel.shortCaller("com.diegonmarcos.cloudstore"))
    }
}
