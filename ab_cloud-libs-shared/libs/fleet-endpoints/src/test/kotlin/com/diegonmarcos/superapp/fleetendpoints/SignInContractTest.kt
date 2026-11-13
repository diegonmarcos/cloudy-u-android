package com.diegonmarcos.superapp.fleetendpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInContractTest {
    @Test fun `a status round-trips and carries no secret field`() {
        val s = SignInContract.Status("github", SignInContract.State.SIGNED_IN, "signed in via gh", "2026-11-12T10:00:00Z")
        val o = s.json()
        assertEquals(setOf("v", "provider", "state", "line", "at"), o.keys().asSequence().toSet())
        val back = SignInContract.parse("github", true, o.toString(), null)
        assertEquals(s, back)
        assertTrue(back.signedIn)
    }

    @Test fun `a refusal, garbage or a newer wire is unknown with a reason, never a crash`() {
        val r = SignInContract.parse("github", false, null, "no grant")
        assertEquals(SignInContract.State.UNKNOWN, r.state); assertTrue(r.line.contains("no grant"))
        val g = SignInContract.parse("github", true, "<html>", null)
        assertEquals(SignInContract.State.UNKNOWN, g.state); assertTrue(g.line.isNotBlank())
        val n = SignInContract.parse("github", true, """{"v":99,"state":"signed_in"}""", null)
        assertFalse(n.signedIn); assertTrue(n.line.contains("newer"))
        assertEquals(SignInContract.State.UNKNOWN, SignInContract.parse("x", true, """{"v":1,"state":"weird"}""", null).state)
        assertFalse(SignInContract.missing("gitea").signedIn)
    }

    @Test fun `the open intent lands on Cloud Account's sign-in pages`() {
        assertEquals("com.diegonmarcos.cloudaccount.OPEN", SignInContract.ACTION_OPEN)
        assertEquals(mapOf("section" to "connect", "page" to "signin", "provider" to "github"), SignInContract.openIntentExtras("github"))
        assertEquals("hub", SignInContract.openIntentExtras("google")["page"])
    }

    @Test fun `a session answer round-trips, expires, and never prints the cookie`() {
        val live = SignInContract.Session("authelia", SignInContract.State.SIGNED_IN, "Cookie", "authelia_session=SECRETVALUE", 5_000L, "ok")
        val back = SignInContract.parseSession("authelia", true, live.json().toString(), null, nowMs = 1_000L)
        assertTrue(back.usable(1_000L)); assertEquals("authelia_session=SECRETVALUE", back.cookie)
        assertFalse(back.toString().contains("SECRETVALUE")); assertFalse(live.toString().contains("SECRETVALUE"))
        // past its expiry the answer reads as signed out, and the cookie is dropped
        val gone = SignInContract.parseSession("authelia", true, live.json().toString(), null, nowMs = 5_000L)
        assertFalse(gone.usable(5_000L)); assertEquals("", gone.cookie); assertEquals(SignInContract.State.SIGNED_OUT, gone.state)
    }

    @Test fun `a refused, garbled or newer session answer is no session with a reason`() {
        val r = SignInContract.parseSession("authelia", false, null, "caller is not a fleet app", 0L)
        assertEquals("", r.cookie); assertTrue(r.line.contains("not a fleet app")); assertEquals(SignInContract.State.UNKNOWN, r.state)
        assertEquals("", SignInContract.parseSession("authelia", true, "<html>", null, 0L).cookie)
        assertTrue(SignInContract.parseSession("authelia", true, """{"v":9,"state":"signed_in","cookie":"x","expires_at":99}""", null, 0L).line.contains("newer"))
    }
}
