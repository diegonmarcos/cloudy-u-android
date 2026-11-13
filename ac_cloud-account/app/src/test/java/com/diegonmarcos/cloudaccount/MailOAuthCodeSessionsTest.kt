package com.diegonmarcos.cloudaccount

import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import com.diegonmarcos.superapp.profile.MailOAuthCodeSessions
import com.diegonmarcos.superapp.profile.MailOAuthCodeSessions.Pkce
import com.diegonmarcos.superapp.profile.MailOAuthCodeSessions.Verdict
import com.diegonmarcos.superapp.profile.MailOAuthCodeSessions.buildAuthorizationUrl
import com.diegonmarcos.superapp.profile.MailOAuthCodeSessions.redirectVerdict
import com.diegonmarcos.superapp.profile.MailOAuthCodeSessions.tokenExchangeFor
import com.diegonmarcos.superapp.profile.MusicDeviceGrant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder

/**
 * #1703 MOVED from Cloud Mail (OAuthClientTest: the PKCE draw and the RFC 7636 vector, the authorization URL pinned as sent, https only, our parameters replacing
 * the endpoint's, the code exchange with its grant fields / no redirect replay / no token or description in an error; OAuthRedirectTest: the state check before
 * anything is read, one redeem per request, a forged return not consuming the real one, the state's draw) and adapted to where the grant runs now:
 * [MailOAuthCodeSessions] over the real transport against a MockWebServer. Nothing here is skipped: every case of the originals is executed.
 */
