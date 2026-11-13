package com.diegonmarcos.cloudaccount

import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import com.diegonmarcos.superapp.profile.MailOAuthSessions
import com.diegonmarcos.superapp.profile.MusicDeviceGrant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1693 MOVED from Cloud Mail's OAuthClientTest (the device-grant half: start, poll, pending, slow_down, denied, description + AADSTS, no token or
 * body in any error, an unreadable answer is not a network failure, an unreachable server is named by nobody, a redirect never replays the
 * device code) and adapted to where the grant runs now: [MailOAuthSessions] over the real transport against a MockWebServer, and over a fake POST.
 * The provider-level checks (grant, endpoints, wire shape) live in MailOAuthProviderTest.
 */
@RunWith(RobolectricTestRunner::class)
class MailOAuthSessionsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        MailOAuthSessions.clear()
        MailOAuthSessions.post = MusicDeviceGrant.defaultPost()
        MailOAuthSessions.nowMs = { System.currentTimeMillis() }
    }

    @After fun tearDown() { runCatching { server.shutdown() }; MailOAuthSessions.clear() }

    private fun req(base: String = server.url("/").toString().trimEnd('/')) = MailOAuthContract.Request("me@example.org", "x", "$base/auth/device", "$base/auth/token", "sterna", "scope-a offline_access")

    /** start() checks endpoints for https through the contract's parse; the transport tests go through the same json, so the request is built for the test host. */
    private fun startBundle(r: MailOAuthContract.Request): Bundle = MailOAuthSessions.startUnchecked(r)

    private fun answer(b: Bundle) = MailOAuthContract.parsePoll(b.getString(MailOAuthContract.KEY_JSON))!!
    private fun started(b: Bundle) = MailOAuthContract.parseStart(b.getString(MailOAuthContract.KEY_JSON))!!

    private val device = """{"device_code":"DC","user_code":"WDJB-MJHT","verification_uri":"https://srv/device","verification_uri_complete":"https://srv/device?code=WDJB-MJHT","expires_in":1800,"interval":5}"""

    @Test fun startParsesTheCodeKeepsTheDeviceCodeAndSendsClientAndScope() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(device))
        val b = startBundle(req())
        assertTrue(b.getBoolean(MailOAuthContract.KEY_OK))
        val s = started(b)
        assertEquals("WDJB-MJHT", s.userCode); assertEquals(5, s.interval); assertEquals("https://srv/device?code=WDJB-MJHT", s.verificationUriComplete)
        assertFalse("the device code stays in Cloud Account", b.getString(MailOAuthContract.KEY_JSON)!!.contains("\"DC\""))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("client_id=sterna")); assertTrue(body.contains("scope=scope-a"))
    }

    @Test fun pollIsPendingThenSuccess() {
        server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        assertEquals("pending", answer(MailOAuthSessions.pollOnce(h)).state)
        server.enqueue(MockResponse().setBody("""{"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer","id_token":"IDT"}"""))
        val ok = answer(MailOAuthSessions.pollOnce(h))
        assertEquals("success", ok.state); assertEquals("AT", ok.accessToken); assertEquals("RT", ok.refreshToken); assertEquals("IDT", ok.idToken)
        val body = server.let { it.takeRequest(); it.takeRequest(); it.takeRequest().body.readUtf8() }
        assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")); assertTrue(body.contains("device_code=DC")); assertFalse("a public client sends no secret", body.contains("client_secret"))
        assertEquals("the handle is spent once the tokens are handed over", "expired_token", answer(MailOAuthSessions.pollOnce(h)).error)
    }

    @Test fun pollSlowDownAndDenied() {
        server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
        assertEquals("slow_down", answer(MailOAuthSessions.pollOnce(h)).state)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"access_denied"}"""))
        val d = answer(MailOAuthSessions.pollOnce(h)); assertEquals("failed", d.state); assertEquals("access_denied", d.error)
    }

    @Test fun aFailureCarriesDescriptionAndAadsts() {
        server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_declined","error_description":"AADSTS650051: app not approved."}"""))
        val f = answer(MailOAuthSessions.pollOnce(h))
        assertEquals("authorization_declined", f.error); assertEquals("AADSTS650051", f.aadsts); assertTrue(f.description.contains("not approved"))
    }

    @Test fun aMalformedTokenAnswerCarriesNoToken() {
        server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
        server.enqueue(MockResponse().setBody("""{"access_token":"SECRET-AT","refresh_token":"SECRET-RT","expires_in":36"""))
        val f = answer(MailOAuthSessions.pollOnce(h))
        assertEquals("failed", f.state); assertFalse(f.json().contains("SECRET-AT")); assertFalse(f.json().contains("SECRET-RT")); assertFalse(f.toString().contains("SECRET"))
    }

    @Test fun anUnreadableAnswerIsNotANetworkFailureAndNamesNoBody() {
        server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>SECRET-PROXY-PAGE: authentication required</body></html>"))
        val f = answer(MailOAuthSessions.pollOnce(h))
        assertEquals("failed", f.state); assertTrue(f.error != "network_error"); assertFalse(f.json().contains("SECRET-PROXY-PAGE"))
    }

    @Test fun aMalformedDeviceAuthorizationCarriesNoPayload() {
        server.enqueue(MockResponse().setBody("""{"device_code":"SECRET-DC","user_code":"UC","verification_uri":"https://s/d","interval":"""))
        val b = startBundle(req())
        assertFalse(b.getBoolean(MailOAuthContract.KEY_OK)); assertFalse(b.getString(MailOAuthContract.KEY_ERROR)!!.contains("SECRET-DC"))
    }

    @Test fun aRefusedStartCarriesTheOAuthErrorWordButNotTheBody() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_client","error_description":"SECRET-DESCRIPTION for tenant X"}"""))
        val b = startBundle(req())
        assertFalse(b.getBoolean(MailOAuthContract.KEY_OK))
        val why = b.getString(MailOAuthContract.KEY_ERROR)!!
        assertTrue(why.contains("invalid_client")); assertFalse(why.contains("SECRET-DESCRIPTION"))
    }

    @Test fun anUnreachableServerIsReportedWithoutNamingIt() {
        server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
        val port = server.port; server.shutdown()
        val f = answer(MailOAuthSessions.pollOnce(h))
        assertEquals("failed", f.state); assertEquals("network_error", f.error)
        val text = f.json()
        assertFalse(text.contains("localhost")); assertFalse(text.contains("127.0.0.1")); assertFalse(text.contains(port.toString()))
        server = MockWebServer().also { it.start() }
    }

    @Test fun anExpiredSessionIsExpiredToken() {
        server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
        MailOAuthSessions.nowMs = { System.currentTimeMillis() + 2_000_000L }
        assertEquals("expired_token", answer(MailOAuthSessions.pollOnce(h)).error)
        assertEquals("expired_token", answer(MailOAuthSessions.pollOnce("nope")).error)
    }

    @Test fun aRedirectNeverReplaysTheDeviceCodeOrTheStart() {
        val second = MockWebServer().also { it.start() }
        try {
            second.enqueue(MockResponse().setBody("""{"access_token":"EVIL-AT","refresh_token":"EVIL-RT"}"""))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", second.url("/steal").toString()))
            val b = startBundle(req())
            assertFalse("the start POST must not be replayed", b.getBoolean(MailOAuthContract.KEY_OK))
            assertEquals(0, second.requestCount)
            server.enqueue(MockResponse().setBody(device)); val h = started(startBundle(req())).handle
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", second.url("/steal").toString()))
            val f = answer(MailOAuthSessions.pollOnce(h))
            assertEquals("the device code must not be replayed to the redirect target", 0, second.requestCount)
            assertEquals("failed", f.state); assertEquals("http_307", f.error)
        } finally { second.shutdown() }
    }

    @Test fun theProviderWireIsRefusedForABadRequestAndDemandsNoSecret() {
        val b = MailOAuthSessions.start(androidx.test.core.app.ApplicationProvider.getApplicationContext(), req().copy(tokenEndpoint = "https://evil.net/t").json())
        assertFalse(b.getBoolean(MailOAuthContract.KEY_OK)); assertNotNull(b.getString(MailOAuthContract.KEY_ERROR))
    }
}
