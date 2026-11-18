package com.diegonmarcos.superapp.torrent

import android.util.Log
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import org.libtorrent4j.TorrentStatus as LtStatus
import java.io.File

/**
 * The real BitTorrent session: libtorrent 2.1 through libtorrent4j (#1790). This is the ONLY class in the lib that names a
 * native type. The native library loads when [SessionManager] is first constructed; if that throws (an ABI the artifacts do
 * not carry), [TorrentEngine.start] turns it into [EngineState.Unavailable].
 *
 * #1790 root cause of "downloads do not work": the engine was jlibtorrent 1.2.0.18, i.e. libtorrent 1.2.0.0 (2019) with
 * OpenSSL 1.1.1 (2018). Its x86_64 build is 4 KB-linked, so the x86_64 (emulator) APK carried NO engine at all and could
 * never be tested end to end; and its 2019 network-interface code predates Android 11's netlink restrictions for apps that
 * target API 30+ (this app targets 35), which leaves the session without usable listen sockets on a current phone. libtorrent4j
 * 2.1.0-39 ships libtorrent 2.1 with OpenSSL 3.5 for arm64 AND x86_64, both 16 KB LOAD-aligned (the same artifacts Cloud
 * Browser already links), so the phone and the emulator run the same engine.
 *
 * Web seeds (BEP 19, `url-list`) and HTTP seeds are libtorrent's own: a torrent's web seeds are used with no extra code, so an
 * Internet Archive torrent (served by archive.org's own web seed) completes even with no peers. [RawStatus.webSeeds] reports how
 * many the session holds for a torrent.
 *
 * Torrents are tracked by info-hash id: the engine owns the list, this class owns the handles. A torrent added paused is paused
 * as soon as libtorrent has its handle (a magnet's handle appears asynchronously). Errors the session raises (tracker, web
 * seed, file, metadata, listen) are kept per torrent as the last error sentence and logged under [TAG], so a stuck download says why.
 */
internal class LibtorrentBackend : TorrentBackend {
    private var session: SessionManager? = null
    private val known = LinkedHashSet<String>()
    private val pendingPause = HashSet<String>()
    /** Torrents that must run but whose handle libtorrent has not produced yet: the add hands back a PAUSED torrent, so each is resumed once. */
    private val pendingResume = HashSet<String>()
    /** The last error the session raised per torrent (tracker / web seed / file), shown while the torrent has no error of its own. */
    private val lastAlert = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var listenError: String? = null

    // NOTHING NATIVE AT CONSTRUCTION: the first libtorrent4j class touched loads the native library, and a device (or a JVM test)
    // without it must get EngineState.Unavailable from start()'s catch, never an error out of TorrentEngines.get(). So the listener
    // and the watched alert types are built on first use, inside start().
    private val listener: AlertListener by lazy { object : AlertListener {
        override fun types(): IntArray = WATCHED.map { it.swig() }.toIntArray()
        override fun alert(a: Alert<*>) {
            runCatching {
                when (a.type()) {
                    AlertType.LISTEN_FAILED -> { listenError = a.message(); Log.w(TAG, a.message()) }
                    AlertType.LISTEN_SUCCEEDED -> { listenError = null; Log.i(TAG, a.message()) }
                    AlertType.TRACKER_ERROR, AlertType.URL_SEED, AlertType.FILE_ERROR, AlertType.METADATA_FAILED,
                    AlertType.TORRENT_ERROR, AlertType.SAVE_RESUME_DATA_FAILED -> {
                        val ta = a as? org.libtorrent4j.alerts.TorrentAlert<*>
                        val id = ta?.handle()?.takeIf { it.isValid }?.infoHash()?.toHex()?.lowercase()
                        if (id != null) lastAlert[id] = a.message()
                        Log.w(TAG, a.message())
                    }
                    AlertType.TORRENT_FINISHED, AlertType.ADD_TORRENT, AlertType.METADATA_RECEIVED -> Log.i(TAG, a.message())
                    else -> Unit
                }
            }
        }
    } }

    @Synchronized override fun start() {
        if (session?.isRunning == true) return
        val s = SessionManager()
        s.addListener(listener)
        s.start(SessionParams(pack()))
        session = s
    }

    @Synchronized override fun stop() {
        session?.let { runCatching { it.removeListener(listener) }; it.stop() }
        session = null
        known.clear(); pendingPause.clear(); pendingResume.clear(); lastAlert.clear(); seedsOf.clear()
    }

    private fun s(): SessionManager = session ?: error("the BitTorrent session is not running")

    @Synchronized override fun addMagnet(id: String, magnet: String, saveDir: File, paused: Boolean) {
        s().download(magnet, saveDir, torrent_flags_t())
        known += id
        if (paused) pendingPause += id else pendingResume += id
    }

