package com.diegonmarcos.cloudstore.shell

import android.content.Context
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.adbdebug.HostShell
import com.diegonmarcos.superapp.adbdebug.HostShellWorker

/**
 * #894 Cloud Store's OWN privileged shell channel.
 *
 * The Store handles the fleet's installs and its own self-update, not SuperApp, so it needs its
 * own uid-2000 channel. The engine is libs:shizuku-adb-debug-tools (embedded adb pairing, the
 * stock Shizuku app as the declared fallback in build.json::shizuku_client); Fleet.commit installs
 * through `pm install` as the shell user and falls back to the PackageInstaller session only when
 * no channel is up.
 *
 * The host glue (re-arm after reboot, keep Wireless debugging on, the WRITE_SECURE_SETTINGS
 * self-grant after a pairing) moved into the lib as [HostShell] so Cloud Account runs the same
 * copy (account redesign, spec section 2.4). This object only names the Store's entry point.
 */
object CloudStoreShell {
    /** Called once from [com.diegonmarcos.cloudstore.App.onCreate]. */
    fun install(app: Context) = HostShell.install(app)

    fun keepWirelessDebuggingOn(ctx: Context): Boolean = HostShell.keepWirelessDebuggingOn(ctx)
}

/** Kept by name: WorkManager may still hold a job enqueued under this class by an older build. */
class ShellChannelWorker(ctx: Context, params: WorkerParameters) : HostShellWorker(ctx, params)

