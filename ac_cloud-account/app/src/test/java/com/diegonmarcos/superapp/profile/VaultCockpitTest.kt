package com.diegonmarcos.superapp.profile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.superapp.profile.VaultCockpit.State as S
import com.diegonmarcos.superapp.ui.StatusLight.State as L
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #570 — Configs ▸ Profile ▸ Fleet: the vault bundle as the fleet configurator.
 *
 * Every expectation is computed from the fixture by a walk of its own, or read
 * back from the store an apply wrote — never typed twice. The device is chosen,
 * and its mesh identity derives from the vault: a profile is the device's when
 * the profile's own Address line carries the device's declared wg address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultCockpitTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `devices are the electronics entries that carry a wg_peer, pending ones excluded`() {
        val b = bundle()
        val got = VaultCockpit.devices(b)
        // Independent walk: every group → entry with a non-pending wg_peer.wg_ip.
        val want = mutableListOf<Pair<String, String>>()
        val e = b.getJSONObject("electronics")
        e.keys().forEach { g ->
            val group = e.optJSONObject(g) ?: return@forEach
            group.keys().forEach { id ->
                val peer = group.optJSONObject(id)?.optJSONObject("wg_peer") ?: return@forEach
                if (!peer.optBoolean("pending") && peer.optString("wg_ip").isNotBlank())
                    want += id to peer.getString("wg_ip")
            }
        }
        assertTrue("fixture must have a pending group", e.has("watches"))
        assertEquals(want.toSet(), got.map { it.id to it.wgIp }.toSet())
        assertEquals("termux-galaxy", got.first { it.id == "galaxy" }.label)
        // #766 the vault's own device label wins over the tunnel's client name.
        b.getJSONObject("electronics").getJSONObject("phones").getJSONObject("galaxy").put("label", "Samsung Galaxy S21+")
        assertEquals("Samsung Galaxy S21+", VaultCockpit.devices(b).first { it.id == "galaxy" }.label)
    }

    @Test fun `a mesh profile belongs to the device whose declared address it carries`() {
        val b = bundle()
        val devices = VaultCockpit.devices(b).associateBy { it.id }
        val galaxy = VaultCockpit.meshProfiles(b, devices.getValue("galaxy"))
        val surface = VaultCockpit.meshProfiles(b, devices.getValue("surface"))
        assertEquals(setOf("config-v4-split", "config-v6-full"), galaxy.keys)
        assertTrue("the surface has no profile in this export", surface.isEmpty())
        // Ownership came from the Address line, not the file name: each owned
        // profile carries the device's v4 OR v6 address there (the v6-only
        // profile is the device's by fd0c:1d00::9 alone).
        val g = devices.getValue("galaxy")
        galaxy.forEach { (name, conf) ->
            val address = conf.lineSequence().first { it.startsWith("Address") }
            assertTrue("$name: $address", address.contains(g.wgIp) || address.contains(g.wgIpv6))
        }
        assertTrue("the v6-only profile is owned by the v6 address alone",
            !galaxy.getValue("config-v6-full").lineSequence().first { it.startsWith("Address") }.contains(g.wgIp))
    }

    // #1693 the two mesh-apply tests (applying a profile writes the tunnel through the WireGuard parser; a conf
    // the parser rejects writes nothing) moved to the SuperApp's network/AccountMeshApplyTest: the apply path is
    // the SuperApp's own WireGuard prefs, and this suite runs in Cloud Account, which has none.

    @Test fun `the mail account is found by the local part and its password by pass_env`() {
        val b = bundle()
        val d = VaultCockpit.mailDeclared(b, "me@example.org")!!
        val accounts = b.getJSONObject("mail").getJSONObject("accounts")
        val key = accounts.keys().asSequence().first { accounts.getJSONObject(it).getString("name") == "me" }
        assertEquals(key, d.account)
        assertEquals(b.getJSONObject("mail").getJSONObject("passwords").getString(accounts.getJSONObject(key).getString("pass_env")), d.password)
        assertEquals(b.getJSONObject("mail").getJSONObject("endpoints").getString("domain"), d.domain)
        assertNull("no such account", VaultCockpit.mailDeclared(b, "nobody@example.org"))
        assertNull("a pending password is no password", VaultCockpit.mailDeclared(b, "yo@example.org")!!.password)
    }

    @Test fun `ai rows map each vault token to the device provider the layout names`() {
        val b = bundle()
        val layout = VaultCockpit.parseLayout(JSONObject("""{"sections":[],"ai_tokens":{"openrouter_my_ai_api":"openrouter"}}"""))
        val token = b.getJSONObject("ai").getJSONObject("tokens").getString("openrouter_my_ai_api")
        val present = VaultCockpit.aiState("""{"providers":[{"id":"openrouter","key_present":true,"key_hint":"${token.takeLast(4)}"}]}""")
        val rows = VaultCockpit.aiRows(b, layout, present, "down", "unmapped").associateBy { it.label }
        assertEquals(VaultCockpit.State.MATCH, rows.getValue("openrouter_my_ai_api").state)
        assertEquals(VaultCockpit.State.PENDING, rows.getValue("openrouter_3").state)
        assertEquals("unmapped", rows.getValue("openrouter_hermes_agent").device)
        assertEquals(VaultCockpit.State.PENDING, rows.getValue("claude").state)

        val other = VaultCockpit.aiState("""{"providers":[{"id":"openrouter","key_present":true,"key_hint":"zzzz"}]}""")
        assertEquals(VaultCockpit.State.DIFFERS, VaultCockpit.aiRows(b, layout, other, "down", "u").first { it.label == "openrouter_my_ai_api" }.state)
        assertEquals("down", VaultCockpit.aiRows(b, layout, null, "down", "u").first { it.label == "openrouter_my_ai_api" }.device)
        // Every row carries a value length, never the token itself.
        VaultCockpit.aiRows(b, layout, present, "d", "u").forEach { assertTrue(it.declared, !it.declared.contains(token)) }
    }

    @Test fun `the declared app set merges fleet packages and an inventory, pending contributes nothing`() {
        val b = bundle()
        val galaxy = VaultCockpit.devices(b).first { it.id == "galaxy" }
        val fleet = setOf("com.x.a")
        val got = VaultCockpit.appsDeclared(b, galaxy, fleet)
        // Independent walk: every "package" under apps.devices.galaxy.
        val want = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(b.getJSONObject("apps").getJSONObject("devices").getJSONObject("galaxy").toString())
            .map { it.groupValues[1] }.toSet()
        assertTrue(want.size >= 3)
        assertEquals(want, got.map { it.pkg }.toSet())
        assertTrue(got.first { it.pkg == "com.x.a" }.ours)
        assertTrue(!got.first { it.pkg == "com.x.b" }.ours)
        assertTrue(VaultCockpit.appsDeclared(b, VaultCockpit.devices(b).first { it.id == "surface" }, fleet).isEmpty())
    }

    @Test fun `#727 the Infos app list names each declared app from the vault's own file and its store through the one map`() {
        val b = bundle()
        val fleet = setOf("com.x.a")
        val got = VaultCockpit.appsListed(b, "galaxy", fleet).associateBy { it.pkg }
        assertEquals(VaultCockpit.appsDeclared(b, "galaxy", fleet).map { it.pkg }, got.keys.toList())
        assertEquals("A", got.getValue("com.x.a").label)
        assertEquals("com.x.b", got.getValue("com.x.b").label)
        val sources = JSONObject("""{"ours":{"label":"Cloud"},"sources":{"com.android.vending":{"label":"Play"}}}""")
        assertEquals("Cloud", VaultCockpit.storeLabel(sources, got.getValue("com.x.a")))
        assertEquals("Play", VaultCockpit.storeLabel(sources, VaultCockpit.DeclaredApp("p", "P", "com.android.vending", false)))
        assertEquals("org.unknown", VaultCockpit.storeLabel(sources, VaultCockpit.DeclaredApp("p", "P", "org.unknown", false)))
        assertNull(VaultCockpit.storeLabel(sources, got.getValue("com.y.c")))
        assertTrue(VaultCockpit.appsListed(b, "surface", fleet).isEmpty())
    }

    @Test fun `keyboard rows show a pending list as pending and an exported one by size`() {
        val rows = VaultCockpit.keyboardRows(bundle(), "kb").associateBy { it.label }
        assertEquals(VaultCockpit.State.PENDING, rows.getValue("personal_data").state)
        assertTrue(rows.getValue("personal_data").declared.contains("pending-phone-export"))
        assertEquals("2 entries", rows.getValue("urls_pub").declared)
        assertTrue(rows.values.all { it.device == "kb" })
    }

    @Test fun `a schema version this build does not know is refused`() {
        val known = setOf(1)
        assertNull(VaultConnect.unknownSchemaVersion(JSONObject("""{"bundle":{"schema_version":1}}"""), known))
        assertEquals(2, VaultConnect.unknownSchemaVersion(JSONObject("""{"bundle":{"schema_version":2}}"""), known))
        assertEquals(0, VaultConnect.unknownSchemaVersion(JSONObject("""{"bundle":{}}"""), known))
        assertTrue("the baked list is non-empty", VaultConnect.knownSchemaVersions.isNotEmpty())
    }

    @Test fun `the baked layout has sections, each fed by at least one vault section`() {
        val layout = VaultCockpit.layout
        assertTrue(layout.sections.isNotEmpty())
        layout.sections.forEach { s ->
            assertTrue(s.id, s.vault.isNotEmpty())
            assertTrue(s.id, s.label.isNotBlank())
        }
        assertEquals(layout.sections.size, layout.sections.map { it.id }.distinct().size)
        assertTrue(VaultCockpit.consumed(layout).containsAll(layout.sections.flatMap { it.vault }))
    }

    // ── #570 reopened: the cockpit's lights ──────────────────────────────

    @Test fun `a card's light is the shared StatusLight state its rows justify`() {
        fun row(s: S) = VaultCockpit.Row("r", "d", "v", s)
        assertEquals(L.UNKNOWN, VaultCockpit.sectionLight(emptyList()))
        assertEquals(L.ON, VaultCockpit.sectionLight(listOf(row(S.MATCH), row(S.MATCH))))
        assertEquals(L.ON, VaultCockpit.sectionLight(listOf(row(S.MATCH), row(S.PENDING))))
        assertEquals(L.UNKNOWN, VaultCockpit.sectionLight(listOf(row(S.PENDING))))
        assertEquals(L.OFF, VaultCockpit.sectionLight(listOf(row(S.MATCH), row(S.DIFFERS))))
        assertEquals(L.OFF, VaultCockpit.sectionLight(listOf(row(S.MATCH), row(S.ABSENT), row(S.PENDING))))
        // A section this app cannot observe never earns a colour, whatever the rows say.
        assertEquals(L.UNVERIFIABLE, VaultCockpit.sectionLight(listOf(row(S.MATCH)), observed = false))
        assertEquals(L.UNVERIFIABLE, VaultCockpit.sectionLight(emptyList(), observed = false))
        // The hero sums the cards: one red is red, all green is green, else nobody can say.
        assertEquals(L.UNKNOWN, VaultCockpit.overallLight(emptyList()))
        assertEquals(L.ON, VaultCockpit.overallLight(listOf(L.ON, L.ON)))
        assertEquals(L.OFF, VaultCockpit.overallLight(listOf(L.ON, L.OFF, L.UNKNOWN)))
        assertEquals(L.UNKNOWN, VaultCockpit.overallLight(listOf(L.ON, L.UNKNOWN)))
        assertEquals(L.UNKNOWN, VaultCockpit.overallLight(listOf(L.ON, L.UNVERIFIABLE)))
        val t = VaultCockpit.tally(listOf(row(S.MATCH), row(S.MATCH), row(S.DIFFERS), row(S.ABSENT), row(S.PENDING)))
        assertEquals(VaultCockpit.Tally(2, 2, 1), t)
    }

    @Test fun `the layout carries a badge icon per section, the observed flag and the device icons`() {
        val layout = VaultCockpit.parseLayout(JSONObject("""{
            "sections":[{"id":"a","label":"A","vault":["x"],"icon":"ic_mail"},
                        {"id":"b","label":"B","vault":["y"],"icon":"ic_keyboard","observed":false}],
            "device_icons":{"phone":"ic_p","_default":"ic_d"}}"""))
        assertEquals("ic_mail", layout.sections[0].icon)
        assertTrue(layout.sections[0].observed)
        assertTrue(!layout.sections[1].observed)
        val b = bundle()
        val galaxy = VaultCockpit.devices(b).first { it.id == "galaxy" }
        val surface = VaultCockpit.devices(b).first { it.id == "surface" }
        assertEquals("phone", galaxy.type)
        assertEquals("ic_p", VaultCockpit.deviceIcon(layout, galaxy))
        assertEquals("the notebook has no entry, so the default", "ic_d", VaultCockpit.deviceIcon(layout, surface))
        assertEquals("nothing chosen yet is the default too", "ic_d", VaultCockpit.deviceIcon(layout, null))
        assertEquals("", VaultCockpit.deviceIcon(VaultCockpit.Layout(emptyList(), emptyMap()), galaxy))
        // The BAKED layout: every section has an icon, and it resolves to a real drawable — not the fallback.
        val fallback = ctx.resources.getIdentifier("ic_link_tile", "drawable", ctx.packageName)
        VaultCockpit.layout.sections.forEach { s ->
            assertTrue("${s.id} has no icon", s.icon.isNotBlank())
            val res = ctx.resources.getIdentifier(s.icon, "drawable", ctx.packageName)
            assertTrue("${s.id}: icon ${s.icon} is not a drawable of this app", res != 0 && res != fallback)
        }
        assertTrue("the baked layout names a default device icon", VaultCockpit.layout.deviceIcons.containsKey("_default"))
        VaultCockpit.layout.deviceIcons.values.forEach {
            assertTrue("device icon $it is not a drawable", ctx.resources.getIdentifier(it, "drawable", ctx.packageName) != 0)
        }
        assertTrue("at least one section is declared unobservable (the keyboard owns its lists)",
            VaultCockpit.layout.sections.any { !it.observed })
    }

    @Test fun `the chosen device is the only thing stored`() {
        VaultCockpit.selectDevice(ctx, "galaxy")
        assertEquals("galaxy", VaultCockpit.selectedDevice(ctx))
        val all = ctx.getSharedPreferences("vault_cockpit", Context.MODE_PRIVATE).all
        assertEquals(mapOf("device_id" to "galaxy"), all)
    }

    // ── fixture: the bundle shape E0_configs/emit.py produces (schema v1 + apps) ──

    private val zeroKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val peerA = "vV/phXUwnCjxACQ5Df11Uw47BzJaK4r85jPYMu2HmDc="
    private val peerB = "Bnyf83VpvGY4BjcjQEXgutNEzx9OXMltHIcKNh7cKkc="

    private fun conf(address: String, vararg peers: String) = buildString {
        appendLine("# fixture"); appendLine("[Interface]")
        appendLine("PrivateKey = $zeroKey"); appendLine("Address = $address"); appendLine("DNS = 10.0.0.1")
        peers.forEach { appendLine(); appendLine("[Peer]"); appendLine("PublicKey = $it"); appendLine("Endpoint = 1.2.3.4:51820"); appendLine("AllowedIPs = 10.0.0.0/24") }
    }

    private fun bundle() = JSONObject().apply {
        put("schema_version", 1)
        put("mesh", JSONObject().put("profiles", JSONObject()
            .put("config-v4-split", conf("10.0.0.9/24, fd0c:1d00::9/64", peerA, peerB))
            .put("config-v6-full", conf("fd0c:1d00::9/64", peerA))
            .put("config-other", conf("10.0.0.77/24", peerA)))
            .put("public_key", "PUB"))
        put("mail", JSONObject()
            .put("endpoints", JSONObject().put("domain", "jmap.example.org"))
            .put("accounts", JSONObject()
                .put("admin", JSONObject().put("name", "me").put("pass_env", "ME_PASSWORD"))
                .put("yo", JSONObject().put("name", "yo").put("pass_env", "YO_PASSWORD")))
            .put("passwords", JSONObject().put("ME_PASSWORD", "pw-one")
                .put("YO_PASSWORD", JSONObject().put("pending", true).put("source", "x").put("reason", "y"))))
        put("ai", JSONObject().put("tokens", JSONObject()
            .put("openrouter_my_ai_api", "sk-or-abcd1234")
            .put("openrouter_hermes_agent", "sk-or-efgh5678")
            .put("openrouter_3", JSONObject().put("pending", true).put("source", "no-store").put("reason", "r"))
            .put("claude", JSONObject().put("plan", "subscription").put("token", JSONObject.NULL))))
        put("git", JSONObject().put("github_token", "ghp_x").put("ssh_private_key", "KEY").put("repos", org.json.JSONArray(listOf("a", "b"))))
        put("autocomplete", JSONObject()
            .put("personal_data", JSONObject().put("pending", true).put("source", "pending-phone-export").put("reason", "Personal Data"))
            .put("urls_pub", org.json.JSONArray(listOf("https://a", "https://b"))))
        put("electronics", JSONObject()
            .put("computers", JSONObject().put("surface", JSONObject()
                .put("type", "notebook")
                .put("wg_peer", JSONObject().put("wg_ip", "10.0.0.5").put("wg_ipv6", "fd0c:1d00::5").put("name", "desktop-nixos"))
                .put("mac", JSONObject().put("pending", true).put("source", "o").put("reason", "r"))))
            .put("phones", JSONObject().put("galaxy", JSONObject()
                .put("type", "phone")
                .put("wg_peer", JSONObject().put("wg_ip", "10.0.0.9").put("wg_ipv6", "fd0c:1d00::9").put("name", "termux-galaxy"))))
            .put("watches", JSONObject().put("pending", true).put("source", "o").put("reason", "r"))
            .put("kde_connect", org.json.JSONArray()))
        put("apps", JSONObject().put("devices", JSONObject().put("galaxy", JSONObject()
            .put("fleet", org.json.JSONArray().put(JSONObject().put("package", "com.x.a").put("label", "A")).put(JSONObject().put("package", "com.x.b")))
            .put("foreign", JSONObject().put("pending", true).put("source", "pending-phone-export").put("reason", "r"))
            .put("inventory", JSONObject().put("kind", "cloud-sa.app-inventory").put("schema", 1)
                .put("apps", org.json.JSONArray().put(JSONObject().put("package", "com.y.c").put("version_name", "1").put("version_code", 1)
                    .put("origin_store", JSONObject.NULL).put("ours", false).put("category", JSONObject.NULL)))))))
        put("_generated", JSONObject().put("tree_sha256", "abc"))
    }
}
