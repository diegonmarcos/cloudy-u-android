package com.diegonmarcos.superapp.torrent

import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.SessionHandle
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus as LtStatus
import java.io.File

/**
 * The real BitTorrent session: libtorrent through jlibtorrent. This is the ONLY class in the lib that names a native
 * type. The native library loads when [SessionManager] is first constructed; if that throws (an ABI the artifacts do not
 * carry), [TorrentEngine.start] turns it into [EngineState.Unavailable].
 *
 * Torrents are tracked by info-hash id: the engine owns the list, this class owns the handles. A torrent added paused
 * is paused as soon as libtorrent has its handle (a magnet's handle appears asynchronously).
 */
internal class JlibtorrentBackend : TorrentBackend {
    private var session: SessionManager? = null
    private val known = LinkedHashSet<String>()
    private val pendingPause = HashSet<String>()
    /** Torrents that must run but whose handle libtorrent has not produced yet: SessionManager.download() adds PAUSED, so each is resumed once. */
    private val pendingResume = HashSet<String>()

    @Synchronized override fun start() {
        if (session?.isRunning == true) return
        val s = SessionManager()
        s.start()
        session = s
    }

    @Synchronized override fun stop() {
        session?.stop()
        session = null
        known.clear(); pendingPause.clear(); pendingResume.clear()
    }

    private fun s(): SessionManager = session ?: error("the BitTorrent session is not running")

    @Synchronized override fun addMagnet(id: String, magnet: String, saveDir: File, paused: Boolean) {
        s().download(magnet, saveDir)
        known += id
        if (paused) pendingPause += id else pendingResume += id
    }

    @Synchronized override fun addTorrent(id: String, file: File, saveDir: File, paused: Boolean) {
        s().download(TorrentInfo(file), saveDir)
        known += id
        if (paused) pendingPause += id else pendingResume += id
    }

    private fun handle(id: String) = s().find(Sha1Hash(id))

    @Synchronized override fun snapshot(): Map<String, RawStatus> {
        val sm = session ?: return emptyMap()
        val out = LinkedHashMap<String, RawStatus>()
        for (id in known) {
            val h = sm.find(Sha1Hash(id)) ?: continue
            if (!h.isValid) continue
            if (id in pendingPause) { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause(); pendingPause -= id }
            // download() hands back a PAUSED, not auto-managed torrent: without this it would sit at 0 bytes until a manual Resume.
            if (id in pendingResume) { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.resume(); pendingResume -= id }
            val st = h.status()
            out[id] = raw(id, st)
        }
        return out
    }

    private fun raw(id: String, st: LtStatus): RawStatus {
        val phase = when (st.state()) {
            LtStatus.State.DOWNLOADING_METADATA -> Phase.METADATA
            LtStatus.State.CHECKING_FILES, LtStatus.State.CHECKING_RESUME_DATA, LtStatus.State.ALLOCATING -> Phase.CHECKING
            LtStatus.State.DOWNLOADING -> Phase.DOWNLOADING
            LtStatus.State.FINISHED -> Phase.FINISHED
            LtStatus.State.SEEDING -> Phase.SEEDING
            else -> Phase.UNKNOWN
        }
        val ec = st.errorCode()
        return RawStatus(
            id = id, name = st.name().orEmpty(), phase = phase,
            paused = st.flags().and_(TorrentFlags.PAUSED).nonZero(),
            progress = st.progress(), totalBytes = maxOf(st.total(), st.totalWanted(), st.totalDone()), doneBytes = st.totalDone(),
            downRate = st.downloadPayloadRate().toLong(), upRate = st.uploadPayloadRate().toLong(),
            seeds = st.numSeeds(), peers = st.numPeers(),
            uploaded = st.allTimeUpload(), downloaded = st.allTimeDownload(),
            error = if (ec.isError) ec.message() else null,
        )
    }

    @Synchronized override fun pause(id: String) {
        val h = handle(id)
        if (h == null) { pendingPause += id; return }
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.pause()
    }

    @Synchronized override fun resume(id: String) {
        pendingPause -= id
        val h = handle(id)
        if (h == null) pendingResume += id else { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.resume() }
    }

    @Synchronized override fun remove(id: String, deleteFiles: Boolean) {
        known -= id; pendingPause -= id
        val h = handle(id) ?: return
        if (deleteFiles) s().remove(h, SessionHandle.DELETE_FILES) else s().remove(h)
    }
}
