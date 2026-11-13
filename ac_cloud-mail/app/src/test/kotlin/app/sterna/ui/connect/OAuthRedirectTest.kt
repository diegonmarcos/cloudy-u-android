package app.sterna.ui.connect

import app.sterna.core.jmap.OAuthMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXECUTES Cloud Mail's half of the browser round-trip's decisions; it reads no source file. #1703 the state check, the verifier, the exchange body and the
 * one-redeem-per-request rule MOVED to Cloud Account with the grant (ac_cloud-account MailOAuthCodeSessionsTest, same cases); what stays here is the scheme
 * this app registered, the one request waiting for the browser and the words Cloud Account answers a redirect with.
 */
class OAuthRedirectTest {

    private val metadata = OAuthMetadata(
        issuer = "https://mail.example.com",
        authorizationEndpoint = "https://mail.example.com/authorize",
        tokenEndpoint = "https://mail.example.com/token",
    )

    private fun request(handle: String = "HANDLE-1") =
        PendingHandover(
            handle = handle,
            host = "mail.example.com",
            metadata = metadata,
            email = "user@example.com",
            accountName = "Work",
        )

    // ---- The redirect URI follows the applicationId, it is not a literal ----

    @Test fun `the redirect uri is the application id plus the oauth host`() {
        assertEquals("app.sterna://oauth", oauthRedirectUri("app.sterna"))
    }

    /**
     * The mutation this kills: `"app.sterna://oauth"` written as a constant. The test build is
     */
    @Test fun `the redirect uri follows the package it is asked about`() {
        assertEquals("app.sterna.test://oauth", oauthRedirectUri("app.sterna.test"))
        assertEquals("org.example.fork://oauth", oauthRedirectUri("org.example.fork"))
        assertNotEquals(oauthRedirectUri("app.sterna"), oauthRedirectUri("app.sterna.test"))
    }

    @Test fun `a redirect belongs to us only by our own scheme and host`() {
        assertTrue(isOAuthRedirect("app.sterna.test", "oauth", "app.sterna.test"))
        assertFalse(
            "the test build must not answer for the production scheme",
            isOAuthRedirect("app.sterna", "oauth", "app.sterna.test"),
        )
        assertFalse(isOAuthRedirect("app.sterna", "callback", "app.sterna"))
        assertFalse(isOAuthRedirect("https", "oauth", "app.sterna"))
        assertFalse(isOAuthRedirect("mailto", null, "app.sterna"))
        assertFalse(isOAuthRedirect(null, null, "app.sterna"))
    }

    // ---- One redirect, one hand-over ----

    @Test fun `the same return twice hands over once`() {
        val slot = AuthorizationRequestSlot()
        slot.arm(request())
        assertEquals("HANDLE-1", slot.take()?.handle)
        assertNull("a replayed redirect must find nothing", slot.take())
        assertFalse(slot.isArmed())
    }

    @Test fun `a new request replaces the one in flight`() {
        val slot = AuthorizationRequestSlot()
        slot.arm(request("OLD")); slot.arm(request("NEW"))
        assertEquals("NEW", slot.take()?.handle)
    }

    @Test fun `cancelling leaves nothing a late redirect could hand over`() {
        val slot = AuthorizationRequestSlot()
        slot.arm(request())
        slot.disarm()
        assertFalse(slot.isArmed())
        assertNull(slot.take())
    }

    @Test fun `a fresh slot has nothing in flight`() {
        val slot = AuthorizationRequestSlot()
        assertFalse(slot.isArmed())
        assertNull(slot.take())
    }

    /** The slot holds the handle, never a verifier or a state: they are in Cloud Account. */
    @Test fun `a request cannot exist without Cloud Accounts handle`() {
        assertThrows(IllegalArgumentException::class.java) { request(handle = "") }
        assertFalse(request().toString().contains("VERIFIER"))
    }

    // ---- Cloud Account's words for a redirect it could not redeem ----

    @Test fun `Cloud Accounts three failure words keep their sentences and any other word is the providers own`() {
        assertEquals(RedirectProblem.STATE_MISMATCH, redirectProblemOf("state_mismatch"))
        assertEquals(RedirectProblem.NO_PENDING, redirectProblemOf("no_pending_request"))
        assertEquals(RedirectProblem.NOTHING_USABLE, redirectProblemOf("nothing_usable"))
        assertEquals(RedirectProblem.OTHER, redirectProblemOf("access_denied"))
        assertEquals(RedirectProblem.OTHER, redirectProblemOf("invalid_grant"))
        assertEquals(RedirectProblem.OTHER, redirectProblemOf(""))
    }
}
