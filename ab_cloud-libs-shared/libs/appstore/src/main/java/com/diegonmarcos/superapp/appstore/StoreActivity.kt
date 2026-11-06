package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.dl.DlJob
import com.diegonmarcos.superapp.appstore.dl.DlState
import com.diegonmarcos.superapp.appstore.dl.DlText

/**
 * #1524 THE ONE ACTIVITY MODEL of the Store: every download (the engine's StateFlow) and every install (the [JobBoard] row) of the
 * process, folded into one list of [Item]s and one [Overall] line. Pure Kotlin, no android: the panel (StoreActivityPanel) only draws
 * what this says, and a JVM test pins the numbers. Nothing here starts, stops or changes a job.
 *
 * A download and an install of different apps run side by side, so both show at once; for ONE app the engine record speaks until its
 * file is verified, then the install row takes over (installing, then done or failed).
 */
object StoreActivity {

    /** #1551 WAITING_CONFIRM: handed to Android, waiting for the user's tap on the notification; RETRY: its install did not finish
     *  and it is re-installed from the cache when the next try is due. */
    enum class Phase { QUEUED, DOWNLOADING, PAUSED, VERIFYING, INSTALLING, DONE, FAILED, WAITING_CONFIRM, RETRY }
    /** The expanded list's sections, in order. */
    enum class Group { ACTIVE, QUEUED, FINISHED }
    /** The collapsed line's colour. */
    enum class Tone { IDLE, ACTIVE, PAUSED, ERROR }

    /** Unknown order (an install row has no engine record): counted with the current session. */
    const val UNKNOWN_AT = -1L
    /** A finished item still counts toward the percent when it was added within this long of the oldest open one (one batch). */
    const val SESSION_MS = 10 * 60 * 1000L
    /** The Done / Failed section lists at most this many, newest first. */
    const val RECENT = 10
    /** Share of one job's work that is the install step; the rest is the download (as [JobBoard]). */
    private const val INSTALL_SHARE = 0.1

