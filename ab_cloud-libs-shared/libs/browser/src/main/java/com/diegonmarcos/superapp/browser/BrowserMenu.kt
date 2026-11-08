package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject

/** One declared overflow-menu entry (build.json::ui.browser.menu.items[]). */
data class BrowserMenuItem(
    val id: String,
    val section: String,
    val label: String,
    /** action | toggle | screen */
    val kind: String = "action",
    /** facts that must all be true for the row to be enabled (see [BrowserMenu.rows]). */
    val requires: List<String> = emptyList(),
    /** the fact or setting key whose truth a toggle shows. */
    val checked: String? = null,
    /** may /api/browser/menu/act run it (non-destructive page actions only). */
    val api: Boolean = false,
    /** #886 a `settings`-section row that belongs INSIDE one Configs section (its id) instead of at the page's foot. */
    val settingsSection: String? = null,
    /** #1141 the small word under an icon-row glyph. */
    val caption: String? = null,
    /** optional sub-header the row is grouped under inside its section (Page Tools: Reading, Save & Export ...). */
    val category: String? = null,
)

data class BrowserMenuSection(val id: String, val label: String)

data class BrowserMenuRow(val item: BrowserMenuItem, val enabled: Boolean, val why: String?, val checked: Boolean?)

/**
 * #802 THE OVERFLOW MENU AS DATA. Sections, rows, what each row needs to be usable and
 * why it is not, all declared in build.json::ui.browser.menu. This class only resolves
 * the declaration against the page's current facts; the sheet draws what it returns and
 * /api/browser/menu serves the same rows, so the screen and the API cannot disagree.
 * Pure: no android.*, JVM-tested.
 */
class BrowserMenu(
    val sections: List<BrowserMenuSection>,
    val items: List<BrowserMenuItem>,
    /** fact → why a row needing it is disabled, in words. */
    val whys: Map<String, String>,
) {
    /** Every item with enabled/why/checked under [facts]. An unknown fact counts as false. */
    fun rows(facts: Map<String, Boolean>): List<BrowserMenuRow> = items.map { item ->
        val missing = item.requires.firstOrNull { facts[it] != true }
        BrowserMenuRow(
            item = item,
            enabled = missing == null,
            why = missing?.let { whys[it] ?: "needs $it" },
            checked = item.checked?.let { facts[it] == true },
        )
    }

    /** Rows grouped by section, in the declared section order. */
    fun grouped(facts: Map<String, Boolean>): List<Pair<BrowserMenuSection, List<BrowserMenuRow>>> {
        val rows = rows(facts)
        return sections.map { s -> s to rows.filter { it.item.section == s.id } }.filter { it.second.isNotEmpty() }
    }

    /** #1141 the icon rows, top to bottom: every `icons*` section in declared order (row 1 `icons`, row 2 `icons2`). */
    fun iconRows(facts: Map<String, Boolean>): List<List<BrowserMenuRow>> =
        grouped(facts).filter { isIconSection(it.first.id) }.map { it.second }

    /** #1141 the titled list sections under the icon rows. */
    fun listSections(facts: Map<String, Boolean>): List<Pair<BrowserMenuSection, List<BrowserMenuRow>>> =
        grouped(facts).filterNot { isIconSection(it.first.id) }

    fun item(id: String): BrowserMenuItem? = items.firstOrNull { it.id == id }

    fun toJson(facts: Map<String, Boolean>): JSONArray = JSONArray().also { arr ->
        for ((s, rows) in grouped(facts)) {
            val r = JSONArray()
            rows.forEach {
                r.put(JSONObject().put("id", it.item.id).put("label", it.item.label).put("kind", it.item.kind)
                    .put("enabled", it.enabled).put("why", it.why ?: JSONObject.NULL)
                    .put("checked", it.checked ?: JSONObject.NULL).put("api", it.item.api).put("category", it.item.category ?: JSONObject.NULL))
            }
            arr.put(JSONObject().put("section", s.id).put("label", s.label).put("rows", r))
        }
    }

    companion object {
        val EMPTY = BrowserMenu(emptyList(), emptyList(), emptyMap())

        fun isIconSection(id: String) = id == "icons" || (id.startsWith("icons") && id.drop(5).all { it.isDigit() })

        fun parse(o: JSONObject?): BrowserMenu {
            if (o == null) return EMPTY
            val sections = o.optJSONArray("sections").objects().map {
                BrowserMenuSection(it.optString("id"), it.optString("label", it.optString("id")))
            }
            val ids = sections.map { it.id }.toSet()
            val items = o.optJSONArray("items").objects().mapNotNull {
                val id = it.optString("id").trim()
                val section = it.optString("section")
                // A row in no declared section would never be drawn: refuse it here, loudly in the test.
                if (id.isEmpty() || section !in ids) return@mapNotNull null
                BrowserMenuItem(
                    id = id, section = section, label = it.optString("label", id),
                    kind = it.optString("kind", "action"),
                    requires = it.optJSONArray("requires").let { a -> if (a == null) emptyList() else (0 until a.length()).map { i -> a.optString(i) } },
                    checked = it.optString("checked").ifBlank { null },
                    api = it.optBoolean("api", false),
                    settingsSection = it.optString("settings_section").ifBlank { null },
                    caption = it.optString("caption").ifBlank { null },
                    category = it.optString("category").ifBlank { null },
                )
            }
            val whys = HashMap<String, String>()
            o.optJSONObject("requires_why")?.let { w -> w.keys().forEach { k -> whys[k] = w.optString(k) } }
            return BrowserMenu(sections, items, whys)
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