    /**
     * #1790 a .torrent goes in through libtorrent's own loader (load_torrent_file -> add_torrent_params), NEVER through
     * SessionManager.download(TorrentInfo): that path only sets the torrent info, and libtorrent 2.1 reads a torrent's TRACKERS and
     * WEB SEEDS from add_torrent_params, not from the info, so such a torrent ran with no tracker and no web seed (the emulator showed
     * 0 peers and 0 bytes for 20 minutes on an Internet Archive torrent). The loader fills trackers, tiers and url seeds from the file.
     * Its web seeds are also kept here ([seedsOf]) and added to the handle if it reports none.
     */
    @Synchronized override fun addTorrent(id: String, file: File, saveDir: File, paused: Boolean) {
        val sm = s()
        if (sm.find(Sha1Hash.parseHex(id)) == null) {
            val ec = org.libtorrent4j.swig.error_code()
            val p = org.libtorrent4j.swig.libtorrent.load_torrent_file(file.absolutePath, ec, org.libtorrent4j.swig.load_torrent_limits())
            if (ec.value() != 0) error("cannot read ${file.name}: ${ec.message()}")
            p.setSave_path(saveDir.absolutePath)
            p.setFlags(p.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv()))
            sm.swig().async_add_torrent(p)
        }
        seedsOf[id] = runCatching { Magnet.parseTorrent(file.readBytes())?.webSeeds.orEmpty() }.getOrDefault(emptyList())
        known += id
        if (paused) pendingPause += id else pendingResume += id
    }

    /** #1790 the web seeds each .torrent declares (BEP 19 url-list), re-added to a handle that reports none. */
    private val seedsOf = HashMap<String, List<String>>()

    private fun handle(id: String) = s().find(Sha1Hash.parseHex(id))

    @Synchronized override fun snapshot(): Map<String, RawStatus> {
        val sm = session ?: return emptyMap()
        val out = LinkedHashMap<String, RawStatus>()
        for (id in known) {
            val h = sm.find(Sha1Hash.parseHex(id)) ?: continue
            if (!h.isValid) continue
            if (id in pendingPause) { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause(); pendingPause -= id }
            // The add hands back a PAUSED torrent: without this it would sit at 0 bytes until a manual Resume.
            if (id in pendingResume) { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.resume(); pendingResume -= id }
            var seeds = runCatching { h.urlSeeds().size }.getOrDefault(0)
            if (seeds == 0) seedsOf[id]?.takeIf { it.isNotEmpty() }?.let { ws ->
                ws.forEach { u -> runCatching { h.addUrlSeed(u) } }
                Log.i(TAG, "added ${ws.size} web seed(s) to $id: $ws")
                seeds = runCatching { h.urlSeeds().size }.getOrDefault(0)
            }
            val st = h.status()
            out[id] = raw(id, st, seeds)
        }
        return out
    }

    private fun raw(id: String, st: LtStatus, webSeeds: Int): RawStatus {
        val phase = when (st.state()) {
            LtStatus.State.DOWNLOADING_METADATA -> Phase.METADATA
            LtStatus.State.CHECKING_FILES, LtStatus.State.CHECKING_RESUME_DATA -> Phase.CHECKING
            LtStatus.State.DOWNLOADING -> Phase.DOWNLOADING
            LtStatus.State.FINISHED -> Phase.FINISHED
            LtStatus.State.SEEDING -> Phase.SEEDING
            else -> Phase.UNKNOWN
        }
        val ec = st.errorCode()
        val err = if (ec.isError) ec.message else null
        return RawStatus(
            id = id, name = st.name().orEmpty(), phase = phase,
            paused = st.flags().and_(TorrentFlags.PAUSED).non_zero(),
            progress = st.progress(), totalBytes = maxOf(st.total(), st.totalWanted(), st.totalDone()), doneBytes = st.totalDone(),
            downRate = st.downloadPayloadRate().toLong(), upRate = st.uploadPayloadRate().toLong(),
            seeds = st.numSeeds(), peers = st.numPeers(),
            uploaded = st.allTimeUpload(), downloaded = st.allTimeDownload(),
            error = err,
            webSeeds = webSeeds,
            note = lastAlert[id] ?: listenError,
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
        known -= id; pendingPause -= id; pendingResume -= id; lastAlert.remove(id); seedsOf.remove(id)
        val h = handle(id) ?: return
        if (deleteFiles) s().remove(h, SessionHandle.DELETE_FILES) else s().remove(h)
    }

    companion object {
        const val TAG = "CloudTorrent"

        private val WATCHED by lazy { listOf(AlertType.LISTEN_FAILED, AlertType.LISTEN_SUCCEEDED, AlertType.TRACKER_ERROR, AlertType.URL_SEED,
            AlertType.FILE_ERROR, AlertType.METADATA_FAILED, AlertType.TORRENT_ERROR, AlertType.SAVE_RESUME_DATA_FAILED,
            AlertType.TORRENT_FINISHED, AlertType.ADD_TORRENT, AlertType.METADATA_RECEIVED) }

        /** DHT, LSD, uTP in and out, listening on every interface (IPv4 and IPv6, any port): libtorrent's defaults made explicit. */
        fun pack(): SettingsPack = SettingsPack().apply {
            setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0,[::]:0")
            setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
        }
    }
}
