package com.diegonmarcos.clouddrive.sync.inventory

import com.diegonmarcos.clouddrive.sync.LocalRepos
import com.diegonmarcos.clouddrive.sync.RepoCheap
import java.io.File

/**
 * #1315 THE ONE DISCOVERY PASS over the store: one script (run by the one StoreShell in cld.termux, where the store is
 * visible whatever this app's uid) lists every git working tree, bare repository and leftover folder under the declared
 * roots with the facts the inventory classifies on: origin, branch, upstream, HEAD time, shallow. The same shape comes
 * from [walkIo] for roots outside the store (shared storage, this app's files), so the inventory has ONE input type.
 * Pure (build, parse, walk over java.io), JVM-tested (InventoryScriptTest).
 */
object InventoryScript {

    /** #1491 a bare repository is a TREE with [Entry.bare] set, not a kind of its own: one list of repo folders. */
    enum class Kind { TREE, GITFILE, DEBRIS }

    /** One folder the pass found. [path] is in HOST form once parsed (the script prints guest paths; [parse] translates). */
    data class Entry(
        val path: String,
        val kind: Kind,
        val origin: String = "",
        val branch: String? = null,
        val upstream: String? = null,
        val headTimeMs: Long = 0,
        val shallow: Boolean = false,
        /** #1491 a bare repository (no working tree): an attribute of the repo, never its own list. */
        val bare: Boolean = false,
    ) {
        val name: String get() = path.trimEnd('/').substringAfterLast('/')
        /** The repository's own name: a bare folder `x.git` is the repository `x`. */
        val repoName: String get() = if (bare) name.removeSuffix(".git") else name
    }

    /** The script's output after [parse]: the entries and how many lines could not be read. */
    data class Parsed(val entries: List<Entry>, val malformed: Int, val skippedOutside: Int)

    /** A glob a `find -name` accepts: letters, digits, `. _ - * ?` only (never a quote, a slash or a space). */
    fun safeGlob(g: String): Boolean = g.isNotBlank() && g.length <= 64 && g.all { it.isLetterOrDigit() || it in "._-*?" } && g != "*" && g != ".*"

    /**
     * The one script. [guestRoots] in guest form; a repository nests at most [maxDepth] folders under a root; every folder
     * named like one of [debris] is reported as DEBRIS and never entered; hidden folders and `Android` are never entered.
     * Prints one TAB-separated line per finding: `kind path origin branch upstream headct shallow`.
     */
    fun build(guestRoots: List<String>, maxDepth: Int, debris: List<String>, q: (String) -> String): String {
        val depth = (maxDepth.coerceIn(0, 8) + 1)
        val debrisNames = debris.filter(::safeGlob).joinToString(" ") { "-o -name ${q(it)}" }
        val roots = guestRoots.joinToString(" ") { q(it) }
        return "for r in $roots; do [ -d \"\$r\" ] || continue; " +
            "find \"\$r\" -mindepth 1 -maxdepth $depth " +
            "\\( -name .git -prune -print \\) -o \\( \\( -false $debrisNames \\) -prune -print \\) -o \\( -name '*.git' -prune -print \\) " +
            "-o \\( \\( -name '.*' -o -name Android \\) -prune \\) 2>/dev/null; done | while IFS= read -r g; do " +
            "case \"\$g\" in " +
            "*/.git) d=\"\${g%/.git}\"; if [ -d \"\$g\" ]; then " +
            "printf 'tree\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$d\" \"\$(git -C \"\$d\" remote get-url origin 2>/dev/null)\" " +
            "\"\$(git -C \"\$d\" symbolic-ref -q --short HEAD 2>/dev/null)\" \"\$(git -C \"\$d\" rev-parse -q --abbrev-ref '@{u}' 2>/dev/null)\" " +
            "\"\$(git -C \"\$d\" log -1 --format=%ct 2>/dev/null)\" \"\$([ -f \"\$g/shallow\" ] && echo 1 || echo 0)\"; " +
            "else printf 'gitfile\\t%s\\n' \"\$d\"; fi;; " +
            "*.git) if [ -f \"\$g/HEAD\" ] && [ -d \"\$g/objects\" ]; then " +
            "printf 'tree\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t1\\n' \"\$g\" \"\$(git --git-dir=\"\$g\" config remote.origin.url 2>/dev/null)\" " +
            "\"\$(git --git-dir=\"\$g\" symbolic-ref -q --short HEAD 2>/dev/null)\" \"\$(git --git-dir=\"\$g\" rev-parse -q --abbrev-ref '@{u}' 2>/dev/null)\" " +
            "\"\$(git --git-dir=\"\$g\" log -1 --format=%ct 2>/dev/null)\" \"\$([ -f \"\$g/shallow\" ] && echo 1 || echo 0)\"; fi;; " +
            "*) printf 'debris\\t%s\\n' \"\$g\";; " +
            "esac; done; true"
    }

