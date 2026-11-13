package com.diegonmarcos.cloudlib.drivecore

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * The Kotlin reference engines. They return what the Rust core returns (DriveCoreParityTest holds both to the same fixtures) and are
 * the answer when a switch says Kotlin, when the core did not load, when it failed, and for any path that is not a real file path.
 */
internal object KotlinEngines {
    private const val BUF = 1 shl 20

    private fun hex(b: ByteArray): String {
        val d = "0123456789abcdef"
        val s = StringBuilder(b.size * 2)
        for (x in b) { s.append(d[(x.toInt() shr 4) and 15]); s.append(d[x.toInt() and 15]) }
        return s.toString()
    }

    fun hashOne(path: String, algos: Int, limit: Long): FileHash = try {
        val sha = if (algos and DriveCore.SHA256 != 0) MessageDigest.getInstance("SHA-256") else null
        val b3 = if (algos and DriveCore.BLAKE3 != 0) Blake3() else null
        val buf = ByteArray(BUF)
        var left = if (limit < 0) Long.MAX_VALUE else limit
        var total = 0L
        FileInputStream(File(path)).use { input ->
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                sha?.update(buf, 0, n)
                b3?.update(buf, 0, n)
                total += n
                left -= n
            }
        }
        FileHash(path, total, b3?.let { hex(it.digest()) } ?: "", sha?.let { hex(it.digest()) } ?: "", "")
    } catch (e: Exception) {
        FileHash(path, 0, "", "", e.message ?: e.javaClass.simpleName)
    }

    /** Sequential on purpose: the parallel path is the Rust core's job; this one is the reference. */
    fun hash(paths: List<String>, algos: Int, limit: Long): List<FileHash> = paths.map { hashOne(it, algos, limit) }

    /** The extension the way FileClassifier.extensionOf reads it, case preserved. */
    fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
    }

    fun walk(root: String, o: WalkOpts): WalkAgg {
        val rootDir = File(root)
        val t0 = System.currentTimeMillis()
        var entries = 0L
        var files = 0L; var dirs = 0L; var bytes = 0L
        var partial = ""
        val folders = HashMap<String, Long>()
        val top = ArrayList<SizedPath>()
        val byExt = HashMap<String, LongArray>()
        val listing = ArrayList<ListedFile>()
        var seen = 0L
        val rootStream = try { Files.newDirectoryStream(rootDir.toPath()) } catch (e: Exception) { throw IllegalStateException("$root: ${e.message}") }

        fun addFile(topKey: String, name: String, path: String, len: Long, mtime: Long) {
            files++; bytes += len
            folders[topKey] = (folders[topKey] ?: 0L) + len
            val e = byExt.getOrPut(extOf(name)) { LongArray(2) }; e[0]++; e[1] += len
            if (o.topFiles > 0) top += SizedPath(len, path)
            if (o.listFiles) listing += ListedFile(path, len, mtime)
        }

        class Job(val dir: File, val rel: String, val top: String)
        val stack = ArrayList<Job>()
        rootStream.use { s ->
            for (p in s) {
                if (partial.isNotEmpty()) break
                entries++
                if (entries > o.maxEntries) { partial = "partial, stopped after ${o.maxEntries} entries"; break }
                val attrs = try { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: Exception) { continue }
                if (attrs.isSymbolicLink) continue
                val name = p.fileName.toString()
                if (attrs.isDirectory) {
                    if (name in o.skipNames || name in o.skipRel) continue
                    dirs++
                    stack += Job(File(rootDir, name), name, File(rootDir, name).path)
                } else if (attrs.isRegularFile) {
                    addFile(o.looseLabel, name, File(rootDir, name).path, attrs.size(), attrs.lastModifiedTime().toMillis())
                }
            }
        }
        while (stack.isNotEmpty() && partial.isEmpty()) {
            val job = stack.removeAt(stack.size - 1)
            val ds = try { Files.newDirectoryStream(job.dir.toPath()) } catch (e: Exception) { continue }
            ds.use { s ->
                for (p in s) {
                    if (partial.isNotEmpty()) break
                    entries++
                    if (entries > o.maxEntries) { partial = "partial, stopped after ${o.maxEntries} entries"; break }
                    seen++
                    if ((seen and 1023L) == 0L && System.currentTimeMillis() - t0 > o.budgetMs) { partial = "partial, stopped after ${o.budgetMs / 1000} s"; break }
                    val attrs = try { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: Exception) { continue }
                    if (attrs.isSymbolicLink) continue
                    val name = p.fileName.toString()
                    if (attrs.isDirectory) {
                        val rel = job.rel + "/" + name
                        if (name in o.skipNames || rel in o.skipRel) continue
                        dirs++
                        stack += Job(File(job.dir, name), rel, job.top)
                    } else if (attrs.isRegularFile) {
                        addFile(job.top, name, File(job.dir, name).path, attrs.size(), attrs.lastModifiedTime().toMillis())
                    }
                }
            }
        }
        val fv = folders.entries.map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
            .let { if (o.topFolders > 0) it.take(o.topFolders) else it }
        val tf = top.sortedWith(compareByDescending<SizedPath> { it.bytes }.thenBy { it.path }).take(o.topFiles.coerceAtLeast(0))
        return WalkAgg(
            files, dirs, bytes, minOf(entries, o.maxEntries + 1), partial, fv, tf,
            byExt.toSortedMap().mapValues { ExtTotal(it.value[0], it.value[1]) },
            listing.sortedBy { it.path },
        )
    }

    fun diff(left: List<ListedFile>, right: List<ListedFile>): List<Delta> {
        val lm = left.associateBy { it.path }
        val rm = right.associateBy { it.path }
        val out = ArrayList<Delta>()
        for ((p, l) in lm) {
            val r = rm[p]
            if (r == null) out += Delta('D', p) else if (changed(l, r)) out += Delta('M', p)
        }
        for (p in rm.keys) if (p !in lm) out += Delta('A', p)
        return out.sortedWith(compareBy<Delta> { it.path }.thenBy { it.kind })
    }

    fun changed(l: ListedFile, r: ListedFile): Boolean {
        if (l.bytes != r.bytes) return true
        if (l.hash.isNotEmpty() && r.hash.isNotEmpty()) return l.hash != r.hash
        return l.mtimeMs != r.mtimeMs
    }
}
