package com.diegonmarcos.cloudlib.gitrepos

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * #1313 STALE PACK CLEANUP (build.json ui.sync.git.stale_pack_cleanup), JVM-tested in StalePackCleanerTest.
 *
 * An interrupted clone or fetch leaks its temp pack: JGit's ObjectDirectoryPackParser writes `incoming_<digits>.pack/.idx`
 * into objects/pack (renamed to pack-<sha>.* on success), and C git (the Termux git of the bare mirrors) leaves
 * `tmp_pack_XXXXXX`, `tmp_idx_XXXXXX`, `tmp_rev_*`. They are hundreds of MB each and nothing else ever deletes them.
 *
 *  - ONLY `objects/pack` is touched: `<git root>/<clone>/.git/objects/pack` of every working clone and
 *    `<git root>/<bare_root>/<owner>/<repo>.git/objects/pack` of every bare mirror.
 *  - A file goes only if ALL hold: its name matches a declared pattern; it does NOT match `pack-*` (checked here whatever
 *    the declaration says: a real pack, .idx, .rev, .keep, .promisor is never a candidate); it is a regular file, not a
 *    symlink; its last-modified time is older than min_age_hours (a live download keeps writing, so its mtime is fresh);
 *    and no git job is running (the whole pass is skipped, the coordinator keeps no finer per-repo state for the
 *    background and bulk jobs).
 *  - The same rules run two ways: [sweep] on java.io.File when this app can open the store, [script] inside cld.termux
 *    (find, no shell glob on the pattern) when the store is only reachable through CLOUD_EXEC.
 */
object StalePackCleaner {

    data class Result(val deleted: Int = 0, val bytes: Long = 0, /** why nothing ran, "" when the pass ran */ val skipped: String = "")

    /** Used when the declaration lists no safe pattern. */
    val DEFAULT_PATTERNS = listOf("incoming_*.pack", "incoming_*.idx", "tmp_pack_*", "tmp_idx_*", "tmp_rev_*")

    const val PACK_PREFIX = "pack-"
    private val SAFE_PATTERN = Regex("[A-Za-z0-9_.*?-]+")

    /** The patterns that may be declared: plain file-name globs, never a path, never one that can name a real pack. */
    fun safePattern(p: String): Boolean = SAFE_PATTERN.matches(p) && !p.startsWith(PACK_PREFIX) && !p.startsWith("*") && !p.startsWith("?")

    /** `*` and `?` glob of a file name as a regex. */
    private fun globRegex(glob: String): Regex =
        Regex(glob.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString(""))

    /** A candidate name: matches a declared pattern and is never `pack-*`. */
    fun matches(name: String, patterns: List<String>): Boolean =
        !name.startsWith(PACK_PREFIX) && patterns.any { safePattern(it) && globRegex(it).matches(name) }

    /** True when no git job may be touched: any per-repo operation, a clone, a depth / bare run or a bulk pull is in flight. */
    fun busy(runningRepos: Int, cloning: Int, depthRunActive: Boolean, bulkActive: Boolean): Boolean =
        runningRepos > 0 || cloning > 0 || depthRunActive || bulkActive

    /** Every objects/pack folder under [gitRoot]: the working clones' and the bare mirrors'. Real directories only. */
    fun packDirs(gitRoot: File, bareRoot: String): List<File> {
        fun real(d: File) = d.isDirectory && !Files.isSymbolicLink(d.toPath())
        val out = ArrayList<File>()
        gitRoot.listFiles()?.sortedBy { it.name }?.forEach { clone ->
            if (!real(clone)) return@forEach
            val pack = File(clone, ".git/objects/pack")
            if (real(pack)) out += pack
        }
        File(gitRoot, bareRoot).listFiles()?.sortedBy { it.name }?.forEach { owner ->
            if (!real(owner)) return@forEach
            owner.listFiles()?.sortedBy { it.name }?.forEach { repo ->
                val pack = File(repo, "objects/pack")
                if (repo.name.endsWith(".git") && real(repo) && real(pack)) out += pack
            }
        }
        return out
    }

    /** The pass on java.io.File. [jobRunning] true = skip everything. Never throws: an unreadable file is left. */
    fun sweep(packDirs: List<File>, patterns: List<String>, minAgeHours: Int, nowMs: Long, jobRunning: Boolean): Result {
        if (jobRunning) return Result(skipped = "a git job is running")
        val cutoff = nowMs - minAgeHours.coerceAtLeast(1) * 3_600_000L
        var n = 0; var bytes = 0L
        packDirs.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val p = f.toPath()
                if (!matches(f.name, patterns)) return@forEach
                if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return@forEach
                if (f.lastModified() >= cutoff) return@forEach
                val size = f.length()
                if (runCatching { Files.delete(p) }.isSuccess) { n++; bytes += size }
            }
        }
        return Result(n, bytes)
    }

    /**
     * The same pass for cld.termux (GNU find): [guestGit] is the store's git folder as the guest sees it, [q] shell-quotes.
     * Prints one line per deleted file, its size in bytes; read it back with [parse]. -type f excludes symlinks, ! -name 'pack-*'
     * excludes real packs, -mmin +N is the age gate, -delete runs last so only a successful delete prints.
     */
    fun script(guestGit: String, bareRoot: String, patterns: List<String>, minAgeHours: Int, q: (String) -> String): String {
        val g = q(guestGit.trimEnd('/')); val b = q(bareRoot.trim('/'))
        val names = patterns.filter { safePattern(it) }
        if (names.isEmpty()) return "true"
        val match = names.joinToString(" -o ") { "-name ${q(it)}" }
        return "for d in $g/*/.git/objects/pack $g/$b/*/*.git/objects/pack; do " +
            "[ -d \"\$d\" ] && [ ! -L \"\$d\" ] || continue; " +
            "find \"\$d\" -maxdepth 1 -type f ! -name 'pack-*' \\( $match \\) -mmin +${minAgeHours.coerceAtLeast(1) * 60} -delete -printf '%s\\n'; done"
    }

    /** The script's output back into a [Result]: one size per line. */
    fun parse(out: String): Result {
        val sizes = out.lineSequence().mapNotNull { it.trim().toLongOrNull() }.toList()
        return Result(sizes.size, sizes.sum())
    }

    /** "Cleaned 4 stale pack files (1.2 GB)"; "" when nothing was deleted (the cleaner says nothing then). */
    fun message(r: Result): String =
        if (r.deleted <= 0) "" else "Cleaned ${r.deleted} stale pack file${if (r.deleted == 1) "" else "s"} (${humanBytes(r.bytes)})"

    fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.1f KB", b / (1L shl 10).toDouble())
        else -> "$b B"
    }
}
