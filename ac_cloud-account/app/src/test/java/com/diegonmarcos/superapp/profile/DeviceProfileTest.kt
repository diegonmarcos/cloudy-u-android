package com.diegonmarcos.superapp.profile

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cloud Account redesign 5.1 / tester 7: a device file never carries a secret value. A literal in
 * a secret-class key is refused on build and on parse (import), named by its key; capture masks
 * it to "@vault:<path>"; plan (file -> runtime) carries the config-class keys only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceProfileTest {

    /** mail's `password` and any `*token` key are secret; the rest is config. */
    private val cls = DeviceProfile.SecretClass { _, _, key -> key == "password" || key.endsWith("token") }

    private fun settings(password: Any?) = JSONObject().put("mail", JSONObject().put("prefs", JSONObject()
        .put("mail_prefs", JSONObject().put("host", "imap.test").put("password", password ?: JSONObject.NULL)
            .put("_types", JSONObject().put("host", "string")))))

    private val device = JSONObject().put("id", "galaxy").put("model", "SM-TEST").put("android", 34)

    @Test fun build_refuses_a_secret_literal_naming_the_key() {
        val e = runCatching { DeviceProfile.build(device, JSONObject(), settings("hunter2"), cls) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("mail.prefs.mail_prefs.password"))
        assertFalse(e.message!!.contains("hunter2"))
    }

    @Test fun mask_replaces_the_literal_with_a_vault_reference_and_then_builds() {
        val masked = DeviceProfile.mask(settings("hunter2"), cls)
        val v = masked.getJSONObject("mail").getJSONObject("prefs").getJSONObject("mail_prefs")
        assertEquals("@vault:settings.mail.prefs.mail_prefs.password", v.getString("password"))
        assertEquals("imap.test", v.getString("host"))
        val p = DeviceProfile.build(device, JSONObject(), masked, cls)
        assertFalse(DeviceProfile.text(p).contains("hunter2"))
    }

    @Test fun parse_refuses_a_secret_literal_and_a_wrong_kind() {
        val bad = JSONObject().put("kind", DeviceProfile.KIND).put("schema", 1).put("settings", settings("hunter2")).toString()
        val r = DeviceProfile.parse(bad, cls)
        assertTrue(r is DeviceProfile.Parsed.Refused && r.reason.contains("mail.prefs.mail_prefs.password"))
        assertTrue(DeviceProfile.parse("{\"kind\":\"other\"}", cls) is DeviceProfile.Parsed.Refused)
        val ok = DeviceProfile.text(DeviceProfile.build(device, JSONObject(), DeviceProfile.mask(settings("x"), cls), cls))
        assertTrue(DeviceProfile.parse(ok, cls) is DeviceProfile.Parsed.Ok)
    }

    @Test fun empty_and_null_secrets_are_not_literals() {
        assertTrue(DeviceProfile.secretViolations(settings(""), cls).isEmpty())
        assertTrue(DeviceProfile.secretViolations(settings(null), cls).isEmpty())
    }

    @Test fun plan_carries_config_keys_only() {
        val p = DeviceProfile.build(device, JSONObject(), DeviceProfile.mask(settings("x"), cls), cls)
        val f = DeviceProfile.plan(p, cls).getJSONObject("mail").getJSONObject("prefs").getJSONObject("mail_prefs")
        assertTrue(f.has("host"))
        assertFalse(f.has("password"))
        assertTrue(f.has("_types"))
    }

    @Test fun key_names_never_values() {
        val names = DeviceVault.keyNames(settings("hunter2")).toString()
        assertTrue(names.contains("mail.prefs.mail_prefs.password"))
        assertFalse(names.contains("hunter2"))
        assertFalse(names.contains("imap.test"))
    }
}
