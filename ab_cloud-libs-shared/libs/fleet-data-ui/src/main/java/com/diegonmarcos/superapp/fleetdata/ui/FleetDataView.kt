package com.diegonmarcos.superapp.fleetdata.ui

import com.diegonmarcos.superapp.fleetdata.provider.FleetDataStore
import com.diegonmarcos.superapp.fleetdata.provider.FleetManifest
import com.diegonmarcos.superapp.uikit.KitFileTree
import com.diegonmarcos.superapp.uikit.KitState

/**
 * The pure view-model behind the "Fleet Data" page (JVM-tested; no Android, no I/O): it turns this provider's cached datasets
 * (FleetDataStore.list) and its served manifest (consumers + tier + source) into rows GROUPED BY the IPC endpoint that serves them
 * (`content://<authority>/app/<pkg>/...`, `/shared/...`, `/index`). Nothing here reaches the network or a file; the Composable only draws it.
 */
enum class SyncState { OK, STALE, ERROR }

/** One served dataset as a row: its path under the provider, the full content:// URI, and everything the page shows about it. */
data class DatasetRow(
    val path: String,
    val uri: String,
    val dataset: String,
    val tier: Int?,
    val schemaVersion: Int?,
    val size: Long?,
    val etag: String?,
    val syncedAt: Long,
    val source: String?,
    val consumers: List<String>,
    val state: SyncState,
)

/** The datasets one IPC endpoint serves (endpoint = `app/<pkg>`, `shared`, or `index`). */
data class EndpointGroup(val endpoint: String, val endpointUri: String, val rows: List<DatasetRow>)

/**
 * The file-browser bucket a dataset falls in, for the pane's top filter chip row:
 *  - [APP]     — an `app/<pkg>` endpoint (tier 3 by construction): a per-app file served to that one package.
 *  - [USER]    — tier 1 (personal) / tier 2 (Account) data: only the Account host serves these; EMPTY on the Store authority.
 *  - [RUNTIME] — tier 4 reference data (lookups the fleet reads at runtime).
 *  - [OTHER]   — the index, shared tier-3 defaults, and anything whose tier the manifest does not declare (null).
 */
enum class FileBucket(val label: String) { ALL("All"), APP("App Files"), USER("User Files"), RUNTIME("RunTime Files"), OTHER("Others") }

object FleetDataView {
    fun uri(authority: String, path: String): String = "content://$authority/$path"

    /** The endpoint a stored path belongs to: `app/<pkg>` for app/<pkg>/<dataset>, `shared` for shared/<dataset>, else the first segment. */
    fun endpointOf(path: String): String {
        val s = path.split('/')
        return when {
            s.size >= 3 && s[0] == "app" -> "app/${s[1]}"
            s.isNotEmpty() && s[0] == "shared" -> "shared"
            else -> s.firstOrNull().orEmpty()
        }
    }

    /** The dataset name inside its endpoint (keeps nested names like `tools/sensors`). */
    fun datasetOf(path: String): String {
        val s = path.split('/')
        return when {
            s.size >= 3 && s[0] == "app" -> s.drop(2).joinToString("/")
            s.size >= 2 && s[0] == "shared" -> s.drop(1).joinToString("/")
            else -> path
        }
    }

    /** The tier: a shared dataset's is the manifest's; an app dataset is tier 3 (app default) by construction. */
    fun tierOf(path: String, manifest: FleetManifest): Int? {
        val s = path.split('/')
        return when {
            s.firstOrNull() == "shared" -> manifest.shared[datasetOf(path)]?.tier
            s.firstOrNull() == "app" -> 3
            else -> null
        }
    }

    /** Who may read it: a shared dataset's manifest consumers; an app dataset is readable by that one package. */
    fun consumersOf(path: String, manifest: FleetManifest): List<String> {
        val s = path.split('/')
        return when {
            s.firstOrNull() == "shared" -> manifest.shared[datasetOf(path)]?.consumers ?: emptyList()
            s.size >= 2 && s.firstOrNull() == "app" -> listOf(s[1])
            else -> emptyList()
        }
    }

