package app.sterna.fleet

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient

/**
 * #1693 Cloud Mail types no mailbox password, API token or gateway token: they are entered once in Cloud Account (App credentials) and read
 * from there over the provider's per-credential grant. The ids are pure (JVM-tested); a read BLOCKS (run it off the main thread), never
 * throws, and the value is in no line, no log and no state beyond the field that asked for it.
 */
object CloudSecrets {
    /** `mail.password.<address>`: the IMAP / JMAP password of one mailbox. */
    fun passwordId(address: String) = "mail.password." + AppCredentialsContract.slug(address.trim().lowercase())
    /** `mail.token.<address>`: the API token of one JMAP-token mailbox. */
    fun tokenId(address: String) = "mail.token." + AppCredentialsContract.slug(address.trim().lowercase())
    /** The gateway (fleet) bearer token of the Account ▸ Authentication section. */
    const val GATEWAY_TOKEN_ID = "mail.gateway_token"

    sealed class Taken {
        data class Value(val secret: String) : Taken() { override fun toString() = "Value(<redacted>)" }
        /** [line] says why and what to do; never a value. */
        data class Missing(val line: String) : Taken()
    }

    /** The pure rule over a read: a present value, else the sentence naming the fix. */
    fun taken(c: AppCredentialsContract.Credential): Taken = if (c.present) Taken.Value(c.value) else Taken.Missing(c.line)

    /** An empty address cannot name a credential: say so instead of asking Cloud Account for `mail.password.`. */
    fun needAddress(address: String): Taken.Missing? = if (address.isBlank()) Taken.Missing("Enter the email address first: the password is filed under it in Cloud Account") else null

    fun read(ctx: Context, id: String, label: String): Taken = runCatching { taken(AccountClient.credential(ctx, id, label)) }.getOrElse { Taken.Missing("Cloud Account read failed") }

    fun open(ctx: Context): String? = AccountClient.openCredentials(ctx)
}
