package com.diegonmarcos.superapp.appstore.dl

/** #1517 One queue item's life. PAUSED and FAILED keep their `.part`; CANCELLED drops it. */
enum class DlState { QUEUED, RUNNING, VERIFYING, PAUSED, DONE, FAILED, CANCELLED;
    /** Still owned by the engine: a new request for the same id joins it and never restarts it. */
    val active get() = this == QUEUED || this == RUNNING || this == VERIFYING || this == PAUSED
}

/**
 * What a mirror resolved to for one attempt: the URL, its credentials, the declared sha256 (null = the mirror publishes none,
 * then [bytes] is the weaker check) and the declared length (-1 = unknown).
 */
class DlSource(val url: String, val headers: Map<String, String> = emptyMap(), val sha256: String? = null, val bytes: Long = -1L)

/** One place the bytes can come from. [resolve] runs per attempt (it may fetch a sidecar or a token) and throws when the mirror cannot serve. */
class DlMirror(val name: String, val resolve: () -> DlSource)

/** What a caller asks for. [id] is the identity: asking again for an id the engine still owns joins the running job. */
class DlSpec(
    val id: String,
    val pkg: String,
    val versionCode: Long = -1L,
    val label: String = id,
    /** File-name prefix inside the cache dir ("fleet-<id>-"): the part and the finished file share it. */
    val namePrefix: String = "dl-$id-",
    val priority: Int = 0,
    val userStarted: Boolean = true,
)

/** The persisted and observed record of one item. Immutable: the engine replaces it on every change. */
data class DlJob(
    val id: String,
    val pkg: String,
    val versionCode: Long,
    val label: String,
    val namePrefix: String,
    val priority: Int,
    val userStarted: Boolean,
    val addedAt: Long,
    val state: DlState = DlState.QUEUED,
    val bytes: Long = 0L,
    val total: Long = -1L,
    val etag: String = "",
    val lastModified: String = "",
    /** Index into the mirror list, in declared order. */
    val mirror: Int = 0,
    val mirrorName: String = "",
    /** Consecutive failures on the current mirror: drives the backoff and the max retry count. */
    val failures: Int = 0,
    val nextTryAt: Long = 0L,
    val sha256: String = "",
    val error: String = "",
    /** What the mirrors already tried said, in order ("mesh: HTTP 404 | release: ..."), kept across retries and restarts. */
    val trail: String = "",
    /** The finished, verified file name inside the cache dir ("" until DONE). */
    val file: String = "",
    /** Transient (never persisted): bytes per second over the last seconds. */
    val bytesPerSec: Long = 0L,
) {
    val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else -1
    /** Seconds left at the current speed, or -1 when it cannot be said. */
    val etaSec: Long get() = if (total > 0 && bytesPerSec > 0 && state == DlState.RUNNING) ((total - bytes).coerceAtLeast(0) / bytesPerSec) else -1L
}

/** "1.4 MB/s · 2 min left" for a running row; "" when there is nothing to say. Pure. */
object DlText {
    fun size(b: Long): String = when {
        b >= 1_000_000_000L -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000L -> "%.1f MB".format(b / 1e6)
        b >= 1_000L -> "%.0f kB".format(b / 1e3)
        else -> "$b B"
    }

    fun eta(sec: Long): String = when {
        sec < 0 -> ""
        sec < 60 -> "${sec}s left"
        sec < 3600 -> "${(sec + 59) / 60} min left"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min left"
    }

    fun speedEta(j: DlJob): String =
        if (j.state != DlState.RUNNING || j.bytesPerSec <= 0) "" else listOf(size(j.bytesPerSec) + "/s", eta(j.etaSec)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** The one line a row or the Downloads panel shows for [j]. */
    fun line(j: DlJob): String = when (j.state) {
        DlState.QUEUED -> if (j.failures > 0) "retrying (${j.failures}) · ${j.error}" else "queued"
        DlState.RUNNING -> listOf(if (j.percent >= 0) "${j.percent}%" else "downloading", size(j.bytes) + (if (j.total > 0) " of " + size(j.total) else ""), speedEta(j)).filter { it.isNotEmpty() }.joinToString(" · ")
        DlState.VERIFYING -> "verifying"
        DlState.PAUSED -> "paused · " + size(j.bytes) + " kept"
        DlState.DONE -> "downloaded"
        DlState.FAILED -> "failed: ${j.error}"
        DlState.CANCELLED -> "cancelled"
    }
}
