package com.diegonmarcos.cloudwriter.core

/**
 * #1418 the conversions every Writer box needs, in one place.
 *
 * A box's canonical value is HTML (the WYSIWYG editor's own format). Text-only mode, the engines
 * (which take plain text) and the three export formats are all views of it, so a switch between
 * the two edit modes, a run and an export can never disagree about what the box holds.
 *
 * Deliberately a small regex converter over the tags the editor writes (p, br, h1-h6, b/strong,
 * i/em, u, a, ul/ol/li, blockquote, code, pre). It is not an HTML parser and does not pretend to be:
 * an imported page keeps its words and its paragraphs, which is what a writing box needs.
 */
object HtmlText {

    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")

    fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun unescape(text: String): String {
        var s = Regex("&#(\\d+);").replace(text) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        // &amp; last, so "&amp;lt;" stays the literal "&lt;" rather than becoming "<".
        ENTITIES.filterKeys { it != "&amp;" }.forEach { (k, v) -> s = s.replace(k, v) }
        return s.replace("&amp;", "&")
    }

    /** Plain text into editor HTML: blank lines split paragraphs, single newlines are line breaks. */
    fun textToHtml(text: String): String {
        val norm = text.replace("\r\n", "\n").trim('\n')
        if (norm.isBlank()) return ""
        return norm.split(Regex("\n{2,}")).joinToString("") { p -> "<p>" + escape(p).replace("\n", "<br>") + "</p>" }
    }

    /** Editor HTML into plain text: block ends become newlines, list items get a dash, tags go. */
    fun htmlToText(html: String): String {
        var s = html.replace("\r\n", "\n")
        s = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(s, "")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)<li[^>]*>").replace(s, "- ")
        s = Regex("(?i)</(p|div|h[1-6]|li|blockquote|pre|ul|ol|tr)>").replace(s, "\n\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = unescape(s)
        s = s.lines().joinToString("\n") { it.trimEnd() }
        // list items were split by paragraph breaks above; a list reads as consecutive lines.
        s = Regex("\n{2,}(?=- )").replace(s, "\n")
        return Regex("\n{3,}").replace(s, "\n\n").trim()
    }

