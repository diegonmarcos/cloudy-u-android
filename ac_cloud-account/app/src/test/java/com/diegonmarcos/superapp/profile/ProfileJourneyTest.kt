package com.diegonmarcos.superapp.profile

import android.app.Application
import com.diegonmarcos.cloudlib.auth.ProfileJourney
import com.diegonmarcos.cloudlib.auth.ProfileJourney.Lock
import com.diegonmarcos.cloudlib.auth.ProfileJourney.Phase
import com.diegonmarcos.cloudlib.auth.ProfileJourney.State
import com.diegonmarcos.cloudlib.auth.ProfileJourney.Step
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.UserRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #573 — Configs ▸ Profile ▸ Connect, THE JOURNEY: the state machine (libs:auth
 * ProfileJourney). Its View layout (ProfileJourneyView) was deleted with ProfileFragment
 * (Cloud Account redesign, task 3), so only the state machine is exercised here.
 *
 * The registry fixture is built from a table and every expectation is a walk
 * of that table, never a typed count. The state machine is exercised as the
 * page would drive it — nothing, a stored bearer, a session, a registry, the
 * two picks, an apply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileJourneyTest {

    private val identities = listOf(Triple("one@t.test", true, "personal"), Triple("two@t.test", false, "work"))
    /** id → (label, primary, profile ids) */
    private val peers = linkedMapOf(
        "p-a" to Triple("Phone A", true, "v4-full,v6-split"),
        "p-b" to Triple("Phone B", false, ""),
    )

    private fun registry(): UserRegistry.Registry {
        val root = JSONObject().put("_meta", JSONObject().put("user", "tester"))
            .put("profile", JSONObject().put("name", "Tester Person"))
        root.put("identities", JSONArray().apply {
            identities.forEach { (e, p, l) -> put(JSONObject().put("email", e).put("primary", p).put("label", l)) }
        })
        root.put("peers", JSONObject().apply {
            peers.forEach { (id, row) ->
                val wg = JSONObject()
                row.third.split(",").filter { it.isNotBlank() }.forEach { wg.put(it, JSONObject().put("name", "wg-$it").put("config_text", "x")) }
                put(id, JSONObject().put("label", row.first).put("primary", row.second).put("wireguard", wg))
            }
        })
        return UserRegistry.parse(root)!!
    }

    private val primaryEmail = identities.first { it.second }.first
    private val otherEmail = identities.first { !it.second }.first
    private val primaryPeer = peers.entries.first { it.value.second }.key
    private val otherPeer = peers.entries.first { !it.value.second }.key

    // ── the state machine ────────────────────────────────────────────────

    @Test fun `nothing known - step 1 is active and every other step is locked behind it`() {
        val s = State()
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(s, Step.SIGN_IN))
        for (step in Step.values().drop(1)) {
            assertEquals(step.name, Phase.LOCKED, ProfileJourney.phase(s, step))
            assertEquals(step.name, Lock.SIGN_IN_FIRST, ProfileJourney.lock(s, step))
        }
        assertEquals(Step.SIGN_IN, ProfileJourney.next(s))
        assertEquals(1, ProfileJourney.stepNumber(s))
    }

    @Test fun `a stored bearer is a sign-in, but without a registry step 2 says the config was not fetched`() {
        val s = State(storedBearerEmail = primaryEmail)
        assertEquals(Phase.DONE, ProfileJourney.phase(s, Step.SIGN_IN))
        assertEquals(Lock.NO_REGISTRY, ProfileJourney.lock(s, Step.WHO))
        assertEquals(Phase.LOCKED, ProfileJourney.phase(s, Step.WHO))
        assertEquals(2, ProfileJourney.stepNumber(s))
    }

    @Test fun `an identity-only provider signs in and locks step 2 with its own reason`() {
        val s = State(session = SignIn.Session("idp", "who@t.test"), identityOnly = true)
        assertTrue(ProfileJourney.done(s, Step.SIGN_IN))
        assertEquals(Lock.IDENTITY_ONLY, ProfileJourney.lock(s, Step.WHO))
        assertEquals(Lock.IDENTITY_ONLY, ProfileJourney.lock(s, Step.DEVICE))
        assertEquals(Lock.IDENTITY_ONLY, ProfileJourney.lock(s, Step.GET))
    }

    @Test fun `with a registry step 2 is active and the proved address wins the default over the primary`() {
        val proved = State(session = SignIn.Session("sso", otherEmail), registry = registry())
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(proved, Step.WHO))
        assertEquals(otherEmail, ProfileJourney.defaultIdentity(proved)!!.email)
        val unproved = State(session = SignIn.Session("sso", "nobody@t.test"), registry = registry())
        assertEquals(primaryEmail, ProfileJourney.defaultIdentity(unproved)!!.email)
        val bearer = State(storedBearerEmail = otherEmail, registry = registry())
        assertEquals(otherEmail, ProfileJourney.defaultIdentity(bearer)!!.email)
        // A default is not a pick: step 2 stays undone until the owner taps a row.
        assertFalse(ProfileJourney.done(proved, Step.WHO))
        assertEquals(Lock.PICK_IDENTITY, ProfileJourney.lock(proved, Step.DEVICE))
    }

    @Test fun `the picks walk the steps in order, and the primary peer is the device default`() {
        val base = State(session = SignIn.Session("sso", primaryEmail), registry = registry(), artifactInMemory = true)
        assertEquals(primaryPeer, ProfileJourney.defaultPeer(base)!!.id)
        val who = base.copy(identity = primaryEmail)
        assertEquals(Phase.DONE, ProfileJourney.phase(who, Step.WHO))
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(who, Step.DEVICE))
        assertEquals(Lock.PICK_PEER, ProfileJourney.lock(who, Step.GET))
        val device = who.copy(peer = otherPeer)
        assertEquals(Phase.DONE, ProfileJourney.phase(device, Step.DEVICE))
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(device, Step.GET))
        assertNull(ProfileJourney.lock(device, Step.GET))
        assertEquals(4, ProfileJourney.stepNumber(device))
        val applied = device.copy(appliedAt = "2026-09-25 18:00")
        assertTrue(ProfileJourney.allDone(applied))
        assertEquals(Phase.DONE, ProfileJourney.phase(applied, Step.GET))
    }

    @Test fun `a pick that the registry no longer knows does not count`() {
        val s = State(storedBearerEmail = primaryEmail, registry = registry(), identity = "gone@t.test", peer = "p-gone")
        assertFalse(ProfileJourney.done(s, Step.WHO))
        assertNull(s.chosenIdentity)
        assertNull(s.chosenPeer)
    }

    @Test fun `after a restart the picks hold but step 4 asks for a re-fetch, and a failed step shows red`() {
        val s = State(storedBearerEmail = primaryEmail, registry = registry(), identity = primaryEmail, peer = primaryPeer, artifactInMemory = false)
        assertEquals(Lock.REFETCH, ProfileJourney.lock(s, Step.GET))
        assertEquals(Phase.LOCKED, ProfileJourney.phase(s, Step.GET))
        val failed = s.copy(artifactInMemory = true, failed = setOf(Step.GET))
        assertEquals(Phase.FAILED, ProfileJourney.phase(failed, Step.GET))
        assertTrue(ProfileJourney.bodyOpen(Phase.FAILED))
    }

    @Test fun `#766 a vault landing alone is a sign-in, its registry answers who and which device, and step 4 is not a re-fetch`() {
        val fetched = State(vaultFetched = true)
        assertTrue(fetched.signedIn)
        assertEquals(Phase.DONE, ProfileJourney.phase(fetched, Step.SIGN_IN))
        // No registry yet → the same lock as any sign-in without one, never "sign in first".
        assertEquals(Lock.NO_REGISTRY, ProfileJourney.lock(fetched, Step.WHO))
        val picked = fetched.copy(registry = registry(), identity = primaryEmail, peer = otherPeer)
        assertEquals(Phase.DONE, ProfileJourney.phase(picked, Step.DEVICE))
        assertNull(ProfileJourney.lock(picked, Step.GET))
        assertEquals(Phase.ACTIVE, ProfileJourney.phase(picked, Step.GET))
        // The control: the same picks with neither the artifact nor the vault in memory ask for a re-fetch.
        assertEquals(Lock.REFETCH, ProfileJourney.lock(picked.copy(vaultFetched = false, storedBearerEmail = primaryEmail), Step.GET))
        assertFalse(State().signedIn)
    }

}
