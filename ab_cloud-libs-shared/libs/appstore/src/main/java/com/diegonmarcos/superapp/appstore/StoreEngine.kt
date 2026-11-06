package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.appstore.dl.CheckGate
import com.diegonmarcos.superapp.appstore.dl.DlJob
import com.diegonmarcos.superapp.appstore.dl.DlMirror
import com.diegonmarcos.superapp.appstore.dl.DlSource
import com.diegonmarcos.superapp.appstore.dl.DlSpec
import com.diegonmarcos.superapp.appstore.dl.DlState
import com.diegonmarcos.superapp.appstore.dl.DownloadCache
import com.diegonmarcos.superapp.appstore.dl.DownloadEngine
import com.diegonmarcos.superapp.appstore.dl.DownloadStore
import com.diegonmarcos.superapp.appstore.dl.Throttle
import com.diegonmarcos.superapp.updater.BatchForeground
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.diegonmarcos.superapp.updater.source.EngineLegs
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * #1517 THE STORE'S ONE DOWNLOAD ENGINE, process-wide. Every fragment, tab and recomposition goes through here and none owns a
 * transfer: [get] builds the single [DownloadEngine] once (queue persisted in files/store-downloads.json, `.part` files and the
 * finished APKs in the APK cache dir), restores what a kill or reboot interrupted, and keeps it for the life of the process.
 *
 * [fetch] is the blocking call the pipeline's verbs make ([StoreStages.fetch]): it ASKS the engine and WAITS, mirroring progress onto
 * the keyed [UpdateProgress] state for the page it was asked from. If that caller goes away the job does not: it is the engine's.
 * Install is NOT here: a verified file goes to the existing install path ([StoreStages.install] / installCached), unchanged.
 *
 * Background rule (#1502): nothing here starts work by itself. The engine only runs jobs the user started (while the Store is
 * open, with the shared "Store downloads" foreground notification held only while the queue is non-empty and released the moment it
 * empties) or, after a restart, jobs the background policy allows (Auto-update ON and unmetered).
 */
object StoreEngine {
    private const val TAG = "StoreEngine"
    @Volatile private var engine: DownloadEngine? = null

