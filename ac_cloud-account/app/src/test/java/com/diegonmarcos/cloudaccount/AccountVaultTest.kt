package com.diegonmarcos.cloudaccount

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetconfig.FleetPolicy
import com.diegonmarcos.superapp.fleetconfig.SetupContract
import com.diegonmarcos.superapp.fleetconfig.SetupProvider
import com.diegonmarcos.superapp.fleetconfig.SetupStores
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountMigrate
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.FleetSetup
import com.diegonmarcos.superapp.profile.SetupPlan
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.BundleCrypto
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #874 THE ACCOUNT VAULT, round trip on a real provider under Robolectric: the four sections live in one
 * store, the whole bundle leaves encrypted and versioned and arrives intact, a package reads a secret only
 * with a grant and loses it on revoke, the setup provider's authorizer is the same table, and what SuperApp
 * kept is taken in through the setup contract.
 */
@RunWith(RobolectricTestRunner::class)
class AccountVaultTest {
    private lateinit var ctx: Context
    private val other = "com.example.other"

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { ctx.packageName }
        SetupStores.authorizer = SetupStores.defaultAuthorizer
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
        SetupStores.authorizer = SetupStores.defaultAuthorizer
        FleetPolicy.testManifest = java.io.File("../../fleet-private-data/fleet-config.json").readText()
        SetupProvider.manifestSource = { FleetPolicy.manifestOrNull(it) }
    }

    private fun provider() = Robolectric.buildContentProvider(AccountData.Provider::class.java).create(AccountData.AUTHORITY).get()
    private fun secret(path: String) = provider().call(AccountData.METHOD_SECRET, path, null)

    private fun seeded(): AccountVault = AccountVault(ctx).also {
        it.putConnection("fleet.bearer", "tok-123456")
        it.putConnection("mail.password", "hunter2")
        it.putConnection("dns.preset", "quad9")
        it.putConnection("wg.if_listen_port", 51820)
        it.putIdentities(JSONArray().put(JSONObject().put("label", "me")))
        it.putAppConfigs(JSONObject().put("me", JSONObject().put("x_prefs", JSONObject().put("x_prefs", JSONObject().put("theme", "dark")))))
        it.grants.grant(other, "fleet.bearer")
        ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE).edit().putString("name", "Diego").putInt("age", 7).commit()
        AccountStore(File(ctx.filesDir, "account_test"), AccountStore.PlainIo).write(AccountStore.Slot.L, JSONObject().put("a", 1), "test", "2026-10-06")
    }

    @Test fun fourSectionsLiveInOneStoreAndTypesSurvive() {
        val v = seeded()
        assertEquals("quad9", v.connection("dns.preset")); assertEquals(51820, v.connection("wg.if_listen_port"))
        val s = v.summary()
        assertEquals(4, s.getInt("connections")); assertEquals(3, s.getInt("data")); assertEquals(1, s.getInt("configs")); assertEquals(1, s.getInt("secrets"))
        // one keystore-backed file: every section is a key of the same prefs
        val keys = ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.toString(), keys.containsAll(listOf("configs_json", "data_json", "app_configs_json", "grants_json")))
    }

    @Test fun theBundleRoundTripsEncryptedAndVersioned() {
        val text = seeded().exportBundle("correct horse".toCharArray(), "2026-10-06T00:00:00Z")
        for (secret in listOf("tok-123456", "hunter2", "Diego")) assertFalse("plaintext leaked: $secret", text.contains(secret))
        assertEquals(1, JSONObject(text).getInt("v"))
        // a clean phone
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(ctx.filesDir, "account_test").deleteRecursively()
        val v = AccountVault(ctx)
        assertTrue(v.connection("fleet.bearer") == null)
        val r = v.importBundle(text, "correct horse".toCharArray())
        assertTrue(r.why, r.ok); assertEquals(listOf("connections", "data", "configs", "secrets"), r.sections)
        assertEquals("tok-123456", v.connection("fleet.bearer")); assertEquals(51820, v.connection("wg.if_listen_port"))
        assertEquals("me", v.identities().getJSONObject(0).getString("label"))
        assertEquals("dark", v.appConfigs().getJSONObject("me").getJSONObject("x_prefs").getJSONObject("x_prefs").getString("theme"))
        assertTrue(v.grants.allow(other, "fleet.bearer"))
        assertEquals("Diego", ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE).getString("name", null))
        assertEquals(7, ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE).getInt("age", -1))
        assertNotNull(AccountStore(File(ctx.filesDir, "account_test"), AccountStore.PlainIo).read(AccountStore.Slot.L))
    }

    @Test fun aBundleIsRefusedForTheWrongPassphraseDamageAndANewerVersion() {
        val v = seeded(); val good = v.exportBundle("pw".toCharArray())
        assertEquals("wrong passphrase, or the bundle is damaged", v.importBundle(good, "nope".toCharArray()).why)
        val h = JSONObject(good)
        val flipped = JSONObject(good).put("ct", h.getString("ct").let { (if (it[10] == 'A') "B" else "A").let { c -> it.substring(0, 10) + c + it.substring(11) } }).toString()
        assertEquals("wrong passphrase, or the bundle is damaged", v.importBundle(flipped, "pw".toCharArray()).why)
        val newer = JSONObject(good).put("v", 2).toString()
        assertTrue(v.importBundle(newer, "pw".toCharArray()).why.contains("version 2"))
        assertEquals("not a Cloud Account bundle", v.importBundle("""{"kind":"x"}""", "pw".toCharArray()).why)
        assertFalse(v.importBundle("not json", "pw".toCharArray()).ok)
        // the version is authenticated: relabelling a v1 bundle as v1 with another header field is caught by the AAD only for v; kdf cost is bounded
        assertTrue(v.importBundle(JSONObject(good).put("iter", 5).toString(), "pw".toCharArray()).why.contains("key-derivation"))
        // a refused import writes nothing
        assertEquals("tok-123456", v.connection("fleet.bearer"))
    }

    @Test fun aSecretNeedsAGrantAndRevokeTakesItBack() {
        val v = seeded()
        assertEquals("tok-123456", secret("fleet.bearer").getString(AccountData.KEY_VALUE))              // self: always
        AccountData.callerResolver = { other }
        assertTrue(secret("fleet.bearer").getBoolean(AccountData.KEY_OK))                                  // granted
        assertEquals("tok-123456", secret("fleet.bearer").getString(AccountData.KEY_VALUE))
        val denied = secret("mail.password")                                                               // not granted
        assertFalse(denied.getBoolean(AccountData.KEY_OK)); assertEquals("no grant for $other", denied.getString(AccountData.KEY_ERROR))
        assertFalse(denied.toString().contains("hunter2"))
        v.grants.grant(other, "mail.*"); assertEquals("hunter2", secret("mail.password").getString(AccountData.KEY_VALUE))   // a prefix pattern
        v.grants.revoke(other, "mail.*"); assertFalse(secret("mail.password").getBoolean(AccountData.KEY_OK))               // revoked
        v.grants.revoke(other); assertFalse(secret("fleet.bearer").getBoolean(AccountData.KEY_OK))                           // revoked whole
    }

    @Test fun theWholeExportNeedsItsOwnGrant() {
        val v = seeded()
        AccountData.callerResolver = { other }
        assertFalse(provider().call(AccountData.METHOD_EXPORT, null, null).getBoolean(AccountData.KEY_OK))
        v.grants.grant(other, AccountData.GRANT_EXPORT)
        assertTrue(provider().call(AccountData.METHOD_EXPORT, null, null).getBoolean(AccountData.KEY_OK))
        assertFalse(provider().call("delete", null, null).getBoolean(AccountData.KEY_OK))
    }

    @Test fun theDefaultsAreSeededOnceAndStayRevoked() {
        val v = AccountVault(ctx); v.seedDefaults()
        assertTrue(v.grants.allow("com.diegonmarcos.superapp", "fleet.bearer")); assertTrue(v.grants.allow("com.diegonmarcos.superapp", AccountData.GRANT_EXPORT))
        assertTrue(v.grants.allow("com.diegonmarcos.cloudstore", "fleet.bearer")); assertFalse(v.grants.allow("com.diegonmarcos.cloudstore", "mail.password"))
        v.grants.revoke("com.diegonmarcos.cloudstore"); v.seedDefaults()
        assertFalse("a revoked default is not seeded back", v.grants.allow("com.diegonmarcos.cloudstore", "fleet.bearer"))
    }

    @Test fun theSetupProviderUsesTheSameGrants() {
        // this app's own setup provider, over a sample declaration with a secret key
        SetupProvider.manifestSource = { FleetPolicy.Manifest(JSONObject("""
          {"migrate":["config","secret"],"classes":{},"kinds":{},"resolve":{},"libs":{},
           "apps":{"acct":{"package":"${it.packageName}","module":"ac_acct","schema_version":1,"libs":[],"items":[]}},
           "stores":{"vault_like":{"kind":"prefs","class":"secret","doc":"d","used_by":["ac_acct"]}}}""")) }
        ctx.getSharedPreferences("vault_like", Context.MODE_PRIVATE).edit().putString("k", "v").commit()
        val v = AccountVault(ctx); SetupStores.authorizer = v.authorizer()
        val p = Robolectric.buildContentProvider(SetupProvider::class.java).create(SetupContract.authority(ctx.packageName)).get()
        fun export() = JSONObject(p.call(SetupContract.METHOD_EXPORT, "vault_like", Bundle())!!.getString(SetupContract.KEY_JSON)!!).getJSONObject("stores")
        assertTrue(export().has("vault_like"))                                  // the caller is this app: its own secret
        SetupStores.authorizer = SetupStores.Authorizer { c, _, op, s, k, sec -> v.authorizer().allow(c, other, op, s, k, sec) }
        assertFalse("a package with no grant gets nothing", export().has("vault_like"))
        v.grants.grant(other, "vault_like.k")
        assertTrue(export().has("vault_like"))
        v.grants.revoke(other, "vault_like.k")
        assertFalse(export().has("vault_like"))
    }

    @Test fun whatSuperAppKeptIsTakenInThroughTheSetupContractOnlyWhereTheVaultIsEmpty() {
        val m = FleetPolicy.Manifest(JSONObject("""
          {"migrate":["config","secret"],"classes":{},"kinds":{},"resolve":{},"libs":{},
           "bundle":{"dns.preset":[{"store":"fleet_dns_prefs","key":"preset","pull":"sa"}],
                     "dagu.server_url":[{"store":"dagu_prefs","key":"server_url","pull":"sa"}],
                     "fleet.bearer":[{"store":"dagu_prefs","key":"bearer_token","pull":"sa"}]},
           "apps":{"sa":{"package":"com.diegonmarcos.superapp","module":"aa_sa","schema_version":1,"libs":[],"items":[]}},
           "stores":{"fleet_dns_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["aa_sa"]},
                     "dagu_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["aa_sa"],"keys":{"bearer_token":"secret"}}}}"""))
        val exports = mapOf("fleet_dns_prefs" to """{"fleet_dns_prefs":{"fleet_dns_prefs":{"preset":"mirror"}}}""", "dagu_prefs" to """{"dagu_prefs":{"dagu_prefs":{"server_url":"https://dagu","bearer_token":"sa-bearer"}}}""")
        val t = FleetSetup.Transport { _, method, store, _ ->
            if (method != SetupContract.METHOD_EXPORT) SetupContract.Reply.Refused("no")
            else SetupContract.Reply.Ok(JSONObject().put("stores", JSONObject(exports[store] ?: "{}")))
        }
        val v = AccountVault(ctx); v.putConnection("dns.preset", "private")                   // the vault already holds this one
        val filled = AccountMigrate.pull(v, m, { true }, t)
        assertEquals(setOf("dagu.server_url", "fleet.bearer"), filled.map { it.path }.toSet())
        assertEquals("private", v.connection("dns.preset"))                                    // never overwritten
        assertEquals("sa-bearer", v.connection("fleet.bearer")); assertEquals("https://dagu", v.connection("dagu.server_url"))
        assertTrue(AccountMigrate.pull(v, m, { true }, t).isEmpty())                           // once filled, a no-op
        assertTrue(AccountMigrate.pull(AccountVault(ctx).also { ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit() }, m, { false }, t).isEmpty()) // not installed: nothing
    }

    @Test fun theBundleCipherIsDeterministicInNothing() {
        val a = BundleCrypto.encrypt("x", "pw".toCharArray(), 100_000); val b = BundleCrypto.encrypt("x", "pw".toCharArray(), 100_000)
        assertFalse("a fresh salt and iv every time", JSONObject(a).getString("ct") == JSONObject(b).getString("ct"))
        assertEquals("x", BundleCrypto.decrypt(a, "pw".toCharArray()))
    }

    /**
     * Defect: Connections 12 -> 1 after a Fleet Setup run. The plan pushed a captured `import_configs.configs_json`
     * snapshot back into Cloud Account's own vault file, replacing the live blob. A setup pass must leave the
     * Connections section as it was.
     */
    @Test fun aSetupPassLeavesTheConnectionsSectionIntact() {
        val v = AccountVault(ctx)
        (1..12).forEach { v.putConnection("c$it.k", "v$it") }
        val before = AccountVault.leaves(v.connections())
        assertEquals(12, before)
        val m = FleetPolicy.Manifest(JSONObject("""
          {"migrate":["config","secret"],"classes":{},"kinds":{},"resolve":{},"libs":{},"bundle":{"c1.k":[{"store":"x_prefs","key":"k"}]},
           "apps":{"account":{"package":"${ctx.packageName}","module":"ac_account","schema_version":1,"libs":["lib-account"],"items":[]}},
           "stores":{"import_configs":{"kind":"encrypted","class":"secret","doc":"d","used_by":["lib-account"],
                       "keys":{"configs_json":"secret","app_configs_json":"secret","data_json":"secret"}},
                     "x_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["lib-account"]}}}"""))
        // the app's export as captured (an older vault with ONE connection), filed under Configs
        val snapshot = JSONObject().put("c1", JSONObject().put("k", "old")).toString()
        val export = JSONObject().put("stores", JSONObject().put("import_configs", JSONObject().put("import_configs",
            JSONObject().put("configs_json", snapshot))).put("x_prefs", JSONObject().put("x_prefs", JSONObject().put("k", "v1"))))
        v.putAppConfigs(JSONObject().put("account", export.getJSONObject("stores")))        // as a pre-fix capture left it
        // a fake transport that writes into THIS app's stores like its setup provider does (import_configs = the vault file)
        val x = HashMap<String, Any?>()
        val t = FleetSetup.Transport { _, method, store, body ->
            when (method) {
                SetupContract.METHOD_DESCRIBE -> SetupContract.Reply.Ok(JSONObject().put("stores", JSONArray()
                    .put(JSONObject().put("name", "import_configs").put("served", true)).put(JSONObject().put("name", "x_prefs").put("served", true))))
                SetupContract.METHOD_APPLY -> {
                    val vals = body!!.getJSONObject("values")
                    if (store == "import_configs") vals.optString("configs_json").takeIf { it.isNotEmpty() }?.let { v.prefs.json = it }
                    else vals.keys().forEach { k -> x[k] = vals.get(k) }
                    SetupContract.Reply.Ok(JSONObject().put("ok", true))
                }
                else -> SetupContract.Reply.Ok(JSONObject().put("stores", JSONObject()
                    .put("import_configs", JSONObject().put("import_configs", JSONObject().put("configs_json", v.prefs.json)))
                    .put("x_prefs", JSONObject().put("x_prefs", JSONObject().also { o -> x.forEach { (k, xv) -> o.put(k, xv) } }))))
            }
        }
        val plan = SetupPlan.build(m, v.connections(), null, v.appConfigs())
        assertTrue(plan.apps.flatMap { it.items }.none { it.store == SetupPlan.VAULT_STORE })
        FleetSetup.run(plan, { true }, t)
        assertEquals(before, AccountVault.leaves(v.connections()))
        assertEquals("v1", v.connection("c1.k"))
        // and a fresh capture never files the vault into its own Configs section
        AccountMigrate.capture(v, m, { true }, t)
        assertFalse(v.appConfigs().getJSONObject("account").has(SetupPlan.VAULT_STORE))
        assertEquals(before, AccountVault.leaves(v.connections()))
    }
}
