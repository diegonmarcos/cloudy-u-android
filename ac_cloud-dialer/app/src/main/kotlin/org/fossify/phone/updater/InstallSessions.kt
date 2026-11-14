package org.fossify.phone.updater

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** One of OUR PackageInstaller sessions, as the pure policy sees it (no framework types, so a JVM test can fake it). */
data class SessionView(
    val id: Int,
    val pkg: String?,
    val committed: Boolean,
    /** A client currently holds it open: a write in flight. */
    val active: Boolean,
    val createdMs: Long,
)

/** The three PackageInstaller calls the session policy needs. [create] may throw the platform's cap error. */
interface SessionPort {
    fun mine(): List<SessionView>
    fun abandon(id: Int)
    fun create(): Int
}

/**
 * #1735 THE ONE PLACE install sessions are counted, reaped and opened.
 *
 * Android allows an installer without INSTALL_PACKAGES at most 50 open sessions per UID and refuses every further
 * createSession with "Too many active sessions for UID n". Open means created and not yet finished: an uncommitted
 * one (a write that died) AND a committed one still waiting on a confirmation tap both count. Three leaks fed the
 * cap on the phone: (1) sessions created and never abandoned when anything between create and commit threw;
 * (2) committed sessions held by unanswered prompts, which the old reaper never touched, so once they alone reached
 * the cap nothing could free a slot; (3) a retry that met the cap error just tried again, five times, into the same wall.
 *
 * Pure over [SessionPort]; the Android wrapper is [AndroidPort]. [UpdateInstaller] goes
 * through here.
 */
// Dialer copy of the fleet policy in libs:updater (install/InstallSessions.kt); the dialer links no shared updater lib. Keep them identical below this line.
object InstallSessions {
    private const val TAG = "InstallSessions"
    /** Android's own limit for an installer without INSTALL_PACKAGES is 50. */
    const val NEAR_CAP = 40
    /** Slots to free when we are at the cap. */
    const val HEADROOM = 8
    /** The held-session count at which installs are budgeted to zero and the reaper starts working. */
    const val BUDGET_CAP = NEAR_CAP - HEADROOM
    const val STALE_SESSION_MS = 60L * 60L * 1000L
    /** Waits between attempts after the platform's cap error, each preceded by an aggressive reap. */
    val CAP_BACKOFF_MS = longArrayOf(500L, 2_000L, 6_000L)

    fun isCapError(t: Throwable?): Boolean {
        var c = t
        var depth = 0
        while (c != null && depth++ < 6) {
            if (c.message.orEmpty().contains("Too many active sessions", ignoreCase = true)) return true
            c = c.cause
        }
        return false
    }

    /**
     * Pure: which sessions to abandon. [aggressive] (the cap was hit, or the held count is at [BUDGET_CAP]) also reclaims
     * COMMITTED sessions, oldest first: a committed session that has sat unanswered while we are out of slots is the install
     * that is blocking every other one, and its abort answer is swallowed through [SupersededSessions] so it is not
     * reported as a failure. Never an [SessionView.active] one (a write in flight) and never one of the packages in [keep].
     */
    fun victims(mine: List<SessionView>, now: Long, aggressive: Boolean, keep: Set<String> = emptySet()): List<SessionView> {
        val idle = mine.filter { !it.active && (it.pkg == null || it.pkg !in keep) }
        if (idle.isEmpty()) return emptyList()
        val atCap = mine.size >= BUDGET_CAP
        if (!atCap && !aggressive) return idle.filter { !it.committed && it.createdMs > 0 && now - it.createdMs > STALE_SESSION_MS }
        val uncommitted = idle.filter { !it.committed }.sortedBy { it.createdMs }
        // Uncommitted leftovers always go first and all of them: they are never an install, only a leak.
        val out = ArrayList(uncommitted)
        if (aggressive) {
            val committed = idle.filter { it.committed }.sortedBy { it.createdMs }
            val still = (mine.size - out.size) - (BUDGET_CAP - HEADROOM)
            if (still > 0) out += committed.take(still)
        }
        return out
    }

