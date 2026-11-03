package com.diegonmarcos.cloudaccount.autofill

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Account ▸ Import → Cloud Vault: each ID document found in a paste goes to Cloud Vault's
 * add-identity entry point, which opens the vault's new-Identity screen prefilled (after the
 * vault's own unlock). Nothing is stored here and nothing is saved there until the user taps Save.
 *
 * The contract is Cloud Vault's (`ac_cloud-vault/.../data/autofill/cloud/AddIdentityRequest.kt`):
 * action [ACTION] in [VAULT_PACKAGE], behind the fleet signature permission [PERMISSION] that this
 * app requests (and defines, identically, so it exists whichever app is installed first). A vault
 * that does not answer [ACTION] (not installed, or older than the entry point) gets the previous
 * flow: open Cloud Vault and add the Identity item by hand. The number travels only in this one
 * explicit intent; it is never logged.
 */
object VaultIdentityHandoff {
    const val VAULT_PACKAGE = "com.diegonmarcos.cloudvault"
    const val ACTION = "com.diegonmarcos.cloudvault.action.ADD_IDENTITY"
    const val PERMISSION = "com.diegonmarcos.cloud.permission.VAULT_ADD_IDENTITY"
    const val VERSION = 1

    const val EXTRA_VERSION = "com.diegonmarcos.cloudvault.extra.VERSION"
    const val EXTRA_TYPE = "com.diegonmarcos.cloudvault.extra.ID_TYPE"
    const val EXTRA_NUMBER = "com.diegonmarcos.cloudvault.extra.ID_NUMBER"
    const val EXTRA_SUPPORT = "com.diegonmarcos.cloudvault.extra.ID_SUPPORT"
    const val EXTRA_ISSUING_COUNTRY = "com.diegonmarcos.cloudvault.extra.ID_ISSUING_COUNTRY"
    const val EXTRA_VALID_UNTIL = "com.diegonmarcos.cloudvault.extra.ID_VALID_UNTIL"
    const val EXTRA_ISSUED = "com.diegonmarcos.cloudvault.extra.ID_ISSUED"
    const val EXTRA_FIRST_NAME = "com.diegonmarcos.cloudvault.extra.FIRST_NAME"
    const val EXTRA_MIDDLE_NAME = "com.diegonmarcos.cloudvault.extra.MIDDLE_NAME"
    const val EXTRA_LAST_NAME = "com.diegonmarcos.cloudvault.extra.LAST_NAME"

    /** The explicit intent that asks Cloud Vault to add [id] as an Identity item. */
    fun intentFor(id: AutofillImport.ParsedId): Intent {
        val issuingCountry = id.details["issuing_country"]?.takeIf { it.isNotBlank() } ?: id.country
        return Intent(ACTION).setPackage(VAULT_PACKAGE).apply {
            putExtra(EXTRA_VERSION, VERSION)
            putNonBlank(EXTRA_TYPE, id.type)
            putNonBlank(EXTRA_NUMBER, id.number)
            putNonBlank(EXTRA_SUPPORT, id.details["support"])
            putNonBlank(EXTRA_ISSUING_COUNTRY, issuingCountry)
            putNonBlank(EXTRA_VALID_UNTIL, id.details["valid_until"])
            putNonBlank(EXTRA_ISSUED, id.details["issued"])
            putNonBlank(EXTRA_FIRST_NAME, id.givenName)
            putNonBlank(EXTRA_MIDDLE_NAME, id.middleName)
            putNonBlank(EXTRA_LAST_NAME, id.familyName)
        }
    }

    /** Whether the installed Cloud Vault has the add-identity entry point. */
    fun isAvailable(ctx: Context): Boolean =
        runCatching { ctx.packageManager.resolveActivity(Intent(ACTION).setPackage(VAULT_PACKAGE), 0) != null }
            .getOrDefault(false)

    /**
     * Opens Cloud Vault's new-Identity screen for [id]; false (nothing opened) when the vault is
     * missing, too old, or refuses this app. On Android 14+ the vault is told who is calling
     * (shared identity), so it can check the permission again in code.
     */
    fun send(ctx: Context, id: AutofillImport.ParsedId): Boolean {
        if (!isAvailable(ctx)) return false
        val intent = intentFor(id).apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { ctx.startActivity(intent, launchOptions()); true }.getOrDefault(false)
    }

    private fun launchOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        } else {
            null
        }

    /** Opens Cloud Vault's own screen (the fallback: the user adds the Identity item there). */
    fun openVault(ctx: Context): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(VAULT_PACKAGE) ?: return false
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun Intent.putNonBlank(key: String, value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { putExtra(key, it) }
    }
}
