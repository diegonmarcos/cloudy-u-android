package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellWire
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** The privileged channel as the broker sees it (the SuperApp plugs the embedded ADB / Shizuku ladder in). */
interface BrokerChannel {
    fun name(): String
    /** Cheap: is a channel executing right now? */
    fun ready(): Boolean
    /** Run `sh -c cmd` (stdin piped from [stdin] when given); null = the channel could not serve it. */
    fun run(cmd: String, stdin: File?): String?
    /** Why no channel is ready (SuperApp-side facts: wireless debugging off, not paired, connection refused). */
    fun whyDown(): String = "no privileged channel is armed"
    /** #1550 The adb switches and pairing record; null = this channel has no adb facts (the transport reads UNKNOWN). */
    fun adbFacts(): AdbFacts? = null
    /** #1550 Run [cmd] over the embedded adb session ONLY (never the local server or Shizuku); null = no session. */
    fun adbRun(cmd: String): String? = null
}

/** #1550 [session] = the embedded client holds a socket (a flag, never trusted alone); [endpoint] "host:port" when known. */
data class AdbFacts(val adbEnabled: Boolean?, val wireless: Boolean?, val paired: Boolean, val session: Boolean, val endpoint: String?)

/** #1510 Battery: the broker holds nothing while idle. Idle = no call in flight and none for [IDLE_MS] (the fleet's 30 s floor). */
object IdlePolicy {
    const val IDLE_MS = 30_000L
    fun shouldStop(inflight: Int, lastActiveMs: Long, nowMs: Long, idleMs: Long = IDLE_MS): Boolean =
        inflight == 0 && nowMs - lastActiveMs >= idleMs
}

/**
 * #1510 The broker's brain, pure of Android: allowlist -> concurrency cap -> channel -> timeout -> audit.
 * No loop, no timer, no keep-alive: its worker threads exist only while a call runs and die [IdlePolicy.IDLE_MS] after the last.
 */
