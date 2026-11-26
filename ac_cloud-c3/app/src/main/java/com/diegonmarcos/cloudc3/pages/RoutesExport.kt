package com.diegonmarcos.cloudc3.pages

/**
 * Containers > Table export, pure Kotlin so the JVM suite checks it. Everything works on the rows the screen shows
 * (already filtered and sorted by [RoutesTableModel.view]) and the screen's columns, header first.
 */
object RoutesExport {

    /** The shown columns' header texts; keep in step with the screen's headers. */
    val HEADERS: Map<RoutesTableModel.Column, String> = mapOf(
        RoutesTableModel.Column.STATUS to "Status",
        RoutesTableModel.Column.PROJECT to "Project", RoutesTableModel.Column.GROUP to "Cat1", RoutesTableModel.Column.CATEGORY to "Cat2",
        RoutesTableModel.Column.TYPE to "Type", RoutesTableModel.Column.CONTAINER to "Container",
        RoutesTableModel.Column.PUBLIC_URL to "Public URL", RoutesTableModel.Column.AUTH_TYPE to "Auth Type",
        RoutesTableModel.Column.PRIVATE_URL to "Private URL", RoutesTableModel.Column.VM_NAME to "VM Name",
        RoutesTableModel.Column.VM_ADDR to "VM IP:PORT",
    )

    /** Header row, then one row per shown row; the Status cell is the light's label. */
    fun grid(shown: List<RoutesTableModel.Row>, light: (RoutesTableModel.Row) -> ProbeModel.Light): List<List<String>> =
        listOf(RoutesTableModel.Column.entries.map { HEADERS.getValue(it) }) +
            shown.map { r -> RoutesTableModel.Column.entries.map { c -> if (c == RoutesTableModel.Column.STATUS) light(r).label else r.text(c) } }

    /** RFC 4180: quote a field holding a comma, quote, CR or LF (or edge space); double the quotes; CRLF rows. */
    fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s.startsWith(" ") || s.endsWith(" ")) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun csv(grid: List<List<String>>): String = grid.joinToString("") { r -> r.joinToString(",") { csvField(it) } + "\r\n" }

    /** Tabs and line breaks inside a cell would break the paste into sheets; they become single spaces. */
    fun tsvField(s: String): String = s.replace(Regex("[\\t\\r\\n]+"), " ")

    fun tsv(grid: List<List<String>>): String = grid.joinToString("\n") { r -> r.joinToString("\t") { tsvField(it) } }

    /** GitHub-style table: pipes and line breaks escaped so a cell stays in its column. */
    fun mdField(s: String): String = s.replace("\\", "\\\\").replace("|", "\\|").replace(Regex("\\r?\\n|\\r"), " ")

    fun markdown(grid: List<List<String>>): String {
        if (grid.isEmpty()) return ""
        fun line(r: List<String>) = "| " + r.joinToString(" | ") { mdField(it) } + " |"
        return (listOf(line(grid.first()), "| " + grid.first().joinToString(" | ") { "---" } + " |") + grid.drop(1).map(::line)).joinToString("\n")
    }
}
