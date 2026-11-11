package com.diegonmarcos.cloudwriter.core

/** #1457 how the document list is ordered; the ids are what the filter chips carry. */
enum class DocSort(val id: String) {
    RECENT("recent"), LONGEST("longest"), TITLE("title");

    companion object {
        fun of(id: String?): DocSort = values().firstOrNull { it.id == id } ?: RECENT
    }
}

/** Where the editor's autosave is: nothing pending, a write queued, or the last write failed. */
enum class SaveState { SAVED, SAVING, FAILED }

/** The pure rules of the document list and the editor's status line (the screens only draw them). */
object DocList {
    const val WORDS_PER_MINUTE = 200

    fun sort(docs: List<DocMeta>, by: DocSort): List<DocMeta> = when (by) {
        DocSort.RECENT -> docs.sortedWith(compareByDescending<DocMeta> { it.modified }.thenByDescending { it.id })
        DocSort.LONGEST -> docs.sortedWith(compareByDescending<DocMeta> { it.words }.thenByDescending { it.modified })
        DocSort.TITLE -> docs.sortedWith(compareBy<DocMeta, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenByDescending { it.modified })
    }

    fun totalWords(docs: List<DocMeta>): Int = docs.sumOf { it.words }

    /** Whole minutes to read [words]; 0 for no words, never less than 1 otherwise. */
    fun readingMinutes(words: Int): Int = if (words <= 0) 0 else maxOf(1, (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE)

    /** The letter on a row's tile: the title's first letter or digit, upper case; a bullet for an empty or symbol-only title. */
    fun initial(title: String): String = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"

    /** Characters without line breaks, as the status line counts them. */
    fun chars(text: String): Int = text.count { it != '\n' && it != '\r' }

    /** #1532 the tags of the listed documents, most used first (then A-Z), for the list's filter chips. */
    fun allTags(docs: List<DocMeta>): List<String> =
        docs.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** The documents carrying [tag]; all of them for no tag. */
    fun withTag(docs: List<DocMeta>, tag: String?): List<DocMeta> = if (tag == null) docs else docs.filter { tag in it.tags }
}
