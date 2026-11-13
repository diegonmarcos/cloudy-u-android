package com.diegonmarcos.superapp.appstore

/**
 * #1556 WHEN THE adb-transport probe MAY RUN AGAIN. Pure Kotlin, no android: a JVM test pins "settles once, never loops" and the
 * floor.
 *
 * The #1550 ADB chip re-checked on every resume and on every Settings.Global adb change, and its UI drove the probe from a
 * recomposition effect whose own completion bumped the recomposition key — so a chip that never reached a definite state (a probe
 * that threw or hung, leaving the status "checking") re-fired the probe continuously, and a flapping adb setting during a wireless
 * connect could do the same even when it did settle. This is the one gate every re-check asks first: a probe runs only when none
 * has run yet, or at least [FLOOR_MS] has passed since the last one STARTED, and never while one is already in flight. A settled
 * Connected state therefore stops re-checking until the next resume or adb-setting change that also clears the floor.
 */
object AdbRecheck {
    /** Owner battery rule: no re-check more often than every 30 s, resume and adb-setting change included. */
    const val FLOOR_MS = 30_000L

    /**
     * [lastStartedAt] = when the last probe STARTED (0 = none yet), [now] = now, [inFlight] = a probe is running.
     * A probe is due when none is in flight AND (none has ever started, or the floor has elapsed since the last start).
     */
    fun due(lastStartedAt: Long, now: Long, inFlight: Boolean, floorMs: Long = FLOOR_MS): Boolean =
        !inFlight && (lastStartedAt <= 0L || now - lastStartedAt >= floorMs)
}
