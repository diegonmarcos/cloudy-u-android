package com.diegonmarcos.cloudaccount

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetconfig.FleetPolicy
import com.diegonmarcos.superapp.fleetconfig.SetupContract
import com.diegonmarcos.superapp.fleetconfig.SetupProvider
import com.diegonmarcos.superapp.fleetconfig.SetupStores
import com.diegonmarcos.superapp.profile.FleetSetup
import com.diegonmarcos.superapp.profile.SetupPlan
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * #873 Fleet Setup, end to end on a real SetupProvider under Robolectric: the plan comes from the
 * declarations (a bundle route + a declared copy's settings), the run does describe -> apply -> read
 * back per app, ✓/✗ names the failing key, a retry of one app picks up after the cause is fixed, and
 * an app that is not installed is reported, not skipped.
 */
@RunWith(RobolectricTestRunner::class)
class FleetSetupTest {
    private lateinit var ctx: Context
    private lateinit var provider: SetupProvider

    private fun manifest(pkg: String) = FleetPolicy.Manifest(JSONObject("""
      {"migrate":["config","secret"],"classes":{},"kinds":{},"resolve":{},"libs":{},
       "bundle":{"mail.password":[{"store":"mail_jmap_prefs","key":"password"}],
                 "mail.username":[{"store":"mail_jmap_prefs","key":"email"}],
                 "wg.wg0_private_key":[{"store":"wireguard_prefs","key":"if_privkey"}]},
       "apps":{"first":{"package":"$pkg","module":"aa_first","schema_version":1,"libs":["lib-mail"],"items":[]},
               "ghost":{"package":"com.example.ghost","module":"ac_ghost","schema_version":1,"libs":["lib-mail"],"items":[]},
               "plain":{"package":"com.example.plain","module":"ac_plain","schema_version":1,"libs":[],"items":[]}},
       "stores":{
         "mail_jmap_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["lib-mail"],"keys":{"password":"secret"}},
         "wireguard_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["aa_first"],"keys":{"if_privkey":"secret"}},
         "fleet_dns_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["aa_first"],"keys":{"last_lookup":"device"}}}}"""))

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SetupProvider.manifestSource = { manifest(it.packageName) }
        SetupStores.authorizer = SetupStores.defaultAuthorizer
        provider = Robolectric.buildContentProvider(SetupProvider::class.java).create(SetupContract.authority(ctx.packageName)).get()
    }

    @After fun tearDown() {
        FleetPolicy.testManifest = java.io.File("../../fleet-private-data/fleet-config.json").readText()
        SetupProvider.manifestSource = { FleetPolicy.manifestOrNull(it) }
        SetupStores.authorizer = SetupStores.defaultAuthorizer
    }

    private val transport = FleetSetup.Transport { pkg, method, store, body ->
        if (pkg != ctx.packageName) SetupContract.Reply.Unreachable("no provider")
        else {
            val b = provider.call(method, store, Bundle().apply { body?.let { putString(SetupContract.KEY_JSON, it.toString()) } })
            b.getString(SetupContract.KEY_ERROR)?.let { SetupContract.Reply.Refused(it) } ?: SetupContract.Reply.Ok(JSONObject(b.getString(SetupContract.KEY_JSON)!!))
        }
    }

    private val configs = JSONObject("""{"mail":{"username":"me@example.org","password":"hunter2","imap_url":"imaps://x"},
        "wg":{"wg0_private_key":"AAAA"},"ssh":{"vault_repo_key":"k"}}""")
    private val declared = JSONObject("""{"settings":{"first":{"_schema":1,"fleet_dns_prefs":{"preset":"quad9","volume":3,"last_lookup":"x","_types":{"volume":"i"}}}}}""")

    private fun plan() = SetupPlan.build(manifest(ctx.packageName), configs, declared)
    private fun prefs(n: String) = ctx.getSharedPreferences(n, Context.MODE_PRIVATE)

    @Test fun planIsDerivedFromDeclarationsNotAList() {
        val p = plan()
        assertEquals(listOf("first", "ghost"), p.apps.map { it.app.id })            // plain declares none of the stores
        val first = p.of("first")!!.items.map { it.store + "." + it.key }.toSet()
        assertEquals(setOf("mail_jmap_prefs.password", "mail_jmap_prefs.email", "wireguard_prefs.if_privkey", "fleet_dns_prefs.preset", "fleet_dns_prefs.volume"), first)
        assertEquals(2, p.of("ghost")!!.items.size)                                  // every owner of the store gets it
        val why = p.unmapped.associate { it.source to it.why }
        assertTrue(why["settings.first.fleet_dns_prefs.last_lookup"]!!.contains("never migrates"))   // a device key stays home
        assertEquals("no declared store takes it", why["ssh.vault_repo_key"])
        assertEquals("no declared store takes it", why["mail.imap_url"])
    }

    @Test fun setupPushesAndReadsBackPerApp() {
        val out = FleetSetup.run(plan(), { it == ctx.packageName }, transport)
        val first = out.first { it.id == "first" }
        assertEquals(first.line(), FleetSetup.State.DONE, first.state)
        assertEquals("hunter2", prefs("mail_jmap_prefs").getString("password", null))
        assertEquals("quad9", prefs("fleet_dns_prefs").getString("preset", null))
        assertEquals(3, prefs("fleet_dns_prefs").getInt("volume", -1))              // typed Int via _types
        assertFalse(prefs("fleet_dns_prefs").contains("last_lookup"))
        assertEquals(FleetSetup.State.NOT_INSTALLED, out.first { it.id == "ghost" }.state)
        assertEquals("✓ first: 5 key(s)", first.line())
        // idempotent: the same run changes nothing and is still green
        assertTrue(FleetSetup.run(plan(), { it == ctx.packageName }, transport, setOf("first")).single().ok)
    }

    @Test fun aDeniedKeyIsNamedAndARetryRecovers() {
        SetupStores.authorizer = SetupStores.Authorizer { _, _, _, _, k, _ -> k != "password" }
        val bad = FleetSetup.run(plan(), { it == ctx.packageName }, transport, setOf("first")).single()
        assertEquals(FleetSetup.State.FAILED, bad.state)
        assertEquals("✗ first: mail_jmap_prefs.password: no grant for ${ctx.packageName}", bad.line())
        assertFalse(prefs("mail_jmap_prefs").contains("password"))                   // atomic per store: the email did not land either
        assertFalse(prefs("mail_jmap_prefs").contains("email"))
        assertEquals("quad9", prefs("fleet_dns_prefs").getString("preset", null))   // another store of the same app still went through
        SetupStores.authorizer = SetupStores.defaultAuthorizer
        assertTrue(FleetSetup.run(plan(), { it == ctx.packageName }, transport, setOf("first")).single().ok)
        assertEquals("hunter2", prefs("mail_jmap_prefs").getString("password", null))
    }

    @Test fun anAppWithoutTheEndpointSaysSo() {
        val o = FleetSetup.run(plan(), { true }, transport, setOf("ghost")).single()
        assertEquals(FleetSetup.State.NO_CONTRACT, o.state)
        assertTrue(o.line(), o.line().startsWith("✗ ghost: no setup endpoint"))
    }

    @Test fun aValueTheAppReadsBackDifferentlyFails() {
        val lying = FleetSetup.Transport { pkg, m, s, b ->
            val r = transport.call(pkg, m, s, b)
            if (m == SetupContract.METHOD_EXPORT && r is SetupContract.Reply.Ok) {
                val f = r.json.optJSONObject("stores")?.optJSONObject("mail_jmap_prefs")?.optJSONObject("mail_jmap_prefs"); f?.put("email", "someone@else")
            }
            r
        }
        val o = FleetSetup.run(plan(), { it == ctx.packageName }, lying, setOf("first")).single()
        assertEquals("✗ first: mail_jmap_prefs.email: applied, but the app reads back a different value", o.line())
    }

    /** Runbook `configs` = the setup op's plan (vault bundle Configs) merged with the device file; the device file wins per key. */
    @Test fun theRunbookPlanIsTheVaultBundleMergedWithTheDeviceFile() {
        val m = manifest(ctx.packageName)
        val bundle = JSONObject("""{"first":{"fleet_dns_prefs":{"fleet_dns_prefs":{"preset":"quad9","volume":3}}}}""")
        val device = JSONObject("""{"first":{"fleet_dns_prefs":{"fleet_dns_prefs":{"preset":"cloudflare"}}}}""")
        val fleet = SetupPlan.build(m, configs, null, bundle)
        val merged = SetupPlan.merge(fleet, SetupPlan.build(m, null, null, device))
        val first = merged.of("first")!!.items.associate { it.store + "." + it.key to it.value }
        assertEquals("cloudflare", first["fleet_dns_prefs.preset"])                 // device file wins
        assertEquals(3, first["fleet_dns_prefs.volume"])                            // vault-bundle key kept
        assertEquals("hunter2", first["mail_jmap_prefs.password"])                  // Connections routes kept
        assertEquals(fleet.itemCount, merged.itemCount)                              // an override, not an extra key
        assertTrue(SetupPlan.merge(SetupPlan.build(m, null, null, JSONObject()), fleet).itemCount > 0)   // an empty working file is not "nothing to push"
    }
}
