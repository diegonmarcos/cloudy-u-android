package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.fleetendpoints.SignInContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1693 item 1: the short-lived Authelia session Cloud Account serves over the `session` method. */
class SessionLeaseTest {
    private var now = 1_000_000L
    @Before fun setUp() { SessionLease.clock = { now }; SessionLease.forget() }
    @After fun tearDown() { SessionLease.forget(); SessionLease.clock = { System.currentTimeMillis() } }
    private val auth = SignInContract.PROVIDER_AUTHELIA

    @Test fun `only a caller with the fleet signature permission gets a session`() {
        SessionLease.remember("authelia_session=SECRETVALUE")
        val refused = SessionLease.answer(auth, allowed = false)
        assertEquals("", refused.cookie); assertEquals(SignInContract.State.UNKNOWN, refused.state)
        assertFalse(refused.line.contains("SECRETVALUE"))
        val ok = SessionLease.answer(auth, allowed = true)
        assertTrue(ok.usable(now)); assertEquals("authelia_session=SECRETVALUE", ok.cookie)
    }

    @Test fun `no session is held for any other provider, and none before a sign-in`() {
        assertEquals(SignInContract.State.SIGNED_OUT, SessionLease.answer(auth, true).state)
        SessionLease.remember("c=1")
        assertEquals("", SessionLease.answer("github", true).cookie)
    }

    @Test fun `the lease expires and then reads as signed out`() {
        SessionLease.remember("c=1", ttlMs = 5_000)
        assertEquals(now + 5_000, SessionLease.expiresAt())
        now += 5_000
        val s = SessionLease.answer(auth, true, probe = { true })
        assertEquals("", s.cookie); assertEquals(SignInContract.State.SIGNED_OUT, s.state); assertEquals(0L, SessionLease.expiresAt())
    }

    @Test fun `near expiry Cloud Account refreshes it, and drops it when the portal refuses`() {
        SessionLease.remember("c=1", ttlMs = SessionLease.REFRESH_WINDOW_MS - 1)
        var probed = 0
        val s = SessionLease.answer(auth, true) { probed++; true }
        assertEquals(1, probed); assertEquals(now + SessionLease.TTL_MS, s.expiresAtMs)
        // far from expiry: no probe at all
        SessionLease.answer(auth, true) { probed++; true }
        assertEquals(1, probed)
        now += SessionLease.TTL_MS - 1000
        assertEquals("", SessionLease.answer(auth, true) { false }.cookie)
    }

    @Test fun `a refusal never reaches a caller that is not allowed, and a probe is not run for it`() {
        SessionLease.remember("c=1", ttlMs = 1000)
        var probed = 0
        SessionLease.answer(auth, false) { probed++; true }
        assertEquals(0, probed)
    }

    @Test fun `the portal probe reads the state route and sends the cookie only over https to the declared host`() {
        assertTrue(SessionProbe.accepted(200, """{"status":"OK","data":{"authentication_level":2}}"""))
        assertFalse(SessionProbe.accepted(200, """{"data":{"authentication_level":0}}"""))
        assertFalse(SessionProbe.accepted(401, ""))
        assertEquals("https://auth.example.org/api/state", SessionProbe.stateUrl("https://auth.example.org/portal/"))
        assertEquals(null, SessionProbe.stateUrl("http://auth.example.org/"))
        assertEquals(null, SessionProbe.stateUrl(""))
    }
}
