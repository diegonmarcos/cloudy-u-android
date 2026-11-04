package com.diegonmarcos.clouddrive

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.cloudlib.gitrepos.SyncEvent
import com.diegonmarcos.cloudlib.gitrepos.SyncHistory
import com.diegonmarcos.cloudlib.gitrepos.SyncSchedule
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #575 the GitSync app's scheduled sync, natively; #579 per repository. ONE periodic
 * request ticks at the BASE period build.json::storage.git_sync declares (WorkManager's
 * floor); on each tick every repository that opted in (ManagedRepo.autoSync) is
 * checked by [SyncSchedule.isDue] against its OWN period (0 = the base) and its own
 * network rule, and the due ones get the engine's one-tap sync. Every run is written
 * to the sync history (trigger `scheduled`), so the Sync ▸ Git card and its History
 * show exactly what this worker did. The registry and the credential store are the
 * SAME files the screen uses.
 *
 * Lives in the app, not the library: the library owns the engine and the screen, the
 * app owns when they run (lib → app is forbidden; app → lib is how the chrome uses
 * an engine).
 */
class GitSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        runCatching { JobPathMigration.run(applicationContext) }
        com.diegonmarcos.clouddrive.sync.TermuxGitTransport.install(applicationContext)
        val registry = RepoRegistry(File(applicationContext.filesDir, REGISTRY_FILE))
        val credentials = GitCredentialStore(applicationContext)
        val history = SyncHistory(File(applicationContext.filesDir, SyncHistory.FILE))
        val unmetered = networkUnmetered(applicationContext)
        val now = System.currentTimeMillis() / 1000
        // #1313 before the cycle: abandoned incoming_* / tmp_pack* files out of objects/pack (the age gate protects a live download;
        // the worker has no view of the foreground coordinator's jobs).
        runCatching {
            val d = com.diegonmarcos.clouddrive.Declarations.sync.git.stalePackCleanup
            if (d.enabled) {
                val git = SharedStore.gitRoot(); val bare = com.diegonmarcos.clouddrive.Declarations.sync.git.bareRoot
                val r = if (com.diegonmarcos.clouddrive.sync.StoreGit.useExec(applicationContext)) com.diegonmarcos.clouddrive.sync.StoreGit.sweepStalePacks(applicationContext, git, bare, d.patterns, d.minAgeHours)
                    else com.diegonmarcos.cloudlib.gitrepos.StalePackCleaner.sweep(com.diegonmarcos.cloudlib.gitrepos.StalePackCleaner.packDirs(git, bare), d.patterns, d.minAgeHours, System.currentTimeMillis(), false)
                val line = r?.let { com.diegonmarcos.cloudlib.gitrepos.StalePackCleaner.message(it) }.orEmpty()
                if (line.isNotEmpty()) history.append(SyncEvent(now, "stale-packs", "stale packs", SyncHistory.TRIGGER_SCHEDULED, true, "stale_pack_cleanup: $line"))
            }
        }
        registry.load().filter { it.autoSync }.forEach { repo ->
            if (isStopped) return Result.retry()
            val due = SyncSchedule.isDue(
                autoSync = repo.autoSync, repoIntervalMinutes = repo.syncIntervalMinutes, baseIntervalMinutes = BuildConfig.GIT_SYNC_INTERVAL_MINUTES,
                lastSyncEpochSeconds = repo.lastSyncEpochSeconds, nowEpochSeconds = now,
                requireUnmetered = repo.syncRequireUnmetered, networkUnmetered = unmetered,
            )
            if (!due) return@forEach
            val viaExec = com.diegonmarcos.clouddrive.sync.StoreGit.useExec(applicationContext)
            val result = if (viaExec) runCatching {
                // cloud-shared-store: not in cld.termux's uid -> the sync runs inside it (CLOUD_EXEC).
                com.diegonmarcos.clouddrive.sync.StoreGit.sync(
                    applicationContext, File(repo.path), repo.syncMessage,
                    repo.authorName.ifBlank { DEFAULT_AUTHOR }, repo.authorEmail.ifBlank { DEFAULT_EMAIL }, repo.pullRebase,
                    (credentials.authFor(repo) as? com.diegonmarcos.cloudlib.gitsync.GitAuth.Https)?.secret.orEmpty(),
                )
            }.getOrElse { GitOpResult(false, it.message ?: it.toString()) } else runCatching {
                GitEngine(applicationContext, File(repo.path)).use { engine ->
                    engine.sync(
                        repo.syncMessage,
                        repo.authorName.ifBlank { DEFAULT_AUTHOR },
                        repo.authorEmail.ifBlank { DEFAULT_EMAIL },
                        rebase = repo.pullRebase,
                        auth = credentials.authFor(repo),
                    )
                }
            }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            Log.i(TAG, "${repo.name}: ${result.summary}")
            val at = System.currentTimeMillis() / 1000
            registry.upsert(repo.copy(lastSyncEpochSeconds = at, lastSyncSummary = "scheduled: " + result.summary))
            history.append(SyncEvent(at, repo.id, repo.name, SyncHistory.TRIGGER_SCHEDULED, result.ok, result.summary, result.details))
        }
        // A conflict or a rejected push is the user's to look at (the card goes red and says
        // so), not something a retry storm fixes: report success so the period stays regular.
        return Result.success()
    }

    companion object {
        private const val TAG = "GitSyncWorker"
        const val WORK_NAME = "cloud-drive-git-sync"
        /** The registry file the git manager screen writes (libs:git-sync names it in GitSyncScreen). */
        const val REGISTRY_FILE = "git-sync/repos.json"
        private const val DEFAULT_AUTHOR = "cloud-drive"
        private const val DEFAULT_EMAIL = "cloud-drive@localhost"

        /** null when the platform will not say (no active network, no capabilities). */
        fun networkUnmetered(context: Context): Boolean? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return null
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        /**
         * Enqueue (or re-apply) the periodic sync from the baked declaration. UPDATE, not
         * KEEP, for the reason libs:updater gives: a KEEP'd request pins the first interval a
         * phone ever saw and a later build.json edit never reaches it. The request's network
         * constraint is CONNECTED (a repository declaring "any network" must get its tick);
         * the base declaration's unmetered rule is what a repository inherits when it says
         * nothing, applied per repository in doWork.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (BuildConfig.GIT_SYNC_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            // #1289 the tick is the smallest period an opted-in repository asks for (Configs-AutoSync, floor 15), so
            // "every 15 mins" on one repository is served even when the declared base is longer.
            val opted = runCatching { RepoRegistry(File(context.applicationContext.filesDir, REGISTRY_FILE)).load().filter { it.autoSync }.map { it.syncIntervalMinutes } }.getOrDefault(emptyList())
            val tick = com.diegonmarcos.clouddrive.sync.AutoSyncPlan.tickMinutes(BuildConfig.GIT_SYNC_INTERVAL_MINUTES, opted)
            val request = PeriodicWorkRequestBuilder<GitSyncWorker>(tick, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
