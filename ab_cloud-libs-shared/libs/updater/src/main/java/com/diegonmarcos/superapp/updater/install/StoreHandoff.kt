package com.diegonmarcos.superapp.updater.install

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.updater.BuildConfig
import com.diegonmarcos.superapp.updater.Fleet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1675 The install was left to Cloud Store, the installer of record ([SilentReadiness.INSTALLER_OF_RECORD]); nothing was
 * installed by this app. Not a failure: callers report "Cloud Store is installing it" (handed over) or "Cloud Store updates it"
 * (a background request, served by Cloud Store's own fleet pass).
 */
class HandedToStore(val pkg: String, val handedOver: Boolean) : IllegalStateException(
    if (handedOver) "$pkg was handed to Cloud Store, the fleet's installer of record, which installs it"
    else "$pkg is left to Cloud Store, the fleet's installer of record: its next fleet pass updates it")

/**
 * #1675 ONE INSTALLER OF RECORD. A fleet app other than Cloud Store never opens a PackageInstaller session for a fleet package
 * while Cloud Store is installed (that would make it the package's installer of record and every later Store update a prompt):
 *
 *  - from the FOREGROUND (a tap) it opens Cloud Store with [EXTRA_INSTALL] = the package; Cloud Store's MainActivity queues
 *    the install in its own process ([StoreHandoffWorker]) and shows its Cloud page with the row;
 *  - from the BACKGROUND (a periodic self-update) it installs nothing: Cloud Store's own unattended fleet pass updates it.
 *
 * No broadcast receiver: Cloud Store declares none (#1502, it never wakes for another app), so the hand-off is an activity
 * start the user sees. Only packages in Cloud Store's baked fleet list are installed this way (no URL is accepted).
 */
object StoreHandoff {
    private const val TAG = "Updater/Handoff"
    const val ACTION_OPEN = "com.diegonmarcos.cloudstore.OPEN"
    const val EXTRA_INSTALL = "install_pkg"
    const val EXTRA_TAB = "tab"
    const val EXTRA_FROM = "from"

    /** The activity intent. */
    fun intent(selfPkg: String, pkg: String): Intent =
        Intent(ACTION_OPEN).setPackage(SilentReadiness.INSTALLER_OF_RECORD)
            .putExtra(EXTRA_INSTALL, pkg).putExtra(EXTRA_TAB, "cloud").putExtra(EXTRA_FROM, selfPkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What an install request does in the app that makes it. */
    enum class Action { INSTALL_HERE, OPEN_STORE, LEAVE_TO_STORE }

    /** Pure. */
    fun action(route: SilentReadiness.Route, foreground: Boolean): Action = when {
        route == SilentReadiness.Route.LOCAL -> Action.INSTALL_HERE
        foreground -> Action.OPEN_STORE
        else -> Action.LEAVE_TO_STORE
    }

    /**
     * Null when this app installs [pkg] itself (it is Cloud Store, or Cloud Store is not installed: bootstrap). Otherwise the
     * request went to Cloud Store (or is left to its pass) and the caller must NOT install: the [HandedToStore] says which.
     * Never throws.
     */
    fun handOff(ctx: Context, pkg: String): HandedToStore? = runCatching {
        // Only FLEET packages have Cloud Store as their installer of record; a third-party app the SuperApp's Phone page
        // installs (F-Droid, a vendor URL) is not in Cloud Store's fleet list and stays a local install.
        if (!isFleetPackage(pkg)) return@runCatching null
        when (action(SilentReadiness.route(ctx.packageName, SilentReadiness.storeInstalled(ctx)), isForeground(ctx))) {
            Action.INSTALL_HERE -> null
            Action.OPEN_STORE -> {
                val opened = runCatching { ctx.startActivity(intent(ctx.packageName, pkg)); true }.getOrDefault(false)
                Log.i(TAG, "$pkg -> Cloud Store (installer of record), opened=$opened")
                HandedToStore(pkg, handedOver = opened)
            }
            Action.LEAVE_TO_STORE -> {
                Log.i(TAG, "$pkg: background request left to Cloud Store's fleet pass")
                HandedToStore(pkg, handedOver = false)
            }
        }
    }.getOrElse { Log.w(TAG, "hand-off of $pkg: ${it.message}"); null }

    private val fleet: List<Fleet.App> by lazy {
        runCatching { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }.getOrDefault(emptyList())
    }

    /** Is [pkg] a fleet package (by the fleet list baked into this app). */
    fun isFleetPackage(pkg: String): Boolean = appFor(fleet, pkg) != null

    /** Pure: the fleet entry a hand-off names, by its package or a stock upstream id. */
    fun appFor(apps: List<Fleet.App>, pkg: String): Fleet.App? = apps.firstOrNull { it.pkg == pkg || it.altId == pkg }

    /** Cloud Store's side, called from its MainActivity: queue [intent]'s package when this is Cloud Store. True when queued. */
    fun accept(ctx: Context, received: Intent?): Boolean {
        val intent = received ?: return false
        val pkg = intent.getStringExtra(EXTRA_INSTALL)?.takeIf { it.isNotBlank() } ?: return false
        if (!SilentReadiness.isInstallerOfRecord(ctx.packageName)) return false
        return runCatching {
            val req = OneTimeWorkRequestBuilder<StoreHandoffWorker>()
                .setInputData(Data.Builder().putString(EXTRA_INSTALL, pkg).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("store-handoff-$pkg", ExistingWorkPolicy.KEEP, req)
            Log.i(TAG, "queued $pkg from ${intent.getStringExtra(EXTRA_FROM)}")
            intent.removeExtra(EXTRA_INSTALL)   // a rotation or a re-delivered intent does not queue it twice
            true
        }.getOrElse { Log.w(TAG, "could not queue $pkg: ${it.message}"); false }
    }

    private fun isForeground(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        am.runningAppProcesses.orEmpty().any {
            it.processName == ctx.packageName && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }.getOrDefault(false)
}

/** Runs a handed-off install inside Cloud Store, through the ordinary fleet path (download, verify, one session). */
class StoreHandoffWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pkg = inputData.getString(StoreHandoff.EXTRA_INSTALL).orEmpty()
        if (pkg.isBlank()) return@withContext Result.failure()
        runCatching {
            val app = StoreHandoff.appFor(runCatching { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }.getOrDefault(emptyList()), pkg)
            if (app != null) Fleet.install(applicationContext, app)
            else Log.w("Updater/Handoff", "$pkg is not a fleet package: Cloud Store does not install it on request")
        }.onFailure { Log.w("Updater/Handoff", "handed-off install of $pkg failed: ${it.message}") }
        Result.success()
    }
}
