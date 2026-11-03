package com.diegonmarcos.clouddrive.sync

import java.util.Locale

/**
 * #1419 AUTO COMMIT: stage every dirty file and commit it with a message built from the staged diff.
 * Pure (no Android), so AutoCommitTest pins the rule, the truncation, the folder grouping, the counts,
 * the all-repos target filter and the writer's fallback.
 *
 * The rule (Configs ▸ Git ▸ Auto Commit shows it with a sample):
 *  - subject: `auto: 12 files (+340 −58, 1.2 MB) in 3 folders`;
 *  - body: every affected folder (its full path in the repository, file count, +/− lines, size), then the changed
 *    files one per line as `path/name (+a −d)`, until the message reaches [LIMIT] bytes, then `…and N more`.
 */
object AutoCommit {
    /** The whole message stays within this many UTF-8 bytes (subject and tail line included). */
    const val LIMIT = 4096
    /** How much of the diff a model writer is sent. */
    const val DIFF_FOR_MODEL = 8000

    /** One staged file: its repository path, added and deleted lines, and its size in the working tree (0 = deleted). */
    data class FileStat(val path: String, val added: Int, val deleted: Int, val bytes: Long)

    data class FolderStat(val path: String, val files: Int, val added: Int, val deleted: Int, val bytes: Long)

    /** The per-file +/− counts of a unified `git diff` (the b-side path; a deletion keeps the a-side path). */
    fun parseDiff(diff: String): Map<String, Pair<Int, Int>> {
        val out = LinkedHashMap<String, IntArray>()
        var cur: IntArray? = null
        var inHunk = false
        for (line in diff.lineSequence()) {
            if (line.startsWith("diff --git ")) {
                val m = Regex("^diff --git a/(.*) b/(.*)$").find(line)
                val path = m?.groupValues?.get(2) ?: line.removePrefix("diff --git ")
                cur = out.getOrPut(path) { IntArray(2) }
                inHunk = false
                continue
            }
            val c = cur ?: continue
            if (line.startsWith("@@")) { inHunk = true; continue }
            if (!inHunk) continue
            when {
                line.startsWith("+") -> c[0]++
                line.startsWith("-") -> c[1]++
            }
        }
        return out.mapValues { (_, v) -> v[0] to v[1] }
    }

    /** The diff's files with their sizes ([sizeOf] reads the working tree; a missing file is 0). */
    fun stats(diff: String, sizeOf: (String) -> Long): List<FileStat> =
        parseDiff(diff).map { (p, c) -> FileStat(p, c.first, c.second, runCatching { sizeOf(p) }.getOrDefault(0L)) }.sortedBy { it.path }

    /** The folder a file sits in: its full repository path, `/` for the root. */
    fun folderOf(path: String): String = path.substringBeforeLast('/', "").ifBlank { "/" }

