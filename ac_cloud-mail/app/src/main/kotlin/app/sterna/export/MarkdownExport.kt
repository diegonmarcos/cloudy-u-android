package app.sterna.export

/**
 * #1870 "Export... > Markdown (.md)": the message as a formatted Markdown document. A YAML front matter block (subject,
 * from, to, cc, date, message id), the subject as the title, the body converted from the reader's sanitised HTML to
 * Markdown (headings, paragraphs, emphasis, code, links, images, lists, quoted replies, tables) or, for a plain-text
 * message, its text as it is, then the attachments by name. Pure Kotlin, no Android type: [htmlToMarkdown] is
 * JVM-tested on fixtures.
 */
fun buildMarkdown(msg: ExportMessage, labels: ExportLabels): String {
    val out = StringBuilder()
    out.append("---\n")
    fun front(key: String, value: String?) {
        if (!value.isNullOrBlank()) out.append(key).append(": ").append(yamlQuote(value)).append('\n')
    }
    front("subject", msg.subject)
    front("from", msg.from)
    front("to", msg.to)
    front("cc", msg.cc)
    front("date", msg.dateIso)
    front("message_id", msg.messageId)
    out.append("---\n\n")
    out.append("# ").append(escapeInline(msg.subject.ifBlank { "(no subject)" })).append("\n\n")
    fun line(label: String, value: String?) {
        if (!value.isNullOrBlank()) out.append("**").append(label.trimEnd(':')).append(":** ").append(escapeInline(value)).append("  \n")
    }
    line(labels.from, msg.from)
    line(labels.to, msg.to)
    line(labels.cc, msg.cc)
    line(labels.date, msg.dateIso)
    out.append("\n---\n\n")
    val body = msg.bodyText?.let { plainBody(it) } ?: htmlToMarkdown(msg.bodyHtml)
    out.append(body.trim()).append('\n')
    val files = msg.parts.filter { !it.inline }
    if (files.isNotEmpty()) {
        out.append("\n## ").append(labels.attachments).append("\n\n")
        for (part in files) {
            val name = part.name?.takeIf { it.isNotBlank() } ?: "attachment"
            out.append("- `").append(name.replace("`", "'")).append("` (").append(part.type)
            if (part.size > 0) out.append(", ").append(humanSize(part.size))
            out.append(")\n")
        }
    }
    return out.toString()
}

/** A plain-text body is kept as it is; only line endings are normalised. */
internal fun plainBody(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

private fun yamlQuote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""

private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.0f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}

// ---- HTML to Markdown ---------------------------------------------------------------------------------------------

private class Node(val tag: String?, val attrs: Map<String, String> = emptyMap(), val text: String = "") {
    val children = mutableListOf<Node>()
    val isText get() = tag == null
}

private val VOID = setOf("br", "hr", "img", "meta", "link", "input", "col", "wbr", "area", "base", "source")
private val SKIP = setOf("script", "style", "head", "title", "template", "noscript", "svg")
private val BLOCK = setOf(
    "p", "div", "section", "article", "header", "footer", "main", "nav", "aside", "center", "address", "figure",
    "figcaption", "form", "fieldset", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "pre", "blockquote", "table",
    "hr", "dl", "dt", "dd", "li", "tr", "td", "th", "thead", "tbody", "tfoot", "body", "html",
)

/** Elements a new sibling implicitly closes (HTML's optional end tags). */
private val IMPLIED_CLOSE = mapOf(
    "li" to setOf("li"),
    "p" to setOf("p"),
    "tr" to setOf("tr", "td", "th"),
    "td" to setOf("td", "th"),
    "th" to setOf("td", "th"),
    "dt" to setOf("dt", "dd"),
    "dd" to setOf("dt", "dd"),
)

private val TOKEN = Regex("""<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?]]>|<![^>]*>|<\?[^>]*>|</\s*([A-Za-z][A-Za-z0-9:-]*)\s*>|<([A-Za-z][A-Za-z0-9:-]*)((?:\s+[^\s/>=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*(/?)>""")
private val ATTR = Regex("""([^\s/>=]+)(?:\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+)))?""")

