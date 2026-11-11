package com.diegonmarcos.superapp.torrent

import java.io.File

/**
 * THE TORRENT ENGINE API. One instance per process ([TorrentEngines.get] on Android); the UI calls it, nothing else
 * touches the BitTorrent session.
 *
 *   add          [addMagnet], [addTorrentFile], [addTorrentBytes]  -> [AddResult] (never throws)
 *   track        [refresh] -> every torrent's [TorrentStatus] (progress, rates, seeds, peers, state); call it about once a second
 *   control      [pause], [resume], [remove] (optionally with the files), [setSeeding]
 *   save path    [TorrentSettings.saveDir] for new torrents, or `saveDir` per add
 *   seeding      per torrent ([setSeeding]) and by policy ([TorrentSettings.seedAfterComplete], [TorrentSettings.seedRatioLimit])
 *
 * It owns the list (persisted through [TorrentStore], re-added to the session on [start]) and the finished-torrent policy;
 * the session itself is a [TorrentBackend]. A session that cannot start (libtorrent missing on the device, no storage) is
 * [EngineState.Unavailable] with a reason, and every call answers with that reason instead of throwing.
 */
class TorrentEngine(
    private val backend: TorrentBackend,
    private val store: TorrentStore,
    val settings: TorrentSettings,
    /** Where a copy of each added .torrent is kept so the torrent can be re-added on the next launch. */
    private val keepDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var state: EngineState = EngineState.Stopped
        private set

    private val entries = LinkedHashMap<String, TorrentEntry>()
    private val errors = HashMap<String, String>()
    /** The biggest size each torrent has reported: libtorrent can report 0 once a torrent is complete. */
    private val totals = HashMap<String, Long>()
    private var loaded = false

    /** Called after a torrent is added or resumed (a download may have begun): the host starts its download service here. Never throws into the engine. */
    @Volatile var onDownloadStarted: (() -> Unit)? = null
    private fun downloadStarted() { runCatching { onDownloadStarted?.invoke() } }

    /** What the downloads are doing right now, for the background service ([TorrentActivity.keepAlive] is its run condition). */
    @Synchronized fun activity(): TorrentActivity = TorrentActivity.of(refresh())

    private fun loadOnce() { if (!loaded) { loaded = true; store.load().forEach { entries[it.id] = it; if (it.totalBytes > 0) totals[it.id] = it.totalBytes } } }

    private fun persist() { runCatching { store.save(entries.values.toList()) } }

    /** Starts the session and re-adds every remembered torrent. Safe to call again. */
    @Synchronized fun start(): EngineState {
        loadOnce()
        if (state is EngineState.Running) return state
        try {
            backend.start()
        } catch (t: Throwable) {
            state = EngineState.Unavailable("BitTorrent engine unavailable: " + (t.message ?: t.javaClass.simpleName))
            return state
        }
        state = EngineState.Running
        entries.values.toList().forEach { readd(it) }
        return state
    }

    @Synchronized fun stop() {
        stopPolling()
        if (state is EngineState.Running) runCatching { backend.stop() }
        state = EngineState.Stopped
    }

    private var ticker: Thread? = null

    /** Runs [refresh] every [intervalMs] on a daemon thread, so the finished-torrent policy (seed off / ratio limit) is applied
     *  while no screen is polling. Safe to call twice. */
    @Synchronized fun startPolling(intervalMs: Long = 5_000L) {
        if (ticker?.isAlive == true) return
        ticker = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
                if (state is EngineState.Running) runCatching { refresh() }
            }
        }, "torrent-policy").apply { isDaemon = true; start() }
    }

    @Synchronized fun stopPolling() { ticker?.interrupt(); ticker = null }

    private fun readd(e: TorrentEntry) {
        errors.remove(e.id)
        val dir = File(e.savePath)
        val prep = TorrentPaths.prepare(dir)
        if (!prep.ok) { errors[e.id] = prep.why.orEmpty(); return }
        val paused = e.paused || (e.completed && !e.seed)
        try {
            when {
                e.magnet != null -> backend.addMagnet(e.id, e.magnet, dir, paused)
                e.torrentFile != null && File(e.torrentFile).isFile -> backend.addTorrent(e.id, File(e.torrentFile), dir, paused)
                else -> { errors[e.id] = "The .torrent file is gone; remove the entry and add it again."; return }
            }
            // A session may add a torrent paused (jlibtorrent does): one that is meant to run is started explicitly.
            if (!paused) backend.resume(e.id)
        } catch (t: Throwable) { errors[e.id] = t.message ?: t.javaClass.simpleName }
    }

    // ── add ──────────────────────────────────────────────────────────────────

    @Synchronized fun addMagnet(uri: String, saveDir: File? = null, startPaused: Boolean = false): AddResult {
        val id = Magnet.infoHash(uri) ?: return AddResult.Invalid("Not a BitTorrent magnet link (needs xt=urn:btih:<info-hash>).")
        return add(id, Magnet.displayName(uri) ?: id, uri.trim(), null, saveDir, startPaused)
    }

    @Synchronized fun addTorrentFile(file: File, saveDir: File? = null, startPaused: Boolean = false): AddResult {
        val bytes = runCatching { file.readBytes() }.getOrElse { return AddResult.Invalid("Cannot read ${file.name}: ${it.message}") }
        return addTorrentBytes(file.name, bytes, saveDir, startPaused)
    }

    @Synchronized fun addTorrentBytes(label: String, bytes: ByteArray, saveDir: File? = null, startPaused: Boolean = false): AddResult {
        val meta = Magnet.parseTorrent(bytes) ?: return AddResult.Invalid("$label is not a valid .torrent file.")
        loadOnce()
        if (entries.containsKey(meta.infoHash)) return AddResult.Duplicate(meta.infoHash)
        val kept = File(keepDir, meta.infoHash + ".torrent")
        runCatching { keepDir.mkdirs(); kept.writeBytes(bytes) }.onFailure { return AddResult.Failed("Cannot keep the .torrent: ${it.message}") }
        val r = add(meta.infoHash, meta.name.ifEmpty { label.removeSuffix(".torrent") }, null, kept.path, saveDir, startPaused)
        if (r !is AddResult.Added) kept.delete()
        return r
    }

    private fun add(id: String, name: String, magnet: String?, file: String?, saveDir: File?, paused: Boolean): AddResult {
        loadOnce()
        if (entries.containsKey(id)) return AddResult.Duplicate(id)
        if (state !is EngineState.Running) start()
        (state as? EngineState.Unavailable)?.let { return AddResult.Unavailable(it.reason) }
        val dir = saveDir ?: settings.saveDir
        val prep = TorrentPaths.prepare(dir)
        if (!prep.ok) return AddResult.Failed(prep.why.orEmpty())
        try {
            if (magnet != null) backend.addMagnet(id, magnet, dir, paused) else backend.addTorrent(id, File(file!!), dir, paused)
            // A session may add a torrent paused (jlibtorrent does): one that is meant to run is started explicitly, no manual Resume.
            if (!paused) backend.resume(id)
        } catch (t: Throwable) {
            return AddResult.Failed(t.message ?: t.javaClass.simpleName)
        }
        entries[id] = TorrentEntry(id, name, magnet, file, dir.path, clock(), paused, settings.seedAfterComplete, false)
        persist()
        if (!paused) downloadStarted()
        return AddResult.Added(id)
    }

    // ── track ────────────────────────────────────────────────────────────────

    /** The finished-torrent policy runs here, then every torrent's status. */
    @Synchronized fun refresh(): List<TorrentStatus> {
        loadOnce()
        val raw = if (state is EngineState.Running) runCatching { backend.snapshot() }.getOrDefault(emptyMap()) else emptyMap()
        var changed = false
        for (id in entries.keys.toList()) {
            val r = raw[id] ?: continue
            if (r.totalBytes > 0) totals[id] = maxOf(totals[id] ?: 0L, r.totalBytes)
            var cur = entries.getValue(id)
            // The size is remembered across launches (a finished magnet torrent is re-added paused, with no metadata, and would show 0 B).
            totals[id]?.let { if (it != cur.totalBytes) { cur = cur.copy(totalBytes = it); changed = true } }
            if (r.name.isNotEmpty() && r.name != cur.name) { cur = cur.copy(name = r.name); changed = true }
            if (!cur.completed && done(r)) { cur = cur.copy(completed = true); changed = true }
            if (cur.completed && cur.seed && ratioReached(r)) { cur = cur.copy(seed = false); changed = true }
            if (cur.completed && !cur.seed && !r.paused && !cur.paused) runCatching { backend.pause(id) }
            entries[id] = cur
        }
        if (changed) persist()
        return entries.values.map { status(it, raw[it.id]) }
    }

    fun list(): List<TorrentStatus> = refresh()

    @Synchronized fun status(id: String): TorrentStatus? = refresh().firstOrNull { it.id == id }

    private fun done(r: RawStatus) = r.phase == Phase.FINISHED || r.phase == Phase.SEEDING || (r.totalBytes > 0 && r.progress >= 1f && r.doneBytes >= r.totalBytes)

    private fun ratioReached(r: RawStatus): Boolean {
        val limit = settings.seedRatioLimit
        if (limit <= 0f) return false
        val base = maxOf(r.downloaded, r.totalBytes)
        return base > 0 && r.uploaded.toFloat() / base >= limit
    }

    private fun status(e: TorrentEntry, r: RawStatus?): TorrentStatus {
        val un = (state as? EngineState.Unavailable)?.reason
        val err = errors[e.id] ?: r?.error
        val st = if (r == null) when {
            err != null -> TorrentState.ERROR
            e.completed -> if (e.paused) TorrentState.PAUSED else TorrentState.COMPLETED
            un != null -> TorrentState.ERROR
            e.paused -> TorrentState.PAUSED
            e.magnet != null -> TorrentState.METADATA
            else -> TorrentState.QUEUED
        } else when {
            err != null -> TorrentState.ERROR
            e.paused -> TorrentState.PAUSED
            e.completed -> if (r.paused || !e.seed) TorrentState.COMPLETED else TorrentState.SEEDING
            r.paused -> TorrentState.PAUSED
            else -> when (r.phase) {
                Phase.METADATA -> TorrentState.METADATA
                Phase.CHECKING -> TorrentState.CHECKING
                Phase.DOWNLOADING -> TorrentState.DOWNLOADING
                Phase.FINISHED, Phase.SEEDING -> TorrentState.COMPLETED
                Phase.UNKNOWN -> TorrentState.QUEUED
            }
        }
        val live = st != TorrentState.PAUSED && st != TorrentState.COMPLETED && st != TorrentState.ERROR
        return TorrentStatus(
            id = e.id, name = r?.name?.takeIf { it.isNotEmpty() } ?: e.name, state = st,
            progress = if (e.completed) 1f else r?.progress ?: 0f,
            totalBytes = totals[e.id] ?: r?.totalBytes ?: 0L, doneBytes = if (e.completed) (totals[e.id] ?: r?.totalBytes ?: 0L) else r?.doneBytes ?: 0L,
            downRate = if (live) r?.downRate ?: 0L else 0L, upRate = if (live) r?.upRate ?: 0L else 0L,
            seeds = if (live) r?.seeds ?: 0 else 0, peers = if (live) r?.peers ?: 0 else 0,
            uploaded = r?.uploaded ?: 0L, downloaded = r?.downloaded ?: 0L,
            savePath = e.savePath, magnet = e.magnet, seedEnabled = e.seed, addedAt = e.addedAt,
            error = err ?: if (st == TorrentState.ERROR) un else null,
        )
    }

    // ── control ──────────────────────────────────────────────────────────────

    @Synchronized fun pause(id: String): Boolean {
        loadOnce()
        val e = entries[id] ?: return false
        if (state is EngineState.Running) runCatching { backend.pause(id) }
        entries[id] = e.copy(paused = true); persist()
        return true
    }

    /** Resuming a finished torrent starts seeding it. */
    @Synchronized fun resume(id: String): Boolean {
        loadOnce()
        val e = entries[id] ?: return false
        if (state is EngineState.Running) runCatching { backend.resume(id) }
        entries[id] = e.copy(paused = false, seed = e.seed || e.completed); persist()
        if (!e.completed) downloadStarted()
        return true
    }

    /** Removes the torrent from the list and the session; [deleteFiles] also deletes what it downloaded. */
    @Synchronized fun remove(id: String, deleteFiles: Boolean = false): Boolean {
        loadOnce()
        val e = entries.remove(id) ?: return false
        if (state is EngineState.Running) runCatching { backend.remove(id, deleteFiles) }
        e.torrentFile?.let { File(it).delete() }
        errors.remove(id); totals.remove(id)
        persist()
        return true
    }

    /** Seed control: whether this torrent keeps uploading after it finishes. Takes effect at once on a finished torrent. */
    @Synchronized fun setSeeding(id: String, enabled: Boolean): Boolean {
        loadOnce()
        val e = entries[id] ?: return false
        entries[id] = e.copy(seed = enabled); persist()
        if (e.completed && !e.paused && state is EngineState.Running) runCatching { if (enabled) backend.resume(id) else backend.pause(id) }
        return true
    }

    /** Ids of the torrents that are downloading right now. */
    @Synchronized fun activeIds(): List<String> = refresh().filter { it.active }.map { it.id }

    @Synchronized fun pauseAll() { entries.keys.toList().forEach { pause(it) } }
    @Synchronized fun resumeAll() { entries.keys.toList().forEach { resume(it) } }
}
