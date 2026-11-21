package com.diegonmarcos.cloudaccount

import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.settings.AccountVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1798 The TLS-443 relay key reaches the SuperApp from Cloud Account (App credentials, filed from the vault bundle), not
 * from a hand paste: seed v20 grants exactly `cred.mesh.relay_key` to the SuperApp and to no other package, once, revocably.
 */
@RunWith(RobolectricTestRunner::class)
class RelayKeyDefaultGrantTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `seed v20 grants the SuperApp the relay key and nothing else`() {
        assertTrue("v20 is seeded (v21, #1835, came after it)", AccountVault.SEED_VERSION >= 20)
        assertEquals(mapOf("com.diegonmarcos.superapp" to listOf("cred.mesh.relay_key")), AccountVault.DEFAULT_GRANTS_BY_VERSION.getValue(20))
        val e = AppCredentialsContract.entry("mesh.relay_key")!!
        assertTrue("a secret: hidden on the page", e.secret)
        assertEquals(listOf(AppCredentialsContract.PKG_SUPERAPP), e.packages)
        assertEquals("cred.mesh.relay_key", AppCredentialsContract.grant("mesh.relay_key"))
    }

    @Test fun `an install at v19 gets the grant once and a revoke sticks`() {
        val v = AccountVault(ctx)
        v.prefs.putText("grants_seeded", "19")
        v.seedDefaults()
        assertTrue(v.grants.allow("com.diegonmarcos.superapp", "cred.mesh.relay_key"))
        assertFalse("no other package", v.grants.allow("com.diegonmarcos.cloudstore", "cred.mesh.relay_key"))
        v.grants.revoke("com.diegonmarcos.superapp", "cred.mesh.relay_key"); v.seedDefaults()
        assertFalse("revoked stays revoked", v.grants.allow("com.diegonmarcos.superapp", "cred.mesh.relay_key"))
    }
}
