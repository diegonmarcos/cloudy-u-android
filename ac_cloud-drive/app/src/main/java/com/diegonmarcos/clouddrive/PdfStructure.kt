package com.diegonmarcos.clouddrive

/**
 * Text-layer or OCR text of a PDF page -> Markdown / HTML with headings, paragraphs and lists, by
 * line heuristics (a PDF carries no structure, only positioned text). Pure Kotlin, PdfStructureTest runs it.
 *  - heading: a short line (<= 70 chars) without closing punctuation, starting upper-case or a digit, standing
 *    where a block starts (page start, after a blank line, or after a line that ended a sentence);
 *    ALL CAPS is level 2, the first heading of the document level 1, others level 3;
 *  - list item: leading bullet glyph, dash or "1." / "1)";
 *  - paragraph: following lines joined with a space (a trailing hyphen before a lower-case word is joined whole).
 */
internal object PdfStructure {

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Para(val text: String) : Block()
        data class Items(val ordered: Boolean, val items: List<String>) : Block()
    }

    private val BULLET = Regex("^\\s*[•·▪‣◦*\\-–—]\\s+(.+)$")
    private val NUMBER = Regex("^\\s*\\d{1,3}[.)]\\s+(.+)$")
    private fun endsSentence(s: String) = s.trimEnd().lastOrNull()?.let { it in ".!?:;,)" } ?: true

    private fun looksLikeHeading(line: String, atBlockStart: Boolean, hasNext: Boolean): Boolean {
        val t = line.trim()
        if (!atBlockStart || !hasNext || t.length < 2 || t.length > 70 || t.split(' ').size > 9) return false
        if (endsSentence(t) || BULLET.matches(t) || NUMBER.matches(t)) return false
        val c = t.first()
        return c.isUpperCase() || c.isDigit()
    }

    fun blocks(pages: List<String>): List<List<Block>> {
        var first = true
        return pages.map { page ->
            val out = ArrayList<Block>()
            val para = StringBuilder()
            var items = ArrayList<String>(); var ordered = false
            fun flushPara() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
            fun flushItems() { if (items.isNotEmpty()) out += Block.Items(ordered, items); items = ArrayList() }
            val lines = page.replace("\r\n", "\n").split("\n")
            var blockStart = true
            lines.forEachIndexed { i, raw ->
                val line = raw.trimEnd()
                if (line.isBlank()) { flushPara(); flushItems(); blockStart = true; return@forEachIndexed }
                val b = BULLET.matchEntire(line); val n = NUMBER.matchEntire(line)
                when {
                    b != null || n != null -> {
                        flushPara()
                        val isOrdered = n != null && b == null
                        if (items.isNotEmpty() && ordered != isOrdered) flushItems()
                        ordered = isOrdered; items += (b ?: n)!!.groupValues[1].trim(); blockStart = false
                    }
                    looksLikeHeading(line, blockStart, i + 1 < lines.size && lines[i + 1].isNotBlank()) -> {
                        flushPara(); flushItems()
                        val t = line.trim()
                        val allCaps = t.any { it.isLetter() } && t == t.uppercase()
                        out += Block.Heading(if (first) 1 else if (allCaps) 2 else 3, t); first = false; blockStart = true
                    }
                    else -> {
                        if (items.isNotEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) { items[items.size - 1] = items.last() + " " + line.trim() }
                        else {
                            flushItems()
                            val t = line.trim()
                            if (para.endsWith("-") && t.firstOrNull()?.isLowerCase() == true) para.setLength(para.length - 1) else if (para.isNotEmpty()) para.append(' ')
                            para.append(t)
                        }
                        blockStart = endsSentence(line)
                    }
                }
            }
            flushPara(); flushItems()
            out
        }
    }

    fun toMarkdown(pages: List<String>): String = blocks(pages).mapIndexed { i, bs ->
        "<!-- page ${i + 1} -->\n\n" + bs.joinToString("\n\n") { b ->
            when (b) {
                is Block.Heading -> "#".repeat(b.level) + " " + b.text
                is Block.Para -> b.text
                is Block.Items -> b.items.mapIndexed { k, s -> if (b.ordered) "${k + 1}. $s" else "- $s" }.joinToString("\n")
            }
        }
    }.joinToString("\n\n") + "\n"

    fun toHtml(pages: List<String>, title: String): String {
        val e = PdfConvert::escapeHtml
        val body = blocks(pages).mapIndexed { i, bs ->
            "<section id=\"page-${i + 1}\">\n" + bs.joinToString("\n") { b ->
                when (b) {
                    is Block.Heading -> "<h${b.level}>${e(b.text)}</h${b.level}>"
                    is Block.Para -> "<p>${e(b.text)}</p>"
                    is Block.Items -> (if (b.ordered) "<ol>" else "<ul>") + b.items.joinToString("") { "<li>${e(it)}</li>" } + (if (b.ordered) "</ol>" else "</ul>")
                }
            } + "\n</section>"
        }.joinToString("\n")
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>${e(title.ifEmpty { "PDF export" })}</title>\n</head>\n<body>\n$body\n</body>\n</html>\n"
    }
}
