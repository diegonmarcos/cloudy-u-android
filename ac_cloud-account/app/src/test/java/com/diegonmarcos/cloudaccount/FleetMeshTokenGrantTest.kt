package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.settings.AccountVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1835 the fleet mesh token is filed from the vault and granted to Cloud C3 Webserver (its owner) alone: seed v21. */
class FleetMeshTokenGrantTest {
    @Test fun `seed v21 grants the webserver the fleet mesh token and nothing else`() {
        assertTrue(AccountVault.SEED_VERSION >= 21)
        assertEquals(mapOf("com.diegonmarcos.cloudwebserver" to listOf("cred.fleet.mesh_token")), AccountVault.DEFAULT_GRANTS_BY_VERSION.getValue(21))
        val e = AppCredentialsContract.entry("fleet.mesh_token")!!
        assertTrue("a secret: hidden on the page", e.secret)
        assertEquals(listOf(AppCredentialsContract.PKG_WEBSERVER), e.packages)
    }
}