    /** The repo/homelab source path: the manifest `source` for a shared dataset, else the conventional data/fleet path. */
    fun sourceOf(path: String, manifest: FleetManifest): String? {
        val s = path.split('/')
        return when {
            s.firstOrNull() == "shared" -> manifest.shared[datasetOf(path)]?.source ?: "data/fleet/${datasetOf(path)}.json"
            s.firstOrNull() == "app" -> "data/fleet/$path.json"
            else -> null
        }
    }

    /** error if the last sync listed it as failed; stale if it has never been synced; otherwise ok. */
    fun stateOf(path: String, meta: FleetDataStore.Meta?, failed: Set<String>): SyncState = when {
        failed.contains(path) || failed.contains(datasetOf(path)) -> SyncState.ERROR
        meta == null || meta.syncedAt <= 0L -> SyncState.STALE
        else -> SyncState.OK
    }

    fun rowOf(entry: FleetDataStore.Entry, manifest: FleetManifest, failed: Set<String>, authority: String): DatasetRow {
        val m = entry.meta
        return DatasetRow(
            path = entry.path,
            uri = uri(authority, entry.path),
            dataset = datasetOf(entry.path),
            tier = tierOf(entry.path, manifest),
            schemaVersion = m?.schemaVersion,
            size = m?.size,
            etag = m?.etag,
            syncedAt = m?.syncedAt ?: 0L,
            source = sourceOf(entry.path, manifest),
            consumers = consumersOf(entry.path, manifest),
            state = stateOf(entry.path, m, failed),
        )
    }

    /** Every cached dataset, grouped by endpoint (endpoints sorted: index, shared, then app/<pkg> alphabetically; rows by dataset name). */
    fun groups(entries: List<FleetDataStore.Entry>, manifest: FleetManifest, failed: Set<String>, authority: String): List<EndpointGroup> =
        entries.map { rowOf(it, manifest, failed, authority) }
            .groupBy { endpointOf(it.path) }
            .toList()
            .sortedBy { (ep, _) -> when (ep) { "index" -> "0"; "shared" -> "1"; else -> "2$ep" } }
            .map { (ep, rows) -> EndpointGroup(ep, uri(authority, "$ep/"), rows.sortedBy { it.dataset }) }

    /**
     * The file-browser bucket of a row: an `app/<pkg>` dataset is always [FileBucket.APP]; otherwise the tier decides —
     * 1/2 = [FileBucket.USER] (personal/Account, empty on the Store authority), 4 = [FileBucket.RUNTIME] reference data,
     * everything else (shared tier-3 defaults, index, and an undeclared null tier) = [FileBucket.OTHER].
     */
    fun bucketOf(row: DatasetRow): FileBucket = when {
        endpointOf(row.path).startsWith("app/") -> FileBucket.APP
        row.tier == 1 || row.tier == 2 -> FileBucket.USER
        row.tier == 4 -> FileBucket.RUNTIME
        else -> FileBucket.OTHER
    }

    /**
     * The dense collapsible tree's input: one [KitFileTree.Entry] per dataset, foldered by its endpoint (`index`, `shared`,
     * `app/<pkg>`) so each endpoint draws as one collapsible folder with its files beneath. Payload is the row itself (a tap
     * hands it back for the per-file action sheet). Pure; the Composable only draws what [KitFileTree.rows] makes of this.
     */
    fun treeEntries(groups: List<EndpointGroup>): List<KitFileTree.Entry<DatasetRow>> =
        groups.flatMap { g -> g.rows.map { r -> KitFileTree.Entry(listOf(g.endpoint), r.dataset, r.size ?: 0L, r.syncedAt, "json", r) } }

    // ── presentation helpers (pure; the Composable only draws what these return) ─────────────────────────────────────────────

