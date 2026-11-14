package com.diegonmarcos.clouddrive

/**
 * PDF → txt / markdown / html / csv, as pure functions over per-page text (#458, moved off pdf.js
 * onto the pdfium engine in #577). No Android type appears here on purpose: the JVM unit test
 * (PdfConvertTest) runs these on the CI runner, so what the converter writes is executed, not
 * argued. Text extraction is the engine's job ([PdfEngine.pageText]); writing the file is
 * [PdfConversion]'s (through FileOps.writeText, the atomic save path; #579).
 *
 * docx / xlsx / odt are NOT here and are not pretended: producing them is layout reconstruction,
 * a different feature that needs the fleet converter service. The Files tab lists them disabled,
 * with that reason, so nobody reads a missing button as a bug.
 */
internal object PdfConvert {

    /** The four targets this app can honestly write. The page's convert menu mirrors this list. */
    val TARGETS = listOf("txt", "md", "html", "csv")

    /** The dialog's targets: the four above plus OCR text of the rendered pages (written as `<name>.ocr.txt`). */
    const val OCR = "ocr"
    val DIALOG_TARGETS = TARGETS + OCR

    /** A scan has pages and no text. Four empty files would look like four successes. */
    const val NO_TEXT_LAYER_MESSAGE =
        "This PDF has no text layer, so a scan cannot be converted on the phone — nothing was written."

    private val TRAILING_BLANKS = Regex("[ \\t]+$", RegexOption.MULTILINE)

    /** pdfium ends lines with CR LF and pads glyph advances; one clean LF-separated string. */
    fun cleanPage(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').replace(TRAILING_BLANKS, "")

    /** True when no page carries any text at all — the scan refusal's one question. */
    fun hasNoText(pages: List<String>): Boolean = pages.joinToString("\n\n").isBlank()

    /** [pages] is already [cleanPage]d, one entry per PDF page, in order. */
    fun build(pages: List<String>, target: String, sourceName: String): String = when (target) {
        "md" -> pages.mapIndexed { i, text -> "## Page ${i + 1}\n\n$text" }.joinToString("\n\n") + "\n"
        "html" -> {
            val body = pages.mapIndexed { i, text ->
                "<h2 id=\"page-${i + 1}\">Page ${i + 1}</h2>\n" +
                    text.split("\n").joinToString("\n") { "<p>" + escapeHtml(it) + "</p>" }
            }.joinToString("\n")
            "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>" +
                escapeHtml(sourceName.ifEmpty { "PDF export" }) + "</title>\n</head>\n<body>\n" +
                body + "\n</body>\n</html>\n"
        }
        // One row per page: the honest CSV shape for extracted text (RFC 4180).
        "csv" -> "page,text\n" +
            pages.mapIndexed { i, text -> "${i + 1}," + csvEscape(text) }.joinToString("\n") + "\n"
        else -> pages.joinToString("\n\n") + if (pages.isEmpty()) "" else "\n"
    }

    fun escapeHtml(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** RFC 4180: a field with a comma, quote, CR or LF is quoted and its quotes doubled. */
    fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
            "\"" + value.replace("\"", "\"\"") + "\""
        else value

    /** The first of [preferred], `name (2).ext`, `name (3).ext`… that [taken] lacks. */
    fun freeName(taken: Set<String>, preferred: String): String {
        if (preferred !in taken) return preferred
        val dot = preferred.lastIndexOf('.')
        val base = if (dot > 0) preferred.substring(0, dot) else preferred
        val extension = if (dot > 0) preferred.substring(dot) else ""
        var counter = 2
        while (true) {
            val candidate = "$base ($counter)$extension"
            if (candidate !in taken) return candidate
            counter++
        }
    }

    /** `report.pdf` + `md` → `report.md`. */
    fun preferredName(sourceName: String, target: String): String =
        if (target == OCR) preferredName(sourceName, "ocr.txt") else sourceName.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "") + "." + target

    /**
     * The word around [index] in [text] — what a long press selects. Null when the press landed
     * on whitespace or outside the text. A word is a run of letters or digits; the apostrophe and
     * hyphen inside one keep it whole (don't, well-known).
     */
    fun wordRange(text: String, index: Int): IntRange? {
        if (index < 0 || index >= text.length) return null
        fun inWord(i: Int): Boolean {
            val c = text[i]
            if (c.isLetterOrDigit()) return true
            return (c == '\'' || c == '-' || c == '’') &&
                i > 0 && i < text.length - 1 &&
                text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
        }
        if (!inWord(index)) return null
        var start = index
        var end = index
        while (start > 0 && inWord(start - 1)) start--
        while (end < text.length - 1 && inWord(end + 1)) end++
        return start..end
    }
}
