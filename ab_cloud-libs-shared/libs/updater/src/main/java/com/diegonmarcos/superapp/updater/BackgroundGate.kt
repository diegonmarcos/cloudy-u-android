package com.diegonmarcos.superapp.updater

import android.content.Context
import androidx.work.NetworkType
import com.diegonmarcos.superapp.core.BackgroundPolicy

/**
 * #1502 The WorkManager side of [BackgroundPolicy]: which network constraint a background job of this app carries,
 * and whether a job that is already queued may still run. Account never runs one; Store runs one only with
 * auto-update ON on an unmetered network (the constraint keeps the job, and so the process, asleep otherwise).
 */
object BackgroundGate {
    private fun pkg(ctx: Context) = ctx.packageName

    /** Account: no background job of any kind. */
    fun never(ctx: Context): Boolean = BackgroundPolicy.modeOf(pkg(ctx)) == BackgroundPolicy.Mode.NEVER

    /** UNMETERED for Store's periodic and delayed work (a job must not wake the process on mobile data), CONNECTED elsewhere. */
    fun network(ctx: Context): NetworkType =
        if (BackgroundPolicy.modeOf(pkg(ctx)) == BackgroundPolicy.Mode.AUTO_UPDATE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED

    /** May this app do background work this instant (auto-update switch and the ACTIVE network). */
    fun mayRun(ctx: Context): Boolean =
        BackgroundPolicy.mayBackground(pkg(ctx), AutoUpdatePrefs.enabled(ctx), AutoUpdatePrefs.onUnmetered(ctx))
}
