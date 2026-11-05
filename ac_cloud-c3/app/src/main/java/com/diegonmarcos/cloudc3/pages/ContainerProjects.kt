package com.diegonmarcos.cloudc3.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /** [repoGroup] non-blank = the project keeps its own container groups (Cloudy) and puts its repos in that one group; blank = the project's groups are its registry families. */
    data class Project(val id: String, val label: String, val code: String, val folderPrefix: List<String>, val namePrefix: List<String>, val repoGroup: String = "")

    /** One owner git repo, generated into data/repos_declared.json from the fleet registry (data/gen_repos_slice.py). */
    data class Repo(
        val name: String, val project: String, val group: String, val category: String, val code: String,
        val visibility: String, val defaultBranch: String, val pagesUrl: String, val githubUrl: String,
    )

    /** A kind chip (All | MCPs | APIs | Apps | DBs): matched by name first, then by category; no rule = the declared default kind. */
    data class Kind(val id: String, val label: String, val nameSuffix: List<String>, val nameContains: List<String>, val categoryPrefix: List<String>, val role: List<String> = emptyList())

    data class Decl(
        val default: String, val projects: List<Project>,
        val nameOverrides: Map<String, String> = emptyMap(), val kinds: List<Kind> = emptyList(), val defaultKind: String = "",
        val groupRemap: Map<String, String> = emptyMap(),
        /** Container name -> registry family (the group it takes in a project whose groups are families), and the group of the rest. */
        val containerFamily: Map<String, String> = emptyMap(), val containerGroup: String = "Containers",
        val repos: List<Repo> = emptyList(), val families: Map<String, List<String>> = emptyMap(),
    ) {
        fun project(id: String?): Project? = projects.firstOrNull { it.id == id }
        fun kind(id: String?): Kind? = kinds.firstOrNull { it.id == id }

        /** The explicit name override, else the first project whose folder or name prefix matches [e], else the declared default (null only for an empty declaration). */
        fun projectOf(e: ContainersModel.Entry): Project? =
            project(repos.firstOrNull { it.name == e.name && e.role == "repo" }?.project)
                ?: project(nameOverrides[e.name])
                ?: projects.firstOrNull { p -> p.folderPrefix.any { e.folder.startsWith(it) } || p.namePrefix.any { e.name.startsWith(it) } }
                ?: project(default)

        /** Every kind of [e]: each kind whose `role` names the entity's role (a git repo is a Repo AND a DB, a data store), else the one kind its name rule, category rule or the declared default gives. */
        fun kindsOf(e: ContainersModel.Entry): List<Kind> {
            val byRole = kinds.filter { k -> e.role in k.role }
            if (byRole.isNotEmpty()) return byRole
            return listOfNotNull(
                kinds.firstOrNull { k -> k.nameSuffix.any { e.name.endsWith(it) } || k.nameContains.any { e.name.contains(it) } }
                    ?: kinds.firstOrNull { k -> k.categoryPrefix.any { e.category.startsWith(it) } }
                    ?: kind(defaultKind),
            )
        }

        /** The first (primary) kind of [e]. */
        fun kindOf(e: ContainersModel.Entry): Kind? = kindsOf(e).firstOrNull()
    }

    val EMPTY = Decl("", emptyList())

    fun parse(text: String, reposText: String = ""): Decl {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return EMPTY
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun JsonObject.l(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        fun JsonObject.m(k: String) = (this[k] as? JsonObject).orEmpty().mapNotNull { (n, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { n to it } }.toMap()
        val ps = (o["projects"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            p.s("id").takeIf { it.isNotBlank() }?.let { Project(it, p.s("label").ifBlank { it }, p.s("code"), p.l("folder_prefix"), p.l("name_prefix"), p.s("repo_group")) }
        }
        val ks = (o["kinds"] as? JsonArray).orEmpty().mapNotNull { e ->
            val k = e as? JsonObject ?: return@mapNotNull null
            k.s("id").takeIf { it.isNotBlank() }?.let { Kind(it, k.s("label").ifBlank { it }, k.l("name_suffix"), k.l("name_contains"), k.l("category_prefix"), k.l("role")) }
        }
        val ro = runCatching { Json.parseToJsonElement(reposText) as? JsonObject }.getOrNull()
        val repos = (ro?.get("repos") as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            r.s("name").takeIf { it.isNotBlank() }?.let {
                Repo(it, r.s("project"), r.s("group"), r.s("category"), r.s("code"), r.s("visibility"), r.s("default_branch"), r.s("pages_url"), r.s("github_url"))
            }
        }
        val fams = (ro?.get("projects") as? JsonObject).orEmpty().mapValues { (_, v) -> ((v as? JsonObject)?.l("families")).orEmpty() }
        return Decl(
            o.s("default"), ps, o.m("name_overrides"), ks, o.s("default_kind"), o.m("group_remap"),
            o.m("container_family"), o.s("container_group").ifBlank { "Containers" }, repos, fams,
        )
    }

    /** A repo as a Containers entity (role "repo"): the Table row and the Topology tile read it like any container. */
    fun entryOf(decl: Decl, r: Repo): ContainersModel.Entry = ContainersModel.Entry(
        r.name, "", "repo", r.group, r.category, "${decl.project(r.project)?.label.orEmpty()} repo, ${r.visibility}", "", "", "", null,
        r.visibility == "pub", "", "git", "", r.code, r.pagesUrl, "", "",
        listOf("project" to r.project, "visibility" to r.visibility, "default_branch" to r.defaultBranch, "pages_url" to r.pagesUrl, "github_url" to r.githubUrl),
    )

    /** The one document the pages draw: [c0] plus every declared repo, then the macro groups remapped (group_remap) and, in a project whose groups are registry families, each container regrouped by its family. */
    fun full(decl: Decl, c0: ContainersModel.Catalog): ContainersModel.Catalog {
        val c = regroup(decl, ContainersModel.Catalog(c0.groups, c0.entries + decl.repos.map { entryOf(decl, it) }))
        val entries = c.entries.map { e ->
            val p = decl.projectOf(e)
            if (e.role == "repo" || p == null || p.repoGroup.isNotBlank()) e else e.copy(group = decl.containerFamily[e.name] ?: decl.containerGroup)
        }
        // Group order: the document's, then each project's registry families in the declared project order, then the rest as they appear.
        val doc = c.groups
        val famOrder = decl.projects.flatMap { p -> decl.families[p.id].orEmpty() } + decl.projects.map { it.repoGroup } + decl.containerGroup
        val labels = (doc.map { it.label } + famOrder + entries.map { it.group }).filter { l -> entries.any { it.group == l } }.distinct()
        val groups = labels.map { l ->
            val docCats = doc.firstOrNull { it.label == l }?.categories.orEmpty()
            val inGroup = entries.filter { it.group == l }
            val cats = (docCats.map { it.label } + inGroup.map { it.category }).distinct()
            ContainersModel.Group(doc.firstOrNull { it.label == l }?.id ?: l, l, cats.map { cat ->
                val first = docCats.firstOrNull { it.label == cat }?.names.orEmpty().filter { n -> inGroup.any { it.name == n && it.category == cat } }
                ContainersModel.Category(cat, first + inGroup.filter { it.category == cat && it.name !in first }.map { it.name })
            })
        }
        return ContainersModel.Catalog(groups, entries)
    }

    /** [c] with every container of a remapped macro group moved to its target group (declared `group_remap`, e.g. Project Apps -> Cloud Apps). */
    fun regroup(decl: Decl, c: ContainersModel.Catalog): ContainersModel.Catalog {
        val map = decl.groupRemap.filter { (from, to) -> from != to }
        if (map.isEmpty()) return c
        fun merge(cats: List<ContainersModel.Category>) =
            cats.groupBy { it.label }.map { (l, v) -> ContainersModel.Category(l, v.flatMap { it.names }) }
        // A target the document lacks is created from its sources, so a remapped group never survives under its old name.
        val targets = map.values.distinct().filter { t -> c.groups.none { it.label == t } }
        val groups = (c.groups + targets.map { t -> ContainersModel.Group(t, t, emptyList()) }).filter { it.label !in map }.map { g ->
            g.copy(categories = merge(g.categories + c.groups.filter { map[it.label] == g.label }.flatMap { it.categories }))
        }
        return ContainersModel.Catalog(groups, c.entries.map { e -> map[e.group]?.let { e.copy(group = it) } ?: e })
    }

    /** The containers of [projectId] (null = every project) and of [kindId] (null = every kind) only, groups and categories kept in the document's order (empty categories dropped). */
    fun scope(decl: Decl, c0: ContainersModel.Catalog, projectId: String?, kindId: String? = null): ContainersModel.Catalog {
        val c = full(decl, c0)
        val keep = c.entries.filter { (projectId == null || decl.projectOf(it)?.id == projectId) && (kindId == null || decl.kindsOf(it).any { k -> k.id == kindId }) }
        val names = keep.map { it.name }.toSet()
        val groups = c.groups.map { g ->
            g.copy(categories = g.categories.map { cat -> cat.copy(names = cat.names.filter { it in names }) }.filter { it.names.isNotEmpty() })
        }
        return ContainersModel.Catalog(groups, keep)
    }

    /**
     * The Table's rows for [projectId] (null = All) and [kindId] (null = every kind). In All the rows are clustered by project in the declared order and the
     * Group cell reads "<Project> / <group>", so the project grouping is visible there too.
     */
    fun rows(decl: Decl, c: ContainersModel.Catalog, projectId: String?, kindId: String? = null): List<RoutesTableModel.Row> {
        val scoped = scope(decl, c, projectId, kindId)
        val byName = scoped.entries.associateBy { it.name }
        val rows = ContainersModel.rows(scoped)
        if (projectId != null) return rows
        val order = decl.projects.map { it.id }
        return rows.sortedBy { r -> order.indexOf(byName[r.container]?.let { decl.projectOf(it)?.id }).let { if (it < 0) order.size else it } }
            .map { r -> byName[r.container]?.let { decl.projectOf(it) }?.let { p -> r.copy(group = "${p.label} / ${r.group}") } ?: r }
    }
}

/** The page-wide Filters selection of the Containers section (All | MCPs | APIs | Apps | DBs | Repos): drawn once above the project tabs, read by every Topology and Table page of every project. */
object ContainerFilter {
    var kind by androidx.compose.runtime.mutableStateOf<String?>(null)
}
