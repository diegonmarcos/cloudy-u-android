package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.profile.AuthProviderLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1083 Connect ▸ Authentication providers ▸ Providers: the page is declared once and holds identities only. */
@RunWith(RobolectricTestRunner::class)
class AuthProvidersTest {
    @Test fun theProvidersPageIsTheFirstLeafOfAuthenticationProviders() {
        val auth = MainActivity.NAV.section("connect")!!.page("auth")!!
        assertEquals(listOf("hub", "signin", "import-configs", "settings"), auth.pages.map { it.id })
        assertEquals("Providers", auth.pages.first().label)
    }

    @Test fun theLogKeepsAnIdentityPerFamilyAndNothingElse() {
        val mem = HashMap<String, String>()
        val log = AuthProviderLog({ mem[it].orEmpty() }, { k, v -> mem[k] = v })
        log.record("github", "diegonmarcos")
        log.record("google", "")           // a blank identity is not recorded
        assertEquals(mapOf("github" to "diegonmarcos"), log.all(listOf("authelia", "google", "github", "cloudflare")))
        assertTrue("stored under the one config store's own key", mem.keys.all { it.startsWith("auth_identity.") })
        log.forget("github")
        assertTrue(log.all(listOf("github")).isEmpty())
    }
}