    fun get(ctx: Context): DownloadEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val app = ctx.applicationContext
            val cache = DownloadCache(ApkCache.dir(app))
            val e = DownloadEngine(
                store = DownloadStore(File(app.filesDir, "store-downloads.json")),
                cache = cache,
                mirrorsFor = { job -> mirrors(app, job) },
                maxConcurrent = BuildConfig.DL_MAX_CONCURRENT,
                maxRetries = BuildConfig.DL_MAX_RETRIES,
                onVerified = { job, file ->
                    // The same two steps Fleet.download took for every source: record what was cached, clear the failure note.
                    ApkCache.keep(app, file)
                    ApkCache.clearNote(app, job.pkg)
                },
                onFailed = { job ->
                    ApkCache.note(app, job.pkg, ApkCache.STAGE_DOWNLOAD, "could not download ${job.id}: ${job.error}")
                },
                onBusy = { BatchForeground.begin(app) },
                onIdle = { BatchForeground.end(app); pruneAsync(app) },
            )
            engine = e
            e.start { j -> j.userStarted || (StorePriority.autoOn(app) && StorePriority.unmetered(app)) }
            return e
        }
    }

    /** Tests only: drop the process-wide engine so the next [get] binds the CURRENT app's files dir and cache (each Robolectric test has its own). */
    fun resetForTest() = synchronized(this) { engine = null; asked.clear() }

    /** The engine if this process has built it yet, else null (never builds it). */
    fun engineOrNull(): DownloadEngine? = engine

    /** What the engine is doing about [id] right now, or null. Cheap; safe from any thread (it never starts the engine). */
    fun peek(id: String): DlJob? = engine?.job(id)

    private val fleet by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }

    /** The app each ask named: the engine fetches what it was ASKED for; the baked fleet only serves jobs restored after a restart. */
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Fleet.App>()

    private fun mirrors(app: Context, job: DlJob): List<DlMirror> {
        val a = asked[job.id] ?: fleet.firstOrNull { it.id == job.id }
            ?: return emptyList()
        return EngineLegs.legs(app, a).map { leg ->
            DlMirror(leg.name) { val p = leg.plan(); DlSource(p.url, p.headers, p.sha256, p.bytes) }
        }
    }

    /** LRU + age pruning of finished APKs. Never a `.part`, never a file the engine lists, never one still waiting to be installed. */
    private fun pruneAsync(app: Context) {
        thread(name = "store-dl-prune") {
            runCatching {
                val e = engine ?: return@runCatching
                val listed = e.protectedFiles()
                val r = e.cache.prune(BuildConfig.DL_CACHE_MAX_BYTES, BuildConfig.DL_CACHE_MAX_AGE_MS,
                    protect = { f ->
                        val entry = ApkCache.entry(f)
                        f.name in listed || entry.record == null || !ApkCache.isLanded(app, entry)
                    }, remove = { ApkCache.drop(it) })
                if (r.deleted.isNotEmpty()) Log.i(TAG, "pruned ${r.deleted.size} cached APK(s), ${r.freedBytes / 1_000_000} MB")
            }
        }
    }

    // ── what the pipeline calls ───────────────────────────────────────────────────

    /**
     * Stage 1 for [app]: the published build, verified, or null when it did not complete (paused, cancelled, failed: the failure is in
     * the row's note). Blocking. A build already in the cache is reused; a job the engine already owns is JOINED, never restarted.
     */
    fun fetch(ctx: Context, app: Fleet.App, userStarted: Boolean = true): VerifiedApk? {
        val e = get(ctx)
        Fleet.cachedRelease(ctx.applicationContext, app)?.let { hit ->
            Log.i(TAG, "download ${app.id}: cache HIT ${hit.file.name} → ${hit.evidence}")
            UpdateProgress.update(UpdateProgress.State.Downloading(100, hit.length, hit.length))
            if (ApkCache.record(hit.file) == null) ApkCache.keep(ctx.applicationContext, hit.file)
            ApkCache.clearNote(ctx.applicationContext, app.pkg)
            return hit
        }
        asked[app.id] = app
        e.enqueue(DlSpec(
            id = app.id, pkg = app.pkg, versionCode = app.declaredVersionCode.takeIf { it > 0 } ?: -1L, label = app.label,
            namePrefix = "fleet-${app.id}-", priority = if (userStarted) 10 else 0, userStarted = userStarted,
        ))
        return waitFor(ctx, app, e)
    }

    /** Wait for the job the engine already owns for [app] (a second ask while it runs): nothing is started, restarted or cancelled. */
    fun join(ctx: Context, app: Fleet.App): VerifiedApk? {
        val e = get(ctx)
        val j = e.job(app.id) ?: return null
        return if (j.state.active) waitFor(ctx, app, e) else null
    }

    private fun waitFor(ctx: Context, app: Fleet.App, e: DownloadEngine): VerifiedApk? {
        val done = e.await(app.id) { j ->
            val st = when (j.state) {
                DlState.RUNNING -> UpdateProgress.State.Downloading(j.percent.coerceAtLeast(0), j.bytes, j.total)
                DlState.QUEUED -> UpdateProgress.State.Waiting(com.diegonmarcos.superapp.appstore.dl.DlText.line(j))
                DlState.VERIFYING -> UpdateProgress.State.CheckingManifest
                else -> null
            }
            if (st != null) UpdateProgress.update(st)
        } ?: return null
        return when (done.state) {
            DlState.DONE -> verified(ctx, app, done)
            DlState.FAILED -> {
                UpdateProgress.update(UpdateProgress.State.Failed("could not download ${app.id}: ${done.error}", appId = app.id, pkg = app.pkg))
                null
            }
            else -> null   // paused or cancelled by the user: not a failure, nothing to report
        }
    }

    private fun verified(ctx: Context, app: Fleet.App, j: DlJob): VerifiedApk? {
        val f = File(ApkCache.dir(ctx.applicationContext), j.file)
        val v = if (j.sha256.isNotEmpty()) VerifiedApk.byDigest(f, j.sha256) else VerifiedApk.bySize(f, f.length())
        if (v == null) {
            ApkCache.drop(f)
            ApkCache.note(ctx.applicationContext, app.pkg, ApkCache.STAGE_DOWNLOAD, "could not download ${app.id}: the file is not an APK")
        }
        return v
    }
}

/**
 * #1517 Update checks, decoupled from downloads: a TTL cache plus one in-flight check per app, run one PASS at a time on their own
 * thread. A check reads metadata ([Fleet.status]); it never calls the engine, never touches `.part` or the cache dir.
 */
object StoreChecks {
    val gate = CheckGate<String, Fleet.State>(BuildConfig.DL_CHECK_TTL_MS)
    private val serial = Executors.newSingleThreadExecutor { r -> Thread(r, "store-checks").apply { isDaemon = true } }
    private val updatesFirst = Throttle(BuildConfig.DL_CHECK_TTL_MS)

    fun status(ctx: Context, app: Fleet.App, force: Boolean): Fleet.State = gate.get(app.id, force) { Fleet.status(ctx, app) }

    /** Run [each] for every app, in parallel inside one pass, passes strictly one after another. Returns at once. */
    fun pass(apps: List<Fleet.App>, each: (Fleet.App) -> Unit) {
        serial.execute { apps.map { a -> thread(name = "fleet-check-${a.id}") { runCatching { each(a) } } }.forEach { it.join() } }
    }

    /** The "updates first" chain starts at most once per TTL: a tab switch asks, it does not start another. */
    fun mayStartUpdatesFirst(): Boolean = updatesFirst.tryAcquire()
}
