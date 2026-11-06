package com.diegonmarcos.superapp.fleetdata.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.io.File

/**
 * The provider's access policy over the store, free of Android (callingPackage comes in as a string):
 *  - `app/<pkg>/<dataset>`: only package <pkg> (its own data);
 *  - `shared/<dataset>`: a caller the served manifest names (or "*"); a dataset the manifest does not carry is never served;
 *  - `index`: what the caller can read, with versions;
 *  - the provider's own package reads everything (its Data page). Every request lands in the access log.
 */
class FleetDataServer(
    private val store: FleetDataStore,
    private val log: AccessLog,
    private val selfPackage: String,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    sealed interface Outcome {
        data class Served(val file: File) : Outcome
        data class Bytes(val bytes: ByteArray) : Outcome
        data class Denied(val why: String) : Outcome
        data object Missing : Outcome
    }

    fun manifest(): FleetManifest =
        store.read(Route.Shared(FleetManifest.DATASET))?.let { FleetManifest.parse(String(it)) } ?: FleetManifest.EMPTY

    fun open(caller: String?, segments: List<String>): Outcome {
        val route = Route.parse(segments) ?: return Outcome.Missing
        val path = Route.path(route)
        val own = caller == selfPackage
        val out: Outcome = when {
            caller == null -> Outcome.Denied("unknown caller")
            route is Route.Index -> Outcome.Bytes(index(caller).toByteArray())
            route is Route.App -> if (own || caller == route.pkg) serve(route) else Outcome.Denied("$caller is not ${route.pkg}")
            route is Route.Shared -> {
                val e = manifest().shared[route.dataset]
                when {
                    e == null -> if (own) serve(route) else Outcome.Denied("${route.dataset} is not in the manifest")
                    own || e.grants(caller) -> serve(route)
                    else -> Outcome.Denied("$caller is not a consumer of ${route.dataset}")
                }
            }
            else -> Outcome.Missing
        }
        log.add(caller, path, out !is Outcome.Denied, now())
        return out
    }

    private fun serve(r: Route): Outcome =
        if (store.encrypted(r)) store.read(r)?.let { Outcome.Bytes(it) } ?: Outcome.Missing
        else store.file(r)?.takeIf { it.isFile }?.let { Outcome.Served(it) } ?: Outcome.Missing

    /** The caller's view: every shared dataset it may read plus its own app datasets, each with schemaVersion, size and etag. */
    fun index(caller: String): String {
        val m = manifest()
        val rows: JsonArray = buildJsonArray {
            for (e in store.list()) {
                val r = Route.parse(e.path.split('/')) ?: continue
                val allowed = when (r) {
                    is Route.App -> caller == selfPackage || caller == r.pkg
                    is Route.Shared -> caller == selfPackage || m.shared[r.dataset]?.grants(caller) == true
                    else -> false
                }
                if (!allowed) continue
                add(buildJsonObject {
                    put("path", e.path)
                    e.meta?.let { put("schemaVersion", it.schemaVersion); put("size", it.size); put("syncedAt", it.syncedAt); it.etag?.let { t -> put("etag", t) } }
                })
            }
        }
        val o: JsonObject = buildJsonObject { put("schemaVersion", 1); put("datasets", rows) }
        return o.toString()
    }
}
