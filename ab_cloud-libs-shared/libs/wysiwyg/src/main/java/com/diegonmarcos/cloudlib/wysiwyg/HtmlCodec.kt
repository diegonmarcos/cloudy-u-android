package com.diegonmarcos.cloudlib.wysiwyg

/**
 * HTML to the model and back, without a DOM library: headings, paragraphs, lists (nested), quotes, pre, rules, line breaks and
 * the inline strong/em/code/a. Unknown tags are skipped and their text kept; script, style and head content is dropped from
 * the model, but [renderDocument] puts the edited body back into the original file, so the head, the scripts and the body
 * attributes survive an edit.
 */
object HtmlCodec {
    private val bodyOpen = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val bodyClose = Regex("</body\\s*>", RegexOption.IGNORE_CASE)

    // ── parse ────────────────────────────────────────────────────────────────

    private class Open(val type: MarkType, val start: Int, val url: String?)

    fun parse(source: String): Doc {
        val blocks = ArrayList<Block>()
        var kind: BlockKind? = null
        var level = 0
        val text = StringBuilder()
        val marks = ArrayList<Mark>()
        val open = ArrayList<Open>()
        val lists = ArrayList<String>()
        var quote = 0
        var pre = false
        var skip: String? = null

        fun flush() {
            val k = kind
            if (k != null) {
                val raw = text.toString()
                val t = if (k == BlockKind.Code) raw.trim('\n') else raw.trim()
                val lead = if (k == BlockKind.Code) 0 else raw.length - raw.trimStart().length
                if (t.isNotEmpty() || k == BlockKind.Rule) {
                    val ms = marks.map { it.copy(start = it.start - lead, end = it.end - lead) }
                        .map { it.copy(start = it.start.coerceIn(0, t.length), end = it.end.coerceIn(0, t.length)) }
                    blocks += Block(k, t, if (k == BlockKind.Code) emptyList() else Ops.normalize(ms), level)
                }
            }
            kind = null; level = 0; text.setLength(0); marks.clear()
        }
        fun begin(k: BlockKind, l: Int = 0) { flush(); kind = k; level = l }
        fun ensure() { if (kind == null) { kind = if (quote > 0) BlockKind.Quote else BlockKind.Paragraph } }
        fun append(s: String) {
            if (s.isEmpty()) return
            ensure()
            if (pre) { text.append(s); return }
            for (ch in s) {
                if (ch.isWhitespace()) { if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') text.append(' ') }
                else text.append(ch)
            }
        }
        fun closeMark(type: MarkType) {
            val idx = open.indexOfLast { it.type == type }
            if (idx < 0) return
            val o = open.removeAt(idx)
            if (text.length > o.start) marks += Mark(o.type, o.start, text.length, o.url)
        }

        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            if (c == '<' && i + 1 < n && (source[i + 1].isLetter() || source[i + 1] == '/' || source[i + 1] == '!')) {
                if (source.startsWith("<!--", i)) { val e = source.indexOf("-->", i + 4); i = if (e < 0) n else e + 3; continue }
                val e = source.indexOf('>', i)
                if (e < 0) { append(source.substring(i)); break }
                val inner = source.substring(i + 1, e)
                i = e + 1
                if (inner.startsWith("!") || inner.startsWith("?")) continue
                val closing = inner.startsWith("/")
                val name = inner.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
                val s = skip
                if (s != null) { if (closing && name == s) skip = null; continue }
                if (!closing && (name == "script" || name == "style" || name == "head" || name == "title")) { if (!inner.trimEnd().endsWith("/")) skip = name; continue }
                if (!closing) {
                    when (name) {
                        "h1", "h2", "h3", "h4", "h5", "h6" -> begin(BlockKind.Heading, name[1] - '0')
                        "p", "div" -> begin(if (quote > 0) BlockKind.Quote else BlockKind.Paragraph)
                        "li" -> begin(if (lists.lastOrNull() == "ol") BlockKind.Numbered else BlockKind.Bullet, (lists.size - 1).coerceAtLeast(0))
                        "ul", "ol" -> { flush(); lists += name }
                        "blockquote" -> { flush(); quote++ }
                        "pre" -> { begin(BlockKind.Code); pre = true }
                        "hr" -> { begin(BlockKind.Rule); flush() }
                        "br" -> { ensure(); text.append('\n') }
                        "b", "strong" -> { ensure(); open += Open(MarkType.Bold, text.length, null) }
                        "i", "em" -> { ensure(); open += Open(MarkType.Italic, text.length, null) }
                        "code" -> if (!pre) { ensure(); open += Open(MarkType.Code, text.length, null) }
                        "a" -> { ensure(); open += Open(MarkType.Link, text.length, attr(inner, "href")) }
                    }
                } else {
                    when (name) {
                        "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "li" -> flush()
                        "ul", "ol" -> { flush(); if (lists.isNotEmpty()) lists.removeAt(lists.size - 1) }
                        "blockquote" -> { flush(); if (quote > 0) quote-- }
                        "pre" -> { flush(); pre = false }
                        "b", "strong" -> closeMark(MarkType.Bold)
                        "i", "em" -> closeMark(MarkType.Italic)
                        "code" -> if (!pre) closeMark(MarkType.Code)
                        "a" -> closeMark(MarkType.Link)
                    }
                }
                continue
            }
            val next = source.indexOf('<', i + 1).let { if (it < 0) n else it }
            if (skip == null) append(decode(source.substring(i, next)))
            i = next
        }
        flush()
        return Doc(blocks)
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return decode(m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
    }

    fun decode(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> when (e) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "; else -> m.value }
            }
        }
    }

    // ── render ───────────────────────────────────────────────────────────────

    fun escape(s: String, attribute: Boolean = false): String {
        val e = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return if (attribute) e.replace("\"", "&quot;") else e
    }

    private fun inlineOut(b: Block): String {
        val sb = StringBuilder()
        for (seg in Segments.of(b)) {
            var s = escape(seg.text).replace("\n", "<br>")
            if (seg.marks.any { it.type == MarkType.Code }) s = "<code>$s</code>"
            if (seg.marks.any { it.type == MarkType.Italic }) s = "<em>$s</em>"
            if (seg.marks.any { it.type == MarkType.Bold }) s = "<strong>$s</strong>"
            seg.marks.firstOrNull { it.type == MarkType.Link }?.let { s = "<a href=\"${escape(it.url.orEmpty(), true)}\">$s</a>" }
            sb.append(s)
        }
        return sb.toString()
    }

    /** The body fragment of [doc]. */
    fun render(doc: Doc): String {
        val out = StringBuilder()
        val stack = ArrayList<String>()
        var liOpen = false
        fun closeLists() {
            if (liOpen) { out.append("</li>\n"); liOpen = false }
            while (stack.isNotEmpty()) {
                out.append("</").append(stack.removeAt(stack.size - 1)).append(">\n")
                if (stack.isNotEmpty()) out.append("</li>\n")
            }
        }
        for (b in doc.blocks) {
            if (b.kind == BlockKind.Bullet || b.kind == BlockKind.Numbered) {
                val tag = if (b.kind == BlockKind.Bullet) "ul" else "ol"
                val l = b.level.coerceIn(0, stack.size)
                if (stack.isEmpty()) { out.append('<').append(tag).append(">\n"); stack += tag }
                else if (l > stack.size - 1) { out.append('\n').append('<').append(tag).append(">\n"); stack += tag }
                else {
                    if (liOpen) { out.append("</li>\n"); liOpen = false }
                    while (stack.size - 1 > l) { out.append("</").append(stack.removeAt(stack.size - 1)).append(">\n</li>\n") }
                    if (stack.last() != tag) { out.append("</").append(stack.removeAt(stack.size - 1)).append(">\n<").append(tag).append(">\n"); stack += tag }
                }
                out.append("<li>").append(inlineOut(b))
                liOpen = true
                continue
            }
            closeLists()
            when (b.kind) {
                BlockKind.Heading -> out.append("<h${b.level.coerceIn(1, 6)}>").append(inlineOut(b)).append("</h${b.level.coerceIn(1, 6)}>\n")
                BlockKind.Paragraph -> out.append("<p>").append(inlineOut(b)).append("</p>\n")
                BlockKind.Quote -> out.append("<blockquote>").append(inlineOut(b)).append("</blockquote>\n")
                BlockKind.Code -> out.append("<pre>").append(escape(b.text)).append("</pre>\n")
                BlockKind.Rule -> out.append("<hr>\n")
                else -> Unit
            }
        }
        closeLists()
        return out.toString()
    }

    /** A whole file: [original]'s head and attributes with the body replaced by [doc]; with no original, a minimal page. */
    fun renderDocument(doc: Doc, original: String?): String {
        val body = render(doc)
        if (original != null) {
            val open = bodyOpen.find(original)
            if (open != null) {
                val close = bodyClose.findAll(original).lastOrNull { it.range.first >= open.range.last }
                val end = close?.range?.first ?: original.length
                return original.substring(0, open.range.last + 1) + "\n" + body + original.substring(end)
            }
        }
        return "<!doctype html>\n<html>\n<head><meta charset=\"utf-8\"></head>\n<body>\n$body</body>\n</html>\n"
    }
}
