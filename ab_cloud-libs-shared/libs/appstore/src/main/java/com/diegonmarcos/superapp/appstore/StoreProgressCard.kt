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

    fun lines(rows: List<JobBoard.Row>, overall: JobBoard.Overall): List<String> {
        if (rows.isEmpty()) return emptyList()
        val queueOrder = rows.filter { it.phase == JobBoard.Phase.QUEUED }.map { it.key }
        val out = mutableListOf(totals(rows, overall))
        // Live work first, then the queue, then what finished: what moves is at the top of a bounded card.
        val ordered = rows.sortedBy { order(it.phase) }
        ordered.take(MAX_ROWS).forEach { out += row(it, queueOrder.indexOf(it.key).let { i -> if (i >= 0) i + 1 else 0 }, queueOrder.size) }
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

    fun row(r: JobBoard.Row, queuePos: Int, queueSize: Int): String = listOfNotNull(
        r.label.ifEmpty { r.key },
        phaseWord(r.phase),
        if (r.phase == JobBoard.Phase.DOWNLOADING && r.percent >= 0) "${r.percent}%" else null,
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
