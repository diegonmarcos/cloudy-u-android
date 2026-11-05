package com.diegonmarcos.superapp.appstore

import java.util.concurrent.Semaphore

/**
 * One state entry per job, keyed (the package), so concurrent downloads and an install
 * never read each other's state. The Store used to draw ONE shared "current job", and
 * every row that ran at the same time fought over it. Pure Kotlin: a JVM test drives it.
 *
 * Rules: a row only moves by events carrying its own key; its byte count and percent never
 * go down; a finished row (done / failed) ignores late events until it is queued again;
 * a failure stays in its own row and costs the others nothing.
 */
class JobBoard {

    enum class Phase { QUEUED, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED }

    /** [waitingFor] is what a QUEUED row waits on ("download" / "install"). */
    data class Row(
        val key: String, val label: String, val phase: Phase,
        val bytes: Long = 0, val total: Long = 0, val percent: Int = -1,
        val reason: String = "", val waitingFor: String = "",
    ) {
        val finished get() = phase == Phase.DONE || phase == Phase.FAILED
        /** The row's own words, from its own state only. */
        fun text(): String = when (phase) {
            Phase.QUEUED -> "queued" + if (waitingFor.isNotEmpty()) " · waiting for the $waitingFor slot" else ""
            Phase.DOWNLOADING -> if (percent >= 0) "downloading $percent%" else "downloading…"
            Phase.VERIFYING -> "verifying"
            Phase.INSTALLING -> "installing"
            Phase.DONE -> "done"
            Phase.FAILED -> "failed" + if (reason.isNotEmpty()) ": $reason" else ""
        }
    }

    /** [percent] covers every job of the current session, finished ones included, so it does not fall when one ends. */
    data class Overall(val running: Int, val queued: Int, val percent: Int) {
        val active get() = running + queued
        /** More than one job in flight: the top bar speaks for all of them, not for one app. */
        val multi get() = active > 1
        fun text(): String = listOf(
            if (running > 0) "$running running" else "",
            if (queued > 0) "$queued queued" else "",
            "$percent%",
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private val rows = LinkedHashMap<String, Row>()

    /** Share of a job's work that is the install step; the rest is the download. */
    private val installShare = 0.1

    @Synchronized fun queue(key: String, label: String = key, waitingFor: String = "download") {
        // A new session (nothing active) forgets the last one's finished rows, so the overall percent restarts.
        if (rows.values.none { !it.finished }) rows.values.removeAll { it.finished && it.key != key }
        rows[key] = Row(key, label.ifEmpty { rows[key]?.label ?: key }, Phase.QUEUED, waitingFor = waitingFor)
    }

    /** A job names itself. A chain's nested verb (download, install, clear) re-announces the SAME job:
     *  that must not rewind a row already under way, so only a new or finished row is (re)queued. */
    @Synchronized fun begin(key: String, label: String) {
        val r = rows[key]
        if (r != null && !r.finished) { if (label.isNotEmpty()) rows[key] = r.copy(label = label) }
        else queue(key, label, "")
    }

    @Synchronized fun label(key: String, label: String) {
        val r = rows[key] ?: return run { rows[key] = Row(key, label, Phase.QUEUED) }
        if (label.isNotEmpty()) rows[key] = r.copy(label = label)
    }

    private inline fun edit(key: String, f: (Row) -> Row) {
        val r = rows[key] ?: Row(key, key, Phase.QUEUED)
        if (r.finished) return
        rows[key] = f(r)
    }

    /** Byte progress of [key]; bytes and percent only grow (a restarted source reopens at 0). */
    @Synchronized fun download(key: String, bytes: Long, total: Long) = edit(key) { r ->
        val b = maxOf(r.bytes, bytes)
        val t = if (total > 0) total else r.total
        val pct = if (t > 0) maxOf(r.percent, (b * 100 / t).toInt().coerceIn(0, 100)) else r.percent
        r.copy(phase = Phase.DOWNLOADING, bytes = b, total = t, percent = pct, waitingFor = "")
    }

    @Synchronized fun phase(key: String, phase: Phase, waitingFor: String = "") = edit(key) { it.copy(phase = phase, waitingFor = waitingFor) }
    @Synchronized fun fail(key: String, reason: String) = edit(key) { it.copy(phase = Phase.FAILED, reason = reason, waitingFor = "") }
    @Synchronized fun done(key: String) = edit(key) { it.copy(phase = Phase.DONE, waitingFor = "") }

    /** The job's thread is finished: a row still open (it never said failed) is done, never stuck. */
    @Synchronized fun settle(key: String) {
        val r = rows[key] ?: return
        if (!r.finished) rows[key] = r.copy(phase = Phase.DONE, waitingFor = "")
    }

    /** The Activity panel's Clear: forget finished rows, never a live one. */
    @Synchronized fun clearFinished() { rows.values.removeAll { it.finished } }

    /** Forget every row (a test starting from nothing running). */
    @Synchronized fun reset() = rows.clear()

    @Synchronized fun row(key: String): Row? = rows[key]
    @Synchronized fun rows(): List<Row> = rows.values.toList()

    @Synchronized fun overall(): Overall {
        val all = rows.values
        val running = all.count { it.phase == Phase.DOWNLOADING || it.phase == Phase.VERIFYING || it.phase == Phase.INSTALLING }
        val queued = all.count { it.phase == Phase.QUEUED }
        if (all.isEmpty()) return Overall(0, 0, 0)
        // Weight by download size; a job whose size is not known yet counts as the average known one.
        val known = all.filter { it.total > 0 }.map { it.total }
        val unit = if (known.isEmpty()) 1.0 else known.average()
        var num = 0.0; var den = 0.0
        for (r in all) {
            val w = if (r.total > 0) r.total.toDouble() else unit
            val f = when (r.phase) {
                Phase.QUEUED -> 0.0
                Phase.DOWNLOADING -> (1 - installShare) * (if (r.total > 0) r.bytes.toDouble() / r.total else 0.0).coerceIn(0.0, 1.0)
                Phase.VERIFYING, Phase.INSTALLING -> 1 - installShare   // downloaded; the install step is still ahead
                Phase.DONE, Phase.FAILED -> 1.0
            }
            num += w * f; den += w
        }
        return Overall(running, queued, Math.round(100 * num / den).toInt().coerceIn(0, 100))
    }
}

/**
 * The concurrency bound: at most [maxDownloads] downloads at once and ONE install at a time
 * (PackageInstaller sessions and `pm install` must not overlap). A job that has to wait shows
 * QUEUED on its own row, naming what it waits for, rather than looking busy.
 */
class JobRunner(val board: JobBoard, maxDownloads: Int = 3) {
    private val downloads = Semaphore(maxDownloads, true)
    private val installs = Semaphore(1, true)

    fun <T> download(key: String, f: () -> T): T = gated(downloads, key, "download", JobBoard.Phase.DOWNLOADING, f)
    fun <T> install(key: String, f: () -> T): T = gated(installs, key, "install", JobBoard.Phase.INSTALLING, f)

    private fun <T> gated(s: Semaphore, key: String, what: String, phase: JobBoard.Phase, f: () -> T): T {
        if (!s.tryAcquire()) {
            board.phase(key, JobBoard.Phase.QUEUED, what)
            s.acquireUninterruptibly()
        }
        try { board.phase(key, phase); return f() } finally { s.release() }
    }
}
