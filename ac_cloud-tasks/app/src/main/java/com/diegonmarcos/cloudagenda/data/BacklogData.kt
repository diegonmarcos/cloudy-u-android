package com.diegonmarcos.cloudagenda.data

import android.content.Context
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspaceClient
import com.diegonmarcos.superapp.uikit.KitFileTree
import org.json.JSONObject
import java.io.File

/**
 * #1974 One ticket of the Product Backlog (cloud-me-pvt-tasks, 1___BACKLOG___/1.1.Product-Backlog/tasks/<session>/<id>.json), reduced to what the
 * Backlog list and the RoadMap draw. The project identity is the one the ticket carries in metadata.scope: the ids (project "1.1", family "1.1.1"),
 * their names (names.project "Cloudy", names.family "Android") and the repos it touches. The Home selector's chips are those project names, so a
 * selection of "Cloudy" keeps the tickets whose scope names that project (or its id, its family, or one of its repos).
 */
data class BacklogTicket(
    val id: String,
    val subject: String,
    val status: String,
    val projectId: String = "",
    val familyId: String = "",
    val projectName: String = "",
    val familyName: String = "",
    val repos: List<String> = emptyList(),
) {
    /** The project shown on the chip and the card: the scope's project name, else its id. */
    val project: String get() = projectName.ifBlank { projectId }

    val num: Int get() = id.toIntOrNull() ?: 0

    val done: Boolean get() = status == "completed"

    val inProgress: Boolean get() = status == "in_progress"

    /** Every name this ticket answers to in a selection. */
    val keys: Set<String> get() = (listOf(projectName, projectId, familyName, familyId) + repos).filter { it.isNotBlank() }.toSet()

    /** A ticket with no scope belongs to no project: only "All" shows it (same rule as ProjectSelection.includes). */
    fun inSelection(sel: ProjectSelection): Boolean = sel.all || keys.any { it in sel.ids }
}

/** What a backlog source hands over: the tickets, the 4___ASSETS___ tree, where it came from and, when empty, why. */
data class BacklogSnapshot(
    val tickets: List<BacklogTicket> = emptyList(),
    val assets: List<KitFileTree.Entry<String>> = emptyList(),
    val origin: String = "",
    val reason: String? = null,
)

/** One bucket of the RoadMap timeline: the tickets whose number lies in [from]..[to] (the ticket number is the order they were filed in). */
data class RoadEpoch(val from: Int, val to: Int, val done: Int, val inProgress: Int, val open: Int) {
    val total: Int get() = done + inProgress + open
}

/** One project of the RoadMap: its counts, its families and its timeline, newest bucket first. */
data class RoadProject(val name: String, val done: Int, val inProgress: Int, val open: Int, val families: List<Pair<String, Int>>, val epochs: List<RoadEpoch>, val active: List<BacklogTicket>) {
    val total: Int get() = done + inProgress + open
}

/** The pure half of Backlog, RoadMap and Assets: parse, filter by the Home selection, group. */
object Backlog {
    const val REPO = "cloud-me-pvt-tasks"
    const val TASKS_DIR = "1___BACKLOG___/1.1.Product-Backlog/tasks"
    const val ASSETS_DIR = "4___ASSETS___"
    const val FIXTURE = "backlog-fixture.json"
    const val EPOCH = 100

    fun parse(o: JSONObject): BacklogTicket? {
        val id = o.optString("id").ifBlank { return null }
        val scope = o.optJSONObject("metadata")?.optJSONObject("scope")
        val names = scope?.optJSONObject("names")
        val repos = scope?.optJSONArray("repos")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return BacklogTicket(
            id, o.optString("subject"), o.optString("status"), scope?.optString("project").orEmpty(), scope?.optString("family").orEmpty(),
            names?.optString("project").orEmpty(), names?.optString("family").orEmpty(), repos,
        )
    }

    fun parse(json: String): BacklogTicket? = runCatching { parse(JSONObject(json)) }.getOrNull()

    /** The tickets a Home selection keeps (empty selection of "All" keeps everything; "None" keeps nothing). */
    fun filter(tickets: List<BacklogTicket>, sel: ProjectSelection): List<BacklogTicket> = if (sel.all) tickets else tickets.filter { it.inSelection(sel) }

    /** The project names the backlog contributes to the Home selector. */
    fun projectNames(tickets: List<BacklogTicket>): List<String> = tickets.map { it.project }.filter { it.isNotBlank() }.distinct().sorted()

