package com.diegonmarcos.superapp.fleetendpoints

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1693 THE MAIL ACCOUNTS CONTRACT: the fleet's own mail accounts live in Cloud Account's vault, and Cloud Mail takes them
 * from there instead of asking its owner to sign in again. Wire: `content://<account package>.accountdata`, method
 * [METHOD], JSON under [KEY_JSON]:
 *   {"v":1,"owner","jmap","imap","imap_port","smtp","smtp_port","accounts":[{"email","password"?}]}
 * Default deny: a caller needs the [GRANT_READ] grant (Secrets in Cloud Account, revocable) on top of the fleet signature
 * permission the provider checks first. The passwords ride only this answer: [Published.toString] and
 * [Account.toString] hide them, so an interpolated log line cannot write one out. Pure over org.json: both ends share it.
 */
object MailAccountsContract {
    const val VERSION = 1
    const val ACCOUNT_PACKAGE = FleetEndpointsContract.ACCOUNT_PACKAGE
    const val AUTHORITY = FleetEndpointsContract.AUTHORITY
    const val METHOD = "mail_accounts"
    const val GRANT_READ = "mail.accounts"
    const val KEY_OK = FleetEndpointsContract.KEY_OK
    const val KEY_ERROR = FleetEndpointsContract.KEY_ERROR
    const val KEY_JSON = "mail_accounts_json"
    /** The provider name Cloud Account's sign-in pages and state answer to for fleet mail. */
    const val PROVIDER = "mail"

    data class Account(val email: String, val password: String?) {
        override fun toString() = "Account(email=$email, password=${if (password.isNullOrBlank()) "none" else "<redacted>"})"
    }

    data class Published(
        val owner: String, val jmap: String, val imap: String, val imapPort: Int, val smtp: String, val smtpPort: Int,
        val accounts: List<Account>,
    ) {
        override fun toString() = "Published(owner=$owner, jmap=$jmap, imap=$imap:$imapPort, smtp=$smtp:$smtpPort, accounts=$accounts)"
    }

    fun toJson(p: Published): String = JSONObject().put("v", VERSION).put("owner", p.owner).put("jmap", p.jmap)
        .put("imap", p.imap).put("imap_port", p.imapPort).put("smtp", p.smtp).put("smtp_port", p.smtpPort)
        .put("accounts", JSONArray().also { a ->
            for (x in p.accounts) a.put(JSONObject().put("email", x.email).also { o -> if (!x.password.isNullOrBlank()) o.put("password", x.password) })
        }).toString()

    /** The document, or null when it is not one of this version. An entry with no address is dropped. */
    fun parse(text: String?): Published? {
        val o = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
        if (o.optInt("v", 0) != VERSION) return null
        val arr = o.optJSONArray("accounts") ?: return null
        val accounts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { a ->
            val email = a.optString("email").trim()
            if (email.isBlank()) null else Account(email, a.optString("password").takeIf { it.isNotBlank() })
        }
        return Published(o.optString("owner"), o.optString("jmap"), o.optString("imap"), o.optInt("imap_port", 993),
            o.optString("smtp"), o.optInt("smtp_port", 465), accounts)
    }

    /** What Cloud Mail shows when Cloud Account cannot answer: a sentence naming the fix, never a value. */
    fun refusal(error: String?): String = "Cloud Account did not hand over the mail accounts: ${error.orEmpty().ifBlank { "no reason given" }}"
}
