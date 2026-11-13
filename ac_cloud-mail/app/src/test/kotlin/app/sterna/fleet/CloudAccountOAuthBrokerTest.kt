package app.sterna.fleet

import app.sterna.core.jmap.DeviceTokenResult
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1693 what Cloud Account answers for the OAuth device grant becomes the types the Connect walk already speaks; the screen says what it said before. */
class CloudAccountOAuthBrokerTest {
    @Test fun `a start answer becomes the device authorization, the handle standing in for the device code`() {
        val d = CloudAccountOAuthBroker.toDevice(MailOAuthContract.StartAnswer("H1", "WDJB-MJHT", "https://srv/device", "https://srv/device?c=1", 5, 900))!!
        assertEquals("H1", d.deviceCode); assertEquals("WDJB-MJHT", d.userCode); assertEquals(5, d.interval); assertEquals(900, d.expiresIn)
        assertEquals("https://srv/device?c=1", d.verificationUriComplete)
        assertNull(CloudAccountOAuthBroker.toDevice(null))
    }

    @Test fun `every poll state maps onto the verdict the walk handles`() {
        assertEquals(DeviceTokenResult.Pending, CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("pending")))
        assertEquals(DeviceTokenResult.SlowDown, CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("slow_down")))
        val ok = CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("success", "AT", "RT", 3600, "IDT", "mail")) as DeviceTokenResult.Success
        assertEquals("AT", ok.tokens.accessToken); assertEquals("RT", ok.tokens.refreshToken); assertEquals(3600L, ok.tokens.expiresIn); assertEquals("IDT", ok.tokens.idToken)
        val f = CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("failed", error = "authorization_declined", description = "AADSTS650051: x", aadsts = "AADSTS650051")) as DeviceTokenResult.Failed
        assertEquals("authorization_declined", f.error); assertEquals("AADSTS650051", f.aadstsCode)
    }

    @Test fun `an unreadable or unknown answer is a failure with a fixed word, never an exception`() {
        assertEquals(DeviceTokenResult.Failed("unreadable_answer"), CloudAccountOAuthBroker.toResult(null))
        assertEquals(DeviceTokenResult.Failed("unreadable_answer"), CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("teapot")))
        assertEquals(DeviceTokenResult.Failed("unknown_error"), CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("failed")))
    }

    @Test fun `the wire answer round-trips and no log form carries a token`() {
        val a = MailOAuthContract.PollAnswer("success", "SECRET-AT", "SECRET-RT", 60)
        assertEquals("success", MailOAuthContract.parsePoll(a.json())!!.state)
        assertFalse(a.toString().contains("SECRET"))
        val s = MailOAuthContract.StartAnswer("H", "UC", "https://v", null, 5, 60)
        assertEquals(s, MailOAuthContract.parseStart(s.json())); assertTrue(MailOAuthContract.parseStart("nope") == null)
    }

    // ---- #1703 the authorization-code grant ----

    @Test fun `a code start answer is the handle the screen carries, and nothing else of it`() {
        assertEquals("H9", CloudAccountOAuthBroker.toHandle(MailOAuthContract.CodeStartAnswer("H9", 600)))
        assertNull(CloudAccountOAuthBroker.toHandle(null))
    }

    @Test fun `the code finish answer maps onto the same verdicts, a failure word reaching the screen unchanged`() {
        val ok = CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("success", "AT", "RT", 3600)) as DeviceTokenResult.Success
        assertEquals("AT", ok.tokens.accessToken)
        assertEquals(DeviceTokenResult.Failed("state_mismatch"), CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("failed", error = MailOAuthContract.ERR_STATE_MISMATCH)))
        assertEquals(DeviceTokenResult.Failed("access_denied"), CloudAccountOAuthBroker.toResult(MailOAuthContract.PollAnswer("failed", error = "access_denied")))
    }

    @Test fun `the code request on the wire names the package's own redirect and carries no secret of ours`() {
        val r = MailOAuthContract.CodeRequest("me@example.org", "mail.example.org", "https://auth.example.org/authorize", "https://auth.example.org/token", "sterna", "mail", "app.sterna://oauth")
        val j = r.json()
        assertFalse("no verifier or state is in this app to send", j.contains("verifier") || j.contains("\"state\""))
        assertEquals(r, MailOAuthContract.parseCode(j, "app.sterna"))
        assertNull(MailOAuthContract.parseCode(j, "someone.else"))
    }
}
