package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmark
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmarks
import com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler
import com.diegonmarcos.superapp.search.SearchScope
import com.diegonmarcos.superapp.search.SuperappSearchIndex

/**
 * #1728 Cloud SuperApp's declared benchmarks (Configs > X-Ray App > Performance). Every engine path the app already wraps in
 * [EngineProfiler.time] (notify, media, store installs, feeds, dns, bgpolicy, search, xray http, icons) is ALSO recorded in the fleet
 * profiler as "<engine>.<path>" the moment it runs, so the table shows what the app really did, CPU split included. These
 * benchmarks add the two hot paths worth running on demand: the X-Ray engine profile build and the search index over the four local
 * scopes. Both are read-only, in-process, bounded by what the phone holds, and use no network.
 */
object SuperAppBenchmarks {
    /** The four local search scopes (kind of [SuperappSearchIndex.hitsFor]); browser scopes are per query and need Cloud Browser. */
    val SEARCH_KINDS = listOf("cloud_apps", "phone_apps", "cloud_configs", "phone_configs")

    fun all(): List<XrayBenchmark> = listOf(
        XrayBenchmark("superapp.xray.profile-build", "X-Ray engine profile build", "read-only; reads the app's own counters and state, no shell, no network", 20_000, 3) { ctx ->
            XrayProfiler.span("superapp.xray.profile-build") { sp -> EngineProbes.collect(ctx).also { sp.add(items = 1) } }
            null
        },
        XrayBenchmark("search.index", "Search index (local scopes)", "read-only; ${SEARCH_KINDS.size} local scopes of the in-app search, recorded by the engine profiler as search.index", 20_000, 3) { ctx ->
            for (k in SEARCH_KINDS) runCatching { SuperappSearchIndex.hitsFor(ctx, SearchScope(k, k, k)) }
            null
        },
    )

    fun register() { XrayBenchmarks.register(*all().toTypedArray()) }
}
