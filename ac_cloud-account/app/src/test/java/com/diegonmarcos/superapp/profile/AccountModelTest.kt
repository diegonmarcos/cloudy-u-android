package com.diegonmarcos.superapp.profile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.profile.AccountRuntime.AppRead
import com.diegonmarcos.superapp.profile.AccountRuntime.Status
import com.diegonmarcos.superapp.profile.AccountStore.Slot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #778 — Account's tab structure and sync directions through the ONE model the tabs and the debug
 * API share, over the baked declaration (build.json ui.profile.tabs / drift, the cockpit's apps).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private fun model() = AccountModel(ctx, AccountStore(tmp.root, AccountStore.PlainIo))

    private val server = JSONObject()
        .put("about", JSONObject().put("profile", JSONObject().put("name", "Ada").put("company", "Acme")))
        .put("git", JSONObject().put("github_token", "tok-server-value"))

    private val name = "about › profile › name"
    private val company = "about › profile › company"
    private val token = "git › github_token"

    /** R as AccountRuntime assembles it: about reports a different name and holds no company. */
    private fun writeRuntime(m: AccountModel, nameNow: String = "Bob") {
        val (body, apps) = AccountRuntime.snapshot(listOf(
            AppRead("about", "About", Status.REACHABLE, "", mapOf(name to nameNow, company to null)),
            AppRead("keyboard", "Keyboard", Status.NOT_REPORTING, "com.example.keyboard", emptyMap()),
        ))
        m.store.write(Slot.R, body, "runtime", "t", apps)
    }

    @Test fun `the strip is exactly the four declared tabs, in order`() {
        assertEquals(listOf("connect", "profiles", "runtime", "drift"), AccountModel.tabs().map { it.id })
        assertTrue(AccountModel.tabs().all { it.label.isNotBlank() })
    }

    @Test fun `drift compares the declared pairs, in order, and the apps are the cockpit's`() {
        assertEquals(listOf("SR", "LS", "LR"), AccountModel.pairs().map { it.id })
        assertEquals(listOf(Slot.S to Slot.R, Slot.L to Slot.S, Slot.L to Slot.R), AccountModel.pairs().map { it.a to it.b })
        val apps = model().apps.map { it.id }
        assertTrue(apps.containsAll(listOf("mail", "keyboard", "mesh", "drive", "ai", "apps", "about")))
        assertTrue("the upload target is declared, and it is not the generated server file",
            AccountModel.upload.optString("path").let { it.isNotBlank() && !it.endsWith("profile-secrets.json") })
    }

    @Test fun `a fetch lands S, and L starts as S only when there is none`() {
        val m = model()
        m.landServer(server, "GitHub · WebAuth")
        assertEquals("GitHub · WebAuth", m.server()!!.meta.source)
        assertEquals(AccountDrift.sha256(server), m.savedLocal()!!.meta.sha256)
        m.edit(name, "Eve"); m.save()
        m.landServer(JSONObject(server.toString()).put("x", JSONObject().put("y", "z")), "File")
        assertEquals("a later fetch never clobbers the local copy", "Eve", AccountDrift.leaves(m.savedLocal()!!.body)[name])
        assertEquals("z", AccountDrift.leaves(m.server()!!.body)["x › y"])
    }

    @Test fun `Profiles edits the working copy until Save, and populate from the server resets it`() {
        val m = model()
        m.landServer(server, "x")
        m.edit(name, "Eve")
        assertTrue(m.dirty)
        assertEquals("Eve", AccountDrift.leaves(m.shown())[name])
        assertEquals("not saved yet", "Ada", AccountDrift.leaves(m.savedLocal()!!.body)[name])
        m.save()
        assertFalse(m.dirty)
        assertEquals("Eve", AccountDrift.leaves(m.savedLocal()!!.body)[name])
        assertEquals("a new model reads the saved copy", "Eve", AccountDrift.leaves(model().shown())[name])
        m.populateFromServer()
        assertEquals("Ada", AccountDrift.leaves(m.shown())[name])
        assertTrue(m.dirty)
    }

    @Test fun `populate from runtime writes what the apps report and keeps what they hold none of`() {
        val m = model()
        m.landServer(server, "x")
        writeRuntime(m)
        m.populateFromRuntime()
        val l = AccountDrift.leaves(m.shown())
        assertEquals("Bob", l[name])
        assertEquals("Acme", l[company])
        assertEquals("an unobserved field is untouched", "tok-server-value", l[token])
    }

    @Test fun `runtime to declared saves into L, discard returns L to S`() {
        val m = model()
        m.landServer(server, "x")
        writeRuntime(m)
        val lr = AccountModel.pairs().first { it.a == Slot.L && it.b == Slot.R }
        assertEquals(setOf(name, company), m.diff(lr).filter { it.kind != AccountDrift.Kind.SAME }.map { it.path }.toSet())
        m.pullRuntimeToLocal(listOf(name, company))
        assertEquals("Bob", AccountDrift.leaves(m.savedLocal()!!.body)[name])
        assertFalse(m.dirty)
        assertEquals(listOf(company), m.diff(lr).filter { it.kind != AccountDrift.Kind.SAME }.map { it.path })
        m.discardLocal()
        assertEquals("Ada", AccountDrift.leaves(m.savedLocal()!!.body)[name])
    }

    @Test fun `server to runtime pushes S's value into the app and re-reads it`() {
        val m = model()
        m.landServer(server, "x")
        writeRuntime(m)
        ProfilePrefs(ctx).name = "Bob"
        m.pushServerToRuntime(listOf(name))
        assertEquals("Ada", ProfilePrefs(ctx).name)
        val sr = AccountModel.pairs().first { it.a == Slot.S && it.b == Slot.R }
        assertEquals("after the push the fresh snapshot agrees", AccountDrift.Kind.SAME, m.diff(sr).first { it.path == name }.kind)
    }

    @Test fun `the report has every file and pair, and no value`() {
        val m = model()
        m.landServer(server, "x")
        writeRuntime(m)
        val report = m.report()
        assertEquals(setOf("S", "R", "L"), report.getJSONObject("files").keys().asSequence().toSet())
        assertEquals(setOf("SR", "LS", "LR"), report.getJSONObject("pairs").keys().asSequence().toSet())
        assertNotNull(report.getJSONObject("three_way"))
        val text = report.toString()
        assertFalse("no value leaves in the report", "tok-server-value" in text || "Acme" in text || "Bob" in text)
    }

    @Test fun `the upload plan commits the saved L file to the declared path`() {
        val m = model()
        m.landServer(server, "x")
        val (target, bytes, message) = m.uploadPlan("galaxy")!!
        assertEquals(AccountModel.upload.optString("path"), target.path)
        assertEquals("L", JSONObject(String(bytes)).getJSONObject("_meta").getString("file"))
        assertTrue("galaxy" in message)
    }

    @Test fun `the runtime snapshot files observed values at their paths and lists what was observed`() {
        val (body, apps) = AccountRuntime.snapshot(listOf(
            AppRead("about", "About", Status.REACHABLE, "", mapOf(name to "Bob", company to null)),
            AppRead("mesh", "Mesh", Status.REACHABLE, "", mapOf("mesh › profiles › wg0" to "x"), readOnly = setOf("mesh › profiles › wg0")),
        ))
        assertEquals(mapOf(name to "Bob", "mesh › profiles › wg0" to "x"), AccountDrift.leaves(body))
        assertEquals(setOf(name, company, "mesh › profiles › wg0"), AccountRuntime.observed(apps))
        assertEquals(setOf("mesh › profiles › wg0"), AccountRuntime.readOnly(apps))
        assertEquals("reachable", apps.getJSONObject("about").getString("status"))
    }

    @Test fun `each app's path rule files its values where the vault keeps them`() {
        assertEquals(mapOf("about › profile › titles_v2" to "titles"), AccountRuntime.aboutPaths(mapOf("titles" to "titles_v2")))
        assertEquals("peers › galaxy › profiles › wg0", AccountRuntime.meshPath("galaxy/wg0"))
        assertEquals("mesh › profiles › phone", AccountRuntime.meshPath("phone"))
        assertEquals(setOf("git › github_token", "git › ssh_private_key"), AccountRuntime.drivePaths().keys)
        val mail = JSONObject().put("mail", JSONObject().put("accounts", JSONObject()
            .put("admin", JSONObject().put("name", "me").put("pass_env", "PW_ADMIN"))
            .put("bot", JSONObject().put("name", "bot").put("pass_env", "PW_BOT"))))
        assertEquals("mail › accounts › bot › name" to "mail › passwords › PW_BOT", AccountRuntime.mailPaths(mail, "bot@example.test", "me@example.test"))
        assertEquals("nothing held: the owner's account", "mail › accounts › admin › name" to "mail › passwords › PW_ADMIN", AccountRuntime.mailPaths(mail, "", "me@example.test"))
        assertEquals(null, AccountRuntime.mailPaths(mail, "", ""))
    }
}
