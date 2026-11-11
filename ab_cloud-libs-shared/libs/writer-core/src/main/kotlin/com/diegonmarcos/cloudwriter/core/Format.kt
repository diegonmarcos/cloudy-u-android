package com.diegonmarcos.cloudwriter.core

/**
 * #1532 the editor's formatting toolbar as data: each chip is one [FormatAction], in the order and groups the toolbar
 * draws them, and [apply] is the whole behaviour behind the tap, over the same Markdown tools the tests hold.
 */
enum class FormatAction(val id: String) {
    H1("h1"), H2("h2"), BOLD("bold"), ITALIC("italic"), CODE("code"), LINK("link"),
    BULLETS("bullets"), NUMBERED("numbered"), CHECKLIST("checklist"), QUOTE("quote");

    fun apply(text: String, start: Int, end: Int): Edit = when (this) {
        H1 -> Markdown.heading(text, start, end, 1)
        H2 -> Markdown.heading(text, start, end, 2)
        BOLD -> Markdown.wrap(text, start, end, "**")
        ITALIC -> Markdown.wrap(text, start, end, "*")
        CODE -> Markdown.wrap(text, start, end, "`")
        LINK -> Markdown.link(text, start, end)
        BULLETS -> Markdown.prefixLines(text, start, end, "- ")
        NUMBERED -> Markdown.prefixLines(text, start, end, "1. ")
        CHECKLIST -> Markdown.prefixLines(text, start, end, "- [ ] ")
        QUOTE -> Markdown.prefixLines(text, start, end, "> ")
    }

    companion object {
        /** The toolbar's groups, a divider between each. */
        val GROUPS: List<List<FormatAction>> = listOf(
            listOf(H1, H2),
            listOf(BOLD, ITALIC, CODE, LINK),
            listOf(BULLETS, NUMBERED, CHECKLIST, QUOTE),
        )
    }
}

/** Focus mode: the current paragraph stays at full ink and everything else is dimmed. */
object Focus {
    private fun lineStart(text: String, at: Int): Int = if (at == 0) 0 else text.lastIndexOf('\n', at - 1) + 1

    private fun lineEnd(text: String, at: Int): Int = text.indexOf('\n', at).let { if (it < 0) text.length else it }

    private fun blank(text: String, from: Int, to: Int): Boolean = text.substring(from, to).isBlank()

    /** The paragraph around [caret]: consecutive non-blank lines. A caret on a blank line owns no paragraph (an empty range). */
    fun paragraph(text: String, caret: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = caret.coerceIn(0, text.length)
        var start = lineStart(text, at)
        var end = lineEnd(text, at)
        if (blank(text, start, end)) return start until start
        while (start > 0) {
            val prev = lineStart(text, start - 1)
            if (blank(text, prev, start - 1)) break
            start = prev
        }
        while (end < text.length) {
            val next = end + 1
            if (blank(text, next, lineEnd(text, next))) break
            end = lineEnd(text, next)
        }
        return start until end
    }

    /** The ranges to dim: before and after the current paragraph; the whole text when the caret owns none. */
    fun dimmed(text: String, caret: Int): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val p = paragraph(text, caret)
        if (p.isEmpty()) return listOf(0 until text.length)
        return listOfNotNull(
            if (p.first > 0) 0 until p.first else null,
            if (p.last + 1 < text.length) p.last + 1 until text.length else null,
        )
    }
}

/** Hashtags in the text (`#idea`), the document's tags: Bear-style, kept in the file itself so there is nothing to migrate. */
object Tags {
    private val TAG = Regex("(?<![\\w#&/])#(\\p{L}[\\p{L}\\p{N}_-]{0,29})")

    fun of(text: String, max: Int = 4): List<String> {
        var fenced = false
        val out = LinkedHashSet<String>()
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```")) {
                fenced = !fenced
                continue
            }
            if (fenced) continue
            TAG.findAll(line).forEach { out += it.groupValues[1].lowercase() }
        }
        return out.take(max)
    }
}