@RunWith(RobolectricTestRunner::class)
class MailOAuthCodeSessionsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        MailOAuthCodeSessions.clear()
        MailOAuthCodeSessions.post = MusicDeviceGrant.defaultPost()
        MailOAuthCodeSessions.nowMs = { System.currentTimeMillis() }
    }

    @After fun tearDown() { runCatching { server.shutdown() }; MailOAuthCodeSessions.clear() }

    private val AUTHORIZE = "https://idp.example.com/authorize"
    private fun base() = server.url("/").toString().trimEnd('/')
    private fun req(redirect: String = "app.sterna://oauth") = MailOAuthContract.CodeRequest("me@example.org", "x", "https://idp.example.com/authorize", "${base()}/auth/token", "sterna", "scope-a offline_access", redirect)
    private fun started(b: Bundle) = MailOAuthContract.parseCodeStart(b.getString(MailOAuthContract.KEY_JSON))!!
    private fun answer(b: Bundle) = MailOAuthContract.parsePoll(b.getString(MailOAuthContract.KEY_JSON))!!
    private fun q(url: String) = formFields(url.substringAfter("?"))
    private fun formFields(encoded: String): Map<String, String> = encoded.split("&").filter { it.isNotEmpty() }.associate { pair ->
        val cut = pair.indexOf('=')
        val name = if (cut < 0) pair else pair.substring(0, cut); val value = if (cut < 0) "" else pair.substring(cut + 1)
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

    /** Starts a sign-in and returns (handle, the state and the challenge the browser URL carries). */
    private fun begin(r: MailOAuthContract.CodeRequest = req()): Triple<String, String, String> {
        val h = started(MailOAuthCodeSessions.startUnchecked(r)).handle
        val url = MailOAuthCodeSessions.authorizationUrl(h)!!
        return Triple(h, q(url)["state"]!!, q(url)["code_challenge"]!!)
    }

    // ---- PKCE (RFC 7636) ----

    @Test fun pkce_pinsTheRfc7636AppendixBVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.codeChallengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals("S256", Pkce.CODE_CHALLENGE_METHOD)
    }

    @Test fun pkce_drawsFromACryptographicSource() {
        val source: java.util.Random = Pkce.random
        assertTrue("a predictable generator makes PKCE decorative, got ${source.javaClass.name}", source is java.security.SecureRandom)
    }

    @Test fun pkce_refusesALengthOutsideTheRfcsRange() {
        assertTrue("42 is below RFC 7636 §4.1", runCatching { Pkce.newCodeVerifier(42) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue("129 is above RFC 7636 §4.1", runCatching { Pkce.newCodeVerifier(129) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(43, Pkce.newCodeVerifier(43).length); assertEquals(128, Pkce.newCodeVerifier(128).length)
    }

    @Test fun pkce_drawsAFreshVerifierWithinTheUnreservedAlphabet() {
        val first = Pkce.newCodeVerifier(); val second = Pkce.newCodeVerifier()
        assertNotEquals("two verifiers drawn in a row must differ", first, second)
        val legal = Regex("^[A-Za-z0-9._~-]{43,128}$")   // spelled out here, deliberately not read from the production constant
        assertTrue("outside RFC 7636 §4.1: $first", legal.matches(first)); assertTrue(legal.matches(second))
    }

    @Test fun twoStatesDrawnInARowDifferAndAreUrlSafe() {
        val a = MailOAuthCodeSessions.newState(); val b = MailOAuthCodeSessions.newState()
        assertNotEquals("a state that repeats matches somebody else's redirect", a, b)
        val safe = Regex("^[A-Za-z0-9._~-]{43,}$")
        assertTrue("not URL-safe: $a", safe.matches(a)); assertTrue(safe.matches(b))
    }

    // ---- the authorization URL ----

    @Test fun authorizationUrl_carriesEveryParameterOfTheGrant() {
        val url = buildAuthorizationUrl(AUTHORIZE, "sterna", "app.sterna://oauth2/callback", "urn:ietf:params:jmap:core offline_access", "st4te-value", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertEquals("the endpoint itself must be untouched", AUTHORIZE, url.substringBefore("?"))
        val q = formFields(url.substringAfter("?"))
        assertEquals("code", q["response_type"]); assertEquals("sterna", q["client_id"]); assertEquals("app.sterna://oauth2/callback", q["redirect_uri"])
        assertEquals("urn:ietf:params:jmap:core offline_access", q["scope"]); assertEquals("st4te-value", q["state"])
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", q["code_challenge"]); assertEquals("S256", q["code_challenge_method"])
        assertEquals("no parameter beyond the seven the grant defines: $q", 7, q.size)
        assertFalse("an unencoded value would truncate the URL: $url", url.contains(" "))
    }

    @Test fun authorizationUrl_addsToAQueryTheEndpointAlreadyCarried() {
        val url = buildAuthorizationUrl("https://idp.example.com/login?tenant=acme", "sterna", "app.sterna://oauth2/callback", "scope-a", "st4te", "CHALLENGE")
        val q = formFields(url.substringAfter("?"))
        assertEquals("the endpoint's own query must survive: $url", "acme", q["tenant"]); assertEquals("code", q["response_type"]); assertEquals("CHALLENGE", q["code_challenge"])
        assertEquals("its query plus our seven: $q", 8, q.size)
    }

    @Test fun authorizationUrl_isPinnedAsSentAndEncodesHostileValues() {
        val url = buildAuthorizationUrl(AUTHORIZE, "sterna", "app.sterna://oauth2/callback", "urn:ietf:params:jmap:core offline_access", "st4te&injecte=1~x", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertEquals("https://idp.example.com/authorize?response_type=code&client_id=sterna&redirect_uri=app.sterna%3A%2F%2Foauth2%2Fcallback" +
            "&scope=urn%3Aietf%3Aparams%3Ajmap%3Acore%20offline_access&state=st4te%26injecte%3D1~x&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256", url)
        val q = formFields(url.substringAfter("?"))
        assertEquals("the state must stay one value: $q", 7, q.size); assertEquals("st4te&injecte=1~x", q["state"]); assertNull("the injected parameter must not exist: $q", q["injecte"])
    }

    @Test fun authorizationUrl_ourParametersReplaceTheOnesTheEndpointCarried() {
        val url = buildAuthorizationUrl("https://idp.example.com/authorize?client_id=someone-else&tenant=acme&state=stale", "sterna", "app.sterna://cb", "scope-a", "st4te", "CHALLENGE")
        val query = url.substringAfter("?")
        assertEquals("one client_id, and it is ours: $url", 1, Regex("(^|&)client_id=").findAll(query).count())
        assertEquals("one state, and it is ours: $url", 1, Regex("(^|&)state=").findAll(query).count())
        val q = formFields(query)
        assertEquals("sterna", q["client_id"]); assertEquals("st4te", q["state"]); assertEquals("what the endpoint carried and we do not set must survive", "acme", q["tenant"])
        assertEquals("its own parameter plus our seven: $q", 8, q.size)
    }

    @Test fun authorizationUrl_refusesAnEndpointThatIsNotHttps() {
        listOf("http://idp.example.com/authorize", "HTTP://idp.example.com/authorize", "app.sterna://authorize", "idp.example.com/authorize").forEach { endpoint ->
            val outcome = runCatching { buildAuthorizationUrl(endpoint, "sterna", "app.sterna://cb", "s", "st", "ch") }
            assertTrue("a browser must never be sent to $endpoint, got $outcome", outcome.exceptionOrNull() is IllegalArgumentException)
            val text = outcome.exceptionOrNull()?.message.orEmpty()
            assertFalse("the refusal quoted the endpoint: $text", text.contains(endpoint, ignoreCase = true))
            assertFalse("the refusal named the host: $text", text.contains("idp.example.com")); assertFalse("the refusal named the client: $text", text.contains("sterna"))
        }
        assertTrue(buildAuthorizationUrl(AUTHORIZE, "sterna", "app.sterna://cb", "s", "st", "ch").startsWith("https://idp.example.com/authorize?"))
    }

    // ---- the start: what the browser is sent to is drawn HERE ----

    @Test fun aStartAnswersOnlyAHandleAndTheBrowserUrlCarriesThePkceChallengeAndTheState() {
        val b = MailOAuthCodeSessions.startUnchecked(req())
        assertTrue(b.getBoolean(MailOAuthContract.KEY_OK))
        val json = b.getString(MailOAuthContract.KEY_JSON)!!
        assertFalse("no URL, state or challenge rides the answer to Cloud Mail", json.contains("https://") || json.contains("state") || json.contains("challenge"))
        val url = MailOAuthCodeSessions.authorizationUrl(started(b).handle)!!
        val q = q(url)
        assertEquals("S256", q["code_challenge_method"]); assertEquals("app.sterna://oauth", q["redirect_uri"]); assertEquals("sterna", q["client_id"]); assertEquals(43, q["state"]!!.length)
        assertNull("an unknown handle opens nothing", MailOAuthCodeSessions.authorizationUrl("nope"))
    }

    @Test fun aStartWithAnEndpointThatIsNotHttpsIsRefusedNotHalfBuilt() {
        val b = MailOAuthCodeSessions.startUnchecked(req().copy(authorizationEndpoint = "http://idp.example.com/authorize"))
        assertFalse(b.getBoolean(MailOAuthContract.KEY_OK))
        assertFalse(b.getString(MailOAuthContract.KEY_ERROR)!!.contains("idp.example.com"))
    }

    @Test fun aNewSignInDoesNotKeepAnExpiredOneAndTheWaitingOnesAreBounded() {
        val first = started(MailOAuthCodeSessions.startUnchecked(req())).handle
        MailOAuthCodeSessions.nowMs = { System.currentTimeMillis() + 11 * 60 * 1000L }
        assertNull("an expired request opens nothing", MailOAuthCodeSessions.authorizationUrl(first))
        repeat(8) { assertTrue(MailOAuthCodeSessions.startUnchecked(req()).getBoolean(MailOAuthContract.KEY_OK)) }
        assertFalse("too many waiting", MailOAuthCodeSessions.startUnchecked(req()).getBoolean(MailOAuthContract.KEY_OK))
    }

    // ---- what a redirect is worth (moved from OAuthRedirectTest) ----

    private val metaReq = MailOAuthContract.CodeRequest("me@example.org", "x", "https://idp.example.com/authorize", "https://idp.example.com/token", "sterna", "scope-a", "app.sterna://oauth")
    private fun pending(state: String = "STATE-1", verifier: String = "VERIFIER-1") = MailOAuthCodeSessions.Pending(state, verifier, metaReq, "https://idp.example.com/authorize?x=1", Long.MAX_VALUE)

    @Test fun aStateThatIsNotTheOneWeDrewExchangesNothing() = assertEquals(Verdict.StateMismatch, redirectVerdict(pending(), "SOMEONE-ELSES-STATE", "CODE-42", null))

    @Test fun aRedirectCarryingNoStateAtAllExchangesNothing() {
        assertEquals(Verdict.StateMismatch, redirectVerdict(pending(), null, "CODE-42", null))
        assertEquals(Verdict.StateMismatch, redirectVerdict(pending(), "", "CODE-42", null))
    }

    @Test fun theStateIsCheckedBeforeTheErrorFieldIsRead() = assertEquals(Verdict.StateMismatch, redirectVerdict(pending(), "FORGED", null, "access_denied"))

    @Test fun withNothingInFlightThereIsNoExchange() = assertEquals(Verdict.NoPendingRequest, redirectVerdict(null, "STATE-1", "CODE-42", null))

    @Test fun ourOwnAnswerIsExchangedWithTheVerifierWeDrew() {
        val p = pending(); val v = redirectVerdict(p, "STATE-1", "CODE-42", null) as Verdict.Exchange
        assertEquals("CODE-42", v.code); assertEquals("VERIFIER-1", v.request.codeVerifier); assertEquals("me@example.org", v.request.request.email)
    }

    @Test fun aRefusalIsAMessageNotAnExchange() = assertEquals(Verdict.Refused("access_denied"), redirectVerdict(pending(), "STATE-1", null, "access_denied"))

    @Test fun anErrorWinsOverACodeOnOurOwnSignIn() = assertEquals(Verdict.Refused("invalid_scope"), redirectVerdict(pending(), "STATE-1", "CODE-42", "invalid_scope"))

    @Test fun neitherACodeNorAnErrorIsNothingUsable() {
        assertEquals(Verdict.NothingUsable, redirectVerdict(pending(), "STATE-1", null, null))
        assertEquals(Verdict.NothingUsable, redirectVerdict(pending(), "STATE-1", "", ""))
    }

    @Test fun anAuthorizationRequestCannotExistWithoutACodeVerifier() {
        assertThrows(IllegalArgumentException::class.java) { pending(verifier = "") }
        assertThrows(IllegalArgumentException::class.java) { pending(state = "") }
    }

    @Test fun theExchangeCarriesTheVerifierOfTheRequestBeingRedeemedAndIsDerivedNeverRedrawn() {
        val v = redirectVerdict(pending("STATE-1", "VERIFIER-1"), "STATE-1", "CODE-42", null) as Verdict.Exchange
        assertEquals(MailOAuthCodeSessions.TokenExchange(metaReq.tokenEndpoint, "sterna", "CODE-42", "app.sterna://oauth", "VERIFIER-1"), tokenExchangeFor(v))
        assertEquals("the same verdict answers the same thing twice", tokenExchangeFor(v), tokenExchangeFor(v))
    }

    @Test fun theSameReturnTwiceExchangesOnce() {
        val slots = MailOAuthCodeSessions.Slots(); slots.arm("H", pending())
        assertTrue(slots.redeem("H", "STATE-1", "CODE-42", null) is Verdict.Exchange)
        assertEquals("a replayed redirect must find nothing", Verdict.NoPendingRequest, slots.redeem("H", "STATE-1", "CODE-42", null)); assertFalse(slots.isArmed("H"))
    }

    @Test fun aForeignReturnDoesNotConsumeTheRealRequest() {
        val slots = MailOAuthCodeSessions.Slots(); slots.arm("H", pending())
        assertEquals(Verdict.StateMismatch, slots.redeem("H", "FORGED", "ATTACKER-CODE", null)); assertTrue(slots.isArmed("H"))
        assertEquals("CODE-42", (slots.redeem("H", "STATE-1", "CODE-42", null) as Verdict.Exchange).code)
    }

    @Test fun aRefusalConsumesTheRequestTooAndACancelLeavesNothing() {
        val slots = MailOAuthCodeSessions.Slots(); slots.arm("H", pending())
        assertEquals(Verdict.Refused("access_denied"), slots.redeem("H", "STATE-1", null, "access_denied"))
        assertEquals(Verdict.NoPendingRequest, slots.redeem("H", "STATE-1", "CODE-42", null))
        slots.arm("H", pending()); slots.disarm("H"); assertFalse(slots.isArmed("H"))
        assertEquals(Verdict.NoPendingRequest, slots.redeem("H", "STATE-1", "CODE-42", null)); assertEquals(Verdict.NoPendingRequest, slots.redeem("NEVER", "S", "C", null))
    }

    // ---- the whole round trip over the transport ----

    @Test fun theTokenEndpointGetsTheGrantTheCodeAndTheVerifierWhoseChallengeTheBrowserUrlCarried() {
        server.enqueue(MockResponse().setBody("""{"access_token":"AT3","refresh_token":"RT3","expires_in":1200,"id_token":"IDT"}"""))
        val (h, state, challenge) = begin()
        val a = answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "THE-CODE", state, null)))
        assertEquals("success", a.state); assertEquals("AT3", a.accessToken); assertEquals("RT3", a.refreshToken); assertEquals(1200L, a.expiresIn); assertEquals("IDT", a.idToken)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/auth/token", request.path)
        val f = formFields(request.body.readUtf8())
        assertEquals("authorization_code", f["grant_type"]); assertEquals("THE-CODE", f["code"]); assertEquals("app.sterna://oauth", f["redirect_uri"]); assertEquals("sterna", f["client_id"])
        assertEquals("nothing else goes to the token endpoint: $f", 5, f.size)
        assertEquals("the verifier that redeems the code is the one the browser URL's challenge came from (PKCE is not decoration)", challenge, Pkce.codeChallengeOf(f["code_verifier"]!!))
        assertEquals("the request is spent once the tokens are handed over", MailOAuthContract.ERR_NO_PENDING, answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "THE-CODE", state, null))).error)
    }

    @Test fun aForgedReturnReachesNoNetworkAndLeavesTheRealSignInAlive() {
        val (h, state, _) = begin()
        val a = answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "ATTACKER-CODE", "FORGED", null)))
        assertEquals("failed", a.state); assertEquals(MailOAuthContract.ERR_STATE_MISMATCH, a.error); assertEquals("no token request was made", 0, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"access_token":"AT"}"""))
        assertEquals("the real return still works", "success", answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "C", state, null))).state)
    }

    @Test fun theOtherVerdictsAreAnswersWithFixedWordsAndNoNetwork() {
        val (h1, s1, _) = begin()
        assertEquals("access_denied", answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h1, null, s1, "access_denied"))).error)
        val (h2, s2, _) = begin()
        assertEquals(MailOAuthContract.ERR_NOTHING_USABLE, answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h2, null, s2, null))).error)
        assertEquals(MailOAuthContract.ERR_NO_PENDING, answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish("unknown", "C", "S", null))).error)
        assertEquals(0, server.requestCount)
    }

    @Test fun aCancelOrAnExpiryLeavesNothingALateRedirectCouldExchange() {
        val (h, state, _) = begin(); MailOAuthCodeSessions.cancel(h)
        assertEquals(MailOAuthContract.ERR_NO_PENDING, answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "C", state, null))).error)
        val (h2, s2, _) = begin(); MailOAuthCodeSessions.nowMs = { System.currentTimeMillis() + 11 * 60 * 1000L }
        assertEquals(MailOAuthContract.ERR_NO_PENDING, answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h2, "C", s2, null))).error); assertEquals(0, server.requestCount)
    }

    @Test fun exchange_doesNotReplayTheCodeToARedirectTarget() {
        val second = MockWebServer().also { it.start() }
        try {
            second.enqueue(MockResponse().setBody("""{"access_token":"EVIL-AT","refresh_token":"EVIL-RT"}"""))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", second.url("/steal").toString()))
            val (h, state, _) = begin()
            val a = answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "SECRET-CODE", state, null)))
            assertEquals("neither the code nor the verifier may be replayed", 0, second.requestCount)
            assertEquals("failed", a.state); assertEquals("http_307", a.error)
            assertEquals("the endpoint we asked for was hit once, and only it", 1, server.requestCount)
            val sent = formFields(server.takeRequest().body.readUtf8())
            assertEquals("the code still went to the endpoint the caller named", "SECRET-CODE", sent["code"])
        } finally { second.shutdown() }
    }

    @Test fun exchange_doesNotPutTheTokensInTheAnswerOfAMalformedResponse() {
        server.enqueue(MockResponse().setBody("""{"access_token":"SECRET-AT","refresh_token":"SECRET-RT","expires_in":36"""))
        val (h, state, _) = begin()
        val a = answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "C", state, null)))
        assertEquals("failed", a.state)
        assertFalse(a.json().contains("SECRET-AT")); assertFalse(a.json().contains("SECRET-RT")); assertFalse(a.toString().contains("SECRET"))
    }

    @Test fun exchange_carriesTheOAuthErrorButNotTheDescription() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"SECRET-DESCRIPTION: code already used"}"""))
        val (h, state, _) = begin()
        val a = answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "C", state, null)))
        assertEquals("invalid_grant", a.error); assertEquals("", a.description)
        assertFalse(a.json().contains("SECRET-DESCRIPTION")); assertFalse(a.json().contains("error_description"))
    }

    @Test fun anUnreachableTokenEndpointIsANetworkFailureNamingNobody() {
        val (h, state, _) = begin(); val port = server.port; server.shutdown()
        val a = answer(MailOAuthCodeSessions.finishOnce(MailOAuthContract.CodeFinish(h, "C", state, null)))
        assertEquals("network_error", a.error); assertFalse(a.json().contains(port.toString()))
        server = MockWebServer().also { it.start() }
    }
}
