package com.diegonmarcos.superapp.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The legacy device id migration: pure, the alias table comes from the declaration handed in.
 *  Robolectric only for org.json, which the plain JVM android.jar stubs throw on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountDeviceAliasTest {
    private fun b64(json: String) = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
    private val aliases = AccountDevice.aliases(b64("""{"device_aliases":{"old":"new-id"}}"""))

    @Test fun aliasesAreReadFromTheDeclaration() {
        assertEquals(mapOf("old" to "new-id"), aliases)
        assertEquals(emptyMap<String, String>(), AccountDevice.aliases(b64("{}")))
        assertEquals(emptyMap<String, String>(), AccountDevice.aliases("not base64 !"))
    }

    @Test fun legacyIdMigratesOnlyWhenTheVaultNoLongerListsItAndListsTheTarget() {
        assertEquals("new-id", AccountDevice.migrated("old", setOf("new-id", "DEFAULT"), aliases))
        assertNull(AccountDevice.migrated("old", setOf("old", "new-id"), aliases))   // old still exists: keep it
        assertNull(AccountDevice.migrated("old", setOf("other"), aliases))           // target not listed: keep it
        assertNull(AccountDevice.migrated("old", emptySet(), aliases))               // listing unknown: keep it
        assertNull(AccountDevice.migrated("fine", setOf("new-id"), aliases))         // not an aliased id
        assertNull(AccountDevice.migrated("", setOf("new-id"), aliases))
    }
}
