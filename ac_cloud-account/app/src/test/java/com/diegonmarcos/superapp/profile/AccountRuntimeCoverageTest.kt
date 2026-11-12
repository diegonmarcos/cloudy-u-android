package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.fleetconfig.FleetPolicy

import android.app.Application
import com.diegonmarcos.superapp.profile.AccountRuntime.AppRead
import com.diegonmarcos.superapp.profile.AccountRuntime.Status
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #781 — Runtime reads FULL configs: every Profiles field is mapped to the app(s) that hold it
 * (cockpit `vault_fields`), each app's declared / reported / missing / not-read is counted from that
 * map, the keyboard reports its autocomplete lists in the vault's own shape, and a phone with no
 * device picked on Connect still reads its own mesh profiles (the device the live tunnel carries).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountRuntimeCoverageTest {

    private val s = AccountDrift.SEP
    private val layout = VaultCockpit.layout

    @Test fun `the field map is exactly the Profiles schema - one declaration, both tabs`() {
        val schema = InfoMask.schema.flatMap { sec -> sec.fields.map { "${sec.id}$s$it" } }.toSet()
        assertTrue("the baked schema is readable", schema.isNotEmpty())
        assertEquals(schema, layout.vaultFields.keys)
        val ids = layout.sections.map { it.id }.toSet()
        for ((path, f) in layout.vaultFields) {
            assertTrue("$path names an undeclared app: ${f.apps}", ids.containsAll(f.apps))
            if (f.apps.isEmpty() || !f.held) assertTrue("$path is held by no app and says no why", f.why.isNotBlank())
            if (f.held) for (app in f.apps)
                assertTrue("$path is held by $app, whose vault sections do not include it",
                    path.substringBefore(s) in layout.sections.first { it.id == app }.vault)
        }
    }

    @Test fun `the keyboard is read over text tools, cloud-drive through its FleetConfig export`() {
        val kb = layout.sections.first { it.id == "keyboard" }
        assertEquals(VaultCockpit.TEXT_TOOLS, kb.runtime.servedBy)
        assertTrue(kb.runtime.reports)
        assertTrue("every held autocomplete list has a tab file",
            layout.vaultFields.filter { (_, f) -> "keyboard" in f.apps && f.held }.keys
                .filterNot { it.endsWith("${s}manifest") }.all { it.substringAfter(s) in kb.runtime.lists })
        val drive = layout.sections.first { it.id == "cloud-drive" }
        assertTrue("#789 cloud-drive reports now", drive.runtime.reports)
        assertEquals("git-sync-credentials", drive.runtime.store)
        assertTrue("the store it reads is a declared fleet-config secret store",
            FleetPolicy.manifest(androidx.test.core.app.ApplicationProvider
                .getApplicationContext()).stores[drive.runtime.store]?.cls == "secret")
    }

    @Test fun `789 cloud-drive's token - presence and fingerprint in the detail, never the value`() {
        val token = "token-" + "x".repeat(36)
        fun export(vararg kv: Pair<String, String>) = JSONObject().put("stores", JSONObject()
            .put("git-sync-credentials", JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }))
        val (detail, value) = AccountRuntime.heldSecret(export("repo-a" to token, "repo-b" to token), "git-sync-credentials")
        assertFalse("the token never reaches the detail line: $detail", token in detail)
        assertTrue(detail, detail.contains("2 repo(s)") && detail.contains(AccountRuntime.fingerprint(token)))
        assertEquals("one agreed copy is what Drift compares (the screen masks it)", token, value)
        // Controls: two different copies are named, not merged; nothing held is said plainly.
        val (two, none) = AccountRuntime.heldSecret(export("repo-a" to token, "repo-b" to "other"), "git-sync-credentials")
        assertTrue(two, two.contains(AccountRuntime.fingerprint("other")))
        assertNull(none)
        assertEquals("no token held" to null, AccountRuntime.heldSecret(JSONObject(), "git-sync-credentials"))
        assertTrue(AccountRuntime.fingerprint(token).length == 12 && AccountRuntime.fingerprint(token) != AccountRuntime.fingerprint("other"))
    }

    @Test fun `coverage - an unread app misses all it holds, a read one what it holds empty`() {
        val vf = mapOf(
            "x${s}a" to VaultCockpit.VaultField(listOf("app"), true),
            "x${s}b" to VaultCockpit.VaultField(listOf("app"), true),
            "x${s}c" to VaultCockpit.VaultField(listOf("app"), false, "input"),
            "y${s}d" to VaultCockpit.VaultField(listOf("other"), true),
        )
        val down = AccountRuntime.coverage(AppRead("app", "App", Status.NOT_REPORTING, "", emptyMap()), vf)
        assertEquals(listOf("x${s}a", "x${s}b"), down.fields)
        assertEquals(down.fields, down.missing)
        val up = AccountRuntime.coverage(AppRead("app", "App", Status.REACHABLE, "",
            mapOf("x${s}a${s}k" to "v", "x${s}a${s}empty" to null)), vf)
        assertEquals(listOf("x${s}a", "x${s}b"), up.fields)
        assertEquals(listOf("x${s}a${s}empty"), up.missing)
        assertEquals(listOf("x${s}b"), up.unread)
        assertEquals(1, up.reported)
        val (_, apps) = AccountRuntime.snapshot(listOf(up))
        val c = apps.getJSONObject("app").getJSONObject("counts")
        assertEquals(listOf(2, 2, 1, 1, 1), listOf("declared", "observed", "reported", "missing", "unread").map { c.getInt(it) })
    }

    @Test fun `the keyboard's export lands at the vault's autocomplete paths and equals the vault's copy`() {
        val items = JSONArray().put(JSONObject().put("timeStamp", 1).put("text", "a").put("mimeTypes", JSONArray()))
        val tabs = JSONArray().put(JSONObject().put("listName", JSONObject.NULL).put("file", "default.json").put("count", 1))
            .put(JSONObject().put("listName", "Personal Data").put("file", "Personal_Data.json").put("count", 1))
        val export = JSONObject().put("version", 1).put("tabs", tabs)
            .put("files", JSONObject().put("default.json", items).put("Personal_Data.json", items))
        val r = AccountRuntime.keyboardValues(export, mapOf("default" to "default.json", "personal_data" to "Personal_Data.json", "agents" to "Agents.json"))
        assertNull("a list the keyboard does not have is observed empty", r["autocomplete${s}agents"])
        assertFalse("the export's moment is not config", r.keys.any { it.endsWith("exportedAt") })
        // The vault holds the same export (manifest with its exportedAt, and each list): only the list the keyboard lacks drifts.
        val vault = JSONObject().put("autocomplete", JSONObject()
            .put("manifest", JSONObject().put("version", 1).put("exportedAt", 99).put("tabs", JSONArray(tabs.toString())))
            .put("default", JSONArray(items.toString())).put("personal_data", JSONArray(items.toString()))
            .put("agents", JSONArray(items.toString())))
        val rBody = JSONObject().also { b -> r.forEach { (p, v) -> if (v != null) AccountDrift.put(b, p, v) } }
        val fields = AccountDrift.diff(AccountDrift.leaves(vault), AccountDrift.leaves(rBody), emptyList(), r.keys)
        assertEquals(listOf("autocomplete${s}agents"), fields.filter { it.kind != AccountDrift.Kind.SAME }.map { it.path })
    }

    @Test fun `no device picked - the live tunnel's address names the device`() {
        val a37 = VaultCockpit.Device("samsung-a37", "A37", "10.0.0.37", "fd00::37")
        val galaxy = VaultCockpit.Device("galaxy", "Galaxy", "10.0.0.21", "")
        val all = listOf(a37, galaxy)
        assertEquals(galaxy to false, AccountRuntime.deviceFor(all, "galaxy", "10.0.0.37/32"))
        assertEquals(a37 to true, AccountRuntime.deviceFor(all, "", "10.0.0.37/32, fd00::37/128"))
        assertEquals(a37 to true, AccountRuntime.deviceFor(all, "gone", "fd00::37/128"))
        assertNull(AccountRuntime.deviceFor(all, "", "10.9.9.9/32"))
        assertNull(AccountRuntime.deviceFor(all, "", ""))
    }

    @Test fun `mail - the JMAP host is what endpoints domain is compared with`() {
        assertEquals("mail.example.com", AccountRuntime.hostOf("https://mail.example.com/.well-known/jmap"))
        assertNull(AccountRuntime.hostOf(""))
        assertNull(AccountRuntime.hostOf("not a url"))
    }

    // ── #810 justified, not unread ───────────────────────────────────────

    @Test fun `810 a held field with an unread_why is justified, one without stays unread`() {
        val vf = mapOf(
            "peers${s}a${s}profiles" to VaultCockpit.VaultField(listOf("mesh"), true, unreadWhy = "another peer's"),
            "mesh${s}profiles" to VaultCockpit.VaultField(listOf("mesh"), true),
        )
        val r = AccountRuntime.coverage(AppRead("mesh", "Mesh", Status.REACHABLE, "", emptyMap()), vf)
        assertEquals(listOf("mesh${s}profiles"), r.unread)
        assertEquals(mapOf("peers${s}a${s}profiles" to "another peer's"), r.justified)
        val snap = AccountRuntime.snapshot(listOf(r)).second.getJSONObject("mesh")
        assertEquals(1, snap.getJSONObject("counts").getInt("justified"))
        assertEquals(1, snap.getJSONObject("counts").getInt("unread"))
        assertEquals("another peer's", snap.getJSONObject("justified").getString("peers${s}a${s}profiles"))
    }

    @Test fun `810 the baked map - every peer's profiles carry a reason`() {
        val peers = layout.vaultFields.filterKeys { it.startsWith("peers$s") && it.endsWith("${s}profiles") }
        assertTrue(peers.isNotEmpty())
        peers.forEach { (k, f) -> assertTrue("$k has no unread_why", f.unreadWhy.isNotBlank()) }
    }

    @Test fun `810 fleet - a store file the export walked and left out is empty, not unread`() {
        val f = listOf("settings${s}calc${s}a", "settings${s}calc${s}b")
        val seen = setOf("settings${s}calc${s}a${s}k")
        val (u, j) = AccountFleet.unreadOf(f, seen, JSONObject().put("stores", JSONObject()))
        assertEquals(emptyList<String>(), u)
        assertEquals(setOf("settings${s}calc${s}b"), j.keys)
        assertEquals(AccountFleet.EMPTY_WHY, j.values.single())
        // an answer that names no stores walked nothing: the absent file stays unread
        val (u2, j2) = AccountFleet.unreadOf(f, seen, JSONObject())
        assertEquals(listOf("settings${s}calc${s}b"), u2)
        assertTrue(j2.isEmpty())
    }

    // ── #782 apps and mesh with no device pick ───────────────────────────

    @Test fun `782 an app row carries version, stage and auto-update - installed or not`() {
        val a = AccountRuntime.appRow("calc", "Cloud Calc", "com.x.calc", "1.2", 42L, "installed", "installed v42", true, "1.2")
        assertTrue(a.getBoolean("installed")); assertEquals(42L, a.getLong("version_code"))
        assertEquals("1.2", a.getString("version_name")); assertEquals("installed", a.getString("stage"))
        assertTrue(a.getBoolean("auto_update"))
        val b = AccountRuntime.appRow("nav", "Nav", "com.x.nav", null, null, "not_installed", "", false, null)
        assertFalse(b.getBoolean("installed")); assertTrue(b.isNull("version_code"))
        assertEquals("Cloud Calc · 1.2 (42) · installed · auto-update", rosterLine(a))
        assertEquals("Nav · not installed · not_installed", rosterLine(b))
    }

    @Test fun `782 the roster reaches the runtime snapshot with its count`() {
        val rows = listOf(AccountRuntime.appRow("a", "A", "p.a", "1", 1L, "installed", "", true, null),
            AccountRuntime.appRow("b", "B", "p.b", null, null, "not_installed", "", true, null))
        val snap = AccountRuntime.snapshot(listOf(AppRead("apps", "Apps", Status.REACHABLE, "no device picked on Connect · whole fleet", emptyMap(), roster = rows))).second.getJSONObject("apps")
        assertEquals(2, snap.getJSONArray("roster").length())
        assertEquals(2, snap.getJSONObject("counts").getInt("roster"))
        val merged = AccountFleet.merge(listOf(AppRead("apps", "Apps", Status.REACHABLE, "", emptyMap(), roster = rows)),
            listOf(AppRead("apps", "Apps", Status.REACHABLE, "", emptyMap())))
        assertEquals("a fleet merge keeps the roster", 2, merged.single().roster.size)
    }

    @Test fun `782 the live tunnel row names no key`() {
        val t = AccountRuntime.tunnelRow(VaultCockpit.TunnelState("wg0", "10.0.0.5/32", setOf("KEY1=", "KEY2=")))
        assertEquals("wg0", t.getString("tunnel")); assertEquals(2, t.getInt("peers"))
        assertFalse(t.toString().contains("KEY1"))
        assertEquals("wg0 · 10.0.0.5/32 · 2 peers", rosterLine(t))
    }
}
