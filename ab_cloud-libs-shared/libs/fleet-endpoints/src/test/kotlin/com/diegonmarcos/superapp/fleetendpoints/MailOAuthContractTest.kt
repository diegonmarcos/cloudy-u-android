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
}
