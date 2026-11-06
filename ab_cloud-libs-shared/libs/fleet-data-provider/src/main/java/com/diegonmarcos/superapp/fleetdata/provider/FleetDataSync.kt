package com.diegonmarcos.superapp.fleetdata.provider

import com.diegonmarcos.superapp.fleetdata.FleetDataRouting
import com.diegonmarcos.superapp.fleetdata.SchemaCheck

/** One conditional GET. [code] 200 with a body, 304 not modified, anything else a failure. */
data class Reply(val code: Int, val body: ByteArray?, val etag: String?)
fun interface Fetcher { fun get(url: String, etag: String?, headers: Map<String, String>): Reply }
data class Req(val url: String, val headers: Map<String, String> = emptyMap())

/** Where a provider pulls from: the manifest request, one dataset request, and the tiers it may ever serve. */
interface RemoteSource {
    val tiers: Set<Int>
    fun manifest(): Req?
    fun dataset(dataset: String, pkg: String?, entry: ManifestEntry?): Req?
}

/**
 * The sync, free of Android and of WorkManager (the worker only calls [run]): manifest first, then each dataset of a tier this provider may
 * serve, with If-None-Match. A body replaces the copy only after it parses and its schemaVersion is accepted; otherwise the copy stays (the
 * last-known-good) and the failure is reported. A run inside [minGapMs] of the previous one is refused (the 30 s retry floor).
 */
class FleetDataSync(
    private val store: FleetDataStore,
    private val source: RemoteSource,
    private val fetcher: Fetcher,
    private val supportedSchema: Int = 1,
    private val minGapMs: Long = 30_000,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val changed: (String) -> Unit = {},
) {
    data class Report(val updated: List<String>, val notModified: List<String>, val failed: Map<String, String>, val throttled: Boolean = false, val at: Long = 0)

    @Volatile var last: Report? = null; private set
    private var lastRunAt = Long.MIN_VALUE / 2

    @Synchronized fun run(): Report {
        val t = now()
        if (t - lastRunAt < minGapMs) return Report(emptyList(), emptyList(), emptyMap(), throttled = true, at = t)
        lastRunAt = t
        val updated = ArrayList<String>(); val same = ArrayList<String>(); val failed = LinkedHashMap<String, String>()
        fun pull(r: Route, req: Req?, label: String, validate: (String) -> String?) {
            if (req == null) { failed[label] = "no credential or url"; return }
            val have = store.meta(r)?.takeIf { store.file(r)?.isFile == true }
            val reply = try { fetcher.get(req.url, have?.etag, req.headers) } catch (e: Exception) { failed[label] = e.javaClass.simpleName; return }
            when {
                reply.code == 304 -> { store.touch(r, reply.etag, t); same += label }
                reply.code == 200 && reply.body != null -> {
                    val text = String(reply.body, Charsets.UTF_8)
                    val bad = (SchemaCheck.check(text, supportedSchema) as? SchemaCheck.Result.Bad)?.why ?: validate(text)
                    if (bad != null) { failed[label] = bad; return }
                    val v = (SchemaCheck.check(text, supportedSchema) as SchemaCheck.Result.Ok).schemaVersion
                    try {
                        if (store.replace(r, reply.body, reply.etag, v, t)) { updated += label; changed(Route.path(r)) } else same += label
                    } catch (e: Exception) { failed[label] = "write: ${e.javaClass.simpleName}" }
                }
                else -> failed[label] = "HTTP ${reply.code}"
            }
        }
        pull(Route.Shared(FleetManifest.DATASET), source.manifest(), FleetManifest.DATASET) { if (FleetManifest.parse(it) == null) "manifest has no shared/apps" else null }
        val manifest = store.read(Route.Shared(FleetManifest.DATASET))?.let { FleetManifest.parse(String(it)) } ?: FleetManifest.EMPTY
        for ((name, e) in manifest.shared) {
            // A tier this provider may not serve is never fetched, even when the manifest lists it (tiers 1-2 never come from the public repo).
            if (e.tier !in source.tiers) { failed[name] = "tier ${e.tier} not served here"; continue }
            if (!FleetDataRouting.validDataset(name)) { failed[name] = "bad dataset name"; continue }
            pull(Route.Shared(name), source.dataset(name, null, e), name) { null }
        }
        for ((pkg, names) in manifest.apps) for (name in names) {
            if (!FleetDataRouting.validPackage(pkg) || !FleetDataRouting.validDataset(name)) { failed["$pkg/$name"] = "bad name"; continue }
            pull(Route.App(pkg, name), source.dataset(name, pkg, null), "$pkg/$name") { null }
        }
        return Report(updated, same, failed, at = t).also { last = it }
    }
}
