package com.diegonmarcos.cloudc3.pages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which PROJECT a container belongs to (owner request 2026-11-04), as a pure function of data/container_projects.json
 * (baked as BuildConfig.CONTAINER_PROJECTS_B64) and the one Containers document. No project, folder or container is named
 * here: the strip (All | Cloudy | CG | ...) is declared in build.json, the mapping in the JSON file.
 */
object ContainerProjects {

    data class Project(val id: String, val label: String, val code: String, val folderPrefix: List<String>, val namePrefix: List<String>)

    data class Decl(val default: String, val projects: List<Project>) {
        fun project(id: String?): Project? = projects.firstOrNull { it.id == id }

        /** The first project whose folder or name prefix matches [e], else the declared default (null only for an empty declaration). */
        fun projectOf(e: ContainersModel.Entry): Project? =
            projects.firstOrNull { p -> p.folderPrefix.any { e.folder.startsWith(it) } || p.namePrefix.any { e.name.startsWith(it) } } ?: project(default)
    }

    val EMPTY = Decl("", emptyList())

    fun parse(text: String): Decl {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return EMPTY
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun JsonObject.l(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val ps = (o["projects"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            p.s("id").takeIf { it.isNotBlank() }?.let { Project(it, p.s("label").ifBlank { it }, p.s("code"), p.l("folder_prefix"), p.l("name_prefix")) }
        }
        return Decl(o.s("default"), ps)
    }

    /** The containers of [projectId] only, groups and categories kept in the document's order (empty categories dropped). */
    fun scope(decl: Decl, c: ContainersModel.Catalog, projectId: String): ContainersModel.Catalog {
        val keep = c.entries.filter { decl.projectOf(it)?.id == projectId }
        val names = keep.map { it.name }.toSet()
        val groups = c.groups.map { g ->
            g.copy(categories = g.categories.map { cat -> cat.copy(names = cat.names.filter { it in names }) }.filter { it.names.isNotEmpty() })
        }
        return ContainersModel.Catalog(groups, keep)
    }

    /**
     * The Table's rows for [projectId] (null = All). In All the rows are clustered by project in the declared order and the
     * Group cell reads "<Project> / <group>", so the project grouping is visible there too.
     */
    fun rows(decl: Decl, c: ContainersModel.Catalog, projectId: String?): List<RoutesTableModel.Row> {
        val scoped = if (projectId == null) c else scope(decl, c, projectId)
        val byName = scoped.entries.associateBy { it.name }
        val rows = ContainersModel.rows(scoped)
        if (projectId != null) return rows
        val order = decl.projects.map { it.id }
        return rows.sortedBy { r -> order.indexOf(byName[r.container]?.let { decl.projectOf(it)?.id }).let { if (it < 0) order.size else it } }
            .map { r -> byName[r.container]?.let { decl.projectOf(it) }?.let { p -> r.copy(group = "${p.label} / ${r.group}") } ?: r }
    }
}
