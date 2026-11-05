package com.diegonmarcos.superapp.headless

import android.content.Context
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs

/**
 * #1498 the encrypted store the backend selector reads by key NAME: the same encrypted prefs file and key names libs:apify's store uses
 * (#1470/#1474: `cloudflare.worker_url`, `cloudflare.worker_token`), so the Cloudflare-Browser-Run backend reuses them as they are. The
 * Cloudy-Browser-Headless URL and bearer sit under the key names the Configs settings name. Values never leave the store except to the caller.
 */
class HeadlessStore(ctx: Context) {
    private val sp = SafeEncryptedPrefs.prefs(ctx.applicationContext, PREFS)
    fun get(key: String?): String? = key?.let { sp.getString(it, null) }
    fun set(key: String, value: String?) {
        sp.edit().apply { if (value.isNullOrBlank()) remove(key) else putString(key, value.trim()) }.apply()
    }
    companion object { const val PREFS = "apify_tokens" }
}
