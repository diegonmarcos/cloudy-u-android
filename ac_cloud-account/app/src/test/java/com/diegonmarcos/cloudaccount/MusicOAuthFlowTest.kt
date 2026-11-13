package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.profile.MusicDeviceGrant
import com.diegonmarcos.superapp.profile.MusicOAuthFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1693 the Spotify (code + PKCE) and Deezer (implicit) browser sign-ins, moved here from libs:scraper's MusicOAuth, every step over a fake POST. */
@RunWith(RobolectricTestRunner::class)
class MusicOAuthFlowTest {
    private val spotify = MusicOAuthFlow.SPECS.getValue("spotify")
    private val deezer = MusicOAuthFlow.SPECS.getValue("deezer")

    @Test fun theChallengeIsTheRfc7636OneForItsVerifier() =
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", MusicOAuthFlow.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

    @Test fun theVerifierIsLongUrlSafeAndFresh() {
        val a = MusicOAuthFlow.verifier(); val b = MusicOAuthFlow.verifier()
        assertTrue(a.length >= 43 && a.all { it.isLetterOrDigit() || it == '-' || it == '_' }); assertTrue(a != b)
    }

    @Test fun theAuthorizationUrlsCarryTheirParameters() {
        val u = MusicOAuthFlow.authUrl(spotify, "CID", "ST", "CH")
        assertTrue(u.startsWith("https://accounts.spotify.com/authorize?")); for (p in listOf("response_type=code", "client_id=CID", "state=ST", "code_challenge_method=S256", "code_challenge=CH")) assertTrue(p, u.contains(p))
        val d = MusicOAuthFlow.authUrl(deezer, "APP", "ST", null)
        assertTrue(d.contains("app_id=APP") && d.contains("response_type=token") && d.contains("perms=")); assertFalse(d.contains("code_challenge"))
    }

    @Test fun theRedirectYieldsACodeATokenOrAnErrorAndOnlyTheRedirect() {
        val r = spotify.redirect
        assertEquals("C1", MusicOAuthFlow.callback("$r?code=C1&state=ST", spotify, "ST")!!.code)
        val t = MusicOAuthFlow.callback("${deezer.redirect}#access_token=T1&expires=3600&state=ST", deezer, "ST")!!
        assertEquals("T1", t.token); assertEquals(3600L, t.expiresInSec)
        assertEquals("access_denied", MusicOAuthFlow.callback("$r?error=access_denied&state=ST", spotify, "ST")!!.error)
        assertEquals("state mismatch", MusicOAuthFlow.callback("$r?code=C1&state=OTHER", spotify, "ST")!!.error)
        assertNull(MusicOAuthFlow.callback("https://accounts.spotify.com/login", spotify, "ST"))
    }

    @Test fun theExchangeSendsThePkceFieldsAndTokensOfFollowsTheRedirect() {
        var sent: Map<String, String>? = null
        val post = MusicDeviceGrant.Post { url, f -> assertEquals(spotify.tokenUrl, url); sent = f; """{"access_token":"AT","refresh_token":"RT","expires_in":3600}""" }
        val t = MusicOAuthFlow.tokensOf(post, spotify, "CID", MusicOAuthFlow.Callback(code = "C1"), "VER")
        assertEquals("AT", t.access); assertEquals("RT", t.refresh)
        assertEquals("authorization_code", sent!!["grant_type"]); assertEquals("VER", sent!!["code_verifier"]); assertEquals("C1", sent!!["code"]); assertEquals(spotify.redirect, sent!!["redirect_uri"])
        val imp = MusicOAuthFlow.tokensOf(post, deezer, "APP", MusicOAuthFlow.Callback(token = "DT", expiresInSec = 60), "")
        assertEquals("DT", imp.access); assertNull(imp.refresh)
    }

    @Test fun aRefusalOrAMissingCodeIsAFixedSentenceNeverAValue() {
        val post = MusicDeviceGrant.Post { _, _ -> error("not called") }
        val e1 = runCatching { MusicOAuthFlow.tokensOf(post, spotify, "c", MusicOAuthFlow.Callback(error = "access_denied"), "v") }.exceptionOrNull()!!
        assertTrue(e1.message!!.contains("access_denied"))
        val e2 = runCatching { MusicOAuthFlow.tokensOf(post, spotify, "c", MusicOAuthFlow.Callback(), "v") }.exceptionOrNull()!!
        assertTrue(e2.message!!.contains("no code"))
        val bad = MusicDeviceGrant.Post { _, _ -> throw MusicDeviceGrant.Http(400, """{"error":"invalid_grant","access_token":"SECRET"}""") }
        val e3 = runCatching { MusicOAuthFlow.tokensOf(bad, spotify, "c", MusicOAuthFlow.Callback(code = "x"), "v") }.exceptionOrNull()!!
        assertFalse(e3.message.orEmpty().contains("SECRET"))
    }
}