private fun parse(html: String): Node {
    val root = Node("#root")
    val stack = ArrayDeque<Node>().apply { addLast(root) }
    var at = 0
    fun text(s: String) { if (s.isNotEmpty()) stack.last().children += Node(null, text = decodeEntities(s)) }
    var skipUntil: String? = null
    for (m in TOKEN.findAll(html)) {
        if (skipUntil != null) {
            if (m.groupValues[1].lowercase() == skipUntil) { skipUntil = null; at = m.range.last + 1 }
            continue
        }
        text(html.substring(at, m.range.first))
        at = m.range.last + 1
        val close = m.groupValues[1].lowercase()
        val open = m.groupValues[2].lowercase()
        when {
            close.isNotEmpty() -> {
                val idx = stack.indexOfLast { it.tag == close }
                if (idx > 0) while (stack.size > idx) stack.removeLast()
            }
            open.isNotEmpty() -> {
                if (open in SKIP) { if (m.groupValues[4] != "/") skipUntil = open; continue }
                IMPLIED_CLOSE[open]?.let { closes ->
                    // Close an open sibling, but never past the list or table that holds it.
                    val barrier = setOf("ul", "ol", "table", "tbody", "thead", "tfoot", "dl")
                    val idx = stack.indexOfLast { it.tag in closes || it.tag in barrier }
                    if (idx > 0 && stack[idx].tag in closes) while (stack.size > idx) stack.removeLast()
                }
                val attrs = HashMap<String, String>()
                for (a in ATTR.findAll(m.groupValues[3])) {
                    val v = a.groups[3]?.value ?: a.groups[4]?.value ?: a.groups[5]?.value ?: ""
                    attrs[a.groupValues[1].lowercase()] = decodeEntities(v)
                }
                val node = Node(open, attrs)
                stack.last().children += node
                if (open !in VOID && m.groupValues[4] != "/") stack.addLast(node)
            }
        }
    }
    if (skipUntil == null) text(html.substring(at))
    return root
}

