package com.diegonmarcos.superapp.extinstall

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.diegonmarcos.superapp.appstore.StoreJobs
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * The runtime side of the `extapp:` install progress: starts / cancels /
 * restarts jobs on the shared engine (Updater.installApk → ApkInstallWorker),
 * feeds [ExtInstallTracker] from [UpdateProgress], and tells listeners (the
 * notifier and every tile ring) that a row changed. One store, two readers.
 */
object ExtAppInstalls {

    /** Same board the Cloud Store draws — one per-app install state for the process. */
    val tracker = ExtInstallTracker(StoreJobs.board)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    @Volatile private var app: Context? = null

    fun addListener(l: (String) -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    private fun changed(pkg: String) {
        main.post { listeners.forEach { it(pkg) } }
        app?.let { ExtInstallNotifier.post(it, pkg) }
        if (tracker.snapshot(pkg)?.phase == ExtPhase.DONE) app?.let { ctx -> main.post { ExtTileRing.onInstalled(ctx, pkg) } }
    }

    private val observer: (UpdateProgress.State) -> Unit = { st ->
        tracker.onEvent(st, UpdateProgress.job)?.let { changed(it) }
    }

    /** Process start (Application.onCreate): the worker can outlive the UI and
     *  the process, so the adapter must be listening before it publishes. */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        UpdateProgress.addObserver(observer)
    }

    fun snapshot(pkg: String): ExtSnapshot? = tracker.snapshot(pkg)
    fun isActive(pkg: String) = snapshot(pkg)?.phase?.active == true

    fun workName(pkg: String) = "companion-install-$pkg"

    /** Begin (or re-begin) installing [pkg] from [url] through the shared engine. */
    fun start(ctx: Context, pkg: String, label: String, url: String) {
        init(ctx)
        tracker.start(pkg, label, url)
        changed(pkg)
        Updater.installApk(ctx.applicationContext, url, pkg, label)
    }

    fun cancel(ctx: Context, pkg: String) {
        runCatching { androidx.work.WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(workName(pkg)) }
        tracker.cancel(pkg)
        changed(pkg)
    }

    /** Retry / Restart: a new job on the same URL. the shared downloader keeps the
     *  `.part` file and resumes with an HTTP Range request when the server
     *  honours it; otherwise it starts over from zero. */
    fun restart(ctx: Context, pkg: String) {
        val url = tracker.urlOf(pkg) ?: return
        val label = tracker.labelOf(pkg) ?: pkg
        runCatching { androidx.work.WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(workName(pkg)) }
        start(ctx, pkg, label, url)
    }

    /** Re-check on resume: anything the system finished while nobody listened. */
    fun refresh(ctx: Context) = com.diegonmarcos.superapp.xray.EngineProfiler.time("store", "installs") {
        for (pkg in tracker.trackedPackages()) {
            val installed = ctx.packageManager.getLaunchIntentForPackage(pkg) != null
            val s = tracker.snapshot(pkg) ?: continue
            if (installed && s.phase.active) { tracker.installed(pkg); changed(pkg) }
        }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}
