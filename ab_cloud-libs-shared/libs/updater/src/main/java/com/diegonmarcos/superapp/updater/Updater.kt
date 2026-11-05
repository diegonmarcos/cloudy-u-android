package com.diegonmarcos.superapp.updater

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.diegonmarcos.superapp.core.StoreNotifyGate
import java.util.concurrent.TimeUnit

/**
 * Public API for the updater module. Call [start] once from the launcher
 * Activity (e.g. MainActivity.onCreate). Everything else is data-driven by
 * build.json::release.auto_update — no caller-side config.
 */
object Updater {
    private const val TAG = "Updater"
    /** Cap on the wait for WorkManager to accept the request. The enqueue is a
     *  local DB write, so a wait this long means something is wrong, and a
     *  caller holding an HTTP connection open must get an answer either way. */
    private const val ENQUEUE_TIMEOUT_SECONDS = 15L
    private const val WORK_NAME = "superapp-auto-update"
    private const val ONE_SHOT_NAME = "superapp-update-now"
    private const val KICK_NAME = "superapp-update-kick"
    private const val COMPANION_TAG = "companion-install"

    /** Enqueue (or refresh) the periodic update worker. Idempotent. Respects
     *  the runtime Auto-update toggle (AutoUpdatePrefs.enabled) — OFF cancels. */
    fun start(context: Context) {
        // #894 The SuperApp schedules no update work at all (Cloud Store updates it) and clears
        // whatever an older build left in WorkManager.
        if (!StoreNotifyGate.mayPost(context)) { cancelAll(context); return }
        // #1502 Cloud Account never runs a background job: clear what an older build queued and stop.
        if (BackgroundGate.never(context)) { cancelAll(context); return }
        // IDENTITY GATE (#453). A work-profile, parallel-clone or Secure
        // Folder copy of this app is not the install the updater owns. It must
        // not schedule the periodic self-update at all — never queue work whose
        // only job is to draw a badge or ask for an update.
        if (!InstallIdentity.isManaged(context)) {
            Log.i("Updater", "identity gate: running as ${InstallIdentity.describe(context)} " +
                              "— NOT the managed primary install; not scheduling the self-update")
            cancel(context)
            return
        }
        if (!BuildConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)) {
            cancel(context)
            return
        }
        // Always CONNECTED (not UNMETERED): the worker must run on metered too so
        // it can CHECK and — when AU_REQUIRE_UNMETERED is set — PROMPT instead of
        // auto-downloading. The metered decision moved into UpdateWorker (runtime
        // NET_CAPABILITY_NOT_METERED check), so gating the worker off entirely on
        // metered would suppress the "ask for update" prompt.
        val constraints = Constraints.Builder().apply {
            // #1502 Store: UNMETERED, so a periodic or delayed job never wakes the process on mobile data.
            setRequiredNetworkType(BackgroundGate.network(context))
            if (BuildConfig.AU_REQUIRE_CHARGING) {
                setRequiresCharging(true)
            }
        }.build()

        val request = PeriodicWorkRequestBuilder<UpdateWorker>(
            BuildConfig.AUTO_UPDATE_INTERVAL_HOURS, TimeUnit.HOURS,
        ).setConstraints(constraints).build()

