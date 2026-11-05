package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.content.Intent
import com.diegonmarcos.superapp.updater.BatchForeground
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.FleetIdentity
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import java.util.concurrent.ConcurrentHashMap

/**
 * #774 DOWNLOAD → INSTALL → CLEAR, as three stages a Store row can show and a
 * user (or the auto chain) can drive one at a time. No UI here: the Cloud tab,
 * the debug API and the auto chain all read [stage] and call the same three
 * verbs, so the screen and `/api/store/stage` cannot disagree.
 *
 * The stage is DERIVED, never stored: the persistent cache ([ApkCache], under
 * no_backup) plus what the device has installed plus the last outcome note
 * ([ApkCache.note], written by the download path and the install receiver).
 * That is what makes it survive an install cancel, a failure, an app restart
 * and a reboot — there is no in-memory state to lose except "a verb is running
 * right now", which is exactly the part that should vanish with the process.
 *
 *  - Download fetches into the cache ([Fleet.download]: cache-hit first, then
 *    Range-resumable sources, verified by the release sha256).
 *  - Install is the WHOLE chain (#784): no installable cached APK → Download
 *    first, then hand the CACHED file to the existing installer ladder
 *    ([FleetInstall.install] → shell `pm install`, else a PackageInstaller
 *    session), then Clear once [ApkCache.landed] proves the install. It is
 *    never disabled for want of a cache. A cancel or failure stops the chain at
 *    that stage and leaves the file; Install again resumes from there.
 *  - Clear deletes the app's cached APK(s) — on demand, or by the chain once
 *    the install is proven.
 *  - [downloadAll] / [updateAll] are the same verbs over a list, with a
 *    per-app report and a dry run; Update all installs from the cache with no
 *    network at all.
 */
object StoreStages {

    private const val TAG = "StoreStages"

    init {
        // #903 the foreground service's notification IS the Store bar line.
        BatchForeground.text = { progress()?.text }
        BatchForeground.icon = { AppStoreHost.notificationIcon }
        BatchForeground.launch = { ctx ->
            AppStoreHost.launchActivity?.let { Intent(ctx, it).apply { AppStoreHost.launchExtras.forEach { (k, v) -> putExtra(k, v) } } }
        }
    }

    const val DOWNLOAD = "download"
    const val INSTALL = "install"
    const val CLEAR = "clear"

    /**
     * One row's truth. [id] is the machine word (update_available, not_installed,
     * downloading, cached, installing, installed, error, blocked, unknown);
     * [text] is the sentence the row shows; [actions] are the verbs that make
     * sense from here, in stage order; [failedAt] names the stage an error
     * stopped at, so the user takes over from exactly there.
     */
    class Stage(
        val id: String,
        val text: String,
        val actions: List<String>,
        val cached: ApkCache.Entry? = null,
        val failedAt: String? = null,
    )

    /** pkg → the verb running right now. In-memory on purpose: see the header. */
    private val busy = ConcurrentHashMap<String, String>()

    /**
     * The installer the Install stage hands the cached file to. A seam with
     * the production ladder as its only default — the same shape as
     * [Fleet.downgradePolicy] — so a test can stand in for Android's install
     * sheet (accept / cancel) without a device. Returns the message to show,
     * or null on handover/success, exactly like [FleetInstall.install].
     */
    @Volatile
    var installer: (Context, Fleet.App, VerifiedApk) -> String? = FleetInstall::install

    private fun pkgs(app: Fleet.App) = setOfNotNull(app.pkg, app.altId)

    /** The newest complete cached APK for [app], or null. A record names the
     *  package; a record-less file (its manifest would not parse) is still the
     *  app's when the store named it `fleet-<id>-…`. */
    fun cachedFor(ctx: Context, app: Fleet.App): ApkCache.Entry? =
        ApkCache.entries(ctx).filter { e ->
            !e.partial && (e.record?.pkg?.let { it in pkgs(app) }
                ?: e.file.name.startsWith("fleet-${app.id}-"))
        }.maxWithOrNull(compareBy({ it.record?.versionCode ?: 0L }, { it.modifiedAt }))

    /** Bytes of an interrupted download waiting to be resumed, or 0. */
    fun partialBytes(ctx: Context, app: Fleet.App): Long =
        ApkCache.entries(ctx).filter { it.partial && it.file.name.startsWith("fleet-${app.id}-") }
            .sumOf { it.bytes }

    private fun mb(b: Long) = FleetIdentity.human(b)

    /**
     * The row's stage. [remote] is the network answer ([Fleet.status]) when the
     * caller has one; without it the stage still says everything the device
     * itself knows — cached, installed, last error — and only "is there an
     * update" is left unknown.
     */
    fun stage(ctx: Context, app: Fleet.App, remote: Fleet.State? = null): Stage {
        val s = stageBase(ctx, app, remote)
        // #1121 an installed terminal shows what its unpack said ("unpacked" or the error).
        val unpack = if (s.id == "installed") UnpackAfterInstall.resultOf(ctx, app.pkg) else null
        return if (unpack == null) s else Stage(s.id, "${s.text} · $unpack", s.actions, s.cached, s.failedAt)
    }

