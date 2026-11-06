package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellclient.AdbLink
import com.diegonmarcos.superapp.shellclient.ShellWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1550 the ADB transport is checked on its own, never inferred from the broker answering `id -u`:
 *   every state (adb off, wireless off, not paired, paired-but-unreachable, connected), the reported case
 *   "broker up, Wireless debugging off => not connected", only the embedded adb session is asked, and the wire carries it.
 */
class AdbTransportStatusTest {
    private val list = Allowlist.parse("""{"callers":{}}""")

    /** The ladder answers `id -u` = 2000 (the local server) whatever adb does. */
    private class Ch(val facts: AdbFacts?, val adb: (String) -> String?) : BrokerChannel {
        val adbRan = ArrayList<String>()
        override fun name() = "local-server"
        override fun ready() = true
        override fun run(cmd: String, stdin: File?): String? = "2000\n"
        override fun adbFacts() = facts
        override fun adbRun(cmd: String): String? { adbRan += cmd; return adb(cmd) }
    }

    private fun status(f: AdbFacts?, adb: (String) -> String? = { "36\n" }) = BrokerCore({ list }, Ch(f, adb), AuditLog(), { 1_000L }).status()

    @Test fun `broker up and wireless debugging off is not connected`() {
        val s = status(AdbFacts(adbEnabled = true, wireless = false, paired = true, session = false, endpoint = null))
        assertTrue("the broker itself is up", s.armed)
        assertEquals(AdbLink.WIRELESS_OFF, s.adb)
        assertFalse(s.adb.up)
        assertTrue(s.adbLine().contains("Wireless debugging is off") && s.adbLine().contains("shell broker up via local-server"))
    }

    @Test fun `a stale socket flag with wireless off is still not connected`() {
        val s = status(AdbFacts(true, false, true, session = true, endpoint = "1.2.3.4:5"), adb = { null })
        assertEquals(AdbLink.WIRELESS_OFF, s.adb)
    }

    @Test fun `adb off`() = assertEquals(AdbLink.ADB_OFF, status(AdbFacts(false, null, false, false, null)).adb)

    @Test fun `on but not paired`() = assertEquals(AdbLink.NOT_PAIRED, status(AdbFacts(true, true, false, false, null)).adb)

    @Test fun `paired but unreachable`() {
        val s = status(AdbFacts(true, true, true, session = true, endpoint = "1.2.3.4:5"), adb = { "ERR: closed" })
        assertEquals(AdbLink.UNREACHABLE, s.adb)
        assertTrue(s.adbDetail.contains("getprop"))
        assertEquals(AdbLink.UNREACHABLE, status(AdbFacts(true, true, true, session = false, endpoint = null)).adb)
    }

    @Test fun `connected only on a getprop round trip over adb`() {
        val ch = Ch(AdbFacts(true, true, true, true, "1.2.3.4:37123")) { "36\n" }
        val s = BrokerCore({ list }, ch, AuditLog(), { 1_000L }).status()
        assertEquals(AdbLink.CONNECTED, s.adb)
        assertEquals("1.2.3.4:37123", s.adbDetail)
        assertEquals(listOf(BrokerCore.ADB_CHECK_CMD), ch.adbRan)
    }

    @Test fun `no adb facts reads unknown, never green`() {
        val s = status(null)
        assertEquals(AdbLink.UNKNOWN, s.adb); assertEquals(null, s.adb.verdict)
    }

    @Test fun `the wire carries the transport and an old broker reads unknown`() {
        val s = status(AdbFacts(true, false, true, false, null))
        val back = ShellWire.decodeStatus(ShellWire.encodeStatus(s))
        assertEquals(AdbLink.WIRELESS_OFF, back.adb); assertTrue(back.armed)
        val old = ShellWire.decodeStatus("""{"armed":true,"channel":"x","checkedAt":5}""")
        assertEquals(AdbLink.UNKNOWN, old.adb); assertFalse(old.adb.up)
    }

    @Test fun `classifier table`() {
        assertEquals(AdbLink.CONNECTED, AdbLink.classify(false, false, false, true))
        assertEquals(AdbLink.WIRELESS_OFF, AdbLink.classify(true, false, true, false))
        assertEquals(AdbLink.ADB_OFF, AdbLink.classify(false, true, true, false))
        assertEquals(AdbLink.UNKNOWN, AdbLink.classify(true, null, true, null))
        assertEquals(AdbLink.NOT_PAIRED, AdbLink.classify(true, true, false, false))
        assertEquals(AdbLink.UNREACHABLE, AdbLink.classify(true, true, true, false))
    }

    @Test fun `invalidate forces a fresh check`() {
        var wifi = true
        val ch = object : BrokerChannel {
            override fun name() = "local-server"; override fun ready() = true
            override fun run(cmd: String, stdin: File?) = "2000\n"
            override fun adbFacts() = AdbFacts(true, wifi, true, wifi, "h:1")
            override fun adbRun(cmd: String) = if (wifi) "36" else null
        }
        val c = BrokerCore({ list }, ch, AuditLog(), { 1_000L })
        assertEquals(AdbLink.CONNECTED, c.status().adb)
        wifi = false
        assertEquals("cached within the TTL", AdbLink.CONNECTED, c.status().adb)
        c.invalidate()
        assertEquals(AdbLink.WIRELESS_OFF, c.status().adb)
    }
}
