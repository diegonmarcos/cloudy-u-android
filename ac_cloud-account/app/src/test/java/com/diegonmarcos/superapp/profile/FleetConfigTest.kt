package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.fleetconfig.FleetPolicy
import com.diegonmarcos.superapp.fleetconfig.FleetPolicyEngine

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.FleetConfig
import com.diegonmarcos.superapp.core.FleetConfigProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #783 — the fleet configuration contract, end to end on the REAL manifest (libs:core
 * assets/fleet-config.json): a fully configured fleet is exported from one profile and imported
 * into a clean one, and every app's every declared field must come out equal, typed, with nothing
 * the manifest keeps on the device ever leaving.
 *
 * The two "phones" are two prefixes over Robolectric's SharedPreferences (the engine opens a store
 * through an [FleetPolicy.Opener], the provider's real opener differs only in where files live and
 * in going through EncryptedSharedPreferences for `encrypted` stores, which needs the Keystore a
 * JVM does not have). CI has no emulator, so this is where the migration is proven.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetConfigTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val m: FleetPolicy.Manifest by lazy { FleetPolicy.manifest(ctx) }

    private fun phone(name: String) = FleetPolicy.Opener { _, file, create ->
        val p = ctx.getSharedPreferences("${name}__${file.replace('/', '_')}", Context.MODE_PRIVATE)
        if (!create && p.all.isEmpty()) null else p
    }

    private fun prefs(phone: String, file: String) = ctx.getSharedPreferences("${phone}__${file.replace('/', '_')}", Context.MODE_PRIVATE)

    private val DEVICE_KEY = "zz_device_only_install_id"

    /** Every migrating store of [app] filled with one value of every type, plus a key its manifest
     *  keeps on the device where the store narrows one, plus a whole device store. */
    private fun configure(phone: String, app: FleetPolicy.App): Int {
        var n = 0
        for (s in m.stores.values) {
            val cls = m.classOf(s, app.id)
            for (file in s.filesFor(app.pkg)) {
                val ed = prefs(phone, file).edit()
                ed.putString("s_${app.id}", "value-${app.id}-$file")
                    .putInt("i_count", 7).putLong("l_epoch", 1_700_000_000_123L).putFloat("f_ratio", 0.25f)
                    .putBoolean("b_on", true).putStringSet("ss_tags", setOf("a", "b"))
                s.keys.entries.firstOrNull { it.value == "device" && '*' !in it.key }?.let { ed.putString(it.key, "never-moves") }
                ed.commit()
                if (cls in m.migrate && s.kind in m.portable) n++
            }
        }
        return n
    }

    @Test fun `the manifest is the one baked into the APK and declares every fleet app`() {
        assertTrue(m.stores.size > 100)
        assertEquals(com.diegonmarcos.superapp.updater.Fleet.parse(com.diegonmarcos.superapp.BuildConfig.CONSTELLATION_FLEET_B64).count { it.kind == "app" }, m.apps.size)
        assertEquals(setOf("config", "secret"), m.migrate)
        assertNotNull("the SuperApp itself is a declared fleet app", m.appByPackage(ctx.packageName))
    }

    @Test fun `a configured fleet exported from one phone and imported into a clean one is equal, app by app, field by field`() {
        var migrating = 0
        for (app in m.apps.values) {
            migrating += configure("old", app)
            val exported = FleetPolicy.exportApp(m, app, phone("old"))
            val result = FleetPolicy.importApp(m, app, exported, phone("new"))
            assertFalse("${app.id}: ${result.optString("error")}", result.has("error"))
            val again = FleetPolicy.exportApp(m, app, phone("new"))
            assertEquals("${app.id}: the new phone's export differs from the old one's",
                AccountDrift.canonical(exported), AccountDrift.canonical(again))
            // Every declared migrating field arrived with its type, readable the way the app reads it.
            val stores = exported.getJSONObject("stores")
            for (file in stores.keys()) {
                val p = prefs("new", file)
                assertEquals(7, p.getInt("i_count", -1))
                assertEquals(1_700_000_000_123L, p.getLong("l_epoch", -1))
                assertEquals(0.25f, p.getFloat("f_ratio", -1f))
                assertTrue(p.getBoolean("b_on", false))
                assertEquals(setOf("a", "b"), p.getStringSet("ss_tags", null))
            }
        }
        assertTrue("the fleet declares migrating stores ($migrating)", migrating > 40)
    }

    @Test fun `nothing the manifest keeps on the device ever leaves it`() {
        for (app in m.apps.values) {
            configure("dev", app)
            val stores = FleetPolicy.exportApp(m, app, phone("dev")).getJSONObject("stores")
            for (file in stores.keys()) {
                val s = m.storeOfFile(app.pkg, file)!!
                assertTrue("$file of ${app.id} is ${m.classOf(s, app.id)}", m.classOf(s, app.id) in m.migrate)
                val values = stores.getJSONObject(file)
                for (k in values.keys()) if (k != FleetConfig.TYPES)
                    assertTrue("${app.id}/$file/$k is ${m.keyClass(s, k, app.id)}", m.keyClass(s, k, app.id) in m.migrate)
            }
            for (s in m.stores.values) if (m.classOf(s, app.id) !in m.migrate || s.kind !in m.portable)
                for (f in s.filesFor(app.pkg)) assertFalse("${app.id}: $f must not be exported", stores.has(f))
        }
    }

    @Test fun `a key a config store narrows to device stays behind, its siblings move`() {
        val app = m.apps.getValue("aa_cloud-superapp")
        prefs("k1", "profile_prefs").edit().putString("name", "Ada").putString("install_id", "dev-1").commit()
        val v = FleetPolicy.exportApp(m, app, phone("k1")).getJSONObject("stores").getJSONObject("profile_prefs")
        assertEquals("Ada", v.getString("name"))
        assertFalse(v.has("install_id"))
        // …and an import carrying it anyway refuses it.
        val body = JSONObject().put("stores", JSONObject().put("profile_prefs", JSONObject().put("name", "Eve").put("install_id", "forged")))
        val r = FleetPolicy.importApp(m, app, body, phone("k2"))
        assertEquals(JSONArray().put("install_id").toString(), r.getJSONObject("files").getJSONObject("profile_prefs").getJSONArray("refused").toString())
        assertNull(prefs("k2", "profile_prefs").getString("install_id", null))
        assertEquals("Eve", prefs("k2", "profile_prefs").getString("name", null))
    }

    @Test fun `an import refuses an undeclared store, a device store and a newer schema — never guesses`() {
        val app = m.apps.getValue("calc")
        val r = FleetPolicy.importApp(m, app, JSONObject().put("stores", JSONObject()
            .put("not_a_store", JSONObject().put("x", 1))
            .put("cloud_camera", JSONObject().put("zero_tilt", 1.0))), phone("r1"))
        assertEquals("not a declared store", r.getJSONObject("files").getJSONObject("not_a_store").getString("error"))
        assertTrue(r.getJSONObject("files").getJSONObject("cloud_camera").getString("error").contains("never migrates"))
        assertEquals(0, r.getInt("written"))
        val newer = FleetPolicy.importApp(m, app, JSONObject().put("schema_version", app.schema + 1).put("stores", JSONObject()), phone("r1"))
        assertTrue(newer.getString("error").contains("update calc first"))
    }

    @Test fun `applying the same declared copy twice changes nothing (idempotent)`() {
        val app = m.apps.getValue("nav")
        configure("i1", app)
        val body = FleetPolicy.exportApp(m, app, phone("i1"))
        val first = FleetPolicy.importApp(m, app, body, phone("i2"))
        val snap = FleetPolicy.exportApp(m, app, phone("i2"))
        val second = FleetPolicy.importApp(m, app, body, phone("i2"))
        assertEquals(first.getInt("written"), second.getInt("written"))
        assertEquals(AccountDrift.canonical(snap), AccountDrift.canonical(FleetPolicy.exportApp(m, app, phone("i2"))))
    }

    @Test fun `the provider answers export and import for its own app, and nothing else`() {
        // #825 the policy runs in Cloud-Lib-Fleetconfig on a phone; here the same engine code runs in-process.
        FleetConfigProvider.engine = { _, method, a ->
            JSONObject(when (method) {
                "plan" -> FleetPolicyEngine.plan(a[0], a[1])
                "export" -> FleetPolicyEngine.export(a[0], a[1], a[2])
                else -> FleetPolicyEngine.import(a[0], a[1], a[2], a[3])
            })
        }
        val provider = Robolectric.setupContentProvider(FleetConfigProvider::class.java, FleetConfig.authority(ctx.packageName))
        ctx.getSharedPreferences("launcher_theme_prefs", Context.MODE_PRIVATE).edit().putString("theme", "cloud_minimalist_black").commit()
        val out = provider.call(FleetConfig.METHOD_EXPORT, null, null)
        val json = JSONObject(out.getString(FleetConfig.KEY_JSON)!!)
        assertEquals("aa_cloud-superapp", json.getString("app"))
        assertEquals("cloud_minimalist_black", json.getJSONObject("stores").getJSONObject("launcher_theme_prefs").getString("theme"))
        val body = JSONObject().put("stores", JSONObject().put("launcher_theme_prefs", JSONObject().put("theme", "cloud")))
        val r = provider.call(FleetConfig.METHOD_IMPORT, null, Bundle().apply { putString(FleetConfig.KEY_JSON, body.toString()) })
        assertEquals(1, JSONObject(r.getString(FleetConfig.KEY_JSON)!!).getInt("written"))
        assertEquals("cloud", ctx.getSharedPreferences("launcher_theme_prefs", Context.MODE_PRIVATE).getString("theme", null))
        assertNotNull(provider.call("delete_everything", null, null).getString(FleetConfig.KEY_ERROR))
    }

    @Test fun `coverage names every non-portable migrating store as a gap, per app`() {
        val mail = m.coverage(m.apps.getValue("mail"))
        // A DataStore became portable (SetupStores.register hands the contract the app's own instance),
        // so mail's settings are covered now; what stays a NAMED gap is a kind the contract cannot write.
        assertTrue("mail's DataStore settings are moved", "datastore:settings" in mail.covered)
        assertFalse("mail's DataStore settings are no longer a gap", "datastore:settings" in mail.gaps)
        val room = FleetPolicy.Manifest(JSONObject()
            .put("migrate", JSONArray(listOf("config", "secret")))
            .put("stores", JSONObject()
                .put("room:notes", JSONObject().put("kind", "room").put("class", "config").put("used_by", JSONArray(listOf("ac_x"))))
                .put("x_prefs", JSONObject().put("kind", "prefs").put("class", "config").put("used_by", JSONArray(listOf("ac_x")))))
            .put("apps", JSONObject().put("x", JSONObject().put("package", "p.x").put("module", "ac_x"))))
        val gap = room.coverage(room.apps.getValue("x"))
        assertEquals("a Room store is a named gap", listOf("room:notes"), gap.gaps)
        assertEquals("a prefs store is covered", listOf("x_prefs"), gap.covered)
        assertTrue("mail's account links move", "sterna_account" in mail.covered)
        assertTrue("mail is covered at least in part", mail.percent in 1..100)
        assertTrue("a partly covered app reports a partial percent", gap.percent in 1..99)
        for (app in m.apps.values) m.coverage(app).let { c -> assertEquals(c.total, c.covered.size + c.gaps.size) }
    }
}
