package com.diegonmarcos.superapp.profile

import android.app.Application
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
 * #695 — Configs ▸ Account ▸ Infos draws the fetched vault configs, and a
 * secret never reaches a view.
 *
 * The rule under test is the BAKED one ([InfoMask.declared], i.e.
 * build.json::ui.profile.infos.mask through BuildConfig), not a copy typed
 * here. Every token-shaped fixture value is assembled at runtime, so this file
 * holds nothing a secret scanner could take for a real credential.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InfoMaskTest {

    private val githubToken = "gh" + "p_" + "a1B2c3D4e5".repeat(4)
    private val sshKey = "-----BEGIN " + "OPENSSH PRIVATE KEY-----\n" + "b3BlbnNzaC1rZXktdjEAAAAA".repeat(3)
    private val mailPassword = "correct-horse-battery-staple"
    private val aiToken = "sk" + "-or-v1-" + "0123456789abcdef".repeat(3)
    private val wgProfile = "[Interface]\nPrivateKey = " + "K".repeat(43) + "=\nAddress = 10.9.9.9/32"
    private val clipBearer = "Authorization: Bearer " + "q".repeat(24)
    private val secrets = listOf(githubToken, sshKey, mailPassword, aiToken, wgProfile, clipBearer)

    private fun bundle(): JSONObject = JSONObject()
        .put("git", JSONObject().put("github_token", githubToken).put("ssh_private_key", sshKey)
            .put("repos", JSONArray().put(JSONObject().put("repo", "front"))))
        .put("mail", JSONObject()
            .put("passwords", JSONObject().put("ME_PASSWORD", mailPassword))
            .put("accounts", JSONObject().put("admin", JSONObject().put("name", "me").put("pass_env", "ME_PASSWORD")))
            .put("endpoints", JSONObject().put("domain", "jmap.example.test")))
        .put("mesh", JSONObject().put("profiles", JSONObject().put("config-v4-full", wgProfile)))
        .put("ai", JSONObject().put("tokens", JSONObject().put("openrouter", aiToken)))
        .put("autocomplete", JSONObject()
            .put("default", JSONArray().apply { repeat(20) { put(JSONObject().put("text", "clip $it")) } })
            .put("urls_pub", JSONArray().put(JSONObject().put("text", clipBearer))))
        .put("about", JSONObject().put("profile", JSONObject().put("name", "Test Person").put("company", "Acme"))
            .put("date_of_birth", JSONObject().put("pending", true).put("source", "owner").put("reason", "not exported")))

    private fun allRows(mask: InfoMask): Map<String, List<InfoMask.Row>> {
        val b = bundle()
        return b.keys().asSequence().associateWith { mask.rows(it, b.opt(it)) }
    }

    @Test fun `the baked rule is a real rule, not the fail-closed fallback`() {
        assertFalse("the baked mask fell back to mask-all: its declaration is missing or broken", InfoMask.declared.maskAll)
    }

    @Test fun `no secret of the bundle reaches a row, and each masked row keeps only its length`() {
        val rows = allRows(InfoMask.declared).values.flatten()
        for (s in secrets) {
            assertTrue("a secret reached a drawn row", rows.none { it.text.contains(s) })
        }
        val masked = rows.filter { it.kind == InfoMask.Kind.MASKED }
        assertTrue(masked.all { it.text.isEmpty() && it.size > 0 })
        // Every secret leaf is one of the masked rows, measured by its own length.
        for (s in listOf(githubToken, sshKey, mailPassword, aiToken, wgProfile, clipBearer)) {
            assertTrue("no masked row carries the length of a secret", masked.any { it.size == s.length })
        }
    }

    @Test fun `what is not a secret is drawn, verbatim`() {
        val rows = allRows(InfoMask.declared)
        assertTrue(rows.getValue("about").any { it.kind == InfoMask.Kind.SHOWN && it.text == "Test Person" })
        assertTrue(rows.getValue("git").any { it.kind == InfoMask.Kind.SHOWN && it.text == "front" })
        assertTrue(rows.getValue("mail").any { it.kind == InfoMask.Kind.SHOWN && it.text == "jmap.example.test" })
    }

    @Test fun `a list longer than collapse_over is one counted row, a pending marker reads pending`() {
        val rows = allRows(InfoMask.declared)
        val auto = rows.getValue("autocomplete")
        assertTrue(auto.any { it.kind == InfoMask.Kind.COLLAPSED && it.path == "default" && it.size == 20 })
        assertTrue(auto.none { it.path.startsWith("default" + InfoMask.SEP) })
        assertTrue(rows.getValue("about").any { it.kind == InfoMask.Kind.PENDING && it.path == "date_of_birth" })
    }

    @Test fun `a declaration with no pattern masks every leaf`() {
        val none = InfoMask(emptyList(), emptyList(), 12)
        assertTrue(none.maskAll)
        val leaves = allRows(none).values.flatten().filter { it.kind == InfoMask.Kind.SHOWN || it.kind == InfoMask.Kind.MASKED }
        assertTrue(leaves.isNotEmpty())
        assertTrue(leaves.all { it.kind == InfoMask.Kind.MASKED })
    }

    @Test fun `one pattern that does not compile masks every leaf`() {
        val broken = InfoMask(listOf("token", "("), listOf("PrivateKey"), 12)
        assertTrue(broken.maskAll)
        assertEquals(0, allRows(broken).values.flatten().count { it.kind == InfoMask.Kind.SHOWN })
    }

    @Test fun `parse reads paths, values and collapse_over off the declaration shape`() {
        val m = InfoMask.parse(JSONObject().put("paths", JSONArray().put("token")).put("values", JSONArray())
            .put("collapse_over", 2))
        assertFalse(m.maskAll)
        // Two keys (not over 2) are walked; the three-item list under one of them is folded.
        val rows = m.rows("x", JSONObject().put("list", JSONArray().put("a").put("b").put("c")).put("token", "t"))
        assertTrue(rows.any { it.kind == InfoMask.Kind.COLLAPSED && it.path == "list" && it.size == 3 })
        assertTrue(rows.any { it.kind == InfoMask.Kind.MASKED && it.path == "token" })
        assertTrue(InfoMask.parse(null).maskAll)
    }

    @Test fun `#713 the baked schema is the vault's sections - schema json's seven, then the staged apps and peers - and every declared field is a row, filled or empty`() {
        val schema = InfoMask.schema
        assertEquals(listOf("mesh", "mail", "ai", "git", "about", "autocomplete", "electronics", "apps", "peers"), schema.map { it.id })
        val b = bundle()
        for (s in schema) {
            val rows = InfoMask.declared.schemaRows(s, b.opt(s.id))
            for (f in s.fields) assertTrue("${s.id} › $f has no row", rows.any { it.path == f || it.path.startsWith(f + InfoMask.SEP) })
        }
        // The fixture leaves ai › tokens › claude and all of electronics unfilled: they read EMPTY, not absent.
        val ai = schema.first { it.id == "ai" }
        assertTrue(InfoMask.declared.schemaRows(ai, b.opt("ai")).any { it.path == "tokens › claude" && it.kind == InfoMask.Kind.EMPTY })
        val el = schema.first { it.id == "electronics" }
        assertTrue(InfoMask.declared.schemaRows(el, null).all { it.kind == InfoMask.Kind.EMPTY })
        // A key the declaration does not name is still drawn: the schema adds rows, it never hides one.
        val extra = InfoMask.declared.schemaRows(InfoMask.SchemaSection("x", "X", listOf("a")), JSONObject().put("b", "seen"))
        assertTrue(extra.any { it.path == "a" && it.kind == InfoMask.Kind.EMPTY })
        assertTrue(extra.any { it.path == "b" && it.kind == InfoMask.Kind.SHOWN && it.text == "seen" })
        // The mask still holds on a declared field: the GitHub token is a length, never its text.
        val git = InfoMask.declared.schemaRows(schema.first { it.id == "git" }, b.opt("git"))
        assertTrue(git.any { it.path == "github_token" && it.kind == InfoMask.Kind.MASKED && it.text.isEmpty() })
    }

    @Test fun `#727 no section is dropped - the skeleton, then the fetch's own schema, then every key the bundle carries`() {
        val schema = listOf(InfoMask.SchemaSection("a", "A", listOf("f")))
        val b = JSONObject().put("a", 1).put("c", JSONObject()).put("_generated", 1).put("schema_version", 1)
        val got = InfoMask.sectionsFor(schema, listOf("b" to "B", "a" to "dup"), b)
        assertEquals(listOf("a", "b", "c"), got.map { it.id })
        assertEquals("A", got[0].label)
        assertEquals(listOf("a", "c"), InfoMask.sectionsFor(schema, emptyList(), b).map { it.id })
        // The staged apps section lists its declared apps and links into the Store.
        val apps = InfoMask.schema.first { it.id == "apps" }
        assertEquals("apps", apps.render)
        assertTrue(apps.route.startsWith("page:"))
    }
}
