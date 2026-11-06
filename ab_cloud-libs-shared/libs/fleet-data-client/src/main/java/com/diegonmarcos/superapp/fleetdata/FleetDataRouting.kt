package com.diegonmarcos.superapp.fleetdata

/**
 * Which provider owns a dataset: a dataset-prefix -> authority table, nothing else. It holds NO data (the datasets are served at runtime).
 * Tiers 1-2 (personal, fleet/infra) live in Cloud Account's accountdata provider, tiers 3-4 (app defaults, static reference) in Cloud Store's.
 * A dataset of tiers 1-2 is named with its tier prefix (`personal/wallet`, `fleet/probes`, `infra/ai`); every other name is the store's.
 */
object FleetDataRouting {
    const val ACCOUNT = "com.diegonmarcos.cloudaccount.accountdata"
    const val STORE = "cloud.store.data"

    private val table = listOf("personal/" to ACCOUNT, "fleet/" to ACCOUNT, "infra/" to ACCOUNT)
    private val SEGMENT = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun authorityFor(dataset: String): String = table.firstOrNull { dataset.startsWith(it.first) }?.second ?: STORE

    /** The tier a name routes to: 1 or 2 (account) or 3 (store; 4 is declared by the manifest, not by the name). */
    fun accountOwned(dataset: String): Boolean = authorityFor(dataset) == ACCOUNT

    /** `a`, `a-b`, `tools/sensors`: lower-case segments, no empty one, no dot-dot. */
    fun validDataset(dataset: String): Boolean = dataset.isNotEmpty() && dataset.split('/').all { SEGMENT.matches(it) && it != ".." && it != "." }

    fun validPackage(pkg: String): Boolean = PACKAGE.matches(pkg)

    /** content://<authority>/shared/<dataset> or /app/<pkg>/<dataset>: the one route scheme, no per-app code. */
    fun path(dataset: String, ownPackage: String?): String =
        if (ownPackage == null) "shared/$dataset" else "app/$ownPackage/$dataset"

    fun uri(dataset: String, ownPackage: String?): String = "content://${authorityFor(dataset)}/${path(dataset, ownPackage)}"
}
