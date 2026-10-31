package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.clone
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitRemoteInfo
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript
import com.diegonmarcos.clouddrive.sync.inventory.RepoInventory
import com.diegonmarcos.clouddrive.sync.jobs.GitJobs
import com.diegonmarcos.clouddrive.sync.store.StoreShell
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #579 the chrome's reading of the git engine for the Sync ▸ Git cards: the managed
 * repositories (the SAME registry file the engine screen and the worker write), a live
 * glance per repository (branch, upstream, ahead/behind, dirty, conflicts, state), the
 * stepped one-tap sync, and the history. App → lib only: it calls GitEngine and never
 * re-implements a verb.
 */
class GitSyncCoordinator(private val ctx: Context, private val scope: CoroutineScope) {

    private val registry = RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE))
    private val credentials = GitCredentialStore(ctx)
    /** #1315 the ONE runner of store scripts and the ONE host<->guest translation (sync/store). */
    private val shell: StoreShell = StoreShell.of(ctx)
    /** #1315 the ONE executor of every git and folder job (sync/jobs): bounded, locked per path, exclusive, cancellable, accounted. */
    val jobs: GitJobs = GitJobs(scope)

    init {
        engineContext = ctx.applicationContext
        // #1358 know the git engine's All files access before the first row is probed.
        scope.launch(Dispatchers.IO) { runCatching { EngineGrant.probe(ctx.applicationContext) } }
    }

    /**
     * #669 THE ONE PLACE A REPOSITORY'S AUTH IS ASSEMBLED for this coordinator's
     * engine calls. An [AUTH_SESSION] repository was listed AND cloned on the fleet
     * session, which lives in PROCESS MEMORY ONLY ([FleetSession]) — the credential
     * store cannot hold it (test-drive-configs-sign-in: the drive host stores no
     * bearer), so it is attached here, on the DECLARED header, every time the
     * engine touches that repository's transport. Everything else is the store's:
     * one store, one id, unchanged.
     */
    private fun authFor(repo: ManagedRepo, token: () -> String = { declaredToken() }): com.diegonmarcos.cloudlib.gitsync.GitAuth =
        pickAuth(
            kind = repo.authKind,
            stored = { credentials.authFor(repo) },
            session = { com.diegonmarcos.cloudlib.gitsync.GitAuth.Session(FleetGit.sessionHeader(), FleetSession.cookie) },
            declaredToken = token,
            owner = Declarations.sync.git.owner,
            declaredHost = hostOf(repo.remoteUrl) == hostOf(Declarations.sync.git.cloneUrl(repo.name)),
        )

    /**
     * #850/#818 THE ONE DECLARED GIT CREDENTIAL, the same path StoreSeed clones with: the
     * vault-delivered token under the one declared id, then the declared chain. A seeded
     * repository is registered with no credential of its own, so without this a pull of a
     * private seeded clone would ride no auth at all. Blocking — call off the main thread.
     */
    private fun declaredToken(): String =
        com.diegonmarcos.clouddrive.configs.DriveAuthApply.vaultGitToken(ctx)
            .ifBlank { runCatching { com.diegonmarcos.clouddrive.configs.DriveGitChain.resolve(ctx).token.orEmpty() }.getOrDefault("") }
    private val statusCache = StatusCache(File(ctx.filesDir, StatusCache.FILE))
    val history = SyncHistory(File(ctx.filesDir, SyncHistory.FILE))

    data class Glance(
        val branch: String? = null,
        val upstream: String? = null,
        val ahead: Int = 0,
        val behind: Int = 0,
        val changed: Int = 0,
        val conflicts: Int = 0,
        val repositoryState: String = "SAFE",
        val error: String? = null,
        val gone: Boolean = false,
        val read: Boolean = false,
        /** Size guard: the worktree was NOT scanned (too large for the unattended pass); branch / upstream are real, counts are not. */
        val large: Boolean = false,
        /** "light" = refs only (the working tree was NOT looked at), "full" = worktree scanned; "" = not read. */
        val level: String = "",
        /** #850 the reader's terminal word: clean / ahead / behind / dirty / not_cloned / error. */
        val state: String = "",
        /** #850 when this glance was read (epoch ms) — the cache's timestamp. */
        val readAtMs: Long = 0,
        /** Untracked files among [changed] (the rest are modified / added / deleted). */
        val untracked: Int = 0,
        /** When the remote was last fetched (epoch ms, the clone's FETCH_HEAD time); 0 = never / not known. */
        val fetchedAtMs: Long = 0,
        /** The exact directory the reader read, how it read it ("files" / "exec" / "engine") and how long it took (debug line). */
        val path: String = "",
        val via: String = "",
        val tookMs: Long = 0,
    )

    enum class Step { STAGING, COMMITTING, PULLING, PUSHING }

    data class Running(val repoId: String, val step: Step)

    val repos = MutableStateFlow<List<ManagedRepo>>(emptyList())
    val glances = MutableStateFlow<Map<String, Glance>>(emptyMap())
    val running = MutableStateFlow<Map<String, Running>>(emptyMap())
    val events = MutableStateFlow<List<SyncEvent>>(emptyList())

    init {
        // #850 the glances ARE the one reader's statuses, entry by entry as each ends — a
        // slow repository no longer holds every other row on "reading…".
        // The persisted cache first (file read on IO): the table renders from it at once, no check runs by itself.
        // #1259 THE CACHE IS READ BEFORE THE FIRST RENDER: the constructor (built off the main thread, MainActivity's
        // LaunchedEffect on IO) loads the persisted statuses AND the clone registry synchronously, so a re-created
        // coordinator (activity re-created after minimize / restart) hands the table its last rows and icons on its first
        // frame. They used to load in a coroutine, so the first frames had NO registry (every row "not cloned" = "?") and
        // no glances. Keys are re-canonicalised against the current registry (a rename between two runs still matches).
        val cached = runCatching { statusCache.load() }.getOrDefault(emptyMap())
        statusReader.seed(rekey(cached) { Declarations.repoSot.canonical(it) })
        glances.value = statusReader.statuses.value.mapValues { (_, st) -> glanceOf(st) }
        runCatching { registry.load() }.getOrNull()?.let { repos.value = it }
        runCatching { history.load() }.getOrNull()?.let { events.value = it }
        scope.launch {
            statusReader.statuses.collect { m ->
                glances.value = m.mapValues { (_, s) -> glanceOf(s) }
                withContext(Dispatchers.IO) { runCatching { statusCache.save(m) } }
            }
        }
    }

    /**
     * Re-load the registry and ASK the one status reader for every repository. Cheap and
     * idempotent: a repository already being read, or read less than the declared ttl ago,
     * starts nothing ([force] re-reads the fresh ones too). Returns once the registry is
     * loaded and the reads are requested, with the job that ends when they have all ended.
     */
    fun refresh(force: Boolean = false): kotlinx.coroutines.Job = scope.launch {
        val list = loadRegistry()
        statusReader.request(list.map { GitStatusReader.Target(statusKey(it), File(it.path)) }, force).join()
    }

    /** #1195 the registry and the history only (a small file read on IO, no git status, no walk, no network): what a page open always does. */
    private suspend fun loadRegistry(): List<ManagedRepo> {
        val list = withContext(Dispatchers.IO) { registry.load() }
        repos.value = list
        events.value = withContext(Dispatchers.IO) { history.load() }
        return list
    }

    /** After an operation changed [repo]'s tree: its cached status is stale, re-read it. */
    private fun reread(repo: ManagedRepo) { statusReader.invalidate(statusKey(repo)); refresh() }

    // ── #850 Force pull (all / one) and Auto pull on open ─────────────────────

    /** The bulk pull in flight or last finished: progress and each repository's outcome. */
    data class BulkPull(
        val force: Boolean,
        val trigger: String,
        val total: Int,
        val done: Int = 0,
        val results: Map<String, GitOpResult> = emptyMap(),
    ) {
        val finished: Boolean get() = done >= total
        val failed: Int get() = results.count { !it.value.ok }
    }

    val bulk = MutableStateFlow<BulkPull?>(null)

    /**
     * Pull every registered clone — [force] = the engine's forcePull (fetch + reset to
     * upstream, the declared destructive op), otherwise a plain pull. Per repository, at
     * most the declared status concurrency at once, each on the ONE credential path; the
     * declared token is resolved ONCE for the whole pass. Each outcome lands in [opResults],
     * the history and the debug log exactly as a single runOp's does.
     */
    fun pullAll(force: Boolean, trigger: String = SyncHistory.TRIGGER_MANUAL, only: Set<String>? = null): kotlinx.coroutines.Job? {
        val opId = if (force) OP_FORCE_PULL else OP_PULL
        val probe = GitJobs.Spec(opId, opId, opId, emptyList()) { GitJobs.Outcome(GitJobs.Status.SKIP, "") }
        if (jobs.refusal(probe) != null) return null
        bulk.value = BulkPull(force, trigger, total = Int.MAX_VALUE)
        return scope.launch {
            sweepStalePacks(ownBulk = true)?.join()
            val list = withContext(Dispatchers.IO) {
                registry.load().filter { only == null || it.name in only || it.id in only }
                    .filter { GitEngine.isRepository(File(it.path)) }
            }
            bulk.value = BulkPull(force, trigger, list.size)
            val token = lazy { declaredToken() }
            val byPath = list.associateBy { File(it.path).absolutePath }
            // #1315 ONE executor: bounded by the declared concurrency, one job per path, exclusive with every other writing job.
            val job = jobs.submit(GitJobs.Spec(opId, opId, opId, list.map { GitJobs.Target(File(it.path).absolutePath, it.name) }, workers = Declarations.sync.git.status.concurrency, exclusive = true,
                onEnd = {
                    DriveDebugLog.i(ctx, TAG, "pull-all ${opId} ($trigger): ${list.size} repos, ${bulk.value?.failed ?: 0} failed")
                    refresh().join()
                }) { t ->
                val repo = byPath.getValue(t.path)
                if (running.value.containsKey(repo.id)) {
                    bulk.update { b -> b?.copy(done = b.done + 1, results = b.results + (repo.name to GitOpResult(false, "skipped: an operation is already running"))) }
                    return@Spec GitJobs.Outcome(GitJobs.Status.SKIP, GitJobs.LOCKED)
                }
                running.update { it + (repo.id to Running(repo.id, Step.PULLING)) }
                val result = try { withContext(Dispatchers.IO) { perform(repo, opId, "", { token.value }, trigger) } } finally { running.update { it - repo.id } }
                opResults.update { it + (repo.id to result) }
                statusReader.invalidate(statusKey(repo))
                bulk.update { b -> b?.copy(done = b.done + 1, results = b.results + (repo.name to result)) }
                GitJobs.Outcome(if (result.ok) GitJobs.Status.OK else GitJobs.Status.FAIL, result.summary, result.details)
            })
            if (job == null) bulk.value = BulkPull(force, trigger, 0) else job.join()
        }
    }

    /** A pull-all job is running (#1315 read off the one executor). */
    private fun bulkRunning(): Boolean = jobs.latest(OP_PULL, OP_FORCE_PULL)?.finished == false

    // ── #1313 stale temp packs (ui.sync.git.stale_pack_cleanup): StalePackCleaner ──

    private val staleSweeping = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun gitJobRunning(ownBulk: Boolean): Boolean =
        StalePackCleaner.busy(running.value.size, cloning.value.size, depthRun.value?.finished == false, !ownBulk && bulkRunning())

    /**
     * Delete the abandoned incoming_* / tmp_pack* files of every clone and bare mirror (declared patterns, older than the
     * declared age, never pack-*), on IO, never while a git job runs ([ownBulk] = called by the bulk pull itself, whose own
     * flag does not count). One line in Activity Logs when something was deleted, nothing otherwise. Returns null when it did not start.
     */
    fun sweepStalePacks(ownBulk: Boolean = false): kotlinx.coroutines.Job? {
        val d = Declarations.sync.git.stalePackCleanup
        if (!d.enabled || !staleSweeping.compareAndSet(false, true)) return null
        return scope.launch(Dispatchers.IO) {
            try {
                if (gitJobRunning(ownBulk)) return@launch
                val git = SharedStore.gitRoot()
                val r = (if (StoreGit.useExec(ctx)) StoreGit.sweepStalePacks(ctx, git, Declarations.sync.git.bareRoot, d.patterns, d.minAgeHours)
                    else StalePackCleaner.sweep(StalePackCleaner.packDirs(git, Declarations.sync.git.bareRoot), d.patterns, d.minAgeHours, System.currentTimeMillis(), false))
                    ?: return@launch
                val line = StalePackCleaner.message(r)
                if (line.isNotEmpty()) {
                    DriveDebugLog.i(ctx, TAG, line)
                    events.value = history.append(SyncEvent(System.currentTimeMillis() / 1000, "stale-packs", "stale packs", SyncHistory.TRIGGER_SCHEDULED, true, "stale_pack_cleanup: $line"))
                }
            } catch (e: Exception) {
                DriveDebugLog.e(ctx, TAG, "stale pack cleanup failed: ${e.message}")
            } finally { staleSweeping.set(false) }
        }
    }

    private val pagePrefs by lazy { ctx.getSharedPreferences(PREFS_PAGE, Context.MODE_PRIVATE) }

    /** #850 the persisted "Auto pull on open" toggle; before the owner touches it, the declared default. */
    val autoPullOnOpen = MutableStateFlow(false).also { f ->
        scope.launch(Dispatchers.IO) { f.value = pagePrefs.getBoolean(KEY_AUTO_PULL, Declarations.sync.git.autoPull.defaultOn) }
    }

    fun setAutoPullOnOpen(on: Boolean) {
        autoPullOnOpen.value = on
        scope.launch(Dispatchers.IO) { pagePrefs.edit().putBoolean(KEY_AUTO_PULL, on).apply() }
    }

    /** What the last page-open did about auto pull, in words the page shows ("" = nothing to say). */
    val autoPullNote = MutableStateFlow("")

    /** Whether the active network is unmetered (Wi-Fi / ethernet) — the declared constraint. */
    private fun unmetered(): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /**
     * #850 / #1195 the page opened. The table renders from what is cached: this loads the registry, the persisted
     * size cache and the cached local status read. Status reads, fetches, pulls and size walks run only when the page's Settings
     * ask for them ([AutoChecks.plan], all off by default); a plain pull additionally keeps its declared Wi-Fi-only
     * constraint. Called from the page's LaunchedEffect(Unit): once per entry, never per recomposition.
     */
    fun onPageOpened() {
        refreshRegistry()
        refreshBare()
        scope.launch {
            val list = loadRegistry()
            // #1256 the one exception: an EMPTY last-commit cache (first open) is filled once from the local HEADs.
            if (LastCommits.needsFirstFill(withContext(Dispatchers.IO) { decodeTimes(pagePrefs.getString(KEY_LAST_COMMITS, null)) }, list.size)) refreshLastCommits()
            // NO status work here: the table shows the persisted cache; a check runs only on an explicit owner action (pull-to-refresh, Light / Full check all, the status window).
            runPlan(AutoChecks.plan(autoSettings(), AutoChecks.Trigger.OPEN))
        }
    }

    /** Bumped when a newer registry than the baked snapshot was applied (Declarations.liveSot): the table recomposes on it. */
    val sotRev = MutableStateFlow(0)

    /** Table open: apply the cached live registry at once, then ask the declared url (conditional GET, at most every refresh_minutes) and apply a newer one. Off the main thread. */
    fun refreshRegistry() {
        scope.launch(Dispatchers.IO) {
            fun apply(text: String?) {
                val d = text?.let { runCatching { Declarations.parseRepoSot(it) }.getOrNull() } ?: return
                if (d.repos.isNotEmpty()) { Declarations.liveSot = d; sotRev.value = sotRev.value + 1 }
            }
            apply(RepoSotLive.cached(ctx.filesDir))
            val o = runCatching { RepoSotLive.refresh(ctx.filesDir, BuildConfig.REPOS_SOT_URL, RepoSotLive.http, BuildConfig.REPOS_SOT_REFRESH_MIN * 60_000L, System.currentTimeMillis()) }.getOrNull()
            if (o == RepoSotLive.Outcome.UPDATED) apply(RepoSotLive.cached(ctx.filesDir))
        }
    }

    /** The periodic tick of an open page (the page schedules it only when an interval is set). */
    fun onTick() { scope.launch { runPlan(AutoChecks.plan(autoSettings(), AutoChecks.Trigger.TICK)) } }

    /** The Git page's auto-behaviour settings as persisted in cloud-drive-prefs (DrivePrefs writes the same file); all off when nothing is stored. */
    private suspend fun autoSettings(): AutoChecks.Settings = withContext(Dispatchers.IO) { AutoChecks.Settings(AutoChecks.Page.GIT, pagePrefs.all) }

    private suspend fun runPlan(work: Set<AutoChecks.Work>) {
        if (AutoChecks.Work.READ_STATUS in work) refresh()
        if (AutoChecks.Work.FETCH_ALL in work) fetchAll()
        if (AutoChecks.Work.REFRESH_SIZES in work) refreshSizes()
        if (AutoChecks.Work.PULL_ALL in work) {
            val on = withContext(Dispatchers.IO) { pagePrefs.getBoolean(KEY_AUTO_PULL, Declarations.sync.git.autoPull.defaultOn) }
            autoPullOnOpen.value = on
            autoPullNote.value = when (val d = autoPullDecision(on, Declarations.sync.git.autoPull.requireUnmetered, unmetered(), bulkRunning())) {
                AUTO_PULL_GO -> { pullAll(force = false, trigger = TRIGGER_OPEN); "" }
                else -> d
            }
        }
    }

    /** The owner asked for ONE clone's working-tree status (status window): the size guard does not apply, one repository, forced. */
    fun checkWorktree(repo: ManagedRepo) { statusReader.request(listOf(GitStatusReader.Target(statusKey(repo), File(repo.path), onDemand = true)), force = true) }

    /** Progress of the manual FULL check: done of total repositories, null = none running. */
    data class FullProgress(val done: Int, val total: Int)
    val fullProgress = MutableStateFlow<FullProgress?>(null)

    /** "Light check all": refs only, forced, one worker; this is also what runs on open. */
    /** #1356 the Check of a page on another storage (#1376): the same status reads, over that root's repositories. */
    fun checkRepos(list: List<ManagedRepo>, full: Boolean) {
        statusReader.request(list.map { GitStatusReader.Target(statusKey(it), File(it.path)) }, force = true, level = if (full) GitStatusReader.Level.FULL else GitStatusReader.Level.LIGHT)
    }

    fun lightCheckAll() { scope.launch { val list = loadRegistry(); statusReader.request(list.map { GitStatusReader.Target(statusKey(it), File(it.path)) }, force = true, level = GitStatusReader.Level.LIGHT) }; scanUnlisted(); refreshLastCommits() }

    /** "Full check all": MANUAL only. Adds the dirty / worktree scan, one repository at a time with the per-repo timeout; the size guard marks huge / LFS repositories LARGE (each is confirmed individually in its status window). Progress is published; [cancelStatusReads] stops it. */
    fun fullCheckAll() {
        if (fullProgress.value != null) return
        scanUnlisted(GitStatusReader.Level.FULL); refreshLastCommits()
        scope.launch {
            val list = loadRegistry()
            val started = System.currentTimeMillis()
            fullProgress.value = FullProgress(0, list.size)
            val job = statusReader.request(list.map { GitStatusReader.Target(statusKey(it), File(it.path)) }, force = true, level = GitStatusReader.Level.FULL)
            while (job.isActive) {
                val st = statusReader.statuses.value
                fullProgress.value = FullProgress(list.count { st[statusKey(it)]?.let { s -> s.terminal && s.level == GitStatusReader.Level.FULL && s.readAtMs >= started } == true }, list.size)
                kotlinx.coroutines.delay(300)
            }
            fullProgress.value = null
        }
    }

    /** The Git page closed: stop the reads still queued. */
    fun cancelStatusReads() { statusReader.cancelAll(); fullProgress.value = null }

    /** Fetch every clone (the declared fetch op, one running op per repository). */
    /** Fetch every clone of [only] (default: every registered clone): ONE bounded job, one op per repository. */
    fun fetchAll(only: List<ManagedRepo>? = null) { bulkOps(OP_FETCH, only ?: repos.value) { repo -> perform(repo, OP_FETCH, "", { declaredToken() }, SyncHistory.TRIGGER_MANUAL) } }

    // ── #1195 size on disk: walked in the background, cached by fingerprint, shown from the cache ──

    val diskSizes = MutableStateFlow<Map<String, RepoDiskSize.Entry>>(emptyMap())
        .also { f -> scope.launch(Dispatchers.IO) { f.value = RepoDiskSize.load(pagePrefs) } }
    val refreshingSizes = MutableStateFlow(false)

    /**
     * Bring the size cache up to date, incrementally: a clone whose fingerprint (path + HEAD / index / FETCH_HEAD /
     * folder mtimes) is unchanged is not walked again; [force] walks every clone. One clone at a time, on IO.
     */
    fun refreshSizes(force: Boolean = false) {
        if (!refreshingSizes.compareAndSet(false, true)) return
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    val dirs = registry.load().map { File(it.path) }
                    val route = shell.route
                    // #1315 a store clone is invisible to java.io through cld.termux: its size comes from ONE du script on the shell.
                    val (viaShell, viaIo) = if (route.viaTermux) dirs.partition { route.inStore(it.absolutePath) && !it.isDirectory } else emptyList<File>() to dirs
                    val out = LinkedHashMap(RepoDiskSize.refresh(diskSizes.value, viaIo, force))
                    if (viaShell.isNotEmpty() && (force || viaShell.any { diskSizes.value[it.absolutePath] == null })) {
                        val sizes = InventoryScript.parseSizes(shell.run(InventoryScript.sizesScript(viaShell.mapNotNull { route.guest(it.absolutePath) }) { StoreShell.q(it) }, timeoutMs = 110_000).out)
                        viaShell.forEach { d -> (route.guest(d.absolutePath)?.let { sizes[it] })?.let { out[d.absolutePath] = RepoDiskSize.Entry("shell", it) } ?: diskSizes.value[d.absolutePath]?.let { out[d.absolutePath] = it } }
                    } else viaShell.forEach { d -> diskSizes.value[d.absolutePath]?.let { out[d.absolutePath] = it } }
                    RepoDiskSize.save(pagePrefs, out)
                    out
                }
                diskSizes.value = next
            } finally { refreshingSizes.value = false }
        }
    }

    // #1289 every save re-applies the ONE periodic worker, so a repository's AutoSync period is what it ticks at.
    fun save(repo: ManagedRepo) { scope.launch { withContext(Dispatchers.IO) { registry.upsert(repo); runCatching { com.diegonmarcos.clouddrive.GitSyncWorker.schedule(ctx) } }; refresh() } }

    /** #1289 Configs-AutoSync: On/Off and every [minutes] (floored at WorkManager's 15), stored on the repository. */
    fun setAutoSync(repo: ManagedRepo, on: Boolean, minutes: Long) {
        val (enabled, period) = AutoSyncPlan.toggled(on, minutes, com.diegonmarcos.clouddrive.Declarations.sync.git.repoMenu.autoSync.defaultMinutes)
        save(repo.copy(autoSync = enabled, syncIntervalMinutes = period))
    }

    /** #1289 Workflow: the working tree's status (List Dirty / List Conflicts / Solve Conflicts read it), off the main thread. */
    suspend fun workTree(repo: ManagedRepo): Result<com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot> = withContext(Dispatchers.IO) {
        runCatching { GitEngine(ctx, File(repo.path)).use { it.status() } }
    }

    /**
     * #1289 Workflow > Solve Conflicts: run a [RepoWorkflow.Plan] the owner confirmed. Each file is resolved (ours / theirs,
     * staged) or staged as edited, then the merge or cherry-pick is committed or the rebase continued. NEVER a push.
     */
    fun solveConflicts(repo: ManagedRepo, plan: RepoWorkflow.Plan) {
        if (!plan.ready || running.value.containsKey(repo.id)) return
        running.update { it + (repo.id to Running(repo.id, Step.COMMITTING)) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val done = runCatching {
                    val dir = File(repo.path)
                    val lines = mutableListOf<String>()
                    GitEngine(ctx, dir).use { e ->
                        for (step in plan.steps) when (step) {
                            is RepoWorkflow.Step.Resolve -> { e.resolve(step.path, step.side); lines += "kept ${step.side.name.lowercase()}: ${step.path}" }
                            is RepoWorkflow.Step.Stage -> { e.markResolved(step.path); lines += "staged as edited: ${step.path}" }
                            is RepoWorkflow.Step.Continue -> when (step.what) {
                                RepoWorkflow.InProgress.REBASE -> {
                                    val r = RepoCompound.raw(ctx, dir, "rebase_continue", org.json.JSONObject().put("name", repo.authorName.ifBlank { DEFAULT_AUTHOR }).put("email", repo.authorEmail.ifBlank { DEFAULT_EMAIL }), authFor(repo))
                                    if (!r.ok) return@runCatching GitOpResult(false, "solve conflicts: files staged, rebase --continue refused", (lines + r.details.ifBlank { r.summary }).joinToString("\n"))
                                    lines += "rebase continued"
                                }
                                RepoWorkflow.InProgress.NONE -> Unit
                                else -> { val c = e.commit("Resolve conflicts", repo.authorName.ifBlank { DEFAULT_AUTHOR }, repo.authorEmail.ifBlank { DEFAULT_EMAIL }); lines += "committed ${c.shortSha}" }
                            }
                        }
                    }
                    GitOpResult(true, "solve conflicts: ${plan.steps.count { it !is RepoWorkflow.Step.Continue }} file(s) resolved, not pushed", lines.joinToString("\n"))
                }.getOrElse { GitOpResult(false, "solve conflicts failed: " + (it.message ?: it.toString())) }
                record(repo, RepoActions.SOLVE_CONFLICTS, done, SyncHistory.TRIGGER_MANUAL)
            }
            opResults.update { it + (repo.id to result) }
            running.update { it - repo.id }
            reread(repo)
        }
    }

    fun remove(repo: ManagedRepo) { scope.launch { withContext(Dispatchers.IO) { registry.remove(repo.id); credentials.clear(repo.id) }; refresh() } }

    fun setSecret(repo: ManagedRepo, secret: String) { credentials.setSecret(repo.id, secret) }
    fun hasSecret(repo: ManagedRepo): Boolean = credentials.hasSecret(repo.id)

    /**
     * GitSync's one-tap sync with the step the card shows: stage → commit → pull → push.
     * The engine's own sync() is one call; the steps are re-run here through the same
     * verbs so the card can name the step in flight. The outcome lands in the history
     * and the registry, and the card re-reads its glance.
     */
    fun syncNow(repo: ManagedRepo) = single(repo, RepoActions.SYNC_NOW, Step.STAGING) {
        val result = runCatching {
            GitEngine(ctx, File(repo.path)).use { e ->
                val steps = mutableListOf<String>()
                e.stageAll()
                running.update { it + (repo.id to Running(repo.id, Step.COMMITTING)) }
                if (e.status().staged.isNotEmpty()) {
                    val c = e.commit(repo.syncMessage, repo.authorName.ifBlank { DEFAULT_AUTHOR }, repo.authorEmail.ifBlank { DEFAULT_EMAIL })
                    steps += "committed ${c.shortSha}"
                } else steps += "nothing to commit"
                if (e.remotes().none { it.name == "origin" }) return@use GitOpResult(true, steps.joinToString(", ") + ", no remote 'origin'")
                running.update { it + (repo.id to Running(repo.id, Step.PULLING)) }
                val pulled = e.pull(rebase = repo.pullRebase, auth = authFor(repo))
                steps += pulled.summary
                if (!pulled.ok) return@use GitOpResult(false, steps.joinToString(", "), pulled.details)
                running.update { it + (repo.id to Running(repo.id, Step.PUSHING)) }
                val pushed = e.push(auth = authFor(repo))
                steps += pushed.summary
                GitOpResult(pushed.ok, steps.joinToString(", "), pushed.details)
            }
        }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
        val now = System.currentTimeMillis() / 1000
        registry.upsert(repo.copy(lastSyncEpochSeconds = now, lastSyncSummary = result.summary))
        history.append(SyncEvent(now, repo.id, repo.name, SyncHistory.TRIGGER_MANUAL, result.ok, result.summary, result.details))
        // On-device debug log: the SAME sentence the history holds — the engine's own summary, never a credential.
        if (result.ok) DriveDebugLog.i(ctx, TAG, "sync ${repo.name}: ${result.summary}")
        else DriveDebugLog.e(ctx, TAG, "sync ${repo.name} FAILED: ${result.summary} ${result.details}".trim())
        result
    }

    // ── #608 the Sync ▸ Git page's per-repository operations ────────────────

    /**
     * Everything the row's disclosure shows about ONE clone, read in one pass when it is
     * expanded: the remotes and branches and commits from [GitEngine], the hooks, the
     * workflows and the size from [GitRepoScan]. Loaded lazily on purpose — walking every
     * clone of a long list on every recomposition is how a dense page becomes unusable.
     */
    data class Details(
        val loading: Boolean = true,
        val remotes: List<GitRemoteInfo> = emptyList(),
        val branches: List<GitBranchInfo> = emptyList(),
        val commits: List<GitCommitInfo> = emptyList(),
        val hooks: List<String> = emptyList(),
        val workflows: List<String> = emptyList(),
        val size: GitRepoScan.Size = GitRepoScan.Size(0, 0, 0, 0),
        val error: String? = null,
    )

    val details = MutableStateFlow<Map<String, Details>>(emptyMap())

    /** The last outcome of an operation, per repository, in the engine's own words. */
    val opResults = MutableStateFlow<Map<String, GitOpResult>>(emptyMap())

    /** A clone in flight, keyed by the repository name the store will hold it under. */
    val cloning = MutableStateFlow<Set<String>>(emptySet())

    /** The commits not pushed: the newest [ahead] of the branch's log (RepoStatus.unpushed), read on demand off the main thread. */
    suspend fun unpushedCommits(repo: ManagedRepo, ahead: Int): Result<List<GitCommitInfo>> = withContext(Dispatchers.IO) {
        runCatching { RepoStatus.unpushed(GitEngine(ctx, File(repo.path)).use { it.log(max = ahead.coerceAtLeast(1)) }, ahead) }
    }

    fun loadDetails(repo: ManagedRepo, historyMax: Int) {
        details.update { it + (repo.id to Details(loading = true)) }
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                val dir = File(repo.path)
                if (!dir.isDirectory) return@withContext Details(loading = false, error = "gone")
                runCatching {
                    GitEngine(ctx, dir).use { e ->
                        Details(
                            loading = false,
                            remotes = e.remotes(),
                            branches = e.branches(),
                            commits = e.log(max = historyMax),
                            hooks = GitRepoScan.hooks(dir),
                            workflows = GitRepoScan.workflows(dir),
                            size = GitRepoScan.size(dir),
                        )
                    }
                }.getOrElse { Details(loading = false, error = it.message ?: it.toString()) }
            }
            details.update { it + (repo.id to read) }
        }
    }

    /**
     * ONE declared operation on ONE repository, every verb the engine's own (#608 added
     * forcePush/forcePull to it for exactly this page). The outcome lands in [opResults]
     * and in the shared history — a force push that rewrote a remote is not something the
     * page should be able to forget — and the glance is re-read afterwards.
     *
     * The two destructive ids are NOT special-cased here: the row confirms before it calls.
     */
    fun runOp(repo: ManagedRepo, opId: String, message: String = "") {
        val step = when (opId) {
            OP_COMMIT -> Step.COMMITTING
            OP_PULL, OP_FORCE_PULL, OP_FETCH -> Step.PULLING
            else -> Step.PUSHING
        }
        single(repo, opId, step) { perform(repo, opId, message, { declaredToken() }, SyncHistory.TRIGGER_MANUAL) }
    }

    /** ONE operation on ONE repository, recorded — the shared body of [runOp] and [pullAll]. Blocking. */
    private fun perform(repo: ManagedRepo, opId: String, message: String, token: () -> String, trigger: String): GitOpResult {
            val result = run {
                runCatching {
                    GitEngine(ctx, File(repo.path)).use { e ->
                        val auth = authFor(repo, token)
                        when (opId) {
                            OP_FETCH -> e.fetch(auth = auth)
                            OP_PULL -> e.pull(rebase = repo.pullRebase, auth = auth)
                            OP_COMMIT -> {
                                e.stageAll()
                                if (e.status().staged.isEmpty()) GitOpResult(true, "nothing to commit")
                                else {
                                    val c = e.commit(
                                        message.ifBlank { repo.syncMessage },
                                        repo.authorName.ifBlank { DEFAULT_AUTHOR },
                                        repo.authorEmail.ifBlank { DEFAULT_EMAIL },
                                    )
                                    GitOpResult(true, "committed ${c.shortSha}")
                                }
                            }
                            OP_PUSH -> e.push(auth = auth)
                            OP_FORCE_PUSH -> e.forcePush(auth = auth)
                            OP_FORCE_PULL -> e.forcePull(auth = auth)
                            else -> GitOpResult(false, "no such operation: $opId")
                        }
                    }
                }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            }
            return record(repo, opId, result, trigger)
    }

    /** The registry line, the shared history and the debug log for one finished operation; returns [result]. */
    private fun record(repo: ManagedRepo, opId: String, result: GitOpResult, trigger: String): GitOpResult {
            val now = System.currentTimeMillis() / 1000
            runCatching {
                registry.upsert(repo.copy(lastSyncEpochSeconds = now, lastSyncSummary = "$opId: ${result.summary}"))
                history.append(SyncEvent(now, repo.id, repo.name, trigger, result.ok, "$opId: ${result.summary}", result.details))
            }
            if (result.ok) DriveDebugLog.i(ctx, TAG, "$opId ${repo.name}: ${result.summary}")
            else DriveDebugLog.e(ctx, TAG, "$opId ${repo.name} FAILED: ${result.summary} ${result.details}".trim())
            return result
    }

    /**
     * #1138 a compound operation of the repository detail: the three Sync verbs and Repo clean
     * ([RepoActions.isCompound]), composed in [RepoCompound] on the transport that serves the clone
     * (git inside cld.termux for the shared store). The confirmation for Repo clean is the page's.
     */
    fun runCompound(repo: ManagedRepo, id: String) {
        if (!RepoActions.isCompound(id)) return
        single(repo, id, if (id == RepoActions.SYNC_LOCAL_WINS) Step.PUSHING else Step.PULLING) {
            record(repo, id, runCatching { RepoCompound.run(ctx, repo, id, authFor(repo)) }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }, SyncHistory.TRIGGER_MANUAL)
        }
    }

    /** The bulk bar: [id] on every registered clone ([runCompound] per repository; each answers in its own row). */
    /** The bulk bar: [id] on every clone of [only] (default: every registered clone) through ONE bounded job; each answers in its own row. */
    fun runCompoundAll(id: String, only: List<ManagedRepo>? = null) {
        if (!RepoActions.isCompound(id)) return
        bulkOps(id, only ?: repos.value) { repo -> record(repo, id, runCatching { RepoCompound.run(ctx, repo, id, authFor(repo)) }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }, SyncHistory.TRIGGER_MANUAL) }
    }

    /**
     * #1315 ONE job for a verb over many clones: at most ui.sync.git.jobs.workers at once, one job per path, exclusive with
     * the other writing jobs; the per-row step and result are published as for a single op. Returns null when refused.
     */
    private fun bulkOps(id: String, targets: List<ManagedRepo>, op: (ManagedRepo) -> GitOpResult): kotlinx.coroutines.Job? {
        val byPath = targets.associateBy { File(it.path).absolutePath }
        val step = if (id == RepoActions.SYNC_LOCAL_WINS || id == OP_PUSH) Step.PUSHING else Step.PULLING
        return jobs.submit(GitJobs.Spec(id, id, id, targets.map { GitJobs.Target(File(it.path).absolutePath, it.name) }, workers = Declarations.sync.git.jobs.workers, exclusive = true,
            onEnd = { refresh().join() }) { t ->
            val repo = byPath.getValue(t.path)
            if (running.value.containsKey(repo.id)) return@Spec GitJobs.Outcome(GitJobs.Status.SKIP, GitJobs.LOCKED)
            running.update { it + (repo.id to Running(repo.id, step)) }
            val result = try { withContext(Dispatchers.IO) { op(repo) } } finally { running.update { it - repo.id } }
            opResults.update { it + (repo.id to result) }
            statusReader.invalidate(statusKey(repo))
            GitJobs.Outcome(if (result.ok) GitJobs.Status.OK else GitJobs.Status.FAIL, result.summary, result.details)
        })
    }

    /**
     * #1315 ONE op on ONE clone through the executor: not exclusive (reads and single verbs run beside a bulk job) but the
     * path is locked, so a folder command never moves a clone a verb is working in. [body] runs on IO and answers the result.
     */
    private fun single(repo: ManagedRepo, id: String, step: Step, body: () -> GitOpResult) {
        if (running.value.containsKey(repo.id)) return
        running.update { it + (repo.id to Running(repo.id, step)) }
        val started = jobs.submit(GitJobs.Spec(id + "@" + repo.id, id, id, listOf(GitJobs.Target(File(repo.path).absolutePath, repo.name)), workers = 1, exclusive = false) { _ ->
            val result = try { withContext(Dispatchers.IO) { body() } } finally { running.update { it - repo.id } }
            opResults.update { it + (repo.id to result) }
            reread(repo)
            GitJobs.Outcome(if (result.ok) GitJobs.Status.OK else GitJobs.Status.FAIL, result.summary, result.details)
        })
        if (started == null) running.update { it - repo.id }
    }

    /** Goto > Checkout Main, through the terminal-root wire; the answer lands in the repo's result line. */
    fun checkoutMain(repo: ManagedRepo) = single(repo, "checkout_main", Step.PULLING) {
        record(repo, "checkout_main", runCatching { RepoCompound.checkoutMain(ctx, repo, authFor(repo)) }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }, SyncHistory.TRIGGER_MANUAL)
    }

    /** Workflow > PRs: the open pull requests as display lines. */
    suspend fun prFeed(owner: String, name: String): Result<List<String>> = withContext(Dispatchers.IO) {
        RepoRemoteStats.fetchPulls(Declarations.sync.git.api.baseUrl, owner, name, declaredToken())
    }

    // ── #1138 clone settings: persisted, applied by every later clone ──

    /** 0 = clone the full history, 1 = depth 1. */
    val cloneDepth = MutableStateFlow(0).also { f -> scope.launch(Dispatchers.IO) { f.value = pagePrefs.getInt(KEY_CLONE_DEPTH, 0) } }
    /** True = clone over SSH, false = Web OAuth (https with the sign-in's token). */
    val cloneViaSsh = MutableStateFlow(false).also { f -> scope.launch(Dispatchers.IO) { f.value = pagePrefs.getBoolean(KEY_CLONE_SSH, false) } }

    fun setCloneDepth(depth: Int) { cloneDepth.value = depth; scope.launch(Dispatchers.IO) { pagePrefs.edit().putInt(KEY_CLONE_DEPTH, depth).apply() } }
    fun setCloneViaSsh(ssh: Boolean) { cloneViaSsh.value = ssh; scope.launch(Dispatchers.IO) { pagePrefs.edit().putBoolean(KEY_CLONE_SSH, ssh).apply() } }

    // ── #1138 the repo table's remote facts: cached, refreshed on pull-to-refresh only ──

    val remoteStats = MutableStateFlow<Map<String, RepoRemoteStats.Stats>>(emptyMap())
        .also { f -> scope.launch(Dispatchers.IO) { f.value = RepoRemoteStats.Cache.load(pagePrefs) } }
    val refreshingRemote = MutableStateFlow(false)

    /** Re-read the REST facts of [targets] (owner to name), keeping the last good answer of a repository that fails. */
    fun refreshRemoteStats(targets: List<Pair<String, String>>, extraToken: String = "") {
        if (!refreshingRemote.compareAndSet(false, true)) return
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                val token = extraToken.ifBlank { declaredToken() }
                val base = Declarations.sync.git.api.baseUrl
                val out = remoteStats.value.toMutableMap()
                targets.distinct().forEach { (owner, name) ->
                    RepoRemoteStats.fetch(base, owner, name, token).onSuccess { out[RepoRemoteStats.keyOf(owner, name)] = it }
                        .onFailure { DriveDebugLog.e(ctx, TAG, "remote stats $name: ${it.message}") }
                }
                RepoRemoteStats.Cache.save(pagePrefs, out)
                out
            }
            remoteStats.value = next
            refreshingRemote.value = false
        }
    }

    /** #1256 first open only: no cached entry carries the forge's pushed_at (the Last commit fallback), so read the remote facts once. */
    fun fillRemoteStatsOnce(targets: List<Pair<String, String>>, extraToken: String = "") {
        scope.launch {
            val cached = withContext(Dispatchers.IO) { RepoRemoteStats.Cache.load(pagePrefs) }
            if (targets.isNotEmpty() && LastCommits.needsRemoteFill(cached.values)) refreshRemoteStats(targets, extraToken)
        }
    }

    /** The CI/CD run feed of one repository: newest runs as display lines, or the failure sentence. */
    suspend fun runFeed(owner: String, name: String): Result<List<String>> = withContext(Dispatchers.IO) {
        RepoRemoteStats.fetchRuns(Declarations.sync.git.api.baseUrl, owner, name, declaredToken())
    }

    /**
     * Point `origin` at the URL a DECLARED remote mode composes, and record which shape
     * the repository now speaks so the credential store hands the engine the matching
     * [com.diegonmarcos.cloudlib.gitsync.GitAuth]. A read-only mode keeps the HTTPS URL
     * and drops the auth kind to none: the page then refuses push and force push on it
     * rather than offering a button that always fails.
     */
    fun setRemote(repo: ManagedRepo, mode: com.diegonmarcos.clouddrive.Declarations.GitRemoteModeDecl, owner: String, name: String) {
        val url = mode.urlFor(owner, name)
        if (url.isBlank()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GitEngine(ctx, File(repo.path)).use { e ->
                        if (e.remotes().none { r -> r.name == ORIGIN }) e.addRemote(ORIGIN, url)
                        else { e.setRemoteUrl(ORIGIN, url); e.setRemoteUrl(ORIGIN, url, push = true) }
                    }
                    GitOpResult(true, "origin → $url")
                }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            }
            if (result.ok) {
                val kind = when {
                    mode.readOnly -> AUTH_NONE
                    mode.id == com.diegonmarcos.clouddrive.Declarations.REMOTE_SSH -> AUTH_SSH
                    else -> AUTH_HTTPS
                }
                withContext(Dispatchers.IO) { registry.upsert(repo.copy(remoteUrl = url, authKind = kind)) }
            }
            opResults.update { it + (repo.id to result) }
            refresh()
        }
    }

    /**
     * Clone [url] into the store's git folder as [name] and register it — the SAME engine,
     * the SAME registry and the SAME folder the first-run seed uses, so a repository cloned
     * from the page and one seeded on first run are indistinguishable afterwards.
     *
     * [token] is the sign-in's access token for an https clone (blank for a public one) and
     * is handed to the engine and to the credential store, never written here.
     */
    fun cloneInto(name: String, url: String, authKind: String, username: String, token: String, sshKeyPath: String = "") {
        if (name.isBlank() || url.isBlank() || cloning.value.contains(name)) return
        val dir = SharedStore.repoDir(name)
        cloning.update { it + name }
        val started = jobs.submit(GitJobs.Spec("clone@" + name, Declarations.GIT_OP_CLONE, Declarations.GIT_OP_CLONE, listOf(GitJobs.Target(dir.absolutePath, name)), workers = 1, exclusive = false) { _ ->
            // The clone's decision point, on the device: the URL's HOST and the destination —
            // never the full URL (a listing URL could carry a credential in its userinfo) and
            // never the token, which travels only through GitCredentialStore.
            DriveDebugLog.i(ctx, TAG, "clone start: host=${hostOf(url)} dest=${dir.name} auth=$authKind")
            val result = try { withContext(Dispatchers.IO) { cloneBlocking(name, url, authKind, username, token, sshKeyPath, cloneDepth.value.takeIf { it > 0 }) } } finally { cloning.update { it - name } }
            val now = System.currentTimeMillis() / 1000
            withContext(Dispatchers.IO) {
                history.append(SyncEvent(now, RepoRegistry.idFor(dir.absolutePath), name, SyncHistory.TRIGGER_MANUAL, result.ok, "clone: ${result.summary}", result.details))
                if (result.ok) DriveDebugLog.i(ctx, TAG, "clone $name: ${result.summary}")
                else DriveDebugLog.e(ctx, TAG, "clone $name FAILED: host=${hostOf(url)} dest=${dir.name} ${result.summary} ${result.details}".trim())
            }
            opResults.update { it + (RepoRegistry.idFor(dir.absolutePath) to result) }
            refresh()
            GitJobs.Outcome(if (result.ok) GitJobs.Status.OK else GitJobs.Status.FAIL, result.summary, result.details)
        })
        if (started == null) cloning.update { it - name }
    }


    /** The clone itself, blocking (IO): the store's exec path or the engine, [depth] null = full history; registers on success. Shared by [cloneInto] and [cloneMissing]. */
    private fun cloneBlocking(name: String, url: String, authKind: String, username: String, token: String, sshKeyPath: String, depth: Int?): GitOpResult {
                val dir = SharedStore.repoDir(name)
                val id = RepoRegistry.idFor(dir.absolutePath)
                val managed = ManagedRepo(id = id, name = name, path = dir.absolutePath, remoteUrl = url, authKind = authKind, authUsername = username, sshKeyPath = sshKeyPath)
                if (token.isNotBlank()) credentials.setSecret(id, token)
                return runCatching {
                    // cloud-shared-store: not in cld.termux's uid -> clone inside it (CLOUD_EXEC).
                    if (StoreGit.useExec(ctx)) {
                        if (StoreGit.isRepository(ctx, dir)) GitOpResult(true, "already cloned")
                        else StoreGit.clone(ctx, url, dir, token, depth = depth).also { if (!it.ok) error(it.summary + " " + it.details) }
                    }
                    else if (GitEngine.isRepository(dir)) GitOpResult(true, "already cloned")
                    else { (if (depth != null) GitEngine.clone(ctx, url, dir, auth = authFor(managed), depth = depth) else GitEngine.clone(ctx, url, dir, auth = authFor(managed))).close(); GitOpResult(true, "cloned into ${dir.name}") }
                }.getOrElse {
                    val why = it.message ?: it.toString()
                    // #669 a fleet-session clone that fails must name its NEXT STEP —
                    // a redirect means the gate, not gitea, answered (sign in again);
                    // a 401/403 means the fleet itself refused. Never a silent
                    // fall-through to another host: this is the only URL attempted.
                    GitOpResult(false, if (authKind == AUTH_SESSION) FleetGit.explainCloneFailure(why) else why)
                }
                    .also { if (it.ok) registry.upsert(managed) }
    }

    // ── #1254 the declared depth commands: Clone all (depth 1) and Keep depth 1, bulk and per repository ──

    /** One registry repository the page could clone, with the credential the row's own clone would use. */
    data class CloneTarget(val name: String, val url: String, val authKind: String, val username: String, val token: String)

    /** The depth command in flight or last finished (progress, and every repository's outcome for the status window). */
    val depthRun = MutableStateFlow<BulkDepth.Run?>(null)
        .also { f -> scope.launch { jobs.jobs.collect { f.value = jobs.latest(*DEPTH_JOB_KINDS)?.let { s -> BulkDepth.Run(s.id, s.kind, s.total, s.done, s.ok, s.skipped, s.failed, s.cancelled, s.finished) } } } }

    /**
     * Every clone on the device the page can see: #1315 THE ONE INVENTORY's clones (every tree the pass found, whatever its class),
     * read fresh when no pass ran yet, plus the registry's own rows. No java.io scan of its own (blocking: IO).
     */
    private fun existingClones(list: List<ManagedRepo>): List<BulkDepth.Existing> {
        val inv = inventory.value ?: discover(list).first.let { RepoInventory.classify(it, RegistryNames.live(), Declarations.repoSot, list.map { r -> File(r.path).absolutePath }.toSet()) }
        // A managed row whose folder the pass did not see is gone (its parent is always a root): it holds nothing.
        val present = inv.rows.map { it.path }.toSet()
        val reg = list.filter { File(it.path).absolutePath in present }.map { BulkDepth.Existing(it.path, it.remoteUrl) }
        return reg + inv.rows.filter { it.isClone }.map { BulkDepth.Existing(it.path, it.origin) }
    }

    /**
     * #1254 CLONE MISSING at the declared depth: ONE clone per repository — a target whose repository already has a clone
     * ANYWHERE the page can see (by origin URL, renamed_from chains folded in; or its destination already holds a repository)
     * is skipped and reported, never cloned a second time. At most the declared workers at once; [cancelDepthRun] stops the
     * ones not started yet (a clone in flight finishes); the status cache is re-read after.
     */
    fun cloneMissing(action: Declarations.GitDepthActionDecl, targets: List<CloneTarget>) = depth(action, plan = {
        val list = registry.load()
        val plan = BulkDepth.planClones(targets.map { BulkDepth.Target(it.name, it.url) }, existingClones(list)) { Declarations.repoSot.canonical(it) }
        targets.filter { t -> t.name !in cloning.value && (plan.toClone.any { it.name == t.name } || t.name in plan.held) }.map { t -> DepthItem(SharedStore.repoDir(t.name).absolutePath, t.name, plan.held[t.name]) to t }
    }) { (item, t) ->
        val held = item.held
        if (held != null) t.name to Outcome.SKIP to "already cloned at $held"
        else if (withContext(Dispatchers.IO) { StoreGit.useExec(ctx) && StoreGit.isRepository(ctx, SharedStore.repoDir(t.name)) || GitEngine.isRepository(SharedStore.repoDir(t.name)) }) {
            t.name to Outcome.SKIP to "already cloned at ${SharedStore.repoDir(t.name).absolutePath}"
        } else {
            cloning.update { it + t.name }
            val r = try { withContext(Dispatchers.IO) { cloneBlocking(t.name, t.url, t.authKind, t.username, t.token, "", action.depth) } } finally { cloning.update { it - t.name } }
            withContext(Dispatchers.IO) { history.append(SyncEvent(System.currentTimeMillis() / 1000, RepoRegistry.idFor(SharedStore.repoDir(t.name).absolutePath), t.name, SyncHistory.TRIGGER_MANUAL, r.ok, "${action.id}: ${r.summary}", r.details)) }
            t.name to (if (r.ok) Outcome.OK else Outcome.FAIL) to (if (r.ok) r.summary else BulkDepth.withTail(r.summary, r.details))
        }
    }

    /**
     * #1254 KEEP DEPTH in place for every registered clone, or only [only]: the gate ([BulkDepth.keepSkip]) is read from the
     * clone itself first — dirty, conflicted, mid-merge, no upstream or ahead is SKIPPED and reported; the rest are shrunk by the
     * terminal-root wire's `keep_depth` (which checks the gate again). Same single clone, nothing re-cloned.
     */
    fun keepDepth(action: Declarations.GitDepthActionDecl, only: ManagedRepo? = null) = depth(action, plan = {
        (only?.let { listOf(it) } ?: registry.load()).map { DepthItem(File(it.path).absolutePath, it.name) to it }
    }) { (_, repo) ->
        val token = lazy { declaredToken() }
        if (running.value.containsKey(repo.id)) return@depth repo.name to Outcome.SKIP to "an operation is already running"
        running.update { it + (repo.id to Running(repo.id, Step.PULLING)) }
        try {
            withContext(Dispatchers.IO) {
                val dir = File(repo.path)
                val cloned = runCatching { GitEngine.isRepository(dir) || (StoreGit.useExec(ctx) && StoreGit.isRepository(ctx, dir)) }.getOrDefault(false)
                val skip = if (!cloned) BulkDepth.SKIP_NOT_CLONED else runCatching {
                    GitEngine(ctx, dir).use { e -> val st = e.status(); BulkDepth.keepSkip(true, st.files.size - st.conflicts.size, st.conflicts.size, st.ahead, st.upstream, st.repositoryState) }
                }.getOrElse { "unreadable: ${it.message}" }
                if (skip != null) return@withContext repo.name to Outcome.SKIP to skip
                val r = RepoCompound.raw(ctx, dir, "keep_depth", org.json.JSONObject().put("depth", action.depth), authFor(repo) { token.value })
                record(repo, action.id, r, SyncHistory.TRIGGER_MANUAL)
                statusReader.invalidate(statusKey(repo))
                val why = r.details.ifBlank { r.summary }
                repo.name to (if (r.ok) Outcome.OK else if (why.contains("skipped:")) Outcome.SKIP else Outcome.FAIL) to (if (r.ok) "kept depth ${action.depth}" else why.take(300))
            }
        } finally { running.update { it - repo.id } }
    }

    private enum class Outcome { OK, SKIP, FAIL }

    // ── #1282 Clone bare: ONE bare mirror per repository under the declared bare root (BareClones), the working clone untouched ──

    /** Canonical identity -> mirror path, for the page's "bare ✓" indicator; read on IO by [refreshBare]. */
    val bareMirrors = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun bareRoot(): File = File(SharedStore.gitRoot(), Declarations.sync.git.bareRoot)

    private fun listMirrors(): List<BareClones.Mirror> = if (StoreGit.useExec(ctx)) StoreGit.listBare(ctx, bareRoot()) else emptyList()

    fun refreshBare() { scope.launch(Dispatchers.IO) { bareMirrors.value = BareClones.index(listMirrors()) { Declarations.repoSot.canonical(it) } } }

    /**
     * #1282 CLONE BARE at the declared depth (0 = full history): a target whose repository already has a mirror (origin URL,
     * renamed_from chains folded in) is UPDATED with `git remote update --prune`, never cloned twice; the rest get
     * `git clone --mirror` at <store git>/<bare_root>/<owner>/<repo>.git. Bounded workers, cancel-safe, each outcome in the
     * status window and Activity Logs. git runs in cld.termux; without it the run says so and changes nothing.
     */
    fun cloneBare(action: Declarations.GitDepthActionDecl, targets: List<CloneTarget>) {
        val canonical: (String) -> String = { Declarations.repoSot.canonical(it) }
        depth(action, plan = {
            if (!StoreGit.useExec(ctx)) targets.map { DepthItem("", it.name, null, BARE_NO_TERMUX) to it }
            else {
                val plan = BareClones.plan(targets.map { BareClones.Target(it.name, it.url) }, listMirrors(), canonical)
                val gitGuest = shell.route.guest(SharedStore.gitRoot().absolutePath)
                targets.filter { t -> t.name in plan.toUpdate || plan.toClone.any { it.name == t.name } }.map { t ->
                    val at = plan.toUpdate[t.name]
                    val dest = at ?: gitGuest?.let { BareClones.path(it, Declarations.sync.git.bareRoot, t.url, t.name, canonical) }
                    DepthItem(dest?.let { shell.route.host(it) } ?: "", t.name, at, if (dest == null) "no safe bare path for ${t.name}" else null) to t
                }
            }
        }, onEnd = { withContext(Dispatchers.IO) { bareMirrors.value = BareClones.index(listMirrors(), canonical) } }) { (item, t0) ->
            item.refusal?.let { return@depth t0.name to Outcome.FAIL to it }
            val t = if (t0.token.isBlank()) t0.copy(token = runCatching { declaredToken() }.getOrDefault("")) else t0
            // #1314 never while another git job runs on this repository (its working clone's operation).
            if (repos.value.any { it.name == t.name && running.value.containsKey(it.id) }) return@depth t.name to Outcome.SKIP to "an operation is already running"
            val r = withContext(Dispatchers.IO) {
                val at = item.held
                if (at != null) StoreGit.bareUpdate(ctx, at, t.token, action.depth)
                else StoreGit.bareClone(ctx, t.url, shell.route.guestOrThrow(item.path), t.token, action.depth)
            }
            withContext(Dispatchers.IO) { history.append(SyncEvent(System.currentTimeMillis() / 1000, "bare:" + t.name, t.name, SyncHistory.TRIGGER_MANUAL, r.ok, "${action.id}: ${r.summary}", r.details)) }
            t.name to (if (r.ok) Outcome.OK else Outcome.FAIL) to (if (r.ok) r.summary else BulkDepth.withTail(r.summary, r.details))
        }
    }

    /** One depth command at a time; the run state and the job are the coordinator's, so a page close does not lose them. */
    /** One planned item of a depth / folder command: the path it locks (host form; "" = none), the name the report shows, what already holds it, or why it cannot run. */
    private data class DepthItem(val path: String, val name: String, val held: String? = null, val refusal: String? = null)

    /**
     * #1315 ONE depth / folder command through the ONE executor: [plan] (IO) names the items first, then one job runs [one] over
     * them at most [action.workers] at a time, one job per path, exclusive with every other writing job ([cancelDepthRun] stops
     * the items not started yet); every outcome lands in the report and, through [depthRun], in the status window; the registry
     * and the status cache are re-read when it ended. Refused (nothing runs, nothing changes) while another command runs.
     */
    private fun <T> depth(action: Declarations.GitDepthActionDecl, plan: suspend () -> List<Pair<DepthItem, T>>, onEnd: suspend () -> Unit = {}, one: suspend (Pair<DepthItem, T>) -> Pair<Pair<String, Outcome>, String>) {
        val probe = GitJobs.Spec(action.id, action.kind, action.label, emptyList()) { GitJobs.Outcome(GitJobs.Status.SKIP, "") }
        jobs.refusal(probe)?.let { DriveDebugLog.i(ctx, TAG, "${action.id}: refused: $it"); return }
        scope.launch {
            val items = runCatching { withContext(Dispatchers.IO) { plan() } }.getOrElse { e -> DriveDebugLog.e(ctx, TAG, "${action.id}: planning failed: ${e.message}"); emptyList() }
            val byKey = items.associateBy { it.first.path.ifBlank { it.first.name } }
            jobs.submit(GitJobs.Spec(action.id, action.kind, action.label, items.map { GitJobs.Target(it.first.path, it.first.name) }, workers = action.workers, exclusive = true,
                onEnd = { s ->
                    DriveDebugLog.i(ctx, TAG, "${action.id}: ${s.ok.size} ok, ${s.skipped.size} skipped, ${s.failed.size} failed" + if (s.cancelled) ", cancelled" else "")
                    runCatching { onEnd() }
                    refresh(force = true).join()
                    refreshLastCommits()
                }) { t ->
                val item = byKey[t.path.ifBlank { t.name }] ?: return@Spec GitJobs.Outcome(GitJobs.Status.FAIL, "not planned")
                val (head, msg) = one(item); val (_, o) = head
                GitJobs.Outcome(when (o) { Outcome.OK -> GitJobs.Status.OK; Outcome.SKIP -> GitJobs.Status.SKIP; Outcome.FAIL -> GitJobs.Status.FAIL }, msg)
            })
        }
    }

    /** At most the declared workers at once; each item's outcome lands in [depthRun] as it ends. */

    /** Stop the depth command: nothing new starts; an operation already running ends on its own. */
    fun cancelDepthRun() { jobs.latest(*DEPTH_JOB_KINDS)?.takeIf { !it.finished }?.let { jobs.cancel(it.id) } }

    // ── #1288 the per-folder commands of the Parentless and Unlisted tables (ui.sync.git.folder_actions): Rename, Archive, Delete ──

    /** <store git>/<archive_root>, or null when the declared root is unsafe. */
    fun archiveDir(): File? = FolderActions.archiveDir(SharedStore.gitRoot(), Declarations.sync.git.archiveRoot)

    /** The guest path when the store is reached through cld.termux, else null (java.io on the folder itself). */
    private fun execGuest(dir: File): String? = shell.route.let { r -> if (r.viaTermux) r.guest(dir.absolutePath) else null }

    /** What Delete must show before it runs: uncommitted changes and unpushed commits read from the clone (blocking: call on IO). */
    fun deleteGuard(path: String): FolderActions.Guard {
        val dir = File(path)
        return runCatching {
            val g = execGuest(dir)
            if (g != null) {
                val r = shell.run(FolderActions.guardScript(g) { StoreShell.q(it) }, timeoutMs = 30_000)
                if (r.exit == 2) FolderActions.Guard(emptyList(), null, "not readable in cld.termux") else FolderActions.parseGuard(r.out)
            } else GitEngine(ctx, dir).use { e ->
                val st = e.status()
                FolderActions.Guard(st.files.map { it.path }, if (st.upstream.isNullOrBlank()) null else st.ahead)
            }
        }.getOrElse { FolderActions.Guard(emptyList(), null, it.message ?: it.toString()) }
    }

    /** Move one folder; refuses when the destination exists (cld.termux checks again, so a race never merges two folders). */
    private fun moveFolder(from: File, to: File): GitOpResult {
        val gf = execGuest(from); val gt = execGuest(to)
        // #1317 through cld.termux java.io cannot see the store: only the script (exit 17) decides an existing destination.
        if (gf == null && to.exists()) return GitOpResult(false, "refused: ${to.name} already exists")
        if (gf != null && gt != null) {
            val r = shell.run(FolderActions.moveScript(gf, gt) { StoreShell.q(it) }, timeoutMs = 120_000)
            return GitOpResult(r.ok, when (r.exit) { 0 -> "moved"; 16 -> "the folder is gone"; 17 -> "refused: ${to.name} already exists"; else -> "move in cld.termux failed (exit ${r.exit})" }, (r.out + "\n" + r.err).trim().takeLast(2000))
        }
        to.parentFile?.mkdirs()
        return if (from.renameTo(to)) GitOpResult(true, "moved") else GitOpResult(false, "the move failed (nothing changed)")
    }

    /** #1317 which of [candidates] exist under [parent] in cld.termux (ONE probe); null = the probe failed. */
    private fun takenInExec(parent: File, candidates: List<String>): Set<String>? = runCatching {
        val r = shell.run(FolderActions.takenScript(shell.route.guestOrThrow(parent.absolutePath), candidates) { StoreShell.q(it) }, timeoutMs = 60_000)
        if (r.exit == 0) r.out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else null
    }.getOrNull()

    private fun renameFolder(t: FolderActions.Target, repoint: (File, File) -> String): Pair<Outcome, String> {
        val dir = File(t.path); val parent = dir.parentFile
        val g = execGuest(dir)
        // #1317 java.io is asked only when the store is read directly; through cld.termux the move script says "gone" (exit 16).
        if (parent == null || FolderActions.goneByIo(g != null) { dir.isDirectory }) return Outcome.FAIL to "the folder is gone"
        val plan = FolderActions.renamePlan(dir.name, t.repoName, RegistryNames.live(), g != null, { takenInExec(parent, it) }) { File(parent, it).exists() }
        val name = when (plan) {
            is FolderActions.RenamePlan.Skip -> return Outcome.SKIP to plan.why
            is FolderActions.RenamePlan.Fail -> return Outcome.FAIL to plan.why
            is FolderActions.RenamePlan.Go -> plan.name
        }
        val want = FolderActions.renameName(t.repoName, RegistryNames.live()) ?: t.repoName
        val to = File(parent, name)
        val r = moveFolder(dir, to)
        if (!r.ok) return Outcome.FAIL to r.summary
        val refs = runCatching { repoint(dir, to) }.getOrDefault("")
        val head = if (name != want) "$want already exists: renamed to $name (nothing overwritten or merged)" else "renamed to $name"
        return Outcome.OK to (head + if (refs.isNotBlank()) "; $refs" else "")
    }

    /** #1317 Fix origin: the clone's origin URL gets the CURRENT repository name (same scheme, host, owner); never for a name that is not a renamed_from, never while a git job runs on that clone. */
    private fun repointOrigin(t: FolderActions.Target): Pair<Outcome, String> {
        val names = RegistryNames.live()
        val fixed = FolderActions.fixedOrigin(t.origin, names) ?: return Outcome.SKIP to "origin already current"
        val dir = File(t.path)
        if (repos.value.any { File(it.path).absolutePath == dir.absolutePath && running.value.containsKey(it.id) }) return Outcome.SKIP to "an operation is already running"
        val note = FolderActions.fixNote(t.origin, names)
        val g = execGuest(dir)
        if (g != null) {
            val r = shell.run(FolderActions.setOriginScript(g, fixed) { StoreShell.q(it) }, timeoutMs = 60_000)
            return when (r.exit) { 0 -> Outcome.OK to note; 16 -> Outcome.FAIL to "the folder is gone"; 19 -> Outcome.FAIL to "no origin remote to fix"; else -> Outcome.FAIL to "set-url in cld.termux failed (exit ${r.exit})" }
        }
        return if (LocalRepos.setOrigin(dir, fixed)) Outcome.OK to note else Outcome.FAIL to "could not rewrite the origin in ${dir.name}/.git/config"
    }

    /** #1317 ui.sync.git.stale_origin.auto_fix: re-point every clone of [found] whose origin names a retired name; one Activity Logs line each. Returns the number fixed (blocking: call on IO). */
    fun autoFixOrigins(found: List<LocalRepos.Found>): Int {
        if (!Declarations.sync.git.staleOriginAutoFix) return 0
        var n = 0
        found.filter { it.stale && FolderActions.fixedOrigin(it.origin, RegistryNames.live()) != null }.forEach { f ->
            val (o, msg) = runCatching { repointOrigin(FolderActions.Target(f.dir.absolutePath, f.origin, f.repoName)) }.getOrElse { Outcome.FAIL to (it.message ?: it.toString()) }
            if (o == Outcome.SKIP) return@forEach
            history.append(SyncEvent(System.currentTimeMillis() / 1000, "folder:" + f.dir.absolutePath, f.dir.name, SyncHistory.TRIGGER_SCHEDULED, o == Outcome.OK, "stale_origin: $msg", f.dir.absolutePath))
            if (o == Outcome.OK) n++
        }
        if (n > 0) events.value = history.load()
        return n
    }

    private fun archiveFolder(t: FolderActions.Target): Pair<Outcome, String> {
        val dir = File(t.path)
        val root = archiveDir() ?: return Outcome.FAIL to "the declared archive_root is not a safe path"
        val g = execGuest(dir)
        if (FolderActions.goneByIo(g != null) { dir.isDirectory }) return Outcome.FAIL to "the folder is gone"
        if (FolderActions.insideArchive(dir, root)) return Outcome.SKIP to "already in ${root.name}"
        val now = System.currentTimeMillis()
        val takenSet = if (g != null) (takenInExec(root, FolderActions.archiveCandidates(dir.name, now)) ?: return Outcome.FAIL to "could not check the names in cld.termux; nothing changed") else null
        val name = FolderActions.archiveName(dir.name, now) { if (takenSet != null) it in takenSet else File(root, it).exists() } ?: return Outcome.FAIL to "no free name in ${root.name}"
        val to = File(root, name)
        val r = moveFolder(dir, to)
        return if (r.ok) Outcome.OK to "archived to ${root.name}/$name" else Outcome.FAIL to r.summary
    }

    private fun deleteFolder(t: FolderActions.Target, typed: Set<String>): Pair<Outcome, String> {
        val dir = File(t.path)
        if (FolderActions.goneByIo(execGuest(dir) != null) { dir.isDirectory }) return Outcome.SKIP to "the folder is gone"
        if (dir.absolutePath == SharedStore.gitRoot().absolutePath || dir.parentFile == null) return Outcome.FAIL to "refused: not a single repository folder"
        val guard = deleteGuard(t.path)
        if (guard.strict && t.path !in typed) return Outcome.SKIP to "not deleted, needs the typed confirm: " + guard.reasons().joinToString("; ")
        val g = execGuest(dir)
        val ok = if (g != null) shell.run(FolderActions.deleteScript(g) { StoreShell.q(it) }, timeoutMs = 300_000).ok
        else File(dir, ".git").exists() && dir.deleteRecursively()
        return if (ok) Outcome.OK to "deleted the local folder (the remote is untouched)" + (if (guard.strict) "; discarded: " + guard.reasons().joinToString("; ") else "")
        else Outcome.FAIL to "the delete failed or was refused (not a git folder)"
    }

    /**
     * #1288 RUN ONE DECLARED FOLDER COMMAND over [targets] through the ONE executor ([depth] -> GitJobs): one command at a time,
     * at most the declared workers, Cancel stops what has not started, each outcome in the depth report and Activity Logs.
     * [typed] = the paths whose strict Delete the owner confirmed by typing the name; [repoint] re-points saved paths after a rename.
     */
    fun runFolderAction(action: Declarations.GitFolderActionDecl, targets: List<FolderActions.Target>, typed: Set<String> = emptySet(), repoint: (File, File) -> String = { _, _ -> "" }) {
        val run = action.asRun()
        val moved = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        depth(run, plan = { targets.map { DepthItem(it.path, it.name) to it } }, onEnd = {
            withContext(Dispatchers.IO) { events.value = history.load() }
            // #1317 the lists refresh after a run: rows that moved or were deleted go at once (list, persisted copy, status cache), then a rescan.
            val gone = moved.toSet()
            if (gone.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    gone.forEach { statusReader.invalidate(UnlistedRepos.statusKey(it)) }
                    val kept = UnlistedRepos.without(unlisted.value, gone)
                    unlisted.value = kept
                    pagePrefs.edit().putString(KEY_UNLISTED, UnlistedRepos.encode(kept)).apply()
                }
            }
            scanUnlisted()
        }) { (_, t) ->
            withContext(Dispatchers.IO) {
                val (o, msg) = runCatching {
                    when (action.kind) {
                        Declarations.FOLDER_RENAME -> renameFolder(t, repoint)
                        Declarations.FOLDER_ARCHIVE -> archiveFolder(t)
                        Declarations.FOLDER_DELETE -> deleteFolder(t, typed)
                        Declarations.FOLDER_REPOINT -> repointOrigin(t)
                        else -> Outcome.FAIL to "unknown folder command ${action.kind}"
                    }
                }.getOrElse { Outcome.FAIL to (it.message ?: it.toString()) }
                if (o == Outcome.OK && action.kind != Declarations.FOLDER_REPOINT) moved.add(t.path)
                history.append(SyncEvent(System.currentTimeMillis() / 1000, "folder:" + t.path, t.name, SyncHistory.TRIGGER_MANUAL, o != Outcome.FAIL, "${action.id}: $msg", t.path))
                t.name to o to msg
            }
        }
    }

    /** #1288 light status reads for folder-table rows (the Parentless table), keyed like the unlisted ones in the ONE reader. */
    fun requestFolderStatus(paths: List<String>) {
        if (paths.isEmpty()) return
        statusReader.request(paths.map { GitStatusReader.Target(UnlistedRepos.statusKey(it), File(it)) })
    }

    // ── #1255 the Unlisted git folders: declared roots, bounded walk, statuses in the ONE reader (so in StatusCache) ──

    val unlisted = MutableStateFlow<List<UnlistedRepos.Folder>>(emptyList())
        .also { f -> scope.launch(Dispatchers.IO) { f.value = UnlistedRepos.decode(pagePrefs.getString(KEY_UNLISTED, null)) } }
    val scanningUnlisted = MutableStateFlow(false)

    // ── #1315 THE ONE INVENTORY: one discovery pass (the store through the one shell, the rest through java.io), classified once ──

    /** The last inventory (persisted entries re-classified at start, replaced by every pass); null until the first pass or load. */
    val inventory = MutableStateFlow<RepoInventory.Inventory?>(null)
        .also { f -> scope.launch(Dispatchers.IO) { runCatching { InventoryScript.decode(pagePrefs.getString(KEY_INVENTORY, null)) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { f.value = classifyInventory(it, 0L, "cache") } } }

    private fun classifyInventory(entries: List<InventoryScript.Entry>, now: Long, via: String, truncated: Boolean = false, malformed: Int = 0): RepoInventory.Inventory =
        RepoInventory.classify(entries, RegistryNames.live(), Declarations.repoSot, repos.value.map { File(it.path).absolutePath }.toSet(), now, via, truncated, malformed)

    /** The declared roots with their placeholders expanded (host form). */
    private fun inventoryRoots(list: List<ManagedRepo>): List<File> = UnlistedRepos.expandRoots(Declarations.sync.git.unlisted.roots, mapOf(
        "store_git" to listOf(SharedStore.gitRoot().absolutePath),
        "repo_parents" to list.mapNotNull { File(it.path).parentFile?.absolutePath }.distinct(),
        "external" to listOfNotNull(runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrNull()),
        "files" to listOf(ctx.filesDir.absolutePath),
    ))

    /**
     * ONE pass, blocking (IO): the roots inside the store through ONE script on the shell (cld.termux sees the store whatever this
     * app's uid), the roots outside it through the java.io walk of the same shape; without cld.termux every root walks in java.io.
     * The archive root is never entered (#1288). Returns the entries and how they were read.
     */
    private fun discover(list: List<ManagedRepo>): Triple<List<InventoryScript.Entry>, String, Int> {
        val d = Declarations.sync.git.unlisted
        val inv = Declarations.sync.git.inventory
        val roots = inventoryRoots(list)
        val archive = archiveDir()
        val route = shell.route
        val (storeRoots, ioRoots) = if (route.viaTermux) roots.partition { route.inStore(it.absolutePath) } else emptyList<File>() to roots
        val out = ArrayList<InventoryScript.Entry>()
        var via = ""
        var malformed = 0
        if (storeRoots.isNotEmpty()) {
            val r = shell.run(InventoryScript.build(storeRoots.mapNotNull { route.guest(it.absolutePath) }, d.maxDepth, inv.debris) { StoreShell.q(it) }, timeoutMs = d.timeoutSeconds.coerceAtMost(110) * 1000)
            if (r.exit == StoreShell.REFUSED) DriveDebugLog.e(ctx, TAG, "inventory: cld.termux refused: ${r.err}")
            val p = InventoryScript.parse(r.out) { g -> route.host(g) }
            val archiveHost = archive?.absolutePath
            out += p.entries.filter { e -> archiveHost == null || !(e.path == archiveHost || e.path.startsWith("$archiveHost/")) }
            malformed += p.malformed
            via = "exec"
        }
        if (ioRoots.isNotEmpty()) {
            out += InventoryScript.walkIo(ioRoots, d.maxDepth, d.maxDirs, System.currentTimeMillis() + d.timeoutSeconds * 1000L, inv.debris) { f -> archive != null && FolderActions.insideArchive(f, archive) }
            via = if (via.isEmpty()) "files" else "exec+files"
        }
        return Triple(out.distinctBy { it.path }, via, malformed)
    }

    /**
     * #1315 auto_register: a clone the pass found under a registry name that the managed list lacks is registered (name, path,
     * origin; the declared credential path serves it), so the table says cloned and the scheduler syncs it. Never removes anything.
     */
    private fun autoRegister(inv: RepoInventory.Inventory, list: List<ManagedRepo>): Int {
        if (!Declarations.sync.git.inventory.autoRegister) return 0
        val known = list.map { File(it.path).absolutePath }.toSet()
        var n = 0
        inv.rows.filter { (it.cls == RepoInventory.RepoClass.REGISTERED || it.cls == RepoInventory.RepoClass.STALE_ORIGIN || it.cls == RepoInventory.RepoClass.MISNAMED) && it.path !in known }.forEach { row ->
            runCatching {
                registry.upsert(ManagedRepo(id = RepoRegistry.idFor(row.path), name = row.name, path = row.path, remoteUrl = row.origin, authKind = if (row.origin.isBlank()) AUTH_NONE else AUTH_HTTPS))
                DriveDebugLog.i(ctx, TAG, "inventory: registered ${row.name} at ${row.path}")
                n++
            }.onFailure { DriveDebugLog.e(ctx, TAG, "inventory: could not register ${row.name}: ${it.message}") }
        }
        return n
    }

    /**
     * #1315 THE LEFTOVER CLEANUP, one job through the executor: every DEBRIS row (a declared debris name) older than
     * ui.sync.git.inventory.cleanup.min_age_hours is deleted (cld.termux decides the age for a store folder: exit 20 = too young,
     * 16 = gone), a younger one is skipped and stays listed; one Activity Logs line per deletion; the inventory is read again after.
     */
    fun cleanupDebris(rows: List<RepoInventory.Row>): kotlinx.coroutines.Job? {
        val d = Declarations.sync.git.inventory
        val minutes = d.cleanupMinAgeHours * 60
        val targets = rows.filter { it.cls == RepoInventory.RepoClass.DEBRIS && it.path.isNotBlank() }
        if (targets.isEmpty()) return null
        return jobs.submit(GitJobs.Spec(CLEANUP_DEBRIS, CLEANUP_DEBRIS, CLEANUP_DEBRIS, targets.map { GitJobs.Target(it.path, it.name) }, workers = 1, exclusive = false,
            onEnd = { s -> if (s.ok.isNotEmpty()) { DriveDebugLog.i(ctx, TAG, "cleanup: ${s.ok.size} leftover folder(s) deleted"); scanUnlisted() } }) { t ->
            val (status, msg) = withContext(Dispatchers.IO) {
                val dir = File(t.path)
                val g = execGuest(dir)
                if (g != null) {
                    val r = shell.run(FolderActions.debrisScript(g, minutes) { StoreShell.q(it) }, timeoutMs = 120_000)
                    when (r.exit) { 0 -> GitJobs.Status.OK to "deleted"; 16 -> GitJobs.Status.SKIP to "already gone"; 20 -> GitJobs.Status.SKIP to "younger than ${d.cleanupMinAgeHours}h, kept"; else -> GitJobs.Status.FAIL to "delete in cld.termux failed (exit ${r.exit}) ${r.err.trim().takeLast(200)}".trim() }
                } else if (!dir.isDirectory) GitJobs.Status.SKIP to "already gone"
                else if (!FolderActions.debrisOldEnough(dir.lastModified(), System.currentTimeMillis(), minutes)) GitJobs.Status.SKIP to "younger than ${d.cleanupMinAgeHours}h, kept"
                else if (dir.deleteRecursively()) GitJobs.Status.OK to "deleted" else GitJobs.Status.FAIL to "could not delete"
            }
            if (status != GitJobs.Status.SKIP) withContext(Dispatchers.IO) { events.value = history.append(SyncEvent(System.currentTimeMillis() / 1000, "folder:" + t.path, t.name, SyncHistory.TRIGGER_SCHEDULED, status == GitJobs.Status.OK, "$CLEANUP_DEBRIS: $msg", t.path)) }
            GitJobs.Outcome(status, msg)
        })
    }

    /** The pass, published: the inventory, the Unlisted section's rows (derived), the managed list (after auto_register), and the statuses of the new rows. */
    fun scanUnlisted(level: GitStatusReader.Level = GitStatusReader.Level.LIGHT) {
        if (!scanningUnlisted.compareAndSet(false, true)) return
        scope.launch {
            try {
                val (inv, found) = withContext(Dispatchers.IO) {
                    val list = registry.load()
                    var (entries, via, malformed) = discover(list)
                    var inv = RepoInventory.classify(entries, RegistryNames.live(), Declarations.repoSot, list.map { File(it.path).absolutePath }.toSet(), System.currentTimeMillis(), via, malformed = malformed)
                    // #1317 stale_origin.auto_fix on the inventory's rows: a clone whose origin names a retired name is re-pointed, then the pass reads again.
                    val stale = inv.rows.filter { it.staleOrigin && it.isClone }.map { LocalRepos.found(File(it.path), it.origin, RegistryNames.live()) }
                    if (stale.isNotEmpty() && autoFixOrigins(stale) > 0) {
                        discover(list).let { (e, v, m) -> entries = e; via = v; malformed = m }
                        inv = RepoInventory.classify(entries, RegistryNames.live(), Declarations.repoSot, list.map { File(it.path).absolutePath }.toSet(), System.currentTimeMillis(), via, malformed = malformed)
                    }
                    if (autoRegister(inv, list) > 0) inv = RepoInventory.classify(entries, RegistryNames.live(), Declarations.repoSot, registry.load().map { File(it.path).absolutePath }.toSet(), System.currentTimeMillis(), via, malformed = malformed)
                    pagePrefs.edit().putString(KEY_INVENTORY, InventoryScript.encode(entries)).apply()
                    val unlistedRows = inv.of(RepoInventory.RepoClass.UNLISTED).map { UnlistedRepos.Folder(it.path, it.origin) }
                    pagePrefs.edit().putString(KEY_UNLISTED, UnlistedRepos.encode(unlistedRows)).apply()
                    DriveDebugLog.i(ctx, TAG, "inventory ($via): " + inv.counts.entries.joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" } + if (malformed > 0) ", $malformed unreadable" else "")
                    inv to unlistedRows
                }
                inventory.value = inv
                unlisted.value = found
                loadRegistry()
                if (Declarations.sync.git.inventory.cleanupAuto) cleanupDebris(inv.of(RepoInventory.RepoClass.DEBRIS))
                statusReader.request(found.map { GitStatusReader.Target(UnlistedRepos.statusKey(it.path), File(it.path)) }, force = true, level = level)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed scan must never take the app down: keep the last cached list.
                DriveDebugLog.e(ctx, TAG, "inventory failed: ${e.message ?: e.javaClass.simpleName}")
            } finally { scanningUnlisted.value = false }
        }
    }

    // ── #1256 Last commit (local HEAD time per clone, cached), and the persisted filters and sort ──

    val lastCommits = MutableStateFlow<Map<String, Long>>(emptyMap())
        .also { f -> scope.launch(Dispatchers.IO) { f.value = decodeTimes(pagePrefs.getString(KEY_LAST_COMMITS, null)) } }

    /** Read the HEAD commit time of every registered clone (one log entry each, IO), persist it. Check-all and pull-to-refresh only. */
    fun refreshLastCommits() {
        scope.launch(Dispatchers.IO) {
            val list = registry.load()
            val out = list.mapNotNull { r ->
                runCatching { GitEngine(ctx, File(r.path)).use { e -> e.log(max = 1).firstOrNull()?.time } }
                    .onFailure { DriveDebugLog.e(ctx, TAG, "last commit ${File(r.path).name}: ${it.message}") }
                    .getOrNull()?.let(LastCommits::msOf)?.let { LastCommits.keyOf(r.path) to it }
            }.toMap()
            DriveDebugLog.i(ctx, TAG, "last commits: ${out.size}/${list.size} clones read")
            lastCommits.value = out
            pagePrefs.edit().putString(KEY_LAST_COMMITS, out.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()
        }
    }

    val filterSelection = MutableStateFlow<Map<String, String>>(emptyMap())
        .also { f -> scope.launch(Dispatchers.IO) { f.value = RepoFilters.decode(pagePrefs.getString(KEY_FILTERS, null)) } }
    fun setFilter(id: String, option: String) {
        filterSelection.update { if (option == RepoFilters.ALL) it - id else it + (id to option) }
        val enc = RepoFilters.encode(filterSelection.value)
        scope.launch(Dispatchers.IO) { pagePrefs.edit().putString(KEY_FILTERS, enc).apply() }
    }

    /** #1315 every filter back to its declared default (the Clear button). */
    fun clearFilters() { filterSelection.value = emptyMap(); scope.launch(Dispatchers.IO) { pagePrefs.edit().remove(KEY_FILTERS).apply() } }

    val sort = MutableStateFlow(RepoTable.DEFAULT_SORT to true)
        .also { f -> scope.launch(Dispatchers.IO) { f.value = RepoFilters.decodeSort(pagePrefs.getString(KEY_SORT, null)) } }
    fun setSort(column: RepoColumn, ascending: Boolean) {
        sort.value = column to ascending
        scope.launch(Dispatchers.IO) { pagePrefs.edit().putString(KEY_SORT, RepoFilters.encodeSort(column, ascending)).apply() }
    }

    companion object {
        private const val TAG = "GitSync"
        const val KEY_UNLISTED = "git.unlisted"
        /** #1315 the last inventory pass's entries (InventoryScript.encode), re-classified at start. */
        const val KEY_INVENTORY = "git.inventory"
        /** #1315 the job kinds the depth report, the depth progress line and the status window follow (depth commands and folder commands). */
        val DEPTH_JOB_KINDS: Array<String> = arrayOf(Declarations.DEPTH_CLONE_MISSING, Declarations.DEPTH_KEEP, Declarations.DEPTH_CLONE_BARE, Declarations.FOLDER_RENAME, Declarations.FOLDER_ARCHIVE, Declarations.FOLDER_DELETE, Declarations.FOLDER_REPOINT)
        const val CLEANUP_DEBRIS = "cleanup_debris"
        const val BARE_NO_TERMUX = "bare clones run git in cld.termux, which is not answering; nothing changed"
        const val KEY_LAST_COMMITS = "git.last_commits"
        const val KEY_FILTERS = "git.filters"
        const val KEY_SORT = "git.sort"

        fun decodeTimes(s: String?): Map<String, Long> = s.orEmpty().lineSequence().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { p -> p[1].toLongOrNull()?.let { p[0] to it } } }.toMap()

        /**
         * THE ONE KEY of a repository's status: the canonical registry name (a clone folder under an old name and its
         * renamed row are the same repository). The reader's cache, the glances and every row lookup use this and nothing else.
         */
        fun statusKey(repo: ManagedRepo, canonical: (String) -> String = { Declarations.repoSot.canonical(it) }): String = canonical(File(repo.path).name)

        /** #1259 the row's glance: by [statusKey], else by the folder's own name (an entry keyed before a registry rename reached this process). */
        fun glanceFor(repo: ManagedRepo, glances: Map<String, Glance>): Glance? = glances[statusKey(repo)] ?: glances[File(repo.path).name]

        /** #1259 cached keys mapped to the CURRENT canonical name (unlisted folders keep their path key); the newest entry wins a collision. */
        fun rekey(m: Map<String, GitStatusReader.Status>, canonical: (String) -> String): Map<String, GitStatusReader.Status> =
            m.entries.groupBy { (k, _) -> if (k.startsWith(UnlistedRepos.KEY_PREFIX)) k else canonical(k) }
                .mapValues { (_, v) -> v.maxBy { it.value.readAtMs }.value }

        /** #871 the git verbs run in the engine APK, so the shared status probe needs a Context to find it: the one the coordinators were built with. */
        @Volatile private var engineContext: Context? = null

        /**
         * #850 THE ONE STATUS READER of this process — shared by the page's coordinator and
         * the debug route's, so the cache, the in-flight claims and the bounds are one set.
         */
        val statusReader: GitStatusReader by lazy {
            val d = Declarations.sync.git.status
            GitStatusReader(
                probe = ::probeStatus,
                timeoutMs = d.timeoutSeconds * 1000L,
                concurrency = d.concurrency,
                ttlMs = d.ttlSeconds * 1000L,
                lightProbe = ::probeLight,
                tooLarge = { dir -> Declarations.repoSot.lfsOf(dir.name) == true || RepoCheap.tooLarge(dir, d.maxAutoFiles, d.maxScanMs) },
            )
        }

        /** The real probe: libs:git-sync's own status, never a second JGit call here. */
        fun probeStatus(dir: File): GitStatusReader.Probe = GitEngine(engineContext ?: error("no coordinator has been built yet, so there is no Context to reach the git sync engine with"), dir).use { e ->
            val s = e.status()
            GitStatusReader.Probe(
                s.branch, s.upstream, s.ahead, s.behind, s.files.size - s.conflicts.size, s.conflicts.size, s.repositoryState,
                untracked = s.files.count { !it.conflicting && it.unstaged == com.diegonmarcos.cloudlib.gitsync.GitChange.UNTRACKED },
                fetchedAtMs = File(dir, ".git/FETCH_HEAD").lastModified(),
            )
        }

        /**
         * LIGHT probe, refs only, never the worktree: plain .git files when this app can see them (log through the engine only
         * when the two tips differ); otherwise ONE script in cld.termux (a store owned by cld.termux is invisible to java.io).
         * Throws, with the exact path, when neither can read the clone: the row then ends in ERROR with that message.
         */
        fun probeLight(dir: File): GitStatusReader.Probe {
            // #1358 a clone in shared storage is the git engine's to run: without its own All files access every verb fails, so the row says that first.
            if (EngineGrant.blocks(dir.absolutePath)) error(EngineGrant.REASON + " (" + dir.absolutePath + ")")
            if (File(dir, ".git").isDirectory) return RepoCheap.lightProbe(dir) { _ ->
                GitEngine(engineContext ?: error("no coordinator has been built yet, so there is no Context to reach the git sync engine with"), dir).use { e -> e.log(max = Declarations.sync.git.status.logWindow).map { it.sha } }
            }
            val ctx = engineContext ?: error("no coordinator has been built yet, so there is no Context to reach the git sync engine with")
            val sh = StoreShell.of(ctx)
            if (sh.route.viaTermux && sh.route.inStore(dir.absolutePath)) {
                val guest = sh.route.guestOrThrow(dir.absolutePath)
                val r = sh.run(RepoCheap.lightScript(StoreShell.q(guest)), timeoutMs = 15_000)
                if (!r.ok) error("git in cld.termux failed (exit ${r.exit}) reading ${dir.absolutePath}: ${r.err.trim().takeLast(300)}")
                return RepoCheap.parseLight(r.out)
            }
            error("not a git repository: no readable .git in ${dir.absolutePath}")
        }

        const val PREFS_PAGE = "cloud-drive-prefs"
        const val KEY_AUTO_PULL = AutoChecks.GIT_PULL_KEY
        const val KEY_CLONE_DEPTH = "git.clone_depth"
        const val KEY_CLONE_SSH = "git.clone_ssh"
        const val TRIGGER_OPEN = "open"
        const val AUTO_PULL_GO = "go"
        const val AUTO_PULL_OFF = ""
        const val AUTO_PULL_METERED = "metered"
        const val AUTO_PULL_BUSY = "busy"

        /** #850 the pure auto-pull decision: [AUTO_PULL_GO], or why not. */
        fun autoPullDecision(enabled: Boolean, requireUnmetered: Boolean, unmetered: Boolean, busy: Boolean): String = when {
            !enabled -> AUTO_PULL_OFF
            requireUnmetered && !unmetered -> AUTO_PULL_METERED
            busy -> AUTO_PULL_BUSY
            else -> AUTO_PULL_GO
        }

        /** The row's [Glance] for one reader status — READING is the only non-read state. */
        fun glanceOf(s: GitStatusReader.Status): Glance = when (s.state) {
            GitStatusReader.State.READING -> Glance()
            GitStatusReader.State.NOT_CLONED -> Glance(gone = true, read = true, state = "not_cloned", readAtMs = s.readAtMs, path = s.path, tookMs = s.tookMs)
            GitStatusReader.State.LARGE -> Glance(s.probe?.branch, s.probe?.upstream, read = true, large = true, state = "large", readAtMs = s.readAtMs, level = "full", path = s.path, tookMs = s.tookMs)
            GitStatusReader.State.ERROR -> Glance(error = s.reason ?: "unknown", read = true, state = "error", readAtMs = s.readAtMs, level = s.level.name.lowercase(), path = s.path, tookMs = s.tookMs)
            else -> s.probe!!.let { p ->
                Glance(p.branch, p.upstream, p.ahead, p.behind, p.changed, p.conflicts, p.repositoryState, read = true, state = s.state.name.lowercase(), readAtMs = s.readAtMs, untracked = p.untracked, fetchedAtMs = p.fetchedAtMs, level = s.level.name.lowercase(), path = s.path, via = p.via, tookMs = s.tookMs)
            }
        }

        /**
         * #850/#818 which credential a transport verb rides: the fleet session for a
         * session repository, the repository's own stored secret when it has one, else the
         * ONE declared credential (vault token, then the chain) — never a second mechanism,
         * and only to the declared host ([declaredHost]) the credential belongs to.
         */
        fun pickAuth(
            kind: String,
            stored: () -> com.diegonmarcos.cloudlib.gitsync.GitAuth,
            session: () -> com.diegonmarcos.cloudlib.gitsync.GitAuth,
            declaredToken: () -> String,
            owner: String,
            declaredHost: Boolean,
        ): com.diegonmarcos.cloudlib.gitsync.GitAuth {
            if (kind == AUTH_SESSION) return session()
            val own = stored()
            if (own is com.diegonmarcos.cloudlib.gitsync.GitAuth.Ssh) return own
            if (own is com.diegonmarcos.cloudlib.gitsync.GitAuth.Https && own.secret.isNotBlank()) return own
            // The declared token is the DECLARED host's: never presented to another host.
            if (!declaredHost) return own
            val token = declaredToken()
            return if (token.isNotBlank()) com.diegonmarcos.cloudlib.gitsync.GitAuth.Https(owner, token) else own
        }

        /**
         * The URL's HOST alone, for the on-device debug log: a host names which leg a clone
         * rode (fleet gitea vs github) and can never carry a credential, while a full URL's
         * userinfo could. Covers both https URLs and the scp-like ssh form (git@host:o/r).
         */
        fun hostOf(url: String): String =
            runCatching { java.net.URI(url).host }.getOrNull()
                ?: url.substringAfter('@', url).substringBefore(':').substringBefore('/').ifBlank { "?" }

        const val DEFAULT_AUTHOR = "cloud-drive"
        const val DEFAULT_EMAIL = "cloud-drive@localhost"
        const val ORIGIN = "origin"
        const val AUTH_NONE = "none"
        const val AUTH_HTTPS = "https"
        const val AUTH_SSH = "ssh"
        /**
         * #669 the repository rides the FLEET SESSION (Authelia cookie, process
         * memory only) on the declared header — never a stored secret, never a
         * bearer. Set by the fleet-listed clone path; [authFor] resolves it.
         */
        const val AUTH_SESSION = "session"
        /** The declared op ids this coordinator performs; the page's own dispatch covers the read-only rest. */
        const val OP_FETCH = "fetch"
        const val OP_PULL = "pull"
        const val OP_COMMIT = "commit"
        const val OP_PUSH = "push"
        const val OP_FORCE_PUSH = "force_push"
        const val OP_FORCE_PULL = "force_pull"
    }
}
