package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.FleetTokenImport
import com.diegonmarcos.superapp.profile.FleetTokenImport.Outcome
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #1835 the vault to Cloud Account import of the fleet mesh token: the rule on a fake store, then the whole route (landing hook, real
 * store, real provider) under Robolectric. The values here are test fixtures, never a real token; no outcome ever carries one.
 */
@RunWith(RobolectricTestRunner::class)
class FleetTokenImportTest {
    private val t1 = "fixture-token-AAAAAAAAAAAA"
    private val t2 = "fixture-token-BBBBBBBBBBBB"
    private fun bundle(v: Any?) = JSONObject().put(FleetTokenImport.SECTION, JSONObject().apply { if (v != null) put(FleetTokenImport.KEY, v) })

    private class Fake(var held: String = "") {
        var writes = 0
        var accept = true
        fun file(v: String): Boolean { if (accept) { held = v; writes++ }; return accept }
    }

    private fun run(b: JSONObject, f: Fake) = FleetTokenImport.import(b, { f.held }, f::file)

    @Test fun `nothing declared or pending writes nothing`() {
        val f = Fake("keep-me-keep-me-keep")
        assertEquals(Outcome.NOT_DECLARED, run(JSONObject(), f))
        assertEquals(Outcome.NOT_DECLARED, run(bundle(null), f))
        assertEquals(Outcome.PENDING, run(bundle(JSONObject().put("pending", true).put("source", "vault")), f))
        assertEquals(0, f.writes); assertEquals("keep-me-keep-me-keep", f.held)
    }

    @Test fun `a malformed value is refused and never filed`() {
        val f = Fake()
        for (bad in listOf("short", "has space inside the token", "x".repeat(600), "   ", 12345)) {
            assertEquals(if (bad == "   ") Outcome.NOT_DECLARED else Outcome.INVALID, run(bundle(bad), f))
        }
        assertEquals(0, f.writes)
    }

    @Test fun `first import files, the same value is a no-op, a rotation replaces`() {
        val f = Fake()
        assertEquals(Outcome.FILED, run(bundle(t1), f)); assertEquals(t1, f.held)
        assertEquals(Outcome.UNCHANGED, run(bundle(" $t1 "), f)); assertEquals(1, f.writes)
        assertEquals(Outcome.REPLACED, run(bundle(t2), f)); assertEquals(t2, f.held)
    }

    @Test fun `a store that refuses is reported and an outcome never holds the value`() {
        val f = Fake().also { it.accept = false }
        val o = run(bundle(t1), f)
        assertEquals(Outcome.REFUSED, o)
        assertFalse(o.toString().contains(t1))
    }

    @Test fun `the importer targets the catalogue credential the webserver is granted`() {
        assertEquals("fleet.mesh_token", FleetTokenImport.CREDENTIAL)
        assertEquals(listOf(AppCredentialsContract.PKG_WEBSERVER), AppCredentialsContract.entry(FleetTokenImport.CREDENTIAL)!!.packages)
    }

    private lateinit var ctx: Context
    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_fti").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
    }
    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    @Test fun `landing files it and only the granted webserver reads it back over the provider`() {
        assertEquals(Outcome.FILED, FleetTokenImport.land(ctx, bundle(t1)))
        val call = { who: String ->
            AccountData.callerResolver = { who }
            Robolectric.buildContentProvider(AccountData.Provider::class.java).create(AccountData.AUTHORITY).get()
                .call(AppCredentialsContract.METHOD, FleetTokenImport.CREDENTIAL, null)
        }
        val denied = call("com.example.other")
        assertFalse(denied.getBoolean(AppCredentialsContract.KEY_OK))
        assertNull(denied.getString(AppCredentialsContract.KEY_VALUE))
        assertEquals(t1, call(AppCredentialsContract.PKG_WEBSERVER).getString(AppCredentialsContract.KEY_VALUE))
        assertEquals(Outcome.REPLACED, FleetTokenImport.land(ctx, bundle(t2)))
        assertEquals(t2, call(AppCredentialsContract.PKG_WEBSERVER).getString(AppCredentialsContract.KEY_VALUE))
    }
}
