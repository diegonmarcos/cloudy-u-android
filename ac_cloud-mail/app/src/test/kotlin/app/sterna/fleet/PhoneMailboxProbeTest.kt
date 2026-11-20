package app.sterna.fleet

import app.sterna.ui.compose.CheckOutcome
import app.sterna.ui.compose.CheckSource
import app.sterna.ui.compose.RecipientCheckKind
import app.sterna.ui.compose.RecipientVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * #1827 the phone-side mailbox probe, the fallback when the delivery server cannot reach port 25: the same read-only
 * dialogue and classification as the server (smtp-probe.ts), never DATA, its own port-25 gate, and the verdict rules of
 * recipients.ts when its answer replaces the server's mailbox and size lines.
 */
class PhoneMailboxProbeTest {

    @Before fun clean() = PhoneMailboxProbe.reset()

    /** A scripted MX: replies keyed by the command that precedes them; records everything the probe sent. */
    private class FakeMx(
        val greeting: Pair<Int, String> = 220 to "220 mx ESMTP",
        val ehlo: Pair<Int, String> = 250 to "250-mx hello\n250-SIZE 35882577\n250 8BITMIME",
        val rcpt: Pair<Int, String> = 250 to "250 2.1.5 Recipient OK",
        val control: Pair<Int, String> = 550 to "550 5.1.1 User unknown",
    ) {
        val sent = ArrayList<String>()
        val dialled = ArrayList<Pair<String, Int>>()
        fun dialer(reachable: Set<String>): SmtpDialer = SmtpDialer { host, port, _ ->
            dialled += host to port
            if (host !in reachable) throw SocketTimeoutException("connect timed out")
            object : SmtpSession {
                var rcpts = 0
                var last = "<connect>"
                override fun reply(deadlineMillis: Long): Pair<Int, String> = when {
                    last == "<connect>" -> greeting
                    last.startsWith("EHLO") -> ehlo
                    last.startsWith("MAIL FROM") -> 250 to "250 2.1.0 Sender OK"
                    last.startsWith("RCPT TO") -> if (rcpts == 1) rcpt else control
                    else -> 250 to "250 ok"
                }
                override fun send(line: String) {
                    sent += line
                    last = line
                    if (line.startsWith("RCPT TO")) rcpts++
                }
                override fun close() {}
            }
        }
    }

    private val spec = ClientProbeSpec(listOf("mx1.remote.example", "mx2.remote.example"), "mail.diegonmarcos.com", "", 25)

    @Test fun `an existing mailbox is confirmed with the server's exact command sequence and never DATA`() {
        val mx = FakeMx()
        val r = PhoneMailboxProbe.probe(spec, "alice@remote.example", mx.dialer(setOf("mx1.remote.example")), random = { "probe" })
        assertEquals("exists", r.mailbox)
        assertEquals(250, r.code)
        assertEquals(35882577L, r.sizeLimit)
        assertEquals(
            listOf("EHLO mail.diegonmarcos.com", "MAIL FROM:<>", "RCPT TO:<alice@remote.example>", "RCPT TO:<probe@remote.example>", "RSET", "QUIT"),
            mx.sent,
        )
        assertTrue("never DATA", mx.sent.none { it.uppercase().startsWith("DATA") })
        assertEquals(listOf("mx1.remote.example" to 25), mx.dialled)
    }

    @Test fun `does-not-exist, catch-all, greylisting and policy refusals classify like the server`() {
        assertEquals("does_not_exist", PhoneMailboxProbe.probe(spec, "a@x.example", FakeMx(rcpt = 550 to "550 5.1.1 no such user").dialer(setOf("mx1.remote.example"))).mailbox)
        PhoneMailboxProbe.reset()
        assertEquals("accepts_all", PhoneMailboxProbe.probe(spec, "b@x.example", FakeMx(control = 250 to "250 ok").dialer(setOf("mx1.remote.example"))).mailbox)
        PhoneMailboxProbe.reset()
        assertEquals("unknown", PhoneMailboxProbe.probe(spec, "c@x.example", FakeMx(rcpt = 451 to "451 4.7.1 try later").dialer(setOf("mx1.remote.example"))).mailbox)
        PhoneMailboxProbe.reset()
        val policy = PhoneMailboxProbe.probe(spec, "d@x.example", FakeMx(rcpt = 550 to "550 5.7.1 client host blocked").dialer(setOf("mx1.remote.example")))
        assertEquals("a 5.7.x refusal is policy, not a missing mailbox", "unknown", policy.mailbox)
    }

    @Test fun `a down first MX falls to the second, a reachable one is never shopped around`() {
        val mx = FakeMx()
        val r = PhoneMailboxProbe.probe(spec, "e@x.example", mx.dialer(setOf("mx2.remote.example")))
        assertEquals("exists", r.mailbox)
        assertEquals(listOf("mx1.remote.example", "mx2.remote.example"), mx.dialled.map { it.first })
    }

    @Test fun `a phone network that blocks port 25 says so, and the gate answers the next address at once`() {
        val mx = FakeMx()
        val r = PhoneMailboxProbe.probe(spec, "f@x.example", mx.dialer(emptySet()))
        assertFalse(r.connected)
        assertTrue(r.networkBlocked)
        assertEquals("unknown", r.mailbox)
        assertTrue(r.reason, r.reason.contains("this phone's network cannot reach port 25 either"))
        val before = mx.dialled.size
        val next = PhoneMailboxProbe.probe(spec, "g@x.example", mx.dialer(emptySet()))
        assertTrue(next.networkBlocked)
        assertEquals("the closed gate dials nothing", before, mx.dialled.size)
    }

