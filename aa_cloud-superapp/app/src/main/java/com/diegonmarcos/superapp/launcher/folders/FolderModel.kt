package com.diegonmarcos.superapp.launcher.folders

import org.json.JSONObject

/**
 * #1553 Configs ▸ Setup ▸ Launcher ▸ Folders — the declarative, two-table app-into-folder organiser.
 *
 * This file is the PURE core: data classes, the two table parsers and the resolver. It touches no Android
 * APIs so it is unit-testable on the plain JVM; the Android wiring (fleet-data reads, app enumeration,
 * persisted overrides, Compose UI) lives in the sibling files.
 *
 * The two tables are DATA served at runtime by the fleet-data client (never baked into the APK), one pair
 * per [FolderScope]:
 *   • TABLE 1 — folders  (dataset `launcher-folders/<scope>`): the sections and the folders inside them.
 *   • TABLE 2 — assignment (dataset `launcher-folder-assignments/<scope>`): app package → folder id (+ order).
 *
 * [resolve] places every enumerated app into its assigned folder; anything unassigned (or assigned to a
 * folder that no longer exists) lands in the special [OTHERS] folder, so a newly installed/added app shows
 * up there until it is assigned. A per-app override map (the user's own edits) wins over the served table.
 */

/** The two scopes the page toggles between. [key] is the dataset/preferences suffix. */
enum class FolderScope(val key: String) { CLOUD("cloud"), PHONE("phone") }

/** TABLE 1 row: one folder, in a named section. */
data class FolderDef(
    val section: String,
    val id: String,
    val label: String,
    val icon: String,
    val order: Int,
)

/** TABLE 2 row: one app's assignment to a folder, with its order inside that folder. */
data class FolderAssignment(val pkg: String, val folderId: String, val order: Int)

/** An app to be placed, as the resolver needs it (enumeration supplies these). */
data class FolderApp(val pkg: String, val label: String)

/** One folder as rendered: its definition and the apps inside, in order. */
data class ResolvedFolder(val def: FolderDef, val apps: List<FolderApp>)

/** One section as rendered: its title and the folders inside, in order. */
data class ResolvedSection(val title: String, val folders: List<ResolvedFolder>)

/** The special catch-all folder's id, label and section. Everything unassigned goes here. */
const val OTHERS_FOLDER_ID = "others"
const val OTHERS_FOLDER_LABEL = "Others"
const val OTHERS_SECTION = "Others"

object FolderTables {

    /** Parse TABLE 1 (folders) from a dataset JSON string, or none when it is null/blank/malformed. */
    fun parseFolders(json: String?): List<FolderDef> {
        val arr = array(json, "folders") ?: return emptyList()
        val out = ArrayList<FolderDef>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || id == OTHERS_FOLDER_ID) continue   // Others is synthesised, never declared
            out += FolderDef(
                section = o.optString("section").ifBlank { "Folders" },
                id = id,
                label = o.optString("label").ifBlank { id },
                icon = o.optString("icon"),
                order = o.optInt("order", 999),
            )
        }
        return out
    }

    /** Parse TABLE 2 (assignments) from a dataset JSON string, or none when it is null/blank/malformed. */
    fun parseAssignments(json: String?): List<FolderAssignment> {
        val arr = array(json, "assignments") ?: return emptyList()
        val out = ArrayList<FolderAssignment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("package").ifBlank { o.optString("pkg") }
            val folder = o.optString("folder").ifBlank { o.optString("folderId") }
            if (pkg.isBlank() || folder.isBlank()) continue
            out += FolderAssignment(pkg, folder, o.optInt("order", 999))
        }
        return out
    }

    /** A dataset is either a bare JSON array or an object carrying [key] (so both shapes the provider may serve work). */
    private fun array(json: String?, key: String): org.json.JSONArray? {
        val t = json?.trim().orEmpty()
        if (t.isEmpty()) return null
        return runCatching {
            if (t.startsWith("[")) org.json.JSONArray(t) else JSONObject(t).optJSONArray(key)
        }.getOrNull()
    }

    /**
     * Resolve the enumerated [apps] of one scope into rendered sections.
     *
     * [folders] is TABLE 1, [assignments] TABLE 2 (served defaults), [overrides] the user's own pkg→folderId
     * edits (they win). An app whose effective folder is blank, unknown, or [OTHERS] goes to the Others folder.
     * Sections are ordered by the smallest folder order they hold; folders by (order, id); apps inside a folder
     * by (assignment order, label). Others is always last, and is shown only when it (or a pinned-empty rule,
     * which this core does not model) has apps.
     */
    fun resolve(
        folders: List<FolderDef>,
        assignments: List<FolderAssignment>,
        overrides: Map<String, String>,
        apps: List<FolderApp>,
    ): List<ResolvedSection> {
        val defById = folders.associateBy { it.id }
        val servedFolder = assignments.associate { it.pkg to it.folderId }
        val orderOf = assignments.associate { it.pkg to it.order }

        // Effective folder id per app: override, else served, else Others; an unknown folder id also falls to Others.
        val bucket = LinkedHashMap<String, MutableList<FolderApp>>()
        for (app in apps) {
            val wanted = overrides[app.pkg] ?: servedFolder[app.pkg]
            val fid = if (wanted != null && wanted != OTHERS_FOLDER_ID && defById.containsKey(wanted)) wanted else OTHERS_FOLDER_ID
            bucket.getOrPut(fid) { mutableListOf() }.add(app)
        }
        for ((_, list) in bucket) list.sortWith(compareBy({ orderOf[it.pkg] ?: 999 }, { it.label.lowercase() }))

        // Declared folders that actually hold apps, plus ONE synthetic Others when anything landed there. A declared
        // folder whose id is `others` is NOT rendered by this loop — the synthetic block below owns Others, so an app
        // is never added twice (once here, once as Others).
        val resolved = ArrayList<ResolvedFolder>()
        for (def in folders) if (def.id != OTHERS_FOLDER_ID) bucket[def.id]?.takeIf { it.isNotEmpty() }?.let { resolved += ResolvedFolder(def, it) }
        bucket[OTHERS_FOLDER_ID]?.takeIf { it.isNotEmpty() }?.let {
            resolved += ResolvedFolder(FolderDef(OTHERS_SECTION, OTHERS_FOLDER_ID, OTHERS_FOLDER_LABEL, "", Int.MAX_VALUE), it)
        }

        // Each section's position is where it first appears in Table 1 (declaration order), so two sections whose first
        // folder shares an order keep their declared order rather than sorting alphabetically; Others is always last.
        val sectionIndex = LinkedHashMap<String, Int>()
        folders.forEachIndexed { i, f -> sectionIndex.putIfAbsent(f.section, i) }
        val bySection = LinkedHashMap<String, MutableList<ResolvedFolder>>()
        for (rf in resolved) bySection.getOrPut(rf.def.section) { mutableListOf() }.add(rf)
        for ((_, list) in bySection) list.sortWith(compareBy({ it.def.order }, { it.def.id }))
        return bySection.entries
            .sortedWith(compareBy({ if (it.key == OTHERS_SECTION) 1 else 0 }, { sectionIndex[it.key] ?: Int.MAX_VALUE }))
            .map { ResolvedSection(it.key, it.value) }
    }
}
