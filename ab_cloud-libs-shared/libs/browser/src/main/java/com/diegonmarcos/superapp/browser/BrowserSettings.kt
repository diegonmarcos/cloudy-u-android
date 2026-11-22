package com.diegonmarcos.superapp.browser

import android.content.Context
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the USER has chosen, over the [BrowserSettingsCatalogue] the app declared.
 *
 * Separate from [BrowserTabPrefs] because it is not about tabs, and
 * separate from [BrowserConfig] because config is what the APP ships
 * and this is what the USER has since chosen. An unset key reads as the
 * catalogue's default; a key the catalogue does not declare reads as null,
 * so the caller leaves WebView's own behaviour alone rather than inventing one.
 *
 * The prefs file keeps its name, `browser_settings` (declared in the fleet
 * manifest, so FleetConfig carries every key to a new phone with no code here).
 */
class BrowserSettings(context: Context, val catalogue: BrowserSettingsCatalogue = BrowserSettingsCatalogue.EMPTY) {

    private val sp = context.applicationContext
        .getSharedPreferences("browser_settings", Context.MODE_PRIVATE)

    init {
        // #1841 a value stored under the wrong type (it threw when read) is repaired once, after a backup of the prefs
        BrowserListMigration.ensureMap(context, "browser_settings", "set_migrate", catalogue.toJson())
    }

    /** null until he picks one — meaning "use the configured default". */
    fun searchEngineId(): String? = sp.getString(KEY_ENGINE, null)

    fun setSearchEngineId(id: String) {
        sp.edit().putString(KEY_ENGINE, id).apply()
    }

    /** The stored value, else the declared default; null for an undeclared key. */
    fun value(key: String): Any? {
        val s = catalogue[key] ?: return null
        if (!sp.contains(key)) return s.default
        return when (s.type) {
            "bool" -> sp.getBoolean(key, false)
            "int" -> sp.getInt(key, 0)
            "set" -> sp.getStringSet(key, emptySet())
            else -> sp.getString(key, null)
        }
    }

    fun bool(key: String): Boolean? = value(key) as? Boolean
    fun int(key: String): Int? = value(key) as? Int
    fun string(key: String): String? = value(key) as? String
    @Suppress("UNCHECKED_CAST")
    fun stringSet(key: String): Set<String>? = value(key) as? Set<String>

    /**
     * Validate [raw] against the catalogue and store it. @return the error in words, or null.
     * #1841 the validation and typing are the browser core's (`set_parse`; the pure [BrowserSettingsCatalogue.parseValue] is the rule it is held to).
     */
    fun set(key: String, raw: String): String? {
        val r = BrowserCore.lists("set_parse", JSONObject().put("catalogue", catalogue.toJson()).put("key", key).put("raw", raw))
        if (r.has("error")) return r.optString("error")
        val v: Any = when (r.optString("type")) {
            "bool" -> r.getBoolean("value")
            "int" -> r.getInt("value")
            "set" -> r.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            else -> r.getString("value")
        }
        put(key, v)
        return null
    }

    fun put(key: String, v: Any) {
        val ed = sp.edit()
        @Suppress("UNCHECKED_CAST")
        when (v) {
            is Boolean -> ed.putBoolean(key, v)
            is Int -> ed.putInt(key, v)
            is Set<*> -> ed.putStringSet(key, v as Set<String>)
            else -> ed.putString(key, v.toString())
        }
        ed.apply()
        BrowserBus.post(BrowserBus.SETTINGS)
    }

    /** Every setting's current value plus `_types`, the FleetConfig export shape ([BrowserCore] `set_snapshot`; [BrowserSettingsCatalogue.snapshot] is the reference). */
    fun snapshot(): JSONObject {
        val values = JSONObject()
        for (st in catalogue.settings) value(st.key)?.let { v -> values.put(st.key, if (v is Set<*>) JSONArray(v.map { it.toString() }) else v) }
        return BrowserCore.lists("set_snapshot", JSONObject().put("catalogue", catalogue.toJson()).put("values", values))
    }

    private companion object {
        const val KEY_ENGINE = "search_engine_id"
    }
}
