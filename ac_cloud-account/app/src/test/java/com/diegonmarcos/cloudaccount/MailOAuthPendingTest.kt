package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.AppCredentialsStore
import com.diegonmarcos.superapp.profile.MailOAuthPending
import com.diegonmarcos.superapp.profile.MusicDeviceGrant
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/** #1693 Cloud Mail's OAuth sign-in, run here: default-deny request over the provider, endpoint checks, the device grant filing the tokens Mail takes. */
@RunWith(RobolectricTestRunner::class)
class MailOAuthPendingTest {
    private lateinit var ctx: Context
    private val other = "com.example.other"
    private val req = MailOAuthContract.Request("me@example.org", "mail.example.org", "https://auth.example.org/device", "https://auth.example.org/token", "cid", "mail")

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_mo").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { other }
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call(arg: String?) = Robolectric.buildContentProvider(AccountData.Provider::class.java)
        .create(AccountData.AUTHORITY).get().call(MailOAuthContract.METHOD_REQUEST, arg, null)

    @Test fun aRequestNeedsTheGrantAndAGoodShape() {
        val v = AccountVault(ctx)
        assertFalse(call(req.json()).getBoolean(MailOAuthContract.KEY_OK))
        v.grants.grant(other, MailOAuthContract.GRANT_REQUEST)
        assertTrue(call(req.json()).getBoolean(MailOAuthContract.KEY_OK))
        assertEquals(listOf(req), MailOAuthPending.list(v))
        assertFalse("a foreign endpoint is refused", call(req.copy(email = "x@other.org", tokenEndpoint = "https://evil.net/t").json()).getBoolean(MailOAuthContract.KEY_OK))
        assertEquals(1, MailOAuthPending.list(v).size)
        MailOAuthPending.remove(v, req.email); assertTrue(MailOAuthPending.list(v).isEmpty())
    }

    @Test fun theDeviceGrantFilesTheTokensMailTakesAndDropsTheRequest() {
        val v = AccountVault(ctx)
        assertTrue(MailOAuthPending.put(v, req.json()))
        var shown: MusicDeviceGrant.Code? = null
        val post = MusicDeviceGrant.Post { url, f ->
            when (url) {
                req.deviceEndpoint -> { assertEquals("cid", f["client_id"]); """{"device_code":"DC","user_code":"UC","verification_uri":"https://auth.example.org/v","interval":0,"expires_in":60}""" }
                else -> { assertFalse("a public client sends no secret", f.containsKey("client_secret")); """{"access_token":"AT","refresh_token":"RT","expires_in":3600}""" }
            }
        }
        val out = runBlocking { MailOAuthPending.run(ctx, req, post, nowMs = { 1_000L }) { shown = it } }
        assertTrue(out.toString(), out is MusicDeviceGrant.Outcome.Connected)
        assertEquals("UC", shown!!.userCode)
        assertEquals("AT", AppCredentialsStore.get(v, MailOAuthContract.credId(req.email, MailOAuthContract.FIELD_ACCESS)))
        assertEquals("RT", AppCredentialsStore.get(v, MailOAuthContract.credId(req.email, MailOAuthContract.FIELD_REFRESH)))
        assertEquals("3601000", AppCredentialsStore.get(v, MailOAuthContract.credId(req.email, MailOAuthContract.FIELD_EXPIRES)))
        assertTrue(MailOAuthPending.list(v).isEmpty())
    }

    @Test fun aRefusalNamesNoBody() {
        val post = MusicDeviceGrant.Post { _, _ -> throw MusicDeviceGrant.Http(400, """{"error":"invalid_client","detail":"SECRETBODY"}""") }
        val out = runBlocking { MailOAuthPending.run(ctx, req, post) {} } as MusicDeviceGrant.Outcome.Failed
        assertFalse(out.why.contains("SECRETBODY")); assertTrue(out.why.contains("400"))
    }
}