private val NAMED = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0", "ndash" to "–",
    "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
    "rdquo" to "”", "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€",
    "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•", "zwnj" to "", "zwj" to "",
)
private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});""")

internal fun decodeEntities(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
    val e = m.groupValues[1]
    when {
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
        else -> NAMED[e] ?: m.value
    }
}

private fun cp(code: Int): String = if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else "�"

/** Markdown's own syntax characters in running text, escaped so the text reads as text. */
internal fun escapeInline(s: String): String = s.replace(Regex("""([\\`*_\[\]])"""), """\\$1""")

private const val HARD_BREAK = "\\\n"

/** The reader's sanitised HTML fragment as Markdown. */
fun htmlToMarkdown(html: String): String {
    val md = blocks(parse(html).children, inPre = false).joinToString("\n\n")
    return md.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun isBlock(n: Node) = !n.isText && n.tag in BLOCK

private fun blocks(nodes: List<Node>, inPre: Boolean): List<String> {
    val out = mutableListOf<String>()
    val run = StringBuilder()
    fun flush() {
        // A lambda, not a replacement string: in one, the break's backslash would be read as an escape and dropped.
        var s = run.toString().replace(Regex(" *\\\\\n *")) { HARD_BREAK }.trim()
        // A break at either end of a paragraph is no break at all.
        while (s.startsWith(HARD_BREAK)) s = s.removePrefix(HARD_BREAK).trim()
        while (s.endsWith("\\") && !s.endsWith("\\\\")) s = s.removeSuffix("\\").trim()
        if (s.isNotEmpty()) out += s
        run.clear()
    }
    for (n in nodes) {
        if (isBlock(n)) {
            flush()
            block(n).takeIf { it.isNotBlank() }?.let { out += it }
        } else {
            run.append(inline(n))
        }
    }
    flush()
    return out
}

private fun block(n: Node): String = when (val tag = n.tag) {
    "h1", "h2", "h3", "h4", "h5", "h6" -> {
        val text = inlineOf(n.children).replace(HARD_BREAK, " ").trim()
        if (text.isEmpty()) "" else "#".repeat(tag[1] - '0') + " " + text
    }
    "hr" -> "---"
    "pre" -> "```\n" + rawText(n).trim('\n') + "\n```"
    "blockquote" -> blocks(n.children, false).joinToString("\n\n").lines()
        .joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
    "ul", "ol" -> list(n, ordered = tag == "ol")
    "table" -> table(n)
    "dt" -> inlineOf(n.children).trim().let { if (it.isEmpty()) "" else "**$it**" }
    "dd" -> blocks(n.children, false).joinToString("\n\n").lines().joinToString("\n") { ": $it" }
    else -> blocks(n.children, false).joinToString("\n\n")
}

private fun list(n: Node, ordered: Boolean): String {
    val start = n.attrs["start"]?.toIntOrNull() ?: 1
    val items = n.children.filter { !it.isText || it.text.isNotBlank() }
    var i = 0
    return items.joinToString("\n") { item ->
        val marker = if (ordered) "${start + i}. " else "- "
        i++
        val content = (if (item.tag == "li") blocks(item.children, false) else blocks(listOf(item), false)).joinToString("\n")
        if (content.isBlank()) {
            marker.trimEnd()
        } else {
            content.lines().mapIndexed { k, line ->
                if (k == 0) marker + line else if (line.isBlank()) "" else " ".repeat(marker.length) + line
            }.joinToString("\n")
        }
    }
}

private fun rows(n: Node): List<Node> = n.children.flatMap { c ->
    when (c.tag) {
        "tr" -> listOf(c)
        "thead", "tbody", "tfoot" -> rows(c)
        else -> emptyList()
    }
}

private fun cells(row: Node) = row.children.filter { it.tag == "td" || it.tag == "th" }

private fun hasDescendant(n: Node, tag: String): Boolean = n.children.any { it.tag == tag || hasDescendant(it, tag) }

/**
 * A DATA table becomes a GFM table. A LAYOUT table (most HTML mail is laid out in tables: nested tables, or a single
 * column) is unwrapped into its content, which is what the reader sees.
 */
private fun table(n: Node): String {
    val rows = rows(n)
    val width = rows.maxOfOrNull { cells(it).size } ?: 0
    val layout = width <= 1 || hasDescendant(n, "table") || rows.isEmpty()
    if (layout) {
        return rows.flatMap { r -> cells(r).flatMap { blocks(it.children, false) } }
            .ifEmpty { blocks(n.children.filter { it.tag !in setOf("tr", "thead", "tbody", "tfoot") }, false) }
            .joinToString("\n\n")
    }
    fun cellText(c: Node) = blocks(c.children, false).joinToString("<br>")
        .replace(HARD_BREAK, "<br>").replace("\n", " ").replace("|", "\\|").trim()
    val grid = rows.map { r -> cells(r).map(::cellText).let { it + List(width - it.size) { "" } } }
    val sb = StringBuilder()
    sb.append("| ").append(grid.first().joinToString(" | ")).append(" |\n")
    sb.append("|").append(List(width) { " --- " }.joinToString("|")).append("|")
    for (r in grid.drop(1)) sb.append("\n| ").append(r.joinToString(" | ")).append(" |")
    return sb.toString()
}

private fun inlineOf(nodes: List<Node>): String = nodes.joinToString("") { inline(it) }

private fun inline(n: Node): String {
    if (n.isText) return escapeInline(n.text.replace(Regex("[\\s\u00a0]+"), " "))
    return when (n.tag) {
        "br" -> HARD_BREAK
        "strong", "b" -> wrap("**", inlineOf(n.children))
        "em", "i", "cite" -> wrap("*", inlineOf(n.children))
        "del", "s", "strike" -> wrap("~~", inlineOf(n.children))
        "code", "kbd", "samp", "tt" -> rawText(n).replace('\n', ' ').let { code ->
            if (code.isBlank()) "" else if ('`' in code) "`` $code ``" else "`$code`"
        }
        "a" -> link(n)
        "img" -> image(n)
        // A block that sits inside an inline element (a <div> in a <span> or an <a>) reads as a space.
        else -> if (isBlock(n)) " " + inlineOf(n.children) + " " else inlineOf(n.children)
    }
}

private fun wrap(mark: String, inner: String): String {
    val t = inner.trim()
    if (t.isEmpty()) return inner
    val lead = if (inner.startsWith(" ")) " " else ""
    val trail = if (inner.endsWith(" ")) " " else ""
    return "$lead$mark$t$mark$trail"
}

private fun link(n: Node): String {
    val text = inlineOf(n.children).trim()
    val href = n.attrs["href"]?.trim().orEmpty()
    if (href.isEmpty() || href.startsWith("javascript:", ignoreCase = true)) return text
    if (text.isEmpty()) return "<$href>"
    if (text == escapeInline(href) || text == escapeInline(href.removePrefix("mailto:"))) return "<$href>"
    return "[$text](${href.replace(" ", "%20").replace(")", "%29")})"
}

private fun image(n: Node): String {
    val src = n.attrs["src"]?.trim().orEmpty()
    if (src.isEmpty()) return ""
    val alt = escapeInline(n.attrs["alt"].orEmpty().trim())
    return "![$alt](${src.replace(" ", "%20").replace(")", "%29")})"
}

/** Text as it stands, for code: entities decoded, <br> as a line break, no escaping. */
private fun rawText(n: Node): String = if (n.isText) n.text else if (n.tag == "br") "\n" else n.children.joinToString("") { rawText(it) }
