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
}
