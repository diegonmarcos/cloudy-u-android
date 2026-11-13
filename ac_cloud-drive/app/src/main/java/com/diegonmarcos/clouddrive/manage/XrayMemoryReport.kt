package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.files.FileOps

/**
 * Manage > Local Apps > Overview > X-Ray Memory: the pure half. One device report in Markdown (storage and memory) built from
 * whatever sources this phone allows. Every source is a [Src]: real data, or [Src.NA] with the reason, printed as
 * `not available: <reason>`; nothing is guessed or zero-filled. The report holds sizes, names and paths only: never file
 * contents, and a file whose name looks like a credential is listed as withheld ([withhold]).
 */
object XrayMemoryReport {
    sealed class Src<out T> {
        data class Ok<T>(val value: T) : Src<T>()
        data class NA(val reason: String) : Src<Nothing>()
    }

    data class Device(val manufacturer: String, val model: String, val androidRelease: String, val sdk: Int, val appVersion: String, val timestamp: String)
    data class Volume(val label: String, val path: String, val total: Long?, val free: Long?, val state: String)
    data class Sized(val path: String, val bytes: Long)
    data class MediaCat(val category: String, val files: Long, val bytes: Long)
    data class Mem(val totalBytes: Long, val availBytes: Long, val thresholdBytes: Long, val low: Boolean)
    data class Proc(val name: String, val pssKb: Long)

    data class Inputs(
        val device: Device,
        val volumes: Src<List<Volume>>,
        val apps: Src<List<AppRow>>,
        val folders: Src<List<Sized>>,
        val files: Src<List<Sized>>,
        val media: Src<List<MediaCat>>,
        val mem: Src<Mem>,
        val procs: Src<List<Proc>>,
        val adbMeminfo: Src<String>,
        val adbDf: Src<String>,
        val adbDu: Src<String>,
        /** Shared-storage walk note: null = complete, else why it is partial (printed in the header). */
        val scanNote: String? = null,
    )

    /** The H2 section titles, in order: the contract the tests and the screenshots read. */
    val SECTIONS = listOf(
        "Storage: volumes", "Storage: per-app sizes", "Storage: largest folders on shared storage",
        "Storage: largest files on shared storage", "Storage: media categories",
        "Memory: RAM", "Memory: per-process PSS", "ADB owner channel: dumpsys meminfo", "ADB owner channel: df", "ADB owner channel: du",
    )

    const val MAX_APPS = 400
    private val SECRET_NAME = Regex("""(?i)(id_(rsa|dsa|ecdsa|ed25519)|\.pem$|\.key$|\.p12$|\.pfx$|\.jks$|\.keystore$|\.kdbx$|\.env$|\.netrc$|credential|secret|passw|token|\.ssh|\.gnupg|\.aws)""")

    /** A path whose last segments look like a credential is shown as its directory plus a withheld marker (the name itself may be the secret). */
    fun withhold(path: String): String =
        if (SECRET_NAME.containsMatchIn(path)) path.substringBeforeLast('/', "").let { "$it/(name withheld: looks like a credential file)" } else path

    /** `dumpsys meminfo` is hundreds of KB: keep "Total PSS by process" (top [pssLines]) and the RAM summary tail. Lines only; nothing is interpreted. */
    fun trimMeminfo(text: String, pssLines: Int = 60, tailLines: Int = 30): String {
        val lines = text.lines()
        val a = lines.indexOfFirst { it.trim().startsWith("Total PSS by process") }
        val b = lines.indexOfFirst { it.trim().startsWith("Total RAM:") }
        val out = ArrayList<String>()
        if (a >= 0) out += lines.drop(a).take(pssLines + 1)
        if (b >= 0) { if (out.isNotEmpty()) out += ""; out += lines.drop(b).take(tailLines) }
        return if (out.isEmpty()) lines.take(pssLines).joinToString("\n") else out.joinToString("\n")
    }

    /** Drop the lines of a shell listing that name a credential-looking path. */
    fun scrubLines(text: String): String = text.lines().joinToString("\n") { if (SECRET_NAME.containsMatchIn(it)) "(line withheld: looks like a credential path)" else it }

    fun na(reason: String) = "not available: " + reason.replace('\n', ' ').trim().ifBlank { "unknown reason" }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").replace("\r", " ")
    private fun table(headers: List<String>, rows: List<List<String>>): String = buildString {
        append("| ").append(headers.joinToString(" | ") { cell(it) }).append(" |\n")
        append("|").append(headers.joinToString("|") { "---" }).append("|\n")
        rows.forEach { append("| ").append(it.joinToString(" | ") { c -> cell(c) }).append(" |\n") }
    }
    /** A fenced block that its own content cannot close early. */
    private fun fenced(text: String): String = "```text\n" + text.replace("```", "'''").trimEnd() + "\n```\n"
    private fun size(b: Long?) = if (b == null) "n/a" else FileOps.humanBytes(b)

