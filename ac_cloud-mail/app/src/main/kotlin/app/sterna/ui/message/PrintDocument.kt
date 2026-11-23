package app.sterna.ui.message

import app.sterna.core.data.text.htmlEscape
import app.sterna.core.jmap.model.EmailAddress

/**
 * The header lines of the message to print, ALREADY formatted by the caller: the subject resolved
 */
internal data class PrintHeader(
    val subject: String,
    val from: String,
    val to: String,
    /** "" when nobody is in Cc — the line is then left out of the document entirely. */
    val cc: String,
    val date: String,
)

/** The already-localised labels of the header lines, passed in like every string here. */
internal data class PrintLabels(val from: String, val to: String, val cc: String, val date: String)

/**
 * The complete HTML document to hand to Android's print service — distinct from the reader's
 */
internal fun buildPrintDocument(
    header: PrintHeader,
    labels: PrintLabels,
    body: ReaderBody,
    inlineImages: Map<String, String> = emptyMap(),
): String {
    // The same two rewrites the reader's document makes on a rich body, and only on one: the
    // message's own dark-mode styles are defanged here too — paper is white, and a marketing email
    // must not print its dark variant because the device happens to be in dark theme.
    val inner = if (body.richHtml) {
        neutraliseDarkModeStyles(embedInlineImages(body.fragment, inlineImages))
    } else {
        body.fragment
    }
    val lines = StringBuilder()
    fun line(label: String, value: String) {
        lines.append("<p class=\"h\"><b>").append(htmlEscape(label)).append("</b> ")
            .append(htmlEscape(value)).append("</p>\n")
    }
    line(labels.from, header.from)
    line(labels.to, header.to)
    if (header.cc.isNotBlank()) line(labels.cc, header.cc)
    line(labels.date, header.date)
    return """
        <!DOCTYPE html><html><head>
        <meta charset="utf-8">
        <meta name="color-scheme" content="only light">
        $CSP_META
        <style>
          html { color-scheme: only light; }
          html, body { background: #ffffff; color: #111111; }
          body { font-family: sans-serif; line-height: 1.45; margin: 0;
                 word-wrap: break-word; overflow-wrap: break-word; }
          @page { margin: 18mm; }
          img { max-width: 100%; height: auto; }
          /* The two classes readerBody emits, styled as the reader styles them: what was read
             in sans-serif prints in sans-serif, and the "derived text" line stays a note. */
          pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }
          p.s-note { margin: 0 0 12px; font-size: 0.85em; opacity: 0.7; }
          h1 { font-size: 1.3em; margin: 0 0 0.5em 0; }
          p.h { margin: 0.15em 0; }
          hr { border: 0; border-top: 1px solid #cccccc; margin: 1em 0; }
        </style></head><body>
        <h1>${htmlEscape(header.subject)}</h1>
        $lines<hr>
        $inner
        </body></html>
    """.trimIndent()
}

/**
 * Replace every `cid:` reference in [html] by its data URI — the message's own inline images,
 */
internal fun embedInlineImages(html: String, inlineImages: Map<String, String>): String {
    var out = html
    inlineImages.forEach { (cid, dataUri) ->
        // #1869 step 6: an image stored as a file keeps its `cid:` reference; the reader's WebView serves it.
        if (app.sterna.core.data.mail.InlineImageFiles.isRef(dataUri)) return@forEach
        out = out.replace("cid:$cid", dataUri).replace("cid:<$cid>", dataUri)
    }
    return out
}

/**
 * The name the print job carries in the system's print queue and in the PDF it may save: the
 */
internal fun printJobName(subject: String, appName: String): String = subject.trim().ifBlank { appName }

/**
 * Defang the message's own `prefers-color-scheme: dark` media queries by appending an always-false
 */
internal fun neutraliseDarkModeStyles(html: String): String = html.replace(
    Regex("""prefers-color-scheme\s*:\s*dark""", RegexOption.IGNORE_CASE),
    "prefers-color-scheme:dark) and (max-width:0px",
)

/**
 * A participant as paper wants it: `Name <address>`, or the bare address — unlike the screen's
 */
internal fun printAddress(address: EmailAddress): String {
    val name = address.name?.trim().orEmpty()
    return if (name.isEmpty()) address.email else "$name <${address.email}>"
}

/** The participants of one header line, joined the way the header on screen joins them. */
internal fun printAddresses(addresses: List<EmailAddress>): String = addresses.joinToString { printAddress(it) }
