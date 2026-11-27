package com.diegonmarcos.cloudagenda.data

import java.time.LocalDate

/**
 * The Home page's project selector: which projects feed Inbox, Backlog, Tasks and Library. [all] = every project (also ones created
 * later, and rows that belong to no project); otherwise only the names in [ids]. Pure, so the merge is unit-tested without a device, and
 * it round-trips through [encode]/[decode] (committed to the prefs file, applied by every page that reads it).
 */
data class ProjectSelection(val all: Boolean = true, val ids: Set<String> = emptySet()) {
    /** Whether a row of project [project] is shown (blank = the row belongs to no project: only "All" shows it). */
    fun includes(project: String): Boolean = all || (project.isNotBlank() && project in ids)

    val none: Boolean get() = !all && ids.isEmpty()

    /** The names this selection stands for out of [known]. */
    fun resolve(known: Collection<String>): List<String> = if (all) known.toList() else known.filter { it in ids }

    /** One chip tapped: from "All" it becomes everything but [name]; when the set covers every known project again it collapses back to All. */
    fun toggle(name: String, known: Collection<String>): ProjectSelection {
        val cur = if (all) known.toSet() else ids
        val next = if (name in cur) cur - name else cur + name
        return if (known.isNotEmpty() && next.containsAll(known)) ALL else ProjectSelection(false, next)
    }

    fun encode(): String = if (all) "*" else "=" + ids.sorted().joinToString(SEP)

    companion object {
        const val SEP = "\u001F"
        val ALL = ProjectSelection(true, emptySet())
        val NONE = ProjectSelection(false, emptySet())

        fun decode(text: String?): ProjectSelection {
            val t = text.orEmpty()
            return if (t.startsWith("=")) ProjectSelection(false, t.substring(1).split(SEP).filter { it.isNotBlank() }.toSet()) else ALL
        }
    }
}

/** What a project's calendar does by itself (the per-project config in assets/projects.json::configs, read by name). */
data class ProjectConfig(val calendarMode: String? = null, val laneOrder: List<String> = emptyList())

/** One status card of the Home page. */
data class ProjectStatus(
    val name: String,
    val open: Int,
    val inProgress: Int,
    val done: Int,
    val overdue: Int,
    val nextDueTitle: String?,
    val nextDue: LocalDate?,
    val sample: Boolean = false,
    val projectId: String? = null,
) {
    val total: Int get() = open + inProgress + done
}

/** The pure half of the Home page and of the project filter: which projects exist, their status counts, what a selection keeps. */
object ProjectScopes {
    /** Every project: the database's (in their order), the fixture's, then the Product Backlog's (#1974: the scope names its tickets carry), once each. */
    fun names(items: List<MockTask>, snapshot: Snapshot, backlog: Collection<String> = emptyList()): List<String> =
        (snapshot.projects.sortedBy { it.position }.map { it.name } + items.map { it.projectName }.filter { it.isNotBlank() }.distinct().sorted() + backlog.filter { it.isNotBlank() }.distinct().sorted()).distinct()

    /** The rows a selection keeps (the project filter of Inbox, Backlog, Tasks and Library). */
    fun filter(items: List<MockTask>, sel: ProjectSelection): List<MockTask> = if (sel.all) items else items.filter { sel.includes(it.projectName) }

    /** The database part of the filter: the projects the selection keeps, with their lists and tasks. */
    fun filter(snapshot: Snapshot, sel: ProjectSelection): Snapshot {
        if (sel.all) return snapshot
        val projects = snapshot.projects.filter { sel.includes(it.name) }
        val lists = snapshot.lists.filter { l -> projects.any { it.id == l.projectId } }
        val tasks = snapshot.tasks.filter { t -> lists.any { it.id == t.listId } }
        return snapshot.copy(projects = projects, lists = lists, tasks = tasks)
    }

    /** One card per project of [names]: counts over its top-level tasks (open = to do or blocked, in progress = doing), overdue = not done and ended before [today], next due = the earliest end from [today] on. */
    fun statuses(items: List<MockTask>, snapshot: Snapshot, names: List<String>, today: LocalDate): List<ProjectStatus> = names.map { n ->
        val rows = items.filter { it.kind == "tasks" && it.parent == null && it.projectName == n }
        val live = rows.filter { it.status != MockStatus.DONE }
        val next = live.mapNotNull { t -> t.end(today)?.takeIf { !it.isBefore(today) }?.let { t to it } }.minWithOrNull(compareBy({ it.second }, { it.first.title }))
        val db = snapshot.projects.firstOrNull { it.name == n }
        ProjectStatus(
            n, open = live.count { it.status != MockStatus.DOING }, inProgress = live.count { it.status == MockStatus.DOING },
            done = rows.size - live.size, overdue = live.count { t -> t.end(today)?.isBefore(today) == true },
            nextDueTitle = next?.first?.title, nextDue = next?.second, sample = db?.sample == true, projectId = db?.id,
        )
    }

    /** The totals over the selected projects, for the summary card. */
    fun total(cards: List<ProjectStatus>): ProjectStatus = ProjectStatus(
        "Selected", cards.sumOf { it.open }, cards.sumOf { it.inProgress }, cards.sumOf { it.done }, cards.sumOf { it.overdue },
        cards.filter { it.nextDue != null }.minWithOrNull(compareBy({ it.nextDue }, { it.name }))?.nextDueTitle,
        cards.mapNotNull { it.nextDue }.minOrNull(),
    )
}

/** The per-project calendar config: the mode a project opens the calendar in and the order of its category lanes. */
class ProjectConfigs(private val byName: Map<String, ProjectConfig>) {
    fun of(name: String): ProjectConfig? = byName.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** The calendar mode picked by the selection, or null (the user's own choice stands). An explicit selection only: "All" never forces a mode. */
    fun autoMode(sel: ProjectSelection, known: Collection<String>): String? =
        if (sel.all) null else (known + sel.ids).distinct().filter { it in sel.ids }.firstNotNullOfOrNull { of(it)?.calendarMode }

    /** The configured lane order of the selected projects (all of them when "All"), the first project's lanes first. */
    fun laneOrder(sel: ProjectSelection, known: Collection<String>): List<String> =
        (known + sel.ids).distinct().filter { sel.includes(it) }.flatMap { of(it)?.laneOrder.orEmpty() }.distinct()

    companion object {
        val EMPTY = ProjectConfigs(emptyMap())
    }
}