    /** The RoadMap: one entry per project (most tickets first), each with its timeline buckets of [EPOCH] ticket numbers. */
    fun roadmap(tickets: List<BacklogTicket>): List<RoadProject> =
        tickets.groupBy { it.project.ifBlank { "(no project)" } }.map { (name, rows) ->
            val epochs = rows.groupBy { it.num / EPOCH }.toSortedMap(compareByDescending { it }).map { (e, r) ->
                RoadEpoch(e * EPOCH, e * EPOCH + EPOCH - 1, r.count { it.done }, r.count { it.inProgress }, r.count { !it.done && !it.inProgress })
            }
            RoadProject(
                name, rows.count { it.done }, rows.count { it.inProgress }, rows.count { !it.done && !it.inProgress },
                rows.groupBy { it.familyName.ifBlank { it.familyId } }.filterKeys { it.isNotBlank() }.map { (f, r) -> f to r.size }.sortedByDescending { it.second },
                epochs, rows.filter { !it.done }.sortedByDescending { it.num },
            )
        }.sortedWith(compareByDescending<RoadProject> { it.total }.thenBy { it.name })

    /** Assets entries: [path] is relative to 4___ASSETS___ ("1842/shot.png"). */
    fun assetEntry(path: String, bytes: Long, modified: Long): KitFileTree.Entry<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return KitFileTree.Entry(parts.dropLast(1), parts.last(), bytes, modified, KitFileTree.typeOf(parts.last(), ""), path)
    }

    fun parseFixture(json: String): BacklogSnapshot {
        val o = JSONObject(json)
        val t = o.optJSONArray("tickets")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { parse(it) } }.orEmpty()
        val a = o.optJSONArray("assets")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { assetEntry(it.getString("path"), it.optLong("size"), 0L) } }.orEmpty()
        return BacklogSnapshot(t, a, "fixture (" + FIXTURE + ", FAKE)")
    }

    /** Reads a clone of cloud-me-pvt-tasks: every ticket JSON and the 4___ASSETS___ tree. Never throws; an unreadable clone says why. */
    fun readClone(root: File): BacklogSnapshot {
        val tasks = File(root, TASKS_DIR)
        if (!tasks.isDirectory) return BacklogSnapshot(origin = root.path, reason = "The clone has no $TASKS_DIR (or the app may not read it).")
        val tickets = tasks.listFiles().orEmpty().filter { it.isDirectory }.flatMap { d -> d.listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList() }
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }.sortedByDescending { it.num }
        val assetsRoot = File(root, ASSETS_DIR)
        val assets = if (!assetsRoot.isDirectory) emptyList() else assetsRoot.walkTopDown().filter { it.isFile }.take(5000).map {
            assetEntry(it.relativeTo(assetsRoot).invariantSeparatorsPath, it.length(), it.lastModified())
        }.toList()
        return BacklogSnapshot(tickets, assets, root.path)
    }
}

/** Where the Backlog, RoadMap and Assets read from. */
fun interface BacklogSource {
    /** Blocking: call off the main thread. */
    fun load(): BacklogSnapshot

    companion object {
        val EMPTY = object : BacklogSource { override fun load() = BacklogSnapshot(reason = "No backlog source.") }
    }
}

/**
 * The real source: the clone of cloud-me-pvt-tasks that Cloud Drive keeps (found through libs:repo-workspace, the fleet's one contract for where
 * a repository is; the backlog is private, so it is never baked into this APK). The debug build falls back to its FAKE fixture so the pages draw
 * on an emulator without Drive; a release build says exactly what is missing.
 */
class DriveBacklogSource(private val context: Context) : BacklogSource {
    override fun load(): BacklogSnapshot {
        val client = RepoWorkspaceClient(context)
        val reason: String
        if (!client.isServingAppInstalled()) reason = "Cloud Drive is not installed: it keeps the clone of $REPO."
        else {
            val path = client.findRepo(Backlog.REPO).firstOrNull()?.path
            if (path == null) reason = "Cloud Drive has no clone of $REPO (Sync > Git > Clone)."
            else {
                val snap = Backlog.readClone(File(path))
                if (snap.tickets.isNotEmpty() || snap.assets.isNotEmpty()) return snap
                reason = snap.reason ?: "The clone holds no tickets."
            }
        }
        return runCatching { Backlog.parseFixture(context.assets.open(Backlog.FIXTURE).bufferedReader().use { it.readText() }).copy(reason = "$reason Showing the debug fixture.") }
            .getOrElse { BacklogSnapshot(reason = reason) }
    }

    private companion object { const val REPO = Backlog.REPO }
}
