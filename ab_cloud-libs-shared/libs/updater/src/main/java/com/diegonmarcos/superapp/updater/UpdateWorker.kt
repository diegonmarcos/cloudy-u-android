package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.install.InstallRefused
import com.diegonmarcos.superapp.updater.install.ShellInstall
import com.diegonmarcos.superapp.updater.install.UpdateInstaller
import com.diegonmarcos.superapp.updater.source.UpdateChecker
import android.content.Context
import com.diegonmarcos.superapp.core.DataSyncBudget
import com.diegonmarcos.superapp.core.StoreNotifyGate
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager job: check → (gate) → download → install. Scheduled by
 * Updater.start(). Runs at build.json::release.auto_update.interval_hours.
 *
 * Metered gate: when the Wi-Fi-only preference is on, a run on a metered
 * network (mobile data) does NOT download — it publishes UpdateAvailable
 * carrying the size, so the screen can ask. Only a run started with
 * KEY_CONSENTED=true (the "Update now" button, pressed against a size the user
 * has just been shown) downloads over metered. KEY_FORCE alone means a human
 * asked to LOOK: it ignores the Auto-update toggle and draws progress, and it
 * is deliberately NOT authority to spend mobile data.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = BatchForeground.foregroundInfo(applicationContext)

    /** #fgs-timeout budget token of this run's dataSync hold (0 = none). */
    private var fgsToken = 0L

    // #fgs-timeout a stop (Android 15's dataSync timeout, which WorkManager >= 2.10 turns into one,
    // a lost constraint, a cancel) reaches the blocking downloads at once; the hold is closed on every exit.
    override suspend fun doWork(): Result = try { stopCooperatively { work() } }
        finally { DataSyncBudget.end(applicationContext, fgsToken); fgsToken = 0L }

    private suspend fun work(): Result = withContext(Dispatchers.IO) {
        // #894 The SuperApp does no self-update and posts no update notification: a job still
        // persisted from an older build (WorkManager keeps periodic work across app updates), or
        // a manual check from anywhere, ends here. Cloud Store updates the SuperApp.
        if (!StoreNotifyGate.mayPost(applicationContext)) {
            Log.i("Updater/Worker", "Store notifications are Cloud Store's - self-update stays silent")
            return@withContext Result.success()
        }
        // IDENTITY GATE (#453). Belt-and-suspenders under Updater.start: a
        // clone could have had this worker queued before this build was
        // installed, or re-armed by an in-app toggle that never re-runs start.
        // Whatever scheduled it, a non-managed install must act on NOTHING —
        // no check, no download, no install, no state that a badge or prompt
        // reads. Same declaration as the scheduler.
        if (!InstallIdentity.isManaged(applicationContext)) {
            Log.i("Updater/Worker", "identity gate: running as " +
                  "${InstallIdentity.describe(applicationContext)} — NOT the managed primary " +
                  "install; self-update stays silent")
            return@withContext Result.success()
        }
        val force = inputData.getBoolean(KEY_FORCE, false)
        // FORCE and CONSENT are two different permissions and used to be one.
        // `force` answers "did a human ask for this?" — it decides whether the
        // Auto-update toggle applies and whether progress is drawn. `consented`
        // answers the narrower question "has that human been told what this
        // will cost and said yes?", which is the only one that may unlock a
        // download over mobile data. Collapsing them made every manual tap an
        // unlimited spending authorisation, silently.
        val consented = inputData.getBoolean(KEY_CONSENTED, false)
        // The toggle governs UNATTENDED updates, not the user asking directly.
        // This check used to run BEFORE `force` was read, so turning auto-update
        // off also silently killed "Check for updates": the worker returned
        // success without touching the network and the UI showed nothing.
        if (!force && (!BuildConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(applicationContext)))
            return@withContext Result.success()
        // FIRST, before anything that can reach a screen. The persisted twin of
        // [UpdateProgress.quiet] used to be set only by Fleet.autoPass, i.e. only
        // for the fleet half, so the SELF-update half of an unattended pass ran
        // with no persisted flag at all and the overlay had nothing to gate on.
        // Set late is set wrong: a flag raised even one state-write in leaves a
        // visible flash, which is indistinguishable from "still showing".
        if (!force) AutoUpdatePrefs.setUnattendedPass(applicationContext, true)
        UpdateProgress.beginDownload() // disarm any stale cancel from a prior run
        // NOTHING IS DRAWN BY AN UNATTENDED PASS. Fleet.autoPass already held
        // this for the fleet half, which left the SELF-update half above it
        // still driving the overlay: an auto-update the user never asked for
        // put a progress bar over whatever they were doing. `force` is the
        // user tapping "Check for updates", and that one must still show
        // progress, so this is keyed on `force` rather than set unconditionally.
        UpdateProgress.quiet = !force
        try {
            // ── The fleet FIRST, then this app.
            // Installing our own APK tears this process (and therefore this
            // worker) down, so anything after the self-update never runs. The
            // fleet pass used to be nowhere at all: Fleet.installAll had a
            // single caller, the manual Update-All button, so "auto-update on"
            // updated the superapp forever and never once touched the other
            // apps. Unattended only — a forced "check for updates" is the user
            // asking about THIS app.
            // ONE metered decision for both halves of the pass, taken once.
            // The gate used to sit only in front of the SELF download, so the
            // fleet pass — the far bigger payload, N APKs against one — went
            // over mobile data on every unattended wake-up. Fleet.autoPass has
            // no metered check of its own, so the gate belongs here, at the
            // call site, and the same answer must govern both downloads: two
            // separate evaluations could disagree if the radio changed between
            // them, and the second one is the larger bill.
            // Keyed on CONSENT, not on `force`. An automatic pass has neither and
            // is gated exactly as before; a manual check has force but not
            // consent, so on a metered connection it stops at the manifest and
            // publishes the size for the user to approve; "Update now" has both.
            val deferred = if (consented) null else AutoUpdatePrefs.deferredReason(applicationContext)
            if (deferred == null) updateFleet()
            else Log.i("Updater/Worker", "fleet auto-update deferred — $deferred")
            // THE BUG, and it is a nesting bug. Fleet.autoPass raises both flags
            // for itself and lowers them UNCONDITIONALLY in its own finally — it
            // has no idea it was called from inside a larger unattended pass. So
            // control came back here with quiet=false and unattended=false, and
            // everything below (the self download + install, the long, visible
            // part) drew a full progress bar on every single auto-update. That is
            // the bar the user kept reporting after each previous fix: the fixes
            // were all upstream of the point where the flag got cleared.
            // Re-assert rather than teach autoPass to save/restore, because that
            // file is being edited concurrently — see the report.
            if (!force) {
                UpdateProgress.quiet = true
                AutoUpdatePrefs.setUnattendedPass(applicationContext, true)
            }

            val available = UpdateChecker(applicationContext).available()
                ?: return@withContext Result.success()
            // Ask (don't auto-download) on metered unless the user forced it.
            if (deferred != null) {
                Log.i("Updater/Worker", "update available but $deferred — prompting instead of downloading")
                UpdateProgress.update(UpdateProgress.State.UpdateAvailable(available.remoteSize))
                return@withContext Result.success()
            }
            // Poll BOTH: isStopped covers WorkManager cancels of the one-shot;
            // cancelRequested covers a Cancel hit during a PERIODIC run (whose
            // WORK_NAME cancelNow deliberately leaves scheduled).
            // #785 the host's own update is a row too: name it on the Store bar.
            // Left set after the handoff — the install sheet it raised is still
            // this app's — and replaced by whichever job runs next.
            UpdateProgress.beginJob(UpdateProgress.Job(applicationContext.packageName,
                applicationContext.packageName,
                applicationContext.applicationInfo.loadLabel(applicationContext.packageManager).toString(),
                UpdateProgress.STAGE_DOWNLOADING))
            // #903 download + install under a dataSync foreground job (refused = carry on).
            // #fgs-timeout only when a human asked (force): the periodic pass is plain background work.
            if (BatchForeground.allowed(applicationContext))
                fgsToken = holdDataSync(userInitiated = force) { BatchForeground.foregroundInfo(it) }
            val apk = UpdateChecker(applicationContext).download(available) {
                isStopped || UpdateProgress.cancelRequested
            }
            Log.i("Updater/Worker", "downloaded ${available.assetTitle} (${available.remoteSize} bytes)")
            UpdateProgress.stage(UpdateProgress.STAGE_INSTALLING)
            installSelf(apk)
            Result.success()
        } catch (c: java.util.concurrent.CancellationException) {
            // Cancel button: state is already Cancelled — leave it, unwind cleanly.
            Log.i("Updater/Worker", "update cancelled by user")
            Result.success()
        } catch (refused: InstallRefused) {
            // TERMINAL, so do not re-arm. Retrying asks the same question of the
            // same bytes and gets the same answer forever, which is a background
            // loop that can never succeed. The installer has already published
            // State.Failed, so the refusal is on the screen either way.
            Log.w("Updater/Worker", "refused: ${refused.message}")
            Result.failure()
        } catch (t: Throwable) {
            Log.w("Updater/Worker", "check failed: ${t.message}", t)
            Result.retry()
        } finally {
            // Process-wide flag: it must not survive this worker, or the next
            // thing the USER starts would run with no progress bar at all.
            UpdateProgress.quiet = false
            // Same for the persisted one, and for the same reason — this is the
            // outermost scope of the pass, so here the unconditional clear is
            // the correct one. A crash mid-pass costs one silenced pass, never a
            // permanently muted app.
            AutoUpdatePrefs.setUnattendedPass(applicationContext, false)
        }
    }

    /**
     * Unattended constellation pass: update the fleet apps that already have a
     * newer release, never install missing ones. MISSING is deliberately out of
     * scope here — a first install cannot be silent (we are not yet the
     * installer of record for that package, so the OS shows its dialog no
     * matter what we ask for), and an unattended job must not throw dialogs at
     * someone who is not looking at the phone. Missing apps stay a job for the
     * Update-All button, which runs with the user watching.
     *
     * Failures are per-app inside [Fleet.installAll]; a fleet problem must not
     * cost this app its own update, which is the whole reason for the catch.
     */
    private fun updateFleet() {
        // #975 ONE OWNER. Every fleet app compiles this worker in and bakes the whole fleet, so
        // Mail, Drive, Account... each ran this pass on every process start (Updater.start's kick):
        // each downloaded the ~400 MB rootfs libs into its own cache, and with no privileged
        // channel committed them again and again - a "tap to finish installing" for the rootfs
        // libs from every app, in a loop. The fleet is Cloud Store's: here it is the only host.
        if (!ownsFleetPass(applicationContext.packageName)) {
            Log.i("Updater/Worker", "fleet pass is Cloud Store's - ${applicationContext.packageName} updates only itself")
            return
        }
        val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        if (fleet.isEmpty()) return
        runCatching {
            // CAP IT. This pass is unattended, and installAll's own contract
            // says so: every install it starts can leave a tap-to-confirm
            // notification holding a PackageInstaller session until the user
            // answers, and Android refuses new sessions past 50 per UID. This
            // call passed no limit at all, so it defaulted to Int.MAX_VALUE —
            // a whole 50-entry fleet from one unattended wake-up.
            //
            // It also made the fleet pass non-deterministic once the batch
            // became single-flight: ConstellationWorker runs the SAME pass
            // capped at AU_MAX_PER_PASS, so whichever worker won the race
            // decided whether 3 apps or all of them installed. Same cap, same
            // behaviour, whoever gets there first.
            // THE CAP EXISTS FOR ONE REASON ONLY: a SessionInstall leaves a
            // tap-to-confirm notification holding a PackageInstaller session
            // until the user answers it, and Android refuses new sessions past
            // 50 per UID. A shell install opens no session and shows nothing,
            // so when the shell channel is live the cap protects against
            // nothing and only stops the fleet from ever catching up — three
            // apps per six hours against a fifty-entry fleet is why
            // "auto-update on" never finished.
            // That whole decision now lives in Fleet.autoPass, which both
            // unattended workers call. It also carries the mode: Mode.AUTO
            // (apps: updates only; libs: updates AND missing), because
            // Mode.UPDATES dropped every State.Missing and a lib is Missing on
            // any device that never installed one — all 36 lib entries were
            // skipped on every pass.
            val pass = Fleet.autoPass(applicationContext, fleet, owner = "Updater/Worker")
            // Silent means nothing is DRAWN, never that nothing is logged, and
            // never a bare "acted on 0" that hides which of the three
            // do-nothing reasons applied.
            Log.i("Updater/Worker", "fleet auto-update: ${pass.reason}")
        }.onFailure { Log.w("Updater/Worker", "fleet auto-update failed: ${it.message}", it) }
    }

    /**
     * THE SELF-UPDATE TAKES THE FLEET'S LADDER, NOT ONLY ITS LAST RUNG (#588).
     *
     * This called UpdateInstaller directly — a PackageInstaller session, which
     * for an unattended run means STATUS_PENDING_USER_ACTION with no Activity
     * to show the confirm on. The phone downloaded the new SuperApp, committed
     * the session, logged "confirm launch deferred to notification" and stayed
     * on the old build, while a fleet library in the same pass installed
     * silently through the embedded-adb shell. Same ladder as Fleet.channels
     * now: the privileged shell first (zero dialogs, works with the screen
     * off), the session second unless this device opted out of prompting.
     *
     * `pm install -r` of OUR OWN package kills this process when it succeeds,
     * so a shell self-install usually never reads its own "Success". The log
     * line before it is the evidence; the new versionCode is the proof.
     */
    private fun installSelf(apk: VerifiedApk) {
        Log.i("Updater/Worker", "self-install: trying the privileged shell channel first")
        val declined = ShellInstall.shellInstall(applicationContext, apk) ?: return
        Log.w("Updater/Worker", "self-install: shell channel declined — $declined")
        if (AutoUpdatePrefs.requireSilent(applicationContext)) {
            val why = "the SuperApp update was downloaded but NOT installed: the privileged " +
                "shell channel declined ($declined) and this device is set to never prompt, " +
                "so no confirmation was asked for. Re-pair Wireless debugging, or turn " +
                "\"never prompt\" off to install with one tap"
            UpdateProgress.update(UpdateProgress.State.Failed(why))
            throw InstallRefused(why)
        }
        UpdateInstaller(applicationContext).install(apk)
    }

    companion object {
        const val KEY_FORCE = "force"
        const val KEY_CONSENTED = "consented"

        /** #975 The one package whose unattended pass updates the OTHER apps and the libs. */
        fun ownsFleetPass(packageName: String): Boolean = packageName == StoreNotifyGate.STORE_PKG
    }
}