    /** Abandon [victims] of [port]; returns how many went. Committed ones are recorded through [onSuperseded] first. */
    fun reap(port: SessionPort, now: Long, aggressive: Boolean, keep: Set<String> = emptySet(),
             onSuperseded: (Int) -> Unit = {}, onUndo: (Int) -> Unit = {}): Int {
        var reaped = 0
        for (v in victims(runCatching { port.mine() }.getOrDefault(emptyList()), now, aggressive, keep)) {
            if (v.committed) onSuperseded(v.id)
            if (runCatching { port.abandon(v.id) }.isSuccess) reaped++ else if (v.committed) onUndo(v.id)
        }
        if (reaped > 0) Log.w(TAG, "abandoned $reaped install session(s) (aggressive=$aggressive)")
        return reaped
    }

    /**
     * Open a session: reap our stale ones first; when the platform still answers with the cap error, reap
     * aggressively and wait ([CAP_BACKOFF_MS]) before the next try, never a blind retry. Throws the last error.
     */
    fun open(port: SessionPort, now: () -> Long = System::currentTimeMillis, sleep: (Long) -> Unit = Thread::sleep,
             keep: Set<String> = emptySet(), onSuperseded: (Int) -> Unit = {}, onUndo: (Int) -> Unit = {}): Int {
        reap(port, now(), aggressive = false, keep = keep, onSuperseded = onSuperseded, onUndo = onUndo)
        // Still at the budget after the uncommitted leftovers went: what remains is committed installs nobody answered.
        if (runCatching { port.mine().size }.getOrDefault(0) >= BUDGET_CAP)
            reap(port, now(), aggressive = true, keep = keep, onSuperseded = onSuperseded, onUndo = onUndo)
        var attempt = 0
        while (true) {
            try {
                return port.create()
            } catch (t: Throwable) {
                if (!isCapError(t) || attempt >= CAP_BACKOFF_MS.size) throw t
                Log.w(TAG, "session cap hit (attempt ${attempt + 1}): reaping and backing off", t)
                reap(port, now(), aggressive = true, keep = keep, onSuperseded = onSuperseded, onUndo = onUndo)
                sleep(CAP_BACKOFF_MS[attempt++])
            }
        }
    }

    /**
     * Run [block] on a freshly opened session id, and ABANDON it unless [block] returned true (= committed). In a finally,
     * so a throw, a cancellation (InterruptedException / CancellationException) or a timeout between create and commit can
     * never leave the session open. The throw still propagates.
     */
    fun <T> guarded(port: SessionPort, id: Int, block: () -> Pair<Boolean, T>): T {
        var committed = false
        try {
            val (c, r) = block()
            committed = c
            return r
        } finally {
            if (!committed) runCatching { port.abandon(id) }.onFailure { Log.w(TAG, "could not abandon session $id", it) }
        }
    }

    /** The Android port over the real [PackageInstaller]. */
    class AndroidPort(private val installer: PackageInstaller, private val params: PackageInstaller.SessionParams? = null) : SessionPort {
        override fun mine(): List<SessionView> = installer.mySessions.map {
            SessionView(it.sessionId, it.appPackageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.isCommitted else false,
                it.isActive,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.createdMillis else 0L)
        }
        override fun abandon(id: Int) = installer.abandonSession(id)
        override fun create(): Int = installer.createSession(requireNotNull(params) { "no SessionParams" })
    }

    /** How many sessions this app holds open right now (-1 unreadable). */
    fun openCount(ctx: Context): Int =
        runCatching { ctx.packageManager.packageInstaller.mySessions.size }.getOrDefault(-1)

    /**
     * Reap on demand (Store start, the diagnostics button). [aggressive] also drops committed-and-unanswered sessions
     * (their abort answer is swallowed). Returns the number abandoned.
     */
    fun reapOwn(ctx: Context, aggressive: Boolean): Int = runCatching {
        reap(AndroidPort(ctx.packageManager.packageInstaller), System.currentTimeMillis(), aggressive,
            )
    }.getOrDefault(0)
}