    /** #1517 The row of a job the engine owns: percent, bytes, speed and ETA. A paused one offers Download, which resumes it. */
    private fun engineStage(j: com.diegonmarcos.superapp.appstore.dl.DlJob): Stage = when (j.state) {
        com.diegonmarcos.superapp.appstore.dl.DlState.QUEUED -> Stage("queued", com.diegonmarcos.superapp.appstore.dl.DlText.line(j), emptyList())
        com.diegonmarcos.superapp.appstore.dl.DlState.PAUSED -> Stage("paused", com.diegonmarcos.superapp.appstore.dl.DlText.line(j), listOf(DOWNLOAD))
        else -> Stage("downloading", com.diegonmarcos.superapp.appstore.dl.DlText.line(j), emptyList())
    }

    private fun stageBase(ctx: Context, app: Fleet.App, remote: Fleet.State?): Stage {
        when (busy[app.pkg]) {
            DOWNLOAD -> {
                // #1517 the engine's own record first: bytes, speed and ETA of THIS job.
                StoreEngine.peek(app.id)?.takeIf { it.state.active }?.let { return engineStage(it) }
                // This row's OWN state (keyed by package), never the process-wide one another row is writing.
                val r = StoreJobs.board.row(app.pkg)
                if (r?.phase == JobBoard.Phase.QUEUED) return Stage("queued", r.text(), emptyList())
                return Stage("downloading",
                    if (r != null && r.bytes > 0) "downloading ${r.percent.coerceAtLeast(0)}% · ${mb(r.bytes)}" +
                        (if (r.total > 0) " of ${mb(r.total)}" else "") else "downloading…",
                    emptyList())
            }
            INSTALL -> {
                val r = StoreJobs.board.row(app.pkg)
                if (r?.phase == JobBoard.Phase.QUEUED) return Stage("queued", r.text(), emptyList(), cachedFor(ctx, app))
                return Stage("installing", "installing from cache…", emptyList(), cachedFor(ctx, app))
            }
        }
        // #1517 a job the engine restored after a restart has no UI thread and no `busy` entry: the engine's record is the truth.
        StoreEngine.peek(app.id)?.takeIf { it.state.active }?.let { return engineStage(it) }
        // #858 a handover that was aborted, abandoned or never answered turns
        // into the Install-stage note read below: the row offers Retry.
        // #906 a handover still waiting says so (and what PackageInstaller last said),
        // instead of falling back to "cached (ready to install)" with no word.
        if (runCatching { StoreInstallWatch.sweep(ctx, app) }.getOrNull() == StoreInstallWatch.WAITING)
            return Stage("installing", StoreInstallWatch.waitingText(ApkCache.installerStatusOf(ctx, app.pkg)),
                emptyList(), cachedFor(ctx, app))
        // A uid change (INSTALL_FAILED_UID_CHANGED, -24) is not retryable: while the old
        // build is installed the row is the one-time migration, with no Install/Download.
        val mig = runCatching { SharedUidMigration.observe(ctx, app) }.getOrNull()
        if (mig != null && SharedUidMigration.blocks(mig, installedKey(ctx, app)))
            return Stage(SharedUidMigration.STAGE, "${mig.cause.constant}: ${app.label} ${mig.version} changes its Linux " +
                "user — migrate once (back up, uninstall, install, restore); install retries are stopped",
                emptyList(), cachedFor(ctx, app))
        // #780 PRECEDENCE: busy → ACTIONABLE cache → remote → installed. A
        // cache whose build is already on the device (landed) or that the
        // remote has moved past is NOT actionable, and it must never stand in
        // front of the remote's answer: it used to return "installed · Clear"
        // before `remote` was even read, so any app whose LAST update was still
        // cached never showed its NEXT one — no Download, fleet-wide.
        var note = ApkCache.noteOf(ctx, app.pkg)
        val e = reapLanded(ctx, app)
        val act = e?.takeIf { actionable(ctx, app, it, remote) }
        // An Install-stage note with nothing installable behind it is history,
        // and left in place it holds the unattended pass (Fleet.heldAtInstall)
        // off this app forever.
        if (act == null && note?.stage == ApkCache.STAGE_INSTALL) { ApkCache.clearNote(ctx, app.pkg); note = null }
        if (act != null) {
            val ver = act.record?.versionCode?.let { " v$it" } ?: ""
            val why = note?.takeIf { it.stage == ApkCache.STAGE_INSTALL }?.message?.let { m ->
                // -7 outside a declared migration group: the installed copy is signed with another key.
                if (SharedUidMigration.cause(m) == SharedUidMigration.Cause.UPDATE_INCOMPATIBLE &&
                    !SharedUidMigration.migrates(SharedUidMigration.Cause.UPDATE_INCOMPATIBLE, app.pkg))
                    "signature mismatch — the installed copy is signed with a different key; uninstall it to install this build ($m)"
                else m
            }
            // Download stays live beside Install: a new download replaces the
            // cached APK, so it never has to wait for a Clear (owner rule).
            return if (why != null)
                Stage("error", "cached$ver ${mb(act.bytes)} · install did not finish: $why — Install retries " +
                    "from the cache", listOf(INSTALL, DOWNLOAD, CLEAR), act, failedAt = INSTALL)
            else Stage("cached", "cached$ver ${mb(act.bytes)} (ready to install)", listOf(INSTALL, DOWNLOAD, CLEAR), act)
        }
        // A non-actionable cache left behind: Clear is an EXTRA verb, never the only one.
        val extra = if (e != null) listOf(CLEAR) else emptyList()
        // #784 Install is live wherever Download is: it IS Download → Install →
        // Clear, so having nothing cached is no reason to grey it out.
        val fetch = listOf(INSTALL, DOWNLOAD) + extra
        val kept = e?.let { " · cached APK ${mb(it.bytes)} — Clear frees it" } ?: ""
        val part = partialBytes(ctx, app)
        val partNote = if (part > 0) " · ${mb(part)} kept, Download resumes" else ""
        if (note?.stage == ApkCache.STAGE_DOWNLOAD)
            return Stage("error", "download failed: ${note.message}$partNote", fetch,
                failedAt = DOWNLOAD)
        return when (remote) {
            is Fleet.State.UpdateAvailable ->
                Stage("update_available", "update available · ${mb(remote.bytes)}$partNote", fetch)
            is Fleet.State.Missing ->
                Stage("not_installed", "not installed · ${mb(remote.bytes)}$partNote", fetch)
            is Fleet.State.Installed -> Stage("installed", "installed ${remote.versionName}$kept", extra)
            is Fleet.State.Blocked -> Stage("blocked", "not published", extra)
            is Fleet.State.Error -> Stage("error", remote.message, fetch, failedAt = DOWNLOAD)
            null -> when {
                part > 0 -> Stage("update_available", "download interrupted$partNote", fetch)
                e != null -> Stage("installed", "installed${e.record?.versionCode?.let { " v$it" } ?: ""}$kept", extra)
                Fleet.installedId(ctx, app) != null ->
                    Stage("installed", "installed · not checked for an update", emptyList())
                else -> Stage("unknown", "not checked yet", fetch)
            }
        }
    }