    @Test fun `EHLO SIZE parsing`() {
        assertEquals(1000L, PhoneMailboxProbe.parseSize("250-mx\n250 SIZE 1000"))
        assertEquals(0L, PhoneMailboxProbe.parseSize("250-mx\n250-SIZE\n250 OK"))
        assertNull(PhoneMailboxProbe.parseSize("250-mx\n250 8BITMIME"))
    }

    // -- the wire: the server's blocked answer, then the phone's probe applied --------------------------------------

    private val blockedBody = """
        {"results":[{"address":"info@i-live-berlin.de","verdict":"unknown","reason":"mailbox: outbound :25 blocked on probe host; role address",
          "checks":[{"check":"syntax","outcome":"pass"},{"check":"domain","outcome":"pass","detail":"MX: iliveberlin-de0ce.mail.protection.outlook.com"},
            {"check":"mailbox","outcome":"unknown","detail":"outbound :25 blocked on probe host"},{"check":"past_bounce","outcome":"pass"},
            {"check":"disposable_role","outcome":"warn","detail":"role address"},{"check":"size_limit","outcome":"unknown"}],
          "check_details":{"domain_mx":{"state":"ok","hosts":["iliveberlin-de0ce.mail.protection.outlook.com"]},
            "mailbox":{"state":"unknown","reason":"outbound :25 blocked on probe host","probe_blocked":true,
              "client_probe":{"hosts":["iliveberlin-de0ce.mail.protection.outlook.com"],"helo":"mail.diegonmarcos.com","mail_from":"","port":25}}},
          "cached":false,"checked_at":"2026-11-20T08:00:00Z"},
         {"address":"ok@ok.example","verdict":"ok","reason":"mailbox confirmed","checks":[{"check":"mailbox","outcome":"pass"}],
          "check_details":{"mailbox":{"state":"exists","host":"mx.ok.example"}}}]}
    """.trimIndent()

    @Test fun `only a probe_blocked answer hands over a client probe`() {
        val specs = clientProbeSpecs(blockedBody)
        assertEquals(setOf("info@i-live-berlin.de"), specs.keys)
        assertEquals(ClientProbeSpec(listOf("iliveberlin-de0ce.mail.protection.outlook.com"), "mail.diegonmarcos.com", "", 25), specs.getValue("info@i-live-berlin.de"))
    }

    private fun blockedStatus() = parseRecipientCheckResponse(blockedBody, messageSizeBytes = 2_000).getValue("info@i-live-berlin.de")

    @Test fun `a confirmed mailbox replaces the server's lines, labelled as the phone's, and keeps the role hint risky`() {
        val probe = PhoneProbeResult("mx", true, 1000L, "exists", 250, "mx: 250 2.1.5 OK")
        val s = applyPhoneProbe(blockedStatus(), probe, messageSizeBytes = 500)
        assertEquals(CheckOutcome.PASS, s.check(RecipientCheckKind.MAILBOX).outcome)
        assertEquals(CheckSource.PHONE, s.check(RecipientCheckKind.MAILBOX).source)
        assertEquals(CheckOutcome.PASS, s.check(RecipientCheckKind.SIZE_LIMIT).outcome)
        assertEquals(CheckSource.PHONE, s.check(RecipientCheckKind.SIZE_LIMIT).source)
        assertEquals("role address: risky, as the server would say", RecipientVerdict.RISKY, s.verdict)
        assertTrue(s.serverReason!!.startsWith("Mailbox checked on this phone"))
        assertEquals("every other line is the server's, nothing dropped", 6, s.checks.size)
    }

    @Test fun `a missing mailbox or an oversized message will fail, and a phone that cannot tell keeps the server's verdict`() {
        assertEquals(RecipientVerdict.WILL_FAIL, applyPhoneProbe(blockedStatus(), PhoneProbeResult("mx", true, null, "does_not_exist", 550, "550 5.1.1"), null).verdict)
        assertEquals(RecipientVerdict.WILL_FAIL, applyPhoneProbe(blockedStatus(), PhoneProbeResult("mx", true, 1000L, "exists", 250, "ok"), 5_000).verdict)
        val blocked = applyPhoneProbe(blockedStatus(), PhoneProbeResult("mx", false, null, "unknown", null, "this phone's network cannot reach port 25 either", networkBlocked = true), null)
        assertEquals(RecipientVerdict.UNKNOWN, blocked.verdict)
        assertTrue(blocked.serverReason!!.contains("neither can this phone's network"))
        assertTrue(blocked.check(RecipientCheckKind.MAILBOX).detail!!.contains("cannot reach port 25 either"))
    }

    @Test fun `the fleet client runs the phone probe only for blocked answers, bounded, off the main thread`() {
        val src = listOf("src/main/kotlin/app/sterna/fleet/FleetRecipientChecker.kt", "app/src/main/kotlin/app/sterna/fleet/FleetRecipientChecker.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("withPhoneProbes(parseRecipientCheckResponse(text, request.messageSizeBytes), clientProbeSpecs(text), request.messageSizeBytes)"))
        assertTrue(src.contains("async(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(src.contains("withTimeoutOrNull(PHONE_PROBE_TIMEOUT_MILLIS)"))
        val probe = listOf("src/main/kotlin/app/sterna/fleet/PhoneMailboxProbe.kt", "app/src/main/kotlin/app/sterna/fleet/PhoneMailboxProbe.kt")
            .map(::File).first { it.exists() }.readText()
        assertFalse("no code path writes DATA", Regex("""say\("DATA""").containsMatchIn(probe))
        assertTrue(IOException::class.java.isAssignableFrom(SocketTimeoutException::class.java))
    }
}
