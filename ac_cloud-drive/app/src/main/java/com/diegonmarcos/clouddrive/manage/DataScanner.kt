package com.diegonmarcos.clouddrive.manage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Manage ▸ Data's scanner: walks the declared roots (shared storage and the shared store) and
 * returns every classified file with its size. Pure java.nio so a JVM test drives it.
 *
 * NEVER LEAVES A ROOT. Nothing is followed through a symbolic link: a link is skipped whether it
 * points at a file or a folder (attributes are read with NOFOLLOW_LINKS), and a directory whose real
 * path is outside its root is skipped too (a bind mount or a FUSE alias can do that without a link).
 * A root inside another root is scanned once, under itself.
 *
 * INCREMENTAL. [cache] holds, per directory, its last-modified time and the files found directly in
 * it; a directory whose mtime has not moved reuses that list without a stat per file and only its
 * sub-directories are walked. A full rescan passes an empty cache. Adding, removing or renaming a
 * file moves its directory's mtime; a file rewritten in place does not, which is what Rescan is for.
 *
 * CANCELLABLE. [cancelled] is polled per directory and per file; a cancelled walk returns what it has
 * with [Result.complete] false. [progress] is called per directory.
 */
class DataScanner(private val skipNames: Set<String> = DEFAULT_SKIP) {

    data class Item(val path: String, val size: Long, val kind: DataKind, val root: String)
    data class DirEntry(val mtime: Long, val files: List<Item>, val dirs: List<String>)
    data class Progress(val dirs: Int, val files: Int, val bytes: Long, val current: String)
    data class Result(val items: List<Item>, val cache: Map<String, DirEntry>, val complete: Boolean, val reused: Int)

    fun scan(
        roots: List<File>,
        cache: Map<String, DirEntry> = emptyMap(),
        cancelled: () -> Boolean = { false },
        progress: (Progress) -> Unit = {},
        mimeOf: (String) -> String? = { null },
    ): Result {
        val real = roots.mapNotNull { r -> runCatching { r.toPath().toRealPath() }.getOrNull()?.let { r.absolutePath to it } }.distinctBy { it.second }
        val realRoots = real.map { it.second }
        val out = ArrayList<Item>()
        val nextCache = HashMap<String, DirEntry>()
        var dirs = 0; var files = 0; var bytes = 0L; var reused = 0; var complete = true

        for ((label, rootReal) in real) {
            val others = realRoots.filter { it != rootReal && it.startsWith(rootReal) }
            val stack = ArrayDeque<Path>().apply { add(rootReal) }
            while (stack.isNotEmpty()) {
                if (cancelled()) { complete = false; break }
                val dir = stack.removeLast()
                val dirReal = runCatching { dir.toRealPath() }.getOrNull() ?: continue
                if (!dirReal.startsWith(rootReal)) continue
                if (dirReal != rootReal && others.any { dirReal.startsWith(it) }) continue
                val key = dirReal.toString()
                val mtime = runCatching { Files.getLastModifiedTime(dirReal, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(-1L)
                dirs++
                progress(Progress(dirs, files, bytes, key))
                val hit = cache[key]
                val entry = if (hit != null && hit.mtime == mtime && mtime >= 0) {
                    reused++
                    hit.copy(files = hit.files.map { it.copy(root = label) })
                } else {
                    val fs = ArrayList<Item>(); val ds = ArrayList<String>()
                    val listed = runCatching { Files.newDirectoryStream(dirReal).use { s -> s.toList() } }.getOrDefault(emptyList())
                    for (child in listed) {
                        if (cancelled()) { complete = false; break }
                        val name = child.fileName?.toString() ?: continue
                        val attrs = runCatching { Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: continue
                        when {
                            attrs.isSymbolicLink || attrs.isOther -> Unit
                            attrs.isDirectory -> if (name !in skipNames && rootReal.relativize(dirReal.resolve(name)).toString() !in APP_SANDBOXES) ds.add(child.toString())
                            attrs.isRegularFile -> fs.add(Item(child.toString(), attrs.size(), FileClassifier.classifyOrOthers(name, mimeOf(name)), label))
                        }
                    }
                    DirEntry(mtime, fs, ds)
                }
                if (!complete) break
                nextCache[key] = entry
                for (f in entry.files) { out.add(f); files++; bytes += f.size }
                entry.dirs.forEach { stack.add(Path.of(it)) }
            }
            if (!complete) break
        }
        return Result(out.sortedByDescending { it.size }, nextCache, complete, reused)
    }

    companion object {
        /** Not data: app sandboxes on shared storage (they are Manage ▸ Apps' numbers) and VCS internals. */
        val DEFAULT_SKIP = setOf(".git", ".thumbnails", ".trashed")
        /** App sandboxes on shared storage, relative to a root: apps, not data (Manage ▸ Apps counts them). Android/media stays: it is user media. */
        val APP_SANDBOXES = setOf("Android/data", "Android/obb")

        /** Folders view: the listed files grouped by their parent folder, the folder's size the sum of its matching files. */
        fun folders(items: List<Item>): List<Pair<String, Long>> =
            items.groupBy { it.path.substringBeforeLast('/') }.map { (dir, xs) -> dir to xs.sumOf { it.size } }.sortedByDescending { it.second }

        fun filtered(items: List<Item>, filter: DataFilter): List<Item> = items.filter { filter.accepts(it.kind) }.sortedByDescending { it.size }
        /** The cache as text, one record per line (D = a directory, F = a file in it, S = a sub-directory). Tabs never occur in the fields we write. */
        fun encodeCache(cache: Map<String, DirEntry>): String = buildString {
            for ((dir, e) in cache) {
                append("D\t").append(dir).append('\t').append(e.mtime).append('\n')
                e.files.forEach { append("F\t").append(it.path).append('\t').append(it.size).append('\t').append(it.kind.name).append('\n') }
                e.dirs.forEach { append("S\t").append(it).append('\n') }
            }
        }

        fun decodeCache(text: String): Map<String, DirEntry> {
            val out = HashMap<String, DirEntry>()
            var dir: String? = null; var mtime = -1L; var fs = ArrayList<Item>(); var ds = ArrayList<String>()
            fun flush() { dir?.let { out[it] = DirEntry(mtime, fs, ds) } }
            for (line in text.lineSequence()) {
                val p = line.split('\t')
                when (p.firstOrNull()) {
                    "D" -> { flush(); dir = p.getOrNull(1); mtime = p.getOrNull(2)?.toLongOrNull() ?: -1L; fs = ArrayList(); ds = ArrayList() }
                    "F" -> { val k = DataKind.entries.firstOrNull { it.name == p.getOrNull(3) }; val sz = p.getOrNull(2)?.toLongOrNull(); if (k != null && sz != null && dir != null) fs.add(Item(p[1], sz, k, "")) }
                    "S" -> p.getOrNull(1)?.let { ds.add(it) }
                }
            }
            flush()
            return out
        }
    }
}
