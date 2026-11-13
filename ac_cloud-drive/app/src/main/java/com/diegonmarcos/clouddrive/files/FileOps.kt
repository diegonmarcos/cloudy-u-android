package com.diegonmarcos.clouddrive.files

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import com.diegonmarcos.clouddrive.DriveEngines
import com.diegonmarcos.cloudlib.drivecore.DriveCore
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/**
 * #579 the typed file-IO core of the Files tab: what the pre-redesign bridge did in
 * JSON strings for a page, as Kotlin for Compose. Pure java.io over the paths the
 * caller already resolved; blocking, so every caller runs it on Dispatchers.IO;
 * cancellable through a `cancelled()` probe and reporting through plain callbacks.
 *
 * The algorithms are the ones the bridge earned: free-space refusal BEFORE a copy,
 * rename-then-copy for a move across volumes, unique names instead of overwrites,
 * two-pass extraction behind the Zip-Slip guard, rsync's size+mtime quick check for
 * mirrors, a NUL/UTF-8 round-trip check before a text file is opened, and a
 * scratch-then-rename save. Nothing here knows about Android.
 */
object FileOps {

    // ── listing ─────────────────────────────────────────────────────────────

    /** One row. [location] is the pane's own model so an archive entry and a local file share a shape. */
    data class Entry(
        val location: Location,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long,
        val mime: String,
        val extension: String,
        val hidden: Boolean,
        /** Zip entries only: bytes on disk inside the archive. */
        val compressedSize: Long? = null,
        /** Folders only, when cheap to know: how many children. */
        val childCount: Int? = null,
    ) {
        val key: String get() = location.key
        val localFile: File? get() = (location as? Location.Local)?.let { File(it.path) }
    }

    /** A mime lookup the caller supplies (Android's MimeTypeMap on the phone, a table in tests). */
    fun interface MimeResolver { fun mimeOf(extension: String): String? }

    fun extensionOf(name: String): String = if (name.contains('.') && !name.startsWith(".")) name.substringAfterLast('.').lowercase() else ""

    fun describe(file: File, mimes: MimeResolver, countChildren: Boolean = true): Entry {
        val ext = extensionOf(file.name)
        val dir = file.isDirectory
        return Entry(
            location = Location.Local(file.absolutePath), name = file.name, isDirectory = dir,
            size = if (dir) 0L else file.length(), modified = file.lastModified(),
            mime = if (dir) DIRECTORY_MIME else (mimes.mimeOf(ext) ?: OCTET_MIME), extension = ext,
            hidden = file.name.startsWith("."),
            childCount = if (dir && countChildren) file.list()?.size else null,
        )
    }

    /** Folders first, then [sort] ∈ name | size | modified | type, reversed when [descending]. */
    fun sortEntries(entries: List<Entry>, sort: String, descending: Boolean): List<Entry> {
        val by: Comparator<Entry> = when (sort) {
            "size" -> compareBy { if (it.isDirectory) -1L else it.size }
            "modified" -> compareBy { it.modified }
            "type" -> compareBy<Entry>({ it.extension }, { it.name.lowercase() })
            else -> compareBy { it.name.lowercase() }
        }
        return entries.sortedWith(compareByDescending<Entry> { it.isDirectory }.then(if (descending) by.reversed() else by))
    }

    /** The honest cause: `isDirectory` is false for a missing path AND for another uid's private dir, so only a real file is "not a folder". */
    fun notFolder(f: File): IOException =
        if (f.isFile) IOException("not a folder: ${f.name}")
        else IOException("cannot open ${f.name}: missing, or private to another app that this one cannot reach")

    @Throws(IOException::class)
    fun list(dir: File, mimes: MimeResolver, showHidden: Boolean): List<Entry> {
        if (!dir.isDirectory) throw notFolder(dir)
        val children = dir.listFiles() ?: throw IOException("cannot read ${dir.name}")
        return children.filter { showHidden || !it.name.startsWith(".") }.map { describe(it, mimes) }
    }

    // ── names ───────────────────────────────────────────────────────────────

