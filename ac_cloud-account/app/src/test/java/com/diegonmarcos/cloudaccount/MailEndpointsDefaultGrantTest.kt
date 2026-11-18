package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.settings.AccountVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1803 Cloud Mail's compose recipient check reads the `communication-mcp` endpoint pair Cloud Account publishes, so the
 * mail package is granted `fleet.endpoints` by the declared default-grant table (seed v19), not by hand. The pairs are not
 * secrets: v19 auto-grants exactly that one key, and the bearer keeps its own, earlier gate.
 */
@RunWith(RobolectricTestRunner::class)
class MailEndpointsDefaultGrantTest {

    @Test fun `seed v19 grants the mail app fleet endpoints and nothing else`() {
        assertEquals(19, AccountVault.SEED_VERSION)
        assertEquals(
            mapOf(AccountVault.MAIL_PACKAGE to listOf(FleetEndpointsContract.GRANT_ENDPOINTS)),
            AccountVault.DEFAULT_GRANTS_BY_VERSION.getValue(19),
        )
    }

    @Test fun `v19 auto-grants no secret and does not widen the bearer`() {
        val keys = AccountVault.DEFAULT_GRANTS_BY_VERSION.getValue(19).values.flatten()
        assertTrue("only fleet.endpoints: $keys", keys.all { it == FleetEndpointsContract.GRANT_ENDPOINTS })
        assertTrue("no bearer in v19", FleetEndpointsContract.GRANT_BEARER !in keys)
        assertTrue("no credential, wildcard or export in v19", keys.none { it.startsWith("cred.") || it.endsWith("*") || it.startsWith("auth.") })
        // the mail app's bearer grant is still the one v2 gave, from before this change
        assertTrue(AccountVault.DEFAULT_GRANTS_BY_VERSION.getValue(2).getValue(AccountVault.MAIL_PACKAGE).contains(FleetEndpointsContract.GRANT_BEARER))
    }

    @Test fun `the endpoint the grant is for is declared`() {
        val decl = org.json.JSONObject(java.io.File("../build.json").readText()).getJSONObject("fleet_endpoints").toString()
        assertTrue(FleetEndpointsContract.parseDecl(decl).any { it.id == "communication-mcp" })
    }
}
