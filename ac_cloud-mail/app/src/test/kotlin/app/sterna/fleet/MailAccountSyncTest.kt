package app.sterna.fleet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import app.sterna.core.data.account.AccountStore
import com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1693 The pull of the fleet's mail accounts from Cloud Account, on a stand-in for its provider: the plan the writer takes is what
 * Cloud Account published (passwords included, nowhere printed), and a missing, refusing or unreadable Cloud Account is a sentence
 * that names the fix and carries no value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MailAccountSyncTest {
    class FakeAccount : ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle { calls += method; return if (method == MailAccountsContract.METHOD) reply else Bundle() }
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(u: Uri): String? = null
        override fun insert(u: Uri, v: ContentValues?): Uri? = null
        override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
        companion object { var reply: Bundle = Bundle(); val calls = mutableListOf<String>() }
    }

    private lateinit var ctx: Context
    private val secret = "S3CRET-mail-pw"

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        FakeAccount.calls.clear(); FakeAccount.reply = Bundle()
        Robolectric.buildContentProvider(FakeAccount::class.java).create(MailAccountsContract.AUTHORITY)
    }

    private fun installAccount() = shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
        packageName = MailAccountsContract.ACCOUNT_PACKAGE
        applicationInfo = ApplicationInfo().apply { packageName = MailAccountsContract.ACCOUNT_PACKAGE }
    })

    private fun refused(why: String) = Bundle().apply { putBoolean(MailAccountsContract.KEY_OK, false); putString(MailAccountsContract.KEY_ERROR, why) }

    @Test fun `no Cloud Account installed is its own outcome and asks nothing`() {
        assertEquals(MailAccountSync.Outcome.AccountMissing, MailAccountSync.pull(ctx, AccountStore(ctx)))
        assertTrue(FakeAccount.calls.isEmpty())
        assertTrue(MailAccountSync.openCloudAccount(ctx)!!.contains("not installed"))
    }

    @Test fun `a refusal is a sentence that names the reason and carries no password`() {
        installAccount(); FakeAccount.reply = refused("no grant for com.diegonmarcos.comms.mail")
        val out = MailAccountSync.pull(ctx, AccountStore(ctx)) as MailAccountSync.Outcome.Refused
        assertTrue(out.line, out.line.contains("no grant"))
        assertFalse(out.line.contains(secret))
    }

    @Test fun `an unreadable answer or one naming no server changes nothing`() {
        installAccount()
        FakeAccount.reply = Bundle().apply { putBoolean(MailAccountsContract.KEY_OK, true); putString(MailAccountsContract.KEY_JSON, "garbage") }
        assertTrue(MailAccountSync.pull(ctx, AccountStore(ctx)) is MailAccountSync.Outcome.Refused)
        val noServer = MailAccountsContract.toJson(MailAccountsContract.Published("me@example.org", "", "", 993, "", 465, listOf(MailAccountsContract.Account("me@example.org", secret))))
        FakeAccount.reply = Bundle().apply { putBoolean(MailAccountsContract.KEY_OK, true); putString(MailAccountsContract.KEY_JSON, noServer) }
        val out = MailAccountSync.pull(ctx, AccountStore(ctx)) as MailAccountSync.Outcome.Refused
        assertFalse(out.line.contains(secret))
    }

    @Test fun `the writer's plan is exactly what Cloud Account published`() {
        val p = MailAccountsContract.Published("me@example.org", "jmap.example.org", "imap.example.org", 993, "smtp.example.org", 465,
            listOf(MailAccountsContract.Account("me@example.org", secret), MailAccountsContract.Account("bot@example.org", null)))
        val plan = MailAccountSync.planOf(p)
        assertEquals("me@example.org", plan.owner); assertEquals("jmap.example.org", plan.jmap)
        assertEquals(listOf("me@example.org", "bot@example.org"), plan.accounts.map { it.email })
        assertEquals(secret, plan.accounts[0].password); assertEquals(null, plan.accounts[1].password)
        assertEquals(993, plan.imapPort); assertEquals(465, plan.smtpPort)
    }
}
