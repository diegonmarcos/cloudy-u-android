package com.diegonmarcos.superapp.battery

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Per-app battery use per CHARGE CYCLE and per CALENDAR MONTH, pure (BatteryRepository persists it).
 *
 * A cycle runs from an unplug (or a full charge) to the next plug-in. It is driven by snapshots: one at
 * every ACTION_POWER_CONNECTED / DISCONNECTED (PowerStateReceiver) and one on BatterySessionWorker's
 * 15-minute tick. Each snapshot carries the level, the plug state and the per-uid mAh that
 * `dumpsys batterystats --charged` reports through the privileged adb channel (null when the channel
 * is down). batterystats counts since its last reset, and it resets on a full charge, so a cycle adds
 * up the reset-aware DIFFERENCE between consecutive snapshots ([delta]) rather than reading one total.
 * A month is the sum of the cycles that started in it ([months]).
 */
object BatteryCycles {

    const val FULL_PCT = 100
    private const val EPS = 0.05

    /** One reading. [apps] = cumulative mAh per uid since batterystats' last reset; null = no channel. */
    data class Snap(val ts: Long, val levelPct: Int, val plugged: Boolean, val apps: Map<Int, Double>?)

    /** A cycle; [endTs] null while it is still open. [apps] = mAh per uid consumed inside it. */
    data class Cycle(val startTs: Long, val endTs: Long?, val startPct: Int, val endPct: Int, val apps: Map<Int, Double>) {
        val usedPct: Int get() = (startPct - endPct).coerceAtLeast(0)
        val totalMah: Double get() = apps.values.sum()
    }

    /** The recorder's state: the open cycle (if any) and the last snapshot (the diff baseline). */
    data class State(val open: Cycle?, val last: Snap?)

    enum class Edge { NONE, OPEN, CLOSE }

    /** OPEN on battery (an unplug) or at full charge with no cycle open; CLOSE on the next plug-in. */
    fun edge(prev: Snap?, now: Snap, open: Boolean): Edge = when {
        open && now.plugged && prev?.plugged == false -> Edge.CLOSE
        !open && (!now.plugged || now.levelPct >= FULL_PCT) -> Edge.OPEN
        else -> Edge.NONE
    }

    /**
     * What each uid used between two cumulative readings. When ANY uid went down, batterystats reset in
     * between (full charge, or a reboot that dropped it): the new reading then counts from zero, so the
     * whole of it is the use. Missing either side is no attributable use.
     */
    fun delta(prev: Map<Int, Double>?, cur: Map<Int, Double>?): Map<Int, Double> {
        if (prev == null || cur == null) return emptyMap()
        val reset = prev.any { (uid, v) -> (cur[uid] ?: 0.0) + EPS < v }
        return cur.mapNotNull { (uid, v) ->
            val d = if (reset) v else v - (prev[uid] ?: 0.0)
            if (d > EPS) uid to d else null
        }.toMap()
    }

    fun add(a: Map<Int, Double>, b: Map<Int, Double>): Map<Int, Double> {
        if (b.isEmpty()) return a
        val out = HashMap(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0.0) + v
        return out
    }

    /** Fold one snapshot in. Returns the new state and the cycle it closed, if any. */
    fun step(state: State, now: Snap): Pair<State, Cycle?> {
        val prev = state.last
        // A snapshot without the channel keeps the last per-app baseline, so the gap is counted once it is back.
        val last = now.copy(apps = now.apps ?: prev?.apps)
        val acc = state.open?.let { it.copy(endPct = now.levelPct, apps = add(it.apps, delta(prev?.apps, now.apps))) }
        return when (edge(prev, now, acc != null)) {
            Edge.CLOSE -> State(null, last) to acc!!.copy(endTs = now.ts)
            Edge.OPEN -> State(Cycle(now.ts, null, now.levelPct, now.levelPct, emptyMap()), last) to null
            Edge.NONE -> State(acc, last) to null
        }
    }

    /** "2026-10": the month a cycle belongs to (the month it started in). */
    fun monthKey(ts: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = ts }
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    data class Month(val key: String, val cycles: Int, val usedPct: Int, val apps: Map<Int, Double>) {
        val totalMah: Double get() = apps.values.sum()
    }

    /** Per month, newest first: the cycles started in it, their % used and per-app mAh summed. */
    fun months(cycles: List<Cycle>, zone: TimeZone = TimeZone.getDefault()): List<Month> =
        cycles.groupBy { monthKey(it.startTs, zone) }.map { (k, l) ->
            Month(k, l.size, l.sumOf { it.usedPct }, l.fold(emptyMap()) { acc, c -> add(acc, c.apps) })
        }.sortedByDescending { it.key }

    /** One table row: a uid, its mAh and its share of the period (0..1). Heaviest first. */
    data class Share(val uid: Int, val mAh: Double, val share: Double)

    fun shares(apps: Map<Int, Double>): List<Share> {
        val total = apps.values.sum()
        if (total <= 0.0) return emptyList()
        return apps.map { (u, v) -> Share(u, v, v / total) }.sortedByDescending { it.mAh }
    }

    // ── storage codec (one TEXT column) ──────────────────────────────────

    fun encode(apps: Map<Int, Double>?): String? =
        apps?.entries?.joinToString(",") { "${it.key}:${String.format(Locale.US, "%.3f", it.value)}" }

    fun decode(s: String?): Map<Int, Double>? = s?.split(',')?.mapNotNull { p ->
        val i = p.indexOf(':'); if (i <= 0) null else p.substring(0, i).toIntOrNull()?.let { u -> p.substring(i + 1).toDoubleOrNull()?.let { u to it } }
    }?.toMap()
}
