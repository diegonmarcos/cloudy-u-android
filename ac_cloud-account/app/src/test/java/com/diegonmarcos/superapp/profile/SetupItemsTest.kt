package com.diegonmarcos.superapp.profile

import android.app.Application
import com.diegonmarcos.superapp.profile.VaultCockpit.State as S
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #695 — Configs ▸ Account ▸ Cloud Constellation Setup: every item states
 * applied / not applied, measured against what the device holds, never assumed.
 * Expectations are walks of the fixture, never typed twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SetupItemsTest {

    private val accounts = linkedMapOf("admin" to "me", "noreply" to "no-reply", "yo" to "yo", "blank" to "")

    private fun bundle(): JSONObject = JSONObject()
        .put("mail", JSONObject()
            .put("accounts", JSONObject().apply {
                accounts.forEach { (k, name) -> put(k, JSONObject().put("name", name).put("pass_env", "PW_$k")) }
            })
            .put("passwords", JSONObject().put("PW_admin", "pw-admin"))
            .put("endpoints", JSONObject().put("domain", "jmap.example.test")))
        .put("about", JSONObject().put("profile", JSONObject()
            .put("name", "Test Person").put("company", "Acme").put("titles_v2", "")))

    @Test fun `every declared account with a name is an item, addressed at the signed-in domain`() {
        val got = VaultCockpit.mailAccounts(bundle(), "example.test")
        val want = accounts.filterValues { it.isNotBlank() }.map { (k, n) -> k to "$n@example.test" }
        assertEquals(want, got.map { it.account to it.email })
        assertEquals("pw-admin", got.first { it.account == "admin" }.password)
        assertTrue(got.filter { it.account != "admin" }.all { it.password == null })
        assertTrue("no domain known → no address is composed", VaultCockpit.mailAccounts(bundle(), "").isEmpty())
    }

    @Test fun `only the account the device's mail holds reads applied`() {
        val list = VaultCockpit.mailAccounts(bundle(), "example.test")
        assertTrue(VaultCockpit.mailAccountRows(list, "").all { it.state == S.ABSENT })
        val rows = VaultCockpit.mailAccountRows(list, list[1].email)
        assertEquals(listOf(list[1].email), rows.filter { it.state == S.MATCH }.map { it.label })
        assertTrue(rows.filter { it.label != list[1].email }.all { it.state == S.DIFFERS })
        assertTrue("a password never reaches an item", rows.none { it.declared.contains("pw-admin") || it.device.contains("pw-admin") })
    }

    @Test fun `about compares each declared field and applies only what the vault carries`() {
        val fields = linkedMapOf("name" to "name", "company" to "company", "titles" to "titles_v2", "nickname" to "nickname")
        val device = mutableMapOf("name" to "Test Person", "company" to "", "titles" to "old")
        val rows = VaultCockpit.aboutRows(bundle(), fields) { device[it] }
        assertEquals(fields.keys.toList(), rows.map { it.label })
        assertEquals(S.MATCH, rows.first { it.label == "name" }.state)
        assertEquals(S.ABSENT, rows.first { it.label == "company" }.state)
        assertEquals("the vault carries no titles_v2 value → pending, not a mismatch",
            S.PENDING, rows.first { it.label == "titles" }.state)
        assertEquals("a field the device does not have → pending, said, not skipped",
            S.PENDING, rows.first { it.label == "nickname" }.state)
        val written = VaultCockpit.applyAbout(bundle(), fields) { f, v -> if (f in device) { device[f] = v; true } else false }
        assertEquals(listOf("name", "company"), written)
        assertEquals("Acme", device["company"])
        assertEquals("old", device["titles"])
    }
}
