package com.diegonmarcos.superapp.shellclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1682 The output door and the owner-page wire methods (invalidate, audit, callers), over a fake transport. */
class ShellClientOutDoorTest {
    private class Fake(val reply: (String) -> String?, val out: Pair<String?, ByteArray>? = null) : ShellClient.Transport {
        val methods = mutableListOf<String>()
        override fun call(method: String, cmd: String, extras: Map<String, String>): String? { methods += method; return reply(method) }
        override fun callOut(cmd: String, extras: Map<String, String>): Pair<String?, ByteArray>? { methods += ShellWire.M_EXEC_OUT; return out }
    }

    @Test fun outDoorStdoutComesFromThePipeNotTheReply() {
        val big = ByteArray(3 * ShellWire.EXEC_MAX_BYTES) { 'a'.code.toByte() }
        val f = Fake({ null }, ShellWire.encode(ShellResult.Ok(0, "", "", "embedded-adb")) to big)
        val r = ShellClient(f).execOut("dumpsys package")
        assertTrue(r is ShellResult.Ok)
        assertEquals(big.size, (r as ShellResult.Ok).stdout.length)   // over the binder-sized exec cap
        assertEquals(listOf(ShellWire.M_EXEC_OUT), f.methods)
    }

    @Test fun outDoorRefusalPassesThrough() {
        val f = Fake({ null }, ShellWire.encode(ShellResult.Denied("x has no output door")) to ByteArray(0))
        assertEquals(ShellResult.Denied("x has no output door"), ShellClient(f).execOut("dumpsys"))
    }

    @Test fun noDoorOrBlankIsNeverOk() {
        assertTrue(ShellClient(Fake({ null }, null)).execOut("dumpsys") is ShellResult.Failed)
        val f = Fake({ null }, null)
        assertTrue(ShellClient(f).execOut("  ") is ShellResult.Denied)
        assertTrue(f.methods.isEmpty())
    }

    @Test fun auditAndCallersRoundTrip() {
        val rows = listOf(ShellWire.AuditRow(1L, "app.a", "id -u", "ok", 0, 12L, "embedded-adb"))
        val callers = listOf("app.a" to "3 rules, stdin")
        val f = Fake({ m -> when (m) { ShellWire.M_AUDIT -> ShellWire.encodeAudit(rows); ShellWire.M_CALLERS -> ShellWire.encodeCallers(callers); else -> "{}" } })
        val c = ShellClient(f)
        assertEquals(rows, c.audit())
        assertEquals(callers, c.callers())
        assertTrue(c.invalidate())
        assertEquals(listOf(ShellWire.M_AUDIT, ShellWire.M_CALLERS, ShellWire.M_INVALIDATE), f.methods)
    }

    @Test fun refusedAuditIsNullNotEmpty() {
        val c = ShellClient(Fake({ ShellWire.encode(ShellResult.Denied("no audit grant")) }))
        assertNull(c.audit()); assertNull(c.callers())
        assertFalse(ShellClient(Fake({ null })).invalidate())
    }
}
