package app.sterna.export

import app.sterna.core.data.text.safeFileName

/** The formats the sheet offers, with the extension and MIME type each is saved under. */
enum class ExportFormat(val ext: String, val mime: String) {
    MHTML("mhtml", "multipart/related"),
    ATTACHMENTS_ZIP("zip", "application/zip"),
    HTML("html", "text/html"),
    EML("eml", "message/rfc822"),
    MBOX("mbox", "application/mbox"),
    MARKDOWN("md", "text/markdown"),
}

/**
 * "<date> <subject>.<ext>": the day the message arrived (when the server said), then its subject,
 * stripped of everything a file system or a document provider refuses, whitespace collapsed, no
 * leading/trailing dots or spaces (a hidden or unsaveable name), subject capped at 80 characters.
 */
fun exportFileName(dateIso: String?, subject: String?, ext: String): String {
    val day = dateIso?.let { DAY.find(it)?.value }
    val clean = safeFileName(subject, "message")
        .replace(WHITESPACE, " ")
        .trim(' ', '.')
        .ifEmpty { "message" }
    return (if (day != null) "$day $clean" else clean) + "." + ext.trimStart('.')
}

private val DAY = Regex("""^\d{4}-\d{2}-\d{2}""")
private val WHITESPACE = Regex("""\s+""")