    /** The status pill: ok = green, stale = amber, error = red. */
    fun kitState(s: SyncState): KitState = when (s) { SyncState.OK -> KitState.OK; SyncState.STALE -> KitState.WARN; SyncState.ERROR -> KitState.BAD }
    fun pillWord(s: SyncState): String = s.name.lowercase()

    /** "T3" tier chip; "T-" when the manifest declares none. */
    fun tierChip(tier: Int?): String = "T${tier ?: "-"}"
    fun schemaChip(v: Int?): String = "schema v${v ?: "-"}"
    fun sizeLabel(n: Long?): String = when {
        n == null -> "-"
        n < 1024 -> "$n B"
        else -> "${n / 1024} KB"
    }

    /** "2m ago" style; never synced = "never"; a clock skew into the future reads "just now". */
    fun relativeTime(at: Long, now: Long): String {
        if (at <= 0L) return "never"
        val s = (now - at) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            s < 86400 * 30 -> "${s / 86400}d ago"
            else -> "${s / (86400 * 30)}mo ago"
        }
    }

    /** An ETag cut to `head…tail` (W/"30abb9…ef83") unless [expanded] or short enough to show whole. */
    fun etagLabel(etag: String?, expanded: Boolean): String {
        if (etag == null) return "-"
        return if (expanded || etag.length <= ETAG_HEAD + ETAG_TAIL + 2) etag else etag.take(ETAG_HEAD) + "…" + etag.takeLast(ETAG_TAIL)
    }
    private const val ETAG_HEAD = 9
    private const val ETAG_TAIL = 5

    /** "all" for the wildcard, the name for one consumer, a count for several (the full list when [expanded]). */
    fun consumersLabel(consumers: List<String>, expanded: Boolean): String = when {
        consumers.isEmpty() -> "none"
        consumers == listOf("*") -> "all"
        expanded -> consumers.joinToString()
        consumers.size == 1 -> consumers[0]
        else -> "${consumers.size} consumers"
    }

    /**
     * Groups narrowed by a free-text [query] (dataset, URI, source, ETag, consumers), an optional sync [status], and an
     * optional [bucket] (App / User / RunTime / Others; [FileBucket.ALL] or null does not narrow). Empty groups drop out.
     */
    fun filter(groups: List<EndpointGroup>, query: String, status: SyncState?, bucket: FileBucket? = null): List<EndpointGroup> {
        val q = query.trim().lowercase()
        val b = bucket.takeUnless { it == FileBucket.ALL }
        fun hit(r: DatasetRow) = (status == null || r.state == status) && (b == null || bucketOf(r) == b) && (q.isEmpty() ||
            listOf(r.dataset, r.uri, r.source.orEmpty(), r.etag.orEmpty(), r.consumers.joinToString(" ")).any { it.lowercase().contains(q) })
        return groups.mapNotNull { g -> g.rows.filter(::hit).takeIf { it.isNotEmpty() }?.let { g.copy(rows = it) } }
    }

    /** The overall line: "9 datasets · all ok · last sync 2m ago". */
    fun summary(rows: List<DatasetRow>, now: Long): String {
        val ok = rows.count { it.state == SyncState.OK }
        val stale = rows.count { it.state == SyncState.STALE }
        val err = rows.count { it.state == SyncState.ERROR }
        val health = if (stale == 0 && err == 0) "all ok" else listOf("$ok ok", "$stale stale", "$err error").filterIndexed { i, _ -> i == 0 || (i == 1 && stale > 0) || (i == 2 && err > 0) }.joinToString(", ")
        val last = rows.maxOfOrNull { it.syncedAt } ?: 0L
        return "${rows.size} dataset${if (rows.size == 1) "" else "s"} · $health · last sync ${relativeTime(last, now)}"
    }

    fun summaryState(rows: List<DatasetRow>): KitState = when {
        rows.any { it.state == SyncState.ERROR } -> KitState.BAD
        rows.any { it.state == SyncState.STALE } -> KitState.WARN
        else -> KitState.OK
    }
}
