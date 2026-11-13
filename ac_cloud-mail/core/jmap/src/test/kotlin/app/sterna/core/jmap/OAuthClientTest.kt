package app.sterna.core.jmap

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthClientTest {
    private lateinit var server: MockWebServer
    private val client = OAuthClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun metadata() = OAuthMetadata(
        issuer = server.url("/").toString(),
        tokenEndpoint = server.url("/auth/token").toString(),
        deviceAuthorizationEndpoint = server.url("/auth/device").toString(),
    )

    @Test fun parseError_extractsErrorDescriptionAndAadsts() {
        val parsed = client.parseError(
            """{"error":"invalid_grant","error_description":"AADSTS90094: The grant requires admin permission."}""",
        )
        assertEquals("invalid_grant", parsed?.error)
        assertTrue(parsed?.description?.contains("admin permission") == true)
        assertEquals("AADSTS90094", parsed?.aadstsCode)
    }

    @Test fun parseError_noAadstsWhenAbsent() {
        val parsed = client.parseError(
            """{"error":"expired_token","error_description":"The code expired."}""",
        )
        assertEquals("expired_token", parsed?.error)
        assertNull(parsed?.aadstsCode)
        assertTrue(parsed?.description?.isNotEmpty() == true)
    }

    @Test fun parseError_returnsNullForNonError() {
        assertNull(client.parseError("""{"foo":"bar"}"""))
        assertNull(client.parseError("not json"))
    }

    @Test fun refresh_exchangesRefreshTokenForAccessToken() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"access_token":"AT2","refresh_token":"RT2","expires_in":7200}"""),
        )
        val tokens = client.refresh(server.url("/auth/token").toString(), "RT", "sterna")
        assertEquals("AT2", tokens.accessToken)
        assertEquals(7200, tokens.expiresIn)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=RT"))
    }

    // ---- A malformed token response must not carry the tokens into the error ----
    //
    // kotlinx.serialization puts a SLICE OF THE OFFENDING JSON in its message, and for a token
    // endpoint that JSON is the access and refresh tokens themselves. That message travels:
    // refresh() -> OAuthTokenRefresher -> jmapAuth -> MailRepository.refresh ->
    // InboxViewModel's `t.message` -> the list's error banner.
    //
    // The trigger is a response the parser cannot finish, NOT the `"expires_in": "3600"` a
    // lenient-looking provider sends: this Json coerces that one to the property default without

    @Test fun refresh_doesNotPutTheTokensInTheErrorOfAMalformedResponse() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"SECRET-AT","refresh_token":"SECRET-RT","expires_in":36""",
            ),
        )
        try {
            runBlocking { client.refresh(server.url("/auth/token").toString(), "RT", "sterna") }
            throw AssertionError("expected the malformed response to fail")
        } catch (e: Exception) {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { "${it.javaClass} ${it.message}" }
            assertFalse("the error carried the access token: $text", text.contains("SECRET-AT"))
            assertFalse("the error carried the refresh token: $text", text.contains("SECRET-RT"))
        }
    }

    // ---- The refusal must reach the screen, and only the protocol's word for it ----
    //
    // Two halves of the same rule. The connect screen has to be able to say WHY a sign-in failed,
    // so the server's `error` field has to survive the call — but an exception message here is
    // written by OkHttp and carries the URL, the host and the port, and the response body is the
    // caller's own tokens. So: the closed vocabulary of RFC 6749 §5.2 travels, the prose does not.

    // ---- A 3xx on one of the three POSTs must not send the secret to the host it names ----
    //
    // OkHttp follows a 307/308 by re-issuing the SAME method with the SAME body (verified against
    // okhttp 4.12: on a 307 the second server receives
    //
    // Every test below goes through `client`, i.e. the PUBLIC OAuthClient() constructor. The
    // internal constructor takes an injected client, so a test that hands in its own would only

    private fun startSecondServer(): MockWebServer = MockWebServer().apply { start() }

    @Test fun refresh_doesNotReplayTheRefreshTokenToARedirectTarget() {
        val second = startSecondServer()
        try {
            // Armed on purpose: if the flag falls, the redirect lands here and this body comes
            // back to the caller as a perfectly valid-looking grant. The test can tell the
            // difference because the second server is able to answer.
            second.enqueue(
                MockResponse().setBody("""{"access_token":"EVIL-AT","refresh_token":"EVIL-RT"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader("Location", second.url("/steal").toString()),
            )

            val outcome = runCatching {
                runBlocking { client.refresh(server.url("/auth/token").toString(), "SECRET-RT", "sterna") }
            }

            assertEquals("the refresh token must not be replayed to the redirect target", 0, second.requestCount)
            val failure = outcome.exceptionOrNull()
            assertTrue("expected the 307 to surface as a JmapException, got $outcome", failure is JmapException)
            // The status is what the calling layer reads off the exception, so pin the field.
            assertEquals(307, (failure as JmapException).httpCode)
            assertEquals("the endpoint we asked for was hit once, and only it", 1, server.requestCount)
            assertTrue(
                "the token still went to the endpoint the caller named",
                server.takeRequest().body.readUtf8().contains("refresh_token=SECRET-RT"),
            )
        } finally {
            second.shutdown()
        }
    }

    @Test fun refresh_refusesEvery3xx_notOnlyTheOnesThatKeepTheBody() {
        val second = startSecondServer()
        try {
            listOf(301, 302, 303, 307, 308).forEach { code ->
                second.enqueue(MockResponse().setBody("""{"access_token":"EVIL-AT"}"""))
                server.enqueue(
                    MockResponse().setResponseCode(code)
                        .setHeader("Location", second.url("/steal/$code").toString()),
                )

                val outcome = runCatching {
                    runBlocking { client.refresh(server.url("/auth/token").toString(), "SECRET-RT", "sterna") }
                }

                val failure = outcome.exceptionOrNull()
                assertTrue("HTTP $code was followed: $outcome", failure is JmapException)
                assertEquals("HTTP $code", code, (failure as JmapException).httpCode)
            }
            assertEquals("nothing may reach the redirect target, for any 3xx", 0, second.requestCount)
        } finally {
            second.shutdown()
        }
    }

    // ---- The witness that the cut stopped at the POSTs ----
    //
    // Discovery is a GET carrying no secret, and a deployment whose
    // /.well-known/oauth-authorization-server sits behind a proxy or a vhost answers a 3xx there.

    /**
     * The hop itself: the fetch must still be CARRIED to the redirect target, and the document
     */
    @Test fun discoverMetadata_stillFollowsARedirect() = runBlocking {
        val second = startSecondServer()
        try {
            second.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"issuer":"https://idp.example","token_endpoint":"https://idp.example/token",
                       "device_authorization_endpoint":"https://idp.example/device"}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader(
                    "Location",
                    second.url("/.well-known/oauth-authorization-server").toString(),
                ),
            )

            val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

            assertEquals("the discovery redirect must still be followed", 1, second.requestCount)
            assertNotNull("the document behind the redirect must be the one judged", metadata)
            assertEquals("but a third party's endpoints are blanked", "", metadata?.tokenEndpoint)
            assertNull(metadata?.deviceAuthorizationEndpoint)
            assertTrue("and the fact is carried", metadata?.endpointsOffDomain == true)
        } finally {
            second.shutdown()
        }
    }

    /** The other half: a document served behind a redirect and naming an ACCEPTABLE host is
     *  kept whole, and the sign-in it describes is drivable. */
    @Test fun discoverMetadata_keepsTheMetadataBehindARedirectWhenItIsOnDomain() = runBlocking {
        val second = startSecondServer()
        try {
            second.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"issuer":"https://idp.example","token_endpoint":"https://idp.example/token",
                       "device_authorization_endpoint":"https://idp.example/device"}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader(
                    "Location",
                    second.url("/.well-known/oauth-authorization-server").toString(),
                ),
            )

            val metadata = client.discoverMetadata(server.url("/").toString(), "idp.example")

            assertEquals("the discovery redirect must still be followed", 1, second.requestCount)
            assertEquals("https://idp.example/token", metadata?.tokenEndpoint)
            assertEquals("https://idp.example/device", metadata?.deviceAuthorizationEndpoint)
            assertTrue("the metadata behind the redirect must be the one we use", metadata?.supportsDeviceFlow == true)
            assertFalse("and nothing was blanked", metadata?.endpointsOffDomain == true)
        } finally {
            second.shutdown()
        }
    }

    // ---- The flags, read off the object the factory builds ----

    @Test fun defaultOAuthHttpClient_followsNoRedirectAtAll() {
        val built = defaultOAuthHttpClient()
        assertFalse("a 3xx on a token POST must never be followed", built.followRedirects)
        assertFalse("and never across a scheme change either", built.followSslRedirects)
    }

    // #1703 MOVED to Cloud Account (ac_cloud-account MailOAuthCodeSessionsTest): the PKCE draw + vector, the authorization URL (pinned bytes, hostile values, https only),
    // and the code exchange (grant fields, no redirect replay, no token or description in an error). The authorization-code grant no longer runs in this app.

    @Test fun supportsCodeFlow_needsBothEndpoints() {
        val both = OAuthMetadata(
            authorizationEndpoint = "https://idp.example.com/authorize",
            tokenEndpoint = "https://idp.example.com/token",
        )
        assertTrue(both.supportsCodeFlow)
        assertFalse(
            "no authorization endpoint at all",
            OAuthMetadata(tokenEndpoint = "https://idp.example.com/token").supportsCodeFlow,
        )
        assertFalse(
            "a blank endpoint is not an endpoint",
            OAuthMetadata(
                authorizationEndpoint = "   ",
                tokenEndpoint = "https://idp.example.com/token",
            ).supportsCodeFlow,
        )
        assertFalse(
            "nowhere to exchange the code",
            OAuthMetadata(authorizationEndpoint = "https://idp.example.com/authorize").supportsCodeFlow,
        )
        assertFalse(
            "a blank token endpoint is not an endpoint either",
            OAuthMetadata(
                authorizationEndpoint = "https://idp.example.com/authorize",
                tokenEndpoint = "   ",
            ).supportsCodeFlow,
        )
        // The two flags are not the same question, and the device one must not have moved.
        assertFalse("a code-flow server is not a device-flow server", both.supportsDeviceFlow)
        assertTrue(
            OAuthMetadata(
                tokenEndpoint = "https://idp.example.com/token",
                deviceAuthorizationEndpoint = "https://idp.example.com/device",
            ).supportsDeviceFlow,
        )
    }

    // -- login_hint on the approval page (#55) -----------------------------------------------------

    /**
     * The whole string is pinned, literally, and NOT recomputed here: a test that rebuilds the URL
     */
    @Test fun loginHint_isAddedAsAQueryToABareUrl() {
        assertEquals(
            "https://idp.example.com/device?login_hint=nina%40example.com",
            withLoginHint("https://idp.example.com/device", "nina@example.com"),
        )
    }

    /** The server's own `code=` is what the page needs to identify the request: it must survive
     *  untouched, and the hint arrives after it. */
    @Test fun loginHint_keepsTheQueryTheServerAlreadySent() {
        assertEquals(
            "https://idp.example.com/device?code=QA-BENCH&login_hint=nina%40example.com",
            withLoginHint("https://idp.example.com/device?code=QA-BENCH", "nina@example.com"),
        )
    }

    /** A fragment ends the URL, so a parameter written after it is read by nobody — it must stay
     *  in the query, ahead of the `#`. */
    @Test fun loginHint_staysInTheQueryAheadOfAFragment() {
        assertEquals(
            "https://idp.example.com/device?code=QA-BENCH&login_hint=nina%40example.com#form",
            withLoginHint("https://idp.example.com/device?code=QA-BENCH#form", "nina@example.com"),
        )
    }

    /**
     * A URL we cannot read comes back WORD FOR WORD — not null, not "", not truncated, and not
     */
    @Test fun loginHint_givesAnUnreadableUrlBackVerbatim() {
        assertEquals(
            "sterna-device:approve?code=QA-BENCH",
            withLoginHint("sterna-device:approve?code=QA-BENCH", "nina@example.com"),
        )
        assertEquals("", withLoginHint("", "nina@example.com"))
        assertEquals("not a url at all", withLoginHint("not a url at all", "nina@example.com"))
    }

    /**
     * The server wins. RFC 6749 §3.1 forbids a parameter twice, and a second `login_hint` would
     */
    @Test fun loginHint_neverDoublesAHintTheServerAlreadyWrote() {
        assertEquals(
            "https://idp.example.com/device?code=QA-BENCH&login_hint=someone%40example.com",
            withLoginHint(
                "https://idp.example.com/device?code=QA-BENCH&login_hint=someone%40example.com",
                "nina@example.com",
            ),
        )
        assertEquals(
            "an empty hint on the URL is still the server's hint",
            "https://idp.example.com/device?login_hint=",
            withLoginHint("https://idp.example.com/device?login_hint=", "nina@example.com"),
        )
    }

    /**
     * `URLEncoder` writes a space as `+` — and, read back per RFC 3986, an address typed with a
     */
    @Test fun loginHint_percentEncodesAndNeverFormEncodes() {
        assertEquals(
            "a space is %20, never +, or the address gains a plus on the way",
            "https://idp.example.com/device?login_hint=nina%20qa%40example.com",
            withLoginHint("https://idp.example.com/device", "nina qa@example.com"),
        )
        assertEquals(
            "~ is unreserved (RFC 3986 §2.3) and must survive as itself",
            "https://idp.example.com/device?login_hint=nina~qa%40example.com",
            withLoginHint("https://idp.example.com/device", "nina~qa@example.com"),
        )
        assertEquals(
            "https://idp.example.com/device?login_hint=nina%2Bqa%40example.com",
            withLoginHint("https://idp.example.com/device", "nina+qa@example.com"),
        )
        assertEquals(
            "https://idp.example.com/device?login_hint=n%C3%AFna%40example.com",
            withLoginHint("https://idp.example.com/device", "nïna@example.com"),
        )
        assertEquals(
            "a hint with a query separator in it must not open a parameter of its own",
            "https://idp.example.com/device?login_hint=a%26b%3Dc%40example.com",
            withLoginHint("https://idp.example.com/device", "a&b=c@example.com"),
        )
    }

    /**
     * NOT over cleartext. `toHttpUrlOrNull` takes `http://` as happily as `https://`, and the
     */
    @Test fun loginHint_refusesToPutAnIdentityOnACleartextUrl() {
        assertEquals(
            "http://idp.example.com/device?code=QA-BENCH",
            withLoginHint("http://idp.example.com/device?code=QA-BENCH", "nina@example.com"),
        )
    }

    /**
     * The whole URL is rebuilt through `HttpUrl`, so what the SERVER wrote in its own query has to
     */
    @Test fun loginHint_leavesTheServersOwnQueryBytesAlone() {
        assertEquals(
            "https://idp.example.com/device?code=A+B&login_hint=nina%40example.com",
            withLoginHint("https://idp.example.com/device?code=A+B", "nina@example.com"),
        )
    }

    /** A bare `?` with nothing behind it: pinned as okhttp actually renders it, not as it ought to
     *  look — this one is an observation, and a change of okhttp must be seen here. */
    @Test fun loginHint_pinsWhatABareQuestionMarkBecomes() {
        assertEquals(
            "https://idp.example.com/device?&login_hint=nina%40example.com",
            withLoginHint("https://idp.example.com/device?", "nina@example.com"),
        )
    }

    /** Nothing to pre-fill is not a reason to rewrite the URL: no empty parameter is sent. */
    @Test fun loginHint_leavesTheUrlAloneWhenThereIsNoHint() {
        assertEquals(
            "https://idp.example.com/device?code=QA-BENCH",
            withLoginHint("https://idp.example.com/device?code=QA-BENCH", ""),
        )
        assertEquals(
            "https://idp.example.com/device?code=QA-BENCH",
            withLoginHint("https://idp.example.com/device?code=QA-BENCH", "   "),
        )
    }

    // ════════ WHERE a discovery document is allowed to send the secrets ════════
    //
    // The discovery GET follows 3xx on purpose (above), and what comes back names the three
    // endpoints that the device code, the authorization code, the PKCE verifier and — for the
    //
    // The decision is `oauthEndpointAllowed`, a PURE FUNCTION, and the tests below EXECUTE it
    // with pinned literal arguments. Nothing here re-derives the rule to choose what it expects:
    // invert the rule in the source and these go red.

    @Test fun oauthEndpointAllowed_takesTheQueriedHostAndItsSubdomains() {
        assertTrue(
            "the host that was just asked must be able to name itself",
            oauthEndpointAllowed("https://mail.example.com/token", "mail.example.com", ""),
        )
        assertTrue(
            "a subdomain of the host that was asked is under it",
            oauthEndpointAllowed("https://auth.mail.example.com/token", "mail.example.com", ""),
        )
    }

    @Test fun oauthEndpointAllowed_takesTheAddressDomainAndItsSubdomains() {
        assertTrue(
            "the address' own domain is the second thing the reader typed",
            oauthEndpointAllowed("https://example.com/token", "asked.example.org", "example.com"),
        )
        assertTrue(
            "a sibling host under the address domain is the bench's own deployment",
            oauthEndpointAllowed("https://stalwart.example.com/token", "asked.example.org", "example.com"),
        )
    }

    @Test fun oauthEndpointAllowed_refusesAHostThatMerelyContainsTheDomain() {
        assertFalse(
            "evil-<domain> is a third party, not a subdomain — hence the leading dot",
            oauthEndpointAllowed("https://evil-example.com/token", "asked.example.org", "example.com"),
        )
        assertFalse(
            "<domain>.evil.tld is a third party too",
            oauthEndpointAllowed("https://example.com.evil.tld/token", "asked.example.org", "example.com"),
        )
        assertFalse(
            "and so is a host that merely contains the queried host",
            oauthEndpointAllowed("https://mail.example.com.evil.tld/token", "mail.example.com", ""),
        )
    }

    @Test fun oauthEndpointAllowed_refusesCleartextEvenOnAGoodHost() {
        assertFalse(
            "a token endpoint on http:// hands the refresh token to the network",
            oauthEndpointAllowed("http://mail.example.com/token", "mail.example.com", "example.com"),
        )
    }

    @Test fun oauthEndpointAllowed_ignoresCase() {
        assertTrue(
            "a document may shout its own host",
            oauthEndpointAllowed("https://MAIL.EXAMPLE.COM/token", "mail.example.com", ""),
        )
        assertTrue(
            "and the host we asked, or the domain typed, may be in any case too",
            oauthEndpointAllowed("https://stalwart.example.com/token", "MAIL.EXAMPLE.COM", "EXAMPLE.COM"),
        )
    }

    @Test fun oauthEndpointAllowed_refusesWhatItCannotRead() {
        assertFalse(
            "a string that is not a URL names nothing",
            oauthEndpointAllowed("not a url at all", "mail.example.com", "example.com"),
        )
        assertFalse(
            "an absent endpoint arrives here empty",
            oauthEndpointAllowed("", "mail.example.com", "example.com"),
        )
    }

    @Test fun oauthEndpointAllowed_anEmptyOwnerOpensNothing() {
        assertFalse(
            "a malformed address gives an empty domain, and an empty domain must not match",
            oauthEndpointAllowed("https://idp.example.org/token", "mail.example.com", ""),
        )
        assertFalse(
            "nor may an empty queried host and an empty domain accept a host ending in a dot",
            oauthEndpointAllowed("https://idp.example.org./token", "", ""),
        )
    }

    // ---- The same decision, executed through the real fetch ----

    /** The document a compromised (or merely redirected) server can serve: everything elsewhere. */
    @Test fun discoverMetadata_blanksThreeEndpointsNamedOffDomain() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"https://ailleurs.example",
                   "authorization_endpoint":"https://ailleurs.example/authorize",
                   "token_endpoint":"https://ailleurs.example/token",
                   "device_authorization_endpoint":"https://ailleurs.example/device"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

        assertNotNull("a refused document is still an ANSWER: null would say 'no OAuth here'", metadata)
        assertEquals("the token endpoint must not survive", "", metadata?.tokenEndpoint)
        assertNull("nor the authorization endpoint", metadata?.authorizationEndpoint)
        assertNull("nor the device endpoint", metadata?.deviceAuthorizationEndpoint)
        assertFalse("no device flow can be built from it", metadata?.supportsDeviceFlow == true)
        assertFalse("no code flow either", metadata?.supportsCodeFlow == true)
        assertTrue("and the fact must be carried", metadata?.endpointsOffDomain == true)
    }

    /**
     * The neighbour case, and it is the BENCH's own deployment: the host asked is not the host
     */
    @Test fun discoverMetadata_keepsASiblingHostUnderTheAddressDomain() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"https://stalwart.example.com",
                   "authorization_endpoint":"https://stalwart.example.com/authorize",
                   "token_endpoint":"https://stalwart.example.com/token",
                   "device_authorization_endpoint":"https://stalwart.example.com/device"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

        assertEquals("https://stalwart.example.com/token", metadata?.tokenEndpoint)
        assertEquals("https://stalwart.example.com/authorize", metadata?.authorizationEndpoint)
        assertEquals("https://stalwart.example.com/device", metadata?.deviceAuthorizationEndpoint)
        assertTrue("the sign-in must still be drivable", metadata?.supportsDeviceFlow == true)
        assertFalse("and nothing was blanked", metadata?.endpointsOffDomain == true)
    }

    /** One endpoint elsewhere does not condemn the others — nor does it pass. */
    @Test fun discoverMetadata_blanksOnlyTheEndpointThatIsElsewhere() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"https://mail.example.com",
                   "authorization_endpoint":"https://mail.example.com/authorize",
                   "token_endpoint":"https://mail.example.com/token",
                   "device_authorization_endpoint":"https://ailleurs.example/device"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

        assertEquals("https://mail.example.com/token", metadata?.tokenEndpoint)
        assertEquals("https://mail.example.com/authorize", metadata?.authorizationEndpoint)
        assertNull("the third party's device endpoint must go", metadata?.deviceAuthorizationEndpoint)
        assertFalse("so there is no device flow left", metadata?.supportsDeviceFlow == true)
        assertTrue("the code grant survives", metadata?.supportsCodeFlow == true)
        assertTrue("and the fact is carried", metadata?.endpointsOffDomain == true)
    }

    /**
     * Acceptance coming from THE HOST THAT WAS ASKED, alone: the address' domain here is
     */
    @Test fun discoverMetadata_keepsEndpointsOnTheHostThatWasAsked() = runBlocking {
        val asked = "https://${server.hostName}:${server.port}"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"$asked","token_endpoint":"$asked/token",
                   "device_authorization_endpoint":"$asked/device"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "unrelated.example")

        assertEquals("$asked/token", metadata?.tokenEndpoint)
        assertEquals("$asked/device", metadata?.deviceAuthorizationEndpoint)
        assertFalse("nothing was blanked", metadata?.endpointsOffDomain == true)
    }

    /** A document with no token endpoint is a server WITHOUT OAuth, not a refusal. Still null. */
    @Test fun discoverMetadata_aDocumentWithoutATokenEndpointIsStillNull() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"https://mail.example.com"}""",
            ),
        )

        assertNull(client.discoverMetadata(server.url("/").toString(), "example.com"))
    }

    /** The fact is ours, never the server's: a document claiming it is ignored. */
    @Test fun discoverMetadata_theOffDomainFactIsNeverReadOffTheWire() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"https://mail.example.com","endpointsOffDomain":true,
                   "token_endpoint":"https://mail.example.com/token"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

        assertEquals("https://mail.example.com/token", metadata?.tokenEndpoint)
        assertFalse("the server does not get to set this", metadata?.endpointsOffDomain == true)
    }

    // ---- The fact is about WHO, not about every refusal ----
    //
    // `oauthEndpointAllowed` refuses for three reasons: another host, cleartext, and a string that
    // is not a URL. Only the FIRST is something the connect screen may describe as the server
    // handing sign-in to another domain. Widening the fact to "an endpoint was refused" puts a new
    // lie exactly where #54/#137 took one out.

    @Test fun oauthEndpointNamesAnotherHost_isTrueOnlyForSomeoneElse() {
        assertTrue(
            "a third party is the whole point",
            oauthEndpointNamesAnotherHost("https://ailleurs.example/token", "mail.example.com", "example.com"),
        )
        assertFalse(
            "the host asked is not another host",
            oauthEndpointNamesAnotherHost("https://mail.example.com/token", "mail.example.com", "example.com"),
        )
        assertFalse(
            "nor is a sibling under the address' own domain",
            oauthEndpointNamesAnotherHost("https://stalwart.example.com/token", "mail.example.com", "example.com"),
        )
    }

    @Test fun oauthEndpointNamesAnotherHost_saysNothingOfCleartextOnOurOwnHost() {
        assertFalse(
            "http:// on our own host is refused, but it names NOBODY else — telling the reader " +
                "her server hands sign-in to another domain would be false",
            oauthEndpointNamesAnotherHost("http://mail.example.com/token", "mail.example.com", "example.com"),
        )
        assertFalse(
            "and a string that is not a URL names nobody at all",
            oauthEndpointNamesAnotherHost("/token", "mail.example.com", "example.com"),
        )
    }

    /**
     * The whole document is on the host asked, but in the clear. Blanked, and the sign-in fails —
     * yet nothing may claim a third party was named.
     */
    @Test fun discoverMetadata_blanksCleartextWithoutCallingItAnotherDomain() = runBlocking {
        val asked = "http://${server.hostName}:${server.port}"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"$asked","token_endpoint":"$asked/token",
                   "device_authorization_endpoint":"$asked/device"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

        assertEquals("cleartext is still refused", "", metadata?.tokenEndpoint)
        assertNull("and so is the device endpoint", metadata?.deviceAuthorizationEndpoint)
        assertFalse(
            "but no other host was named, so the fact must stay down",
            metadata?.endpointsOffDomain == true,
        )
    }

    /**
     * The token endpoint ALONE must raise the fact. Written because a document that also names
     */
    @Test fun discoverMetadata_raisesTheFactOnTheTokenEndpointAlone() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"https://mail.example.com",
                   "token_endpoint":"https://ailleurs.example/token"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

        assertEquals("", metadata?.tokenEndpoint)
        assertTrue("the token endpoint alone must carry it", metadata?.endpointsOffDomain == true)
    }

    /**
     * And the mirror: a token endpoint kept, a DEVICE endpoint sent elsewhere, no authorization
     */
    @Test fun discoverMetadata_raisesTheFactWhileTheTokenEndpointSurvives() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"issuer":"https://mail.example.com",
                   "token_endpoint":"https://mail.example.com/token",
                   "device_authorization_endpoint":"https://ailleurs.example/device"}""",
            ),
        )

        val metadata = client.discoverMetadata(server.url("/").toString(), "example.com")

        assertEquals("https://mail.example.com/token", metadata?.tokenEndpoint)
        assertNull(metadata?.deviceAuthorizationEndpoint)
        assertFalse("nothing can be driven", metadata?.supportsDeviceFlow == true)
        assertFalse("nothing at all", metadata?.supportsCodeFlow == true)
        assertTrue("and a third party WAS named", metadata?.endpointsOffDomain == true)
    }

    private companion object {
        /** `example.com` everywhere: `NoPrivateHostInTreeTest` refuses any private host. */
        const val AUTHORIZE = "https://idp.example.com/authorize"
    }
}