    /** A name that cannot climb out of its directory, or null. */
    fun sanitize(name: String): String? {
        val t = name.trim()
        if (t.isEmpty() || t == "." || t == "..") return null
        if (t.contains('/') || t.contains('\u0000')) return null
        return t
    }

    /** "report.pdf" → "report (2).pdf" rather than overwriting what is there. */
    fun uniqueIn(destination: File, name: String): File {
        var candidate = File(destination, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (candidate.exists()) {
            candidate = File(destination, base + " (" + n + ")" + if (ext.isEmpty()) "" else ".$ext")
            n++
        }
        return candidate
    }

    fun humanBytes(bytes: Long): String {
        if (bytes < 0) return "–"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var u = -1
        while (v >= 1024.0 && u < units.size - 1) { v /= 1024.0; u++ }
        return String.format(Locale.US, "%.1f %s", v, units[u])
    }

    // ── create / rename ─────────────────────────────────────────────────────

    @Throws(IOException::class)
    fun createFolder(parent: File, name: String): File {
        val safe = sanitize(name) ?: throw IOException("a folder name cannot contain a path separator")
        val target = File(parent, safe)
        if (target.exists()) throw IOException("$safe already exists")
        if (!target.mkdirs()) throw IOException("could not create $safe")
        return target
    }

    @Throws(IOException::class)
    fun createFile(parent: File, name: String): File {
        val safe = sanitize(name) ?: throw IOException("a file name cannot contain a path separator")
        val target = File(parent, safe)
        if (target.exists()) throw IOException("$safe already exists")
        if (!target.createNewFile()) throw IOException("could not create $safe")
        return target
    }

    @Throws(IOException::class)
    fun rename(file: File, name: String): File {
        val safe = sanitize(name) ?: throw IOException("a name cannot contain a path separator")
        val target = File(file.parentFile, safe)
        if (target.exists()) throw IOException("$safe already exists")
        if (!file.renameTo(target)) throw IOException("could not rename ${file.name}")
        return target
    }

    // ── transfer (copy / move) with progress ─────────────────────────────────

    /** Progress of a long job: bytes so far, total when known, and the item in flight. */
    fun interface Progress { fun report(bytesDone: Long, bytesTotal: Long, current: String) }

    data class Outcome(val done: Int, val failed: List<String>) { val ok: Boolean get() = failed.isEmpty() }

    fun folderSize(dir: File, depthLeft: Int = DEPTH_CEILING): Long {
        if (depthLeft <= 0) return 0L
        var total = 0L
        dir.listFiles()?.forEach { c -> total += if (c.isDirectory) folderSize(c, depthLeft - 1) else c.length() }
        return total
    }

    fun totalSize(sources: List<File>): Long = sources.sumOf { if (it.isDirectory) folderSize(it) else it.length() }

    /**
     * Copies or moves [sources] into [destination]. The free-space check happens BEFORE
     * a byte moves ([freeBytes] is the caller's StatFs reading); a move tries the atomic
     * rename first and falls back to copy-then-delete across volumes.
     */
    @Throws(IOException::class)
    fun transfer(sources: List<File>, destination: File, move: Boolean, freeBytes: Long, progress: Progress, cancelled: () -> Boolean): Outcome {
        if (!destination.isDirectory) throw notFolder(destination)
        val needed = totalSize(sources)
        if (needed > freeBytes) throw NotEnoughSpace(needed, freeBytes)
        var done = 0L
        var count = 0
        val failed = mutableListOf<String>()
        for (source in sources) {
            if (cancelled()) break
            val target = uniqueIn(destination, source.name)
            val ok = try {
                if (move && source.renameTo(target)) { done += if (source.isDirectory) 0 else target.length(); true }
                else {
                    copyTree(source, target, needed, { d -> done = d }, done, progress, cancelled)
                    done = progressAfter(source, done)
                    if (move && !cancelled()) source.deleteRecursively() else true
                }
            } catch (e: Exception) { false }
            if (ok) count++ else failed += source.name
        }
        return Outcome(count, failed)
    }

    private fun progressAfter(source: File, before: Long): Long = before + (if (source.isDirectory) folderSize(source) else source.length())

    private fun copyTree(source: File, target: File, total: Long, setDone: (Long) -> Unit, doneBefore: Long, progress: Progress, cancelled: () -> Boolean): Boolean {
        var done = doneBefore
        fun walk(src: File, dst: File, depth: Int): Boolean {
            if (cancelled()) return false
            if (depth <= 0) return false
            if (src.isDirectory) {
                if (!dst.isDirectory && !dst.mkdirs()) return false
                val children = src.listFiles() ?: return false
                for (c in children) if (!walk(c, File(dst, c.name), depth - 1)) return false
                return true
            }
            dst.parentFile?.mkdirs()
            FileInputStream(src).use { input -> FileOutputStream(dst).use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    if (cancelled()) { dst.delete(); return false }
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    progress.report(done, total, src.name)
                }
            } }
            dst.setLastModified(src.lastModified())
            return true
        }
        val ok = walk(source, target, DEPTH_CEILING)
        setDone(done)
        if (!ok) throw IOException("copy of ${source.name} did not finish")
        return true
    }

    class NotEnoughSpace(val needed: Long, val free: Long) : IOException("not enough space: needs ${humanBytes(needed)}, only ${humanBytes(free)} free")

    fun delete(sources: List<File>, progress: Progress, cancelled: () -> Boolean): Outcome {
        var count = 0
        val failed = mutableListOf<String>()
        sources.forEachIndexed { i, f ->
            if (cancelled()) return Outcome(count, failed)
            progress.report(i.toLong(), sources.size.toLong(), f.name)
            if (f.deleteRecursively()) count++ else failed += f.name
        }
        return Outcome(count, failed)
    }

    // ── archives ────────────────────────────────────────────────────────────

    /** Zips [sources] into [zipFile] (which must not exist); a failure deletes the partial archive. */
    @Throws(IOException::class)
    fun archive(sources: List<File>, zipFile: File, progress: Progress, cancelled: () -> Boolean): Int {
        if (zipFile.exists()) throw IOException("${zipFile.name} already exists")
        if (sources.isEmpty()) throw IOException("nothing to archive")
        val total = totalSize(sources)
        var done = 0L
        var entries = 0
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                val used = mutableSetOf<String>()
                fun put(file: File, entryName: String) {
                    zip.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            if (cancelled()) throw IOException("cancelled")
                            val n = input.read(buf); if (n < 0) break
                            zip.write(buf, 0, n); done += n
                            progress.report(done, total, file.name)
                        }
                    }
                    zip.closeEntry(); entries++
                }
                fun folder(src: File, prefix: String, depth: Int) {
                    if (depth <= 0) return
                    src.listFiles()?.forEach { c -> if (c.isDirectory) folder(c, prefix + c.name + "/", depth - 1) else put(c, prefix + c.name) }
                }
                sources.forEach { s ->
                    if (s.isDirectory) folder(s, uniqueZipName(used, s.name, true), DEPTH_CEILING)
                    else put(s, uniqueZipName(used, s.name, false))
                }
            }
        } catch (e: Exception) {
            zipFile.delete()
            throw if (e is IOException) e else IOException(e.message ?: "the archive did not finish")
        }
        return entries
    }

    /**
     * Tars [sources] into [tarGzFile] (which must not exist) with gzip compression, through
     * commons-compress's TAR writer and java.util.zip's GZIP stream — the same TAR/GZIP pair
     * [ArchiveExtractor] reads back. Entry names, de-duping and the folder walk mirror [archive];
     * a failure deletes the partial archive. Returns the number of file entries written.
     */
    @Throws(IOException::class)
    fun archiveTarGz(sources: List<File>, tarGzFile: File, progress: Progress, cancelled: () -> Boolean): Int {
        if (tarGzFile.exists()) throw IOException("${tarGzFile.name} already exists")
        if (sources.isEmpty()) throw IOException("nothing to archive")
        val total = totalSize(sources)
        var done = 0L
        var entries = 0
        try {
            TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(tarGzFile)))).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                val used = mutableSetOf<String>()
                fun put(file: File, entryName: String) {
                    val entry = TarArchiveEntry(file, entryName)
                    tar.putArchiveEntry(entry)
                    FileInputStream(file).use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            if (cancelled()) throw IOException("cancelled")
                            val n = input.read(buf); if (n < 0) break
                            tar.write(buf, 0, n); done += n
                            progress.report(done, total, file.name)
                        }
                    }
                    tar.closeArchiveEntry(); entries++
                }
                fun folder(src: File, prefix: String, depth: Int) {
                    if (depth <= 0) return
                    src.listFiles()?.forEach { c -> if (c.isDirectory) folder(c, prefix + c.name + "/", depth - 1) else put(c, prefix + c.name) }
                }
                sources.forEach { s ->
                    if (s.isDirectory) folder(s, uniqueZipName(used, s.name, true), DEPTH_CEILING)
                    else put(s, uniqueZipName(used, s.name, false))
                }
            }
        } catch (e: Exception) {
            tarGzFile.delete()
            throw if (e is IOException) e else IOException(e.message ?: "the archive did not finish")
        }
        return entries
    }

    private fun uniqueZipName(used: MutableSet<String>, name: String, isFolder: Boolean): String {
        val stem = if (isFolder) name else name.substringBeforeLast('.', name)
        val tail = if (isFolder) "" else "." + name.substringAfterLast('.', "")
        val slash = if (isFolder) "/" else ""
        var candidate = name + slash
        var n = 2
        while (candidate in used) { candidate = "$stem ($n)$tail$slash"; n++ }
        used.add(candidate)
        return candidate
    }

    /**
     * Unzips [zipFile] into [destination]: the Zip-Slip guard runs over EVERY entry name
     * BEFORE the first byte is written, and a failure removes what this pass wrote.
     * [onlyUnder] restricts the pass to entries below that prefix (extracting a folder
     * out of a browsed archive), stripping the prefix from the target path.
     */
    @Throws(IOException::class)
    fun extract(zipFile: File, destination: File, progress: Progress, cancelled: () -> Boolean, onlyUnder: Set<String> = emptySet()): Int {
        if (!zipFile.isFile) throw IOException("not a zip: ${zipFile.name}")
        if (!destination.isDirectory) throw notFolder(destination)
        fun relative(name: String): String? {
            if (onlyUnder.isEmpty()) return name
            val hit = onlyUnder.firstOrNull { p -> name == p || name.startsWith(if (p.endsWith("/")) p else "$p/") } ?: return null
            val parent = hit.trimEnd('/').substringBeforeLast('/', "")
            return if (parent.isEmpty()) name else name.removePrefix("$parent/")
        }
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { input ->
            while (true) {
                val e = input.nextEntry ?: break
                val rel = relative(e.name)
                if (rel != null && zipEntryTarget(destination, rel) == null) throw IOException("the archive tries to write outside the destination: ${e.name}")
                input.closeEntry()
            }
        }
        val written = mutableListOf<File>()
        var count = 0
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { input ->
                while (true) {
                    if (cancelled()) throw IOException("cancelled")
                    val e = input.nextEntry ?: break
                    val rel = relative(e.name)
                    if (rel == null || e.isDirectory) { input.closeEntry(); continue }
                    val target = zipEntryTarget(destination, rel) ?: continue
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                    written += target; count++
                    progress.report(count.toLong(), 0L, target.name)
                    input.closeEntry()
                }
            }
        } catch (e: Exception) {
            written.reversed().forEach { it.delete() }
            throw if (e is IOException) e else IOException(e.message ?: "the extraction did not finish")
        }
        return count
    }

    // ── search ──────────────────────────────────────────────────────────────

    data class Hit(val entry: Entry, val matchedByContent: Boolean)

    /** Name search, and content search for text files under [SEARCH_TEXT_CEILING]; stops at [SEARCH_RESULT_CEILING]. Returns true when truncated. */
    fun search(root: File, query: String, contentToo: Boolean, mimes: MimeResolver, cancelled: () -> Boolean, onScanned: (Long) -> Unit, onHit: (Hit) -> Unit): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return false
        var scanned = 0L
        var found = 0
        fun walk(dir: File): Boolean {
            if (cancelled()) return false
            val children = dir.listFiles() ?: return false
            for (c in children) {
                if (cancelled()) return false
                scanned++; if (scanned % 50 == 0L) onScanned(scanned)
                val nameMatch = c.name.lowercase().contains(needle)
                val contentMatch = !nameMatch && contentToo && matchesText(c, needle, mimes)
                if (nameMatch || contentMatch) {
                    onHit(Hit(describe(c, mimes, countChildren = false), contentMatch))
                    if (++found >= SEARCH_RESULT_CEILING) return true
                }
                if (c.isDirectory && walk(c)) return true
            }
            return false
        }
        val truncated = walk(root)
        onScanned(scanned)
        return truncated
    }

    private fun matchesText(file: File, needle: String, mimes: MimeResolver): Boolean {
        if (!file.isFile || file.length() > SEARCH_TEXT_CEILING) return false
        val mime = mimes.mimeOf(extensionOf(file.name)) ?: return false
        if (!mime.startsWith("text/")) return false
        return runCatching { String(file.readBytes(), Charsets.UTF_8).lowercase().contains(needle) }.getOrDefault(false)
    }

    // ── properties / hashes / duplicates ────────────────────────────────────

    data class Properties(val size: Long, val files: Int, val folders: Int, val readable: Boolean, val writable: Boolean, val executable: Boolean, val md5: String?, val sha256: String?)

    fun properties(file: File, cancelled: () -> Boolean, progress: Progress): Properties {
        var bytes = 0L; var files = 0; var folders = 0
        if (file.isDirectory) {
            fun walk(d: File, depth: Int) {
                if (depth <= 0 || cancelled()) return
                d.listFiles()?.forEach { c -> if (c.isDirectory) { folders++; walk(c, depth - 1) } else { files++; bytes += c.length(); progress.report(bytes, 0L, c.name) } }
            }
            walk(file, DEPTH_CEILING)
        } else { bytes = file.length(); files = 1 }
        var md5: String? = null; var sha: String? = null
        if (file.isFile && !cancelled()) {
            val d5 = MessageDigest.getInstance("MD5"); val d256 = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER); var done = 0L
            FileInputStream(file).use { input ->
                while (true) {
                    if (cancelled()) return Properties(bytes, files, folders, file.canRead(), file.canWrite(), file.canExecute(), null, null)
                    val n = input.read(buf); if (n < 0) break
                    d5.update(buf, 0, n); d256.update(buf, 0, n); done += n
                    progress.report(done, bytes, file.name)
                }
            }
            md5 = hex(d5.digest()); sha = hex(d256.digest())
        }
        return Properties(bytes, files, folders, file.canRead(), file.canWrite(), file.canExecute(), md5, sha)
    }

    data class DuplicateGroup(val size: Long, val paths: List<String>)

    /** Size buckets first, SHA-256 only inside buckets of two or more. */
    fun duplicates(root: File, cancelled: () -> Boolean, onScanned: (Long) -> Unit): List<DuplicateGroup> {
        val bySize = HashMap<Long, MutableList<File>>()
        var scanned = 0L
        fun walk(d: File, depth: Int) {
            if (depth <= 0 || cancelled()) return
            d.listFiles()?.forEach { c -> scanned++; if (scanned % 100 == 0L) onScanned(scanned); if (c.isDirectory) walk(c, depth - 1) else bySize.getOrPut(c.length()) { mutableListOf() }.add(c) }
        }
        walk(root, DEPTH_CEILING)
        val groups = mutableListOf<DuplicateGroup>()
        bySize.values.filter { it.size > 1 }.forEach { bucket ->
            if (cancelled()) return groups
            val byHash = HashMap<String, MutableList<String>>()
            // #1729 one batched, parallel DriveCore hash per slice of the bucket (cancel is polled between slices); an unreadable file is left out of the groups
            DriveEngines.ensure()
            for (slice in bucket.chunked(64)) {
                if (cancelled()) break
                DriveCore.hashFiles(slice.map { it.absolutePath }, DriveCore.SHA256).forEach { h -> if (h.error.isEmpty()) byHash.getOrPut(h.sha256) { mutableListOf() }.add(h.path) }
            }
            byHash.values.filter { it.size > 1 }.forEach { groups += DuplicateGroup(bucket.first().length(), it) }
        }
        return groups.sortedByDescending { it.size * it.paths.size }
    }

    /** The lowercase hex SHA-256 of [file]: the DriveCore hash engine (Rust, Kotlin fallback). Throws like the stream it replaced when the file cannot be read. */
    fun sha256(file: File): String {
        DriveEngines.ensure()
        return DriveCore.sha256Hex(file.absolutePath) ?: throw IOException("cannot read ${file.path}")
    }

    private fun hex(digest: ByteArray): String = digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }

    // ── bulk rename ─────────────────────────────────────────────────────────

    data class RenameStep(val path: String, val oldName: String, val newName: String, val reason: String?) { val changes: Boolean get() = oldName != newName }

    /** Expands {name} {n} {ext} {date} per file and validates every target; the apply pass uses this exact plan. */
    fun renamePlan(files: List<File>, pattern: String): List<RenameStep> {
        val used = mutableSetOf<String>()
        return files.mapIndexed { i, f ->
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(f.lastModified()))
            val newName = pattern.replace("{name}", f.nameWithoutExtension).replace("{n}", (i + 1).toString()).replace("{ext}", f.extension).replace("{date}", date)
            val c = sanitize(newName)
            val reason = when {
                c == null -> "the pattern produced an empty name"
                c == f.name -> "the name does not change"
                File(f.parentFile, c).exists() -> "that name already exists on disk"
                c in used -> "two files would get the same name"
                else -> null
            }
            if (newName != f.name) used += newName
            RenameStep(f.absolutePath, f.name, newName, reason)
        }
    }

    /** No half-apply: a failed rename rolls every earlier one back. Returns how many were renamed. */
    @Throws(IOException::class)
    fun applyRenamePlan(plan: List<RenameStep>): Int {
        if (plan.any { it.reason != null && it.changes }) throw IOException("cannot rename: the preview found name conflicts")
        val applied = mutableListOf<Pair<File, String>>()
        for (step in plan) {
            if (!step.changes) continue
            val old = File(step.path); val target = File(old.parentFile, step.newName)
            if (!old.renameTo(target)) {
                applied.reversed().forEach { (renamed, original) -> renamed.renameTo(File(renamed.parentFile, original)) }
                throw IOException("could not rename ${old.name} — every change has been rolled back")
            }
            applied += target to step.oldName
        }
        return applied.size
    }

    // ── mirror (rsync on a phone) ───────────────────────────────────────────

    data class MirrorTally(var copied: Int = 0, var skipped: Int = 0, var deleted: Int = 0, var failed: Int = 0, var scanned: Int = 0) { val ok: Boolean get() = failed == 0 }

    /**
     * Copies everything under [source] into [destination], skipping what rsync's quick
     * check (size + mtime to the second) says is already there; [deleteExtra] is --delete.
     * Refuses a destination inside its own source before a byte moves.
     */
    @Throws(IOException::class)
    fun mirror(source: File, destination: File, deleteExtra: Boolean, cancelled: () -> Boolean, onProgress: (MirrorTally, String) -> Unit): MirrorTally {
        if (!source.isDirectory) throw notFolder(source)
        if (destination.path == source.path || destination.path.startsWith(source.path + File.separator)) throw IOException("the destination sits inside the source, so each pass would copy the last one")
        val tally = MirrorTally()
        fun into(src: File, dst: File, depth: Int) {
            if (cancelled()) return
            if (depth <= 0) { tally.failed++; return }
            if (!dst.isDirectory && !dst.mkdirs()) { tally.failed++; return }
            val children = src.listFiles() ?: run { tally.failed++; return }
            for (c in children) {
                if (cancelled()) return
                tally.scanned++
                val t = File(dst, c.name)
                if (c.isDirectory) into(c, t, depth - 1)
                else if (t.isFile && t.length() == c.length() && t.lastModified() / 1000L == c.lastModified() / 1000L) tally.skipped++
                else try { c.copyTo(t, overwrite = true); t.setLastModified(c.lastModified()); tally.copied++ } catch (e: Exception) { tally.failed++ }
                onProgress(tally, c.name)
            }
            if (!deleteExtra) return
            val kept = children.map { it.name }.toSet()
            dst.listFiles()?.filterNot { it.name in kept }?.forEach { extra -> if (extra.deleteRecursively()) tally.deleted++ else tally.failed++ }
        }
        into(source, destination, DEPTH_CEILING)
        return tally
    }

    // ── text ────────────────────────────────────────────────────────────────

    /** The editor will not open what it cannot write back: a NUL byte or a lossy UTF-8 round trip refuses the file. */
    @Throws(IOException::class)
    fun readText(file: File): String {
        if (!file.isFile) throw IOException("not a file: ${file.name}")
        if (file.length() > TEXT_CEILING) throw IOException("${file.name} is too large to edit here (over ${TEXT_CEILING / 1024 / 1024} MB)")
        val bytes = file.readBytes()
        if (bytes.any { it == 0.toByte() }) throw IOException("${file.name} is not a text file")
        val text = String(bytes, Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw IOException("${file.name} is not UTF-8 text, and saving it here would damage it")
        return text
    }

    /** Scratch sibling first, then rename (copy fallback on FUSE), so a crash mid-write leaves the original whole. */
    @Throws(IOException::class)
    fun writeText(file: File, text: String) {
        val parent = file.parentFile ?: throw IOException("no folder to write into")
        val payload = text.toByteArray(Charsets.UTF_8)
        val scratch = File(parent, "." + file.name + ".clouddrive-save")
        try {
            scratch.writeBytes(payload)
            if (scratch.length() != payload.size.toLong()) { scratch.delete(); throw IOException("the save did not finish — ${file.name} is unchanged") }
            if (!scratch.renameTo(file)) { scratch.copyTo(file, overwrite = true); scratch.delete() }
        } catch (e: Exception) {
            scratch.delete()
            throw if (e is IOException) e else IOException(e.message ?: "could not save ${file.name}")
        }
    }

    const val DIRECTORY_MIME = "inode/directory"
    const val OCTET_MIME = "application/octet-stream"
    const val DEPTH_CEILING = 32
    const val BUFFER = 1 * 1024 * 1024
    const val TEXT_CEILING = 2L * 1024 * 1024
    const val SEARCH_TEXT_CEILING = 1L * 1024 * 1024
    const val SEARCH_RESULT_CEILING = 300
}

/**
 * The Zip Slip guard, as a pure function so the shell tester and the JVM suite
 * (ZipSlipTest) hold it to account. Returns the file an entry may be written to, or
 * null when the entry name would escape [destination] — anything absolute, anything
 * with a ".." segment, or a canonical path outside the destination's canonical root.
 * Backslashes are read as separators (a zip made on Windows may use them).
 */
fun zipEntryTarget(destination: File, entryName: String): File? {
    val cleaned = entryName.replace('\\', '/')
    if (cleaned.startsWith("/")) return null
    val segments = cleaned.split("/").filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return null
    if (segments.isEmpty()) return null
    val target = File(destination, segments.joinToString("/"))
    val destCanonical = try { destination.canonicalFile } catch (e: Exception) { return null }
    val targetCanonical = try { target.canonicalFile } catch (e: Exception) { return null }
    val allowed = destCanonical == targetCanonical || targetCanonical.path.startsWith(destCanonical.path + File.separator)
    return if (allowed) targetCanonical else null
}
