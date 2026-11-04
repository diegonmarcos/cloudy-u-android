package com.diegonmarcos.cloudnews

import android.content.Context
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs

/** The ticketers' API keys: AES-256 EncryptedSharedPreferences (libs:crash SafeEncryptedPrefs), never a plain file. */
class EncryptedKeyStore(ctx: Context) : EventKeyStore {
    private val sp = SafeEncryptedPrefs.prefs(ctx.applicationContext, "event_keys")
    override fun get(id: String): String? = sp.getString(id, null)
    override fun set(id: String, value: String?) {
        sp.edit().apply { if (value.isNullOrBlank()) remove(id) else putString(id, value) }.apply()
    }
}