    /**
     * #780/#789 May [e] be installed from here? Never when it is already behind
     * the device ([ApkCache.landed]: the installed APK's sha256 IS the cached
     * one, or a newer versionCode is installed). Then the remote decides, by
     * digest — the APK's own sha256 on both channels (the release sidecar; the
     * GHCR blob digest):
     *  - an update is published → only the cache that IS it ("release" is the
     *    no-sidecar placeholder and cannot tell, so it leaves the cache in);
     *  - the device already runs the published build → nothing cached is;
     *  - remote unknown → a newer versionCode is; the SAME versionCode with
     *    other bytes (a constant-versionCode fork) cannot be ordered without
     *    the remote, so it waits for it — [install] asks the download instead.
     */
    private fun actionable(ctx: Context, app: Fleet.App, e: ApkCache.Entry, remote: Fleet.State?): Boolean {
        if (ApkCache.landed(ctx, e, app.pkg)) return false
        val rec = e.record ?: return remote !is Fleet.State.Installed
        return when (remote) {
            is Fleet.State.UpdateAvailable -> remote.remoteDigest12 == "release" ||
                rec.sha256.startsWith(remote.remoteDigest12, ignoreCase = true)
            is Fleet.State.Installed -> false
            else -> installedCode(ctx, app)?.first?.let { it < rec.versionCode } ?: true
        }
    }

    /**
     * Owner rule (#780, held in every path by #784): a cached APK whose bytes
     * ARE the installed APK clears itself — [ApkCache.reapIfInstalled] deletes
     * only on that proof. Returns what is cached afterwards.
     * ponytail: hashes a landed file on each call until it goes; record-less or
     * different-bytes caches stay (Clear offered).
     */
    private fun reapLanded(ctx: Context, app: Fleet.App): ApkCache.Entry? {
        val e = cachedFor(ctx, app) ?: return null
        if (!ApkCache.landed(ctx, e, app.pkg)) return e
        return if (ApkCache.reapIfInstalled(ctx, e.file) is ApkCache.Retention.Reaped) cachedFor(ctx, app) else e
    }

    /** The newest cached APK that [actionable] allows, or null. */
    fun actionableFor(ctx: Context, app: Fleet.App, remote: Fleet.State? = null): ApkCache.Entry? =
        cachedFor(ctx, app)?.takeIf { actionable(ctx, app, it, remote) }

    /** Stage 1. Blocking — call off the main thread. Errors are already in the
     *  note ([Fleet.download] writes it); the returned stage shows them. */
    fun download(ctx: Context, app: Fleet.App): Stage {
        // #1517 A second ask while the engine owns this package's download (a tab switch's updates-first chain, a double tap) JOINS
        // it. It must not run `named`: that would settle this row's job-board entry and end the process-wide job under the running one.
        if (busy[app.pkg] == DOWNLOAD) { StoreEngine.join(ctx, app); return stage(ctx, app) }
        return named(ctx, app, UpdateProgress.STAGE_DOWNLOADING, "") {
            fetch(ctx, app)
            stage(ctx, app)
        }
    }