    /** The script's lines as entries; [host] maps a guest path to its host form (null = outside the store, skipped). */
    fun parse(out: String, host: (String) -> String?): Parsed {
        val entries = ArrayList<Entry>()
        var malformed = 0
        var outside = 0
        for (raw in out.lineSequence()) {
            if (raw.isEmpty()) continue // a whitespace-only line is a malformed record, counted below
            val f = raw.split('\t')
            val kind = when (f[0]) { "tree", "bare" -> Kind.TREE; "gitfile" -> Kind.GITFILE; "debris" -> Kind.DEBRIS; else -> null }
            val path = f.getOrNull(1)?.trim().orEmpty()
            if (kind == null || path.isBlank()) { malformed++; continue }
            val h = host(path) ?: run { outside++; null } ?: continue
            entries += when (kind) {
                Kind.TREE -> Entry(h, kind, f.getOrNull(2).orEmpty().trim(), f.getOrNull(3)?.trim()?.ifEmpty { null }, f.getOrNull(4)?.trim()?.ifEmpty { null },
                    (f.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L) * 1000L, f.getOrNull(6)?.trim() == "1", f.getOrNull(7)?.trim() == "1" || f[0] == "bare")
                else -> Entry(h, kind)
            }
        }
        return Parsed(entries.distinctBy { it.path }, malformed, outside)
    }

    /**
     * The same pass over java.io for roots this app can read itself (a root outside the store, or the store in DIRECT mode
     * with no cld.termux): bounded by [maxDepth], [maxDirs] and [deadlineMs]; a symlinked folder and a `.git` file pointing
     * outside are not clones of their own (#1317); [skip] prunes a folder (the archive root).
     */
    fun walkIo(roots: List<File>, maxDepth: Int, maxDirs: Int, deadlineMs: Long, debris: List<String>, now: () -> Long = System::currentTimeMillis, skip: (File) -> Boolean = { false }): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        val debrisRx = debris.filter(::safeGlob).map { globRegex(it) }
        var seen = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        roots.filter { it.isDirectory }.distinctBy { it.absolutePath }.forEach { queue.add(it to 0) }
        while (queue.isNotEmpty() && seen < maxDirs && now() < deadlineMs) {
            val (d, depth) = queue.removeFirst(); seen++
            if (skip(d)) continue
            if (depth > 0) {
                val n = d.name
                if (debrisRx.any { it.matches(n) }) { out.putIfAbsent(d.absolutePath, Entry(d.absolutePath, Kind.DEBRIS)); continue }
                // #1491 the ONE detection (git-repos RepoDetect): a bare repo folder is a repo like a working tree, with the bare attribute.
                if (com.diegonmarcos.cloudlib.gitrepos.RepoDetect.isBare(d)) {
                    out.putIfAbsent(d.absolutePath, Entry(d.absolutePath, Kind.TREE, LocalRepos.originOf(d).ifBlank { bareOrigin(d) }, null, null, 0L, File(d, "shallow").isFile, bare = true)); continue
                }
            }
            val dot = File(d, ".git")
            if (dot.exists()) {
                if (LocalRepos.ownGit(d)) {
                    val h = RepoCheap.head(d)
                    out.putIfAbsent(d.absolutePath, Entry(d.absolutePath, Kind.TREE, LocalRepos.originOf(d), h.branch, h.upstream, 0L, File(dot, "shallow").isFile))
                } else out.putIfAbsent(d.absolutePath, Entry(d.absolutePath, Kind.GITFILE))
                continue
            }
            if (depth >= maxDepth) continue
            d.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" && !LocalRepos.isSymlink(it) }.sortedBy { it.name.lowercase() }
                .forEach { queue.add(it to depth + 1) }
        }
        return out.values.toList()
    }

    /** #1315 the sizes on disk of many folders in ONE script (`du -sk`): `<guest dir> TAB <kbytes>` per line; a gone folder prints nothing. */
    fun sizesScript(guestDirs: List<String>, q: (String) -> String): String =
        "for d in ${guestDirs.joinToString(" ") { q(it) }}; do [ -d \"\$d\" ] || continue; printf '%s\\t%s\\n' \"\$d\" \"\$(du -sk \"\$d\" 2>/dev/null | cut -f1)\"; done; true"

    /** [sizesScript]'s lines as bytes by guest path (a line without a number is skipped). */
    fun parseSizes(out: String): Map<String, Long> =
        out.lineSequence().filter { '\t' in it }.mapNotNull { l -> l.substringAfter('\t').trim().toLongOrNull()?.let { kb -> l.substringBefore('\t') to kb * 1024L } }.toMap()

    private fun bareOrigin(gitDir: File): String = runCatching { File(gitDir, "config").takeIf { it.isFile }?.readText()?.let { LocalRepos.originFromConfig(it) } }.getOrNull().orEmpty()

    private fun globRegex(glob: String): Regex = Regex(glob.map { c -> when (c) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(c.toString()) } }.joinToString(""))

    /** The entries as one prefs string (one line each, TAB-separated) and back: the last inventory survives a restart. */
    fun encode(l: List<Entry>): String = l.joinToString("\n") { e -> listOf(e.kind.name, e.path, e.origin, e.branch.orEmpty(), e.upstream.orEmpty(), e.headTimeMs.toString(), if (e.shallow) "1" else "0", if (e.bare) "1" else "0").joinToString("\t") }
    fun decode(s: String?): List<Entry> = s.orEmpty().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split('\t')
        // an older save wrote BARE as a kind of its own: it reads back as a TREE with the bare attribute
        val kind = runCatching { Kind.valueOf(if (f[0] == "BARE") "TREE" else f[0]) }.getOrNull() ?: return@mapNotNull null
        val path = f.getOrNull(1).orEmpty(); if (path.isBlank()) return@mapNotNull null
        Entry(path, kind, f.getOrNull(2).orEmpty(), f.getOrNull(3)?.ifEmpty { null }, f.getOrNull(4)?.ifEmpty { null }, f.getOrNull(5)?.toLongOrNull() ?: 0L, f.getOrNull(6) == "1", f.getOrNull(7) == "1" || f[0] == "BARE")
    }.toList()
}
