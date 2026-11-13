package app.sterna.fleet

import android.content.Context
import android.net.Uri
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.VaultMailImport
import com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract
import com.diegonmarcos.superapp.fleetendpoints.SignInContract

/**
 * #1693 Cloud Mail does not sign its fleet accounts in: Cloud Account holds them (address, password, endpoints) and this pulls them
 * over its provider, through the same writer the vault import uses ([VaultMailImport.addAll]). BLOCKS (a provider call): run it off
 * the main thread. A missing, refusing or older Cloud Account is an [Outcome] with a sentence naming the fix, never a crash, and a
 * password is in no outcome, no log and no message.
 */
object MailAccountSync {
    sealed class Outcome {
        data class Added(val count: Int, val total: Int) : Outcome()
        data object AccountMissing : Outcome()
        /** [line] says why and what to do; never a value. */
        data class Refused(val line: String) : Outcome()
    }

    fun accountInstalled(ctx: Context): Boolean = MailEndpointSync.accountInstalled(ctx)

    /** The sign-in link: opens Cloud Account at the mail provider's page; null when it opened, else the reason. */
    fun openCloudAccount(ctx: Context): String? {
        if (!accountInstalled(ctx)) return SignInContract.missing(MailAccountsContract.PROVIDER).line
        val i = android.content.Intent(SignInContract.ACTION_OPEN).setPackage(SignInContract.ACCOUNT_PACKAGE)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        for ((k, v) in SignInContract.openIntentExtras(MailAccountsContract.PROVIDER)) i.putExtra(k, v)
        return runCatching { ctx.startActivity(i); null }.getOrElse { "Cloud Account cannot be opened (${it.javaClass.simpleName})" }
    }

    fun pull(ctx: Context, store: AccountStore): Outcome {
        if (!accountInstalled(ctx)) return Outcome.AccountMissing
        val reply = runCatching {
            ctx.contentResolver.call(Uri.parse("content://${MailAccountsContract.AUTHORITY}"), MailAccountsContract.METHOD, null, null)
        }.getOrNull() ?: return Outcome.Refused("Cloud Account did not answer: update it or open it once")
        if (!reply.getBoolean(MailAccountsContract.KEY_OK)) return Outcome.Refused(MailAccountsContract.refusal(reply.getString(MailAccountsContract.KEY_ERROR)))
        val p = MailAccountsContract.parse(reply.getString(MailAccountsContract.KEY_JSON))
            ?: return Outcome.Refused("Cloud Account answered something that is not a mail account list: update Cloud Mail")
        if (p.jmap.isBlank() && p.imap.isBlank()) return Outcome.Refused("Cloud Account names no mail server: import the vault there")
        return Outcome.Added(VaultMailImport.addAll(planOf(p), store), p.accounts.size)
    }

    /** What the vault import's writer takes, from what Cloud Account published. */
    fun planOf(p: MailAccountsContract.Published) = VaultMailImport.Plan(
        p.accounts.map { VaultMailImport.Declared(it.email, it.password) }, p.owner, p.jmap, p.imap, p.imapPort, p.smtp, p.smtpPort,
    )
}
