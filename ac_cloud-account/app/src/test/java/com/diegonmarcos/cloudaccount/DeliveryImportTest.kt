package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.AppCredentialsStore
import com.diegonmarcos.superapp.profile.DeliveryImport
import com.diegonmarcos.superapp.profile.DeliveryImport.Outcome
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.BundleCrypto
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** #1807 vault delivery of app credentials: the rule on a fake store, then the whole route (encrypted bundle, importBundle, real store). Fixture values only. */
@RunWith(RobolectricTestRunner::class)
class DeliveryImportTest {
    private val k1 = "fixture-relay-key-AAAAAAAA"
    private val k2 = "fixture-relay-key-BBBBBBBB"
    private val id = "mesh.relay_key"
    private fun delivery(vararg kv: Pair<String, Any>) = JSONObject().put(DeliveryImport.APPCRED, JSONObject().apply { kv.forEach { put(it.first, it.second) } })

    private class Fake(val held: MutableMap<String, String> = mutableMapOf(), var accept: Boolean = true) {
        fun file(i: String, v: String): Boolean { if (accept) held[i] = v; return accept }
    }
    private fun run(d: JSONObject?, f: Fake) = DeliveryImport.import(d, { f.held[it].orEmpty() }, f::file)

    @Test fun `nothing delivered writes nothing`() {
        val f = Fake()
        assertTrue(run(null, f).isEmpty()); assertTrue(run(JSONObject(), f).isEmpty())
        assertTrue(f.held.isEmpty())
    }

    @Test fun `a catalogue id is filed once and a held value (manual paste) is kept`() {
        val f = Fake()
        assertEquals(mapOf(id to Outcome.FILED), run(delivery(id to k1), f)); assertEquals(k1, f.held[id])
        assertEquals(mapOf(id to Outcome.KEPT_MANUAL), run(delivery(id to k2), f)); assertEquals(k1, f.held[id])
    }

    @Test fun `unknown ids, malformed values and a refusing store are reported without the value`() {
        val f = Fake()
        val r = run(delivery("not.a_credential" to k1, id to "short", "git.github_token" to 5), f)
        assertEquals(Outcome.UNKNOWN_ID, r["not.a_credential"]); assertEquals(Outcome.INVALID, r[id]); assertEquals(Outcome.INVALID, r["git.github_token"])
        assertTrue(f.held.isEmpty())
        val g = Fake(accept = false)
        val o = run(delivery(id to k1), g)
        assertEquals(Outcome.REFUSED, o[id]); assertFalse(o.toString().contains(k1))
    }

    private lateinit var ctx: Context
    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_dlv").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
    }
    @After fun tearDown() { ConfigsPrefs.factory = null }

    private fun sealed(v: String, pass: String) = BundleCrypto.encrypt(
        JSONObject().put("kind", AccountVault.KIND).put("v", AccountVault.VERSION).put("at", "2026-11-24T00:00:00Z").put(DeliveryImport.SECTION, delivery(id to v)).toString(),
        pass.toCharArray(), iter = 100_000)

    @Test fun `an encrypted delivery bundle round-trips through importBundle, no plaintext in it, the manual paste wins`() {
        val vault = AccountVault(ctx)
        val text = sealed(k1, "fixture-pass")
        assertFalse(text.contains(k1))
        val r = vault.importBundle(text, "fixture-pass".toCharArray())
        assertTrue(r.ok); assertTrue(r.sections.contains("delivery"))
        assertEquals(k1, AppCredentialsStore.get(vault, id))
        // a later paste is the override, and a re-delivery does not undo it
        AppCredentialsStore.put(vault, id, k2)
        assertTrue(vault.importBundle(text, "fixture-pass".toCharArray()).ok)
        assertEquals(k2, AppCredentialsStore.get(vault, id))
        assertFalse(vault.importBundle(text, "wrong".toCharArray()).ok)
    }
}
