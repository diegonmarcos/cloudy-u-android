package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.AppCredentialsStore
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
 * #1693 the App credentials, on a real provider under Robolectric: typed in Cloud Account, read by an app only with the
 * per-credential grant, default deny, a revoke honoured at once, unknown ids refused, no value in any error, the seeded
 * defaults for Drive and KG Store, and a host name with dots filed under one flat key.
 */
@RunWith(RobolectricTestRunner::class)
class AppCredentialsProviderTest {
    private lateinit var ctx: Context
    private val other = "com.example.other"
    private val id = "git.github_token"

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("vault_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("vault_test", Context.MODE_PRIVATE) }
        val dir = File(ctx.filesDir, "account_test_ac").also { it.deleteRecursively() }
        AccountVault.storeSource = { AccountStore(dir, AccountStore.PlainIo) }
        AccountData.callerResolver = { other }
    }

    @After fun tearDown() {
        ConfigsPrefs.factory = null
        AccountData.callerResolver = { c -> c.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.firstOrNull() ?: c.packageName }
    }

    private fun call(arg: String?) = Robolectric.buildContentProvider(AccountData.Provider::class.java)
        .create(AccountData.AUTHORITY).get().call(AppCredentialsContract.METHOD, arg, null)

    @Test fun aPackageNeedsTheGrantForThatCredential() {
        val v = AccountVault(ctx)
        assertTrue(AppCredentialsStore.put(v, id, "ghp_SECRET"))
        val denied = call(id)
        assertFalse(denied.getBoolean(AppCredentialsContract.KEY_OK))
        assertFalse("a refusal never carries the value", denied.toString().contains("ghp_SECRET"))
        v.grants.grant(other, AppCredentialsContract.grant(id))
        assertEquals("ghp_SECRET", call(id).getString(AppCredentialsContract.KEY_VALUE))
        v.grants.revoke(other, AppCredentialsContract.grant(id))
        assertFalse(call(id).getBoolean(AppCredentialsContract.KEY_OK))
    }

    @Test fun aGrantForOneCredentialDoesNotOpenAnother() {
        val v = AccountVault(ctx)
        AppCredentialsStore.put(v, id, "ghp_SECRET"); AppCredentialsStore.put(v, "git.host.git.example.org", "HOSTTOKEN")
        v.grants.grant(other, AppCredentialsContract.grant(id))
        assertFalse(call("git.host.git.example.org").getBoolean(AppCredentialsContract.KEY_OK))
    }

    @Test fun anUnknownIdAndAnEmptyValueAreRefused() {
        val v = AccountVault(ctx)
        v.grants.grant(other, "cred.*")
        assertFalse(call("nope").getBoolean(AppCredentialsContract.KEY_OK))
        assertFalse(call(null).getBoolean(AppCredentialsContract.KEY_OK))
        assertFalse(AppCredentialsStore.put(v, id, "  "))
        assertFalse(AppCredentialsStore.put(v, "nope", "x"))
        val none = call(id)
        assertTrue("nothing filed is ok with an empty value, so the app can say how to fill it", none.getBoolean(AppCredentialsContract.KEY_OK))
        assertEquals("", none.getString(AppCredentialsContract.KEY_VALUE))
    }

    @Test fun aHostWithDotsIsFiledAndListedAndCleared() {
        val v = AccountVault(ctx)
        assertTrue(AppCredentialsStore.put(v, "git.host.git.example.org", "T"))
        assertEquals("T", AppCredentialsStore.get(v, "git.host.git.example.org"))
        assertTrue(AppCredentialsStore.ids(v).contains("git.host.git.example.org"))
        assertTrue(AppCredentialsStore.remove(v, "git.host.git.example.org"))
        assertEquals("", AppCredentialsStore.get(v, "git.host.git.example.org"))
        assertFalse(AppCredentialsStore.ids(v).contains("git.host.git.example.org"))
    }

    @Test fun driveAndKgStoreAreSeededOnceAndARevokeSticks() {
        val v = AccountVault(ctx)
        v.prefs.putText("grants_seeded", "8")
        v.seedDefaults()
        assertTrue(v.grants.allow(AccountVault.DRIVE_PACKAGE, "cred.git.host.git.example.org"))
        assertTrue(v.grants.allow("com.diegonmarcos.cloudkgstore", "cred.git.github_token"))
        assertFalse(v.grants.allow("com.diegonmarcos.cloudkgstore", "cred.git.host.git.example.org"))
        assertTrue(v.grants.allow(AccountVault.DRIVE_PACKAGE, "cred.git.repo.myrepo"))
        assertTrue(v.grants.allow(AccountVault.DRIVE_PACKAGE, "cred.git.ssh.myrepo"))
        assertTrue(v.grants.allow("com.diegonmarcos.cloudagenda", "cred.tasks.caldav_password"))
        assertTrue(v.grants.allow("com.diegonmarcos.cloudagenda", "cred.tasks.jmap_secret"))
        assertTrue(v.grants.allow("com.diegonmarcos.superapp", "cred.mattermost.password"))
        assertTrue(v.grants.allow("com.diegonmarcos.superapp", "cred.mattermost.login"))
        assertTrue(v.grants.allow("com.diegonmarcos.superapp", "cred.dagu.token"))
        assertTrue(v.grants.allow("com.diegonmarcos.cloudc3", "cred.dagu.token"))
        assertTrue(v.grants.allow("com.diegonmarcos.cloudme", "cred.me.buro_backup_passphrase"))
        assertTrue(v.grants.allow("com.diegonmarcos.cloudbrowser", "cred.music.ytmusic.access"))
        assertTrue(v.grants.allow(AccountVault.MAIL_PACKAGE, "cred.mail.password.me_example_org"))
        assertTrue(v.grants.allow(AccountVault.MAIL_PACKAGE, "cred.mail.token.me_example_org"))
        assertTrue(v.grants.allow(AccountVault.MAIL_PACKAGE, "cred.mail.gateway_token"))
        assertFalse("Tasks is granted only its own", v.grants.allow("com.diegonmarcos.cloudagenda", "cred.git.github_token"))
        v.grants.revoke(AccountVault.DRIVE_PACKAGE, "cred.git.github_token"); v.seedDefaults()
        assertFalse(v.grants.allow(AccountVault.DRIVE_PACKAGE, "cred.git.github_token"))
    }
}