class BrokerCore(
    private val allowlist: () -> Allowlist,
    private val channel: BrokerChannel,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val tryArm: () -> Boolean = { false },
) {
    private val permits = Semaphore(maxConcurrent)
    private val stdinPermit = Semaphore(1)
    @Volatile private var inflight = 0
    @Volatile private var lastActive = 0L
    private var pool: ThreadPoolExecutor? = null

    /** True while worker threads exist (false after [reapIfIdle], and before the first call). */
    fun poolAlive(): Boolean = synchronized(this) { pool != null }

    // The real concurrency cap is [permits], not the pool: the pool must always have a free thread for a
    // permitted call, so a wedged channel (a worker stuck in channel.run that ignores interruption after a
    // timeout) can never block a fresh call behind it in a bounded queue. A cached pool with a SynchronousQueue
    // hands every submit its own thread; an orphaned wedged thread sits out of the way and dies on IDLE_MS.
    private fun pool(): ThreadPoolExecutor = synchronized(this) {
        pool ?: ThreadPoolExecutor(0, Int.MAX_VALUE, IdlePolicy.IDLE_MS, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>()) { r ->
            Thread(r, "shell-broker").apply { isDaemon = true }
        }.also { pool = it }
    }

    /** Stop the workers when idle; the next call recreates them. Returns true when it stopped them. */
    fun reapIfIdle(nowMs: Long = clock()): Boolean = synchronized(this) {
        val p = pool ?: return false
        if (!IdlePolicy.shouldStop(inflight, lastActive, nowMs)) return false
        p.shutdown(); pool = null; true
    }

    /** Audit a call this broker did not run (an unenforced legacy door). */
    fun note(caller: String, cmd: String, outcome: String, exit: Int, ms: Long, source: String) =
        audit.add(AuditLog.Entry(clock(), caller, AuditLog.redact(cmd), outcome, exit, ms, source))

    fun auditEntries(): List<AuditLog.Entry> = audit.entries()

    /** Every caller of the allowlist with its one-line allowlist summary, in declared order. */
    fun callerSummaries(): List<Pair<String, String>> = allowlist().let { a -> a.callers().map { it to a.summary(it) } }

    /** Called when a live check flips the verdict (the first check is not a change). */
    @Volatile var onChange: ((com.diegonmarcos.superapp.shellclient.ShellStatus) -> Unit)? = null
    @Volatile private var lastStatus: com.diegonmarcos.superapp.shellclient.ShellStatus? = null

    /**
     * #1521 LIVE status: a trivial command (`id -u`, must answer the shell uid 2000) through the real channel, bounded by
     * [CHECK_TIMEOUT_MS]. Armed only when that round trip succeeds now; otherwise the real reason. Cached for [STATUS_TTL_MS]
     * at most (the cache holds the verdict of a real check, down as well as up), never a flag and never "last known good".
     */
    fun status(): com.diegonmarcos.superapp.shellclient.ShellStatus {
        val now = clock()
        lastStatus?.let { if (now - it.checkedAt in 0 until STATUS_TTL_MS) return it }
        val prev = lastStatus
        return live(now).let { adb(it, now) }.also {
            lastStatus = it
            if (prev != null && (prev.armed != it.armed || prev.adb != it.adb)) runCatching { onChange?.invoke(it) }
        }
    }

    /** #1550 Drop the cached verdict (a Wireless debugging / adb setting changed): the next [status] checks again. */
    fun invalidate() { lastStatus = null }

    /** #1550 The adb transport, checked on its own: switches, pairing, then a `getprop` over the embedded session within the timeout. */
    private fun adb(s: com.diegonmarcos.superapp.shellclient.ShellStatus, now: Long): com.diegonmarcos.superapp.shellclient.ShellStatus {
        val f = runCatching { channel.adbFacts() }.getOrNull() ?: return s
        var why = ""
        val trip: Boolean = if (!f.session) { why = "no adb session"; false } else {
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "shell-broker-adb-check").apply { isDaemon = true } }
            try {
                val raw = pool.submit<String?> { channel.adbRun(ADB_CHECK_CMD) }.get(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val ok = raw != null && !raw.startsWith("ERR: ") && raw.trim().lineSequence().firstOrNull()?.trim()?.all(Char::isDigit) == true && raw.isNotBlank()
                if (!ok) why = "no answer to getprop over adb"
                ok
            } catch (e: TimeoutException) { why = "adb timeout (${CHECK_TIMEOUT_MS / 1000}s)"; false }
            catch (e: Exception) { why = "adb refused (${(e.cause ?: e).javaClass.simpleName})"; false }
            finally { pool.shutdownNow() }
        }
        val link = com.diegonmarcos.superapp.shellclient.AdbLink.classify(f.adbEnabled, f.wireless, f.paired, trip)
        val detail = if (link.up) f.endpoint.orEmpty() else why
        return s.copy(adb = link, adbDetail = detail)
    }

    private fun live(now: Long): com.diegonmarcos.superapp.shellclient.ShellStatus {
        fun down(why: String) = com.diegonmarcos.superapp.shellclient.ShellStatus(true, false, "none", "not armed: $why", now, why)
        if (!runCatching { channel.ready() }.getOrDefault(false)) return down(runCatching { channel.whyDown() }.getOrDefault("no privileged channel is armed"))
        val name = channel.name()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "shell-broker-check").apply { isDaemon = true } }
        try {
            val raw = try { pool.submit<String?> { channel.run(ShellWire.CHECK_CMD, null) }.get(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { return down("timeout: no answer within ${CHECK_TIMEOUT_MS / 1000}s") }
            catch (e: ExecutionException) { return down("connection refused (${e.cause?.javaClass?.simpleName})") }
            if (raw == null) return down("connection refused: the channel did not answer")
            if (raw.startsWith("ERR: ")) return down("connection refused: " + raw.removePrefix("ERR: ").trim().take(80))
            val uid = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            if (uid != ShellWire.SHELL_UID) return down("the channel answered uid ${uid ?: "none"}, not the shell uid ${ShellWire.SHELL_UID}")
            return com.diegonmarcos.superapp.shellclient.ShellStatus(true, true, name, "armed: $name (live check ok)", now, "")
        } finally { pool.shutdownNow() }
    }

    /** [caller] is the binder-resolved package, never a client-supplied string. */
    fun exec(caller: String, cmd: String, timeoutMs: Long, maxBytes: Int = ShellWire.EXEC_MAX_BYTES, stdin: File? = null): ShellResult {
        val t0 = clock()
        val key = allowlist().callerKey(caller)
        val d = allowlist().decide(caller, cmd)
        if (d is Allowlist.Decision.Deny) return finish(key, cmd, ShellResult.Denied(d.reason), "denied", -1, t0)
        return runDecided(key, cmd, cmd, timeoutMs, maxBytes, stdin, t0, ShellWire::clampTimeout)
    }

    /**
     * #1682 The output door: [exec] under the same allowlist decision, with the caller's own output cap ([Allowlist.outCap]) instead
     * of the provider's binder-sized one. A caller without `outMaxBytes` is refused (audited) before anything runs.
     */
    fun execOut(caller: String, cmd: String, timeoutMs: Long): ShellResult {
        val cap = allowlist().outCap(caller)
        if (cap <= 0) {
            val key = allowlist().callerKey(caller)
            return finish(key, cmd, ShellResult.Denied("$key has no output door (no outMaxBytes)"), "denied", -1, clock())
        }
        return exec(caller, cmd, timeoutMs, cap)
    }

    fun mayAudit(caller: String): Boolean = allowlist().mayAudit(caller)
    fun mayKeeper(caller: String): Boolean = allowlist().mayKeeper(caller)

    /**
     * #1512 The stdin door: stream [declaredSize] bytes of [input] to [cmd]'s stdin (`pm install -S <size>`). Default deny: the caller's
     * `stdin` rules decide ([Allowlist.decideStdin]); at most [declaredSize] bytes are read (one more would be an over-run) into a temp
     * file under [spool], which is deleted afterwards; one stdin call at a time (a transfer holds an APK's worth of disk); its own
     * timeout ceiling; audited with the byte count, never the content. The broker merges stderr into stdout itself.
     */
    fun execStdin(caller: String, cmd: String, input: InputStream, declaredSize: Long, timeoutMs: Long, spool: File,
                  maxBytes: Int = ShellWire.EXEC_MAX_BYTES): ShellResult {
        val t0 = clock()
        val key = allowlist().callerKey(caller)
        val label = "$cmd <stdin $declaredSize B>"
        val d = allowlist().decideStdin(caller, cmd, declaredSize)
        if (d is Allowlist.Decision.Deny) return finish(key, label, ShellResult.Denied(d.reason), "denied", -1, t0)
        if (!stdinPermit.tryAcquire()) return finish(key, label, ShellResult.Failed("busy: another stdin transfer is running"), "busy", -1, t0)
        var file: File? = null
        try {
            spool.mkdirs()
            sweep(spool, t0)
            val f = File.createTempFile("stdin-", ".bin", spool).also { file = it }
            val got = copyBounded(input, f, declaredSize)
            if (got != declaredSize) return finish(key, label, ShellResult.Denied("input is ${if (got > declaredSize) "longer" else "shorter"} than the declared $declaredSize bytes"), "denied", -1, t0)
            val runCmd = cmd.trim().let { if (it.endsWith(Allowlist.MERGE)) it else it + Allowlist.MERGE }
            return runDecided(key, label, runCmd, timeoutMs, maxBytes, f, t0, ShellWire::clampStdinTimeout)
        } catch (e: java.io.IOException) {
            return finish(key, label, ShellResult.Failed("could not read the input: ${e.javaClass.simpleName}"), "error", -1, t0)
        } finally {
            file?.delete()
            stdinPermit.release()
        }
    }

    private fun finish(key: String, auditCmd: String, r: ShellResult, outcome: String, exit: Int, t0: Long, source: String = ""): ShellResult {
        audit.add(AuditLog.Entry(t0, key, AuditLog.redact(auditCmd), outcome, exit, clock() - t0, source)); return r
    }

    /** Past the allowlist: concurrency cap -> channel -> timeout -> audit. */
    private fun runDecided(key: String, auditCmd: String, cmd: String, timeoutMs: Long, maxBytes: Int, stdin: File?, t0: Long,
                    clamp: (Long) -> Long): ShellResult {
        fun done(r: ShellResult, outcome: String, exit: Int, source: String = ""): ShellResult = finish(key, auditCmd, r, outcome, exit, t0, source)
        if (!permits.tryAcquire()) return done(ShellResult.Failed("busy: $maxConcurrent calls already running"), "busy", -1)
        synchronized(this) { inflight++; lastActive = t0 }
        // Exactly one release per acquired permit: the worker returns it when the channel returns, and the
        // timeout path returns it when we give up. A wedged channel that never returns (and may ignore the
        // cancel/interrupt) must not keep its slot, or three such calls wedge the whole broker ("busy: 3
        // calls already running") until the process dies. Guarded so a late worker return is a no-op.
        val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) { synchronized(this) { inflight--; lastActive = clock() }; permits.release() } }
        var submitted = false
        try {
            if (!runCatching { channel.ready() }.getOrDefault(false) && !(runCatching { tryArm() }.getOrDefault(false) && channel.ready())) {
                return done(ShellResult.NeedsArm, "needs_arm", -1)
            }
            val src = channel.name()
            val wrapped = "$cmd; printf '\\n$MARK%s\\n' \$?"
            val f: Future<String?> = pool().submit<String?> { try { channel.run(wrapped, stdin) } finally { release() } }
            submitted = true
            val raw = try { f.get(clamp(timeoutMs), TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { f.cancel(true); release(); return done(ShellResult.Failed("timed out after ${timeoutMs / 1000}s", true), "timeout", -1, src) }
            catch (e: ExecutionException) { return done(ShellResult.Failed("channel threw ${e.cause?.javaClass?.simpleName}"), "error", -1, src) }
            if (raw == null) return done(ShellResult.NeedsArm, "needs_arm", -1, src)
            val r = parse(raw, src, maxBytes)
            return done(r, if (r.exitCode == 0) "ok" else "exit", r.exitCode, src)
        } finally {
            if (!submitted) release()
        }
    }

    /** Pure: copy at most [limit] + 1 bytes of [input] into [out] and return the count read (> [limit] = an over-run). */
    private fun copyBounded(input: InputStream, out: File, limit: Long): Long {
        var total = 0L
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (total <= limit) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), limit + 1 - total).toInt())
                if (n < 0) break
                o.write(buf, 0, n); total += n
            }
        }
        return total
    }

    /** A crashed process leaves its spool file behind: drop spool files older than [SPOOL_STALE_MS]. */
    private fun sweep(spool: File, now: Long) {
        spool.listFiles()?.forEach { if (now - it.lastModified() > SPOOL_STALE_MS) it.delete() }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 3
        const val STATUS_TTL_MS = 3_000L
        const val CHECK_TIMEOUT_MS = 4_000L
        /** #1550 the adb transport round trip: answers the SDK level, digits only. */
        const val ADB_CHECK_CMD = "getprop ro.build.version.sdk"
        private const val SPOOL_STALE_MS = 60 * 60 * 1000L
        private const val MARK = "__SB_EXIT__"
        private val EXIT = Regex("""\n?$MARK(-?\d+)\s*$""")

        /** Pure: split the channel's text into stdout / stderr / exit code, capped at [maxBytes]. */
        fun parse(raw: String, source: String, maxBytes: Int): ShellResult.Ok {
            val m = EXIT.find(raw)
            val body = if (m != null) raw.substring(0, m.range.first) else raw
            val exit = m?.groupValues?.get(1)?.toIntOrNull() ?: -1
            // The channel merges stderr into stdout; a channel-level failure arrives as "ERR: ..." with nothing else.
            val chanErr = body.startsWith("ERR: ")
            val out = if (chanErr) "" else body
            val err = if (chanErr) body.removePrefix("ERR: ").trim() else ""
            val capped = if (out.length > maxBytes) out.take(maxBytes) + "\n[truncated at $maxBytes]" else out
            return ShellResult.Ok(if (chanErr && exit == -1) 1 else exit, capped, err, source)
        }
    }
}