    data class Item(
        val key: String, val label: String, val pkg: String, val phase: Phase,
        /** The engine record's id and state, when the engine owns this item: only then are pause / resume / cancel / retry offered. */
        val engineId: String? = null, val engineState: DlState? = null,
        val bytes: Long = 0, val total: Long = -1, val bytesPerSec: Long = 0, val etaSec: Long = -1,
        val reason: String = "", val retries: Int = 0, val waitingFor: String = "", val addedAt: Long = UNKNOWN_AT,
        /** #1551 the queue's retry budget (n/N) and the next try, already formatted by the caller ("14:05"); 0 / "" = none. */
        val retryMax: Int = 0, val nextText: String = "",
    ) {
        val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else -1
        val group: Group get() = when (phase) {
            Phase.DOWNLOADING, Phase.VERIFYING, Phase.INSTALLING -> Group.ACTIVE
            Phase.QUEUED, Phase.PAUSED, Phase.WAITING_CONFIRM, Phase.RETRY -> Group.QUEUED
            Phase.DONE, Phase.FAILED -> Group.FINISHED
        }
        val canPause get() = engineState == DlState.QUEUED || engineState == DlState.RUNNING || engineState == DlState.VERIFYING
        val canResume get() = engineState == DlState.PAUSED
        val canRetry get() = engineState == DlState.FAILED
        val canCancel get() = engineState?.active == true || engineState == DlState.FAILED

        /** The phase word of the row: Queued / Downloading x% · speed · ETA / Paused / Verifying / Installing / Done / Failed: reason. */
        fun phaseText(): String = when (phase) {
            Phase.QUEUED -> "Queued" + when {
                waitingFor.isNotEmpty() -> " · waiting for the $waitingFor slot"
                retries > 0 -> " · retry $retries"
                else -> ""
            }
            Phase.DOWNLOADING -> listOf(if (percent >= 0) "Downloading $percent%" else "Downloading",
                if (bytesPerSec > 0) DlText.size(bytesPerSec) + "/s" else "", DlText.eta(etaSec)).filter { it.isNotEmpty() }.joinToString(" · ")
            Phase.PAUSED -> "Paused" + if (bytes > 0) " · ${DlText.size(bytes)} kept" else ""
            Phase.VERIFYING -> "Verifying"
            Phase.INSTALLING -> "Installing"
            Phase.DONE -> "Done"
            Phase.FAILED -> "Failed" + if (reason.isNotEmpty()) ": $reason" else ""
            Phase.WAITING_CONFIRM -> "Waiting for your confirmation · tap the notification"
            Phase.RETRY -> listOf(if (retryMax > 0) "Retry $retries/$retryMax" else "Retry $retries",
                if (nextText.isNotEmpty()) "next $nextText" else "",
                reason).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    /**
     * #1551 One entry of the persisted install queue ([StoreAuto]): its per-item state survives process death, so after a restart
     * (or with the engine idle) the panel still says what each app waits for. [status] is the queue's word (queued, downloading,
     * downloaded, installing, pending, retry, installed, failed, held, handed).
     */
    data class QueueEntry(
        val key: String, val label: String, val status: String,
        val attempts: Int = 0, val retryMax: Int = 0, val nextText: String = "", val reason: String = "",
    )

    /** The panel phase of a queue status; null = not shown (the host handed to its self-updater). */
    fun queuePhase(status: String): Phase? = when (status) {
        "queued", "downloaded" -> Phase.QUEUED
        "downloading" -> Phase.DOWNLOADING
        "installing" -> Phase.INSTALLING
        "pending" -> Phase.WAITING_CONFIRM
        "retry" -> Phase.RETRY
        "installed" -> Phase.DONE
        "failed", "held" -> Phase.FAILED
        else -> null
    }

    /** What the one collapsed line says. [updates] is how many updates the page knows of (shown only when nothing is in flight). */
    data class Overall(
        val downloading: Int, val installing: Int, val queued: Int, val paused: Int, val failed: Int, val done: Int,
        val percent: Int, val bytesPerSec: Long, val etaSec: Long, val updates: Int = 0,
        /** #1551 waiting for the user's tap, and waiting for an install retry. */
        val confirming: Int = 0, val retrying: Int = 0,
    ) {
        val active get() = downloading + installing + queued + confirming + retrying
        val busy get() = active > 0
        val tone: Tone get() = when {
            busy -> Tone.ACTIVE
            paused > 0 -> Tone.PAUSED
            failed > 0 -> Tone.ERROR
            else -> Tone.IDLE
        }
        /** "3 downloading · 1 installing · 2 queued · 45% · 12.3 MB/s · ~1 min left"; idle: "All up to date" or "N updates available". */
        fun text(): String {
            if (active == 0 && paused == 0) return if (updates > 0) "$updates update${if (updates == 1) "" else "s"} available" else "All up to date"
            return listOf(
                if (downloading > 0) "$downloading downloading" else "",
                if (installing > 0) "$installing installing" else "",
                if (queued > 0) "$queued queued" else "",
                if (confirming > 0) "$confirming waiting for confirmation" else "",
                if (retrying > 0) "$retrying retrying" else "",
                if (paused > 0) "$paused paused" else "",
                "$percent%",
                if (bytesPerSec > 0) DlText.size(bytesPerSec) + "/s" else "",
                if (etaSec >= 0) "~" + DlText.eta(etaSec) else "",
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    private fun keyOf(j: DlJob) = j.pkg.ifEmpty { j.id }

    private fun fromEngine(j: DlJob, phase: Phase) = Item(
        keyOf(j), j.label, j.pkg, phase, j.id, j.state, j.bytes, j.total, j.bytesPerSec, j.etaSec,
        reason = j.error, retries = j.failures, addedAt = j.addedAt,
    )

    private fun fromBoard(r: JobBoard.Row, phase: Phase) = Item(
        r.key, r.label, r.key, phase, bytes = r.bytes, total = if (r.total > 0) r.total else -1,
        reason = r.reason, waitingFor = r.waitingFor,
    )

    private fun boardPhase(p: JobBoard.Phase) = when (p) {
        JobBoard.Phase.QUEUED -> Phase.QUEUED
        JobBoard.Phase.DOWNLOADING -> Phase.DOWNLOADING
        JobBoard.Phase.VERIFYING -> Phase.VERIFYING
        JobBoard.Phase.INSTALLING -> Phase.INSTALLING
        JobBoard.Phase.DONE -> Phase.DONE
        JobBoard.Phase.FAILED -> Phase.FAILED
    }

    /** Every download of the engine and every install row, one [Item] per app. A cancelled download is not an item.
     *  #1551 [queue]: the persisted install queue; an app it names that is not mid-transfer shows the queue's state (waiting for
     *  confirmation, retry n/N with the next time, failed with the reason), and an app only the queue knows is listed too. */
    fun items(jobs: List<DlJob>, rows: List<JobBoard.Row>, queue: List<QueueEntry> = emptyList()): List<Item> =
        withQueue(baseItems(jobs, rows), queue)

    private fun withQueue(base: List<Item>, queue: List<QueueEntry>): List<Item> {
        if (queue.isEmpty()) return base
        val out = base.toMutableList()
        val at = out.withIndex().associate { it.value.key to it.index }
        for (q in queue) {
            val phase = queuePhase(q.status) ?: continue
            fun apply(i: Item) = i.copy(phase = phase, retries = q.attempts, retryMax = q.retryMax, nextText = q.nextText,
                reason = q.reason.ifEmpty { i.reason })
            val n = at[q.key]
            if (n == null) out += apply(Item(q.key, q.label, q.key, phase))
            // A transfer or install under way speaks for itself; the queue speaks for what waits or ended.
            else if (out[n].group != Group.ACTIVE && phase in QUEUE_SPEAKS) out[n] = apply(out[n])
        }
        return out
    }

    private val QUEUE_SPEAKS = setOf(Phase.WAITING_CONFIRM, Phase.RETRY, Phase.FAILED)

    private fun baseItems(jobs: List<DlJob>, rows: List<JobBoard.Row>): List<Item> {
        val byKey = rows.associateBy { it.key }
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (j in jobs.sortedBy { it.addedAt }) {
            val k = keyOf(j); seen += k
            val r = byKey[k]
            val item = when {
                // The file is verified and its install is under way: the install row speaks.
                r != null && r.phase == JobBoard.Phase.INSTALLING -> fromEngine(j, Phase.INSTALLING)
                j.state == DlState.CANCELLED -> null
                j.state == DlState.DONE && r != null && r.phase == JobBoard.Phase.QUEUED -> fromEngine(j, Phase.QUEUED).copy(waitingFor = r.waitingFor.ifEmpty { "install" })
                j.state == DlState.DONE && r != null && r.phase == JobBoard.Phase.FAILED -> fromEngine(j, Phase.FAILED).copy(reason = r.reason)
                else -> fromEngine(j, when (j.state) {
                    DlState.QUEUED -> Phase.QUEUED
                    DlState.RUNNING -> Phase.DOWNLOADING
                    DlState.VERIFYING -> Phase.VERIFYING
                    DlState.PAUSED -> Phase.PAUSED
                    DlState.DONE -> Phase.DONE
                    DlState.FAILED -> Phase.FAILED
                    DlState.CANCELLED -> Phase.FAILED
                })
            }
            if (item != null) out += item
        }
        for (r in rows) if (r.key !in seen) out += fromBoard(r, boardPhase(r.phase))
        return out
    }

    /** The collapsed line: counts, weighted percent, combined speed and ETA over [items]. */
    fun overall(items: List<Item>, updates: Int = 0): Overall {
        fun n(p: Phase) = items.count { it.phase == p }
        val downloading = n(Phase.DOWNLOADING) + n(Phase.VERIFYING)
        val confirming = n(Phase.WAITING_CONFIRM)
        val retrying = n(Phase.RETRY)
        val speed = items.filter { it.phase == Phase.DOWNLOADING }.sumOf { it.bytesPerSec.coerceAtLeast(0) }
        // What is left to fetch at the combined speed: the known sizes of running and queued downloads (an unknown size adds nothing).
        val left = items.filter { it.phase == Phase.DOWNLOADING || it.phase == Phase.QUEUED }
            .filter { it.total > 0 }.sumOf { (it.total - it.bytes).coerceAtLeast(0) }
        val eta = if (speed > 0 && items.any { it.phase == Phase.DOWNLOADING }) left / speed else -1L

        // Percent: every counted item weighs its download size (an unknown size counts as the average known one); a finished item of the
        // same session still counts, so the bar does not fall when one ends. A failure is not progress and is left out.
        val open = items.filter { it.group != Group.FINISHED && it.addedAt >= 0 }.minOfOrNull { it.addedAt }
        val counted = items.filter {
            it.phase != Phase.FAILED && (it.group != Group.FINISHED || it.addedAt < 0 || (open != null && it.addedAt >= open - SESSION_MS))
        }
        val known = counted.filter { it.total > 0 }.map { it.total }
        val unit = if (known.isEmpty()) 1.0 else known.average()
        var num = 0.0; var den = 0.0
        for (i in counted) {
            val w = if (i.total > 0) i.total.toDouble() else unit
            val dl = if (i.total > 0) (i.bytes.toDouble() / i.total).coerceIn(0.0, 1.0) else 0.0
            val f = when (i.phase) {
                Phase.QUEUED -> 0.0
                Phase.DOWNLOADING, Phase.PAUSED -> (1 - INSTALL_SHARE) * dl
                Phase.VERIFYING, Phase.INSTALLING, Phase.WAITING_CONFIRM, Phase.RETRY -> 1 - INSTALL_SHARE
                Phase.DONE, Phase.FAILED -> 1.0
            }
            num += w * f; den += w
        }
        val pct = if (den <= 0.0) 0 else Math.round(100 * num / den).toInt().coerceIn(0, 100)
        return Overall(downloading, n(Phase.INSTALLING), n(Phase.QUEUED), n(Phase.PAUSED), n(Phase.FAILED), n(Phase.DONE), pct, speed, eta, updates,
            confirming, retrying)
    }

    /** The expanded list's section [g]: Active and Queued as they come; Finished lists failures first, at most [RECENT], newest first. */
    fun section(items: List<Item>, g: Group): List<Item> {
        val of = items.filter { it.group == g }
        return if (g != Group.FINISHED) of
        else of.sortedWith(compareBy<Item> { it.phase != Phase.FAILED }.thenByDescending { it.addedAt }).take(RECENT)
    }

    /** A boolean store (SharedPreferences on the phone, a map in a test). */
    interface Flags {
        fun get(key: String, default: Boolean): Boolean
        fun put(key: String, value: Boolean)
    }

    /** The panel's remembered view state: collapsed by default, and what the owner chose survives a restart. */
    class Prefs(private val flags: Flags) {
        var expanded: Boolean
            get() = flags.get(EXPANDED, false)
            set(v) = flags.put(EXPANDED, v)
        /** The Done / Failed section: collapsed by default, errors are never spread open. */
        var finishedOpen: Boolean
            get() = flags.get(FINISHED_OPEN, false)
            set(v) = flags.put(FINISHED_OPEN, v)
        private companion object {
            const val EXPANDED = "activity_expanded"
            const val FINISHED_OPEN = "activity_finished_open"
        }
    }
}
