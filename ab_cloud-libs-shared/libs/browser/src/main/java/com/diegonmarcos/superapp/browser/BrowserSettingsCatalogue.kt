package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/** One user setting as the consuming app declares it (build.json::ui.browser.settings[]). */
data class BrowserSetting(
    val key: String,
    /** bool | int | string | enum | set */
    val type: String,
    val default: Any?,
    val values: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val label: String = key,
    val section: String = "general",
    /** config (migrates with the fleet Account) | device (stays on this phone) */
    val cls: String = "config",
    val doc: String = "",
    /** #886 how each enum value reads on the Configs page (value → words); missing = the value, prettified. */
    val valueLabels: Map<String, String> = emptyMap(),
    /** #1402 the titled group of its Configs page the setting sits in (empty = the page's own, ungrouped). */
    val group: String = "",
)

/**
 * #802 THE SETTINGS CATALOGUE: every user setting, declared once as data.
 *
 * The settings screen, the `/api/browser/settings*` routes and FleetConfig's
 * export all read this one list, so a setting cannot exist in one and be
 * missing from another. Pure: no android.*, JVM-tested.
 */
class BrowserSettingsCatalogue(val settings: List<BrowserSetting>) {

    operator fun get(key: String): BrowserSetting? = settings.firstOrNull { it.key == key }

    /**
     * [raw] (a query-string value) → the typed value to store.
     * @throws IllegalArgumentException naming the key and the rule it broke.
     */
    fun parseValue(key: String, raw: String): Any {
        val s = get(key) ?: throw IllegalArgumentException("$key: not a setting (see settings/catalogue)")
        val v = raw.trim()
        return when (s.type) {
            "bool" -> when (v.lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> throw IllegalArgumentException("$key: '$v' is not a bool (true/false)")
            }
            "int" -> {
                val n = v.toIntOrNull() ?: throw IllegalArgumentException("$key: '$v' is not an int")
                if ((s.min != null && n < s.min) || (s.max != null && n > s.max))
                    throw IllegalArgumentException("$key: $n is out of range ${s.min}..${s.max}")
                n
            }
            "enum" -> if (v in s.values) v
                else throw IllegalArgumentException("$key: '$v' is not one of ${s.values}")
            "set" -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().also { set ->
                val bad = if (s.values.isEmpty()) emptyList() else set.filterNot { it in s.values }
                if (bad.isNotEmpty()) throw IllegalArgumentException("$key: $bad not in ${s.values}")
            }
            else -> v
        }
    }

    /** Every setting's current value plus `_types`, the FleetConfig export shape. */
    fun snapshot(read: (BrowserSetting) -> Any?): JSONObject {
        val out = JSONObject()
        val types = JSONObject()
        for (s in settings) {
            val v = read(s) ?: s.default
            out.put(s.key, if (v is Set<*>) JSONArray(v.map { it.toString() }.sorted()) else v ?: JSONObject.NULL)
            types.put(s.key, s.type)
        }
        return out.put("_types", types)
    }

    fun toJson(): JSONArray = JSONArray().also { arr ->
        for (s in settings) arr.put(JSONObject()
            .put("key", s.key).put("type", s.type).put("default", s.default.let { if (it is Set<*>) JSONArray(it.toList()) else it ?: JSONObject.NULL })
            .put("values", JSONArray(s.values)).put("min", s.min ?: JSONObject.NULL).put("max", s.max ?: JSONObject.NULL)
            .put("label", s.label).put("section", s.section).put("class", s.cls).put("doc", s.doc))
    }

    companion object {
        val EMPTY = BrowserSettingsCatalogue(emptyList())

        /**
         * `values_from: "search_engines"` takes the enum from the app's engine list, and
         * its default is the app's default engine — the list is declared once, there.
         * `values_from: "addons"` does the same for the add-on set.
         */
        fun parse(
            arr: JSONArray?, engineIds: List<String>, defaultEngine: String,
            addonIds: List<String> = emptyList(), addonDefaults: Set<String> = emptySet(),
            engineLabels: Map<String, String> = emptyMap(),
        ): BrowserSettingsCatalogue {
            if (arr == null) return EMPTY
            val out = ArrayList<BrowserSetting>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key").trim()
                val type = o.optString("type", "string")
                if (key.isEmpty()) continue
                val fromEngines = o.optString("values_from") == "search_engines"
                // #802 values_from: addons — the set of add-on ids; default = the default_enabled ones.
                val fromAddons = o.optString("values_from") == "addons"
                val values = if (fromEngines) engineIds else if (fromAddons) addonIds else o.optJSONArray("values").strings()
                val default: Any? = when {
                    fromEngines -> defaultEngine
                    fromAddons -> addonDefaults
                    !o.has("default") || o.isNull("default") -> null
                    type == "bool" -> o.optBoolean("default")
                    type == "int" -> o.optInt("default")
                    type == "set" -> o.optJSONArray("default").strings().toSet()
                    else -> o.optString("default")
                }
                out.add(BrowserSetting(
                    key = key, type = type, default = default, values = values,
                    min = if (o.has("min")) o.optInt("min") else null,
                    max = if (o.has("max")) o.optInt("max") else null,
                    label = o.optString("label", key), section = o.optString("section", "general"), group = o.optString("group"),
                    cls = o.optString("class", "config"), doc = o.optString("doc"),
                    valueLabels = if (fromEngines) engineLabels else o.optJSONObject("value_labels").let { m ->
                        if (m == null) emptyMap() else m.keys().asSequence().associateWith { k -> m.optString(k) }
                    },
                ))
            }
            return BrowserSettingsCatalogue(out)
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }
    }
}
