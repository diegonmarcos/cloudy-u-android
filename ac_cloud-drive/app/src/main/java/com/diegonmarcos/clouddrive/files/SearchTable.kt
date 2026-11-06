package com.diegonmarcos.clouddrive.files

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** #1537 the five columns of the search-results table, in order. */
enum class SearchColumn { NAME, SIZE, FOLDER, MODIFIED, CREATED }

/** The table's sort: one column, ascending or descending. A tap on another column sorts it ascending, a tap on the sorted column flips it. */
data class SearchSort(val column: SearchColumn = SearchColumn.NAME, val descending: Boolean = false) {
    fun tap(c: SearchColumn): SearchSort = if (c == column) copy(descending = !descending) else SearchSort(c, descending = false)
}

/** One result row, flat: what the five columns show, plus the entry a tap opens. */
data class SearchRow(
    val key: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val folder: String,
    val modified: Long,
    val created: Long,
    val entry: FileOps.Entry,
)

/**
 * #1537 the search results as a sortable table (Name, Size, Folder, Modified, Created): the pure part. [rows] flattens listing entries,
 * [sort] orders them by a column (stable: a tie falls back to the name, then the path, so the order never flickers), and the formatters
 * give the cells their text. Used by every place Drive shows file-search results (Explorer's recursive hits and its in-place filter).
 */
object SearchTable {
    /** The file's creation time where the file system reports one, else its modified time. */
    fun createdTime(path: String, fallback: Long): Long = runCatching {
        Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java).creationTime().toMillis()
    }.getOrDefault(fallback).takeIf { it > 0 } ?: fallback

    fun rows(entries: List<FileOps.Entry>, createdOf: (String, Long) -> Long = { p, m -> createdTime(p, m) }): List<SearchRow> = entries.mapNotNull { e ->
        val local = e.location as? Location.Local ?: return@mapNotNull null
        SearchRow(e.key, local.path, e.name, e.isDirectory, if (e.isDirectory) 0L else e.size, local.parent()?.path.orEmpty(), e.modified, createdOf(local.path, e.modified), e)
    }

    fun sort(rows: List<SearchRow>, s: SearchSort): List<SearchRow> {
        val ci = String.CASE_INSENSITIVE_ORDER
        val by: Comparator<SearchRow> = when (s.column) {
            SearchColumn.NAME -> compareBy<SearchRow, String>(ci) { it.name }
            SearchColumn.SIZE -> compareBy<SearchRow> { it.size }
            SearchColumn.FOLDER -> compareBy<SearchRow, String>(ci) { it.folder }
            SearchColumn.MODIFIED -> compareBy<SearchRow> { it.modified }
            SearchColumn.CREATED -> compareBy<SearchRow> { it.created }
        }
        val ordered = by.then(compareBy<SearchRow, String>(ci) { it.name }).thenBy { it.path }
        return rows.sortedWith(if (s.descending) ordered.reversed() else ordered)
    }

    /** "2026-11-06 12:30", blank for an unknown time. */
    fun date(ms: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        if (ms <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(ms))

    /** The Size cell: bytes humanised, blank for a folder. */
    fun sizeText(r: SearchRow): String = if (r.isDirectory) "" else FileOps.humanBytes(r.size)
}
