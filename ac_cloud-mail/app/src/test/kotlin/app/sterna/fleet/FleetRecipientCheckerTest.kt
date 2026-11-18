package app.sterna.fleet

import app.sterna.ui.compose.CheckOutcome
import app.sterna.ui.compose.RecipientCheckKind
import app.sterna.ui.compose.RecipientVerdict
import app.sterna.ui.compose.mergeRecipientStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1803 the wire mapping of POST /v1/recipients/check (DELIVERY-API.md, cloudy-u-containers cfcbd70). */
class FleetRecipientCheckerTest {

    private val body = """
        {"results":[
          {"address":"ok@ok.example","verdict":"ok","reason":"mailbox exists","cached":false,"checked_at":"2026-11-18T11:00:00Z",
           "checks":{"syntax":{"state":"valid"},"domain_mx":{"state":"ok","hosts":["mx1.ok.example","mx2.ok.example"]},
             "mailbox":{"state":"exists","host":"mx1.ok.example","code":250,"reason":"2.1.5 ok"},
             "past_bounce":{"state":"none"},"disposable":false,"role":false,"size_limit":{"bytes":10485760}}},
          {"address":"all@catch.example","verdict":"risky","reason":"accepts all","cached":true,"checked_at":"2026-11-18T10:59:00Z",
           "checks":{"syntax":{"state":"valid"},"domain_mx":{"state":"ok","hosts":["mx.catch.example"]},
             "mailbox":{"state":"accepts_all","host":"mx.catch.example","code":250},
             "past_bounce":{"state":"none"},"disposable":{"state":"false"},"role":{"value":true},"size_limit":{"bytes":null}}},
          {"address":"x@nomx.example","verdict":"fail","reason":"null MX",
           "checks":{"syntax":{"state":"valid"},"domain_mx":{"state":"fail","hosts":[],"null_mx":true},
             "mailbox":{"state":"not_checked"},"past_bounce":{"state":"bounced","at":"2026-11-10T08:00:00Z","reason":"550 5.1.1"}}},
          {"address":"far@remote.example","verdict":"unknown","reason":"cannot reach mx.remote.example:25",
           "checks":{"syntax":{"state":"valid"},"domain_mx":{"state":"ok","hosts":["mx.remote.example"],"implicit":true},
             "mailbox":{"state":"unknown","host":"mx.remote.example","reason":"cannot reach mx.remote.example:25"},
             "size_limit":{"bytes":1000}}}
        ]}
    """.trimIndent()

    private val parsed = parseRecipientCheckResponse(body, messageSizeBytes = 2_000)

    @Test fun `every verdict maps, fail to will-fail`() {
        assertEquals(RecipientVerdict.OK, parsed.getValue("ok@ok.example").verdict)
        assertEquals(RecipientVerdict.RISKY, parsed.getValue("all@catch.example").verdict)
        assertEquals(RecipientVerdict.WILL_FAIL, parsed.getValue("x@nomx.example").verdict)
    }

    @Test fun `every check of a full answer is read, with the server's details`() {
        val ok = parsed.getValue("ok@ok.example")
        assertEquals(RecipientCheckKind.entries.toSet(), ok.checks.map { it.kind }.toSet())
        assertTrue(ok.checks.all { it.outcome == CheckOutcome.PASS })
        assertTrue(ok.check(RecipientCheckKind.DOMAIN).detail!!.contains("mx1.ok.example, mx2.ok.example"))
        assertTrue(ok.check(RecipientCheckKind.MAILBOX).detail!!.contains("mx1.ok.example 250"))
        assertEquals("mailbox exists", ok.serverReason)
        assertEquals(java.time.Instant.parse("2026-11-18T11:00:00Z").toEpochMilli(), ok.checkedAtMillis)
        assertFalse(ok.serverCached)
    }

