package com.diegonmarcos.cloudtoolssmallllms.data

import android.content.Context
import org.json.JSONObject

/**
 * Everything this app keeps that is not a secret, in SharedPreferences [PREFS] (fleet manifest
 * store, class content / config): the router choice, the per-task
 * worker overrides and the last read of Jev's availability. The token is NOT here (SecretPrefs).
 */
class Stores(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The AI chat's backend: "openrouter" (cloud, paid) or "local" (a runtime of the Store, free). */
    var backend: String
        get() = prefs.getString(BACKEND, "openrouter").orEmpty()
        set(v) { prefs.edit().putString(BACKEND, v).apply() }

    /** The model the AI chat is pinned to (Settings, advanced); empty = the task picks it. */
    var pinned: String
        get() = prefs.getString(PINNED, "").orEmpty()
        set(v) { prefs.edit().putString(PINNED, v).apply() }

    var router: String
        get() = prefs.getString(ROUTER, "").orEmpty()
        set(v) { prefs.edit().putString(ROUTER, v).apply() }

    fun workers(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(WORKERS, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun saveWorkers(m: Map<String, String>) {
        prefs.edit().putString(WORKERS, JSONObject(m).toString()).apply()
    }

    /** The last read of the decision catalogue: listed (null = never read), its $/1M input, when. */
    fun jev(): Triple<Boolean?, Double?, Long> {
        if (!prefs.contains(JEV_LISTED)) return Triple(null, null, 0L)
        val price = prefs.getFloat(JEV_PRICE, -1f).toDouble().takeIf { it >= 0 }
        return Triple(prefs.getBoolean(JEV_LISTED, false), price, prefs.getLong(JEV_AT, 0L))
    }

    fun saveJev(listed: Boolean, price: Double?, at: Long) {
        prefs.edit().putBoolean(JEV_LISTED, listed).putFloat(JEV_PRICE, (price ?: -1.0).toFloat()).putLong(JEV_AT, at).apply()
    }

    companion object {
        const val PREFS = "cloud_tools_small_llms"
        private const val ROUTER = "router"
        private const val BACKEND = "backend"
        private const val PINNED = "pinned_model"
        private const val WORKERS = "workers"
        private const val JEV_LISTED = "jev_listed"
        private const val JEV_PRICE = "jev_price_in"
        private const val JEV_AT = "jev_checked_at"
    }
}
