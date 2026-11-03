package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserNavPolicy
import com.diegonmarcos.superapp.browser.BrowserNavPolicy.Decision
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #893 Google sign-in stuck in Cloud Browser, as behaviour. Root causes pinned here:
 *  - ERR_CACHE_MISS on accounts.google.com/gsi/transform: a POST result was committed as the tab's url and
 *    saved in its WebView state, then replayed from the cache on the next rebuild;
 *  - #1420 Google refuses OAuth in any embedded WebView: accounts.google.com goes to a Custom Tab and the
 *    WebView's agent is never disguised;
 *  - app links (intent:) must not leave the browser.
 */
class BrowserSignInTest {


    @Test
    fun `a POST result page is never committed as a tab's url`() {
        val tab = BrowserTab("https://example.com/app", "App", 1L, id = "t1")
        val after = BrowserTabStore.commit(listOf(tab), "t1", "https://accounts.google.com/gsi/transform", "Redirecting")
        assertEquals("https://example.com/app", after.single().url)
        assertFalse(BrowserTabStore.shouldCommit("https://accounts.google.com/gsi/transform"))
        assertFalse(BrowserTabStore.shouldCommit("https://accounts.google.com/signin/oauth/consent?x=1"))
        assertFalse(BrowserTabStore.shouldCommit("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
        assertTrue(BrowserTabStore.shouldCommit("https://www.google.com/search?q=a"))
        assertTrue(BrowserTabStore.shouldCommit("https://myaccount.google.com/"))
    }

    @Test
    fun `form post results are recognised, ordinary pages are not`() {
        assertTrue(BrowserNavPolicy.isFormPostResult("https://accounts.google.com/gsi/transform"))
        assertTrue(BrowserNavPolicy.isFormPostResult("HTTPS://ACCOUNTS.GOOGLE.COM/v3/signin/challenge/pwd"))
        assertFalse(BrowserNavPolicy.isFormPostResult("https://accounts.google.com.evil.example/gsi/transform"))
        assertFalse(BrowserNavPolicy.isFormPostResult("https://example.com/gsi/transform"))
        assertFalse(BrowserNavPolicy.isFormPostResult(null))
    }

    @Test
    fun `Google OAuth hosts go to a Custom Tab, everything else does not`() {
        assertTrue(BrowserNavPolicy.isGoogleOAuth("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
        assertTrue(BrowserNavPolicy.isGoogleOAuth("https://accounts.google.com/v3/signin/identifier"))
        assertTrue(BrowserNavPolicy.isGoogleOAuth("https://ACCOUNTS.google.com/ServiceLogin"))
        assertFalse(BrowserNavPolicy.isGoogleOAuth("http://accounts.google.com/o/oauth2/auth"))
        assertFalse(BrowserNavPolicy.isGoogleOAuth("https://accounts.google.com.evil.example/o/oauth2/auth"))
        assertFalse(BrowserNavPolicy.isGoogleOAuth("https://www.google.com/search?q=accounts.google.com"))
        assertFalse(BrowserNavPolicy.isGoogleOAuth("https://github.com/login/oauth/authorize"))
        val u = "https://accounts.google.com/o/oauth2/auth?client_id=1"
        assertEquals(Decision.CustomTab(u), BrowserNavPolicy.decide(u))
    }

    @Test
    fun `web pages load, Google OAuth goes out, and app links never leave the browser`() {
        assertEquals(Decision.CustomTab("https://accounts.google.com/gsi/transform"), BrowserNavPolicy.decide("https://accounts.google.com/gsi/transform"))
        assertSame(Decision.Load, BrowserNavPolicy.decide("https://example.com/login"))
        assertSame(Decision.Load, BrowserNavPolicy.decide("http://localhost:8000"))
        assertSame(Decision.Block, BrowserNavPolicy.decide("market://details?id=com.google.android.gms"))
        assertSame(Decision.Block, BrowserNavPolicy.decide("intent://scan/#Intent;scheme=zxing;package=com.x;end"))
        assertEquals(Decision.Redirect("https://example.com/fallback?a=1"),
            BrowserNavPolicy.decide("intent://x#Intent;scheme=app;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback%3Fa%3D1;end"))
    }
}
