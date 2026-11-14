package com.diegonmarcos.clouddrive.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import android.util.LruCache
import android.webkit.MimeTypeMap
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #579 the Files tab's Android-side controller: holds the pure [FilesUiState],
 * loads listings on IO, runs the long jobs (copy, move, delete, zip, extract,
 * search, properties) as cancellable coroutines with live progress, and tells the
 * screen what to say. Compose observes the StateFlows; nothing here draws.
 */
class FilesController(
    private val ctx: Context,
    initial: FilesUiState,
    private val scope: CoroutineScope,
    val prefs: DrivePrefs,
) {
    val state = MutableStateFlow(initial)

    data class Listing(
        val entries: List<FileOps.Entry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        /** free, total — only for a location where a storage bar makes sense. */
        val usage: Pair<Long, Long>? = null,
    )

    /** Listings by [Location.key]. A location that is not here has not been asked for yet. */
    val listings = MutableStateFlow<Map<String, Listing>>(emptyMap())

    data class TransferJob(
        val id: Long,
        val title: String,
        val detail: String = "",
        val fraction: Float? = null,
        val done: Boolean = false,
        val ok: Boolean? = null,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
    )

    val jobs = MutableStateFlow<List<TransferJob>>(emptyList())

    /** One-line outcomes for the snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> get() = _messages
    /** Convert ▸ a file a conversion just wrote: the screen offers to open it (snackbar action). */
    private val _converted = MutableSharedFlow<File>(extraBufferCapacity = 8)
    val converted: SharedFlow<File> get() = _converted

    val mimes = FileOps.MimeResolver { ext -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }
    private val jobIds = AtomicLong(0)
    private val thumbs = LruCache<String, Bitmap>(96)

    // ── state ───────────────────────────────────────────────────────────────

    fun update(f: (FilesUiState) -> FilesUiState) { state.update(f) }

    val s: FilesUiState get() = state.value

    fun open(id: PaneId, target: Location) { update { FilesReducer.open(it, id, target) }; ensureLoaded(target) }
    fun openTab(id: PaneId, target: Location) { update { FilesReducer.openTab(it, id, target, Declarations.files.tabsPerPaneMax) }; ensureLoaded(target) }
    /**
     * #1696 Up, checked off the main thread: the parent must be a folder this app can list (or reach through the
     * terminal), and a root's parent only when it sits in another root; otherwise Up stops and the snackbar says why.
     * Returns whether there is a parent at all (the same contract as before).
     */
    fun up(id: PaneId): Boolean {
        val from = s.pane(id).location
        if (from.parentOrNull() == null) { _messages.tryEmit(ctx.getString(R.string.files_up_stop_top)); return false }
        scope.launch {
            val decision = withContext(Dispatchers.IO) { FilesNav.up(from, accessibleRoots(), ::canList) }
            when (decision) {
                is FilesNav.UpDecision.Go -> if (s.pane(id).location == from) { update { FilesReducer.open(it, id, decision.target) }; ensureLoaded(decision.target) }
                is FilesNav.UpDecision.Stop -> _messages.tryEmit(ctx.getString(when (decision.reason) {
                    FilesNav.StopReason.TOP -> R.string.files_up_stop_top
                    FilesNav.StopReason.UNREADABLE -> R.string.files_up_stop_unreadable
                    FilesNav.StopReason.ROOT_BOUNDARY -> R.string.files_up_stop_root
                }))
            }
        }
        return true
    }

    /** #1696 the declared storage home (Environment.getExternalStorageDirectory, /storage/emulated/0). */
    val homeLocation: Location.Local get() = Location.Local(runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrDefault(java.io.File(ctx.filesDir, "").absolutePath))

    /** #1696 Home from any state: the pane's tab opens the storage home. */
    fun home(id: PaneId) { val h = homeLocation; update { FilesNav.home(it, id, h) }; ensureLoaded(h) }

    /** #1696 the accessible roots (volumes, app dirs, declared places); the breadcrumb and Up read them. */
    val roots = MutableStateFlow<List<String>>(emptyList())

    /** Disk + StorageManager: call on IO only. */
    fun accessibleRoots(): List<String> {
        val out = linkedSetOf<String>()
        runCatching { out += homeLocation.path }
        runCatching { Places.declared(ctx).mapNotNullTo(out) { it.location?.path } }
        runCatching { ctx.getExternalFilesDirs(null).filterNotNull().mapTo(out) { it.absolutePath } }
        runCatching { ctx.filesDir.absolutePath.let { out += it } }
        runCatching { Places.discovered(ctx, prefs.snapshot.value, Places.hasAllFilesAccess(ctx)).mapNotNullTo(out) { it.location?.path } }
        runCatching { prefs.snapshot.value.bookmarks.forEach { out += it } }
        return out.toList().also { roots.value = it }
    }

    /** #1696 a real list attempt: a readable folder whose listing returns, or one the terminal lists for us. Disk: IO only. */
    fun canList(path: String): Boolean = runCatching {
        val dir = File(path)
        (dir.isDirectory && dir.canRead() && dir.list() != null) ||
            com.diegonmarcos.cloudlib.sharedstore.StorePaths.listViaOwner(com.diegonmarcos.cloudlib.sharedstore.StoreAccess.paths, dir.absolutePath, com.diegonmarcos.cloudlib.sharedstore.StoreAccess.userId(), dir.isDirectory, com.diegonmarcos.cloudlib.sharedstore.StoreAccess.mode(ctx))
    }.getOrDefault(false)

    /** #1696 a restored (or stuck) location this app cannot list goes Home on launch, with the reason. */
    fun recoverRestored() {
        scope.launch {
            val home = homeLocation
            val moved = withContext(Dispatchers.IO) {
                accessibleRoots()
                val (fixed, n) = FilesNav.recover(s, home, ::canList)
                if (n > 0) state.value = fixed
                n
            }
            if (moved > 0) { ensureLoaded(home); _messages.tryEmit(ctx.getString(R.string.files_restored_home)) }
        }
    }

    init { recoverRestored() }
    /** True when the pane consumed the back press. */
    fun back(id: PaneId): Boolean { val next = FilesReducer.back(s, id) ?: return false; state.value = next; ensureLoaded(next.pane(id).location); return true }
    fun activate(id: PaneId) = update { FilesReducer.activate(it, id) }
    fun setDual(dual: Boolean) { update { FilesReducer.setDual(it, dual) }; prefs.setDualPane(dual) }

    /** Navigates the ACTIVE pane to the folder of [path] and selects the file — the engines' hand-off. */
    fun reveal(path: String) {
        val f = File(path)
        val folder = if (f.isDirectory) f else f.parentFile ?: return
        open(s.active, Location.Local(folder.absolutePath))
        if (f.isFile) update { FilesReducer.selectAll(it, it.active, listOf(Location.Local(f.absolutePath).key)) }
    }

    // ── listings ────────────────────────────────────────────────────────────

    fun ensureLoaded(loc: Location) { if (!listings.value.containsKey(loc.key)) reload(loc) }

    fun reload(loc: Location) {
        listings.update { it + (loc.key to (it[loc.key]?.copy(loading = true) ?: Listing())) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (loc) {
                        is Location.Local -> {
                            val dir = File(loc.path)
                            val viaTerminal = com.diegonmarcos.cloudlib.sharedstore.StorePaths.listViaOwner(com.diegonmarcos.cloudlib.sharedstore.StoreAccess.paths, dir.absolutePath, com.diegonmarcos.cloudlib.sharedstore.StoreAccess.userId(), dir.isDirectory, com.diegonmarcos.cloudlib.sharedstore.StoreAccess.mode(ctx))
                            val entries = if (viaTerminal) ExecListing.list(ctx, dir, mimes, showHidden = true) else FileOps.list(dir, mimes, showHidden = true)
                            val usage = if (viaTerminal) null else usageOf(dir)
                            Listing(entries, loading = false, usage = usage)
                        }
                        is Location.Archive -> Listing(ArchiveFs.list(loc, mimes), loading = false)
                    }
                }
            }
            listings.update { it + (loc.key to result.getOrElse { e -> Listing(loading = false, error = e.message ?: e.toString()) }) }
        }
    }

    /** Reload every pane whose current location is [loc] and drop stale children. */
    fun refreshShowing(loc: Location) { reload(loc); loc.parentOrNull()?.let { p -> if (listings.value.containsKey(p.key)) reload(p) } }

    fun refreshActive() { reload(s.activePane.location) }

    /** Pull-to-refresh of one pane: re-scan the folder it shows (the same load path as every other reload, on IO). */
    fun refreshPane(id: PaneId) { reload(s.pane(id).location) }

    private fun usageOf(dir: File): Pair<Long, Long>? = runCatching { val st = StatFs(dir.absolutePath); st.availableBytes to st.totalBytes }.getOrNull()

    /** The rows a pane shows: hidden filter, the declared filter, then the pane's sort. Pure, so the JVM suite can hold it. */
    /** The inline search's in-place filter (a non-recursive scope): applied to the pane showing [LiveFilter.locationKey] only. Snapshot state, so the pane recomposes as the query changes. */
    data class LiveFilter(val locationKey: String, val query: String, val scope: Declarations.SearchScopeDecl)
    var liveFilter by androidx.compose.runtime.mutableStateOf<LiveFilter?>(null)

    fun visible(pane: PaneState, listing: Listing): List<FileOps.Entry> {
        val shown = visibleEntries(pane, listing.entries, Declarations.files.filters)
        val f = liveFilter ?: return shown
        return if (f.locationKey == pane.location.key && !f.scope.recursive) ExplorerSearch.filterHere(shown, f.query, f.scope) else shown
    }

    /** The entries a pane's selection refers to, in list order. */
    fun selectedEntries(id: PaneId): List<FileOps.Entry> {
        val p = s.pane(id)
        val listing = listings.value[p.location.key] ?: return emptyList()
        return visible(p, listing).filter { it.key in p.selection }
    }

    fun thumbnail(path: String): Bitmap? {
        thumbs.get(path)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= THUMB_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        thumbs.put(path, decoded)
        return decoded
    }

    // ── jobs ────────────────────────────────────────────────────────────────

    private fun startJob(title: String, work: suspend (TransferJob, (String, Float?) -> Unit) -> Pair<Boolean, String>): Job {
        val job = TransferJob(jobIds.incrementAndGet(), title)
        jobs.update { it + job }
        fun set(f: (TransferJob) -> TransferJob) = jobs.update { list -> list.map { if (it.id == job.id) f(it) else it } }
        return scope.launch {
            val (ok, summary) = withContext(Dispatchers.IO) {
                runCatching { work(job) { detail, fraction -> set { it.copy(detail = detail, fraction = fraction) } } }
                    .getOrElse { e -> false to (e.message ?: e.toString()) }
            }
            val cancelled = job.cancelRequested.get()
            set { it.copy(done = true, ok = ok && !cancelled, detail = summary) }
            _messages.tryEmit(if (cancelled) ctx.getString(R.string.files_job_cancelled, title) else summary)
            kotlinx.coroutines.delay(RESULT_LINGER_MS)
            jobs.update { list -> list.filterNot { it.id == job.id } }
        }
    }

    /** True when [f] sits in the terminal's data and this app cannot write there itself: the change goes through CLOUD_EXEC. */
    private fun viaOwner(f: File): Boolean {
        val dir = if (f.isDirectory) f else f.parentFile
        return com.diegonmarcos.cloudlib.sharedstore.StorePaths.mutateViaOwner(
            com.diegonmarcos.cloudlib.sharedstore.StoreAccess.paths, f.absolutePath, com.diegonmarcos.cloudlib.sharedstore.StoreAccess.userId(),
            dir?.canWrite() == true, com.diegonmarcos.cloudlib.sharedstore.StoreAccess.mode(ctx))
    }

    fun cancel(jobId: Long) { jobs.value.firstOrNull { it.id == jobId }?.cancelRequested?.set(true) }

    @Suppress("UNUSED_PARAMETER")
    private fun progressOf(job: TransferJob, set: (String, Float?) -> Unit) = FileOps.Progress { done, total, current -> set(current, if (total > 0) (done.toDouble() / total).toFloat() else null) }

    /** Copy or move the active selection of [from] into [destination]; archive selections are extracted. */
    fun transferSelection(from: PaneId, destination: Location.Local, move: Boolean) {
        val entries = selectedEntries(from)
        if (entries.isEmpty()) return
        val dest = File(destination.path)
        val fromLoc = s.pane(from).location
        update { FilesReducer.clearSelection(it, from) }
        if (fromLoc is Location.Archive) {
            val zip = File(fromLoc.zipPath)
            val prefixes = entries.map { (it.location as Location.Archive).inner }.toSet()
            startJob(ctx.getString(R.string.files_job_extract, zip.name)) { job, set ->
                val n = FileOps.extract(zip, dest, progressOf(job, set), { job.cancelRequested.get() }, onlyUnder = prefixes)
                refreshShowing(destination)
                true to ctx.getString(R.string.files_job_done, ctx.getString(R.string.files_job_extract, zip.name) + " · $n")
            }
            return
        }
        val files = entries.mapNotNull { it.localFile }
        if (viaOwner(dest) || files.any { viaOwner(it) || (!it.canRead() && it.absolutePath.startsWith("/data/")) }) {
            val t = ctx.getString(if (move) R.string.files_job_move else R.string.files_job_copy, files.size)
            startJob(t) { _, _ ->
                val r = runCatching { ExecMutation.transfer(ctx, files, dest, move) }
                refreshShowing(destination); if (move) refreshShowing(fromLoc)
                r.isSuccess to (if (r.isSuccess) ctx.getString(R.string.files_job_done, t) else (r.exceptionOrNull()?.message ?: t))
            }
            return
        }
        val title = ctx.getString(if (move) R.string.files_job_move else R.string.files_job_copy, files.size)
        startJob(title) { job, set ->
            val free = runCatching { StatFs(dest.absolutePath).availableBytes }.getOrDefault(0L)
            val outcome = try {
                FileOps.transfer(files, dest, move, free, progressOf(job, set)) { job.cancelRequested.get() }
            } catch (e: FileOps.NotEnoughSpace) {
                return@startJob false to ctx.getString(R.string.files_job_not_enough_space, FileOps.humanBytes(e.needed), FileOps.humanBytes(e.free))
            }
            refreshShowing(destination); if (move) refreshShowing(fromLoc)
            outcome.ok to (if (outcome.ok) ctx.getString(R.string.files_job_done, title) else ctx.getString(R.string.files_job_failed, title, outcome.failed.size))
        }
    }

    fun deleteSelection(id: PaneId) {
        val files = selectedEntries(id).mapNotNull { it.localFile }
        if (files.isEmpty()) return
        val loc = s.pane(id).location
        update { FilesReducer.clearSelection(it, id) }
        val title = ctx.getString(R.string.files_job_delete, files.size)
        startJob(title) { job, set ->
            val routed = files.filter { viaOwner(it) }
            val routedOk = runCatching { ExecMutation.delete(ctx, routed) }.isSuccess
            val outcome = FileOps.delete(files - routed.toSet(), progressOf(job, set)) { job.cancelRequested.get() }
            refreshShowing(loc)
            val ok = outcome.ok && routedOk
            ok to (if (ok) ctx.getString(R.string.files_job_done, title) else ctx.getString(R.string.files_job_failed, title, outcome.failed.size + (if (routedOk) 0 else routed.size)))
        }
    }

    /** Zip all (or, #1680, Compress all to .tar.gz when [tarGz]) the selection into ONE archive in the pane's folder. */
    fun zipSelection(id: PaneId, archiveName: String) = zipSelection(id, archiveName, tarGz = false)

    fun zipSelection(id: PaneId, archiveName: String, tarGz: Boolean) {
        val files = selectedEntries(id).mapNotNull { it.localFile }
        val loc = s.pane(id).location as? Location.Local ?: return
        if (files.isEmpty()) return
        val safe = FileOps.sanitize(archiveName) ?: return
        val suffix = if (tarGz) ".tar.gz" else ".zip"
        val target = FileOps.uniqueIn(File(loc.path), if (safe.lowercase().endsWith(suffix)) safe else "$safe$suffix")
        update { FilesReducer.clearSelection(it, id) }
        val title = ctx.getString(R.string.files_job_zip, files.size)
        startJob(title) { job, set ->
            val n = if (tarGz) FileOps.archiveTarGz(files, target, progressOf(job, set)) { job.cancelRequested.get() }
            else FileOps.archive(files, target, progressOf(job, set)) { job.cancelRequested.get() }
            refreshShowing(loc)
            true to ctx.getString(R.string.files_job_done, "$title · $n")
        }
    }

    fun extractHere(entry: FileOps.Entry) {
        val zip = entry.localFile ?: return
        val dest = FileOps.uniqueIn(zip.parentFile ?: return, zip.nameWithoutExtension).apply { mkdirs() }
        val title = ctx.getString(R.string.files_job_extract, zip.name)
        startJob(title) { job, set ->
            val n = FileOps.extract(zip, dest, progressOf(job, set), cancelled = { job.cancelRequested.get() })
            refreshShowing(Location.Local(zip.parentFile!!.absolutePath))
            true to ctx.getString(R.string.files_job_done, "$title · $n")
        }
    }

    /** Row menu ▸ Unzip…: any archive type [ArchiveExtractor] reads, into [destPath], as a cancellable job with byte progress. */
    fun unzip(entry: FileOps.Entry, destPath: String) {
        val src = entry.localFile ?: return
        val dest = File(destPath)
        val title = ctx.getString(R.string.files_job_extract, src.name)
        startJob(title) { job, set ->
            val free = generateSequence(dest) { it.parentFile }.firstOrNull { it.exists() }?.let { runCatching { StatFs(it.absolutePath).availableBytes }.getOrNull() } ?: Long.MAX_VALUE
            val r = try {
                ArchiveExtractor.extract(src, dest, progressOf(job, set), { job.cancelRequested.get() }, free)
            } catch (e: FileOps.NotEnoughSpace) {
                return@startJob false to ctx.getString(R.string.files_job_not_enough_space, FileOps.humanBytes(e.needed), FileOps.humanBytes(e.free))
            }
            refreshShowing(Location.Local(dest.absolutePath))
            src.parentFile?.let { refreshShowing(Location.Local(it.absolutePath)) }
            true to ctx.getString(R.string.files_job_done, "$title · ${r.files}")
        }
    }

    /** Row menu ▸ Convert…: run [converter] from the registry beside the file, as a cancellable job. */
    fun convert(entry: FileOps.Entry, converter: com.diegonmarcos.clouddrive.files.convert.Converter) {
        val src = entry.localFile ?: return
        val dir = src.parentFile ?: return
        val title = ctx.getString(R.string.files_job_convert, src.name, converter.label)
        startJob(title) { job, set ->
            val written = converter.convert(src, dir, progressOf(job, set)) { job.cancelRequested.get() }
            refreshShowing(Location.Local(dir.absolutePath))
            written.firstOrNull { it.isFile }?.let { _converted.tryEmit(it) }
            true to ctx.getString(R.string.files_convert_saved, written.joinToString { it.name })
        }
    }

    /** Convert ▸ Convert to PDF…: any file; a type with no PDF form says why instead of failing. Cancellable job. */
    fun toPdf(entry: FileOps.Entry) {
        val src = entry.localFile ?: return
        val dir = src.parentFile ?: return
        com.diegonmarcos.clouddrive.files.convert.ToPdf.refusal(src.name)?.let { _messages.tryEmit(it); return }
        val title = ctx.getString(R.string.files_job_convert, src.name, "PDF")
        startJob(title) { job, set ->
            val written = com.diegonmarcos.clouddrive.files.convert.ToPdf.convert(ctx, src, dir, progressOf(job, set)) { job.cancelRequested.get() }
            refreshShowing(Location.Local(dir.absolutePath))
            written.firstOrNull { it.isFile }?.let { _converted.tryEmit(it) }
            true to ctx.getString(R.string.files_convert_saved, written.joinToString { it.name })
        }
    }

    /** #458/#577 PDF → txt/md/html/csv/OCR text beside the file, through the ONE PdfConversion path the reader also uses; a cancellable job. */
    fun convertPdf(entry: FileOps.Entry, target: String) {
        val f = entry.localFile ?: return
        val title = ctx.getString(R.string.files_job_convert, f.name, target)
        startJob(title) { job, set ->
            val r = com.diegonmarcos.clouddrive.PdfConversion.convertPdf(ctx, f, target, progressOf(job, set), { job.cancelRequested.get() })
            f.parentFile?.let { refreshShowing(Location.Local(it.absolutePath)) }
            r.onSuccess { _converted.tryEmit(it) }
            r.fold({ true to ctx.getString(R.string.files_convert_saved, it.name) }, { false to (it.message ?: it.toString()) })
        }
    }

    fun createFolder(id: PaneId, name: String) = mutate(id) { val p = File(it.path); if (viaOwner(p)) { ExecMutation.mkdir(ctx, p, name); File(p, name) } else FileOps.createFolder(p, name) }
    fun createFile(id: PaneId, name: String) = mutate(id) { val p = File(it.path); if (viaOwner(p)) { ExecMutation.touch(ctx, p, name); File(p, name) } else FileOps.createFile(p, name) }
    fun rename(entry: FileOps.Entry, name: String) {
        val f = entry.localFile ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { if (viaOwner(f)) ExecMutation.rename(ctx, f, name) else FileOps.rename(f, name) } }
            r.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            refreshShowing(Location.Local(f.parentFile?.absolutePath ?: return@launch))
        }
    }

    /** #1644 `git mv` a single item to [newName] in its folder; staged. */
    fun gitRename(entry: FileOps.Entry, newName: String) {
        val f = entry.localFile ?: return
        gitMv(listOf(f), f.parentFile?.let { Location.Local(it.absolutePath) } ?: return) { GitMv.planRename(f, newName) }
    }

    /** #1644 `git mv` the entries into [destination]; staged. */
    fun gitMoveEntries(entries: List<FileOps.Entry>, destination: Location.Local) {
        val files = entries.mapNotNull { it.localFile }
        if (files.isEmpty()) return
        val dest = File(destination.path)
        gitMvMany(files, destination, files.map { f -> { GitMv.planMove(f, dest) } })
    }

    private fun gitMv(files: List<File>, refresh: Location.Local, plan: () -> GitMv.Step) = gitMvMany(files, refresh, listOf(plan))

    private fun gitMvMany(files: List<File>, refresh: Location.Local, plans: List<() -> GitMv.Step>) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { GitMv.apply(ctx, plans.map { it() }) } }
            r.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            refreshShowing(refresh)
            files.firstOrNull()?.parentFile?.let { refreshShowing(Location.Local(it.absolutePath)) }
        }
    }

    /**
     * #1607 Compress one entry into a .zip or .tar.gz (when [tarGz]) archive, beside the item or
     * into [downloadsDir] (Download) when given, as a cancellable job with byte progress. The real
     * writers are [FileOps.archive] / [FileOps.archiveTarGz]; the name is the item's, de-duped in place.
     */
    fun compress(entry: FileOps.Entry, tarGz: Boolean, downloadsDir: File?) {
        val src = entry.localFile ?: return
        val destDir = (downloadsDir ?: src.parentFile) ?: return
        if (!destDir.exists()) destDir.mkdirs()
        val suffix = if (tarGz) ".tar.gz" else ".zip"
        val base = if (entry.isDirectory) src.name else src.nameWithoutExtension
        val target = FileOps.uniqueIn(destDir, base + suffix)
        val title = ctx.getString(R.string.files_job_compress, src.name)
        startJob(title) { job, set ->
            val n = if (tarGz) FileOps.archiveTarGz(listOf(src), target, progressOf(job, set)) { job.cancelRequested.get() }
            else FileOps.archive(listOf(src), target, progressOf(job, set)) { job.cancelRequested.get() }
            refreshShowing(Location.Local(destDir.absolutePath))
            src.parentFile?.let { refreshShowing(Location.Local(it.absolutePath)) }
            true to ctx.getString(R.string.files_job_done, "$title · ${target.name} · $n")
        }
    }

    private fun mutate(id: PaneId, block: (Location.Local) -> File) {
        val loc = s.pane(id).location as? Location.Local ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(loc) } }
            r.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            refreshShowing(loc)
        }
    }

    fun applyRenamePlan(plan: List<FileOps.RenameStep>, loc: Location) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.applyRenamePlan(plan) } }
            r.onSuccess { n -> _messages.tryEmit(ctx.getString(R.string.files_rename_apply, n)) }.onFailure { _messages.tryEmit(it.message ?: it.toString()) }
            update { FilesReducer.clearSelection(it, it.active) }
            refreshShowing(loc)
        }
    }

    // ── search ──────────────────────────────────────────────────────────────

    data class SearchState(val query: String = "", val running: Boolean = false, val hits: List<FileOps.Hit> = emptyList(), val scanned: Long = 0, val truncated: Boolean = false)

    val search = MutableStateFlow(SearchState())
    private var searchJob: Job? = null
    private val searchCancel = AtomicBoolean(false)

    fun startSearch(root: Location.Local, query: String, contentToo: Boolean) {
        cancelSearch()
        searchCancel.set(false)
        search.value = SearchState(query = query, running = true)
        searchJob = scope.launch(Dispatchers.IO) {
            val truncated = FileOps.search(File(root.path), query, contentToo, mimes, { searchCancel.get() },
                onScanned = { n -> search.update { it.copy(scanned = n) } },
                onHit = { hit -> search.update { it.copy(hits = it.hits + hit) } })
            search.update { it.copy(running = false, truncated = truncated) }
        }
    }

    fun cancelSearch() { searchCancel.set(true); searchJob?.cancel(); search.update { it.copy(running = false) } }
    fun clearSearch() { cancelSearch(); search.value = SearchState() }

    // ── inline explorer search (ui.files.search) ────────────────────────────

    /** A recursive scope's streamed hits. [stop] says why the walk ended early (ExplorerSearch.Stop). */
    data class ScopedSearch(val query: String = "", val scopeId: String = "", val running: Boolean = false, val hits: List<FileOps.Entry> = emptyList(), val scanned: Int = 0, val stop: ExplorerSearch.Stop = ExplorerSearch.Stop.NONE)

    val scoped = MutableStateFlow(ScopedSearch())
    private var scopedJob: Job? = null
    private var scopedToken = AtomicBoolean(false)

    /** Restart the recursive walk: the previous one is cancelled, the new one waits the declared debounce, then streams off the main thread. */
    fun runScoped(start: String, query: String, kind: Declarations.SearchScopeDecl, decl: Declarations.FilesSearchDecl = Declarations.files.search) {
        cancelScoped()
        if (!kind.recursive || ExplorerSearch.needle(query).isEmpty()) { scoped.value = ScopedSearch(query, kind.id); return }
        val token = AtomicBoolean(false).also { scopedToken = it }
        scoped.value = ScopedSearch(query, kind.id, running = true)
        scopedJob = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(decl.debounceMs)
            if (token.get()) return@launch
            val stop = ExplorerSearch.walk(File(start), query, kind, ExplorerSearch.Bounds(decl.resultCeiling, decl.scanCeiling, decl.depthCeiling), mimes, { token.get() },
                onScanned = { n -> if (!token.get()) scoped.update { it.copy(scanned = n) } },
                onHit = { e -> if (!token.get()) scoped.update { it.copy(hits = it.hits + e) } })
            if (!token.get()) scoped.update { it.copy(running = false, stop = stop) }
        }
    }

    fun cancelScoped() { scopedToken.set(true); scopedJob?.cancel(); scopedJob = null }
    fun clearScoped() { cancelScoped(); scoped.value = ScopedSearch() }

    // ── duplicates ──────────────────────────────────────────────────────────

    /** null = not asked; empty list = running with nothing yet; the result replaces it. */
    val duplicates = MutableStateFlow<List<FileOps.DuplicateGroup>?>(null)
    var duplicatesRunning = MutableStateFlow(false)
    private val dupCancel = AtomicBoolean(false)

    fun findDuplicates(root: Location.Local) {
        dupCancel.set(false)
        duplicates.value = emptyList(); duplicatesRunning.value = true
        scope.launch(Dispatchers.IO) {
            val groups = FileOps.duplicates(File(root.path), { dupCancel.get() }) { }
            duplicates.value = groups; duplicatesRunning.value = false
        }
    }
    fun closeDuplicates() { dupCancel.set(true); duplicates.value = null; duplicatesRunning.value = false }

    // ── properties ──────────────────────────────────────────────────────────

    data class PropertiesState(val entry: FileOps.Entry, val result: FileOps.Properties? = null, val progress: String = "", val running: Boolean = true)

    val properties = MutableStateFlow<PropertiesState?>(null)
    private val propsCancel = AtomicBoolean(false)

    fun openProperties(entry: FileOps.Entry) {
        propsCancel.set(false)
        properties.value = PropertiesState(entry)
        val f = entry.localFile ?: run { properties.value = PropertiesState(entry, running = false); return }
        scope.launch(Dispatchers.IO) {
            val r = FileOps.properties(f, { propsCancel.get() }, FileOps.Progress { done, _, _ -> properties.update { it?.copy(progress = FileOps.humanBytes(done)) } })
            properties.update { it?.copy(result = r, running = false) }
        }
    }
    fun closeProperties() { propsCancel.set(true); properties.value = null }

    companion object {
        const val THUMB_PX = 96
        const val RESULT_LINGER_MS = 4000L

        /** Hidden filter, declared filter, then sort — the one place a pane's rows are decided. */
        fun visibleEntries(pane: PaneState, entries: List<FileOps.Entry>, filters: List<Declarations.FilterDecl>): List<FileOps.Entry> {
            val filter = filters.firstOrNull { it.id == pane.filterId }
            val shown = entries.filter { e -> (pane.showHidden || !e.hidden) && (filter == null || filter.matches(e.isDirectory, e.mime, e.extension)) }
            return FileOps.sortEntries(shown, pane.sort, pane.descending)
        }
    }
}
