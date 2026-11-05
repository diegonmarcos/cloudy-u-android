package com.diegonmarcos.superapp.headless

/** #1498 limits and the idle policy, in the manner of #1487 KeepAlivePolicy: no job, no service; a dark screen does no work. */
object HeadlessPolicy {
    const val MAX_SESSIONS_DEFAULT = 3
    const val IDLE_STOP_MS_DEFAULT = 120_000L
    const val CALL_TIMEOUT_MS_DEFAULT = 30_000L
    const val SWEEP_MS = 15_000L

    fun clampSessions(n: Int?): Int = (n ?: MAX_SESSIONS_DEFAULT).coerceIn(1, 8)
    fun clampIdleMs(ms: Long?): Long = (ms ?: IDLE_STOP_MS_DEFAULT).coerceIn(15_000L, 30 * 60_000L)
    fun clampCallMs(ms: Long?): Long = (ms ?: CALL_TIMEOUT_MS_DEFAULT).coerceIn(1_000L, HeadlessApi.MAX_TIMEOUT_MS.toLong())

    /** A session idle for at least [idleMs] is closed. */
    fun sessionExpired(now: Long, lastUsed: Long, idleMs: Long): Boolean = now - lastUsed >= idleMs

    /** The service stops when no session is open and nothing was served for [idleMs]. */
    fun serviceShouldStop(now: Long, lastActivity: Long, openSessions: Int, idleMs: Long): Boolean = openSessions == 0 && now - lastActivity >= idleMs

    /** No screen-off work: a command is refused (denied) while the screen is dark. */
    fun mayRun(screenOn: Boolean): Boolean = screenOn

    /** A new session needs a free slot. */
    fun mayOpen(open: Int, max: Int): Boolean = open < max
}
