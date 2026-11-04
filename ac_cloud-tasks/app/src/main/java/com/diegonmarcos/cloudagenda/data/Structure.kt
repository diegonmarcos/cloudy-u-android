package com.diegonmarcos.cloudagenda.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A node of one tab's tree: a folder, an area, a project, a family. Only the items under a node are mock. */
data class Node(val label: String, val children: List<Node> = emptyList())

/**
 * #1461 THE structure of the Tasks tabs, read from Cloud Me's own declaration and never retyped here. Cloud Me > Tasks is
 * Inboxes | Backlogs (| RoadMaps) | Library | Projects; this reads the same three sources it does:
 *  - data/repos/vault-rows.json (the notes vault's numbered folders: each row's chips and their sub-folders),
 *  - build.json::ui.sections[tasks] (the declared pages that lead a row: Due leads Backlogs, Tree leads RoadMaps; the group labels),
 *    baked into an asset by app/build.gradle from ac_cloud-me/build.json,
 *  - data/repos/repos.outline.json (the fleet registry: areas shown in lnktree > projects > families).
 * Pure parsing; no clock, no views.
 */
class Structure(private val trees: Map<String, List<Node>>) {
    /** The top-level nodes of [kind] (inboxes, backlogs, library, projects), in declared order. */
    fun tree(kind: String): List<Node> = trees[kind].orEmpty()

    /** Every node of [kind] as its path from the root, parents before children. */
    fun paths(kind: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        fun walk(n: Node, up: List<String>) { val p = up + n.label; out += listOf(p); n.children.forEach { walk(it, p) } }
        tree(kind).forEach { walk(it, emptyList()) }
        return out
    }

    companion object {
        const val VAULT_ROWS = "vault-rows.json"
        const val OUTLINE = "repos.outline.json"
        const val DECL = "cloud-me-tasks.json"
        val EMPTY = Structure(emptyMap())

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

        private fun chip(o: JSONObject): Node = Node(o.optString("label"), o.optJSONArray("children")?.objects().orEmpty().map { chip(it) })

        private fun row(vaultRows: String, id: String): List<Node> =
            JSONObject(vaultRows).optJSONArray("rows")?.objects().orEmpty().firstOrNull { it.optString("id") == id }
                ?.optJSONArray("chips")?.objects().orEmpty().map { chip(it) }

        private fun label(o: JSONObject) = o.optString("label").ifBlank { o.optString("name") }

        private fun families(n: JSONObject): List<Node> =
            n.optJSONArray("children")?.objects().orEmpty().filter { it.optString("kind") == "family" }.map { Node(label(it), families(it)) }

        /** The registry areas Cloud Me shows in lnktree > Proj (show_in lists lnktree), each with its projects and their families. */
        fun projects(outline: String): List<Node> =
            JSONObject(outline).optJSONArray("areas")?.objects().orEmpty()
                .filter { a -> a.optJSONArray("show_in")?.let { s -> (0 until s.length()).any { s.optString(it) == "lnktree" } } == true }
                .map { a -> Node(label(a), a.optJSONArray("children")?.objects().orEmpty().filter { it.optString("kind") == "project" }.map { Node(label(it), families(it)) }) }

        /** The declared pages that lead [groupId] in the tasks section of a build.json (or its baked subset), by label. */
        private fun lead(decl: String, groupId: String): Pair<String, List<Node>> {
            val ui = JSONObject(decl).optJSONObject("ui") ?: return groupId to emptyList()
            val sectionId = ui.optJSONObject("vault_rows")?.optString("section").orEmpty().ifBlank { "tasks" }
            val s = ui.optJSONArray("sections")?.objects().orEmpty().firstOrNull { it.optString("id") == sectionId } ?: return groupId to emptyList()
            val g = s.optJSONArray("groups")?.objects().orEmpty().firstOrNull { it.optString("id") == groupId } ?: return groupId to emptyList()
            val pages = s.optJSONArray("pages")?.objects().orEmpty()
            val ids = g.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return g.optString("label", groupId) to ids.mapNotNull { id -> pages.firstOrNull { it.optString("id") == id }?.let { Node(it.optString("label")) } }
        }

        fun parse(vaultRows: String, outline: String, decl: String): Structure {
            val (_, backlogLead) = lead(decl, "backlogs")
            val (roadmapsLabel, roadmapLead) = lead(decl, "roadmaps")
            return Structure(mapOf(
                "inboxes" to row(vaultRows, "inboxes"),
                // Backlogs: the declared lead page (Due) and the backlog folders, then the RoadMaps group with its own lead page (Tree) and folders.
                "backlogs" to backlogLead + row(vaultRows, "backlogs") + Node(roadmapsLabel, roadmapLead + row(vaultRows, "roadmaps")),
                "library" to row(vaultRows, "library"),
                "projects" to projects(outline),
            ))
        }

        fun load(context: Context): Structure = runCatching {
            fun read(n: String) = context.assets.open(n).bufferedReader().use { it.readText() }
            parse(read(VAULT_ROWS), read(OUTLINE), read(DECL))
        }.getOrDefault(EMPTY)
    }
}
