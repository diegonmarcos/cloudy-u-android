package com.diegonmarcos.superapp.netwg

import com.diegonmarcos.superapp.net.RelayProbe
import com.diegonmarcos.superapp.net.RelaySpec
import com.diegonmarcos.superapp.net.RelayStatus
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The engine's TCP/443 relay against a local server that speaks what the fleet's wstunnel v11
 * server speaks (WsTunnel's doc): upgrade at /<prefix>/events, the JWT ticket in
 * Sec-WebSocket-Protocol, 400 for a wrong prefix, a ping before the data, one datagram per binary
 * message. TLS is the only part left out (tls = false); MeshRelay's TLS is the JDK's own.
 */
class MeshRelayTest {

    private val prefix = "0123abcd"
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val claims = CopyOnWriteArrayList<JSONObject>()
    private val relays = ArrayList<MeshRelay>()

    init {
        thread(isDaemon = true) { while (!server.isClosed) runCatching { server.accept() }.getOrNull()?.let { s -> thread(isDaemon = true) { serve(s) } } }
    }

    @After fun tearDown() { relays.forEach { it.stop() }; server.close() }

    /** The fake wstunnel: checks the upgrade, sends one ping, then echoes every binary message. */
    private fun serve(s: Socket) { s.use {
        val i = BufferedInputStream(s.getInputStream())
        val o = s.getOutputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = i.read(); if (b < 0) return; head.append(b.toChar()) }
        val lines = head.lines()
        val path = lines[0].split(' ')[1]
        if (path != "/$prefix/events") { o.write("HTTP/1.1 400 Bad Request\r\ncontent-length: 0\r\n\r\n".toByteArray()); return }
        val proto = lines.first { it.startsWith("Sec-WebSocket-Protocol:") }
        val jwt = proto.substringAfter("authorization.bearer.").trim()
        claims += JSONObject(String(Base64.getUrlDecoder().decode(jwt.split('.')[1])))
        val key = lines.first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        o.write("HTTP/1.1 101 Switching Protocols\r\nupgrade: websocket\r\nconnection: upgrade\r\nsec-websocket-accept: $accept\r\n\r\n".toByteArray())
        o.write(byteArrayOf(0x89.toByte(), 2, 'h'.code.toByte(), 'i'.code.toByte()))   // a ping, unmasked like a server's
        o.flush()
        while (true) {
            val f = runCatching { WsTunnel.readFrame(i) }.getOrNull() ?: return
            if (f.opcode == WsTunnel.OP_BINARY) {
                val p = f.payload
                val h = if (p.size < 126) byteArrayOf(0x82.toByte(), p.size.toByte()) else byteArrayOf(0x82.toByte(), 126, (p.size shr 8).toByte(), p.size.toByte())
                o.write(h + p); o.flush()
            }
        }
    } }

    private fun freeUdpPort(): Int = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun spec(listen: Int, key: String = prefix) = RelaySpec("relay.test", server.localPort, listOf("127.0.0.1"), key,
        listOf(RelaySpec.Route(listen, "127.0.0.1:51820")), tls = false)

    private fun relay() = MeshRelay(protect = { true }).also { relays += it }

    @Test fun datagramsCrossTheRelayAndComeBack() {
        val port = freeUdpPort()
        val r = relay()
        val st = RelayStatus.parse(r.apply(spec(port)))
        assertEquals("idle", st.state)
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { wg ->
            wg.soTimeout = 5000
            for (size in listOf(148, 1380, 300)) {   // a handshake initiation, a full data packet, a 126+ length
                val msg = ByteArray(size) { (it * 7).toByte() }
                wg.send(DatagramPacket(msg, msg.size, InetAddress.getByName("127.0.0.1"), port))
                val back = DatagramPacket(ByteArray(2000), 2000)
                wg.receive(back)
                assertTrue("echo of $size bytes", msg.contentEquals(back.data.copyOf(back.length)))
            }
        }
        val up = RelayStatus.parse(r.status())
        assertEquals("up", up.state)
        assertEquals("127.0.0.1", up.legs.single().via)
        assertTrue(up.legs.single().tx >= 148 + 1380 + 300)
        val c = claims.first()
        assertEquals("127.0.0.1", c.getString("r"))
        assertEquals(51820, c.getInt("rp"))
        assertTrue("UDP ticket, the shape wstunnel's LocalProtocol::Udp deserialises", c.getJSONObject("p").getJSONObject("Udp").isNull("timeout"))
    }

    @Test fun aWrongKeyIsNamedNotHidden() {
        val p = RelayProbe.parse(relay().probe(spec(freeUdpPort(), key = "wrong")))
        assertEquals(400, p.code)
        assertTrue(p.error, p.error.contains("relay key"))
        val ok = RelayProbe.parse(relay().probe(spec(freeUdpPort())))
        assertTrue(ok.error, ok.ok)
        assertEquals("none", ok.tls)
    }

    @Test fun anUnreachableAddressFallsToTheNext() {
        // 127.0.0.3 is loopback but nothing listens there (the server is bound to 127.0.0.1 only).
        val s = spec(freeUdpPort()).copy(addrs = listOf("127.0.0.3", "127.0.0.1"))
        val p = RelayProbe.parse(relay().probe(s))
        assertTrue(p.error, p.ok)
        assertEquals("127.0.0.1", p.addr)
        val none = RelayProbe.parse(relay().probe(s.copy(addrs = listOf("127.0.0.3"))))
        assertTrue(!none.ok && none.error.isNotBlank())
    }

    @Test fun anIpv6AddressWithNoRouteIsSkippedAndIpv4CarriesTheRelay() {
        // #1798 the phone's case: DNS gives the v6 address first, the Wi-Fi has no IPv6 route. 2001:db8::1 stands in for it.
        val r = MeshRelay(protect = { true }, hasRoute = { ':' !in it }).also { relays += it }
        val p = RelayProbe.parse(r.probe(spec(freeUdpPort()).copy(addrs = listOf("2001:db8::1", "127.0.0.1"))))
        assertTrue(p.error, p.ok)
        assertEquals("127.0.0.1", p.addr)
        assertTrue(p.attempts, p.attempts.contains("v6 2001:db8::1 skipped: no IPv6 route"))
        assertTrue(p.attempts, p.attempts.contains("v4 127.0.0.1 ok"))
        // and with nothing routable the probe says so for every address, never a bare last error
        val none = RelayProbe.parse(MeshRelay(protect = { true }, hasRoute = { false }).also { relays += it }
            .probe(spec(freeUdpPort()).copy(addrs = listOf("2001:db8::1", "127.0.0.1"))))
        assertTrue(!none.ok && none.error.contains("v6 2001:db8::1 skipped") && none.error.contains("v4 127.0.0.1 skipped"))
    }

    @Test fun theSocketIsBoundBeforeItIsProtected() {
        // VpnService.protect marks the socket's descriptor, which a bare Socket() does not have yet.
        val seen = ArrayList<Boolean>()
        val r = MeshRelay(protect = { seen += it.isBound && !it.isConnected; true }).also { relays += it }
        assertTrue(RelayProbe.parse(r.probe(spec(freeUdpPort()))).ok)
        assertEquals(listOf(true), seen)
    }

    @Test fun stoppingReleasesThePort() {
        val port = freeUdpPort()
        val r = relay()
        r.apply(spec(port))
        r.apply(null)
        assertEquals("off", RelayStatus.parse(r.status()).state)
        DatagramSocket(port, InetAddress.getByName("127.0.0.1")).close()   // bindable again
    }

    @Test fun framesOfEveryLengthClassRoundTrip() {
        for (n in listOf(0, 125, 126, 65535, 65536)) {
            val data = ByteArray(n) { it.toByte() }
            val out = ByteArrayOutputStream()
            WsTunnel.writeFrame(out, WsTunnel.OP_BINARY, data)
            // Unmasked, as wstunnel's own client: the fleet's server does not unmask (WsTunnel's doc).
            assertEquals(0, out.toByteArray()[1].toInt() and 0x80)
            val f = WsTunnel.readFrame(ByteArrayInputStream(out.toByteArray()))
            assertEquals(WsTunnel.OP_BINARY, f.opcode)
            assertTrue("length $n", data.contentEquals(f.payload))
        }
    }

    @Test fun specCrossesTheWireIntact() {
        val s = spec(51830)
        assertEquals(s, RelaySpec.parse(s.toJson()))
        assertEquals(null, RelaySpec.parse(""))
    }
}
