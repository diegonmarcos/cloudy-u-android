package com.diegonmarcos.cloudnav.maps

/** What woke the tracker. The platform sources (Activity Recognition, the geofence on the current place, the significant-motion sensor) live in Cloud Nav's WakeSources and call [LocationTrackerService.signal]. */
enum class GateSignal { ACTIVITY_STILL, ACTIVITY_MOVING, GEOFENCE_EXIT, SIGNIFICANT_MOTION, USER_START }

enum class GateMode { STILL, MOVING }

/** One position the gate judges: a passive fix another app produced, or a batched balanced-power fix. */
data class GateFix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long)

/** [untilMs] ends a MOVING hold; [anchor] is the last stable place (the fix the phone went still at), [lastAccepted] the newest fix kept. */
data class GateState(
    val mode: GateMode = GateMode.STILL,
    val untilMs: Long = 0L,
    val anchor: GateFix? = null,
    val lastAccepted: GateFix? = null,
)

/** What the platform subscription must be for a state: the passive listener always (it costs no radio), a fused request only while moving. */
data class GatePlan(val passive: Boolean, val fused: FusedParams?)

/** The one active request, batched: delivered at most every [maxUpdateDelayMs], never faster than [minIntervalMs], only after [minDistanceM] of displacement, BALANCED_POWER_ACCURACY. */
data class FusedParams(val intervalMs: Long, val minIntervalMs: Long, val maxUpdateDelayMs: Long, val minDistanceM: Float)

data class Offer(val state: GateState, val accepted: Boolean, val reason: String)

/**
 * #1770 THE tracking state machine of Cloud Nav, event-driven and pure (JVM-tested, no platform call in here).
 *
 *   STILL  : only the passive listener (fixes other apps already produced cost nothing); no fused request; no timer.
 *   MOVING : one batched BALANCED_POWER fused request ([FUSED]) until the hold lapses or an activity STILL arrives.
 *
 * Signals ([signal]): activity MOVING / geofence exit hold MOVING for [MOVE_HOLD_MS]; significant motion alone holds
 * [MOTION_HOLD_MS] (weak evidence); the user's Start probes for [START_HOLD_MS]; activity STILL ends it and anchors the place.
 * A hold never gets shorter than one already running. Fixes ([offer]) are accepted by accuracy, age and a minimum displacement
 * filter, so a still phone stores nothing and a moving one stores a point every [MIN_DISPLACEMENT_M] metres.
 */
object TrackGate {
    const val MOVE_HOLD_MS = 30 * 60_000L
    const val MOTION_HOLD_MS = 5 * 60_000L
    const val START_HOLD_MS = 5 * 60_000L

    const val MAX_ACCURACY_M = 100f          // a worse fix is noise, never stored
    const val MAX_FIX_AGE_MS = 10 * 60_000L   // an older fix is history, not a position
    const val MIN_DISPLACEMENT_M = 25.0       // the minimum displacement filter
    const val HEARTBEAT_MS = 30 * 60_000L     // a still phone refreshes its last known position this often from passive fixes
    const val EXIT_M = 150.0                  // a passive fix this far from the stable place, seen STILL, is a geofence exit
    const val EXIT_MAX_ACCURACY_M = 50f

    val FUSED = FusedParams(intervalMs = 60_000L, minIntervalMs = 30_000L, maxUpdateDelayMs = 5 * 60_000L, minDistanceM = MIN_DISPLACEMENT_M.toFloat())

    fun signal(s: GateState, sig: GateSignal, now: Long): GateState = when (sig) {
        GateSignal.ACTIVITY_STILL -> s.copy(mode = GateMode.STILL, untilMs = 0L, anchor = s.lastAccepted ?: s.anchor)
        GateSignal.ACTIVITY_MOVING, GateSignal.GEOFENCE_EXIT -> hold(s, now + MOVE_HOLD_MS)
        GateSignal.SIGNIFICANT_MOTION -> hold(s, now + MOTION_HOLD_MS)
        GateSignal.USER_START -> hold(s, now + START_HOLD_MS)
    }

    private fun hold(s: GateState, until: Long) = s.copy(mode = GateMode.MOVING, untilMs = maxOf(if (s.mode == GateMode.MOVING) s.untilMs else 0L, until))

    /** A lapsed MOVING hold ends in STILL (anchored at the last accepted fix). The only timer the service ever sets is for [GateState.untilMs]. */
    fun expire(s: GateState, now: Long): GateState =
        if (s.mode == GateMode.MOVING && now >= s.untilMs) s.copy(mode = GateMode.STILL, untilMs = 0L, anchor = s.lastAccepted ?: s.anchor) else s

    fun plan(s: GateState): GatePlan = GatePlan(passive = true, fused = if (s.mode == GateMode.MOVING) FUSED else null)

    /** Judge one fix. Accepted fixes update [GateState.lastAccepted]; a trusted fix far from the stable place while STILL is itself a MOVE wake. */
    fun offer(s: GateState, f: GateFix, now: Long): Offer {
        if (!f.lat.isFinite() || !f.lon.isFinite() || f.lat !in -90.0..90.0 || f.lon !in -180.0..180.0 || (f.lat == 0.0 && f.lon == 0.0))
            return Offer(s, false, "invalid")
        val acc = f.accuracyM
        if (acc == null || acc <= 0f || acc > MAX_ACCURACY_M) return Offer(s, false, "accuracy")
        if (f.timeMs > now + 60_000L || now - f.timeMs > MAX_FIX_AGE_MS) return Offer(s, false, "age")
        val last = s.lastAccepted
        if (last != null && f.timeMs <= last.timeMs) return Offer(s, false, "duplicate")
        val moved = last?.let { distanceM(it, f) }
        // keep a point when it is the first, moved enough (beyond both the filter and the new fix's own noise), or it is the still heartbeat
        val keep = last == null || moved!! >= maxOf(MIN_DISPLACEMENT_M, acc.toDouble()) || (s.mode == GateMode.STILL && f.timeMs - last.timeMs >= HEARTBEAT_MS)
        if (!keep) return Offer(s, false, "displacement")
        var out = s.copy(lastAccepted = f)
        if (s.mode == GateMode.STILL && s.anchor != null && acc <= EXIT_MAX_ACCURACY_M && distanceM(s.anchor, f) >= EXIT_M)
            out = signal(out, GateSignal.GEOFENCE_EXIT, now)
        return Offer(out, true, "ok")
    }

    /** Has the phone left a recorded stop? (beyond the stop radius plus the fix's own noise) */
    fun leftStop(stopLat: Double, stopLon: Double, f: GateFix, radiusM: Double): Boolean =
        distanceM(GateFix(stopLat, stopLon, null, 0L), f) > radiusM + (f.accuracyM ?: 0f)

    fun distanceM(a: GateFix, b: GateFix): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }
}
