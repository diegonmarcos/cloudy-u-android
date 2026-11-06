package com.diegonmarcos.superapp.appstore.dl

import com.diegonmarcos.superapp.devtools.RetryBackoff
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** #1517 The only token that lets a caller stop engine work. Created in a click handler, never in a lifecycle callback (EngineGuardTest). */
class UserAction private constructor(val source: String) {
    companion object { fun tap(source: String) = UserAction(source) }
}

/**
 * #1517 THE ONE DOWNLOAD ENGINE. One instance per process, owned by [com.diegonmarcos.superapp.appstore.StoreEngine], outliving every
 * fragment, tab and recomposition. The UI only OBSERVES [state] and ASKS ([enqueue]); asking again for an id the engine already
 * owns joins that job, it never restarts, duplicates or cancels it. The only ways to stop a job are [pause], [cancel] and
 * [cancelAll], and each needs a [UserAction]: a tap, never a resume, a tab switch or an update check.
 *
 * Per job: mirrors in the declared order (mesh, release, ghcr); on a mirror, soft failures (I/O, 5xx, timeouts, sha mismatch)
 * retry with [backoff] (the #1502 30 s floor, doubling) up to [maxRetries], then the next mirror; hard ones (404, 403, DNS, a
 * mirror that cannot resolve) go to the next mirror at once; the last mirror failing is FAILED, and resume() tries again. The
 * `.part` file and its ETag/Last-Modified survive pause, failure, process death and reboot: state is [store]d on every change.
 * Nothing finishes unverified: the bytes must hash to the declared sha256 (or, for a mirror that publishes none, match the declared
 * length) before [onVerified] hands the file to the install path.
 *
 * Pure JVM except for what the host injects: a fake HTTP server tests it end to end.
 */