        // UPDATE (not KEEP): a KEEP'd request pins the FIRST interval/constraints
        // an installed device ever saw, so later build.json::auto_update changes
        // never reach it. UPDATE re-applies the current spec while preserving the
        // running schedule.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
        )

        // A periodic worker's first run is a full interval out, so on a fresh
        // install/launch the self-update wouldn't fire for hours. Kick a one-shot
        // ~30s after launch so the first check happens promptly. KEEP so repeated
        // launches don't stack kicks; same constraints as the periodic run.
        val kick = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(constraints)
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            KICK_NAME, ExistingWorkPolicy.KEEP, kick,
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_NAME)
        wm.cancelUniqueWork(KICK_NAME)
    }

    /** #894 Every update job this class can have queued: periodic, its kick and the manual one-shot. */
    fun cancelAll(context: Context) {
        cancel(context)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT_NAME)
    }

    /**
     * Abort an in-flight update the user asked to cancel (the overlay's Cancel
     * button). Cancels the one-shot self-check, any companion installs, and the
     * fleet check, then flips the progress state to Cancelled so the overlay
     * dismisses. WorkManager cancellation makes the worker coroutine inactive;
     * the download loop bails on the next isStopped check.
     */
    fun cancelNow(context: Context) {
        // Arm the shared cancel flag FIRST so every download loop (the self-update
        // worker's blocking read AND the raw fleet-install threads WorkManager
        // can't cancel) bails on its next poll. WorkManager cancellation below is
        // belt-and-suspenders for the workers; the flag is what makes Cancel
        // actually stop a fleet "Update All".
        UpdateProgress.requestCancel()
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ONE_SHOT_NAME)
        wm.cancelUniqueWork(KICK_NAME)
        wm.cancelAllWorkByTag(COMPANION_TAG)
        UpdateProgress.update(UpdateProgress.State.Cancelled)
    }

    /**
     * One-shot manual check. The "Check for updates" button calls this so the
     * user can install a new APK immediately instead of waiting for the next
     * periodic tick. REPLACE policy means rapid taps cancel the previous run.
     *
     * FORCE, BUT NOT CONSENT TO SPEND. A tap means "look now, and ignore the
     * Auto-update toggle while you do it" — it does not mean "and put a
     * quarter-gigabyte on my mobile bill without mentioning it". This used to
     * be the same call as [downloadNow], so the manual button on a metered
     * connection downloaded whatever it found in silence; the fleet's largest
     * APK is 267 MB, and the rule that came out of paying for one is that a
     * deliberate press MAY spend the data but has to say the number first.
     *
     * So a manual check on a metered connection stops at the manifest — which
     * is kilobytes — and publishes [UpdateProgress.State.UpdateAvailable]
     * carrying the download size. The screen turns that into a question, and
     * answering it calls [downloadNow]. On Wi-Fi nothing is withheld and there
     * is nothing to ask, so the tap downloads as before.
     */
    // RETURNS UNIT, DELIBERATELY, AND THE BRACES ARE LOAD-BEARING. An expression
    // body here infers Operation, which is androidx.work — a dependency this
    // module declares `implementation`, so it is not on any consumer's compile
    // classpath. Leaking it into a public signature broke cloud-wallet and
    // cloud-me with "Cannot access class 'androidx.work.Operation'" at the call
    // site (runs 35043275088 / 35043275146). Only [requestCheck] needs the
    // Operation, and it keeps it inside this module and hands back an [Ack] of
    // Boolean and String instead.
    fun checkNow(context: Context) { enqueueUserInitiated(context, consented = false) }

    /**
     * The "Update now" button on the metered prompt (UpdateProgress.State
     * .UpdateAvailable). Same visible one-shot as [checkNow], plus the one
     * thing that button adds: the user has now seen the size and said yes, so
     * this run downloads over the metered network.
     */
    // Unit, braced, for the same classpath reason as [checkNow] above.
    fun downloadNow(context: Context) { enqueueUserInitiated(context, consented = true) }

    /**
     * The outcome of [requestCheck] — whether an update check is now really
     * scheduled, and the sentence that says so.
     *
     * A boolean alone would let a caller keep inventing its own success text;
     * carrying the message with the verdict means the log line, the on-screen
     * error and the HTTP body are all the same sentence, and there is no
     * wording in which "it failed" can be rendered as "queued".
     */
    data class Ack(val ok: Boolean, val message: String)

    /**
     * Start a one-shot update check on behalf of a caller that is NOT the
     * foreground UI — the on-device debug server's POST /api/system/update,
     * named by [origin] so the log says who asked.
     *
     * THE ACK USED TO BE A LIE, AND IT COST FOUR PUBLISHED APKs.
     *
     * The server's handler for that route was, in full:
     *
     *     DevControlBridge.runOnMain {
     *         DevControlBridge.host()?.onActionFromServer("check_updates")
     *     }
     *     reply(writer, "200 OK", "update queued\n")
     *
     * and `DevControlBridge.host()` is a WeakReference that the launcher
     * Activity registers in onResume and clears in onPause. A fleet-driven
     * update arrives with the screen off and the app backgrounded — which is
     * the entire point of driving it remotely — so host() was null, the
     * safe-call discarded the whole request, and the server answered
     * "update queued" for work that had never been queued, never been looked
     * at, and never been logged. On 2026-09-16 that phone sat four releases
     * behind while 1200 lines of its own logcat held zero matches for
     * updat|install|download|apk: nothing had run, because nothing had been
     * asked to. Three Android tickets were closed on "published" and reopened
     * because "published" had stopped implying "installed" (#280, blocking
     * #260/#261/#267/#70).
     *
     * So the UI is out of the path. WorkManager is reachable from any thread
     * that has an application Context and does not care whether an Activity
     * exists, which is exactly the property the old path lacked. And the
     * answer is the outcome: [enqueueUserInitiated] hands back an [Operation],
     * this waits on it, and an enqueue that fails returns ok=false, logs at
     * ERROR and publishes [UpdateProgress.State.Failed] — a state
     * [UpdateProgress.suppressed] is documented never to hide, so it reaches
     * the screen even during an unattended pass. An updater that cannot update
     * now says why, on the device, in the log and on the display.
     *
     * Blocking is correct here and not an oversight: the debug server handles
     * each connection inline on its own accept-loop thread, never on the main
     * Looper, and a reply sent before the enqueue resolved would be the same
     * unverified optimism this method exists to delete.
     */
    fun requestCheck(context: Context, origin: String): Ack {
        val app = context.applicationContext
        return try {
            enqueueUserInitiated(app, consented = false)
                .result.get(ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val msg = "update check enqueued as \"$ONE_SHOT_NAME\" by $origin — " +
                "progress follows on logcat tags Updater/Check and Updater/Worker"
            Log.i(TAG, msg)
            Ack(true, msg)
        } catch (t: Throwable) {
            val msg = "update check could NOT be started ($origin): " +
                "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"
            Log.e(TAG, msg, t)
            UpdateProgress.update(UpdateProgress.State.Failed(msg))
            Ack(false, msg)
        }
    }

    private fun enqueueUserInitiated(context: Context, consented: Boolean): Operation {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(UpdateWorker.KEY_FORCE, true)
                    .putBoolean(UpdateWorker.KEY_CONSENTED, consented)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
        return WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request,
        )
    }

    /**
     * Download a companion APK from a direct [apkUrl] (e.g. a GitHub release
     * asset) and install it for [packageName] via PackageInstaller. Used by
     * the launcher when a tile points at a companion app (Cloud-Comms /
     * Cloud-IDE hub) that isn't installed yet. URL + package come from
     * build.json::ui.external_apps — never hardcoded here. Keyed per package
     * so taps on different companion tiles don't clobber each other; KEEP
     * means a re-tap while a download is in flight is a no-op.
     */
    fun installApk(context: Context, apkUrl: String, packageName: String, label: String) {
        val data = Data.Builder()
            .putString(ApkInstallWorker.KEY_URL, apkUrl)
            .putString(ApkInstallWorker.KEY_PKG, packageName)
            .putString(ApkInstallWorker.KEY_LABEL, label)
            .build()
        val request = OneTimeWorkRequestBuilder<ApkInstallWorker>()
            .setInputData(data)
            .addTag(COMPANION_TAG) // so cancelNow's cancelAllWorkByTag(COMPANION_TAG) matches
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "companion-install-$packageName", ExistingWorkPolicy.KEEP, request,
        )
    }
}
