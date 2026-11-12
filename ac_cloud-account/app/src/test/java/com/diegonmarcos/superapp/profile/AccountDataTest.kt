package com.diegonmarcos.superapp.profile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #867 — the data Cloud Account hands to SuperApp, asserted on what the copy does to the stores:
 * the profile survives the typed round trip, and nothing the receiver already holds is overwritten.
 * (The provider's signature gate and the never-overwrite guards on configs and slots are source facts,
 * held by ac_cloud-account/test/test-account-app.sh; the configs blob is Keystore-backed, which Robolectric cannot host.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountDataTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun sp(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun `profile prefs survive the typed round trip`() {
        val src = sp("src").also {
            it.edit().putString("name", "Ada").putBoolean("b", true).putInt("i", 7).putLong("l", 1L shl 40).putFloat("f", 1.5f).commit()
        }
        val json = AccountData.profileJson(src.all).toString()
        val dst = sp("dst")
        assertTrue(AccountData.applyProfile(dst, json))
        assertEquals("Ada", dst.getString("name", null))
        assertEquals(true, dst.getBoolean("b", false))
        assertEquals(7, dst.getInt("i", 0))
        assertEquals(1L shl 40, dst.getLong("l", 0))
        assertEquals(1.5f, dst.getFloat("f", 0f), 0f)
    }

    @Test
    fun `a profile the user already filled is never overwritten`() {
        val json = AccountData.profileJson(mapOf("name" to "Remote")).toString()
        val dst = sp("dst2").also { it.edit().putString("email", "me@example.org").commit() }
        assertFalse(AccountData.applyProfile(dst, json))
        assertEquals(null, dst.getString("name", null))
    }

    @Test
    fun `generated keys alone do not count as a filled profile`() {
        val json = AccountData.profileJson(mapOf("name" to "Remote")).toString()
        val dst = sp("dst3").also { it.edit().putInt("schema_version", 2).putString("install_id", "x").commit() }
        assertTrue(AccountData.applyProfile(dst, json))
        assertEquals("Remote", dst.getString("name", null))
    }
}
