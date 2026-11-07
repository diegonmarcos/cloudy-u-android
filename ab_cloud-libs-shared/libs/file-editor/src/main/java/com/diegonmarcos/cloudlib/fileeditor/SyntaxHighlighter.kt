package com.diegonmarcos.cloudlib.fileeditor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class TokenKind { COMMENT, STRING, NUMBER, KEYWORD, HEADING }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

@Serializable
data class LanguageSpec(
    val id: String,
    val extensions: List<String>,
    val line_comment: String? = null,
    val block_comment: List<String>? = null,
    val strings: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val heading_prefix: String? = null,
    val number: Boolean = false,
)

@Serializable
private data class LanguageCatalog(val languages: List<LanguageSpec>)

/**
 * A small, predictable tokenizer driven entirely by file-editor-languages.json:
 * comments, strings (backslash escapes honoured), numbers, keywords, headings.
 * One left-to-right pass, no regex over the whole text, so a pathological file
 * cannot make it backtrack. Good enough to READ code on a phone; not a parser.
 */
class SyntaxHighlighter(private val languages: List<LanguageSpec>) {

    fun languageFor(fileName: String): LanguageSpec? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == fileName.lowercase()) return null
        return languages.firstOrNull { ext in it.extensions }
    }

    fun tokenize(text: String, lang: LanguageSpec?): List<Token> {
        if (lang == null || text.isEmpty()) return emptyList()
        val out = ArrayList<Token>()
        val keywords = lang.keywords.toHashSet()
        val n = text.length
        var i = 0
        var lineStart = true
        while (i < n) {
            val c = text[i]
            // headings (markdown): the whole line, only at a line start
            if (lineStart && lang.heading_prefix != null && text.startsWith(lang.heading_prefix, i)) {
                val end = lineEnd(text, i); out += Token(i, end, TokenKind.HEADING); i = end; lineStart = true; continue
            }
            lineStart = c == '\n'
            if (c == '\n') { i++; continue }
            // block comment
            val bc = lang.block_comment
            if (bc != null && bc.size == 2 && text.startsWith(bc[0], i)) {
                val close = text.indexOf(bc[1], i + bc[0].length)
                val end = if (close < 0) n else close + bc[1].length
                out += Token(i, end, TokenKind.COMMENT); i = end; continue
            }
            // line comment
            val lc = lang.line_comment
            if (lc != null && text.startsWith(lc, i)) {
                val end = lineEnd(text, i); out += Token(i, end, TokenKind.COMMENT); i = end; continue
            }
            // strings
            val delim = lang.strings.firstOrNull { text.startsWith(it, i) }
            if (delim != null) {
                var j = i + delim.length
                while (j < n) {
                    if (text[j] == '\\') { j += 2; continue }
                    if (text.startsWith(delim, j)) { j += delim.length; break }
                    if (text[j] == '\n' && delim != "`" && delim != "```") break   // unterminated: stop at the line
                    j++
                }
                val end = minOf(j, n); out += Token(i, end, TokenKind.STRING); i = end; continue
            }
            // numbers
            if (lang.number && c.isDigit() && (i == 0 || !isWordChar(text[i - 1]))) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                out += Token(i, j, TokenKind.NUMBER); i = j; continue
            }
            // words → keywords
            if (isWordChar(c) && (i == 0 || !isWordChar(text[i - 1]))) {
                var j = i + 1
                while (j < n && isWordChar(text[j])) j++
                if (text.substring(i, j) in keywords) out += Token(i, j, TokenKind.KEYWORD)
                i = j; continue
            }
            i++
        }
        return out
    }

    private fun lineEnd(text: String, from: Int): Int { val e = text.indexOf('\n', from); return if (e < 0) text.length else e }
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Build from the JSON catalogue's text (the asset on Android, the file in the JVM suite). */
        fun fromJson(catalogText: String): SyntaxHighlighter =
            SyntaxHighlighter(json.decodeFromString(LanguageCatalog.serializer(), catalogText).languages)

        /** The runtime dataset (shared/file-editor-languages, #1547), never an asset. */
        const val DATASET = "file-editor-languages"
    }
}
