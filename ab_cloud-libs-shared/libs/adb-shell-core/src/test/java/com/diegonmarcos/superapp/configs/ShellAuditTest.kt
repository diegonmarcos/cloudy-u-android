package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1682 P3: the owner-side audit / callers reads over the client wire (moved with their decoder out of libs:shell-client). */
class ShellAuditTest {
    private class Fake(val reply: (String) -> String?) : ShellClient.Transport {
        val methods = mutableListOf<String>()
        override fun call(method: String, cmd: String, extras: Map<String, String>): String? { methods += method; return reply(method) }
    }

    @Test fun auditAndCallersRoundTrip() {
        val rows = listOf(ShellWire.AuditRow(1L, "app.a", "id -u", "ok", 0, 12L, "embedded-adb"))
        val callers = listOf("app.a" to "3 rules, stdin")
        val f = Fake { m -> when (m) { ShellWire.M_AUDIT -> ShellWire.encodeAudit(rows); ShellWire.M_CALLERS -> ShellWire.encodeCallers(callers); else -> "{}" } }
        val c = ShellClient(f)
        assertEquals(rows, ShellAudit.rows(c))
        assertEquals(callers, ShellAudit.callers(c))
        assertEquals(listOf(ShellWire.M_AUDIT, ShellWire.M_CALLERS), f.methods)
    }

    @Test fun refusedAuditIsNullNotEmpty() {
        val c = ShellClient(Fake { ShellWire.encode(ShellResult.Denied("no audit grant")) })
        assertNull(ShellAudit.rows(c)); assertNull(ShellAudit.callers(c))
    }
}
