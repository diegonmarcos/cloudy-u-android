package com.diegonmarcos.superapp.torrent

import java.io.File

/** What the user sees a torrent doing. [COMPLETED] is finished and not seeding; [SEEDING] is finished and uploading. */
enum class TorrentState { QUEUED, METADATA, CHECKING, DOWNLOADING, SEEDING, PAUSED, COMPLETED, ERROR }

/** One torrent, as the engine reports it on [TorrentEngine.refresh]: everything a downloads row draws. */
data class TorrentStatus(
    val id: String,
    val name: String,
    val state: TorrentState,
    /** 0..1 */
    val progress: Float,
    val totalBytes: Long,
    val doneBytes: Long,
    /** bytes per second */
    val downRate: Long,
    val upRate: Long,
    val seeds: Int,
    val peers: Int,
    val uploaded: Long,
    val downloaded: Long,
    val savePath: String,
    val magnet: String?,
    /** Whether this torrent keeps uploading once it has finished. */
    val seedEnabled: Boolean,
    val addedAt: Long,
    val error: String?,
) {
    val finished: Boolean get() = state == TorrentState.SEEDING || state == TorrentState.COMPLETED
    val active: Boolean get() = state == TorrentState.DOWNLOADING || state == TorrentState.METADATA || state == TorrentState.CHECKING || state == TorrentState.QUEUED
    /** Upload / download, 0 when nothing was downloaded yet. */
    val ratio: Float get() = (if (downloaded > 0) downloaded else totalBytes).let { if (it > 0) uploaded.toFloat() / it else 0f }
    /** Seconds left at the current rate, or -1 when unknown. */
    val etaSeconds: Long get() = if (downRate > 0 && totalBytes > doneBytes) (totalBytes - doneBytes) / downRate else -1L
}

/** Whether the BitTorrent session is usable. [Unavailable] carries the reason (native library missing, no storage): it is a state, not a crash. */
sealed class EngineState {
    object Stopped : EngineState()
    object Running : EngineState()
    class Unavailable(val reason: String) : EngineState()
}

/** The outcome of adding a torrent: the caller shows [Failed] / [Unavailable] as a sentence and never has to catch. */
sealed class AddResult {
    class Added(val id: String) : AddResult()
    class Duplicate(val id: String) : AddResult()
    class Invalid(val reason: String) : AddResult()
    class Unavailable(val reason: String) : AddResult()
    class Failed(val reason: String) : AddResult()
    val idOrNull: String? get() = when (this) { is Added -> id; is Duplicate -> id; else -> null }
}

/** The one sentence that says what an add did (a toast, a status line). */
fun AddResult.sentence(): String = when (this) {
    is AddResult.Added -> "Added to Torrent downloads."
    is AddResult.Duplicate -> "Already in Torrent downloads."
    is AddResult.Invalid -> reason
    is AddResult.Unavailable -> reason
    is AddResult.Failed -> "Could not add: $reason"
}

/** What the session says about one torrent, with no native types (the fake backend of the tests builds these). */
enum class Phase { METADATA, CHECKING, DOWNLOADING, FINISHED, SEEDING, UNKNOWN }

data class RawStatus(
    val id: String,
    val name: String,
    val phase: Phase,
    val paused: Boolean,
    val progress: Float,
    val totalBytes: Long,
    val doneBytes: Long,
    val downRate: Long,
    val upRate: Long,
    val seeds: Int,
    val peers: Int,
    val uploaded: Long,
    val downloaded: Long,
    val error: String? = null,
)

/** The session under the engine. The real one is [JlibtorrentBackend]; tests hand the engine a fake. */
interface TorrentBackend {
    /** Start the session. Throws (any Throwable, an UnsatisfiedLinkError included) when it cannot; the engine turns that into [EngineState.Unavailable]. */
    fun start()
    fun stop()
    fun addMagnet(id: String, magnet: String, saveDir: File, paused: Boolean)
    fun addTorrent(id: String, file: File, saveDir: File, paused: Boolean)
    /** The session's view of every torrent it holds, by id. A torrent still fetching metadata may be absent. */
    fun snapshot(): Map<String, RawStatus>
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String, deleteFiles: Boolean)
}
