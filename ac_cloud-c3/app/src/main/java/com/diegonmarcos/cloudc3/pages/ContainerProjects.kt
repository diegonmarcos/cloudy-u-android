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
    data class Project(val id: String, val label: String, val code: String, val folderPrefix: List<String>, val namePrefix: List<String>, val repoGroup: String = "", val category: String = "")

    /** One owner git repo, generated into data/repos_declared.json from the fleet registry (data/gen_repos_slice.py). */
    data class Repo(
        val name: String, val project: String, val group: String, val category: String, val code: String,
        val visibility: String, val defaultBranch: String, val pagesUrl: String, val githubUrl: String,
        /** #1953 the registry lfs flag, role repo | hub, the GitHub name, and every project the repo is attached to (primary first). */
        val lfs: Boolean = false, val role: String = "repo", val github: String = "", val attached: List<Attach> = emptyList(),
    )

    /** #1953 one project a repo is drawn on: [via] code (registry prefix) | hub (a hub under that code) | declared (project_repos), [path] inside the repo. */
    data class Attach(val project: String, val path: String = "", val via: String = "code")

    /** #1971 the VM view's declared words: page id prefix, the All / unknown host ids, the mode labels, the repos band and its reason, the unknown-host reason (`{host}`). */
    data class VmView(
        val prefix: String = "", val all: String = "all", val unknown: String = "unknown", val modes: List<Pair<String, String>> = emptyList(),
        val reposBand: String = "repos", val reposBandLabel: String = "", val reposReason: String = "", val unknownReason: String = "",
        /** The declared order of the host pages and the declared short label per device (never sorted in code). */
        val hostOrder: List<String> = emptyList(),
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
        /** #1941 Type (App | Db | MCP | API | Repo | Other), all declared in data/container_projects.json: the labels in order, kind id -> label, and the App categories that are Other. */
        val types: List<String> = emptyList(), val typeOfKind: Map<String, String> = emptyMap(), val otherCategories: List<String> = emptyList(),
        /** #1953 E the kind whose Type an LFS repo takes (db) and its db engine (git-lfs), declared under `lfs`. */
        val lfsKind: String = "", val lfsEngine: String = "",
        /** #1953 the Topology layout (bands, sections, the Public / Private split), declared under `topology_layout`; the raw object, read by [ContainerTopology]. */
        val layout: JsonObject? = null,
        /** #1969 Infra > Projects: the declared columns (key, label), filter chips (column keys) and the page a row tap opens (`{project}` = the project id), under `projects_table`. */
        val projectsColumns: List<Pair<String, String>> = emptyList(), val projectsFilters: List<String> = emptyList(), val openPage: String = "",
        /** #1971 the VM mode (container_projects.json::vm_view) and the derived host ids (ContainerHosts.derive; set by Declarations). */
        val vm: VmView = VmView(), val hostIds: List<String> = emptyList(),
    ) {
        /** A VM-mode page id (`vm-<host>`): not a project, scoped by host. */
        fun isVmPage(id: String): Boolean = vm.prefix.isNotBlank() && id.startsWith(vm.prefix)
        /** A page id the scope knows: a project or a VM page. */
        fun scopeKnown(id: String): Boolean = project(id) != null || isVmPage(id)
        /** The label of a scope id: the project label, the VM page's host, else All. */
        fun scopeLabel(id: String?): String = id?.let { project(it)?.label ?: if (isVmPage(it)) it.removePrefix(vm.prefix) else null } ?: "All"
        private fun vmMatch(e: ContainersModel.Entry, h: String): Boolean =
            h == vm.all || (e.role != "repo" && (if (h == vm.unknown) e.host !in hostIds else e.host == h))
        fun vmScopeMatches(e: ContainersModel.Entry, pageId: String): Boolean = vmMatch(e, pageId.removePrefix(vm.prefix))

        /** The first-row categories (the Cloud Me > Proj groups; Lib is Wiki since #1968), in the declared order. */
        fun categories(): List<String> = projects.map { it.category }.filter { it.isNotBlank() }.distinct()

        /** The projects of the first-row category [category], in the declared order. */
        fun projectsOf(category: String): List<Project> = projects.filter { it.category == category }

        /** The repo behind a role-repo entity [e] (null for a container). */
        fun repoOf(e: ContainersModel.Entry): Repo? = if (e.role == "repo") repos.firstOrNull { it.name == e.name } else null

        /** The Type of [e]: a git repo is Repo (an LFS repo is the declared `lfs.type_kind` type, Db), else its kind's declared type, and an App in an `otherCategories` category is Other. */
        fun typeOf(e: ContainersModel.Entry): String {
            if (e.role == "repo") return if (repoOf(e)?.lfs == true && lfsKind.isNotBlank()) typeOfKind[lfsKind].orEmpty() else typeOfKind["repo"].orEmpty()
            val t = typeOfKind[kindOf(e)?.id].orEmpty()
            return if (t == typeOfKind["apps"] && e.category in otherCategories) types.lastOrNull().orEmpty() else t
        }

        fun project(id: String?): Project? = projects.firstOrNull { it.id == id }
        fun kind(id: String?): Kind? = kinds.firstOrNull { it.id == id }

        /** The explicit name override, else the first project whose folder or name prefix matches [e], else the declared default (null only for an empty declaration). */
        fun projectOf(e: ContainersModel.Entry): Project? =
            project(repos.firstOrNull { it.name == e.name && e.role == "repo" }?.project)
                ?: project(nameOverrides[e.name])
                ?: projects.firstOrNull { p -> p.folderPrefix.any { e.folder.startsWith(it) } || p.namePrefix.any { e.name.startsWith(it) } }
                ?: project(default)

        /** #1953 every project [e] is drawn on: a repo's attached projects (primary first: code, hub, declared), else its one [projectOf]. */
        fun projectsOf(e: ContainersModel.Entry): List<Project> =
            repoOf(e)?.attached?.mapNotNull { project(it.project) }?.distinct()?.takeIf { it.isNotEmpty() } ?: listOfNotNull(projectOf(e))

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
            p.s("id").takeIf { it.isNotBlank() }?.let { Project(it, p.s("label").ifBlank { it }, p.s("code"), p.l("folder_prefix"), p.l("name_prefix"), p.s("repo_group"), p.s("category")) }
        }
        val ks = (o["kinds"] as? JsonArray).orEmpty().mapNotNull { e ->
            val k = e as? JsonObject ?: return@mapNotNull null
            k.s("id").takeIf { it.isNotBlank() }?.let { Kind(it, k.s("label").ifBlank { it }, k.l("name_suffix"), k.l("name_contains"), k.l("category_prefix"), k.l("role")) }
        }
        val ro = runCatching { Json.parseToJsonElement(reposText) as? JsonObject }.getOrNull()
        val repos = (ro?.get("repos") as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            r.s("name").takeIf { it.isNotBlank() }?.let {
                val att = (r["attached"] as? JsonArray).orEmpty().mapNotNull { a -> (a as? JsonObject)?.let { x -> x.s("project").takeIf { it.isNotBlank() }?.let { Attach(it, x.s("path"), x.s("via").ifBlank { "code" }) } } }
                Repo(it, r.s("project"), r.s("group"), r.s("category"), r.s("code"), r.s("visibility"), r.s("default_branch"), r.s("pages_url"), r.s("github_url"),
                    (r["lfs"] as? JsonPrimitive)?.contentOrNull == "true", r.s("role").ifBlank { "repo" }, r.s("github").ifBlank { it }, att)
            }
        }
        val fams = (ro?.get("projects") as? JsonObject).orEmpty().mapValues { (_, v) -> ((v as? JsonObject)?.l("families")).orEmpty() }
        return Decl(
            o.s("default"), ps, o.m("name_overrides"), ks, o.s("default_kind"), o.m("group_remap"),
            o.m("container_family"), o.s("container_group").ifBlank { "Containers" }, repos, fams,
            o.l("types"), o.m("type_of_kind"), o.l("other_categories"),
            (o["lfs"] as? JsonObject)?.s("type_kind").orEmpty(), (o["lfs"] as? JsonObject)?.s("db_engine").orEmpty(), o["topology_layout"] as? JsonObject,
            ((o["projects_table"] as? JsonObject)?.get("columns") as? JsonArray).orEmpty().mapNotNull { c -> (c as? JsonObject)?.let { it.s("key").takeIf { k -> k.isNotBlank() }?.let { k -> k to it.s("label").ifBlank { k } } } },
            (o["projects_table"] as? JsonObject)?.l("filters").orEmpty(), (o["projects_table"] as? JsonObject)?.s("open_page").orEmpty(),
            (o["vm_view"] as? JsonObject)?.let { v -> VmView(v.s("page_prefix"), v.s("all").ifBlank { "all" }, v.s("unknown").ifBlank { "unknown" },
                (v["modes"] as? JsonArray).orEmpty().mapNotNull { m -> (m as? JsonObject)?.let { it.s("id") to it.s("label") } }, v.s("repos_band"), v.s("repos_band_label"), v.s("repos_reason"), v.s("unknown_reason"), v.l("host_order")) } ?: VmView(),
        )
    }

    /** A repo as a Containers entity (role "repo"): the Table row and the Topology tile read it like any container. */
    fun entryOf(decl: Decl, r: Repo): ContainersModel.Entry = ContainersModel.Entry(
        r.name, "", "repo", r.group, r.category, "${decl.project(r.project)?.label.orEmpty()} repo, ${r.visibility}", "", "", "", null,
        r.visibility == "pub", "", if (r.lfs) decl.lfsEngine.ifBlank { "git" } else "git", "", r.code, r.pagesUrl, "", "",
        listOf("project" to r.project, "visibility" to r.visibility, "default_branch" to r.defaultBranch, "pages_url" to r.pagesUrl, "github_url" to r.githubUrl) +
            repoExtras(r),
    )

    /** #1953 the added facts of a repo entity (and its Table row meta): lfs, repo role, every attached project, the paths inside the repo. */
    fun repoExtras(r: Repo): List<Pair<String, String>> = listOf(
        "lfs" to r.lfs.toString(), "repo_role" to r.role,
        "attached_projects" to r.attached.joinToString(", ") { it.project },
        "path" to r.attached.filter { it.path.isNotBlank() }.joinToString(", ") { "${it.project}:${it.path}" },
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
    fun scope(decl: Decl, c0: ContainersModel.Catalog, projectId: String?, kindId: String? = null, types: Set<String> = emptySet(), vms: Set<String> = emptySet()): ContainersModel.Catalog {
        val c = full(decl, c0)
        val keep = c.entries.filter {
            (projectId == null || (if (decl.isVmPage(projectId)) decl.vmScopeMatches(it, projectId) else decl.projectsOf(it).any { p -> p.id == projectId })) && (kindId == null || decl.kindsOf(it).any { k -> k.id == kindId }) &&
                (types.isEmpty() || decl.typeOf(it) in types) && (vms.isEmpty() || it.host in vms)
        }
        val names = keep.map { it.name }.toSet()
        val groups = c.groups.map { g ->
            g.copy(categories = g.categories.map { cat -> cat.copy(names = cat.names.filter { it in names }) }.filter { it.names.isNotEmpty() })
        }
        return ContainersModel.Catalog(groups, keep)
    }

    /**
     * The Table's rows for [projectId] (null = All) and [kindId] (null = every kind). In All the rows are clustered by project in the declared order
     * (each row names its project in the Project column).
     */
    fun rows(decl: Decl, c: ContainersModel.Catalog, projectId: String?, kindId: String? = null): List<RoutesTableModel.Row> {
        val scoped = scope(decl, c, projectId, kindId)
        val byName = scoped.entries.associateBy { it.name }
        // #1941 the Project and Type columns (the Cat1 column is the old Group, Cat2 the Category); the old "<Project> / <group>" prefix of the All table is the Project column now.
        val rows = ContainersModel.rows(scoped).map { r ->
            val e = byName[r.container]
            // #1953 a project table names its own project (an attached repo is on it too); All names each row's primary project
            val p = (if (projectId != null) decl.project(projectId) else null) ?: e?.let { decl.projectOf(it) }
            val type = e?.let { decl.typeOf(it) }.orEmpty()
            // #1953 a repo row's meta carries lfs, repo_role, attached_projects, path through Entry.fields() (entryOf's extras)
            r.copy(project = p?.label.orEmpty(), type = type, meta = r.meta + listOf("project_name" to p?.label.orEmpty(), "type" to type))
        }
        if (projectId != null) return rows
        val order = decl.projects.map { it.label }
        return rows.sortedBy { r -> order.indexOf(r.project).let { if (it < 0) order.size else it } }
    }
}

/**
 * #1941 the Topology's own filters (Status, VM, Type), read by the container dashboards. The old page-wide kind Filters row is gone: its kinds
 * are the Type filter. The Table keeps its own selection in RoutesTableModel.State.
 */
object ContainerFilter {
    var lights by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
    var vms by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
    var types by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
    fun reset() { lights = emptySet(); vms = emptySet(); types = emptySet() }
    fun toggled(s: Set<String>, x: String): Set<String> = if (x in s) s - x else s + x
}
