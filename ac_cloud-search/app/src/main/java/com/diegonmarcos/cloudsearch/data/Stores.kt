package com.diegonmarcos.cloudsearch.data

import android.content.SharedPreferences
import com.diegonmarcos.cloudsearch.core.Cache
import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.Listing
import com.diegonmarcos.cloudsearch.core.SearchConfig
import org.json.JSONArray
import java.io.File

/** Saved items: listings the user starred, kept whole so they open offline. */
class SavedStore(private val prefs: SharedPreferences) {
    fun all(): List<Listing> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { Listing.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun isSaved(key: String): Boolean = all().any { it.key == key }

    /** Adds [l] when it is not saved, removes it when it is; answers the new state. */
    fun toggle(l: Listing): Boolean {
        val now = all()
        val on = now.none { it.key == l.key }
        val next = if (on) listOf(l) + now else now.filterNot { it.key == l.key }
        prefs.edit().putString(KEY, JSONArray(next.map { it.toJson() }).toString()).apply()
        return on
    }

    private companion object { const val KEY = "saved" }
}

/** The small choices the app remembers: theme, city, last query per vertical, model, web toggle. */
class Prefs(private val prefs: SharedPreferences, private val cfg: SearchConfig) {
    var dark: Boolean
        get() = prefs.getBoolean("dark", com.diegonmarcos.cloudsearch.BuildConfig.DARK_DEFAULT)
        set(v) = prefs.edit().putBoolean("dark", v).apply()
    var city: String
        get() = prefs.getString("city", cfg.defaultCity) ?: cfg.defaultCity
        set(v) = prefs.edit().putString("city", v).apply()
    var model: String
        get() = prefs.getString("model", cfg.ai.defaultModel) ?: cfg.ai.defaultModel
        set(v) = prefs.edit().putString("model", v).apply()
    var web: Boolean
        get() = prefs.getBoolean("web", true)
        set(v) = prefs.edit().putBoolean("web", v).apply()
    /** The chat's Agent field: a shared-descriptor agent id, "" = the model directly (OpenRouter). */
    var agent: String
        get() = prefs.getString("agent", "") ?: ""
        set(v) = prefs.edit().putString("agent", v).apply()

    fun lastQuery(vertical: String): String = prefs.getString("q:$vertical", "") ?: ""
    fun setLastQuery(vertical: String, q: String) = prefs.edit().putString("q:$vertical", q).apply()
}

/** OpenRouter's live model catalogue, cached for search.ai.catalog_ttl_hours. */
class ModelCatalog(private val cfg: SearchConfig, private val http: Http, private val cache: Cache, private val clock: () -> Long) {
    /** The catalogue as OpenRouter sent it (cached), or null when it was never fetched. #913 prices read it too. */
    fun raw(): String? {
        val key = "models|${cfg.ai.modelsUrl}"
        val hit = cache.get(key)
        return if (hit != null && clock() - hit.at < cfg.ai.catalogTtlHours * 3_600_000L) hit.body
        else runCatching { http.get(cfg.ai.modelsUrl, emptyMap(), cfg.timeoutMs) }.getOrNull()
            ?.takeIf { it.code in 200..299 }?.body?.also { cache.put(key, it, clock()) } ?: hit?.body
    }

    fun models(): List<Chat.Model> =
        raw()?.let { runCatching { Chat.models(it, cfg.ai.nativeWebParam) }.getOrNull() }.orEmpty()
}
