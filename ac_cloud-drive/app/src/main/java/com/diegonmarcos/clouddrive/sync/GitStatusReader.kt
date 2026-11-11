package com.diegonmarcos.clouddrive.sync

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #850 THE ONE STATUS READER of the Sync ▸ Git page (and of /api/git/status).
 *
 * WHY IT EXISTS. Before it, GitSyncCoordinator.refresh() read EVERY repository's
 * `git status` serially inside ONE withContext block and published the glances only
 * when ALL of them had returned, with no bound. One clone whose status never returns
 * (a large working tree on the FUSE-backed shared storage, where JGit re-hashes every
 * file whose stat it cannot trust, or a held lock) therefore left every row on
 * "reading…" for ever — the map the rows read was never written. Each refresh() also
 * started a second full serial pass beside the first, so ops and re-entries stacked
 * readers on the same trees.
 *
 * THE CONTRACT, each clause tested by GitStatusReaderTest:
 *  - BOUNDED: every probe runs on its own daemon thread and is awaited for at most
 *    [timeoutMs]; past it the repository is ERROR("timeout …") — a terminal state.
 *  - OFF THE MAIN THREAD: probes run on dedicated threads, the waits on [scope].
 *  - PER REPOSITORY, LIMITED CONCURRENCY: at most [concurrency] probes are awaited at
 *    once and each publishes its own entry the moment it ends, so a large repository
 *    never holds the others back.
 *  - CACHED WITH A TIMESTAMP: an entry younger than [ttlMs] is not re-read unless forced.
 *  - NEVER RESTARTED: a repository with a probe in flight — or one still stuck past
 *    its timeout — is never probed again until that probe returns, however often the
 *    page recomposes or asks.
 *  - ALWAYS ENDS in CLEAN / AHEAD / BEHIND / DIRTY / NOT_CLONED / ERROR(reason).
 */
