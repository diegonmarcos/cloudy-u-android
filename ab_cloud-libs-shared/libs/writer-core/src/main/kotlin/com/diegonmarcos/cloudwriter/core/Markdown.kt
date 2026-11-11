package com.diegonmarcos.cloudwriter.core

/** One `#` heading of a document, for the outline: its level, its text and where its line starts. */
data class Heading(val level: Int, val title: String, val offset: Int)

/** A formatting action's result: the new text and where the selection lands. */
data class Edit(val text: String, val selStart: Int, val selEnd: Int)

enum class SpanKind { HEADING1, HEADING2, HEADING3, BOLD, ITALIC, CODE, QUOTE, MARKER }

/** A styled range of the source text, for the editor's live rendering. Offsets index the text itself. */
data class Span(val start: Int, val end: Int, val kind: SpanKind)

/**
 * The editor's rich text is MARKDOWN, kept as plain text: the file on disk is readable anywhere,
 * export is the file, and every tool (Enhance, Translate, Listen) works on the same characters the
 * owner sees. These are the pure halves of the toolbar, the outline, find and the live styling.
 */
object Markdown {
    private val HEADING_LINE = Regex("^(#{1,6})[ \\t]+(.+?)(?:[ \\t]+#+)?[ \\t]*$")
    private val HEADING_MARK = Regex("^(#{1,6})[ \\t]+")
    private val LIST_MARK = Regex("^\\s*([-*+]|\\d+\\.)\\s")
    private val BOLD_RE = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*")
    private val ITALIC_RE = Regex("(?<![*\\w])[*_](?=[^\\s*_])([^*_\\n]+?)(?<=\\S)[*_](?![*\\w])")
    private val CODE_RE = Regex("`[^`\\n]+`")

    /** Every line with the offset it starts at. */
    private fun lines(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var at = 0
        for (line in text.split('\n')) {
            out += at to line
            at += line.length + 1
        }
        return out
    }

    /** The headings, in order, skipping anything inside a ``` fence (a `# comment` in code is not a chapter). */
    fun outline(text: String): List<Heading> {
        var fenced = false
        val out = ArrayList<Heading>()
        for ((at, line) in lines(text)) {
            if (line.trimStart().startsWith("```")) {
                fenced = !fenced
                continue
            }
            if (fenced) continue
            HEADING_LINE.find(line)?.let { out += Heading(it.groupValues[1].length, it.groupValues[2], at) }
        }
        return out
    }

    /** Start offsets of every non-overlapping, case-insensitive match of [query]; none for an empty query. */
    fun find(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = text.indexOf(query, from, ignoreCase = true)
            if (i < 0) return out
            out += i
            from = i + query.length
        }
    }

    /** Bold / italic / code: wrap the selection in [marker], or unwrap it when it is already wrapped. */
    fun wrap(text: String, start: Int, end: Int, marker: String): Edit {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        val m = marker.length
        if (a >= m && b + m <= text.length && text.regionMatches(a - m, marker, 0, m) && text.regionMatches(b, marker, 0, m)) {
            return Edit(text.removeRange(b, b + m).removeRange(a - m, a), a - m, b - m)
        }
        return Edit(text.substring(0, a) + marker + text.substring(a, b) + marker + text.substring(b), a + m, b + m)
    }

    /** Bullet / quote / numbered: prefix every line the selection touches, or strip it when all of them carry it. */
    fun prefixLines(text: String, start: Int, end: Int, prefix: String): Edit {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        val first = lineStart(text, a)
        val last = lineEnd(text, b)
        val block = text.substring(first, last).split('\n')
        val all = block.all { it.startsWith(prefix) }
        val changed = block.joinToString("\n") { if (all) it.removePrefix(prefix) else prefix + it }
        return Edit(text.substring(0, first) + changed + text.substring(last), first, first + changed.length)
    }

    /**
     * A link: the selection becomes the text of `[text](url)` with `url` selected for typing over; with nothing selected a
     * `[text](url)` skeleton is inserted with `text` selected.
     */
    fun link(text: String, start: Int, end: Int): Edit {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        if (a == b) {
            return Edit(text.substring(0, a) + "[text](url)" + text.substring(a), a + 1, a + 5)
        }
        val label = text.substring(a, b)
        val urlStart = a + 1 + label.length + 2
        return Edit(text.substring(0, a) + "[" + label + "](url)" + text.substring(b), urlStart, urlStart + 3)
    }

    /** Make the caret's line a heading of [level]; the same level again turns it back into a paragraph. */
    fun heading(text: String, start: Int, end: Int, level: Int): Edit {
        val first = lineStart(text, minOf(start, end).coerceIn(0, text.length))
        val last = lineEnd(text, first)
        val line = text.substring(first, last)
        val mark = HEADING_MARK.find(line)
        val bare = if (mark != null) line.substring(mark.range.last + 1) else line
        val current = mark?.groupValues?.get(1)?.length ?: 0
        val next = if (current == level) bare else "#".repeat(level) + " " + bare
        val caret = first + next.length
        return Edit(text.substring(0, first) + next + text.substring(last), caret, caret)
    }

    /** The styled ranges the editor paints: heading lines, quotes, list markers, **bold**, *italic*, `code`. */
    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        for ((at, line) in lines(text)) {
            val h = HEADING_MARK.find(line)
            if (h != null) {
                val kind = when (h.groupValues[1].length) {
                    1 -> SpanKind.HEADING1
                    2 -> SpanKind.HEADING2
                    else -> SpanKind.HEADING3
                }
                out += Span(at, at + line.length, kind)
                continue
            }
            if (line.startsWith("> ")) out += Span(at, at + line.length, SpanKind.QUOTE)
            LIST_MARK.find(line)?.groups?.get(1)?.let { g -> out += Span(at + g.range.first, at + g.range.last + 1, SpanKind.MARKER) }
        }
        BOLD_RE.findAll(text).forEach { out += Span(it.range.first, it.range.last + 1, SpanKind.BOLD) }
        ITALIC_RE.findAll(text).forEach { out += Span(it.range.first, it.range.last + 1, SpanKind.ITALIC) }
        CODE_RE.findAll(text).forEach { out += Span(it.range.first, it.range.last + 1, SpanKind.CODE) }
        return out.sortedWith(compareBy({ it.start }, { it.end }))
    }

    /** The document's name: its first non-blank line without heading marks or emphasis, at most 80 characters. */
    fun title(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return line.replace(HEADING_MARK, "").replace(Regex("[*_`]"), "").trim().take(80)
    }

    /** What the document list shows under the title: the text after the first line, flattened. */
    fun snippet(text: String, max: Int = 140): String =
        text.trim().substringAfter('\n', "").replace(Regex("[#*_`>]"), "").replace(Regex("\\s+"), " ").trim().take(max)

    fun words(text: String): Int = Regex("\\S+").findAll(text).count()

    private fun lineStart(text: String, at: Int): Int = if (at == 0) 0 else text.lastIndexOf('\n', at - 1) + 1

    private fun lineEnd(text: String, at: Int): Int = text.indexOf('\n', at).let { if (it < 0) text.length else it }
}
