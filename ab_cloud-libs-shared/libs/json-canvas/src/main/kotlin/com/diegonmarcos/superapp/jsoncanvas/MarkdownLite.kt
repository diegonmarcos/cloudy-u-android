package com.diegonmarcos.superapp.jsoncanvas

/** One run of inline text; [link] non-null makes it a link. */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null)

sealed class MdBlock {
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock()
    data class Para(val spans: List<Span>) : MdBlock()
    data class Item(val ordered: Boolean, val number: Int, val depth: Int, val spans: List<Span>) : MdBlock()
    data class Quote(val spans: List<Span>) : MdBlock()
    data class Code(val text: String) : MdBlock()
    object Rule : MdBlock()
}

/** Markdown-light: headings, bold, italic, `code`, [links](url) and bare URLs, bullet/numbered lists, quotes, fenced code, rules. Pure, total (never throws). */
object MarkdownLite {
    fun parse(text: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { out.add(MdBlock.Para(inline(para.toString().trim()))); para.clear() } }
        while (i < lines.size) {
            val raw = lines[i]; val t = raw.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush(); val fence = t.take(3); val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
                    out.add(MdBlock.Code(code.toString().trimEnd('\n')))
                }
                t.isEmpty() -> flush()
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out.add(MdBlock.Rule) }
                Regex("^#{1,6}\\s+.*").matches(t) -> { flush(); val n = t.takeWhile { it == '#' }.length; out.add(MdBlock.Heading(n, inline(t.drop(n).trim().trimEnd('#').trim()))) }
                Regex("^\\s*[-*+]\\s+.*").matches(raw) -> { flush(); out.add(MdBlock.Item(false, 0, (raw.length - raw.trimStart().length) / 2, inline(t.drop(1).trim()))) }
                Regex("^\\s*\\d+[.)]\\s+.*").matches(raw) -> { flush(); val n = t.takeWhile { it.isDigit() }; out.add(MdBlock.Item(true, n.toIntOrNull() ?: 1, (raw.length - raw.trimStart().length) / 2, inline(t.drop(n.length + 1).trim()))) }
                t.startsWith(">") -> { flush(); out.add(MdBlock.Quote(inline(t.drop(1).trim()))) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    private val token = Regex("""\[([^\]]+)\]\(([^)\s]+)\)|\*\*([^*]+)\*\*|__([^_]+)__|`([^`]+)`|\*([^*\s][^*]*)\*|(https?://[^\s)<>]+)""")

    fun inline(s: String): List<Span> {
        val out = ArrayList<Span>(); var pos = 0
        for (m in token.findAll(s)) {
            if (m.range.first > pos) out.add(Span(s.substring(pos, m.range.first)))
            val g = m.groupValues
            out.add(when {
                g[1].isNotEmpty() -> Span(g[1], link = g[2])
                g[3].isNotEmpty() -> Span(g[3], bold = true)
                g[4].isNotEmpty() -> Span(g[4], bold = true)
                g[5].isNotEmpty() -> Span(g[5], code = true)
                g[6].isNotEmpty() -> Span(g[6], italic = true)
                else -> Span(g[7], link = g[7])
            })
            pos = m.range.last + 1
        }
        if (pos < s.length) out.add(Span(s.substring(pos)))
        return out
    }
}
