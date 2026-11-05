package com.diegonmarcos.superapp.adbdebug

/**
 * #1487 battery: the pure rules for the host's keep-alive foreground services. A keep-alive that polls on a fixed
 * period, ignores the screen and never stops held three apps awake for 40 h of background each.
 */
object KeepAlivePolicy {
    const val MIN_MS = 60_000L
    const val MAX_MS = 30 * 60_000L
    const val SCREEN_OFF_MS = 15 * 60_000L

    /** The service has a job only while the host may run in the background (#1502) and the debug server may run or the channel is armed; otherwise it stops. */
    fun needed(debugServerAllowed: Boolean, channelEnabled: Boolean, backgroundAllowed: Boolean = true): Boolean =
        backgroundAllowed && (debugServerAllowed || channelEnabled)

    /** Wait before the next pass: doubles with each consecutive failure up to [MAX_MS]; a dark screen waits [SCREEN_OFF_MS]. */
    fun nextDelayMs(failures: Int, screenOn: Boolean): Long {
        if (!screenOn) return SCREEN_OFF_MS
        val shift = failures.coerceIn(0, 10)
        return (MIN_MS shl shift).coerceAtMost(MAX_MS)
    }

    /** A pass that dials the channel runs only with the screen on. */
    fun mayReconnect(screenOn: Boolean): Boolean = screenOn
}
