package com.diegonmarcos.cloudwriter.core

import java.io.File

/** One document as the list shows it. */
data class DocMeta(val id: String, val title: String, val snippet: String, val modified: Long, val words: Int, val tags: List<String> = emptyList())

/**
 * Cloud Writer's documents: one Markdown file per document in [dir] (the app's private files),
 * named by an opaque id. Plain files on purpose — no database to migrate, export is a copy, and a
 * crash mid-save leaves the previous version because every write goes through a temp file and a
 * rename.
 */
class DocStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private var seq = 0

    init {
        dir.mkdirs()
    }

    /** Newest first. */
    fun list(): List<DocMeta> =
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(EXT) }
            .map { meta(it) }
            .sortedWith(compareByDescending<DocMeta> { it.modified }.thenByDescending { it.id })

    fun read(id: String): String? = file(id).takeIf { it.isFile }?.readText()

    fun create(text: String = ""): String {
        var id: String
        do {
            id = clock().toString(36) + (seq++).toString(36)
        } while (file(id).exists())
        write(id, text)
        return id
    }

    fun write(id: String, text: String) {
        val target = file(id)
        val tmp = File(dir, id + EXT + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
        target.setLastModified(clock())
    }

    /** Listen's sink when no editor is open: the segment goes on the end, spaced as dictation spaces it. */
    fun append(id: String, segment: String): Boolean {
        val current = read(id) ?: return false
        write(id, current + Dictation.separator(current, segment) + segment)
        return true
    }

    fun delete(id: String): Boolean = file(id).delete()

    /** Documents whose text contains [query], ignoring case; every document for a blank query. */
    fun search(query: String): List<DocMeta> {
        val q = query.trim()
        if (q.isEmpty()) return list()
        return list().filter { read(it.id)?.contains(q, ignoreCase = true) == true }
    }

    private fun meta(f: File): DocMeta {
        val text = f.readText()
        return DocMeta(f.name.removeSuffix(EXT), Markdown.title(text), Markdown.snippet(text), f.lastModified(), Markdown.words(text), Tags.of(text))
    }

    /** Ids are the store's own; anything else (a path, a dot) is refused before it can name a file outside [dir]. */
    private fun file(id: String): File {
        require(ID.matches(id)) { "not a document id: $id" }
        return File(dir, id + EXT)
    }

    companion object {
        const val EXT = ".md"
        private val ID = Regex("[a-z0-9]{1,32}")
    }
}
