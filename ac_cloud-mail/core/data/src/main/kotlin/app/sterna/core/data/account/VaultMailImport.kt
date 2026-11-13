package app.sterna.core.data.account

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The vault's `mail` section, delivered IN TRANSIT by the fleet config contract (cockpit
 * `derived_settings` → `settings › mail › sterna_account › vault_mail`, plus `vault_owner` =
 * about.profile.email) and consumed here on process start, once: every declared account is added
 * through [AccountStore.add] (its password encrypted into its own `pw_<id>` slot by that API), the
 * owner's account is made current, and both keys are REMOVED from the prefs. A re-import writes
 * them again and this runs again. Mirrors the Account lib's rules (VaultCockpit.mailAccounts,
 * mailEndpoints, ownerEmail): an address is `accounts.*.name@<owner's domain>`, its password
 * `passwords[pass_env]` when present, JMAP is `endpoints.domain`, IMAP/SMTP the `l4_ports` SNI
 * names (and their port when named; Sterna's IMAPS/SMTPS defaults otherwise).
 * Logs one line per account — the address only, never a password.
 */
object VaultMailImport {
    const val KEY_MAIL = "vault_mail"
    const val KEY_OWNER = "vault_owner"

    data class Declared(val email: String, val password: String?)
    data class Plan(
        val accounts: List<Declared>, val owner: String, val jmap: String,
        val imap: String, val imapPort: Int, val smtp: String, val smtpPort: Int,
    )

    /** [mailText] as the plan, or null when it holds no account addressed at a known domain. */
    fun parse(mailText: String, owner: String): Plan? {
        val mail = runCatching { JSONObject(mailText) }.getOrNull() ?: return null
        val domain = owner.substringAfter('@', "").trim()
        if (domain.isBlank()) return null
        val accounts = mail.optJSONObject("accounts") ?: return null
        val passwords = mail.optJSONObject("passwords")
        val declared = accounts.keys().asSequence().mapNotNull { key ->
            val a = accounts.optJSONObject(key) ?: return@mapNotNull null
            val local = a.optString("name").trim()
            if (local.isBlank()) return@mapNotNull null
            val pw = passwords?.opt(a.optString("pass_env"))
            Declared("$local@$domain", (pw as? String)?.takeIf { it.isNotBlank() })
        }.toList()
        if (declared.isEmpty()) return null
        val e = mail.optJSONObject("endpoints") ?: JSONObject()
        val ports = e.optJSONArray("l4_ports") ?: JSONArray()
        fun l4(prefix: String): JSONObject? = (0 until ports.length()).mapNotNull { ports.optJSONObject(it) }
            .firstOrNull { it.optString("sni").startsWith(prefix) }
        val imap = l4("imap"); val smtp = l4("smtp")
        return Plan(
            declared, owner.trim(), e.optString("domain").trim(),
            imap?.optString("sni").orEmpty(), imap?.optInt("port", 993)?.takeIf { it > 0 } ?: 993,
            smtp?.optString("sni").orEmpty(), smtp?.optInt("port", 465)?.takeIf { it > 0 } ?: 465,
        )
    }

    /** Consume the keys when present; returns the number of accounts added. */
    fun run(context: Context, store: AccountStore): Int {
        val prefs = context.applicationContext.getSharedPreferences(AccountStore.PREFS_NAME, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_MAIL, null) ?: return 0
        val owner = prefs.getString(KEY_OWNER, null).orEmpty()
        // One-shot: the section never stays in the prefs, whatever the parse says.
        prefs.edit().remove(KEY_MAIL).remove(KEY_OWNER).apply()
        val plan = parse(text, owner) ?: run {
            Log.w(AccountStore.TAG, "vault mail: no declared account at a known domain; nothing added")
            return 0
        }
        if (plan.jmap.isBlank() && plan.imap.isBlank()) {
            Log.w(AccountStore.TAG, "vault mail: no endpoint declared; nothing added")
            return 0
        }
        return addAll(plan, store)
    }

    /**
     * #1693 Every account of [plan] through [AccountStore.add] (or the credential restore of one already stored), the owner's made
     * current; returns how many were added. The one writer for both feeds: the vault section delivered in transit ([run]) and
     * Cloud Account's `mail_accounts` answer (app.sterna.fleet.MailAccountSync).
     */
    fun addAll(plan: Plan, store: AccountStore): Int {
        var added = 0
        var current: String? = null
        for (d in plan.accounts) {
            val existing = store.accounts().firstOrNull { it.username.equals(d.email, ignoreCase = true) }
            val id = when {
                existing != null -> {
                    // A declared account that is already here but cannot authenticate (no secret,
                    // or a blob this phone's Keystore no longer reads) takes the vault's password:
                    // otherwise the app keeps it listed, refuses to make it current, and shows the
                    // first account that CAN log in instead — the owner tapped me@ and saw no-reply@.
                    if (store.credentials(existing.id) == null && d.password != null) {
                        // A vault password is a BASIC credential. An account restored as OAUTH (its
                        // access token expired, no refresh token in the backup) would otherwise file
                        // the password in its refresh-token slot and stay unable to log in — the
                        // "isn't signed in yet" me@ account of 2026-10-08.
                        val login = existing.loginKey()
                        if (store.accounts().firstOrNull { it.id == login }?.authType == AuthType.OAUTH) {
                            store.convertToBasicAuth(login)
                        }
                        store.updatePassword(existing.id, d.password)
                        store.setImportPending(existing.id, false)
                        Log.i(AccountStore.TAG, "vault mail: ${d.email} already stored — credentials restored from the vault")
                    } else Log.i(AccountStore.TAG, "vault mail: ${d.email} already stored")
                    existing.id
                }
                d.password == null -> { Log.i(AccountStore.TAG, "vault mail: ${d.email} has no password in the vault; skipped"); continue }
                else -> store.add(
                    server = plan.jmap, username = d.email, password = d.password, accountName = d.email,
                    protocol = if (plan.jmap.isBlank()) MailProtocol.IMAP else MailProtocol.JMAP,
                    imapHost = plan.imap, imapPort = plan.imapPort, imapSecurity = ConnectionSecurity.TLS,
                    smtpHost = plan.smtp, smtpPort = plan.smtpPort, smtpSecurity = ConnectionSecurity.TLS,
                ).also { added++; Log.i(AccountStore.TAG, "vault mail: ${d.email} added") }
            }
            if (current == null || d.email.equals(plan.owner, ignoreCase = true)) current = id
        }
        current?.let(store::setCurrent)
        return added
    }
}
