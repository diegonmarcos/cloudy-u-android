package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONArray

/**
 * #1693 What Cloud Account hands to Cloud Mail so the app never asks its owner to sign in again for a fleet account:
 * the vault's declared mail accounts (address + password), the owner, and the JMAP / IMAP / SMTP endpoints, derived with the
 * SAME rules the Fleet apply and the vault import use ([VaultCockpit.mailAccounts], [VaultCockpit.mailEndpoints], `l4_ports`
 * for the ports). The caller was checked for [MailAccountsContract.GRANT_READ] by the provider; nothing here logs a value.
 */
object MailAccountsPublisher {

    fun build(ctx: Context, vault: AccountVault = AccountVault(ctx)): MailAccountsContract.Published {
        val bundle = vault.connections()
        val owner = ConfigsPrefs(ctx).autheliaEmail.ifBlank { ProfilePrefs(ctx).email.trim() }
        val declared = VaultCockpit.mailAccounts(bundle, owner.substringAfter('@', ""))
        val e = VaultCockpit.mailEndpoints(bundle)
        val ports = bundle.optJSONObject("mail")?.optJSONObject("endpoints")?.optJSONArray("l4_ports") ?: JSONArray()
        fun port(prefix: String, fallback: Int): Int = (0 until ports.length()).mapNotNull { ports.optJSONObject(it) }
            .firstOrNull { it.optString("sni").startsWith(prefix) }?.optInt("port", fallback)?.takeIf { it > 0 } ?: fallback
        return MailAccountsContract.Published(owner.trim(), e.jmap, e.imap, port("imap", 993), e.smtp, port("smtp", 465),
            declared.map { MailAccountsContract.Account(it.email, it.password) })
    }

    fun publish(ctx: Context, vault: AccountVault = AccountVault(ctx)): Bundle {
        val p = build(ctx, vault)
        return if (p.accounts.isEmpty()) Bundle().apply {
            putBoolean(MailAccountsContract.KEY_OK, false)
            putString(MailAccountsContract.KEY_ERROR, "no mail account is declared in the vault: import the vault in Cloud Account first")
        } else Bundle().apply {
            putBoolean(MailAccountsContract.KEY_OK, true)
            putString(MailAccountsContract.KEY_JSON, MailAccountsContract.toJson(p))
        }
    }
}
