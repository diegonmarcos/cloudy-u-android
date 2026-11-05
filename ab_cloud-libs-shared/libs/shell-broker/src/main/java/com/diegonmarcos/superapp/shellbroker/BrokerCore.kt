package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellWire
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The privileged channel as the broker sees it (the SuperApp plugs the embedded ADB / Shizuku ladder in). */
interface BrokerChannel {
    fun name(): String
    /** Cheap: is a channel executing right now? */
    fun ready(): Boolean
    /** Run `sh -c cmd` (stdin piped from [stdin] when given); null = the channel could not serve it. */
    fun run(cmd: String, stdin: File?): String?
}

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
    @Volatile private var inflight = 0
    @Volatile private var lastActive = 0L
    private var pool: ThreadPoolExecutor? = null

    /** True while worker threads exist (false after [reapIfIdle], and before the first call). */
    fun poolAlive(): Boolean = synchronized(this) { pool != null }

    private fun pool(): ThreadPoolExecutor = synchronized(this) {
        pool ?: ThreadPoolExecutor(maxConcurrent, maxConcurrent, IdlePolicy.IDLE_MS, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()) { r ->
            Thread(r, "shell-broker").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }.also { pool = it }
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

    fun status(): com.diegonmarcos.superapp.shellclient.ShellStatus {
        val ready = runCatching { channel.ready() }.getOrDefault(false)
        return com.diegonmarcos.superapp.shellclient.ShellStatus(true, ready, if (ready) channel.name() else "none",
            if (ready) "armed: ${channel.name()}" else "not armed: arm it in the SuperApp")
    }

    /** [caller] is the binder-resolved package, never a client-supplied string. */
    fun exec(caller: String, cmd: String, timeoutMs: Long, maxBytes: Int = ShellWire.EXEC_MAX_BYTES, stdin: File? = null): ShellResult {
        val t0 = clock()
        val key = allowlist().callerKey(caller)
        fun done(r: ShellResult, outcome: String, exit: Int, source: String = ""): ShellResult {
            audit.add(AuditLog.Entry(t0, key, AuditLog.redact(cmd), outcome, exit, clock() - t0, source)); return r
        }
        val d = allowlist().decide(caller, cmd)
        if (d is Allowlist.Decision.Deny) return done(ShellResult.Denied(d.reason), "denied", -1)
        if (!permits.tryAcquire()) return done(ShellResult.Failed("busy: $maxConcurrent calls already running"), "busy", -1)
        synchronized(this) { inflight++; lastActive = t0 }
        fun release() { synchronized(this) { inflight--; lastActive = clock() }; permits.release() }
        var submitted = false
        try {
            if (!runCatching { channel.ready() }.getOrDefault(false) && !(runCatching { tryArm() }.getOrDefault(false) && channel.ready())) {
                return done(ShellResult.NeedsArm, "needs_arm", -1)
            }
            val src = channel.name()
            val wrapped = "$cmd; printf '\\n$MARK%s\\n' \$?"
            // The worker returns the permit when the CHANNEL returns, so a wedged channel keeps its slot: that is the cap.
            val f: Future<String?> = pool().submit<String?> { try { channel.run(wrapped, stdin) } finally { release() } }
            submitted = true
            val raw = try { f.get(ShellWire.clampTimeout(timeoutMs), TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { f.cancel(true); return done(ShellResult.Failed("timed out after ${timeoutMs / 1000}s", true), "timeout", -1, src) }
            catch (e: ExecutionException) { return done(ShellResult.Failed("channel threw ${e.cause?.javaClass?.simpleName}"), "error", -1, src) }
            if (raw == null) return done(ShellResult.NeedsArm, "needs_arm", -1, src)
            val r = parse(raw, src, maxBytes)
            return done(r, if (r.exitCode == 0) "ok" else "exit", r.exitCode, src)
        } finally {
            if (!submitted) release()
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 3
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
