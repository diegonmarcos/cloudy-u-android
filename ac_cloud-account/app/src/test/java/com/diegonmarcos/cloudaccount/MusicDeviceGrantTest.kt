package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.AppCredentialsStore
import com.diegonmarcos.superapp.profile.MusicDeviceGrant
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** #1693 the music device grant, now Cloud Account's: each step over a fake POST, the tokens filed for Cloud Browser, no value in any log form. */
@RunWith(RobolectricTestRunner::class)
class MusicDeviceGrantTest {
    private lateinit var ctx: Context
    private val spec = MusicDeviceGrant.SPECS.getValue("ytmusic")

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_md").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
    }

    @After fun tearDown() { ConfigsPrefs.factory = null }

    @Test fun theCodeAndTheTokensParse() {
        val c = MusicDeviceGrant.parseCode("""{"device_code":"DC","user_code":"ABCD-1234","verification_url":"https://g.example/device","interval":5,"expires_in":900}""")
        assertEquals("ABCD-1234", c.userCode); assertEquals(5L, c.intervalSec)
        val t = MusicDeviceGrant.parseTokens("""{"access_token":"AT","refresh_token":"RT","expires_in":3600}""")
        assertEquals("RT", t.refresh)
        assertFalse(t.toString().contains("AT")); assertFalse(t.toString().contains("RT"))
        assertNull(MusicDeviceGrant.parseTokens("""{"access_token":"AT","expires_in":1}""").refresh)
    }

    @Test fun startSendsTheClientAndTheScope() {
        var sent: Map<String, String>? = null
        MusicDeviceGrant.start({ _, f -> sent = f; """{"device_code":"DC","user_code":"U","verification_uri":"https://v","interval":1,"expires_in":10}""" }, spec, "CID")
        assertEquals("CID", sent!!["client_id"]); assertEquals(spec.scopes, sent!!["scope"])
    }

    @Test fun pollIsNullWhilePendingAndThrowsOnAnyOtherRefusal() {
        val pending = MusicDeviceGrant.Post { _, _ -> throw MusicDeviceGrant.Http(428, """{"error":"authorization_pending"}""") }
        assertNull(MusicDeviceGrant.poll(pending, spec, "c", "s", "DC"))
        assertNull(MusicDeviceGrant.poll({ _, _ -> throw MusicDeviceGrant.Http(400, """{"error":"slow_down"}""") }, spec, "c", "s", "DC"))
        val denied = runCatching { MusicDeviceGrant.poll({ _, _ -> throw MusicDeviceGrant.Http(403, """{"error":"access_denied"}""") }, spec, "c", "s", "DC") }
        assertTrue(denied.exceptionOrNull() is MusicDeviceGrant.Http)
        assertFalse("the error message carries no body", denied.exceptionOrNull()!!.message.orEmpty().contains("access_denied"))
        val sent = HashMap<String, String>()
        assertNotNull(MusicDeviceGrant.poll({ _, f -> sent.putAll(f); """{"access_token":"AT","expires_in":60}""" }, spec, "c", "s", "DC"))
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", sent["grant_type"])
    }

    @Test fun theTokensAreFiledWhereCloudBrowserTakesThem() {
        val v = AccountVault(ctx)
        MusicDeviceGrant.file(v, spec, MusicDeviceGrant.Tokens("AT", "RT", 60), 1_000L)
        assertEquals("AT", AppCredentialsStore.get(v, "music.ytmusic.access"))
        assertEquals("RT", AppCredentialsStore.get(v, "music.ytmusic.refresh"))
        assertEquals("61000", AppCredentialsStore.get(v, "music.ytmusic.expires"))
        MusicDeviceGrant.file(v, spec, MusicDeviceGrant.Tokens("AT2", null, 0), 5L)
        assertEquals("0", AppCredentialsStore.get(v, "music.ytmusic.expires"))
    }

    @Test fun theGrantNeedsTheClientFiledFirst() {
        val out = kotlinx.coroutines.runBlocking { MusicDeviceGrant.run(ctx, spec, onCode = {}) }
        assertTrue(out is MusicDeviceGrant.Outcome.Failed)
    }
}
