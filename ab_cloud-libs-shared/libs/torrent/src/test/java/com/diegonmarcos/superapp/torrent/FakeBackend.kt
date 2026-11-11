package com.diegonmarcos.superapp.torrent

import java.io.File

/** A session with no peers: tests set each torrent's raw status by hand and read back what the engine asked of it. */
/** [startsPaused] mimics jlibtorrent: SessionManager.download() adds the torrent PAUSED until it is resumed. */
class FakeBackend(var failStart: Throwable? = null, val startsPaused: Boolean = false) : TorrentBackend {
    val raw = LinkedHashMap<String, RawStatus>()
    val calls = ArrayList<String>()
    var started = false
    var failAdd: Throwable? = null
    /** What the session saw of the save folder at the moment of each add (libtorrent needs it to exist and be writable). */
    class AddSeen(val id: String, val dir: File, val isDir: Boolean, val writable: Boolean, val magnet: String?, val file: File?)
    val adds = ArrayList<AddSeen>()

    override fun start() { failStart?.let { throw it }; started = true; calls += "start" }
    override fun stop() { started = false; calls += "stop" }
    override fun addMagnet(id: String, magnet: String, saveDir: File, paused: Boolean) {
        failAdd?.let { throw it }
        calls += "addMagnet:$id:${saveDir.path}:$paused"
        adds += AddSeen(id, saveDir, saveDir.isDirectory, saveDir.canWrite(), magnet, null)
        raw[id] = RawStatus(id, "", Phase.METADATA, paused || startsPaused, 0f, 0, 0, 0, 0, 0, 0, 0, 0)
    }
    override fun addTorrent(id: String, file: File, saveDir: File, paused: Boolean) {
        failAdd?.let { throw it }
        calls += "addTorrent:$id:${saveDir.path}:$paused"
        adds += AddSeen(id, saveDir, saveDir.isDirectory, saveDir.canWrite(), null, file)
        raw[id] = RawStatus(id, "", Phase.CHECKING, paused || startsPaused, 0f, 0, 0, 0, 0, 0, 0, 0, 0)
    }
    override fun snapshot(): Map<String, RawStatus> = LinkedHashMap(raw)
    override fun pause(id: String) { calls += "pause:$id"; raw[id]?.let { raw[id] = it.copy(paused = true) } }
    override fun resume(id: String) { calls += "resume:$id"; raw[id]?.let { raw[id] = it.copy(paused = false) } }
    override fun remove(id: String, deleteFiles: Boolean) { calls += "remove:$id:$deleteFiles"; raw.remove(id) }

    /** What libtorrent does when it downloads: bytes land in the folder the session was given, under the torrent's name. */
    fun land(id: String, name: String, bytes: Int): File {
        val dir = adds.last { it.id == id }.dir
        return File(dir, name).also { it.writeBytes(ByteArray(bytes) { 7 }) }
    }

    /** Moves a torrent along: what libtorrent would report after some seconds. */
    fun progress(id: String, name: String, phase: Phase, progress: Float, total: Long, down: Long = 0, seeds: Int = 0, peers: Int = 0, up: Long = 0, uploaded: Long = 0, downloaded: Long = 0) {
        val old = raw.getValue(id)
        raw[id] = old.copy(name = name, phase = phase, progress = progress, totalBytes = total, doneBytes = (total * progress).toLong(),
            downRate = down, upRate = up, seeds = seeds, peers = peers, uploaded = uploaded, downloaded = downloaded)
    }
}
