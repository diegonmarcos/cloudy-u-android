package com.diegonmarcos.superapp.devtools

/**
 * #1502 The one rule for every retry, reconnect and restart loop in the fleet's shared code: a pass never repeats sooner than
 * [MIN_MS] (30 s), the wait doubles with each consecutive failure up to [MAX_MS], and a run that stayed healthy for at least
 * [MIN_MS] does not reset it below the floor. A loop with no wait, or a 1 s one (the LogPipe restart was one: a logcat that exits
 * at once was respawned every second, for ever), is a battery hot loop. Guard: RetryBackoffTest, which also scans the sources.
 */
object RetryBackoff {
    const val MIN_MS = 30_000L
    const val MAX_MS = 10 * 60_000L

    /** The wait before the next try, given the previous wait ([prevMs], 0 for the first failure). Never below [MIN_MS]. */
    fun next(prevMs: Long): Long = if (prevMs < MIN_MS) MIN_MS else (prevMs * 2).coerceAtMost(MAX_MS)

    /** Wait after attempt number [failures] (1 = first failure). */
    fun afterFailures(failures: Int): Long = next(if (failures <= 1) 0L else (MIN_MS shl (failures - 2).coerceIn(0, 10)).coerceAtMost(MAX_MS))
}
