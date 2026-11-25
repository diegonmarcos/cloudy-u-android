package com.diegonmarcos.superapp.apps

import android.content.Context
import java.io.File

/**
 * #1562 The Store's declarations (appstore-controls, appstore-fleet-actions) are served datasets, never APK assets. A unit test has no Cloud Store
 * provider, so the REAL served file (data/fleet/<name>.json, Gradle runs the app module's tests from the module dir) is placed as the client's
 * last-known-good copy, the one the reader falls back to when the provider is missing: the code under test reads exactly what production reads.
 */
object ServedFixtures {
    fun seed(ctx: Context, vararg datasets: String) {
        val dir = File(ctx.applicationContext.filesDir, "fleetdata-lkg").apply { mkdirs() }
        for (d in datasets) File(dir, "shared__$d.json").writeText(File("../../data/fleet/$d.json").readText())
    }
    fun text(dataset: String): String = File("../../data/fleet/$dataset.json").readText()
}
