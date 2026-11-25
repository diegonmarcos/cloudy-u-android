package com.diegonmarcos.superapp.fleetconfig

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * #873 the setup contract end to end, on a real provider under Robolectric: describe -> apply ->
 * export round-trips a sample store with its types, a refused key writes nothing (atomic), a
 * secret key needs the authorizer's grant, and a store with no handler is declared unserved.
 */
@RunWith(RobolectricTestRunner::class)
class SetupProviderTest {
    private lateinit var ctx: Context
    private lateinit var p: SetupProvider

    private fun manifest(pkg: String) = FleetPolicy.Manifest(JSONObject("""
      {"migrate":["config","secret"],"classes":{},"kinds":{},"resolve":{},"libs":{},
       "apps":{"sample":{"package":"$pkg","module":"ac_sample","schema_version":1,"libs":["lib-core"],"items":[]}},
       "stores":{
         "sample_prefs":{"kind":"prefs","class":"config","doc":"d","used_by":["ac_sample"],
                         "keys":{"token":"secret","install_id":"device"}},
         "sample_ds":{"kind":"datastore","class":"config","doc":"d","used_by":["ac_sample"]},
         "sample_cache":{"kind":"prefs","class":"device","doc":"d","used_by":["ac_sample"]},
         "other_app":{"kind":"prefs","class":"config","doc":"d","used_by":["ac_other"]}}}"""))

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SetupProvider.manifestSource = { manifest(it.packageName) }
        SetupStores.authorizer = SetupStores.defaultAuthorizer
        SetupStores.unregister("sample_ds")
        p = Robolectric.buildContentProvider(SetupProvider::class.java).create(SetupContract.authority(ctx.packageName)).get()
    }

    @After fun tearDown() {
        FleetPolicy.testManifest = java.io.File("../../../fleet-private-data/fleet-config.json").readText()
        SetupProvider.manifestSource = { FleetPolicy.manifestOrNull(it) }
        SetupStores.authorizer = SetupStores.defaultAuthorizer
        SetupStores.unregister("sample_ds")
    }

    private fun call(m: String, store: String? = null, body: JSONObject? = null): JSONObject {
        val extras = Bundle().apply { body?.let { putString(SetupContract.KEY_JSON, it.toString()) } }
        val b = p.call(m, store, extras)
        assertNull(b.getString(SetupContract.KEY_ERROR), b.getString(SetupContract.KEY_ERROR))
        return JSONObject(b.getString(SetupContract.KEY_JSON)!!)
    }

    private fun apply(values: JSONObject, remove: List<String> = emptyList()) =
        call(SetupContract.METHOD_APPLY, "sample_prefs", JSONObject().put("values", values).put("remove", JSONArray(remove)))

    private fun prefs() = ctx.getSharedPreferences("sample_prefs", Context.MODE_PRIVATE)

    @Test fun describeListsOnlyThisAppsMigratingStores() {
        val d = call(SetupContract.METHOD_DESCRIBE)
        assertTrue(d.getBoolean("declared")); assertEquals("sample", d.getString("app"))
        val names = (0 until d.getJSONArray("stores").length()).map { d.getJSONArray("stores").getJSONObject(it).getString("name") }
        assertEquals(listOf("sample_ds", "sample_prefs"), names)          // device + other app's stores absent
        val prefsEntry = d.getJSONArray("stores").getJSONObject(1)
        assertTrue(prefsEntry.getBoolean("served"))
        assertEquals("secret", prefsEntry.getJSONObject("keys").getString("token"))
    }

    @Test fun applyThenExportRoundTripsWithTypes() {
        val r = apply(JSONObject().put("theme", "dark").put("volume", 7).put("on", true)
            .put("tags", JSONArray(listOf("a", "b"))).put("_types", JSONObject().put("volume", "i")))
        assertTrue(r.toString(), r.getBoolean("ok")); assertEquals(4, r.getInt("written"))
        assertEquals(7, prefs().getInt("volume", -1))                      // Int, not Long
        val e = call(SetupContract.METHOD_EXPORT).getJSONObject("stores").getJSONObject("sample_prefs").getJSONObject("sample_prefs")
        assertEquals("dark", e.getString("theme")); assertEquals(7, e.getInt("volume"))
        assertEquals("i", e.getJSONObject("_types").getString("volume")); assertEquals(2, e.getJSONArray("tags").length())
    }

    @Test fun aRefusedKeyWritesNothing() {
        prefs().edit().putString("theme", "light").commit()
        val r = apply(JSONObject().put("theme", "dark").put("install_id", "x"))   // install_id is a device key
        assertFalse(r.getBoolean("ok")); assertEquals(0, r.getInt("written"))
        assertFalse(r.getJSONObject("keys").getJSONObject("install_id").getBoolean("ok"))
        assertTrue(r.getJSONObject("keys").getJSONObject("theme").getBoolean("ok"))   // valid, but not applied
        assertEquals("light", prefs().getString("theme", null))
        assertNull(prefs().getString("install_id", null))
    }

    @Test fun aTypeMismatchIsRefused() {
        prefs().edit().putBoolean("on", false).commit()
        val r = apply(JSONObject().put("on", "yes"))
        assertFalse(r.getBoolean("ok")); assertEquals("type mismatch", r.getJSONObject("keys").getJSONObject("on").getString("why"))
        assertFalse(prefs().getBoolean("on", true))
    }

    @Test fun aSecretKeyNeedsTheAuthorizersGrant() {
        SetupStores.authorizer = SetupStores.Authorizer { _, _, _, _, k, _ -> k != "token" }
        val r = apply(JSONObject().put("token", "s3cret").put("theme", "dark"))
        assertFalse(r.getBoolean("ok")); assertEquals("no grant for ${ctx.packageName}", r.getJSONObject("keys").getJSONObject("token").getString("why"))
        assertNull(prefs().getString("token", null))
        SetupStores.authorizer = SetupStores.defaultAuthorizer             // the caller is this app: trusted for its own secrets
        assertTrue(apply(JSONObject().put("token", "s3cret").put("theme", "dark")).getBoolean("ok"))
        SetupStores.authorizer = SetupStores.Authorizer { _, _, op, _, k, _ -> !(op == SetupStores.OP_EXPORT && k == "token") }
        val e = call(SetupContract.METHOD_EXPORT).getJSONObject("stores").getJSONObject("sample_prefs").getJSONObject("sample_prefs")
        assertFalse(e.has("token"))                                        // a denied key never leaves
    }

    @Test fun anUndeclaredOrDeviceStoreIsRefused() {
        for (s in listOf("sample_cache", "other_app", "nope")) {
            val r = call(SetupContract.METHOD_APPLY, s, JSONObject().put("values", JSONObject().put("k", "v")))
            assertFalse(s, r.getBoolean("ok")); assertNotNull(r.optString("why"))
        }
    }

    @Test fun aStoreWithNoHandlerIsUnservedUntilOneIsRegistered() {
        fun ds() = call(SetupContract.METHOD_DESCRIBE).getJSONArray("stores").getJSONObject(0)
        assertFalse(ds().getBoolean("served"))
        assertFalse(call(SetupContract.METHOD_APPLY, "sample_ds", JSONObject().put("values", JSONObject().put("k", 1))).getBoolean("ok"))
        val mem = HashMap<String, Any?>()
        SetupStores.register("sample_ds", object : SetupStores.SetupHandler {
            override fun read(ctx: Context, file: String): Map<String, Any?> = mem
            override fun write(ctx: Context, file: String, set: Map<String, Any?>, remove: Set<String>): Boolean { mem.putAll(set); remove.forEach(mem::remove); return true }
        })
        assertTrue(ds().getBoolean("served"))
        assertTrue(call(SetupContract.METHOD_APPLY, "sample_ds", JSONObject().put("values", JSONObject().put("k", 1))).getBoolean("ok"))
        assertEquals(1, mem["k"])
    }

    @Test fun statusRecordsTheLastApply() {
        assertTrue(call(SetupContract.METHOD_STATUS).isNull("last_apply"))
        apply(JSONObject().put("theme", "dark"))
        val s = call(SetupContract.METHOD_STATUS)
        assertEquals("sample_prefs", s.getJSONObject("last_apply").getString("store")); assertTrue(s.getJSONObject("last_apply").getBoolean("ok"))
        assertEquals(2, s.getInt("stores")); assertEquals(1, s.getInt("served"))
    }

    @Test fun anUnknownMethodIsRefused() {
        assertNotNull(p.call("delete_everything", null, null).getString(SetupContract.KEY_ERROR))
    }
}
