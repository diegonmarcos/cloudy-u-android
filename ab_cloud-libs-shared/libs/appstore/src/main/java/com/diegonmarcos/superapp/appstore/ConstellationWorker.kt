package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.core.DataSyncBudget
import com.diegonmarcos.superapp.core.StoreNotifyGate
import com.diegonmarcos.superapp.updater.holdDataSync
import com.diegonmarcos.superapp.updater.stopCooperatively
import androidx.work.ForegroundInfo
import com.diegonmarcos.superapp.updater.Advisory
import com.diegonmarcos.superapp.updater.BatchForeground
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.InstallIdentity
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Fleet
// AUTO_UPDATE_* knobs are baked into the libs:updater BuildConfig (shared AU
// knobs), NOT the app BuildConfig — reference them explicitly.
import com.diegonmarcos.superapp.updater.BuildConfig as AuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic constellation-fleet check. Unlike self-update (Updater), this scans
 * EVERY constellation app's GHCR image and, when any have updates available,
 * posts a single tap-to-open notification → Store ▸ Cloud Constellation.
 * Install stays user-initiated (P1) — background-installing N foreign APKs is
 * intentionally not silent. Data-driven interval from the shared AU knobs.
 */
class ConstellationWorker(appCtx: Context, params: WorkerParameters) :
    CoroutineWorker(appCtx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = BatchForeground.foregroundInfo(applicationContext)

    /** #fgs-timeout budget token of this run's dataSync hold (0 = none). */
    private var fgsToken = 0L

    // #fgs-timeout a stop (Android 15's dataSync timeout, which WorkManager >= 2.10 turns into one,
    // a lost constraint, a cancel) reaches the blocking downloads at once; the hold is closed on every exit.
    override suspend fun doWork(): Result = try { stopCooperatively { work() } }
        finally { DataSyncBudget.end(applicationContext, fgsToken); fgsToken = 0L }

    private suspend fun work(): Result = withContext(Dispatchers.IO) {
        // #894 Store work and Store notifications are Cloud Store's. A pass still persisted in
        // WorkManager from an older SuperApp (periodic work outlives the update that retired it)
        // ends here, before it can reach a progress notification or an alert.
        if (!StoreNotifyGate.mayPost(applicationContext)) {
            Log.i(TAG, "Store passes run in Cloud Store only - standing down")
            return@withContext Result.success()
        }
        // IDENTITY GATE (#453). A work-profile, parallel-clone or Secure
        // Folder copy of this app is not the install the updater owns. The
        // fleet check is the path that posts the "N constellation update(s)"
        // notification and the badge — so a clone must not run it at all, and
        // must not emit a single event the page could read. Same declaration
        // as the scheduler and the self worker.
        if (!InstallIdentity.isManaged(applicationContext)) {
            Log.i(TAG, "identity gate: running as " +
                  "${InstallIdentity.describe(applicationContext)} — NOT the managed primary " +
                  "install; constellation check stays silent")
            return@withContext Result.success()
        }
        if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(applicationContext))
            return@withContext Result.success()
        // #865 another app owns the fleet pass on this phone (SuperApp hands it to
        // Cloud Store once Cloud Store is installed). Two passes would race the
        // same downloads and install sessions, so this one stands down.
        if (!AppStoreHost.runsFleetPass(applicationContext)) {
            Log.i(TAG, "fleet pass owned by another app on this phone - standing down")
            return@withContext Result.success()
        }
        // #804 the pass below IS the persisted auto chain (Fleet.autoChain);
        // attach is idempotent and makes that true in any process WorkManager wakes.
        StoreAuto.attach(applicationContext) { c, t -> kick(c, t) }
        val trigger = inputData.getString(KEY_TRIGGER) ?: StoreAuto.TRIGGER_PERIODIC
        // #903 the pass downloads and installs: run it as a dataSync foreground job
        // so the OS (and Samsung's freezer) do not kill it; refused = carry on as before.
        // #fgs-timeout ONLY when the user is in the Store (refresh / local API). The periodic,
        // app-start and Wi-Fi passes held it on every run and spent Android 15's 6 h/24 h dataSync
        // cap in the background, then crashed (ForegroundServiceDidNotStopInTimeException). They are
        // plain background work now: the chain is persisted and a stopped pass resumes on the next.
        val userInitiated = trigger == StoreAuto.TRIGGER_STORE_REFRESH || trigger == StoreAuto.TRIGGER_API
        fgsToken = holdDataSync(userInitiated = userInitiated) { BatchForeground.foregroundInfo(it) }
        // The metered decision the UNMETERED constraint used to make, made here
        // instead (same place UpdateWorker makes it). Deliberately a different
        // question: WorkManager's constraint asks the system default network,
        // which on a permanently-VPN'd phone reports metered even over Wi-Fi;
        // this asks the ACTIVE network, which inherits NOT_METERED from the
        // VPN's underlying transport. Next pass retries, so this only defers.
        AutoUpdatePrefs.deferredReason(applicationContext)?.let { why ->
            Log.i(TAG, "auto-update: deferred — $why")
            // SAY SO. Returning silently made a pass held back BY POLICY look
            // exactly like one that had died: no progress, no error, nothing on
            // the Constellation page. The pass is deferred, not broken, and the
            // user is the only one who can act on the difference.
            UpdateProgress.update(UpdateProgress.State.Waiting(why))
            return@withContext Result.success()
        }
        try {
            val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
            // Auto-update ON ⇒ fully unattended: install everything the fleet
            // needs, APPS AND LIBS, drawing nothing on screen. Fleet.autoPass
            // owns the mode (Mode.AUTO — updates for apps, updates AND missing
            // for libs, which is why the 36 lib entries were invisible before)
            // and owns the session cap, so this worker and UpdateWorker can no
            // longer disagree about either depending on who won the race.
            // Fleet.status catches its own per-app errors so one bad image
            // can't throw here (the old 'forever looping' bug).
            val pass = Fleet.autoPass(applicationContext, fleet, owner = "$TAG:$trigger")
            // SILENT IS NOT QUIET. pass.reason always says what happened and,
            // when nothing happened, which of the three reasons it was:
            // nothing to do / no session slots / no privileged channel. The
            // old line was "acted on 0 app(s)" for all three, which reads
            // identically to the feature being broken.
            Log.i(TAG, "auto-update: ${pass.reason}")
            // The pass outcome is also the primary STRANDED-DEVICE signal, so
            // it is recorded, not only logged. A pass that considered work and
            // acted on none of it with no privileged channel is not a bad night
            // on the network — it is a device that can no longer take the fix
            // that would let it update. Advisory turns that into a banner the
            // user sees on next open and a direct-install button that works
            // without any privileged channel at all.
            Advisory.recordPass(applicationContext, pass)
            // Staleness is independent of the pass: a device whose worker never
            // runs never fails, so it would never raise a failure-based
            // advisory. Our own versionCode is a wall-clock timestamp, so "how
            // old am I" costs nothing beyond the status we just computed.
            Advisory.recordSelfAge(
                applicationContext,
                updateAvailable = fleet.any {
                    it.pkg == applicationContext.packageName &&
                        Fleet.status(applicationContext, it) is Fleet.State.UpdateAvailable
                },
            )
            // #894 no alert of its own here: Fleet.autoPass raised the pass's ONE summary
            // ("N updated, M need a tap - Open <store>", PassLedger) and replaces it as
            // late install results arrive. The two per-pass alerts that used to follow
            // ("N installed", "needs confirmation") said the same thing a second time.
            FleetAlerts.withdraw(applicationContext, KEY_INSTALLED)
            FleetAlerts.withdraw(applicationContext, KEY_NO_CHANNEL)
        } catch (t: Throwable) {
            Log.w(TAG, "fleet auto-update failed: ${t.message}")
        }
        Result.success()
    }

    companion object {
        private const val TAG = "Fleet/Worker"
        private const val WORK_NAME = "superapp-constellation-check"
        private const val KEY_INSTALLED = "store:updates_installed"
        private const val KEY_NO_CHANNEL = "store:needs_confirmation"
        private const val KEY_TRIGGER = "trigger"

        /** CONNECTED, never UNMETERED (see [start]); #804 and never on a nearly
         *  full disk — Download all would only fail its storage check there. */
        private fun constraints() = Constraints.Builder().apply {
            setRequiredNetworkType(NetworkType.CONNECTED)
            setRequiresStorageNotLow(true)
            if (AuConfig.AU_REQUIRE_CHARGING) setRequiresCharging(true)
        }.build()

        /** #894 Every job this class can have queued: periodic, its launch one-shot and the Wi-Fi kick. */
        fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork("$WORK_NAME-now")
            wm.cancelUniqueWork("$WORK_NAME-kick")
        }

        private fun triggerData(trigger: String) = Data.Builder().putString(KEY_TRIGGER, trigger).build()

        /**
         * #804 Run the auto chain now for [trigger] (Wi-Fi appeared, the Store
         * refreshed). KEEP: a kick already queued or running is this request
         * being served. doWork gates it exactly like the periodic run — the
         * toggle, the identity, Wi-Fi only.
         */
        fun kick(context: Context, trigger: String) {
            // A screen calls this (the Store refresh): an uninitialised WorkManager
            // must cost the trigger, never the screen.
            runCatching {
                if (!StoreNotifyGate.mayPost(context)) return   // #894
                if (!InstallIdentity.isManaged(context)) return
                if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)) return
                if (!AppStoreHost.runsFleetPass(context)) return   // #865
                val req = OneTimeWorkRequestBuilder<ConstellationWorker>()
                    .setConstraints(constraints())
                    .setInputData(triggerData(trigger))
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork("$WORK_NAME-kick", ExistingWorkPolicy.KEEP, req)
            }.onFailure { Log.w(TAG, "auto chain kick ($trigger) not enqueued: ${it.message}") }
        }

        /** Schedule the periodic fleet check. Idempotent. Call from App.onCreate. */
        fun start(context: Context) {
            // #894 The SuperApp runs no Store pass, ever: clear what an older build queued and stop.
            if (!StoreNotifyGate.mayPost(context)) { cancelAll(context); return }
            // IDENTITY GATE (#453). A clone must not schedule the periodic
            // fleet check (the notification/badge source); if one had it
            // scheduled already, cancel is the honest state — it must never
            // run. Same declaration as the self-updater.
            if (!InstallIdentity.isManaged(context)) {
                Log.i(TAG, "identity gate: running as ${InstallIdentity.describe(context)} " +
                           "— NOT the managed primary install; not scheduling the constellation check")
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME-now")
                return
            }
            // #865 another app on this phone owns the fleet pass (SuperApp hands it
            // to Cloud Store once that is installed). Cancel everything this app
            // had queued and attach no Wi-Fi trigger: two passes would race the
            // same downloads and install sessions.
            if (!AppStoreHost.runsFleetPass(context)) {
                Log.i(TAG, "fleet pass owned by another app on this phone - not scheduling")
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME-now")
                WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME-kick")
                return
            }
            // #804 the persisted chain becomes THE unattended pass, and joining
            // Wi-Fi becomes a trigger. Before the toggles: the network trigger
            // gates itself (kick), and the chain must be the one any pass runs.
            StoreAuto.attach(context) { c, t -> kick(c, t) }
            // Battery-hungry: a periodic GHCR network check. Gated by the
            // "Constellation update check" toggle (Configs → Launcher → Battery
            // Hunger Ones) in addition to the auto-update master switch. Off →
            // cancel the periodic work (manual checks still work).
            // The host owns its settings screen, so it supplies the gate
            // rather than the store reaching into the app's prefs class.
            // Default is "allowed", so a host that sets nothing behaves as
            // before rather than silently never checking.
            if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)
                || !AppStoreHost.periodicCheckAllowed(context)) {
                // Cancel the one-shot too. start() is what the Auto-update
                // toggle calls to reconcile, and a kick already sitting on its
                // 30s delay would otherwise still fire after the user turned
                // auto-update off (doWork re-checks and bails, but leaving
                // queued work behind makes the toggle look like it did nothing).
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME-now")
                return
            }
            // Always CONNECTED (not UNMETERED) — the same fix Updater.start got.
            // An UNMETERED constraint is evaluated by WorkManager against the
            // system's default network, and a device permanently on a VPN
            // routinely has no NET_CAPABILITY_NOT_METERED there, so the work sat
            // ENQUEUED with a constraint that was never going to be met. Run on
            // metered too and decide in doWork, where we can look at the ACTIVE
            // network instead.
            val constraints = constraints()
            val request = PeriodicWorkRequestBuilder<ConstellationWorker>(
                AuConfig.AUTO_UPDATE_INTERVAL_HOURS, TimeUnit.HOURS,
            ).setConstraints(constraints).build()
            // UPDATE (not KEEP): KEEP pins the FIRST interval/constraints the
            // device ever saw, so shipping a corrected build.json never reaches
            // an already-installed phone — including the constraint fix above.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
            // The periodic worker's first run is a full interval out, so on a
            // fresh install/launch auto-update wouldn't fire for hours ("still
            // not being triggered"). Kick a one-shot ~30s after launch so the
            // fleet is checked promptly, then hourly-ish via the periodic.
            checkNow(context)
        }

        /** One-shot fleet check shortly after launch. Same gating as [start]. */
        fun checkNow(context: Context) {
            if (!StoreNotifyGate.mayPost(context)) return   // #894
            // IDENTITY GATE (#453). A clone must never enqueue even a manual
            // fleet check — the notification/badge flows through this worker.
            if (!InstallIdentity.isManaged(context)) {
                Log.i(TAG, "identity gate: running as ${InstallIdentity.describe(context)} " +
                           "— NOT the managed primary install; rejecting the constellation check")
                return
            }
            if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)) return
            if (!AppStoreHost.runsFleetPass(context)) return   // #865
            val req = OneTimeWorkRequestBuilder<ConstellationWorker>()
                .setConstraints(constraints())
                .setInputData(triggerData(StoreAuto.TRIGGER_APP_START))
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            // REPLACE (not KEEP): this kick is what a toggle-ON tap re-arms with.
            // KEEP kept whatever was already sitting there — which, with the old
            // UNMETERED constraint, was a permanently blocked instance — so every
            // tap and every launch was a silent no-op. That is why the toggle
            // looked dead. REPLACE drops the stale one and enqueues this one.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-now", ExistingWorkPolicy.REPLACE, req,
            )
        }
    }
}
