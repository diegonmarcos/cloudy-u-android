package com.diegonmarcos.superapp.profile

import android.app.Application
import com.diegonmarcos.superapp.profile.AccountDrift.Kind
import com.diegonmarcos.superapp.profile.AccountDrift.Skip
import com.diegonmarcos.superapp.profile.AccountDrift.Three
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #778 — the three-file drift engine on GOLDEN fixtures (test resources `account/`): S the server
 * file, R what three apps reported (one holding nothing, one never observed), L a local copy with an
 * edit, a new field, an agreement with R and a conflict. Every verdict is read from expected.json,
 * never typed twice; the sync directions are checked on the same files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountDriftTest {

    private fun res(name: String) = JSONObject(javaClass.getResourceAsStream("/account/$name")!!.readBytes().decodeToString())

    private val sBody = res("S.json")
    private val lBody = res("L.json")
    private val rFile = res("R.json")
    private val expected = res("expected.json")

    private val s = AccountDrift.leaves(sBody)
    private val l = AccountDrift.leaves(lBody)
    private val r = AccountDrift.leaves(rFile.getJSONObject("body"))
    private val observed = rFile.getJSONArray("observed").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }

    private val apps = listOf(
        AccountDrift.App("mail", "Mail", listOf("mail")),
        AccountDrift.App("about", "About", listOf("about")),
        AccountDrift.App("drive", "Drive", listOf("git")),
        AccountDrift.App("ai", "AI", listOf("ai")),
    )

    private fun pair(id: String): List<AccountDrift.Field> = when (id) {
        "SR" -> AccountDrift.diff(s, r, apps, observed)
        "LS" -> AccountDrift.diff(l, s, apps)
        "LR" -> AccountDrift.diff(l, r, apps, observed)
        else -> error(id)
    }

    @Test fun `bookkeeping, blanks and empty lists are never fields, while a pending marker and a whole list are one field each`() {
        assertFalse(s.keys.any { it.startsWith("_") || it == "schema_version" })
        assertTrue("a pending marker is one leaf", s["electronics › watches"] is JSONObject)
        assertTrue("a list is one leaf", s["git › repos"] is JSONArray)
        val sparse = AccountDrift.leaves(JSONObject().put("a", JSONObject().put("blank", " ").put("none", JSONArray()).put("nul", JSONObject.NULL).put("x", 1)))
        assertEquals(setOf("a › x"), sparse.keys)
        assertEquals(13, s.size)
    }

    @Test fun `key order is never drift`() {
        assertEquals(AccountDrift.canonical(s["electronics › watches"]), AccountDrift.canonical(l["electronics › watches"]))
        assertEquals(AccountDrift.sha256(JSONObject("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}")), AccountDrift.sha256(JSONObject("{\"b\":{\"d\":3,\"c\":2},\"a\":1}")))
        assertNotEquals(AccountDrift.sha256(JSONObject("{\"a\":1}")), AccountDrift.sha256(JSONObject("{\"a\":2}")))
    }

    @Test fun `the canonical text and hash equal the vault checker local py, which pins the same vector`() {
        val v = JSONObject("{\"b\":{\"d\":\"x/y\",\"c\":2,\"e\":[true,null,\"ü\"]},\"a\":1}")
        assertEquals("{\"a\":1,\"b\":{\"c\":2,\"d\":\"x\\/y\",\"e\":[true,null,\"ü\"]}}", AccountDrift.canonical(v))
        assertEquals("8c3218095edccb69df34fcbe734750ebc7b73a4b4241419e324cd3121f29b45e", AccountDrift.sha256(v))
    }

    @Test fun `every pair matches the golden counts and drifted fields`() {
        val pairs = expected.getJSONObject("pairs")
        for (id in pairs.keys()) {
            val want = pairs.getJSONObject(id)
            val fields = pair(id)
            val c = AccountDrift.counts(fields)
            val wc = want.getJSONObject("counts")
            assertEquals("$id same", wc.getInt("same"), c.same)
            assertEquals("$id changed", wc.getInt("changed"), c.changed)
            assertEquals("$id only_a", wc.getInt("only_a"), c.onlyA)
            assertEquals("$id only_b", wc.getInt("only_b"), c.onlyB)
            val wd = want.getJSONObject("drift")
            assertEquals("$id drifted fields", wd.keys().asSequence().associateWith { Kind.valueOf(wd.getString(it)) },
                fields.filter { it.kind != Kind.SAME }.associate { it.path to it.kind })
        }
    }

    @Test fun `a comparison with R covers only what R observed`() {
        val sr = pair("SR")
        assertEquals(observed, sr.map { it.path }.toSet())
        assertFalse("mail was never observed, so it is never drift against R", sr.any { it.app == "mail" })
        assertTrue("without the scope, every unobserved field would read as drift",
            AccountDrift.counts(AccountDrift.diff(s, r, apps)).drift > AccountDrift.counts(sr).drift)
    }

    @Test fun `counts per app follow the cockpit's section ownership`() {
        val by = AccountDrift.byApp(pair("SR"), apps)
        assertEquals(listOf("mail", "about", "drive", "ai"), by.keys.toList())
        assertEquals(0, by.getValue("mail").drift)
        assertEquals(2, by.getValue("about").drift)
        assertEquals(2, by.getValue("drive").drift)
        assertEquals(0, by.getValue("ai").drift)
        assertEquals("", AccountDrift.ownerOf("electronics › watches", apps))
    }

    @Test fun `three-way classes match the golden file, conflict and agreement included`() {
        val want = expected.getJSONObject("three_way")
        val got = AccountDrift.threeWay(s, r, l, observed, apps).associate { it.path to it.state }
        assertEquals(want.keys().asSequence().associateWith { Three.valueOf(want.getString(it)) }, got)
        assertEquals(Three.CONFLICT, got["git › ssh_private_key"])
        assertEquals(Three.AGREED, got["git › github_token"])
    }

    @Test fun `runtime to declared writes R's value, never erases with nothing, never takes a read-only or unobserved field`() {
        val drift = AccountDrift.drifted(pair("LR")) + "mail › passwords › PW_ADMIN"
        val w = AccountDrift.runtimeToDeclared(lBody, r, observed, setOf("git › ssh_private_key"), drift)
        assertEquals(listOf("about › profile › email", "about › profile › titles_v2"), w.written)
        assertEquals(mapOf(
            "about › profile › company" to Skip.HOLDS_NONE,
            "git › ssh_private_key" to Skip.READ_ONLY,
            "mail › passwords › PW_ADMIN" to Skip.NOT_OBSERVED,
        ), w.skipped)
        val after = AccountDrift.leaves(w.body)
        assertEquals("ada@other.test", after["about › profile › email"])
        assertEquals("Engineer", after["about › profile › titles_v2"])
        assertEquals("a runtime holding nothing never erases the declared value", "Acme", after["about › profile › company"])
        assertEquals("key-local", after["git › ssh_private_key"])
        assertEquals("the input is not modified", "Lead", AccountDrift.leaves(lBody)["about › profile › titles_v2"])
        assertEquals("after the pull, L and R agree on every written field", 0,
            AccountDrift.diff(after, r, apps, w.written.toSet()).count { it.kind != Kind.SAME })
    }

    @Test fun `server to runtime plans S's value for observed fields only, grouped by app`() {
        val paths = AccountDrift.drifted(pair("SR")) + "mail › passwords › PW_ADMIN" + "about › profile › nowhere"
        val plan = AccountDrift.pushPlan(s, observed, paths, apps)
        assertEquals(setOf("about", "drive"), plan.keys)
        assertEquals(listOf("about › profile › company" to "Acme", "about › profile › email" to "ada@example.test"), plan.getValue("about"))
        assertEquals(listOf("git › github_token" to "tok-server", "git › ssh_private_key" to "key-server"), plan.getValue("drive"))
    }

    @Test fun `put creates the path and keeps the siblings`() {
        val b = AccountDrift.put(JSONObject().put("about", JSONObject().put("profile", JSONObject().put("name", "Ada"))), "about › profile › location", "Paris")
        assertEquals(mapOf("about › profile › name" to "Ada", "about › profile › location" to "Paris"), AccountDrift.leaves(b))
        AccountDrift.put(b, "fresh › deep › key", "v")
        assertEquals("v", AccountDrift.leaves(b)["fresh › deep › key"])
    }

    @Test fun `drifted names every non-same field, per app or all`() {
        val sr = pair("SR")
        assertEquals(listOf("git › github_token", "git › ssh_private_key"), AccountDrift.drifted(sr, "drive"))
        assertEquals(4, AccountDrift.drifted(sr).size)
    }
}
