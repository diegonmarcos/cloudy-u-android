package org.fossify.phone.sim

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs

/**
 * The typed per-SIM fields (schema: [SimSchema]) keyed by [SimFields.key] (ICCID, else subscription id). Non-secret fields live in
 * an encrypted prefs file opened through libs:crash SafeEncryptedPrefs (fleet rule #1106). A secret field (PIN, PUK) is NEVER
 * written to prefs: it goes only to the [SimSecretVault] (Cloud Account). Values are never logged.
 */
class SimManualStore(
    private val prefs: SharedPreferences,
    private val vault: SimSecretVault,
    private val schema: SimSchema,
) {
    constructor(context: Context, schema: SimSchema = SimSchema.load(context)) :
        this(SafeEncryptedPrefs.prefs(context, FILE), AccountSimSecretVault(context, schema.vault), schema)

    /** Every non-secret field of [key], blank when never typed. */
    fun load(key: String): SimManual =
        SimManual(schema.fields.filter { !it.secret }.associate { it.id to (prefs.getString("$key.${it.id}", "") ?: "") })

    /** Saves one field; false = unknown field, or a secret the vault did not take. */
    fun save(key: String, id: String, value: String): Boolean {
        val f = schema.fields.firstOrNull { it.id == id } ?: return false
        if (f.secret) return vault.put(key, id, value)
        prefs.edit().putString("$key.$id", value).apply()
        return true
    }

    /** A secret field's value: null = vault unreachable, "" = none stored. Null for a non-secret id. */
    fun secret(key: String, id: String): String? = if (id in schema.secretIds) vault.get(key, id) else null

    companion object { const val FILE = "cloud_dialer_sim_manual" }
}
