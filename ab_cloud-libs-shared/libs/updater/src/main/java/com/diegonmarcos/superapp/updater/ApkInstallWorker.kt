package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.diegonmarcos.superapp.updater.install.UpdateInstaller
import com.diegonmarcos.superapp.updater.source.Download
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.core.DataSyncBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WorkManager job: download a plain-HTTPS APK (a direct asset URL — e.g. a
 * GitHub release attachment) and hand it to [UpdateInstaller] for a FOREIGN
 * package. This is how the launcher installs companion apps (Cloud-Comms /
 * Cloud-IDE hubs) on first tile tap.
 *
 * Distinct from [UpdateWorker]: that one talks to GHCR's OCI API to self-update
 * Cloud-SuperApp by digest. Here the URL + target package are passed in as
 * input data (sourced from build.json::ui.external_apps), there is no manifest
 * step, and the install targets another applicationId. Progress is published on
 * the shared [UpdateProgress] bus so the existing overlay/notification surface
 * reflects the download with no extra UI.
 */
class ApkInstallWorker(
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
        val url   = inputData.getString(KEY_URL)
        val pkg   = inputData.getString(KEY_PKG)
        val label = inputData.getString(KEY_LABEL) ?: pkg ?: "app"
        if (url.isNullOrBlank() || pkg.isNullOrBlank()) {
            Log.w(TAG, "missing url/pkg input — url=$url pkg=$pkg")
            return@withContext Result.failure()
        }
        try {
            val apk = ApkCache.file(applicationContext, "companion-$pkg.apk")
            UpdateProgress.beginJob(UpdateProgress.Job(pkg, pkg, label, UpdateProgress.STAGE_DOWNLOADING))
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0, -1))
            // #903 download + install under a dataSync foreground job (refused = carry on).
            // #fgs-timeout a companion install is always a tap: user-initiated, still within the budget.
            if (BatchForeground.allowed(applicationContext))
                fgsToken = holdDataSync(userInitiated = true) { BatchForeground.foregroundInfo(it) }
            var declared = 0L
            // The private copy of this loop is gone. It was the only one of the
            // three that chased redirects by hand and the only one that could
            // not resume; [Download] now does both for every caller, so a
            // companion APK survives the same interruptions a fleet APK does.
            Download.toFile(
                url = url,
                target = apk,
                shouldCancel = { isStopped },
            ) { bytes, total ->
                if (total > declared) declared = total
                val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, total))
            }
            // A companion APK comes from a plain URL, so there is no digest to
            // check it against — take the strongest evidence available: the
            // declared length when the server gave one, otherwise structure
            // alone. Either beats handing the installer an unexamined file,
            // which is how a truncated download became
            // "INSTALL_PARSE_FAILED_NOT_APK" rather than "the download stopped".
            UpdateProgress.stage(UpdateProgress.STAGE_VERIFYING)
            // The fleet publishes `<asset>.sha256` next to every release APK.
            // When it is there, the digest is the evidence and a mismatch is a
            // failure; when it is not (a vendor URL, a 404), fall through to
            // the declared length / structure exactly as before.
            val sidecar = runCatching { fetchSidecar(url) }.getOrNull()
            val verified = (if (sidecar != null)
                VerifiedApk.byDigest(apk, sidecar)
                    ?: error("sha256 mismatch for $pkg (sidecar ${sidecar.take(12)}…)")
                else null)
                ?: VerifiedApk.bySize(apk, declared)
                ?: VerifiedApk.structural(apk)
                ?: error("companion APK for $pkg failed verification (${apk.length()} B)")
            Log.i(TAG, "downloaded $label (${apk.length()} bytes, ${verified.evidence}) → installing $pkg")
            // install() flips UpdateProgress to Installing and commits the
            // PackageInstaller session. Do NOT force Done here — that races the
            // async session and would overwrite the "Installing…" overlay before
            // the system installer even appears. The terminal state (Done /
            // Failed) is driven by PackageInstallerReceiver from the real
            // PackageInstaller callback, so the overlay tracks the actual
            // install lifecycle instead of flickering straight to "Done".
            UpdateProgress.stage(UpdateProgress.STAGE_INSTALLING)
            UpdateInstaller(applicationContext).install(verified, pkg)
            Result.success()
        } catch (c: java.util.concurrent.CancellationException) {
            // User hit Cancel: cancelNow() already flipped state to Cancelled.
            // Don't clobber it with Failed. Worker is cancelled → success is moot.
            Log.i(TAG, "install of $pkg cancelled by user")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "install of $pkg failed: ${t.message}", t)
            UpdateProgress.update(UpdateProgress.State.Failed(t.message ?: t.toString(), appId = pkg,
                pkg = pkg, stage = UpdateProgress.job?.stage.orEmpty(), app = label))
            Result.failure()
        }
    }

    /** The `<url>.sha256` sidecar's digest, or null when there is none. */
    private fun fetchSidecar(url: String): String? {
        val f = ApkCache.file(applicationContext, "sidecar-${url.hashCode()}.sha256")
        f.delete(); java.io.File(f.parentFile, f.name + ".part").delete()
        return try {
            Download.toFile(url = "$url.sha256", target = f, shouldCancel = { isStopped })
            parseSha256(f.readText())
        } finally { f.delete() }
    }

    companion object {
        /** First 64-hex token of a `sha256sum`-style sidecar, lower-cased; null when absent. */
        fun parseSha256(text: String): String? =
            Regex("\\b[0-9a-fA-F]{64}\\b").find(text)?.value?.lowercase()

        private const val TAG = "Updater/ApkInstall"
        const val KEY_URL = "url"
        const val KEY_PKG = "pkg"
        const val KEY_LABEL = "label"
    }
}