    /** Editor HTML into Markdown, for the .md export and for engines that keep structure. */
    fun htmlToMarkdown(html: String): String {
        var s = html.replace("\r\n", "\n")
        s = Regex("(?is)<pre[^>]*>(?:<code[^>]*>)?(.*?)(?:</code>)?</pre>").replace(s) { m -> "\n\n```\n" + m.groupValues[1] + "\n```\n\n" }
        for (level in 1..6) {
            s = Regex("(?is)<h$level[^>]*>(.*?)</h$level>").replace(s) { m -> "\n\n" + "#".repeat(level) + " " + m.groupValues[1] + "\n\n" }
        }
        s = Regex("(?is)<(strong|b)>(.*?)</\\1>").replace(s) { m -> "**" + m.groupValues[2] + "**" }
        s = Regex("(?is)<(em|i)>(.*?)</\\1>").replace(s) { m -> "*" + m.groupValues[2] + "*" }
        s = Regex("(?is)<code[^>]*>(.*?)</code>").replace(s) { m -> "`" + m.groupValues[1] + "`" }
        s = Regex("(?is)<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>").replace(s) { m -> "[" + m.groupValues[2] + "](" + m.groupValues[1] + ")" }
        s = Regex("(?is)<blockquote[^>]*>(.*?)</blockquote>").replace(s) { m ->
            "\n\n" + htmlToText(m.groupValues[1]).lines().joinToString("\n") { "> $it" } + "\n\n"
        }
        s = Regex("(?is)<ol[^>]*>(.*?)</ol>").replace(s) { m ->
            var n = 0
            "\n\n" + Regex("(?is)<li[^>]*>(.*?)</li>").replace(m.groupValues[1]) { li -> n++; "$n. " + li.groupValues[1].trim() + "\n" } + "\n"
        }
        s = Regex("(?is)<li[^>]*>(.*?)</li>").replace(s) { m -> "- " + m.groupValues[1].trim() + "\n" }
        s = Regex("(?i)<br\\s*/?>").replace(s, "  \n")
        s = Regex("(?i)</(p|div|ul)>").replace(s, "\n\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = unescape(s)
        // no trimEnd here: two trailing spaces are Markdown's hard line break.
        return Regex("\n{3,}").replace(s, "\n\n").trim()
    }

    /** Markdown into editor HTML, for a loaded .md file: headings, quotes, lists, code, inline marks, links. */
    fun markdownToHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.replace("\r\n", "\n").lines()
        var i = 0
        val para = mutableListOf<String>()
        fun flush() { if (para.isNotEmpty()) { out.append("<p>").append(para.joinToString("<br>") { inline(it) }).append("</p>"); para.clear() } }
        while (i < lines.size) {
            val line = lines[i]
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code += lines[i]; i++ }
                    out.append("<pre><code>").append(escape(code.joinToString("\n"))).append("</code></pre>")
                }
                heading != null -> { flush(); val n = heading.groupValues[1].length; out.append("<h$n>").append(inline(heading.groupValues[2])).append("</h$n>") }
                line.startsWith(">") -> {
                    flush()
                    val q = mutableListOf<String>()
                    while (i < lines.size && lines[i].startsWith(">")) { q += lines[i].removePrefix(">").trimStart(); i++ }
                    out.append("<blockquote>").append(q.joinToString("<br>") { inline(it) }).append("</blockquote>")
                    continue
                }
                Regex("^\\s*[-*+]\\s+").containsMatchIn(line) || Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) -> {
                    flush()
                    val ordered = Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line)
                    val marker = if (ordered) Regex("^\\s*\\d+[.)]\\s+") else Regex("^\\s*[-*+]\\s+")
                    out.append(if (ordered) "<ol>" else "<ul>")
                    while (i < lines.size && marker.containsMatchIn(lines[i])) { out.append("<li>").append(inline(marker.replace(lines[i], ""))).append("</li>"); i++ }
                    out.append(if (ordered) "</ol>" else "</ul>")
                    continue
                }
                line.isBlank() -> flush()
                else -> para += line
            }
            i++
        }
        flush()
        return out.toString()
    }

    private fun inline(text: String): String {
        var s = escape(text)
        s = Regex("`([^`]+)`").replace(s) { m -> "<code>" + m.groupValues[1] + "</code>" }
        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s) { m -> "<strong>" + m.groupValues[1] + "</strong>" }
        s = Regex("(?<![*\\w])\\*([^*]+)\\*(?!\\*)").replace(s) { m -> "<em>" + m.groupValues[1] + "</em>" }
        s = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)").replace(s) { m -> "<a href=\"" + m.groupValues[2] + "\">" + m.groupValues[1] + "</a>" }
        return s
    }
}

/** #1418 the box's two edit modes. WYSIWYG is the default; a stored id this build does not know reads as WYSIWYG. */
enum class EditMode(val id: String) {
    WYSIWYG("wysiwyg"),
    TEXT("text");

    companion object {
        val DEFAULT = WYSIWYG
        fun of(id: String?): EditMode = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** #1418 the three formats a box exports to. */
enum class ExportFormat(val id: String, val extension: String, val mime: String) {
    MD("md", "md", "text/markdown"),
    TXT("txt", "txt", "text/plain"),
    HTML("html", "html", "text/html");

    companion object {
        fun of(id: String?): ExportFormat? = values().firstOrNull { it.id == id }
    }
}

object Export {
    /** The file body of [html] in [format]. HTML is a whole, UTF-8 declared document, not a fragment. */
    fun render(format: ExportFormat, html: String, title: String): String = when (format) {
        ExportFormat.TXT -> HtmlText.htmlToText(html) + "\n"
        ExportFormat.MD -> HtmlText.htmlToMarkdown(html) + "\n"
        ExportFormat.HTML -> "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>" + HtmlText.escape(title) +
            "</title></head><body>\n" + html + "\n</body></html>\n"
    }

    /** A safe file name: the first words of the text, or "writer", with the format's extension. */
    fun fileName(html: String, format: ExportFormat): String {
        val words = HtmlText.htmlToText(html).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).trim('-')
        return words.ifEmpty { "writer" } + "." + format.extension
    }
}
