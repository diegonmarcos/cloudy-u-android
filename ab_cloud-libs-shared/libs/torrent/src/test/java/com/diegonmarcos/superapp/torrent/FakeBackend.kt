package com.diegonmarcos.superapp.torrent

import java.io.File

/** A session with no peers: tests set each torrent's raw status by hand and read back what the engine asked of it. */
class FakeBackend(var failStart: Throwable? = null) : TorrentBackend {
    val raw = LinkedHashMap<String, RawStatus>()
    val calls = ArrayList<String>()
    var started = false
    var failAdd: Throwable? = null

    override fun start() { failStart?.let { throw it }; started = true; calls += "start" }
    override fun stop() { started = false; calls += "stop" }
    override fun addMagnet(id: String, magnet: String, saveDir: File, paused: Boolean) {
        failAdd?.let { throw it }
        calls += "addMagnet:$id:${saveDir.path}:$paused"
        raw[id] = RawStatus(id, "", Phase.METADATA, paused, 0f, 0, 0, 0, 0, 0, 0, 0, 0)
    }
    override fun addTorrent(id: String, file: File, saveDir: File, paused: Boolean) {
        failAdd?.let { throw it }
        calls += "addTorrent:$id:${saveDir.path}:$paused"
        raw[id] = RawStatus(id, "", Phase.CHECKING, paused, 0f, 0, 0, 0, 0, 0, 0, 0, 0)
    }
    override fun snapshot(): Map<String, RawStatus> = LinkedHashMap(raw)
    override fun pause(id: String) { calls += "pause:$id"; raw[id]?.let { raw[id] = it.copy(paused = true) } }
    override fun resume(id: String) { calls += "resume:$id"; raw[id]?.let { raw[id] = it.copy(paused = false) } }
    override fun remove(id: String, deleteFiles: Boolean) { calls += "remove:$id:$deleteFiles"; raw.remove(id) }

    /** Moves a torrent along: what libtorrent would report after some seconds. */
    fun progress(id: String, name: String, phase: Phase, progress: Float, total: Long, down: Long = 0, seeds: Int = 0, peers: Int = 0, up: Long = 0, uploaded: Long = 0, downloaded: Long = 0) {
        val old = raw.getValue(id)
        raw[id] = old.copy(name = name, phase = phase, progress = progress, totalBytes = total, doneBytes = (total * progress).toLong(),
            downRate = down, upRate = up, seeds = seeds, peers = peers, uploaded = uploaded, downloaded = downloaded)
    }
}
