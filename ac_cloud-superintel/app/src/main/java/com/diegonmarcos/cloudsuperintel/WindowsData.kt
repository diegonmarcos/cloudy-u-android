package com.diegonmarcos.cloudsuperintel

import java.util.Locale

/** #1854 One slice of the Context window's breakdown. */
data class CtxSegment(val id: String, val label: String, val tokens: Long, val note: String)

/** #1854 One file of the Files window; [sample] = bundled mock data, not read from the chat. */
data class FileRow(val name: String, val kind: String, val bytes: Long, val source: String, val tokens: Long, val time: String, val sample: Boolean)

/**
 * The data behind the Context and Files windows (the top-right icons). It is MOCK for now (the ticket says so): the numbers are a
 * bundled sample, labelled as one in the window; the window size is the real one of the picked model when the list reports it.
 * Pure: no Android, so the sums and the words are tested on the JVM.
 */
object WindowsData {
    const val DEFAULT_WINDOW = 200_000L

    /** The mock breakdown of a context window of [limit] tokens; the slices never exceed it and the last one is what is free. */
    fun context(limit: Long): List<CtxSegment> {
        val window = if (limit > 0) limit else DEFAULT_WINDOW
        val used = listOf(
            CtxSegment("system", "System prompt", 3_420, "persona, permission mode, standing sentences"),
            CtxSegment("tools", "Tools and MCP schemas", 11_800, "every enabled MCP server's tool list"),
            CtxSegment("skills", "Skills and plugins", 2_150, "enabled plugins, rules and hooks text"),
            CtxSegment("memory", "Memory", 1_640, "what the assistant remembers about the work"),
            CtxSegment("messages", "Messages", 18_930, "your turns and the answers so far"),
            CtxSegment("files", "Files and attachments", 6_200, "text of the files in this chat"),
        )
        // A window smaller than the sample scales every slice down together, so the bar always adds up to the window.
        val sum = used.sumOf { it.tokens }
        val scale = if (sum > window) window.toDouble() / sum else 1.0
        val scaled = used.map { it.copy(tokens = (it.tokens * scale).toLong()) }
        val free = (window - scaled.sumOf { it.tokens }).coerceAtLeast(0)
        return scaled + CtxSegment("free", "Free", free, "room left for the next turn and the answer")
    }

    /** "12,345" with thousands separators. */
    fun n(v: Long): String = String.format(Locale.ROOT, "%,d", v)

    /** "9.4%" of [window]; "-" when the window is unknown. */
    fun pct(tokens: Long, window: Long): String = if (window <= 0) "-" else String.format(Locale.ROOT, "%.1f%%", tokens * 100.0 / window)

    /** "2.4 MB" / "310 KB" / "86 B". */
    fun size(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }

    /** The files filters of the window, in order. */
    val FILTERS = listOf("all", "attached", "generated", "notebook")

    /** The mock files: what an SI chat typically holds. */
    fun files(): List<FileRow> = listOf(
        FileRow("quarterly-report.pdf", "pdf", 2_412_000, "attached", 9_800, "today 09:14", true),
        FileRow("fleet-costs.csv", "csv", 184_000, "attached", 4_100, "today 09:16", true),
        FileRow("architecture.png", "image", 904_000, "attached", 1_600, "today 09:21", true),
        FileRow("summary.md", "text", 6_400, "generated", 1_450, "today 09:30", true),
        FileRow("migration-plan.md", "text", 12_800, "generated", 3_100, "yesterday", true),
        FileRow("Research notes", "note", 3_900, "notebook", 900, "yesterday", true),
        FileRow("api-reference.html", "web", 58_000, "notebook", 12_200, "2 days ago", true),
    )

    /** The rows of [filter] (all = every row), keeping order. */
    fun filtered(rows: List<FileRow>, filter: String): List<FileRow> = if (filter == "all") rows else rows.filter { it.source == filter }

    /** One line over the table: "7 files · 4.3 MB · 33,150 tokens". */
    fun summary(rows: List<FileRow>): String = "${rows.size} files · ${size(rows.sumOf { it.bytes })} · ${n(rows.sumOf { it.tokens })} tokens"
}
