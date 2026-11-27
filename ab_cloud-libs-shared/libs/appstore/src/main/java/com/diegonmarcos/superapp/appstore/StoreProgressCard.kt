package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.updater.FleetIdentity

/**
 * #1944 The full update progress the Store's "running in the background" card shows, from the [JobBoard] rows (the same keyed
 * state the Store page and the Activity panel read, so the card cannot disagree with them). Pure: a JVM test executes it.
 *
 *   first line   totals: apps, overall percent, running / queued / done / failed, bytes done of the known total
 *   one line per app   name · phase · percent · bytes · queue position · last error
 *   [moreLine]   "+N more" once the rows pass [MAX_ROWS] (the notification is bounded; the Store page lists all)
 */
object StoreProgressCard {
    const val MAX_ROWS = 10

    // #1965 the declared "Background Apps" card fields (fleet-notify BackgroundCards.Store; StoreBackgroundCardTest binds them).
    const val F_PROGRESS = "Progress"
    const val F_APP = "App"
    const val F_SPEED = "Speed"
    const val F_STATE = "State"

    /** #1965 bytes per second from two samples of one row; null without a usable earlier sample. */
    fun speed(prevBytes: Long, prevAt: Long, bytes: Long, now: Long): Long? {
        val dt = now - prevAt
        if (prevAt <= 0 || dt <= 0 || bytes < prevBytes) return null
        return (bytes - prevBytes) * 1000 / dt
    }

    fun speedText(bps: Long): String = "${FleetIdentity.human(bps)}/s"

    private val samples = HashMap<String, Pair<Long, Long>>()
    private var lastSpeeds: Map<String, Long> = emptyMap()
    private var lastSampleAt = 0L
    /** Two reads closer than this (the card's lines and its fields, built for one refresh) share one sample. */
    const val MIN_SAMPLE_GAP_MS = 500L

    /** #1965 The live speed of every downloading row, from the previous card refresh's byte counts (no new timer, no new probe). */
    @Synchronized
    fun speeds(rows: List<JobBoard.Row>, now: Long = System.currentTimeMillis()): Map<String, Long> {
        if (lastSampleAt > 0 && now - lastSampleAt in 0 until MIN_SAMPLE_GAP_MS) return lastSpeeds
        lastSampleAt = now
        val out = HashMap<String, Long>()
        rows.forEach { r ->
            if (r.phase != JobBoard.Phase.DOWNLOADING) { samples.remove(r.key); return@forEach }
            samples[r.key]?.let { (b, at) -> speed(b, at, r.bytes, now)?.let { out[r.key] = it } }
            samples[r.key] = r.bytes to now
        }
        samples.keys.retainAll(rows.map { it.key }.toSet())
        lastSpeeds = out
        return out
    }

    /**
     * #1965 The card's fields: Progress (the totals), Speed (the sum of the live rows, or "idle"), one App per row (the same row
     * text as [lines], with its speed), the "+N more" line, then [state] (the Store's former badge lines) as State fields.
     */
    fun fields(rows: List<JobBoard.Row>, overall: JobBoard.Overall, speeds: Map<String, Long>, state: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out += F_PROGRESS to if (rows.isEmpty()) "no updates running" else totals(rows, overall)
        out += F_SPEED to if (speeds.isEmpty()) "idle" else speedText(speeds.values.sum())
        val queueOrder = rows.filter { it.phase == JobBoard.Phase.QUEUED }.map { it.key }
        val ordered = rows.sortedBy { order(it.phase) }
        ordered.take(MAX_ROWS).forEach { r ->
            out += F_APP to row(r, queueOrder.indexOf(r.key).let { i -> if (i >= 0) i + 1 else 0 }, queueOrder.size, speeds[r.key])
        }
        if (ordered.size > MAX_ROWS) out += F_APP to "+${ordered.size - MAX_ROWS} more"
        state.filter { it.isNotBlank() }.forEach { out += F_STATE to it }
        return out
    }

    fun lines(rows: List<JobBoard.Row>, overall: JobBoard.Overall, speeds: Map<String, Long> = emptyMap()): List<String> {
        if (rows.isEmpty()) return emptyList()
        val queueOrder = rows.filter { it.phase == JobBoard.Phase.QUEUED }.map { it.key }
        val out = mutableListOf(totals(rows, overall))
        // Live work first, then the queue, then what finished: what moves is at the top of a bounded card.
        val ordered = rows.sortedBy { order(it.phase) }
        ordered.take(MAX_ROWS).forEach { out += row(it, queueOrder.indexOf(it.key).let { i -> if (i >= 0) i + 1 else 0 }, queueOrder.size, speeds[it.key]) }
        if (ordered.size > MAX_ROWS) out += "+${ordered.size - MAX_ROWS} more"
        return out
    }

    fun totals(rows: List<JobBoard.Row>, overall: JobBoard.Overall): String {
        val done = rows.count { it.phase == JobBoard.Phase.DONE }
        val failed = rows.count { it.phase == JobBoard.Phase.FAILED }
        val bytes = rows.sumOf { it.bytes }
        val total = rows.sumOf { it.total }
        return listOfNotNull(
            "${rows.size} app${if (rows.size == 1) "" else "s"}",
            "${overall.percent}%",
            if (overall.running > 0) "${overall.running} running" else null,
            if (overall.queued > 0) "${overall.queued} queued" else null,
            if (done > 0) "$done done" else null,
            if (failed > 0) "$failed failed" else null,
            if (total > 0) "${FleetIdentity.human(bytes)} of ${FleetIdentity.human(total)}" else null,
        ).joinToString(" · ")
    }

    fun row(r: JobBoard.Row, queuePos: Int, queueSize: Int, speed: Long? = null): String = listOfNotNull(
        r.label.ifEmpty { r.key },
        phaseWord(r.phase),
        if (r.phase == JobBoard.Phase.DOWNLOADING && r.percent >= 0) "${r.percent}%" else null,
        if (r.phase == JobBoard.Phase.DOWNLOADING && speed != null) speedText(speed) else null,
        if (r.total > 0 && (r.phase == JobBoard.Phase.DOWNLOADING || r.bytes > 0)) "${FleetIdentity.human(r.bytes)} / ${FleetIdentity.human(r.total)}"
        else if (r.bytes > 0) FleetIdentity.human(r.bytes) else null,
        if (queuePos > 0) "#$queuePos of $queueSize in queue" + if (r.waitingFor.isNotEmpty()) " (waiting for the ${r.waitingFor} slot)" else "" else null,
        if (r.phase == JobBoard.Phase.FAILED && r.reason.isNotEmpty()) "error: ${r.reason}" else null,
    ).joinToString(" · ")

    fun phaseWord(p: JobBoard.Phase): String = p.name.lowercase()

    private fun order(p: JobBoard.Phase) = when (p) {
        JobBoard.Phase.DOWNLOADING, JobBoard.Phase.VERIFYING, JobBoard.Phase.INSTALLING -> 0
        JobBoard.Phase.QUEUED -> 1
        JobBoard.Phase.FAILED -> 2
        JobBoard.Phase.DONE -> 3
    }
}
