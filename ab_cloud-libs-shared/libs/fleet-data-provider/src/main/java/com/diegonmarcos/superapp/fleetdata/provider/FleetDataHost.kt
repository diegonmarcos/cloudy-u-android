package com.diegonmarcos.superapp.fleetdata.provider

import com.diegonmarcos.superapp.fleetdata.FleetDataRouting

/**
 * Tiers 3-4 (public defaults and reference data) come from the public repo's data folder, raw over HTTPS with ETag. NEVER personal or fleet data:
 * [tiers] is {3, 4} and the sync refuses anything else the manifest lists. The repo path of a dataset is its manifest `source`, else
 * `data/fleet/<dataset>.json`, else (an app dataset) `data/fleet/app/<pkg>/<dataset>.json`.
 */
class PublicRepoSource(private val base: String = RAW) : RemoteSource {
    override val tiers = setOf(3, 4)
    override fun manifest() = Req(base + "data/fleet/manifest.json")
    override fun dataset(dataset: String, pkg: String?, entry: ManifestEntry?): Req? {
        val src = entry?.source ?: if (pkg == null) "data/fleet/$dataset.json" else "data/fleet/app/$pkg/$dataset.json"
        if (!SAFE.matches(src) || ".." in src) return null
        return Req(base + src)
    }
    companion object {
        const val RAW = "https://raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/"
        private val SAFE = Regex("[A-Za-z0-9_./-]+\\.json")
    }
}

/**
 * Tiers 1-2 (personal, fleet/infra) come ONLY from the authenticated cloudy-c3-api route `GET /fleet-data/<tier>/<dataset>` (backed by the
 * private cloud-me-pvt-configs repo), with the fleet bearer and ETag; the manifest is `GET /fleet-data/manifest`. Cloud Account supplies
 * [apiBase] (its fleet endpoint, mesh first) and [bearer] (its vault).
 */
class FleetApiSource(private val apiBase: () -> String?, private val bearer: () -> String?) : RemoteSource {
    override val tiers = setOf(1, 2)
    private fun req(path: String): Req? {
        val b = apiBase()?.trimEnd('/') ?: return null
        val t = bearer()?.takeIf { it.isNotBlank() } ?: return null
        return Req("$b/fleet-data/$path", mapOf("Authorization" to "Bearer $t"))
    }
    override fun manifest() = req("manifest")
    override fun dataset(dataset: String, pkg: String?, entry: ManifestEntry?): Req? =
        req("${entry?.tier ?: 2}/${if (pkg == null) dataset else "app/$pkg/$dataset"}")
}

/** Per-authority wiring: Cloud Account registers its [FleetApiSource] in its provider's onCreate; the store gets the public one. */
object FleetDataHost {
    private val sources = java.util.concurrent.ConcurrentHashMap<String, RemoteSource>()
    fun register(authority: String, source: RemoteSource) { sources[authority] = source }
    fun source(authority: String): RemoteSource? =
        sources[authority] ?: if (authority == FleetDataRouting.STORE) PublicRepoSource() else null
}
