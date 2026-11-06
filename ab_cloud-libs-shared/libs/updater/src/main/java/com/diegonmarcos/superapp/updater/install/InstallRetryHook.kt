package com.diegonmarcos.superapp.updater.install

import android.content.Context

/**
 * #1551 How a TRANSIENT install failure that arrives asynchronously (at [com.diegonmarcos.superapp.updater.PackageInstallerReceiver],
 * after the pass that committed it has ended) reaches the queue that owns the retry. The Store's auto chain sets it when it attaches
 * (App.onCreate runs before any receiver, so it is set in every process that can receive the answer); unset, nothing is scheduled and
 * the note alone carries the failure to the next pass, as before.
 */
object InstallRetryHook {
    @Volatile var onTransient: ((ctx: Context, pkg: String, reason: String) -> Unit)? = null
}