    @Test fun `accepts-all is a warning with its flag, a role mailbox a hint, a missing limit unknown`() {
        val all = parsed.getValue("all@catch.example")
        assertEquals(CheckOutcome.WARN, all.check(RecipientCheckKind.MAILBOX).outcome)
        assertTrue(all.check(RecipientCheckKind.MAILBOX).acceptsAll)
        assertEquals(CheckOutcome.WARN, all.check(RecipientCheckKind.DISPOSABLE_ROLE).outcome)
        assertTrue(all.check(RecipientCheckKind.DISPOSABLE_ROLE).detail!!.contains("role"))
        assertEquals(CheckOutcome.UNKNOWN, all.check(RecipientCheckKind.SIZE_LIMIT).outcome)
        assertTrue(all.serverCached)
    }

    @Test fun `null MX fails the domain, not-checked is unknown, a past bounce is a warning with its date`() {
        val x = parsed.getValue("x@nomx.example")
        assertEquals(CheckOutcome.FAIL, x.check(RecipientCheckKind.DOMAIN).outcome)
        assertEquals(CheckOutcome.UNKNOWN, x.check(RecipientCheckKind.MAILBOX).outcome)
        assertEquals(CheckOutcome.WARN, x.check(RecipientCheckKind.PAST_BOUNCE).outcome)
        assertTrue(x.check(RecipientCheckKind.PAST_BOUNCE).detail!!.contains("2026-11-10"))
        val merged = mergeRecipientStatus("x@nomx.example", x, 1)
        assertEquals("checks the server left out are unknown after the merge", CheckOutcome.UNKNOWN, merged.check(RecipientCheckKind.SIZE_LIMIT).outcome)
    }

    @Test fun `an unreachable mailbox is unknown, never a failure, and the size limit is compared on the phone`() {
        val far = parsed.getValue("far@remote.example")
        assertEquals(CheckOutcome.UNKNOWN, far.check(RecipientCheckKind.MAILBOX).outcome)
        assertTrue(far.check(RecipientCheckKind.DOMAIN).detail!!.contains("implicit"))
        assertEquals(CheckOutcome.FAIL, far.check(RecipientCheckKind.SIZE_LIMIT).outcome)
        assertEquals("a message over the limit fails whatever the server said", RecipientVerdict.WILL_FAIL, far.verdict)
        val small = parseRecipientCheckResponse(body, messageSizeBytes = 500).getValue("far@remote.example")
        assertEquals(CheckOutcome.PASS, small.check(RecipientCheckKind.SIZE_LIMIT).outcome)
        assertEquals(RecipientVerdict.UNKNOWN, small.verdict)
    }

    @Test fun `errors carry the server's code, and an empty or odd answer yields nothing`() {
        assertEquals("HTTP 429 · rate_limited · slow down", recipientCheckError(429, """{"error":"slow down","code":"rate_limited"}"""))
        assertEquals("HTTP 502", recipientCheckError(502, "<html>bad gateway</html>"))
        assertTrue(parseRecipientCheckResponse("""{"results":[]}""", null).isEmpty())
        assertTrue(parseRecipientCheckResponse("""{}""", null).isEmpty())
        assertTrue(parseRecipientCheckResponse("""{"results":[{"verdict":"ok"}]}""", null).isEmpty())
    }

