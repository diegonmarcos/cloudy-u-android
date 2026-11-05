package com.diegonmarcos.cloudlib.wysiwyg

/**
 * Markdown to the model and back. Supported: headings (# to ######), paragraphs, bullet and numbered lists (indent = level),
 * quotes, fenced code, rules, and the inline **bold**, *italic*, `code` and [text](url). What is not understood stays as
 * plain text, so reading and writing a file never drops its words.
 */
object MarkdownCodec {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val quote = Regex("^\\s{0,3}>\\s?(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)\\d+[.)]\\s+(.*)$")

    private fun startsBlock(line: String): Boolean =
        line.isBlank() || line.trimStart().startsWith("```") || heading.matches(line) || rule.matches(line) || quote.matches(line) || bullet.matches(line) || numbered.matches(line)

    fun parse(source: String): Doc {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            if (line.trimStart().startsWith("```")) {
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { body += lines[i]; i++ }
                i++ // the closing fence
                blocks += Block(BlockKind.Code, body.joinToString("\n"))
                continue
            }
            val h = heading.matchEntire(line)
            if (h != null) {
                val (text, marks) = inline(h.groupValues[2])
                blocks += Block(BlockKind.Heading, text, marks, level = h.groupValues[1].length)
                i++
                continue
            }
            if (rule.matches(line)) { blocks += Block(BlockKind.Rule); i++; continue }
            if (quote.matches(line)) {
                val body = ArrayList<String>()
                while (i < lines.size && quote.matches(lines[i])) { body += quote.matchEntire(lines[i])!!.groupValues[1]; i++ }
                val (text, marks) = inline(body.joinToString("\n"))
                blocks += Block(BlockKind.Quote, text, marks)
                continue
            }
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            if (b != null || n != null) {
                val m = (b ?: n)!!
                val (text, marks) = inline(m.groupValues[2])
                blocks += Block(if (b != null) BlockKind.Bullet else BlockKind.Numbered, text, marks, level = (m.groupValues[1].length / 2).coerceAtMost(6))
                i++
                continue
            }
            val body = arrayListOf(line)
            i++
            while (i < lines.size && !startsBlock(lines[i])) { body += lines[i]; i++ }
            val (text, marks) = inline(body.joinToString("\n"))
            blocks += Block(BlockKind.Paragraph, text, marks)
        }
        return Doc(blocks)
    }

    fun render(doc: Doc): String {
        val out = StringBuilder()
        val counters = IntArray(8)
        var prev: BlockKind? = null
        for (b in doc.blocks) {
            val list = b.kind == BlockKind.Bullet || b.kind == BlockKind.Numbered
            if (prev != null) out.append(if (list && prev == b.kind) "\n" else "\n\n")
            if (!list) counters.fill(0)
            when (b.kind) {
                BlockKind.Heading -> out.append("#".repeat(b.level.coerceIn(1, 6))).append(' ').append(inlineOut(b))
                BlockKind.Paragraph -> out.append(inlineOut(b))
                BlockKind.Bullet -> out.append("  ".repeat(b.level)).append("- ").append(inlineOut(b))
                BlockKind.Numbered -> {
                    counters[b.level.coerceIn(0, 6)]++
                    for (d in b.level + 1 until counters.size) counters[d] = 0
                    out.append("  ".repeat(b.level)).append(counters[b.level.coerceIn(0, 6)]).append(". ").append(inlineOut(b))
                }
                BlockKind.Quote -> out.append(inlineOut(b).split("\n").joinToString("\n") { "> $it" })
                BlockKind.Code -> out.append("```\n").append(b.text).append("\n```")
                BlockKind.Rule -> out.append("---")
            }
            prev = b.kind
        }
        return if (out.isEmpty()) "" else out.append('\n').toString()
    }

    // ── inline ───────────────────────────────────────────────────────────────

    fun inline(src: String): Pair<String, List<Mark>> {
        val out = StringBuilder()
        val marks = ArrayList<Mark>()
        scan(src, out, marks)
        return out.toString() to Ops.normalize(marks)
    }

    private fun scan(src: String, out: StringBuilder, marks: MutableList<Mark>) {
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length && src[i + 1] in "\\`*_[]()#>-") { out.append(src[i + 1]); i += 2; continue }
            if (c == '`') {
                val j = src.indexOf('`', i + 1)
                if (j > i + 1) {
                    val s = out.length
                    out.append(src, i + 1, j)
                    marks += Mark(MarkType.Code, s, out.length)
                    i = j + 1
                    continue
                }
            }
            if (c == '[') {
                val j = src.indexOf("](", i + 1)
                val k = if (j > 0) src.indexOf(')', j + 2) else -1
                if (j > i && k > j) {
                    val s = out.length
                    scan(src.substring(i + 1, j), out, marks)
                    marks += Mark(MarkType.Link, s, out.length, src.substring(j + 2, k))
                    i = k + 1
                    continue
                }
            }
            if ((c == '*' || c == '_') && (c == '*' || i == 0 || !src[i - 1].isLetterOrDigit())) {
                var n = 0
                while (i + n < src.length && src[i + n] == c && n < 3) n++
                val close = closer(src, i + n, c, n)
                if (close > 0) {
                    val s = out.length
                    scan(src.substring(i + n, close), out, marks)
                    if (n >= 2) marks += Mark(MarkType.Bold, s, out.length)
                    if (n != 2) marks += Mark(MarkType.Italic, s, out.length)
                    i = close + n
                    continue
                }
            }
            out.append(c)
            i++
        }
    }

    /** Where the closing run of [n] [c]s starts, or -1: not after a space, and for a single delimiter not part of a longer run. */
    private fun closer(src: String, from: Int, c: Char, n: Int): Int {
        var j = from
        while (j < src.length) {
            val at = src.indexOf(c, j)
            if (at < 0) return -1
            var run = 0
            while (at + run < src.length && src[at + run] == c) run++
            val prevOk = at > from && src[at - 1] != ' ' && src[at - 1] != '\n'
            if (prevOk && run >= n) {
                if (n == 1 && run > 1) { j = at + run; continue }
                return at
            }
            j = at + run
        }
        return -1
    }

    private fun inlineOut(b: Block): String {
        val sb = StringBuilder()
        for (seg in Segments.of(b)) {
            // A delimiter cannot sit against a space, so the spaces at the ends of a run stay outside it.
            val core = seg.text.trim(' ', '\n')
            if (core.isEmpty() || seg.marks.isEmpty()) { sb.append(seg.text); continue }
            val lead = seg.text.substring(0, seg.text.indexOf(core))
            val trail = seg.text.substring(lead.length + core.length)
            var s = core
            if (seg.marks.any { it.type == MarkType.Code }) s = "`$s`"
            val bold = seg.marks.any { it.type == MarkType.Bold }
            val italic = seg.marks.any { it.type == MarkType.Italic }
            if (bold && italic) s = "***$s***" else if (bold) s = "**$s**" else if (italic) s = "*$s*"
            seg.marks.firstOrNull { it.type == MarkType.Link }?.let { s = "[$s](${it.url.orEmpty()})" }
            sb.append(lead).append(s).append(trail)
        }
        return sb.toString()
    }
}