    /** Stage 1, returning what it fetched: the PUBLISHED build, verified by its
     *  sha256 (cache hit or network), or null when it did not complete. */
    private fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        if (busy.putIfAbsent(app.pkg, DOWNLOAD) != null) return StoreEngine.join(ctx, app)
        return try {
            UpdateProgress.beginDownload()
            // #1517 The process-wide DownloadEngine owns the transfer (queue, Range resume, mirrors, sha256, concurrency cap); this
            // thread only asks and waits, so nothing that happens to a page can cancel or restart it.
            UpdateProgress.stage(UpdateProgress.STAGE_DOWNLOADING)
            StoreEngine.fetch(ctx, app)
        } catch (t: Throwable) {
            Log.w(TAG, "download ${app.id} stopped: ${t.message}")
            null
        } finally {
            busy.remove(app.pkg)
        }
    }

    /**
     * #784 Install = the whole chain, stopping at the FIRST stage that does not
     * complete and leaving the row on it — so the next Install (or the user's
     * Download) takes over from exactly there, never from zero:
     *  1. no installable cached APK ([actionableFor], against [remote] when the
     *     caller has it) → Download (cache hit first, Range-resumable, sha-verified).
     *     #789 What Download returns IS the published build, so it installs
     *     unless the device already runs those bytes — versionCode has no say,
     *     which is what lets a constant-versionCode fork move at all;
     *  2. install that file;
     *  3. once [ApkCache.landed] proves the install, Clear.
     * Blocking — call off the main thread.
     */
    fun install(ctx: Context, app: Fleet.App, remote: Fleet.State? = null): Stage {
        // SharedUidMigration: the old build refuses this one in place; no path retries it.
        if (SharedUidMigration.blocks(ctx, app)) return stage(ctx, app, remote)
        // #1517 A package already being downloaded or installed is owned by that job; asking again joins it.
        if (busy[app.pkg] == DOWNLOAD) { StoreEngine.join(ctx, app); return stage(ctx, app, remote) }
        if (busy[app.pkg] != null) return stage(ctx, app, remote)
        val cached = actionableFor(ctx, app, remote)
        return named(ctx, app, if (cached != null) UpdateProgress.STAGE_VERIFYING else UpdateProgress.STAGE_DOWNLOADING,
            versionOf(remote, cached)) {
            val e = cached
                ?: fetch(ctx, app)?.let { ApkCache.entry(it.file) }?.takeIf { !ApkCache.landed(ctx, it, app.pkg) }
                ?: return@named stage(ctx, app)
            installCached(ctx, app, e)
            if (!e.file.exists()) return@named stage(ctx, app)   // the receiver already reaped it
            if (!ApkCache.landed(ctx, ApkCache.entry(e.file), app.pkg)) return@named stage(ctx, app)
            // #1121 the terminals unpack their bundled files after an update: start that explicitly.
            runCatching { UnpackAfterInstall.run(ctx, app.pkg) }
            clear(ctx, app)
        }
    }

    /** "1.4.2" from the remote, else the cached build's "v42", else unknown. */
    private fun versionOf(remote: Fleet.State?, cached: ApkCache.Entry?): String =
        (remote as? Fleet.State.UpdateAvailable)?.versionName?.takeIf { it.isNotEmpty() }
            ?: cached?.record?.versionCode?.let { "v$it" } ?: ""

    /**
     * #785 Names the app on the Store bar for the length of one verb. A batch
     * has already named it, with its position and what comes next, so a verb
     * inside one only moves the stage; a verb run alone owns a 1-of-1 job and
     * ends it. A verb that stops on a stage publishes WHAT stopped WHERE — the
     * app and the stage ride on [UpdateProgress.State.Failed], so the bar's
     * error line outlives the job that raised it. One that finishes clean
     * clears the bar: left alone it sat on its last "100%" frame, nameless,
     * until something else moved — a pending install sheet is on screen itself.
     * The process-wide job (overlay, /api/store/progress) is still one, last one named
     * wins; the Store page itself draws from the per-package [StoreJobs] board.
     */
    private fun named(ctx: Context, app: Fleet.App, stage: String, version: String, verb: () -> Stage): Stage = BatchForeground.hold(ctx) {
        // Keyed by package: everything the pipeline publishes on this thread is THIS row's
        // and reaches no other row (StoreJobs). The process-wide state still follows, for the overlay.
        StoreJobs.install()
        UpdateProgress.withKey(app.pkg) {
            val mine = UpdateProgress.job?.appId != app.id
            if (mine) UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label, stage, version))
            else UpdateProgress.stage(stage)
            try {
                val s = verb()
                if (s.failedAt != null) UpdateProgress.update(UpdateProgress.State.Failed(s.text, app.id, app.pkg,
                    stage = UpdateProgress.job?.stage ?: stageOf(s.failedAt), app = app.label))
                else if (mine) UpdateProgress.update(UpdateProgress.State.Idle)
                s
            } finally {
                if (mine) UpdateProgress.endJob()
            }
        }
    }

    /** A verb ([DOWNLOAD]…) as the stage word the bar shows. */
    internal fun stageOf(verb: String): String = when (verb) {
        DOWNLOAD -> UpdateProgress.STAGE_DOWNLOADING
        INSTALL -> UpdateProgress.STAGE_INSTALLING
        CLEAR -> UpdateProgress.STAGE_CLEARING
        else -> verb
    }

    /** The unattended name for the same chain — #783's AccountFleet drives the
     *  new-phone migration through it, so it stays as [install]'s alias. */
    fun auto(ctx: Context, app: Fleet.App, remote: Fleet.State? = null): Stage = install(ctx, app, remote)

    /** Stage 2 alone, [e] from the cache only; the outcome lands in the note. */
    private fun installCached(ctx: Context, app: Fleet.App, e: ApkCache.Entry) {
        if (busy.putIfAbsent(app.pkg, INSTALL) != null) return
        try {
            // ONE install at a time (PackageInstaller / pm must not overlap); the rest show "queued".
            StoreJobs.runner.install(app.pkg) { installCachedGated(ctx, app, e) }
        } catch (t: Throwable) {
            ApkCache.note(ctx, app.pkg, ApkCache.STAGE_INSTALL, t.message ?: t.javaClass.simpleName)
        } finally {
            busy.remove(app.pkg)
        }
    }

    private fun installCachedGated(ctx: Context, app: Fleet.App, e: ApkCache.Entry) {
        run {
            UpdateProgress.stage(UpdateProgress.STAGE_VERIFYING)
            // Re-verify before handing bytes to the installer: against the
            // digest recorded at download time, or — record-less — against the
            // published sidecar. A record that no longer matches means the file
            // is known-bad, so it goes; anything unverifiable is KEPT and named.
            val sha = e.record?.sha256
            val v = if (sha != null) VerifiedApk.byDigest(e.file, sha)
                    else Fleet.cachedRelease(ctx, app)
            if (v == null) {
                if (sha != null) ApkCache.drop(e.file)
                ApkCache.note(ctx, app.pkg, ApkCache.STAGE_DOWNLOAD,
                    if (sha != null) "the cached APK no longer matches what was downloaded — Download again"
                    else "the cached APK cannot be verified (no record, sidecar unreachable) — kept")
                return
            }
            ApkCache.clearNote(ctx, app.pkg)
            ApkCache.clearInstallerStatus(ctx, app.pkg)
            UpdateProgress.stage(UpdateProgress.STAGE_INSTALLING)
            val before = installedKey(ctx, app)
            val msg = installer(ctx, app, v)
            if (msg != null) {
                if (ApkCache.noteOf(ctx, app.pkg) == null) ApkCache.note(ctx, app.pkg, ApkCache.STAGE_INSTALL, msg)
            } else if (installedKey(ctx, app) == before) {
                // #858 handed to Android's prompt and not landed yet: watched
                // until it lands, fails, is abandoned or times out.
                StoreInstallWatch.record(ctx, app.pkg, before)
            }
        }
    }

    /** Stage 3. Deletes this app's cached APK(s) and any partial. On demand it
     *  is the user's call; the chain only calls it after [ApkCache.landed]. */
    fun clear(ctx: Context, app: Fleet.App): Stage = named(ctx, app, UpdateProgress.STAGE_CLEARING, "") {
        ApkCache.entries(ctx).filter { e ->
            e.record?.pkg?.let { it in pkgs(app) } ?: e.file.name.startsWith("fleet-${app.id}-")
        }.forEach { ApkCache.drop(it.file) }
        ApkCache.clearNote(ctx, app.pkg)
        stage(ctx, app)
    }

    // ── #784 the batch verbs: Download all, Update all ───────────────────

    /** Per-app results. A dry run reports the verb it WOULD run instead. */
    const val INSTALLED = "installed"
    const val PENDING = "pending"
    const val DOWNLOADED = "downloaded"
    const val CACHED = "cached"
    const val SKIPPED = "skipped"
    const val NEEDS_DOWNLOAD = "needs_download"
    const val NO_ROOM = "no_room"
    const val FAILED = "failed"

    class Outcome(val app: Fleet.App, val result: String, val text: String, val failedAt: String? = null)

    class Batch(
        val op: String, val dryRun: Boolean, val online: Boolean, val outcomes: List<Outcome>,
        /** Bytes the downloads need, and the room there was for them (Download all). */
        val needBytes: Long = 0, val roomBytes: Long = 0,
    ) {
        fun count(result: String) = outcomes.count { it.result == result }
        val summary: String get() = outcomes.groupingBy { it.result }.eachCount().entries
            .joinToString(" · ") { "${it.value} ${it.key.replace('_', ' ')}" }
    }

    /** Is there a network that claims internet? Batch callers pass it in, so a
     *  test (or `/api/store/updateAll?offline=1`) can take the network away. */
    fun isOnline(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /**
     * Bytes Download all may still write — [ApkCache.room]: the device's REAL
     * free storage (StatFs) less the declared reserve, within the cache bound
     * derived from it (#812: it was the fixed 1073 MB bound, which said "no
     * room" with 40 GB free). A seam like [installer] so a test can stand in
     * for a full disk.
     */
    @Volatile
    var room: (Context) -> Long = { c -> ApkCache.room(c) }

    private const val HOST = "the host — its own updater runs after the batch"

    /** #858 the installed build as one comparable value ("" = not installed). */
    internal fun installedKey(ctx: Context, app: Fleet.App): String =
        installedCode(ctx, app)?.let { "${it.first}/${it.second}" } ?: ""

    internal fun installedCode(ctx: Context, app: Fleet.App): Pair<Long, Long>? = pkgs(app).firstNotNullOfOrNull { p ->
        runCatching {
            @Suppress("DEPRECATION") val pi = ctx.packageManager.getPackageInfo(p, 0)
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
             else @Suppress("DEPRECATION") pi.versionCode.toLong()) to pi.lastUpdateTime
        }.getOrNull()
    }

    /**
     * Download every app/lib with an update (or not installed) into the cache,
     * installing nothing — pre-fetch on Wi-Fi, install later (offline if need
     * be). An app that would not fit in [room] is NOT started: filling the disk
     * or the cache bound would only evict what this batch already fetched.
     * [dryRun] checks and sizes everything and downloads nothing. Blocking.
     *
     * #785 Decide first, act second, so the bar's "3 of 12 · next: Chat" counts
     * only what will actually be fetched, not the current apps passed on the way.
     */
    fun downloadAll(ctx: Context, apps: List<Fleet.App>, online: Boolean = isOnline(ctx), dryRun: Boolean = false): Batch =
        BatchForeground.hold(ctx, !dryRun) { downloadAllRun(ctx, apps, online, dryRun) }

    private fun downloadAllRun(ctx: Context, apps: List<Fleet.App>, online: Boolean, dryRun: Boolean): Batch {
        val todo = apps.filter { !it.blocked }
        val startRoom = room(ctx)
        var free = startRoom
        var need = 0L
        val out = ArrayList<Outcome>()
        val go = ArrayList<Pair<Fleet.App, Fleet.State>>()
        if (!dryRun) UpdateProgress.beginDownload()
        for (app in todo) {
            if (app.pkg == ctx.packageName) { out += Outcome(app, SKIPPED, HOST); continue }
            if (!dryRun) reapLanded(ctx, app)
            if (!online) {
                out += if (actionableFor(ctx, app) != null) Outcome(app, CACHED, "already cached")
                       else Outcome(app, NEEDS_DOWNLOAD, "offline — not fetched")
                continue
            }
            val remote = Fleet.status(ctx, app)
            when {
                remote is Fleet.State.Error -> out += Outcome(app, FAILED, remote.message, failedAt = DOWNLOAD)
                remote !is Fleet.State.UpdateAvailable && remote !is Fleet.State.Missing ->
                    out += Outcome(app, SKIPPED, "already current")
                actionableFor(ctx, app, remote) != null -> out += Outcome(app, CACHED, "already cached")
                else -> {
                    val want = (remote.bytes - partialBytes(ctx, app)).coerceAtLeast(0)
                    need += want
                    when {
                        want > free -> out += Outcome(app, NO_ROOM, "needs ${mb(want)}, ${mb(free)} free — not downloaded")
                        else -> {
                            free -= want
                            if (dryRun) out.add(Outcome(app, DOWNLOAD, "would download ${mb(want)}")) else go.add(app to remote)
                        }
                    }
                }
            }
        }
        // Up to 3 downloads at once (the runner's gate); each row is its own keyed job.
        out += inParallel(go) { _, pair ->
            val (app, remote) = pair
            if (UpdateProgress.cancelRequested) return@inParallel Outcome(app, SKIPPED, "cancelled")
            // The plan reserved room for this one; re-read it, since what is
            // really free may have moved under the batch.
            val want = (remote.bytes - partialBytes(ctx, app)).coerceAtLeast(0)
            val now = room(ctx)
            if (want > now) return@inParallel Outcome(app, NO_ROOM, "needs ${mb(want)}, ${mb(now)} free — not downloaded")
            StoreJobs.board.begin(app.pkg, app.label)
            val s = download(ctx, app)
            if (actionableFor(ctx, app) != null) Outcome(app, DOWNLOADED, s.text)
            else Outcome(app, FAILED, s.text, failedAt = s.failedAt ?: DOWNLOAD)
        }
        if (!dryRun) finishBatch(out)
        return Batch("downloadAll", dryRun, online, out, need, startRoom)
    }

    /**
     * Update every app that needs it. Every installable cached APK newer than
     * what is installed goes first and needs NO network; online, the rest get
     * the full [install] chain (updates for every entry, missing ones for libs
     * only — Install all takes missing apps, as [Fleet.Mode.AUTO] does).
     * Sequential: each install may raise the system sheet. [dryRun] reports
     * what would happen and changes nothing. Blocking. Decides first and acts
     * second, as [downloadAll] does (#785).
     *
     * ponytail: no batch lease and no session-headroom cap, same as tapping each
     * row's Install in turn; a refused session reports as failed at install.
     */
    fun updateAll(ctx: Context, apps: List<Fleet.App>, online: Boolean = isOnline(ctx), dryRun: Boolean = false): Batch =
        BatchForeground.hold(ctx, !dryRun) { updateAllRun(ctx, apps, online, dryRun) }

    private fun updateAllRun(ctx: Context, apps: List<Fleet.App>, online: Boolean, dryRun: Boolean): Batch {
        val todo = apps.filter { !it.blocked }
        val out = ArrayList<Outcome>()
        val go = ArrayList<Pair<Fleet.App, Fleet.State?>>()
        if (!dryRun) UpdateProgress.beginDownload()
        for (app in todo) {
            if (app.pkg == ctx.packageName) { out += Outcome(app, SKIPPED, HOST); continue }
            if (!dryRun) reapLanded(ctx, app)
            if (SharedUidMigration.blocks(ctx, app)) {
                out += Outcome(app, SKIPPED, "needs the one-time uid migration — see its row"); continue
            }
            val remote = if (online) Fleet.status(ctx, app) else null
            val act = actionableFor(ctx, app, remote)
            val wanted = act != null || remote is Fleet.State.UpdateAvailable ||
                (remote is Fleet.State.Missing && app.kind == "lib")
            when {
                !wanted -> out += when (remote) {
                    is Fleet.State.Error -> Outcome(app, FAILED, remote.message, failedAt = DOWNLOAD)
                    is Fleet.State.Missing -> Outcome(app, SKIPPED, "not installed — Install all takes missing apps")
                    null -> if (ApkCache.noteOf(ctx, app.pkg)?.stage == ApkCache.STAGE_DOWNLOAD ||
                                partialBytes(ctx, app) > 0 ||
                                (app.kind == "lib" && Fleet.installedId(ctx, app) == null))
                                Outcome(app, NEEDS_DOWNLOAD, "offline — no cached build to install; Download when online")
                            else Outcome(app, SKIPPED, "offline — nothing newer cached (not checked)")
                    else -> Outcome(app, SKIPPED, "already current")
                }
                dryRun -> out += Outcome(app, INSTALL,
                    if (act != null) "would install${act.record?.versionCode?.let { " v$it" } ?: ""} from the cache"
                    else "would download ${mb(remote?.bytes ?: 0L)} and install")
                else -> go += app to remote
            }
        }
        // Downloads overlap (3 at a time) and installs queue behind ONE install slot, so a finished
        // download installs while others still fetch. Every app is its own keyed job.
        out += inParallel(go, threads = 4) { _, pair ->
            val (app, remote) = pair
            if (UpdateProgress.cancelRequested) return@inParallel Outcome(app, SKIPPED, "cancelled")
            StoreJobs.board.begin(app.pkg, app.label)
            val before = installedCode(ctx, app)
            val s = install(ctx, app, remote)
            val after = installedCode(ctx, app)
            when {
                after != null && after != before -> Outcome(app, INSTALLED, s.text)
                s.failedAt != null -> Outcome(app, FAILED, s.text, failedAt = s.failedAt)
                else -> Outcome(app, PENDING, "handed to the installer — confirm it on screen")
            }
        }
        if (!dryRun) finishBatch(out)
        return Batch("updateAll", dryRun, online, out)
    }

    /** Run [f] over [items] on up to [threads] workers; results come back in item order. A throw is that item's alone. */
    private fun <T> inParallel(items: List<T>, threads: Int = 3, f: (Int, T) -> Outcome): List<Outcome> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads.coerceAtMost(items.size).coerceAtLeast(1))
        try {
            val futures = items.mapIndexed { i, item -> pool.submit(java.util.concurrent.Callable { f(i, item) }) }
            return futures.mapIndexed { i, fu ->
                try { fu.get() } catch (e: Exception) {
                    val app = (items[i] as Pair<*, *>).first as Fleet.App
                    Outcome(app, FAILED, e.cause?.message ?: e.message ?: "failed", failedAt = DOWNLOAD)
                }
            }
        } finally { pool.shutdown() }
    }

    /** A batch's next app. The last app's failure is not drawn over this one
     *  (it held the bar until some state happened to change); [finishBatch]
     *  reports every failure when the batch ends. */
    internal fun beginNext(j: UpdateProgress.Job) {
        if (UpdateProgress.state is UpdateProgress.State.Failed) UpdateProgress.update(UpdateProgress.State.Idle)
        UpdateProgress.beginJob(j)
    }

    /**
     * #785 A batch ends on its failures, not on whatever frame the last app
     * left: each app's failure was on the bar only until the next app replaced
     * it, so a 12-app run with one failure in the middle ended looking clean.
     * The first failed app is the one a tap on the bar jumps to; a clean batch
     * clears the bar, as a clean single verb does.
     */
    internal fun finishBatch(out: List<Outcome>) {
        UpdateProgress.endBatch()
        val failed = out.filter { it.result == FAILED }
        val first = failed.firstOrNull() ?: return UpdateProgress.update(UpdateProgress.State.Idle)
        val more = if (failed.size > 1) " (+${failed.size - 1} more failed: " +
            failed.drop(1).joinToString(", ") { it.app.label } + ")" else ""
        UpdateProgress.update(UpdateProgress.State.Failed(first.text + more, first.app.id, first.app.pkg,
            stage = stageOf(first.failedAt ?: DOWNLOAD), app = first.app.label))
    }

    // ── #785 THE progress line ─────────────────────────────────────────────

    /**
     * What the Store bar under the buttons draws and `/api/store/progress`
     * returns: ONE derivation from [UpdateProgress.state] (bytes, %, failure) and
     * [UpdateProgress.job] (which app, which stage, which version, its place in
     * the batch, what is next) — the same two fields the rows' "downloading 42%"
     * reads. Two renderers of one truth cannot disagree about it.
     * [percent] is -1 when there is no honest percentage (size unknown, or a
     * stage that has none). [index]/[count] are 0 outside a batch of the job.
     */
    class Progress(
        val appId: String, val pkg: String, val app: String, val stage: String, val version: String,
        val bytes: Long, val totalBytes: Long, val percent: Int,
        val index: Int, val count: Int, val next: String?,
        val failed: Boolean, val detail: String?,
        /** #804 "Auto ▸ downloading 3 of 12" while the auto chain runs, else null. */
        val phase: String? = null,
    ) {
        val text: String get() = listOfNotNull(
            phase,
            (if (failed) "✗ " else "") + listOf(app, version).filter { it.isNotEmpty() }.joinToString(" ")
                .ifEmpty { if (phase != null) "" else "Update" },
            if (failed) "failed${if (stage.isNotEmpty()) " at $stage" else ""}" else stage,
            if (percent >= 0) "$percent%" else null,
            when {
                totalBytes > 0 -> "${FleetIdentity.human(bytes)} / ${FleetIdentity.human(totalBytes)}"
                bytes > 0 -> "${FleetIdentity.human(bytes)} so far · total size unknown"
                else -> null
            },
            detail,
            if (count > 1) "$index of $count" else null,
            next?.let { "next: $it" },
        ).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    fun progress(state: UpdateProgress.State = UpdateProgress.state, job: UpdateProgress.Job? = UpdateProgress.job): Progress? {
        // #804 the auto chain's phase rides in front of whatever the job says;
        // between two packages (no job) it is the whole line.
        val phase = StoreAuto.label() ?: return progressOf(state, job)
        val p = progressOf(state, job)
            ?: return Progress("", "", "", "", "", 0, 0, -1, 0, 0, null, false, null, phase)
        return Progress(p.appId, p.pkg, p.app, p.stage, p.version, p.bytes, p.totalBytes, p.percent,
            p.index, p.count, p.next, p.failed, p.detail, phase)
    }

    private fun progressOf(state: UpdateProgress.State, job: UpdateProgress.Job?): Progress? {
        fun of(j: UpdateProgress.Job?, stage: String, bytes: Long = 0, total: Long = 0, percent: Int = -1,
               failed: Boolean = false, detail: String? = null, appId: String = "", pkg: String = "", app: String = "") =
            Progress(j?.appId ?: appId, j?.pkg ?: pkg, j?.app ?: app, stage, j?.version.orEmpty(),
                bytes, total, percent, j?.index ?: 0, j?.total ?: 0, j?.next, failed, detail)
        return when (state) {
            is UpdateProgress.State.Downloading ->
                of(job, job?.stage ?: UpdateProgress.STAGE_DOWNLOADING, state.bytes, state.total,
                    if (state.total > 0) state.percent else -1)
            is UpdateProgress.State.CheckingManifest -> of(job, job?.stage ?: "checking")
            is UpdateProgress.State.Installing -> of(job, job?.stage ?: UpdateProgress.STAGE_INSTALLING)
            is UpdateProgress.State.UpdateAvailable -> of(job, "update available", detail = mb(state.totalBytes))
            is UpdateProgress.State.Waiting -> of(job, "waiting", detail = state.reason)
            // The job only describes a failure it raised: a failure about another
            // app (the batch moved on, or a late install receiver) names itself.
            is UpdateProgress.State.Failed -> {
                val j = job?.takeIf { it.appId.isNotEmpty() && it.appId == state.appId }
                of(j, state.stage.ifEmpty { j?.stage.orEmpty() }, failed = true, detail = state.message,
                    appId = state.appId, pkg = state.pkg, app = state.app.ifEmpty { state.appId })
            }
            is UpdateProgress.State.Cancelled -> null
            // Idle / Done: only the gap between two apps of a batch is shown;
            // a single row's finished job is nothing left to watch.
            else -> job?.takeIf { it.total > 1 }?.let { of(it, it.stage) }
        }
    }
}
