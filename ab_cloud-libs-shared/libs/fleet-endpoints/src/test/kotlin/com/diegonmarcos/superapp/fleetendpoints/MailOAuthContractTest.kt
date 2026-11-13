package com.diegonmarcos.superapp.fleetendpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailOAuthContractTest {
    private val ok = MailOAuthContract.Request("me@example.org", "mail.example.org", "https://auth.example.org/device", "https://auth.example.org/token", "cid", "mail")

    @Test fun `a request round-trips`() = assertEquals(ok, MailOAuthContract.parse(ok.json()))

    @Test fun `an endpoint must be https and the host asked or the address domain`() {
        assertTrue(MailOAuthContract.endpointAllowed("https://mail.example.org/token", "mail.example.org", "example.org"))
        assertTrue(MailOAuthContract.endpointAllowed("https://auth.example.org/x", "mail.example.org", "example.org"))
        assertTrue(MailOAuthContract.endpointAllowed("https://login.mail.example.org:8443/x", "https://mail.example.org/jmap", "other.net"))
        assertFalse("cleartext", MailOAuthContract.endpointAllowed("http://auth.example.org/x", "mail.example.org", "example.org"))
        assertFalse("someone else's host", MailOAuthContract.endpointAllowed("https://evil.net/x", "mail.example.org", "example.org"))
        assertFalse("a look-alike suffix", MailOAuthContract.endpointAllowed("https://notexample.org/x", "mail.example.org", "example.org"))
        assertFalse("userinfo trick", MailOAuthContract.endpointAllowed("https://example.org@evil.net/x", "mail.example.org", "example.org"))
        assertFalse(MailOAuthContract.endpointAllowed("", "mail.example.org", "example.org"))
    }

    @Test fun `a request with a foreign endpoint, no address, no client or another version is refused`() {
        assertNull(MailOAuthContract.parse(ok.copy(tokenEndpoint = "https://evil.net/token").json()))
        assertNull(MailOAuthContract.parse(ok.copy(email = "nobody").json()))
        assertNull(MailOAuthContract.parse(ok.copy(clientId = " ").json()))
        assertNull(MailOAuthContract.parse("""{"v":2}"""))
        assertNull(MailOAuthContract.parse(null)); assertNull(MailOAuthContract.parse("not json"))
    }

    @Test fun `ids are catalogue ids and flat`() {
        for (f in listOf("access", "refresh", "expires")) assertNotNull(AppCredentialsContract.entry(MailOAuthContract.credId(" Me@Example.org", f)))
        assertEquals("mail.oauth.me_example_org.access", MailOAuthContract.credId("Me@Example.org", MailOAuthContract.FIELD_ACCESS))
        assertTrue(AppCredentialsContract.defaultGrants(AppCredentialsContract.PKG_MAIL).contains("cred.mail.oauth.*"))
        assertTrue(MailOAuthContract.refusal(null).contains("no reason given"))
    }

    @Test fun `the start and poll answers round-trip, refuse another version and carry no token in a log form`() {
        val st = MailOAuthContract.StartAnswer("H", "UC", "https://v", "https://v?c=1", 5, 900)
        assertEquals(st, MailOAuthContract.parseStart(st.json()))
        assertNull(MailOAuthContract.parseStart("""{"v":2,"handle":"H","user_code":"U"}""")); assertNull(MailOAuthContract.parseStart("""{"v":1,"handle":"","user_code":"U"}"""))
        val ok = MailOAuthContract.PollAnswer("success", "SECRET-AT", "SECRET-RT", 60, "IDT", "mail")
        val back = MailOAuthContract.parsePoll(ok.json())!!
        assertEquals("SECRET-AT", back.accessToken); assertEquals("IDT", back.idToken)
        assertFalse(ok.toString().contains("SECRET"))
        assertNull(MailOAuthContract.parsePoll("""{"v":1,"state":"teapot"}""")); assertNull(MailOAuthContract.parsePoll(null))
    }

    // ---- #1703 the authorization-code grant ----
    private val code = MailOAuthContract.CodeRequest("me@example.org", "mail.example.org", "https://auth.example.org/authorize", "https://auth.example.org/token", "cid", "mail offline_access", "app.sterna://oauth")

    @Test fun `a code request round-trips for the package whose scheme it names`() = assertEquals(code, MailOAuthContract.parseCode(code.json(), "app.sterna"))

    @Test fun `a code request is refused for another caller, a web redirect, a foreign or cleartext endpoint or no caller`() {
        assertNull("another app's scheme", MailOAuthContract.parseCode(code.json(), "evil.app"))
        assertNull("no caller", MailOAuthContract.parseCode(code.json(), null))
        assertNull("a web page", MailOAuthContract.parseCode(code.copy(redirectUri = "https://evil.net/cb").json(), "app.sterna"))
        assertNull("another path", MailOAuthContract.parseCode(code.copy(redirectUri = "app.sterna://other").json(), "app.sterna"))
        assertNull(MailOAuthContract.parseCode(code.copy(authorizationEndpoint = "https://evil.net/authorize").json(), "app.sterna"))
        assertNull(MailOAuthContract.parseCode(code.copy(authorizationEndpoint = "http://auth.example.org/authorize").json(), "app.sterna"))
        assertNull(MailOAuthContract.parseCode(code.copy(tokenEndpoint = "https://evil.net/token").json(), "app.sterna"))
        assertNull(MailOAuthContract.parseCode(code.copy(email = "nobody").json(), "app.sterna"))
        assertNull(MailOAuthContract.parseCode(code.copy(clientId = " ").json(), "app.sterna"))
        assertNull(MailOAuthContract.parseCode("""{"v":2}""", "app.sterna"))
        assertNull(MailOAuthContract.parseCode("nope", "app.sterna"))
    }

    @Test fun `the code start answer and the finish round-trip and no log form carries a code`() {
        val a = MailOAuthContract.CodeStartAnswer("H1", 600)
        assertEquals(a, MailOAuthContract.parseCodeStart(a.json())); assertNull(MailOAuthContract.parseCodeStart("""{"v":1}"""))
        val f = MailOAuthContract.CodeFinish("H1", "SECRET-CODE", "ST", null)
        assertEquals(f, MailOAuthContract.parseCodeFinish(f.json()))
        assertFalse(f.toString().contains("SECRET")); assertFalse(a.toString().contains("H1"))
        assertNull(MailOAuthContract.parseCodeFinish("""{"v":1}"""))
        assertEquals("denied", MailOAuthContract.parseCodeFinish(MailOAuthContract.CodeFinish("H", null, "S", "denied").json())!!.error)
    }
}