class GitStatusReader(
    private val probe: (File) -> Probe,
    private val timeoutMs: Long,
    concurrency: Int,
    private val ttlMs: Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Is [dir] a clone? A store repository is invisible to File checks in EXEC mode, so this asks the routed probe (GitEngine). */
    private val isRepository: (File) -> Boolean = { com.diegonmarcos.cloudlib.gitsync.GitEngine.isRepository(it) },
    /** Is [dir] too large for the unattended worktree scan (size guard)? true = the row ends LARGE ("tap to check") and no probe runs; an on-demand [Target] bypasses it. */
    private val tooLarge: (File) -> Boolean = { false },
    /** LIGHT level: refs only (cloned, branch, upstream, ahead/behind from refs), NO worktree walk. Defaults to [probe] so a reader built with one probe behaves as before. */
    private val lightProbe: (File) -> Probe = probe,
) {
    /** What a result was read with: LIGHT never looked at the working tree (dirty is unknown), FULL did. */
    enum class Level { LIGHT, FULL }

    /** What one `git status` says, before it is classified. */
    data class Probe(
        val branch: String?,
        val upstream: String?,
        val ahead: Int,
        val behind: Int,
        val changed: Int,
        val conflicts: Int,
        val repositoryState: String = "SAFE",
        /** Untracked files among [changed]. */
        val untracked: Int = 0,
        /** The clone's FETCH_HEAD time (epoch ms); 0 = never fetched / not readable. */
        val fetchedAtMs: Long = 0,
        /** How the facts were read: "files" (plain .git files), "exec" (git in cld.termux), "engine"; "" = unknown. Shown on the debug line. */
        val via: String = "",
        /** #1645 HEAD is a raw sha, not a branch ref (read from HEAD / symbolic-ref). */
        val detached: Boolean = false,
        /** #1645 stash entries (the stash reflog / git stash list). */
        val stashed: Int = 0,
    )

    enum class State { READING, CLEAN, AHEAD, BEHIND, DIRTY, NOT_CLONED, ERROR, LARGE }

    data class Status(
        val state: State,
        val probe: Probe? = null,
        val reason: String? = null,
        val readAtMs: Long = 0,
        val tookMs: Long = 0,
        val level: Level = Level.FULL,
        /** The exact directory that was read (debug line of the status window). */
        val path: String = "",
    ) {
        val terminal: Boolean get() = state != State.READING

        /** The one-line words /api/git/status and the debug log use. */
        fun label(): String = when (state) {
            State.READING -> "reading"
            State.CLEAN -> "clean"
            State.AHEAD -> "ahead ${probe?.ahead ?: 0}"
            State.BEHIND -> "behind ${probe?.behind ?: 0}" + if ((probe?.ahead ?: 0) > 0) " ahead ${probe?.ahead}" else ""
            State.DIRTY -> "dirty ${probe?.changed ?: 0}" + if ((probe?.conflicts ?: 0) > 0) " conflicts ${probe?.conflicts}" else ""
            State.NOT_CLONED -> "not cloned"
            State.ERROR -> "error(${reason ?: "unknown"})"
            State.LARGE -> "large"
        }
    }

    /** One repository to read: its stable id and its working tree. */
    data class Target(val id: String, val dir: File, /** The owner asked for this one (status window): the size guard does not apply. */ val onDemand: Boolean = false)

    private val permits = Semaphore(concurrency.coerceAtLeast(1))
    /** FULL reads are always one repository at a time, whatever the light concurrency. */
    private val fullPermits = Semaphore(1)
    private val inFlight = HashSet<String>()
    private val threadSeq = AtomicInteger()

    private val _statuses = MutableStateFlow<Map<String, Status>>(emptyMap())
    val statuses: StateFlow<Map<String, Status>> = _statuses

    /**
     * Ask for [targets]. Returns the job that ends when every probe THIS call started has
     * ended (already-fresh and already-in-flight repositories start nothing). Safe to call
     * on every composition: it is idempotent while a read is running.
     */
    fun request(targets: List<Target>, force: Boolean = false, level: Level = Level.LIGHT): Job {
        val now = clock()
        val started = mutableListOf<Job>()
        for (t in targets) {
            val claim = synchronized(inFlight) {
                if (t.id in inFlight) false
                else {
                    val cached = _statuses.value[t.id]
                    // A FULL result also satisfies a LIGHT ask; a LIGHT one does not satisfy a FULL ask.
                    val wanted = if (t.onDemand) Level.FULL else level
                    val fresh = cached != null && cached.terminal && now - cached.readAtMs < ttlMs && cached.level.ordinal >= wanted.ordinal && t.id !in stale
                    if (fresh && !force) false else { inFlight += t.id; true }
                }
            }
            if (!claim) continue
            // Keep the last value visible while re-reading; only a never-read row shows READING.
            _statuses.update { m -> if (m.containsKey(t.id)) m else m + (t.id to Status(State.READING)) }
            val epochAtAsk = epoch
            // #1259 a read cancelled BEFORE its coroutine ever ran (the page closed right after a check-all) never reaches
            // readOne's finally: without this its row stayed READING ("?") and its id stayed claimed, so every later check
            // skipped it until the process died. Only a job that never ran is cleaned here (a run one cleans itself).
            val ran = java.util.concurrent.atomic.AtomicBoolean(false)
            val job = scope.launch { ran.set(true); readOne(t, if (t.onDemand) Level.FULL else level, epochAtAsk) }
            synchronized(jobs) { jobs += job }
            job.invokeOnCompletion {
                synchronized(jobs) { jobs -= job }
                if (!ran.get()) {
                    synchronized(inFlight) { inFlight -= t.id }
                    _statuses.update { m -> if (m[t.id]?.terminal == false) m - t.id else m }
                }
            }
            started += job
        }
        return scope.launch { started.joinAll() }
    }

    private val jobs = HashSet<Job>()

    /** Bumped by [cancelAll]: a read asked before the bump never starts its probe after it, whatever the order the cancellations arrive in. */
    @Volatile private var epoch = 0

    /** The screen closed: every read still waiting its turn (or awaited) is cancelled; a probe already running cannot be killed, so its repository stays claimed until it returns. */
    fun cancelAll() { epoch++; synchronized(jobs) { jobs.toList() }.forEach { it.cancel() } }

    /** Seed terminal entries read in an earlier process (the persisted cache); never overwrites an entry already here. */
    fun seed(entries: Map<String, Status>) {
        _statuses.update { m -> entries.filter { (k, v) -> v.terminal && k !in m }.let { if (it.isEmpty()) m else m + it } }
    }

    /** Drop [id]'s cached entry so the next request reads it, e.g. after a pull. */
    fun invalidate(id: String) { synchronized(inFlight) { stale += id } }

    /** #1259 ids whose cached value is known stale (an operation changed the clone): the next request re-reads them, the value stays shown AND persisted meanwhile (it used to be zeroed, which dropped it from StatusCache). */
    private val stale = HashSet<String>()

    /** Whether [id] is claimed by a read in flight (tests and the debug route). */
    fun claimed(id: String): Boolean = synchronized(inFlight) { id in inFlight }

    private suspend fun readOne(t: Target, level: Level, epochAtAsk: Int) {
        val full = level == Level.FULL
        val queuedAt = clock()  
        var stuck = false
        try {
            val status = try {
                (if (full) fullPermits else permits).withPermit {
                    if (epochAtAsk != epoch) throw kotlinx.coroutines.CancellationException("status reads cancelled")
                    val begin = clock()
                    val done = CompletableDeferred<Result<Probe?>>()
                    val deferred = java.util.concurrent.atomic.AtomicReference<RepoCheap.Head?>(null)
                    val thread = Thread({
                        // null = not a clone (checked here, on the probe thread: a store repository is asked of cld.termux).
                        val r = runCatching {
                            if (!isRepository(t.dir)) null
                            else if (!full) lightProbe(t.dir)
                            else if (!t.onDemand && tooLarge(t.dir)) { deferred.set(RepoCheap.head(t.dir)); null }
                            else probe(t.dir)
                        }
                        // A probe that outlived its timeout frees its repository only now; the
                        // completion and the release are one step under the reader's lock.
                        synchronized(inFlight) {
                            done.complete(r)
                            if (stuckIds.remove(t.id)) inFlight -= t.id
                        }
                    }, "git-status-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
                    thread.start()
                    val r = try { withTimeoutOrNull(timeoutMs) { done.await() } } catch (c: kotlinx.coroutines.CancellationException) {
                        // Cancelled mid-probe: the thread keeps running, so the repository stays claimed until it returns.
                        synchronized(inFlight) { if (!done.isCompleted) { stuckIds += t.id; stuck = true } }
                        thread.interrupt()
                        throw c
                    }
                    val took = clock() - begin
                    when {
                        r == null -> {
                            // Not killed (JGit ignores interrupts in places) — abandoned, and the
                            // repository stays claimed until the thread returns: never two probes.
                            synchronized(inFlight) {
                                if (!done.isCompleted) { stuckIds += t.id; stuck = true }
                            }
                            thread.interrupt()
                            timedOut(t, level, took)
                        }
                        r.isFailure -> failed(t, level, r.exceptionOrNull(), took)
                        deferred.get() != null -> deferred.get()!!.let { h -> Status(State.LARGE, Probe(h.branch, h.upstream, 0, 0, 0, 0, detached = h.detached), readAtMs = clock(), tookMs = took, level = Level.FULL, path = t.dir.absolutePath) }
                        r.getOrNull() == null -> Status(State.NOT_CLONED, readAtMs = clock(), tookMs = took, level = level, path = t.dir.absolutePath)
                        else -> classify(r.getOrThrow()!!, clock(), took).copy(level = level, path = t.dir.absolutePath)
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                // The read was cancelled: never leave READING behind.
                // A cached value stays; an entry that never got a result is dropped (the row then reads "not checked yet").
                _statuses.update { m -> if (m[t.id]?.terminal == false) m - t.id else m }
                throw c
            } catch (e: Throwable) {
                // Anything else the read threw: terminal, with its message. Never swallowed.
                failed(t, level, e, clock() - queuedAt)
            }
            publish(t.id, status)
        } finally {
            if (!stuck) synchronized(inFlight) { inFlight -= t.id }
            // Hard guarantee: whatever happened above, this repository does not stay on READING.
            _statuses.update { m -> if (m[t.id]?.terminal == false) m + (t.id to Status(State.ERROR, reason = "status read ended without a result", readAtMs = 0, level = level, path = t.dir.absolutePath)) else m }
        }
    }

    private fun timedOut(t: Target, level: Level, took: Long) = Status(
        State.ERROR, reason = "timeout after " + (if (timeoutMs >= 1000) "${timeoutMs / 1000}s" else "${timeoutMs}ms") + ": status read timed out",
        readAtMs = clock(), tookMs = took, level = level, path = t.dir.absolutePath,
    )

    private fun failed(t: Target, level: Level, e: Throwable?, took: Long) = Status(
        State.ERROR, reason = e?.let { it.message ?: it.javaClass.simpleName } ?: "unknown",
        readAtMs = clock(), tookMs = took, level = level, path = t.dir.absolutePath,
    )

    /** Repositories whose probe outlived its timeout; guarded by the [inFlight] lock. */
    private val stuckIds = HashSet<String>()

    /** True while [id] has a probe running (including one abandoned past its timeout). */
    fun busy(id: String): Boolean = synchronized(inFlight) { id in inFlight }

    private fun publish(id: String, s: Status) { synchronized(inFlight) { stale -= id }; _statuses.update { it + (id to s) } }

    companion object {
        /** The precedence the row's light follows: an uncommitted change outranks the remote. */
        fun classify(p: Probe, now: Long, took: Long = 0): Status = Status(
            state = when {
                p.changed > 0 || p.conflicts > 0 -> State.DIRTY
                p.behind > 0 -> State.BEHIND
                p.ahead > 0 -> State.AHEAD
                else -> State.CLEAN
            },
            probe = p, readAtMs = now, tookMs = took,
        )
    }
}
