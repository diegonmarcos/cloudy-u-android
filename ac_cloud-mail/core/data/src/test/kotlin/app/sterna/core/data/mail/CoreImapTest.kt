package app.sterna.core.data.mail

import app.sterna.core.imap.MailSecurity
import app.sterna.core.imap.MailServerConfig
import com.diegonmarcos.cloudlib.mailcore.MailCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/** #1837 phase 3: the IMAP-through-the-core path is off by default and leaves the Kotlin ImapSession path unchanged. */
class CoreImapTest {
    private val cfg = MailServerConfig("h", 993, MailSecurity.TLS, "u", "p")

    @Test fun byDefaultTheCoreIsNeverTouchedAndNoSocketIsOpened() {
        assertFalse(MailCore.imapEnabled)
        var opened = false
        val r = CoreImap.fetchSource(cfg, "INBOX", 7, open = { opened = true; Socket() }, fetch = { _, _ -> error("must not run") })
        assertNull(r)
        assertFalse(opened)
    }

    @Test fun whatTheCoreCannotDoStaysOnKotlin() {
        assertTrue(CoreImap.eligible(cfg, "INBOX"))
        assertFalse(CoreImap.eligible(cfg.copy(security = MailSecurity.STARTTLS), "INBOX"))
        assertFalse(CoreImap.eligible(cfg.copy(accessToken = "t"), "INBOX"))
        assertFalse(CoreImap.eligible(cfg, "Entwürfe"))
    }

    @Test fun theSocketPipeMapsTimeoutToEmptyAndCloseToNull() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { peer ->
                    val io = CoreImap.SocketIo(client)
                    assertEquals(0, io.read(30)!!.size)
                    peer.getOutputStream().apply { write("hi".toByteArray()); flush() }
                    assertEquals("hi", String(io.read(1000)!!))
                    io.write("x".toByteArray())
                    assertEquals("x", String(peer.getInputStream().readNBytes(1)))
                    peer.close()
                    assertNull(io.read(1000))
                }
            }
        }
    }
}
