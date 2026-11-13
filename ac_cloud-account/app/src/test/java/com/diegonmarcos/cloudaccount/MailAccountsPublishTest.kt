package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.ProfilePrefs
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
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

/**
 * #1693 Cloud Account handing the fleet's mail accounts to Cloud Mail over its provider, on a real provider under Robolectric:
 * default deny (the `mail.accounts` grant), the declared accounts come out with their passwords and the endpoints, a revoke is
 * honoured at once, an empty vault is a refusal that names the fix, and the passwords are in no error and no log form.
 */
@RunWith(RobolectricTestRunner::class)
class MailAccountsPublishTest {
    private lateinit var ctx: Context
    private val other = "com.example.other"

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_ma").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { other }
        ProfilePrefs(ctx).email = "me@example.org"
        AccountVault(ctx).apply {
            putConnection("mail.endpoints.domain", "jmap.example.org")
            putConnection("mail.accounts.me.name", "me"); putConnection("mail.accounts.me.pass_env", "ME_PW")
            putConnection("mail.accounts.bot.name", "bot"); putConnection("mail.accounts.bot.pass_env", "BOT_PW")
            putConnection("mail.passwords.ME_PW", "S3CRET-me")
        }
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call() = Robolectric.buildContentProvider(AccountData.Provider::class.java)
        .create(AccountData.AUTHORITY).get().call(MailAccountsContract.METHOD, null, null)

    @Test fun aPackageNeedsTheMailAccountsGrant() {
        val denied = call()
        assertFalse(denied.getBoolean(MailAccountsContract.KEY_OK))
        assertFalse("a refusal never carries a password", denied.toString().contains("S3CRET"))
        AccountVault(ctx).grants.grant(other, MailAccountsContract.GRANT_READ)
        assertTrue(call().getBoolean(MailAccountsContract.KEY_OK))
    }

    @Test fun everyDeclaredAccountComesOutWithItsPasswordWhenTheVaultHasOne() {
        AccountVault(ctx).grants.grant(other, MailAccountsContract.GRANT_READ)
        val p = MailAccountsContract.parse(call().getString(MailAccountsContract.KEY_JSON))!!
        assertEquals("me@example.org", p.owner)
        assertEquals("jmap.example.org", p.jmap)
        assertEquals(listOf("bot@example.org", "me@example.org"), p.accounts.map { it.email }.sorted())
        assertEquals("S3CRET-me", p.accounts.first { it.email == "me@example.org" }.password)
        assertEquals(null, p.accounts.first { it.email == "bot@example.org" }.password)
        assertFalse(p.toString().contains("S3CRET"))
    }

    @Test fun aRevokeIsHonouredAtOnce() {
        val v = AccountVault(ctx)
        v.grants.grant(other, MailAccountsContract.GRANT_READ)
        assertTrue(call().getBoolean(MailAccountsContract.KEY_OK))
        v.grants.revoke(other, MailAccountsContract.GRANT_READ)
        assertFalse(call().getBoolean(MailAccountsContract.KEY_OK))
    }

    @Test fun anEmptyVaultIsARefusalThatNamesTheFix() {
        AccountVault(ctx).grants.grant(other, MailAccountsContract.GRANT_READ)
        ProfilePrefs(ctx).email = ""
        val r = call()
        assertFalse(r.getBoolean(MailAccountsContract.KEY_OK))
        assertTrue(r.getString(MailAccountsContract.KEY_ERROR)!!.contains("vault"))
    }

    @Test fun theMailAppIsSeededWithTheGrantOnceAndARevokeSticks() {
        val v = AccountVault(ctx)
        v.prefs.putText("grants_seeded", "7")
        v.seedDefaults()
        assertTrue(v.grants.allow(AccountVault.MAIL_PACKAGE, MailAccountsContract.GRANT_READ))
        v.grants.revoke(AccountVault.MAIL_PACKAGE, MailAccountsContract.GRANT_READ); v.seedDefaults()
        assertFalse(v.grants.allow(AccountVault.MAIL_PACKAGE, MailAccountsContract.GRANT_READ))
    }
}
