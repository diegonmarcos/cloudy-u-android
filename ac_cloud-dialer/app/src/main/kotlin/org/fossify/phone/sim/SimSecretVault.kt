package org.fossify.phone.sim

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Where a SIM's PIN and PUK live: the Cloud Account vault, never this app's prefs, never a log. [get] null = the vault is not
 * reachable (Cloud Account missing or the grant not given); "" = reachable, nothing stored.
 */
interface SimSecretVault {
    fun get(key: String, field: String): String?
    fun put(key: String, field: String, value: String): Boolean
}

/**
 * Cloud Account's accountdata provider, methods declared in assets/sim-fields.json::vault (the provider side is
 * libs:account AccountData.Provider). arg = "<key>.<field>"; the value travels in the extras bundle and is never echoed.
 */
class AccountSimSecretVault(context: Context, private val decl: SimVaultDecl) : SimSecretVault {
    private val resolver = context.applicationContext.contentResolver

    private fun call(method: String, arg: String, extras: Bundle?): Bundle? =
        runCatching { resolver.call(Uri.parse("content://$AUTHORITY"), method, arg, extras) }.getOrNull()?.takeIf { it.getBoolean(KEY_OK) }

    override fun get(key: String, field: String): String? = call(decl.get, "$key.$field", null)?.let { it.getString(KEY_VALUE).orEmpty() }

    override fun put(key: String, field: String, value: String): Boolean =
        call(decl.put, "$key.$field", Bundle().apply { putString(KEY_VALUE, value) }) != null

    companion object {
        const val AUTHORITY = "com.diegonmarcos.cloudaccount.accountdata"
        const val KEY_OK = "ok"
        const val KEY_VALUE = "value"
    }
}
