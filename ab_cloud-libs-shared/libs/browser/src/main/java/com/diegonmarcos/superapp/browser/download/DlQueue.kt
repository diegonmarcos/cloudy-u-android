package com.diegonmarcos.superapp.browser.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import com.diegonmarcos.superapp.browser.MhtmlIntent
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The browser's download manager, one per process. It owns the list of downloads (persisted in
 * the `browser_downloads` prefs, the store the fleet manifest already declares, class device),
 * runs up to [DlPolicy.parallel] of them through [DlRunner], applies the Wi-Fi-only / metered
 * rules and the global speed limit, and keeps [DlService] (the dataSync foreground service) up
 * while anything is active. Torrents run through [DlTorrentBridge] when the app ships one.
 *
 * Survives: pause (Range resume), network loss (backoff, then WAITING_NETWORK until a network
 * the policy allows comes back), process death and reboot (state is on disk; [DlResumeJob]
 * re-starts the queue, [DlBootReceiver] schedules it after a boot).
 */
class DlQueue private constructor(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("browser_downloads", Context.MODE_PRIVATE)
    private val jobs = LinkedHashMap<String, DlJob>()
    private val live = ConcurrentHashMap<String, DlLive>()
    private val controls = ConcurrentHashMap<String, DlControl>()
    private val speed = ConcurrentHashMap<String, Pair<Long, Long>>()   // id -> (time, bytes) of the last sample
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "dl-worker").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var lastSave = 0L
    val limiter = DlRateLimiter()
    private val runner = DlRunner(DlHttpEngine(limiter))

    @Volatile var policy: DlPolicy = DlPolicy.fromJson(sp.getString(KEY_POLICY, null)); private set

    /** The app's BitTorrent engine, when it ships one (cloud-browser does; other hosts of libs:browser do not). */
    val torrent: DlTorrentBridge? by lazy { DlTorrentBridge.load(app) }

    init {
        com.diegonmarcos.superapp.browser.BrowserListMigration.ensure(app, "dljobs", "browser_downloads", KEY_JOBS)
        // A process that died mid-transfer left RUNNING rows: the core's reader sends them back to the queue (requeue).
        DlCodec.fromJson(sp.getString(KEY_JOBS, "[]"), requeue = true).forEach { j -> jobs[j.id] = j }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.postDelayed({ pump() }, 1_000) }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { enforceNetwork() } }
            })
        }
        limiter.bytesPerSec = policy.speedLimitKbps * 1024L
    }

    // ── reads ──

    fun all(): List<DlJob> = synchronized(jobs) { jobs.values.sortedByDescending { it.createdAt } }
    fun byId(id: String): DlJob? = synchronized(jobs) { jobs[id] }
    fun live(id: String): DlLive = live[id] ?: DlLive()
    fun hasWork(): Boolean = all().any { it.state.active }   // a seeding torrent is RUNNING, so it counts
    private fun hasTorrents() = all().any { it.kind == DlJob.KIND_TORRENT }
    fun running(): List<DlJob> = all().filter { it.state == DlState.RUNNING || it.state == DlState.RETRYING }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    // ── policy ──

    fun setPolicy(p: DlPolicy) {
        if (p == policy) return
        policy = p
        sp.edit().putString(KEY_POLICY, p.toJson().toString()).apply()
        limiter.bytesPerSec = p.speedLimitKbps * 1024L
        if (hasTorrents()) torrent?.applyPolicy(p)
        enforceNetwork(); pump()
    }

    // ── writes ──

    /**
     * Queue an HTTP(S) download. [cookie], [userAgent] and [referer] are the page's, so a signed-in
     * file downloads; the name comes from Content-Disposition, else the URL and MIME type.
     */
    fun add(url: String, userAgent: String?, referer: String?, cookie: String?, disposition: String?, mime: String?,
            dir: String, checksum: String? = null): DlJob {
        val name = DlFormat.dispositionName(disposition) ?: DlFormat.safeName(URLUtil.guessFileName(url, disposition, mime))
        val id = UUID.randomUUID().toString()
        val file = File(File(root(), id), name)
        val j = DlJob(id = id, url = url, fileName = name, mime = MhtmlIntent.downloadMime(name, mime).orEmpty(), dir = dir, userAgent = userAgent,
            referer = referer, cookie = cookie, checksum = checksum, file = file.path, saveToSharedStore = policy.sharedStore,
            createdAt = System.currentTimeMillis())
        put(j, save = true)
        pump()
        return j
    }

    /** Queue a magnet link or a .torrent (bytes) when the app ships a torrent engine; null when it does not. */
    fun addTorrent(magnetOrNull: String?, torrentBytes: ByteArray?, dir: String): DlJob? {
        val t = torrent ?: return null
        val id = UUID.randomUUID().toString()
        val save = File(File(root(), id), "torrent").apply { mkdirs() }
        val j = DlJob(id = id, url = magnetOrNull ?: "torrent:$id", fileName = DlTorrentBridge.displayName(magnetOrNull) ?: "Torrent",
            kind = DlJob.KIND_TORRENT, dir = dir, file = save.path, createdAt = System.currentTimeMillis(), seed = policy.seed)
        put(j, save = true)
        t.add(j, torrentBytes)
        pump()
        return byId(id)
    }

    /**
     * A .torrent link the page offered (DownloadListener): fetch the small file with the page's cookie,
     * agent and referer, then add it as a torrent. [done] gets the row, or null when it failed.
     */
    fun addTorrentUrl(url: String, userAgent: String?, referer: String?, cookie: String?, dir: String, done: (DlJob?) -> Unit) {
        exec.execute {
            val bytes = runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 30_000
                userAgent?.let { c.setRequestProperty("User-Agent", it) }
                cookie?.let { c.setRequestProperty("Cookie", it) }
                referer?.let { c.setRequestProperty("Referer", it) }
                try {
                    if (c.responseCode !in 200..299) null
                    else c.inputStream.use { s -> s.readNBytesCompat(MAX_TORRENT_FILE) }
                } finally { c.disconnect() }
            }.getOrNull()
            val j = bytes?.let { addTorrent(null, it, dir) }
            main.post { done(j) }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    fun pause(id: String) {
        val j = byId(id) ?: return
        if (j.kind == DlJob.KIND_TORRENT) { torrent?.pause(id); put(j.copy(state = DlState.PAUSED), true); return }
        controls[id]?.request(DlStop.PAUSE)
        if (j.state != DlState.RUNNING) put(j.copy(state = DlState.PAUSED), true)
    }

    fun resume(id: String) {
        val j = byId(id) ?: return
        if (j.state == DlState.COMPLETED && j.kind != DlJob.KIND_TORRENT) return
        put(j.copy(state = DlState.QUEUED, error = null, attempts = 0), true)
        pump()   // a torrent restarts there too (start -> bridge.resume), within the parallel limit
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String) {
        val j = byId(id) ?: return
        if (j.kind == DlJob.KIND_TORRENT) { torrent?.remove(id, deleteFiles = true); finishCancel(j); return }
        val c = controls[id]
        if (c != null) c.request(DlStop.CANCEL) else finishCancel(j)
    }

    /** Forget [id]; [deleteFile] also deletes what it downloaded (the published copy too). */
    fun remove(id: String, deleteFile: Boolean) {
        val j = byId(id) ?: return
        controls[id]?.request(DlStop.CANCEL)
        if (j.kind == DlJob.KIND_TORRENT) torrent?.remove(id, deleteFile)
        if (deleteFile) {
            File(j.file).let { f -> (if (f.parentFile?.name == j.id) f.parentFile else f)?.deleteRecursively() }
            j.publishedUri?.let { DlPublisher.delete(app, it) }
        }
        synchronized(jobs) { jobs.remove(id) }
        live.remove(id)
        save(force = true); notifyChanged()
    }

    fun pauseAll() = all().filter { it.state.active }.forEach { pause(it.id) }
    fun resumeAll() = all().filter { it.state == DlState.PAUSED || it.state == DlState.FAILED || it.state == DlState.WAITING_NETWORK }
        .forEach { resume(it.id) }

    /** Android stopped the service (dataSync time limit, or the job's window ended): park, resume later. */
    fun suspendAll() {
        all().filter { it.state == DlState.RUNNING || it.state == DlState.RETRYING }.forEach {
            if (it.kind == DlJob.KIND_TORRENT) put(it.copy(state = DlState.QUEUED), true) else controls[it.id]?.request(DlStop.TIMEOUT)
        }
        if (hasTorrents()) torrent?.suspend()
    }

    /** Forget everything (the files stay where they were published). */
    fun clear() {
        all().forEach { controls[it.id]?.request(DlStop.CANCEL) }
        synchronized(jobs) { jobs.clear() }
        save(force = true); notifyChanged()
    }

    // ── scheduling ──

    /** Start what the policy allows; park the rest. Safe to call any time, from any thread. */
    fun pump() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { pump() }; return }
        val ok = networkAllowed()
        val all = all().sortedBy { it.createdAt }
        // A torrent that only seeds does not hold a download slot.
        var running = all.count { (it.state == DlState.RUNNING || it.state == DlState.RETRYING) && !seedingOnly(it) }
        for (j in all) {
            if (j.state != DlState.QUEUED && j.state != DlState.WAITING_NETWORK) continue
            if (!ok) { if (j.state != DlState.WAITING_NETWORK) put(j.copy(state = DlState.WAITING_NETWORK), true); continue }
            if (running >= policy.parallel) { if (j.state == DlState.WAITING_NETWORK) put(j.copy(state = DlState.QUEUED), true); continue }
            running++
            start(j)
        }
        DlService.sync(app, this)
        if (hasWork()) DlResumeJob.schedule(app, policy, DlResumeJob.WATCHDOG_MS)
    }

    private fun seedingOnly(j: DlJob) = j.kind == DlJob.KIND_TORRENT && j.total > 0 && j.downloaded >= j.total

    private fun enforceNetwork() {
        if (networkAllowed()) { pump(); return }
        running().forEach {
            if (it.kind == DlJob.KIND_TORRENT) put(it.copy(state = DlState.WAITING_NETWORK), true) else controls[it.id]?.request(DlStop.NETWORK_POLICY)
        }
        if (hasTorrents()) torrent?.suspend()
    }

    private fun start(j0: DlJob) {
        if (j0.kind == DlJob.KIND_TORRENT) {
            put(j0.copy(state = DlState.RUNNING), true)
            torrent?.resume(j0.id)
            return
        }
        val control = DlControl()
        controls[j0.id] = control
        val j = j0.copy(state = DlState.RUNNING, error = null)
        put(j, true)
        exec.execute {
            val o = runCatching {
                runner.run(j, File(j.file), policy.segments, control, networkOk = { networkAllowed() },
                    onRetry = { r, _ -> put(r.copy(state = DlState.RETRYING), true) },
                    onProgress = { p -> progress(p) })
            }.getOrElse { DlOutcome.Failed(j, it.message ?: it.javaClass.simpleName, true) }
            controls.remove(j.id)
            settle(o)
        }
    }

    private fun progress(p: DlJob) {
        val now = System.currentTimeMillis()
        val prev = speed[p.id]
        if (prev == null || now - prev.first >= 1_000) {
            val bps = if (prev == null) 0 else ((p.downloaded - prev.second) * 1000 / (now - prev.first).coerceAtLeast(1)).coerceAtLeast(0)
            val old = live[p.id]?.bytesPerSec ?: 0
            val smooth = if (old == 0L) bps else (old * 2 + bps) / 3
            val eta = if (smooth > 0 && p.total > 0) (p.total - p.downloaded) / smooth else -1
            live[p.id] = DlLive(smooth, eta)
            speed[p.id] = now to p.downloaded
        }
        val cur = byId(p.id) ?: return
        put(p.copy(state = if (cur.state == DlState.RETRYING) DlState.RUNNING else cur.state, attempts = cur.attempts.coerceAtMost(p.attempts)), save = false)
    }

    private fun settle(o: DlOutcome) {
        val j = o.job
        speed.remove(j.id); live.remove(j.id)
        when (o) {
            is DlOutcome.Completed -> {
                val done = j.copy(state = DlState.COMPLETED, finishedAt = System.currentTimeMillis(), error = null)
                val uri = runCatching { DlPublisher.publish(app, done) }.getOrNull()
                val fin = if (uri != null) done.copy(publishedUri = uri) else done.copy(error = "Kept in app storage (could not publish to Downloads)")
                put(fin, true)
                DlService.notifyDone(app, fin)
            }
            is DlOutcome.Stopped -> when (o.reason) {
                DlStop.PAUSE -> put(j.copy(state = DlState.PAUSED), true)
                DlStop.CANCEL -> finishCancel(j)
                DlStop.NETWORK_POLICY -> put(j.copy(state = DlState.WAITING_NETWORK), true)
                DlStop.TIMEOUT -> put(j.copy(state = DlState.QUEUED), true)
            }
            is DlOutcome.Failed -> {
                put(j.copy(state = DlState.FAILED, error = o.error), true)
                DlService.notifyDone(app, byId(j.id) ?: j)
            }
        }
        pump()
    }

    private fun finishCancel(j: DlJob) {
        File(j.file).let { f -> (if (f.parentFile?.name == j.id) f.parentFile else f)?.deleteRecursively() }
        put(j.copy(state = DlState.CANCELLED, downloaded = 0, segments = emptyList(), finishedAt = System.currentTimeMillis()), true)
        pump()
    }

    /** The torrent engine reports here (state, totals, live numbers). */
    fun torrentUpdate(id: String, f: (DlJob) -> DlJob, l: DlLive?) {
        val j = byId(id) ?: return
        l?.let { live[id] = it }
        val n = f(j)
        put(n, save = n.state != j.state)
        if (n.state != j.state && n.state == DlState.COMPLETED) { DlService.notifyDone(app, n); pump() }
    }

    /** Is there a network the policy allows (Wi-Fi only, metered allowed)? */
    fun networkAllowed(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (policy.wifiOnly && !wifi) return false
        if (!policy.allowMetered && !unmetered) return false
        return true
    }

    // ── storage ──

    /** App-specific storage: no permission, removed with the app, direct file paths. */
    fun root(): File = (app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "downloads")).apply { mkdirs() }

    private fun put(j: DlJob, save: Boolean) {
        synchronized(jobs) { jobs[j.id] = j }
        save(force = save)
        notifyChanged()
    }

    private fun save(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 2_000) return
        lastSave = now
        sp.edit().putString(KEY_JOBS, DlCodec.toJson(all())).commit()
    }

    private fun notifyChanged() = listeners.forEach { runCatching { it() } }

    companion object {
        const val KEY_JOBS = "jobs_v2"
        const val KEY_POLICY = "policy_v2"
        const val MAX_TORRENT_FILE = 16 * 1024 * 1024
        @Volatile private var instance: DlQueue? = null
        fun get(ctx: Context): DlQueue = instance ?: synchronized(this) { instance ?: DlQueue(ctx).also { instance = it } }
    }
}
