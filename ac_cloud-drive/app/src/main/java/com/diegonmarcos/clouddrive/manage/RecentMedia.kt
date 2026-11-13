package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.Declarations

/** One recent file of a DATA strip. [uri] is the MediaStore content uri. */
data class RecentFile(val id: Long, val name: String, val size: Long, val mime: String, val uri: String) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** What a strip shows: the files, or why there are none (the grant is missing, the read failed). */
sealed class StripState {
    data class Files(val files: List<RecentFile>) : StripState()
    data class Unavailable(val reason: String) : StripState()
}

/** The MediaStore query of a DATA strip, built from its declaration (ui.manage.overview.data). Pure, so a JVM test pins it. */
object RecentMedia {
    /** `relative_path LIKE ? OR ...` (+ `mime_type LIKE 'image/%'`) and its arguments; a prefix is given with or without the trailing slash. */
    fun selection(d: Declarations.DataStripDecl): Pair<String, Array<String>>? {
        val paths = d.paths.map { it.trim().trim('/') }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) return null
        val like = paths.joinToString(" OR ") { "relative_path LIKE ?" }
        val sel = "($like)" + if (d.imagesOnly) " AND mime_type LIKE 'image/%'" else ""
        // Entries are files (media_type != 0 or mime set), never folders.
        return (sel + " AND mime_type IS NOT NULL") to paths.map { "$it/%" }.toTypedArray()
    }
}
