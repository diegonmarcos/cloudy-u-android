package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/** #1693 the Mail OAuth methods on a real provider: default deny, the grant, the endpoint checks (no network is touched by a refused request). */
@RunWith(RobolectricTestRunner::class)
class MailOAuthProviderTest {
    private lateinit var ctx: Context
    private val other = "com.example.other"
    private val bad = MailOAuthContract.Request("me@example.org", "mail.example.org", "https://evil.net/device", "https://evil.net/token", "cid", "mail")

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_mop").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { other }
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call(method: String, arg: String?) = Robolectric.buildContentProvider(AccountData.Provider::class.java).create(AccountData.AUTHORITY).get().call(method, arg, null)

    @Test fun bothMethodsNeedTheGrant() {
        for (m in listOf(MailOAuthContract.METHOD_START, MailOAuthContract.METHOD_POLL)) {
            val r = call(m, bad.json())
            assertFalse(m, r.getBoolean(MailOAuthContract.KEY_OK)); assertTrue(r.getString(MailOAuthContract.KEY_ERROR)!!.contains("no grant"))
        }
    }

    @Test fun aGrantedBadRequestIsRefusedAndAPollOfNothingIsExpired() {
        AccountVault(ctx).grants.grant(other, MailOAuthContract.GRANT_REQUEST)
        val s = call(MailOAuthContract.METHOD_START, bad.json())
        assertFalse(s.getBoolean(MailOAuthContract.KEY_OK)); assertTrue(s.getString(MailOAuthContract.KEY_ERROR)!!.contains("refused"))
        val p = call(MailOAuthContract.METHOD_POLL, "unknown-handle")
        assertTrue(p.getBoolean(MailOAuthContract.KEY_OK))
        assertTrue(MailOAuthContract.parsePoll(p.getString(MailOAuthContract.KEY_JSON))!!.error == "expired_token")
    }

    @Test fun aRevokeIsHonouredAtOnce() {
        val v = AccountVault(ctx); v.grants.grant(other, MailOAuthContract.GRANT_REQUEST)
        assertTrue(call(MailOAuthContract.METHOD_POLL, "x").getBoolean(MailOAuthContract.KEY_OK))
        v.grants.revoke(other, MailOAuthContract.GRANT_REQUEST)
        assertFalse(call(MailOAuthContract.METHOD_POLL, "x").getBoolean(MailOAuthContract.KEY_OK))
    }

    // ---- #1703 the authorization-code half ----
    private val badCode = MailOAuthContract.CodeRequest("me@example.org", "mail.example.org", "https://evil.net/authorize", "https://evil.net/token", "cid", "mail", "com.example.other://oauth")
    private val goodCode = MailOAuthContract.CodeRequest("me@example.org", "mail.example.org", "https://auth.example.org/authorize", "https://auth.example.org/token", "cid", "mail", "com.example.other://oauth")

    @Test fun theCodeMethodsNeedTheGrantToo() {
        for (m in listOf(MailOAuthContract.METHOD_CODE_START, MailOAuthContract.METHOD_CODE_FINISH)) {
            val r = call(m, badCode.json())
            assertFalse(m, r.getBoolean(MailOAuthContract.KEY_OK)); assertTrue(r.getString(MailOAuthContract.KEY_ERROR)!!.contains("no grant"))
        }
    }

    @Test fun aGrantedCodeStartForAForeignEndpointOrAnotherAppsSchemeIsRefused() {
        AccountVault(ctx).grants.grant(other, MailOAuthContract.GRANT_REQUEST)
        val s = call(MailOAuthContract.METHOD_CODE_START, badCode.json())
        assertFalse(s.getBoolean(MailOAuthContract.KEY_OK)); assertTrue(s.getString(MailOAuthContract.KEY_ERROR)!!.contains("refused"))
        val scheme = call(MailOAuthContract.METHOD_CODE_START, goodCode.copy(redirectUri = "app.sterna://oauth").json())
        assertFalse("another package's scheme", scheme.getBoolean(MailOAuthContract.KEY_OK))
    }

    @Test fun aGrantedCodeStartOpensARequestAndAFinishOfNothingIsNoPending() {
        AccountVault(ctx).grants.grant(other, MailOAuthContract.GRANT_REQUEST)
        val s = call(MailOAuthContract.METHOD_CODE_START, goodCode.json())
        assertTrue(s.getString(MailOAuthContract.KEY_ERROR), s.getBoolean(MailOAuthContract.KEY_OK))
        assertTrue(MailOAuthContract.parseCodeStart(s.getString(MailOAuthContract.KEY_JSON)) != null)
        val f = call(MailOAuthContract.METHOD_CODE_FINISH, MailOAuthContract.CodeFinish("unknown-handle", "C", "S", null).json())
        assertTrue(f.getBoolean(MailOAuthContract.KEY_OK))
        assertTrue(MailOAuthContract.parsePoll(f.getString(MailOAuthContract.KEY_JSON))!!.error == MailOAuthContract.ERR_NO_PENDING)
    }

    @Test fun aRevokeStopsTheCodeMethodsAtOnce() {
        val v = AccountVault(ctx); v.grants.grant(other, MailOAuthContract.GRANT_REQUEST)
        assertTrue(call(MailOAuthContract.METHOD_CODE_FINISH, MailOAuthContract.CodeFinish("x", null, null, null).json()).getBoolean(MailOAuthContract.KEY_OK))
        v.grants.revoke(other, MailOAuthContract.GRANT_REQUEST)
        assertFalse(call(MailOAuthContract.METHOD_CODE_FINISH, MailOAuthContract.CodeFinish("x", null, null, null).json()).getBoolean(MailOAuthContract.KEY_OK))
    }
}
