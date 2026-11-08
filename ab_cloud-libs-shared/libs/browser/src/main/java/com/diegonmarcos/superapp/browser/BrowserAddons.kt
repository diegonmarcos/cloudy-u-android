package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/** One declared add-on (build.json::ui.browser.addons[]). */
data class BrowserAddon(
    val id: String,
    val label: String,
    val doc: String,
    val defaultEnabled: Boolean,
    /** What it may do, in the declared vocabulary ([BrowserAddons.PERMISSIONS]). */
    val permissions: List<String>,
    /** A fleet app it needs installed (applicationId), or null. */
    val requiresPackage: String?,
    /** device | remote */
    val engine: String,
    /** Its rows in the PAGE menu's Page tools section (scope page, the default). */
    val menu: List<BrowserMenuItem>,
    /** #900 Its GLOBAL rows (scope global): they sit on a Configs page, never in the page dropdown. */
    val configRows: List<BrowserConfigRow> = emptyList(),
    /** Its own declared block (remote endpoint, tools, …), handed to it verbatim. */
    val config: JSONObject,
)

/**
 * #802 THE ADD-ONS, as data. Which exist, what each may touch (permissions in words on
 * the Add-ons page), which fleet app each needs, and which menu rows each contributes:
 * those rows join the menu's `addons` section and require the fact `addon:<id>`, so a
 * disabled add-on's rows say why instead of vanishing. Enabled-ness is the catalogue's
 * `addons_enabled` set (values_from: addons). Pure; JVM-tested.
 */
class BrowserAddons(val all: List<BrowserAddon>) {

    operator fun get(id: String) = all.firstOrNull { it.id == id }

    fun enabled(id: String, enabledSet: Set<String>?): Boolean =
        (enabledSet ?: all.filter { it.defaultEnabled }.map { it.id }.toSet()).contains(id) && get(id) != null

    /**
     * #802 I8 the Search add-on's engines: its `engines` array, which the app's build copies from
     * cloud-search's build.json (config_from). Empty when the add-on or the array is absent.
     */
    fun searchEngines(): List<BrowserSearchEngine> {
        val arr = get("search")?.config?.optJSONArray("engines") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { e ->
            val id = e.optString("id"); val url = e.optString("url")
            if (id.isBlank() || BrowserSearchEngine.QUERY !in url) null else BrowserSearchEngine(id, e.optString("label", id), url)
        }
    }

    /** The facts the menu resolves add-on rows against. */
    fun facts(enabledSet: Set<String>?, installed: (String) -> Boolean): Map<String, Boolean> =
        all.associate { a ->
            "addon:${a.id}" to (enabled(a.id, enabledSet) && (a.requiresPackage == null || installed(a.requiresPackage)))
        }

    fun toJson(enabledSet: Set<String>?, installed: (String) -> Boolean): JSONArray = JSONArray().also { arr ->
        all.forEach { a ->
            arr.put(JSONObject().put("id", a.id).put("label", a.label).put("doc", a.doc)
                .put("enabled", enabled(a.id, enabledSet)).put("engine", a.engine)
                .put("permissions", JSONArray(a.permissions))
                .put("requires", a.requiresPackage ?: JSONObject.NULL)
                .put("installed", a.requiresPackage?.let { installed(it) } ?: JSONObject.NULL))
        }
    }

    companion object {
        /** The permission vocabulary, and how each reads on the Add-ons page. */
        val PERMISSIONS = mapOf(
            "page_read" to "reads the page you are on",
            "page_write" to "clicks and types on the page (asks first)",
            "network" to "talks to a server",
            "downloads" to "saves files to Downloads",
            "vault_autofill" to "asks Cloud Vault to fill logins",
            "ai_model" to "sends text to an AI model",
        )

        val EMPTY = BrowserAddons(emptyList())

        fun parse(arr: JSONArray?): BrowserAddons {
            if (arr == null) return EMPTY
            return BrowserAddons((0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                if (id.isEmpty()) return@mapNotNull null
                val perms = o.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                val entries = o.optJSONArray("menu")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                // #900 a global entry belongs to a Configs page; only a page entry joins the page dropdown.
                val configRows = entries.filter { it.optString("scope") == "global" }.map { m ->
                    val req = m.optJSONArray("requires")?.let { r -> (0 until r.length()).map { r.optString(it) } }.orEmpty()
                    BrowserConfigRow(m.optString("id"), m.optString("label", m.optString("id")), m.optString("page"), listOf("addon:$id") + req, m.optString("group"))
                }
                val menu = entries.filter { it.optString("scope") != "global" }.let { a ->
                    a.map { m ->
                        val req = m.optJSONArray("requires")?.let { r -> (0 until r.length()).map { r.optString(it) } }.orEmpty()
                        BrowserMenuItem(m.optString("id"), "page_tools", m.optString("label", m.optString("id")),
                            m.optString("kind", "action"), listOf("addon:$id") + req, m.optString("checked").ifBlank { null },
                            m.optBoolean("api", false))
                    }
                }
                BrowserAddon(id, o.optString("label", id), o.optString("doc"), o.optBoolean("default_enabled", false),
                    perms, o.optString("requires_package").ifBlank { null }, o.optString("engine", "device"), menu, configRows, o)
            })
        }
    }
}