    private fun <T> section(sb: StringBuilder, title: String, src: Src<T>, body: (T) -> String) {
        sb.append("\n## ").append(title).append("\n\n")
        when (src) {
            is Src.NA -> sb.append(na(src.reason)).append('\n')
            is Src.Ok -> sb.append(body(src.value).ifBlank { "none found\n" })
        }
    }

    fun markdown(i: Inputs): String {
        val sb = StringBuilder()
        val d = i.device
        sb.append("# X-Ray Memory: ").append(cell(d.manufacturer)).append(' ').append(cell(d.model)).append("\n\n")
        sb.append("- Device: ").append(d.manufacturer).append(' ').append(d.model).append('\n')
        sb.append("- Android: ").append(d.androidRelease).append(" (API ").append(d.sdk).append(")\n")
        sb.append("- Generated: ").append(d.timestamp).append('\n')
        sb.append("- Generator: Cloud Drive ").append(d.appVersion).append('\n')
        sb.append("- Shared storage scan: ").append(i.scanNote ?: "complete").append('\n')
        sb.append("- Scope: sizes, names and paths only; no file contents, no credentials.\n")

        section(sb, SECTIONS[0], i.volumes) { vs -> table(listOf("Volume", "Path", "Total", "Used", "Free", "State"), vs.map {
            val used = if (it.total != null && it.free != null) it.total - it.free else null
            listOf(it.label, it.path, size(it.total), size(used), size(it.free), it.state) }) }

        section(sb, SECTIONS[1], i.apps) { rows ->
            val shown = rows.sortedWith(compareByDescending<AppRow> { it.totalBytes ?: -1L }.thenBy { it.label.lowercase() }).take(MAX_APPS)
            val unread = rows.count { it.totalBytes == null }
            table(listOf("App", "Package", "Version", "Code", "Data", "Cache", "Total"), shown.map {
                listOf(it.label, it.pkg, it.versionName ?: "n/a", size(it.appBytes), size(it.dataBytes), size(it.cacheBytes), size(it.totalBytes)) }) +
                "\n${rows.size} apps, ${shown.size} listed" + (if (unread > 0) "; $unread without a readable size (usage access not granted for them)" else "") + ".\n"
        }

        val sizedList = { rows: List<Sized> -> table(listOf("Path", "Size"), rows.map { listOf(withhold(it.path), size(it.bytes)) }) }
        section(sb, SECTIONS[2], i.folders, sizedList)
        section(sb, SECTIONS[3], i.files, sizedList)
        section(sb, SECTIONS[4], i.media) { cs -> table(listOf("Category", "Files", "Size"), cs.map { listOf(it.category, it.files.toString(), size(it.bytes)) }) }

        section(sb, SECTIONS[5], i.mem) { m ->
            table(listOf("Metric", "Value"), listOf(
                listOf("Total RAM", size(m.totalBytes)), listOf("Available RAM", size(m.availBytes)),
                listOf("Used RAM", size(m.totalBytes - m.availBytes)), listOf("Low-memory threshold", size(m.thresholdBytes)),
                listOf("Low memory now", if (m.low) "yes" else "no")))
        }
        section(sb, SECTIONS[6], i.procs) { ps -> table(listOf("Process / package", "PSS"), ps.sortedByDescending { it.pssKb }.map { listOf(it.name, size(it.pssKb * 1024)) }) }
        section(sb, SECTIONS[7], i.adbMeminfo) { fenced(it) }
        section(sb, SECTIONS[8], i.adbDf) { fenced(it) }
        section(sb, SECTIONS[9], i.adbDu) { fenced(it) }
        return sb.toString()
    }

    /** Well-formedness check used by the tests (and the collector before it offers the file): returns the problems, empty = fine. */
    fun lint(md: String): List<String> {
        val bad = ArrayList<String>()
        val lines = md.lines()
        if (lines.count { it.startsWith("# ") } != 1) bad += "exactly one H1 expected"
        if (lines.count { it.trimStart().startsWith("```") } % 2 != 0) bad += "unbalanced code fence"
        var fence = false; var cols = -1
        for ((n, l) in lines.withIndex()) {
            if (l.trimStart().startsWith("```")) { fence = !fence; continue }
            if (fence) continue
            if (l.startsWith("|")) {
                val c = l.replace("\\|", "").count { it == '|' }
                if (cols == -1) cols = c else if (c != cols) bad += "table row ${n + 1} has $c pipes, expected $cols"
            } else cols = -1
        }
        if (!md.endsWith("\n")) bad += "no trailing newline"
        return bad
    }
}
