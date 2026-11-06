package com.diegonmarcos.superapp.fleetdata.ui

import com.diegonmarcos.superapp.fleetdata.provider.FleetDataStore
import com.diegonmarcos.superapp.fleetdata.provider.FleetManifest

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

    /** Every cached dataset, grouped by endpoint (endpoints sorted: shared first, then app/<pkg> alphabetically; rows by dataset name). */
    fun groups(entries: List<FleetDataStore.Entry>, manifest: FleetManifest, failed: Set<String>, authority: String): List<EndpointGroup> =
        entries.map { rowOf(it, manifest, failed, authority) }
            .groupBy { endpointOf(it.path) }
            .toList()
            .sortedBy { (ep, _) -> if (ep == "shared") "0" else "1$ep" }
            .map { (ep, rows) -> EndpointGroup(ep, uri(authority, "$ep/"), rows.sortedBy { it.dataset }) }
}
