package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** #1684 WebS / SSHd state mapping (on / off / unknown) from real loopback round trips against throwaway listeners. */
class LocalDoorsTest {

    private fun serve(reply: String): ServerSocket {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching { ss.accept().use { c -> c.getOutputStream().write(reply.toByteArray()); c.getOutputStream().flush(); Thread.sleep(50) } }
        }
        return ss
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun `status line and banner parsing`() {
        assertEquals(200, LocalDoors.statusCode("HTTP/1.1 200 OK"))
        assertEquals(503, LocalDoors.statusCode("HTTP/1.0 503 Service Unavailable"))
        assertNull(LocalDoors.statusCode("SSH-2.0-x"))
        assertNull(LocalDoors.statusCode(null))
        assertEquals(LocalDoors.State.ON, LocalDoors.stateOfHttp(204))
        assertEquals(LocalDoors.State.OFF, LocalDoors.stateOfHttp(503))
        assertEquals(LocalDoors.State.OFF, LocalDoors.stateOfHttp(null))
        assertEquals(LocalDoors.State.ON, LocalDoors.stateOfBanner("SSH-2.0-OpenSSH_9.6"))
        assertEquals(LocalDoors.State.OFF, LocalDoors.stateOfBanner("HTTP/1.1 400"))
    }

    @Test fun `WebS on from a 2xx HEAD answer`() {
        serve("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").use { ss ->
            val p = LocalDoors.probeHttp(LocalDoors.Door("webs", "W", ss.localPort, "127.0.0.1", "/__api__/health"), 2000)
            assertEquals(LocalDoors.State.ON, p.state)
            assertTrue(p.rttMs != null)
        }
    }

    @Test fun `WebS off on a 503 and on refused`() {
        serve("HTTP/1.1 503 Paused\r\n\r\n").use { ss ->
            assertEquals(LocalDoors.State.OFF, LocalDoors.probeHttp(LocalDoors.Door("webs", "W", ss.localPort, null, "/"), 2000).state)
        }
        val p = LocalDoors.probeHttp(LocalDoors.Door("webs", "W", freePort(), null, "/"), 500)
        assertEquals(LocalDoors.State.OFF, p.state)
        assertNull(p.rttMs)
    }

    @Test fun `unknown when the build declared no port`() {
        assertEquals(LocalDoors.State.UNKNOWN, LocalDoors.probeHttp(LocalDoors.Door("webs", "W", null, null)).state)
        assertEquals(LocalDoors.State.UNKNOWN, LocalDoors.probeHttp(null).state)
        assertEquals(LocalDoors.State.UNKNOWN, LocalDoors.probeSsh(LocalDoors.Door("nix", "N", null, null)).state)
    }

    @Test fun `SSHd on from a banner, off from a non-SSH answer or refused`() {
        serve("SSH-2.0-test\r\n").use { ss -> assertEquals(LocalDoors.State.ON, LocalDoors.probeSsh(LocalDoors.Door("n", "N", ss.localPort, null), 2000).state) }
        serve("hello\r\n").use { ss -> assertEquals(LocalDoors.State.OFF, LocalDoors.probeSsh(LocalDoors.Door("n", "N", ss.localPort, null), 2000).state) }
        assertEquals(LocalDoors.State.OFF, LocalDoors.probeSsh(LocalDoors.Door("n", "N", freePort(), null), 500).state)
    }

    @Test fun `combine - any on is on, all off is off, else unknown`() {
        val on = LocalDoors.State.ON; val off = LocalDoors.State.OFF; val unk = LocalDoors.State.UNKNOWN
        assertEquals(on, LocalDoors.combine(listOf(off, on)))
        assertEquals(off, LocalDoors.combine(listOf(off, off)))
        assertEquals(unk, LocalDoors.combine(listOf(off, unk)))
        assertEquals(unk, LocalDoors.combine(emptyList()))
    }

    @Test fun `declaration parse never throws and keeps nulls`() {
        val d = LocalDoors.parse("""{"timeout_ms":700,"webs":{"id":"webs","title":"Cloud Webserver","port":8003,"route":"/__api__/health"},
            "ssh":[{"id":"webserver","title":"W","port":8025,"bind":"mesh"},{"id":"nix","title":"N","port":null}]}""")
        assertEquals(8003, d.webs?.port); assertEquals("/__api__/health", d.webs?.route)
        assertEquals(listOf(8025, null), d.sshd.map { it.port })
        assertEquals(700, d.timeoutMs)
        assertEquals(LocalDoors.Decl(null, emptyList()), LocalDoors.parse("not json"))
    }

    @Test fun `uptime and dropdown rows`() {
        assertEquals("2m 03s", LocalDoors.uptime("""{"ok":true,"uptime_s":123}"""))
        assertEquals("1h 01m", LocalDoors.uptime("""{"uptime_s":3660}"""))
        assertNull(LocalDoors.uptime(null))
        val rows = LocalDoors.rows(LocalDoors.Probe(LocalDoors.Door("n", "Nix", null, null), LocalDoors.State.UNKNOWN, "port not declared", null, 0)) { "t" }
        assertTrue(rows.any { it.startsWith("Port: n/a") } && rows.any { it.startsWith("Bind: n/a") })
    }
}