class DownloadEngine(
    private val store: DownloadStore,
    val cache: DownloadCache,
    private val mirrorsFor: (DlJob) -> List<DlMirror>,
    val maxConcurrent: Int = DEFAULT_CONCURRENCY,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val backoff: (failures: Int) -> Long = { RetryBackoff.afterFailures(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onVerified: (DlJob, File) -> Unit = { _, _ -> },
    private val onFailed: (DlJob) -> Unit = { _ -> },
    private val onBusy: () -> Unit = {},
    private val onIdle: () -> Unit = {},
) {
    companion object {
        /** The declared defaults (build.json::downloads overrides them through the host). */
        const val DEFAULT_CONCURRENCY = 2
        const val DEFAULT_MAX_RETRIES = 3
        private const val STOP_NONE = 0
        private const val STOP_PAUSE = 1
        private const val STOP_CANCEL = 2
    }

    private class Control { @Volatile var stop = STOP_NONE }

    private val lk = ReentrantLock()
    private val changed = lk.newCondition()
    private val jobs = LinkedHashMap<String, DlJob>()
    private val controls = HashMap<String, Control>()
    private val running = HashSet<String>()
    private var busy = false
    private val _state = MutableStateFlow<List<DlJob>>(emptyList())
    /** Every job, newest state: the UI collects this and nothing else. */
    val state: StateFlow<List<DlJob>> = _state

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dl-engine").apply { isDaemon = true } }
    // The timer thread exists only while a retry is waiting: it times out when idle (no keep-alive after the queue empties).
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "dl-engine-timer").apply { isDaemon = true } }).apply {
        setKeepAliveTime(2, TimeUnit.SECONDS); allowCoreThreadTimeOut(true); removeOnCancelPolicy = true
    }

    // ── asking ────────────────────────────────────────────────────────────────

    /**
     * Load the persisted queue (what a restart, a kill or a reboot left) and carry on: interrupted jobs go back to QUEUED.
     * [mayResume] lets the host hold back what must not run now (#1502: a background-started job only runs with Auto-update ON
     * on an unmetered network); a held job is PAUSED with its `.part` kept.
     */
    fun start(mayResume: (DlJob) -> Boolean = { true }) = lk.withLock {
        for (j in store.load()) {
            if (jobs.containsKey(j.id)) continue
            val part = cache.partFile(j.namePrefix)
            val have = if (part.isFile) part.length() else 0L
            var st = if (j.state == DlState.RUNNING || j.state == DlState.VERIFYING) DlState.QUEUED else j.state
            if (st == DlState.QUEUED && !mayResume(j)) st = DlState.PAUSED
            jobs[j.id] = j.copy(state = st, bytes = if (st.active) have else j.bytes, nextTryAt = 0L, bytesPerSec = 0L)
        }
        publish(true)
        pump()
    }

    /**
     * Ask for [spec]. Idempotent: an id the engine still owns (queued, running, verifying, paused) is JOINED, never restarted or
     * duplicated; only a user-started ask resumes a paused one, and a higher priority is kept. A finished, failed or cancelled id
     * starts again and resumes from whatever `.part` is on disk.
     */
    fun enqueue(spec: DlSpec): DlJob = lk.withLock {
        val ex = jobs[spec.id]
        if (ex != null && ex.state.active) {
            var j = ex
            if (spec.priority > j.priority) j = j.copy(priority = spec.priority)
            if (spec.userStarted && j.state == DlState.PAUSED) j = j.copy(state = DlState.QUEUED, nextTryAt = 0L)
            jobs[spec.id] = j
            publish(true); pump()
            return@withLock j
        }
        val part = cache.partFile(spec.namePrefix)
        val keep = ex != null && ex.state == DlState.FAILED
        val j = DlJob(
            id = spec.id, pkg = spec.pkg, versionCode = spec.versionCode, label = spec.label, namePrefix = spec.namePrefix,
            priority = spec.priority, userStarted = spec.userStarted, addedAt = now(),
            bytes = if (part.isFile) part.length() else 0L,
            etag = if (keep) ex!!.etag else "", lastModified = if (keep) ex!!.lastModified else "",
        )
        jobs.remove(spec.id)
        jobs[spec.id] = j
        publish(true); pump()
        j
    }

    fun job(id: String): DlJob? = lk.withLock { jobs[id] }

    /** Block until [id] is no longer working (done, failed, cancelled or paused), calling [onTick] about every [tickMs] with its state. */
    fun await(id: String, tickMs: Long = 300, onTick: (DlJob) -> Unit = {}): DlJob? {
        while (true) {
            val j = lk.withLock {
                val cur = jobs[id]
                if (cur == null || !cur.state.active || cur.state == DlState.PAUSED) return cur
                changed.await(tickMs, TimeUnit.MILLISECONDS)
                jobs[id]
            } ?: return null
            onTick(j)
        }
    }

    // ── the user's controls ────────────────────────────────────────────────────

    fun pause(id: String, by: UserAction) = lk.withLock {
        val j = jobs[id] ?: return@withLock
        if (j.state != DlState.QUEUED && j.state != DlState.RUNNING && j.state != DlState.VERIFYING) return@withLock
        controls[id]?.stop = STOP_PAUSE
        jobs[id] = j.copy(state = DlState.PAUSED, bytesPerSec = 0L)
        publish(true); pump()
    }

    /** Not a stop: it only lets a paused, failed or cancelled job try again (a failed one from its first mirror, resuming its `.part`). */
    fun resume(id: String) = lk.withLock {
        val j = jobs[id] ?: return@withLock
        if (j.state != DlState.PAUSED && j.state != DlState.FAILED && j.state != DlState.CANCELLED) return@withLock
        val fresh = if (j.state == DlState.PAUSED) j.copy(state = DlState.QUEUED, nextTryAt = 0L)
            else j.copy(state = DlState.QUEUED, failures = 0, mirror = 0, nextTryAt = 0L, error = "", trail = "")
        jobs[id] = fresh
        publish(true); pump()
    }

    fun cancel(id: String, by: UserAction) = lk.withLock {
        val j = jobs[id] ?: return@withLock
        if (!j.state.active && j.state != DlState.FAILED) return@withLock
        if (running.contains(id)) controls[id]?.stop = STOP_CANCEL   // the worker deletes the part as it leaves
        else cache.partFile(j.namePrefix).delete()
        jobs[id] = j.copy(state = DlState.CANCELLED, bytes = 0L, bytesPerSec = 0L, etag = "", lastModified = "")
        publish(true); pump()
    }

    /** The Store's own Cancel button (one verb for everything in flight). */
    fun cancelAll(by: UserAction) { for (j in lk.withLock { jobs.values.filter { it.state.active } }) cancel(j.id, by) }

    fun setPriority(id: String, priority: Int) = lk.withLock {
        val j = jobs[id] ?: return@withLock
        jobs[id] = j.copy(priority = priority)
        publish(true); pump()
    }

    /** Forget finished, failed and cancelled rows (the list's Clear). Never touches a live job or a file. */
    fun clearFinished() = lk.withLock {
        val gone = jobs.values.filter { !it.state.active }.map { it.id }
        gone.forEach { jobs.remove(it) }
        if (gone.isNotEmpty()) { publish(true) }
    }

    /** Finished files the engine still lists (the pruner never touches them; a `.part` is never a prune candidate at all). */
    fun protectedFiles(): Set<String> = lk.withLock { jobs.values.map { it.file }.filter { it.isNotEmpty() }.toSet() }

    // ── scheduling ─────────────────────────────────────────────────────────────

    /** Start as many queued jobs as the concurrency cap allows, highest priority first, then oldest. Caller holds the lock. */
    private fun pump() {
        val t = now()
        while (running.size < maxConcurrent) {
            val next = jobs.values.filter { it.state == DlState.QUEUED && it.nextTryAt <= t && !running.contains(it.id) }
                .sortedWith(compareByDescending<DlJob> { it.priority }.thenBy { it.addedAt }).firstOrNull() ?: break
            val ctl = Control()
            controls[next.id] = ctl
            running += next.id
            jobs[next.id] = next.copy(state = DlState.RUNNING, error = if (next.failures == 0) "" else next.error)
            pool.execute { work(next.id, ctl) }
        }
        val wake = jobs.values.filter { it.state == DlState.QUEUED && it.nextTryAt > t }.minOfOrNull { it.nextTryAt }
        if (wake != null) timer.schedule(Runnable { lk.withLock { pump() } }, wake - t + 1, TimeUnit.MILLISECONDS)
        publish(true)
    }

    /** Publish to observers, wake waiters, persist when [save], and tell the host when the engine goes busy or idle. Caller holds the lock. */
    private fun publish(save: Boolean) {
        _state.value = jobs.values.toList()
        changed.signalAll()
        if (save) runCatching { store.save(jobs.values.toList()) }
        val work = jobs.values.any { it.state == DlState.QUEUED || it.state == DlState.RUNNING || it.state == DlState.VERIFYING }
        if (work != busy) { busy = work; if (work) runCatching { onBusy() } else runCatching { onIdle() } }
    }

    // ── one job ────────────────────────────────────────────────────────────────

    /** Apply [f] to the job only while this worker still owns it (a pause or cancel took it away the moment it was asked). */
    private fun update(id: String, ctl: Control, save: Boolean = false, f: (DlJob) -> DlJob) = lk.withLock {
        val j = jobs[id] ?: return@withLock
        if (ctl.stop != STOP_NONE || (j.state != DlState.RUNNING && j.state != DlState.VERIFYING)) return@withLock
        jobs[id] = f(j)
        publish(save)
    }

    private fun work(id: String, ctl: Control) {
        try {
            run(id, ctl)
        } catch (t: Throwable) {
            update(id, ctl, true) { it.copy(state = DlState.FAILED, error = "internal: ${t.message ?: t.javaClass.simpleName}", bytesPerSec = 0L) }
            job(id)?.takeIf { it.state == DlState.FAILED }?.let { runCatching { onFailed(it) } }
        } finally {
            lk.withLock {
                if (ctl.stop == STOP_CANCEL) job(id)?.let { cache.partFile(it.namePrefix).delete() }
                running -= id
                if (controls[id] === ctl) controls -= id
                pump()
            }
        }
    }

    private fun run(id: String, ctl: Control) {
        val first = job(id) ?: return
        val mirrors = try { mirrorsFor(first) } catch (t: Throwable) { emptyList() }
        if (mirrors.isEmpty()) return fail(id, ctl, "no download source for ${first.label}")
        val j = job(id) ?: return
        if (j.mirror >= mirrors.size) return fail(id, ctl, j.trail.ifEmpty { "every source failed" })
        val m = mirrors[j.mirror]
        val src = try { m.resolve() } catch (t: Throwable) {
            // A leg that could not even resolve (mesh not configured, offline) wrote nothing: the part on disk is still resumable.
            return advance(id, ctl, mirrors, "${m.name}: ${t.message ?: t.javaClass.simpleName}", dropPart = false)
        }
        update(id, ctl) { it.copy(mirrorName = m.name, sha256 = src.sha256 ?: "", total = if (src.bytes > 0) src.bytes else it.total) }
        val cur = job(id) ?: return
        val part = cache.partFile(cur.namePrefix)
        // A finished copy of exactly this build is the answer; nothing is fetched.
        if (src.sha256 != null) cache.hit(cur.namePrefix, cur.pkg, cur.versionCode, src.sha256, now())?.let { return finish(id, ctl, it, src) }

        var lastSpeedAt = now(); var lastSpeedBytes = cur.bytes; var lastSave = lastSpeedAt; var lastPublish = 0L
        try {
            RangeFetch.fetch(
                source = src, part = part, etag = cur.etag, lastModified = cur.lastModified,
                stop = { ctl.stop != STOP_NONE },
                onValidators = { e, l -> update(id, ctl, true) { it.copy(etag = e, lastModified = l) } },
                onProgress = { written, total ->
                    val t = now()
                    if (t - lastPublish >= 200) {
                        lastPublish = t
                        var bps = job(id)?.bytesPerSec ?: 0L
                        if (t - lastSpeedAt >= 1000) {
                            val inst = (written - lastSpeedBytes) * 1000 / (t - lastSpeedAt).coerceAtLeast(1)
                            bps = if (bps == 0L) inst else (bps * 6 + inst * 4) / 10
                            lastSpeedAt = t; lastSpeedBytes = written
                        }
                        val saveNow = t - lastSave >= 2000
                        if (saveNow) lastSave = t
                        update(id, ctl, saveNow) { it.copy(bytes = written, total = if (total > 0) total else it.total, bytesPerSec = bps) }
                    }
                },
            )
        } catch (s: RangeFetch.StopSignal) {
            return settleStop(id, ctl)
        } catch (h: RangeFetch.Http) {
            val hard = h.code == 401 || h.code == 403 || h.code == 404 || h.code == 410
            return if (hard) advance(id, ctl, mirrors, "${m.name}: ${h.message}") else soft(id, ctl, mirrors, m, h.message ?: "HTTP ${h.code}")
        } catch (u: UnknownHostException) {
            return advance(id, ctl, mirrors, "${m.name}: cannot resolve ${u.message}")
        } catch (e: java.io.IOException) {
            return soft(id, ctl, mirrors, m, e.message ?: e.javaClass.simpleName)
        }
        if (ctl.stop != STOP_NONE) return settleStop(id, ctl)

        // VERIFY before anything can install it: the bytes must be the published bytes.
        update(id, ctl, true) { it.copy(state = DlState.VERIFYING, bytes = part.length(), bytesPerSec = 0L) }
        val actual = DownloadCache.sha256Of(part)
        if (src.sha256 != null && !actual.equals(src.sha256, ignoreCase = true)) {
            part.delete()
            update(id, ctl) { it.copy(etag = "", lastModified = "", bytes = 0L, state = DlState.RUNNING) }
            return soft(id, ctl, mirrors, m, "sha256 mismatch: got ${actual.take(12)}, declared ${src.sha256.take(12)}")
        }
        if (src.sha256 == null && !(src.bytes > 0 && part.length() == src.bytes)) {
            part.delete()
            update(id, ctl) { it.copy(etag = "", lastModified = "", bytes = 0L, state = DlState.RUNNING) }
            return advance(id, ctl, mirrors, "${m.name}: no sha256 and no declared length to verify against")
        }
        val dest = cache.finalFile(cur.namePrefix, cur.pkg, cur.versionCode, actual)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
        finish(id, ctl, dest, src)
    }

    private fun finish(id: String, ctl: Control, file: File, src: DlSource) {
        val j = job(id) ?: return
        if (ctl.stop != STOP_NONE) return
        runCatching { onVerified(j, file) }.onFailure { return fail(id, ctl, "after download: ${it.message}") }
        update(id, ctl, true) {
            it.copy(state = DlState.DONE, file = file.name, bytes = file.length(), total = file.length(), error = "", trail = "",
                failures = 0, bytesPerSec = 0L, sha256 = src.sha256 ?: it.sha256)
        }
    }

    private fun settleStop(id: String, ctl: Control) {
        // pause()/cancel() already moved the job's state; the worker only has to leave (and the finally block cleans a cancel).
        lk.withLock { publish(true) }
    }

    /** A flaky answer: retry this mirror after the backoff, resuming the part; out of retries, next mirror. */
    private fun soft(id: String, ctl: Control, mirrors: List<DlMirror>, m: DlMirror, why: String) {
        val j = job(id) ?: return
        val n = j.failures + 1
        if (n >= maxRetries) return advance(id, ctl, mirrors, "${m.name}: $why (after $n tries)")
        val part = cache.partFile(j.namePrefix)
        update(id, ctl, true) {
            it.copy(state = DlState.QUEUED, failures = n, nextTryAt = now() + backoff(n), error = "${m.name}: $why",
                bytes = if (part.isFile) part.length() else 0L, bytesPerSec = 0L)
        }
    }

    /** Give up on this mirror (a part from another origin is not trusted) and go to the next, or fail when none is left. */
    private fun advance(id: String, ctl: Control, mirrors: List<DlMirror>, why: String, dropPart: Boolean = true) {
        val j = job(id) ?: return
        val trail = listOf(j.trail, why).filter { it.isNotEmpty() }.joinToString(" | ")
        if (j.mirror + 1 >= mirrors.size) return fail(id, ctl, trail)
        if (dropPart) cache.partFile(j.namePrefix).delete()
        update(id, ctl, true) {
            it.copy(state = DlState.QUEUED, mirror = it.mirror + 1, failures = 0, nextTryAt = 0L, trail = trail, error = why,
                bytes = if (dropPart) 0L else it.bytes, etag = if (dropPart) "" else it.etag, lastModified = if (dropPart) "" else it.lastModified, bytesPerSec = 0L)
        }
    }

    private fun fail(id: String, ctl: Control, why: String) {
        update(id, ctl, true) { it.copy(state = DlState.FAILED, error = why, trail = why, bytesPerSec = 0L) }
        job(id)?.takeIf { it.state == DlState.FAILED }?.let { runCatching { onFailed(it) } }
    }
}