    @Test fun `the client is bound at start, posts to the contract's path in batches of fifty, its endpoints declared once`() {
        val app = listOf("src/main/kotlin/app/sterna/SternaApplication.kt", "app/src/main/kotlin/app/sterna/SternaApplication.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(app.contains("RecipientChecks.checker = app.sterna.fleet.FleetRecipientChecker"))
        assertEquals("/v1/recipients/check", FleetRecipientChecker.PATH)
        assertEquals(50, FleetRecipientChecker.MAX_BATCH)
        assertTrue(app.contains("app.sterna.fleet.FleetRecipientChecker.bind(this)"))
        assertEquals("communication-mcp", FleetRecipientChecker.SERVICE_ID)
        // the service is declared once, in Cloud Account's fleet_endpoints, and Mail's cleartext mesh list is generated from it
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "ac_cloud-account/build.json").isFile }
        val decl = org.json.JSONObject(File(root, "ac_cloud-account/build.json").readText()).getJSONObject("fleet_endpoints").getJSONArray("services")
        val svc = (0 until decl.length()).map { decl.getJSONObject(it) }.single { it.getString("id") == FleetRecipientChecker.SERVICE_ID }
        assertEquals("cloudy-apps-communication-mcp.mesh.diegonmarcos.com", svc.getString("mesh_dns"))
        assertTrue(svc.getJSONArray("route_paths").toString().contains("/v1"))
        val nsc = File(root, "ac_cloud-mail/app/src/main/res/xml/network_security_config.xml").readText()
        assertTrue(nsc.contains(">cloudy-apps-communication-mcp.mesh.diegonmarcos.com<"))
    }

    // -- the current shape (cloudy-u-containers 07f51da): checks as a list, in the client's own terms ---------------

    private val listBody = """
        {"results":[
          {"address":"a@b.example","verdict":"will_fail","reason":"domain has no mail server","cached":true,
           "checked_at":"2026-11-18T11:20:00Z",
           "checks":[{"check":"syntax","outcome":"pass"},
                     {"check":"domain","outcome":"fail","detail":"no MX and no A record"},
                     {"check":"mailbox","outcome":"unknown","detail":"cannot reach mx:25"},
                     {"check":"past_bounce","outcome":"pass"},
                     {"check":"disposable_role","outcome":"warn","detail":"role mailbox"},
                     {"check":"size_limit","outcome":"unknown"},
                     {"check":"something_new","outcome":"pass"}],
           "check_details":{"domain":{"hosts":[],"null_mx":false},"mailbox":"probe host has outbound 25 blocked"}},
          {"address":"c@d.example","verdict":"risky",
           "checks":[{"check":"mailbox","outcome":"warn","accepts_all":true}]}
        ]}
    """.trimIndent()

    @Test fun `the list shape maps verdict, each check, its detail and the accepts-all flag`() {
        val out = parseRecipientCheckResponse(listBody, messageSizeBytes = 100)
        val a = out.getValue("a@b.example")
        assertEquals(RecipientVerdict.WILL_FAIL, a.verdict)
        assertEquals(RecipientCheckKind.entries.toSet(), a.checks.map { it.kind }.toSet())
        assertEquals(CheckOutcome.FAIL, a.check(RecipientCheckKind.DOMAIN).outcome)
        assertEquals(CheckOutcome.UNKNOWN, a.check(RecipientCheckKind.MAILBOX).outcome)
        assertEquals(CheckOutcome.WARN, a.check(RecipientCheckKind.DISPOSABLE_ROLE).outcome)
        assertTrue(a.check(RecipientCheckKind.DOMAIN).detail!!.startsWith("no MX and no A record"))
        assertTrue("check_details are kept", a.check(RecipientCheckKind.DOMAIN).detail!!.contains("null_mx: false"))
        assertTrue(a.check(RecipientCheckKind.MAILBOX).detail!!.contains("outbound 25 blocked"))
        assertTrue(a.serverCached)
        assertEquals("domain has no mail server", a.serverReason)
        val c = out.getValue("c@d.example")
        assertEquals(RecipientVerdict.RISKY, c.verdict)
        assertTrue(c.check(RecipientCheckKind.MAILBOX).acceptsAll)
        val merged = mergeRecipientStatus("c@d.example", c, 1)
        assertEquals(RecipientCheckKind.entries, merged.checks.map { it.kind })
        assertEquals(RecipientVerdict.RISKY, merged.verdict)
    }

    @Test fun `the request carries the message size`() {
        val src = listOf("src/main/kotlin/app/sterna/fleet/FleetRecipientChecker.kt", "app/src/main/kotlin/app/sterna/fleet/FleetRecipientChecker.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("o.put(\"message_size_bytes\", it)"))
    }
}
