package com.diegonmarcos.cloudnav.tracker

/** Why the engine was woken without waiting for the periodic tick. */
enum class WakeKind { SIGNIFICANT_MOTION, ACTIVITY_STILL, ACTIVITY_MOVING, GEOFENCE_EXIT, TICK }

/** Whether the phone is treated as moving until [untilMs] (set by a wake source), and the geofence around the current STAY. */
data class WakeState(val moving: Boolean = false, val untilMs: Long = 0L, val fenceLat: Double = 0.0, val fenceLon: Double = 0.0, val fenceRadiusM: Double = 0.0) {
    val fenced: Boolean get() = fenceRadiusM > 0.0
}

/**
 * Pure rules for the energy-efficient wake sources: Activity Recognition transitions, the significant-motion sensor and a
 * geofence on exit from the current STAY wake the engine at once; the WorkManager tick is only the fallback. High accuracy
 * (GPS) stays forbidden while still, see [BatteryPolicy].
 */
object WakePolicy {
    const val MOTION_HOLD_MS = 5 * 60_000L      // a significant-motion event alone is weak evidence
    const val MOVE_HOLD_MS = 30 * 60_000L       // activity / geofence exit: moving until STILL arrives or this lapses
    const val MOVE_FIX_GAP_MS = 10 * 60_000L    // while moving, an engine accurate fix at most this often (#1770: was 2 min; the tracker's batched request follows the move)
    const val TICK_FALLBACK_MIN = 60L
    const val TICK_DEFAULT_MIN = 15L
    const val FENCE_MIN_M = 100.0
    const val FENCE_MAX_M = 500.0

    /** The state after [kind] at [now]. STILL ends a MOVE; the tick changes nothing. */
    fun apply(s: WakeState, kind: WakeKind, now: Long): WakeState = when (kind) {
        WakeKind.ACTIVITY_STILL -> s.copy(moving = false, untilMs = 0L)
        WakeKind.ACTIVITY_MOVING, WakeKind.GEOFENCE_EXIT -> s.copy(moving = true, untilMs = now + MOVE_HOLD_MS)
        WakeKind.SIGNIFICANT_MOTION -> if (s.moving && s.untilMs > now) s else s.copy(moving = true, untilMs = now + MOTION_HOLD_MS)
        WakeKind.TICK -> s
    }

    /** Moving for this pass: the step cadence says so, or a wake source did and its hold has not lapsed. */
    fun effectiveMoving(stepsMoving: Boolean, s: WakeState, now: Long): Boolean = stepsMoving || (s.moving && now < s.untilMs)

    /** A pass woken by a moving signal asks for an accurate fix at once, then at most every [MOVE_FIX_GAP_MS]. */
    fun accurateWhileMoving(moving: Boolean, lastAccurateMs: Long, now: Long): Boolean = moving && now - lastAccurateMs >= MOVE_FIX_GAP_MS

    /** The tick is the fallback only: relaxed while at least one wake source is armed. */
    fun tickMinutes(sourcesArmed: Boolean): Long = if (sourcesArmed) TICK_FALLBACK_MIN else TICK_DEFAULT_MIN

    fun fenceRadiusM(accuracyM: Double): Double = (accuracyM * 1.5).coerceIn(FENCE_MIN_M, FENCE_MAX_M)

    /** Arm a (new) geofence when none is set, or the STAY's fix moved out of the old one's half radius. */
    fun needsFence(s: WakeState, lat: Double, lon: Double): Boolean =
        !s.fenced || DeadReckoning.haversineM(LatLon(s.fenceLat, s.fenceLon), LatLon(lat, lon)) > s.fenceRadiusM / 2

    /** Does a wake of [kind] mean the phone is on the move (so high accuracy may be requested)? */
    fun movesFor(kind: WakeKind): Boolean = kind == WakeKind.ACTIVITY_MOVING || kind == WakeKind.GEOFENCE_EXIT || kind == WakeKind.SIGNIFICANT_MOTION
}
