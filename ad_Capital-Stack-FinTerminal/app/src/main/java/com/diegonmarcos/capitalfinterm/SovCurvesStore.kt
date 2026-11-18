package com.diegonmarcos.capitalfinterm

import android.content.Context

/** #1786 what the CB Homepage's live sources answered: the merged curves (null = none answered) and why not, when they did not. */
data class SovLive(val curves: SovCurves?, val why: String)

/**
 * #1786 asks the live sources of the CB Homepage boxes (data/cb-indicators.json `live`, the FinTerm API sovereign curves): the declared fleet source's
 * mesh base (data/sources.json `fleet_endpoint`, baked FLEET_MESH_B64) and its health path, exactly like the other finterm-api calls (/v1/curves/...,
 * the sovereign returns): no key, WireGuard is the auth. Asked once per process (again after a minute while nothing answered); never throws.
 */
object SovCurvesStore {
    @Volatile private var served: SovLive? = null
    @Volatile private var askedAt = 0L
    @Volatile private var last: SovLive = SovLive(null, "")

    /** Blocking: call off the main thread. [notMesh] / [noAnswer] are the declared words of the two failures. */
    fun load(ctx: Context, decl: CbDecl, indicators: List<String>, notMesh: String, noAnswer: String): SovLive {
        served?.let { return it }
        val now = System.currentTimeMillis()
        if (now - askedAt < 60_000L) return last
        askedAt = now
        val rules = SovCurvesApi.liveRules(decl, indicators)
        if (rules.isEmpty()) return SovLive(null, "").also { last = it }
        val app = ctx.applicationContext
        val fleet = runCatching { AssetData.sources(app) }.getOrDefault(emptyList())
        var why = ""
        val got = rules.map { it.src }.distinct().mapNotNull { id ->
            val src = decl.sources[id] ?: return@mapNotNull null
            val primary = fleet.firstOrNull { it.id == src.fleetSource }
            val base = primary?.let { Decl.fleetMesh[it.fleetEndpoint] }.orEmpty()
            if (primary == null || base.isEmpty()) { why = notMesh; return@mapNotNull null }
            runCatching {
                val mesh = UrlHttp(connectMs = 4_000, readMs = 20_000, cleartextHosts = Decl.fleetMesh.values.map { Mesh.hostOf(it) }.toSet())
                if (!Mesh.reachable(mesh, base, primary.paths["health"].orEmpty())) { why = notMesh; null }
                else SovCurvesApi.parse(mesh.get(base.trimEnd('/') + SovCurvesApi.path(src, rules))).also { if (it == null) why = noAnswer }
            }.getOrElse { why = noAnswer; null }
        }
        val merged = if (got.isEmpty()) null else got.reduce { a, b ->
            SovCurves(a.asOf.ifEmpty { b.asOf }, a.generatedAt.ifEmpty { b.generatedAt }, (a.tenors + b.tenors).distinct(), a.curves + b.curves, a.unavailable + b.unavailable, a.meta + b.meta)
        }
        val r = SovLive(merged, if (merged == null) why.ifEmpty { noAnswer } else "")
        last = r
        if (merged != null) served = r
        return r
    }
}