    fun folders(files: List<FileStat>): List<FolderStat> =
        files.groupBy { folderOf(it.path) }.map { (dir, fs) -> FolderStat(dir, fs.size, fs.sumOf { it.added }, fs.sumOf { it.deleted }, fs.sumOf { it.bytes }) }.sortedBy { it.path }

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble()
        for (u in listOf("KB", "MB", "GB", "TB")) {
            v /= 1024
            if (v < 1024 || u == "TB") return String.format(Locale.ROOT, "%.1f %s", v, u)
        }
        return "$bytes B"
    }

    private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

    fun subject(files: List<FileStat>): String {
        val fs = folders(files)
        return "auto: ${plural(files.size, "file")} (+${files.sumOf { it.added }} −${files.sumOf { it.deleted }}, ${size(files.sumOf { it.bytes })}) in ${plural(fs.size, "folder")}"
    }

    fun folderLine(f: FolderStat) = "${f.path} — ${plural(f.files, "file")} (+${f.added} −${f.deleted}, ${size(f.bytes)})"
    fun fileLine(f: FileStat) = "${f.path} (+${f.added} −${f.deleted})"

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** The rule's message: subject, the folders, the files, within [limit] bytes; what does not fit is counted in `…and N more`. */
    fun message(files: List<FileStat>, limit: Int = LIMIT): String {
        val dirs = folders(files).map { folderLine(it) }
        val entries = dirs + files.map { fileLine(it) }
        val out = StringBuilder(subject(files)).append("\n\n")
        var used = bytes(out.toString())
        for ((i, e) in entries.withIndex()) {
            val piece = (if (i == dirs.size && i > 0) "\n" else "") + e + "\n"
            val rest = entries.size - i - 1
            if (used + bytes(piece) + (if (rest > 0) bytes(tail(rest)) else 0) > limit) return out.append(tail(entries.size - i)).toString()
            out.append(piece); used += bytes(piece)
        }
        return out.toString().trimEnd('\n')
    }

    fun tail(n: Int) = "…and $n more"

    /** The sample Configs ▸ Git ▸ Auto Commit previews (three folders, a deletion, a binary-sized file). */
    val SAMPLE: List<FileStat> = listOf(
        FileStat("app/src/main/java/Main.kt", 120, 14, 18_400), FileStat("app/src/main/java/Sync.kt", 96, 30, 22_100),
        FileStat("app/src/main/res/values/strings.xml", 12, 2, 9_800), FileStat("docs/README.md", 40, 12, 6_200),
        FileStat("docs/old.md", 0, 0, 0), FileStat("build.json", 72, 0, 1_200_000),
    )

    // ── the writer ──────────────────────────────────────────────────────────────────────────────────

    enum class Writer(val id: String, val label: String) {
        RULE("rule", "Rule (default)"), MODEL("openrouter", "OpenRouter model");
        companion object { fun of(id: String?): Writer = values().firstOrNull { it.id == id } ?: RULE }
    }

    /** The message and who wrote it: `rule`, `model <id>` or `rule (fallback: why)`. */
    data class Written(val message: String, val by: String)

    /** What a model writer is sent: the rule's stat block plus the diff cut to [DIFF_FOR_MODEL] characters. */
    fun prompt(files: List<FileStat>, diff: String): String =
        "Write one git commit message for the staged change below. First line: a summary under 72 characters. " +
            "Then a blank line and a short body. Answer with the message only.\n\n" +
            "Diff stat:\n" + message(files, LIMIT).substringAfter("\n\n") + "\n\nDiff (truncated):\n" +
            (if (diff.length > DIFF_FOR_MODEL) diff.take(DIFF_FOR_MODEL) + "\n…(truncated)" else diff)

    /** A model's answer as a message: fences dropped, trimmed, cut to [LIMIT] bytes; null when nothing usable is left. */
    fun clean(answer: String?): String? {
        val t = answer?.trim()?.removePrefix("```")?.removeSuffix("```")?.trim().orEmpty()
        if (t.isBlank()) return null
        var s = t
        while (bytes(s) > LIMIT) s = s.dropLast(1)
        return s
    }

    /**
     * The message for [files]. [Writer.MODEL] asks [ask] (model id, prompt → the answer, null/throw = failed) when [online];
     * any failure, an empty answer, offline or no model falls back to the rule, saying why.
     */
    fun write(files: List<FileStat>, diff: String, writer: Writer, model: String?, online: Boolean, ask: ((String, String) -> String?)?): Written {
        val rule = message(files)
        if (writer == Writer.RULE) return Written(rule, "rule")
        val why = when {
            model.isNullOrBlank() -> "no model picked"
            !online -> "offline"
            ask == null -> "no model client"
            else -> {
                val r = runCatching { ask(model, prompt(files, diff)) }
                val m = clean(r.getOrNull())
                if (m != null) return Written(m, "model $model")
                r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "empty answer"
            }
        }
        return Written(rule, "rule (fallback: $why)")
    }

    /** Auto Commit All runs on the dirty clones: a clone known clean ([dirtyOf] = 0) is left out; unknown (null) is tried. */
    fun <T> targets(repos: List<T>, dirtyOf: (T) -> Int?): List<T> = repos.filter { (dirtyOf(it) ?: 1) > 0 }
}
