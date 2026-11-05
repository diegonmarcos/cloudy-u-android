package com.diegonmarcos.superapp.appstore

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * #1511 the build-time lib tables (Shared libs, Machine-learning libs) in the Apps page's own shape: a group per category,
 * dense [StoreRow.Item] rows under it, and the shared filter chips on top. NOTHING here names a lib or a category: the
 * categories, their order and each lib's category, class and ships-as come from lib-consumers.json, which
 * cloud-android-lib-consumers.py generates from 1_cicd/src/data/lib-categories.json (a lib in no category fails that guard).
 */
internal object LibsTable {
    /** One lib: [ships] is "apk" (only engine APKs compile it in) or "static" (compiled into its [consumers]). */
    class Row(val id: String, val cls: String, val category: String, val consumers: List<String>, val ships: String,
              val description: String, val engines: List<String>, val primary: String) {
        val shipsLabel: String get() = if (ships == "apk") "APK" else "static"
        /** The row's one meta line: class, how many apps compile it in, how it ships. */
        val meta: String get() = "$cls · ${consumers.size} app${if (consumers.size == 1) "" else "s"} · $shipsLabel"
    }

    class Group(val id: String, val label: String, val rows: List<Row>)

    const val OTHER = "Other"
    /** The filter chips: all, shipped as an APK, static. */
    val FILTERS = listOf("All", "APK", "Static")
    val FILTER_TAGS = listOf("store-libs-filter:all", "store-libs-filter:apk", "store-libs-filter:static")

    /** An app dir as its name: `ac_cloud-drive` -> `cloud-drive`, `ab_x/keyboard-engines` -> `keyboard-engines`. */
    fun appName(dir: String): String = dir.substringAfterLast('/').replace(Regex("^a[a-z]_"), "")

    fun parse(root: JSONObject, ml: Boolean): List<Row> {
        val libs = root.optJSONArray("build_time") ?: return emptyList()
        fun strings(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
        return (0 until libs.length()).map { libs.getJSONObject(it) }.filter { it.optBoolean("ml") == ml }.map {
            Row(it.getString("id"), it.optString("class"), it.optString("category"), strings(it.optJSONArray("compiled_by")).map(::appName),
                it.optString("ships", "static"), it.optString("description"), strings(it.optJSONArray("engines")),
                it.optString("primary").takeIf { p -> p.isNotEmpty() }?.let(::appName).orEmpty())
        }
    }

    /** The rows kept by [filter] (0 all, 1 APK, 2 static), grouped by category in the DECLARED order, each group's rows by
     *  id; a lib whose category is not declared gets a trailing [OTHER] group; a group with no rows is not drawn. */
    fun groups(root: JSONObject, rows: List<Row>, filter: Int): List<Group> {
        val kept = rows.filter { filter == 0 || (filter == 1) == (it.ships == "apk") }
        val cats = root.optJSONArray("categories")
        val declared = (0 until (cats?.length() ?: 0)).map { cats!!.getJSONObject(it) }.map { it.getString("id") to it.optString("label", it.getString("id")) }
        val out = declared.map { (id, label) -> Group(id, label, kept.filter { it.category == id }.sortedBy { it.id }) }.toMutableList()
        val known = declared.map { it.first }.toSet()
        out += Group("", OTHER, kept.filter { it.category !in known }.sortedBy { it.id })
        return out.filter { it.rows.isNotEmpty() }
    }

    /** The summary line over the table. */
    fun summary(rows: List<Row>): String =
        "${rows.size} total  ·  ${rows.count { it.ships == "apk" }} APK  ·  ${rows.count { it.ships != "apk" }} static"

    /** The detail under an open row: what it is, who compiles it (their names), and where else it ships. */
    fun detail(r: Row): List<String> = listOfNotNull(
        r.description.takeIf { it.isNotEmpty() },
        if (r.consumers.isEmpty()) "Compiled into no app directly" else "Compiled into ${r.consumers.size}: ${r.consumers.joinToString(", ")}",
        r.engines.takeIf { it.isNotEmpty() }?.let { "Inside ${if (it.size > 3) "${it.size} engine APKs" else it.joinToString(", ")}" },
        r.primary.takeIf { it.isNotEmpty() }?.let { "Primary: $it" },
    )

    /** The table. [group] draws one category's header and its content (the page's collapsible group header). */
    @Composable
    fun Page(root: JSONObject, ml: Boolean, group: @Composable (key: String, title: String, count: Int, content: @Composable () -> Unit) -> Unit) {
        val rows = remember(root, ml) { parse(root, ml) }
        if (rows.isEmpty()) return
        var filter by remember { mutableIntStateOf(0) }
        val open = remember { mutableStateMapOf<String, Boolean>() }
        StoreRow.Summary(summary(rows), tag = "store-libs-summary")
        StoreRow.Filters(FILTERS, filter, { if (filter != it) filter = it }, tags = FILTER_TAGS)
        val groups = groups(root, rows, filter)
        if (groups.isEmpty()) StoreCaption("Nothing in this filter.")
        for (g in groups) group("lib_group_${if (ml) "ml" else "shared"}_${g.id}", g.label, g.rows.size) {
            for (r in g.rows) {
                val isOpen = open[r.id] == true
                StoreRow.Item(r.id, isOpen, { open[r.id] = !isOpen }, tag = "store-lib:${r.id}",
                    dot = if (r.ships == "apk") "▣" else "·", meta = r.meta, headTag = "store-lib-head:${r.id}",
                    detail = { for (l in detail(r)) StoreRow.Line(l, Modifier) })
            }
        }
    }
}
