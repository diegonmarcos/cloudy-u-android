package org.fossify.phone.sim

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs

/**
 * Every manual SIM field (PIN and PUK included) is stored ONLY in an encrypted prefs file, opened through libs:crash
 * SafeEncryptedPrefs (fleet rule #1106). Values are never logged.
 */
class SimManualStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(SafeEncryptedPrefs.prefs(context, FILE))

    fun load(key: String): SimManual = SimManual(
        provider = get(key, "provider"), plan = get(key, "plan"), monthlyPrice = get(key, "monthlyPrice"),
        currency = get(key, "currency"), dataAllowance = get(key, "dataAllowance"), renewalDate = get(key, "renewalDate"),
        number = get(key, "number"), pin = get(key, "pin"), puk = get(key, "puk"), apnNotes = get(key, "apnNotes"),
        support = get(key, "support"),
    )

    fun save(key: String, m: SimManual) {
        prefs.edit()
            .putString("$key.provider", m.provider).putString("$key.plan", m.plan)
            .putString("$key.monthlyPrice", m.monthlyPrice).putString("$key.currency", m.currency)
            .putString("$key.dataAllowance", m.dataAllowance).putString("$key.renewalDate", m.renewalDate)
            .putString("$key.number", m.number).putString("$key.pin", m.pin).putString("$key.puk", m.puk)
            .putString("$key.apnNotes", m.apnNotes).putString("$key.support", m.support)
            .apply()
    }

    private fun get(key: String, f: String): String = prefs.getString("$key.$f", "") ?: ""

    companion object { const val FILE = "cloud_dialer_sim_manual" }
}
