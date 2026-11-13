package com.diegonmarcos.clouddrive.sync

import android.content.SharedPreferences
import com.diegonmarcos.clouddrive.startup.StartupGuard
import com.diegonmarcos.clouddrive.DriveEngines
import com.diegonmarcos.cloudlib.drivecore.DriveCore
import com.diegonmarcos.cloudlib.drivecore.Engine
import com.diegonmarcos.cloudlib.drivecore.Impl
import com.diegonmarcos.cloudlib.drivecore.WalkOpts
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The "Size on disk" column's facts: how many bytes a clone occupies (working tree and .git), computed by a walk, so
 * ONLY off the main thread ([compute] refuses to run on it). The answer is cached per clone under a [fingerprint]
 * (path + the mtimes of HEAD, index, FETCH_HEAD and the folder): while the fingerprint holds the walk is skipped,
 * when a commit, checkout, fetch or file add moves one of them only that clone is walked again (incremental).
 */
object RepoDiskSize {
    data class Entry(val fingerprint: String, val bytes: Long)

    /** Path + HEAD / index / FETCH_HEAD / folder mtimes: four stats, no walk. A missing file counts as 0. */
    fun fingerprint(dir: File): String {
        val git = File(dir, ".git")
        fun m(f: File) = f.lastModified()
        return "${dir.absolutePath}|${m(File(git, "HEAD"))}|${m(File(git, "index"))}|${m(File(git, "FETCH_HEAD"))}|${m(dir)}"
    }

    /** The recursive byte count of [dir], links not followed. Blocking: refuses the main thread. */
    fun compute(dir: File): Long = compute(dir, Int.MAX_VALUE)

    /**
     * [maxEntries] bounds the walk (the Performance benchmark's cap); the default is unbounded, as before. Recorded as the folder-scan span of
     * X-Ray > Performance. #1729 answered by the DriveCore walk engine (Rust by default, the same Kotlin walk as fallback when the switch
     * says Kotlin, the core is missing or it fails): [computeKotlin] is that Kotlin walk, kept as it was.
     */
    fun compute(dir: File, maxEntries: Int): Long {
        check(!StartupGuard.onMainThread()) { "disk walk asked from the main thread" }
        if (!dir.isDirectory) return 0L
        if (DriveCore.impl(Engine.WALK) != Impl.RUST) return computeKotlin(dir, maxEntries)
        DriveEngines.ensure()
        return com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler.span("fsync.folder.scan") { sp ->
            val agg = runCatching {
                DriveCore.walk(dir.absolutePath, WalkOpts(maxEntries = maxEntries.toLong(), budgetMs = Long.MAX_VALUE / 4, topFiles = 0, topFolders = 1))
            }.getOrNull()
            if (agg == null) return@span computeKotlin(dir, maxEntries)
            sp.add(items = agg.files, bytes = agg.bytes)
            agg.bytes
        }
    }

    /** The Kotlin walk: [compute] with the switch on Kotlin, and the benchmark's Kotlin row. Links are not followed. */
    fun computeKotlin(dir: File, maxEntries: Int): Long {
        check(!StartupGuard.onMainThread()) { "disk walk asked from the main thread" }
        if (!dir.isDirectory) return 0L
        return com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler.span("fsync.folder.scan") { sp ->
            var total = 0L; var seen = 0
            Files.walkFileTree(dir.toPath(), emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return if (++seen >= maxEntries) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
            sp.add(items = seen.toLong(), bytes = total)
            total
        }
    }

    /** True when [cached] still describes [dir]. */
    fun fresh(cached: Entry?, dir: File): Boolean = cached != null && cached.fingerprint == fingerprint(dir)

    /**
     * Bring [cache] up to date for [dirs] (path to folder): only a clone whose fingerprint moved (or all of them when
     * [force]) is walked; a folder that is gone drops out. [walk] is injectable so the suite counts the walks.
     */
    fun refresh(cache: Map<String, Entry>, dirs: Collection<File>, force: Boolean = false, walk: (File) -> Long = ::compute): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        dirs.forEach { d ->
            if (!d.isDirectory) return@forEach
            val have = cache[d.absolutePath]
            out[d.absolutePath] = if (!force && fresh(have, d)) have!! else Entry(fingerprint(d), walk(d))
        }
        return out
    }

    private const val KEY = "git.disk_sizes"

    fun encode(m: Map<String, Entry>): String = m.entries.joinToString("\n") { (p, e) -> "${e.bytes}\t${e.fingerprint}\t$p" }
    fun decode(s: String?): Map<String, Entry> = s.orEmpty().lines().mapNotNull { l ->
        val a = l.split('\t', limit = 3)
        val b = a.getOrNull(0)?.toLongOrNull()
        if (a.size == 3 && b != null) a[2] to Entry(a[1], b) else null
    }.toMap()

    fun load(p: SharedPreferences): Map<String, Entry> = decode(runCatching { p.getString(KEY, null) }.getOrNull())
    fun save(p: SharedPreferences, m: Map<String, Entry>) { p.edit().putString(KEY, encode(m)).apply() }
}
