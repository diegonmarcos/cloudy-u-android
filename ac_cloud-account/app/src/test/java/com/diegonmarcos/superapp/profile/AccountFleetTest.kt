package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.fleetconfig.FleetPolicy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.FleetConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #783 — the fleet in Account: the path layout R/S/L share (`settings › app › file › key`), the
 * grouped server → runtime bodies, the secret mask, the drift's app ownership and the new-phone
 * plan (install-then-apply / apply / done / nothing), on the real manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountFleetTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val m: FleetPolicy.Manifest by lazy { FleetPolicy.manifest(ctx) }
    private val S = AccountDrift.SEP

    private val export = JSONObject().put("schema_version", 1).put("stores", JSONObject()
        .put("cloud_calc_jev", JSONObject().put("model_route", "openai/gpt").put("limit", 5)
            .put(FleetConfig.TYPES, JSONObject().put("limit", "l"))))

    @Test fun `an export is filed at settings › app › file › key, its types beside it, its schema on record`() {
        val v = AccountFleet.flatten("calc", export)
        assertEquals("openai/gpt", v["settings${S}calc${S}cloud_calc_jev${S}model_route"])
        assertEquals("l", v["settings${S}calc${S}cloud_calc_jev${S}_types${S}limit"])
        assertEquals(1, v["settings${S}calc${S}_schema"])
    }

    @Test fun `a declared subtree is the wire format again, and the round trip is exact`() {
        val body = JSONObject()
        AccountFleet.flatten("calc", export).forEach { (p, x) -> AccountDrift.put(body, p, x!!) }
        val back = AccountFleet.body(body.getJSONObject("settings").getJSONObject("calc"))
        assertEquals(AccountDrift.canonical(export), AccountDrift.canonical(back))
    }

    @Test fun `server → runtime groups one body per app and carries each value's declared type`() {
        val server = JSONObject().put("settings", JSONObject().put("calc", JSONObject().put("_schema", 1)
            .put("cloud_calc_jev", JSONObject().put("limit", 9).put(FleetConfig.TYPES, JSONObject().put("limit", "l")))))
        val b = AccountFleet.bodies(listOf(
            "settings${S}calc${S}cloud_calc_jev${S}limit" to 9,
            "settings${S}calc${S}cloud_calc_jev${S}model_route" to "x",
            "settings${S}nav${S}cloud_nav_cockpit${S}mode" to "drive",
            "mail${S}accounts${S}yo${S}name" to "not settings"), server)
        assertEquals(setOf("calc", "nav"), b.keys)
        val jev = b.getValue("calc").getJSONObject("stores").getJSONObject("cloud_calc_jev")
        assertEquals("l", jev.getJSONObject(FleetConfig.TYPES).getString("limit"))
        assertEquals("x", jev.getString("model_route"))
    }

    @Test fun `a value the manifest classes secret is masked, whatever its key is called`() {
        assertTrue(AccountFleet.isSecret(m, "settings${S}calc${S}cloud_calc_jev_secret${S}openrouter_token"))
        assertTrue("a narrowed key", AccountFleet.isSecret(m, "settings${S}aa_cloud-superapp${S}wireguard_prefs${S}if_privkey"))
        assertFalse(AccountFleet.isSecret(m, "settings${S}aa_cloud-superapp${S}wireguard_prefs${S}tunnel_name"))
        assertTrue("an app this build does not know fails closed", AccountFleet.isSecret(m, "settings${S}future-app${S}x${S}y"))
    }

    @Test fun `every fleet app owns its settings, and an id the cockpit also has is one app`() {
        val cockpit = listOf(AccountDrift.App("mail", "Mail", listOf("mail")), AccountDrift.App("about", "About", listOf("about")))
        val apps = AccountFleet.driftApps(cockpit, m, emptyMap())
        assertEquals(1, apps.count { it.id == "mail" })
        assertEquals(listOf("mail", "settings${S}mail"), apps.first { it.id == "mail" }.sections)
        assertEquals(m.apps.size + 1, apps.size)   // 30 fleet apps + about
        assertEquals("mail", AccountDrift.ownerOf("settings${S}mail${S}text_tools${S}provider", apps))
        assertEquals("mail", AccountDrift.ownerOf("mail${S}accounts${S}yo", apps))
        assertEquals("calc", AccountDrift.ownerOf("settings${S}calc${S}cloud_clock${S}data", apps))
        assertEquals("", AccountDrift.ownerOf("settings${S}calculator${S}x", apps))
    }

    @Test fun `the new-phone plan installs what is missing, applies what is there, skips what landed, resumes`() {
        val declared = JSONObject().put("settings", JSONObject()
            .put("calc", JSONObject().put("_schema", 1).put("cloud_calc_jev", JSONObject().put("model_route", "a")))
            .put("nav", JSONObject().put("_schema", 1))
            .put("wallet", JSONObject().put("pending", true).put("source", "phone-export").put("reason", "no values yet"))
            .put("aa_cloud-superapp", JSONObject().put("_schema", 1).put("launcher_theme_prefs", JSONObject().put("theme", "cloud"))))
        val installed = setOf("com.diegonmarcos.superapp", "com.diegonmarcos.cloudnav")
        val journal = HashMap<String, String>()
        fun plan() = AccountFleet.plan(m, declared, emptyMap(), { it in installed }, { journal[it] }).associateBy { it.id }
        val p = plan()
        assertEquals(AccountFleet.INSTALL_APPLY, p.getValue("calc").action)
        assertEquals(AccountFleet.APPLY, p.getValue("nav").action)
        assertEquals(AccountFleet.APPLY, p.getValue("aa_cloud-superapp").action)
        assertEquals(AccountFleet.NOTHING, p.getValue("mail").action)
        assertEquals("a pending app is never installed on a guess", AccountFleet.NOTHING, p.getValue("wallet").action)
        assertEquals("the SuperApp is planned first", "aa_cloud-superapp", m.apps.keys.first())
        // The superapp applied: a re-run skips it while the declared copy is unchanged …
        journal["aa_cloud-superapp"] = p.getValue("aa_cloud-superapp").sha + "|✓ aa_cloud-superapp: 1 written"
        assertEquals(AccountFleet.DONE, plan().getValue("aa_cloud-superapp").action)
        // … and applies again once it changes; a failed step is retried.
        declared.getJSONObject("settings").getJSONObject("aa_cloud-superapp").getJSONObject("launcher_theme_prefs").put("theme", "black")
        assertEquals(AccountFleet.APPLY, plan().getValue("aa_cloud-superapp").action)
        journal["nav"] = p.getValue("nav").sha + "|✗ nav: unreachable"
        assertEquals(AccountFleet.APPLY, plan().getValue("nav").action)
        assertEquals(JSONArray::class, AccountFleet.planJson(plan().values.toList())::class)
    }

    @Test fun `790 the terminals' agent credentials are derived from the declared ai tokens, as fleet settings`() {
        val a = VaultCockpit.layout.agentAuth ?: error("cockpit agent_auth is not declared")
        assertEquals(listOf("termux", "nix-on-droid"), a.apps)
        // The store is a declared, migrating, secret store of both terminals — so FleetConfig imports it and Drift masks it.
        for (id in a.apps) {
            val app = m.apps.getValue(id)
            val store = m.storeOfFile(app.pkg, a.store) ?: error("${a.store} is not a store of $id")
            for (k in a.env.keys) {
                assertTrue("$id › $k migrates", m.migratesKey(store, k, id))
                assertTrue("$id › $k is masked", AccountFleet.isSecret(m, "settings$S$id$S${a.store}$S$k"))
            }
        }
        val server = JSONObject()
            .put("ai", JSONObject().put("tokens", JSONObject()
                .put("claude", JSONObject().put("plan", "subscription").put("token", "tok-claude"))
                .put("openrouter_hermes_agent", " tok-router ")))
            .put("settings", JSONObject().put("termux", JSONObject().put("pending", true).put("reason", "not yet")))
        val before = server.toString()
        val d = AccountFleet.derive(server, a) { 1 }!!
        assertEquals("the server body is never mutated", before, server.toString())
        for (id in a.apps) {
            val sub = d.getJSONObject("settings").getJSONObject(id)
            assertFalse("a pending subtree becomes a declared one", sub.optBoolean("pending"))
            assertEquals(1, sub.getInt(AccountFleet.SCHEMA))
            val store = sub.getJSONObject(a.store)
            assertEquals("tok-claude", store.getString("CLAUDE_CODE_OAUTH_TOKEN"))
            assertEquals("tok-router", store.getString("OPENROUTER_API_KEY"))
        }
        // The new-phone migration applies it: both terminals plan an import of exactly that store.
        val p = AccountFleet.plan(m, d, emptyMap(), { true }, { null }).associateBy { it.id }
        for (id in a.apps) {
            assertEquals(AccountFleet.APPLY, p.getValue(id).action)
            val body = AccountFleet.body(d.getJSONObject("settings").getJSONObject(id))
            assertEquals(listOf(a.store), body.getJSONObject("stores").keys().asSequence().toList())
        }
        // Controls: a key the profile declares itself wins; a null token and no tokens add nothing.
        server.getJSONObject("settings").put("termux", JSONObject().put(a.store, JSONObject().put("OPENROUTER_API_KEY", "own")))
        val own = AccountFleet.derive(server, a) { 1 }!!.getJSONObject("settings").getJSONObject("termux").getJSONObject(a.store)
        assertEquals("own", own.getString("OPENROUTER_API_KEY"))
        assertEquals("tok-claude", own.getString("CLAUDE_CODE_OAUTH_TOKEN"))
        val subscription = JSONObject().put("ai", JSONObject().put("tokens", JSONObject()
            .put("claude", JSONObject().put("plan", "subscription").put("token", JSONObject.NULL))))
        assertTrue("nothing to derive returns the body itself", AccountFleet.derive(subscription, a) { 1 } === subscription)
        assertFalse(subscription.has("settings"))
    }

    @Test fun `802 the browser's autofill profile is derived from about, as JSON, into its secret store`() {
        val a = VaultCockpit.layout.derivedSettings.firstOrNull { it.store == "browser_autofill" }
            ?: error("cockpit derived_settings declares no browser_autofill block")
        assertEquals(listOf("browser"), a.apps)
        val app = m.apps.getValue("browser")
        val store = m.storeOfFile(app.pkg, a.store) ?: error("${a.store} is not a store of browser")
        for (k in a.env.keys) {
            assertTrue("browser › $k migrates", m.migratesKey(store, k, "browser"))
            assertTrue("browser › $k is masked", AccountFleet.isSecret(m, "settings${S}browser$S${a.store}$S$k"))
        }
        val addresses = JSONArray().put(JSONObject().put("first_name", "Ada").put("city", "Berlin"))
        val server = JSONObject().put("about", JSONObject()
            .put("profile", JSONObject().put("name", "Ada L").put("email", "ada@example.test"))
            .put("addresses", addresses))
        val d = VaultCockpit.layout.derivations.fold(server as JSONObject?) { b, x -> AccountFleet.derive(b, x) { 1 } }!!
        val st = d.getJSONObject("settings").getJSONObject("browser").getJSONObject(a.store)
        // An object or a list travels as its JSON text, which the browser parses back.
        assertEquals("Ada L", JSONObject(st.getString("vault_profile")).getString("name"))
        assertEquals("Berlin", JSONArray(st.getString("vault_addresses")).getJSONObject(0).getString("city"))
        // An empty subtree adds nothing.
        val none = AccountFleet.derive(JSONObject().put("about", JSONObject().put("addresses", JSONArray())), a) { 1 }!!
        assertFalse(none.has("settings"))
    }

    @Test fun `mail the vault's mail section travels as JSON into cloud-mail's secret keys, and a pending mail becomes declared`() {
        val a = VaultCockpit.layout.derivedSettings.firstOrNull { it.store == "sterna_account" }
            ?: error("cockpit derived_settings declares no sterna_account block")
        assertEquals(listOf("mail"), a.apps)
        assertEquals(listOf("mail"), a.env["vault_mail"])
        assertEquals(listOf("about", "profile", "email"), a.env["vault_owner"])
        val app = m.apps.getValue("mail")
        val store = m.storeOfFile(app.pkg, a.store) ?: error("${a.store} is not a store of mail")
        for (k in a.env.keys) {
            assertTrue("mail › $k migrates", m.migratesKey(store, k, "mail"))
            assertTrue("mail › $k is masked", AccountFleet.isSecret(m, "settings${S}mail$S${a.store}$S$k"))
        }
        assertFalse("pw_<id> slots never migrate", m.migratesKey(store, "pw_x", "mail"))
        val mail = JSONObject()
            .put("accounts", JSONObject().put("yo", JSONObject().put("name", "yo").put("pass_env", "YO_PASSWORD")))
            .put("passwords", JSONObject().put("YO_PASSWORD", "s3cret"))
            .put("endpoints", JSONObject().put("domain", "jmap.example.test"))
        val server = JSONObject().put("mail", mail)
            .put("about", JSONObject().put("profile", JSONObject().put("email", "me@example.test")))
            .put("settings", JSONObject().put("mail", JSONObject().put("pending", true)))
        val d = VaultCockpit.layout.derivations.fold(server as JSONObject?) { b, x -> AccountFleet.derive(b, x) { 7 } }!!
        val sub = d.getJSONObject("settings").getJSONObject("mail")
        // The pending subtree became a declared one, at the app's schema, holding the section's text.
        assertFalse(sub.has("pending"))
        assertEquals(7, sub.getInt(AccountFleet.SCHEMA))
        val st = sub.getJSONObject(a.store)
        assertEquals(mail.toString(), st.getString("vault_mail"))
        assertEquals("me@example.test", st.getString("vault_owner"))
        assertEquals("jmap.example.test", JSONObject(st.getString("vault_mail")).getJSONObject("endpoints").getString("domain"))
    }
}
