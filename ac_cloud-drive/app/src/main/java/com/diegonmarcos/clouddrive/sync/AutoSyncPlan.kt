package com.diegonmarcos.clouddrive.sync

/**
 * #1289 CONFIGS-AUTOSYNC of one repository, pure: On/Off plus "every X mins" (the declared default, 15), floored at
 * WorkManager's 15; the minutes the ONE periodic GitSyncWorker must tick at so every opted-in repository is served;
 * and the next run a repository's status line shows.
 */
object AutoSyncPlan {
    const val FLOOR_MINUTES = 15L

    /** The period [repoMinutes] names, or [defaultMinutes] when it names none (0); never under the floor. */
    fun minutes(repoMinutes: Long, defaultMinutes: Long): Long = (if (repoMinutes > 0) repoMinutes else defaultMinutes).coerceAtLeast(FLOOR_MINUTES)

    /** Turning AutoSync on writes the period explicitly, so the worker and the status line agree on it. */
    fun toggled(on: Boolean, repoMinutes: Long, defaultMinutes: Long): Pair<Boolean, Long> = on to minutes(repoMinutes, defaultMinutes)

    /** The worker's tick: the smallest period an opted-in repository asks for, never above [baseMinutes] nor under the floor. */
    fun tickMinutes(baseMinutes: Long, optedInMinutes: List<Long>): Long =
        ((optedInMinutes.filter { it > 0 } + baseMinutes).minOrNull() ?: baseMinutes).coerceAtLeast(FLOOR_MINUTES)

    /**
     * Minutes until the repository next syncs: null when AutoSync is off; otherwise when its own period has elapsed since its
     * last sync, but never before the worker's next tick ([tickInMinutes], null when WorkManager has not said).
     */
    fun nextRunMinutes(on: Boolean, periodMinutes: Long, lastSyncEpochSeconds: Long, nowEpochSeconds: Long, tickInMinutes: Long?): Long? {
        if (!on) return null
        val due = if (lastSyncEpochSeconds <= 0) 0L else ((lastSyncEpochSeconds + periodMinutes * 60 - nowEpochSeconds).coerceAtLeast(0) + 59) / 60
        return maxOf(due, tickInMinutes ?: 0L)
    }
}
