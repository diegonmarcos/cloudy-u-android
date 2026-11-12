package com.diegonmarcos.superapp.configs

import android.content.Context

/**
 * #1682 P3 step 4b: the hooks the owner-side ADB Shell page needs from the app that hosts it, so the page itself can live in
 * libs:adb-shell-core (one copy for the SuperApp today and the Cloud Webserver after the flip). The host installs them at process
 * start (SuperApp: App.onCreate), the same pattern as AdbReadiness.connector / hostOpener. Not installed = the keep-alive reads off
 * and a switch does nothing (never a crash): the page then shows the keeper as off, which is the truth for a host without one.
 */
object AdbOwnerHost {
    /** The Wireless-debugging keep-alive switch of the host (SuperApp: WirelessDebugKeeper.Prefs). */
    interface Keeper {
        fun enabled(ctx: Context): Boolean
        fun setEnabled(ctx: Context, on: Boolean)
    }

    object NoKeeper : Keeper {
        override fun enabled(ctx: Context): Boolean = false
        override fun setEnabled(ctx: Context, on: Boolean) {}
    }

    @Volatile var keeper: Keeper = NoKeeper

    fun keeperEnabled(ctx: Context): Boolean = runCatching { keeper.enabled(ctx) }.getOrDefault(false)
    fun setKeeperEnabled(ctx: Context, on: Boolean) { runCatching { keeper.setEnabled(ctx, on) } }
}
