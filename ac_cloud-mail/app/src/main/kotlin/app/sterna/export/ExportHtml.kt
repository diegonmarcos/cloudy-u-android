package app.sterna.export

import app.sterna.core.data.text.htmlEscape

/** The labels of the header block, localised by the caller. */
data class ExportLabels(
    val from: String,
    val to: String,
    val cc: String,
    val date: String,
    /** #1870 the heading of the Markdown export's attachment list. */
    val attachments: String = "Attachments",
)

/** The page both the MHTML root part and the self-contained .html share: headers block, rule, body. */
fun exportDocument(msg: ExportMessage, labels: ExportLabels, bodyFragment: String = msg.bodyHtml): String {
    val lines = StringBuilder()
    fun line(label: String, value: String) {
        if (value.isBlank()) return
        lines.append("<p class=\"h\"><b>").append(htmlEscape(label)).append("</b> ")
            .append(htmlEscape(value)).append("</p>\n")
    }
    line(labels.from, msg.from)
    line(labels.to, msg.to)
    line(labels.cc, msg.cc)
    line(labels.date, msg.dateIso.orEmpty())
    return "<!DOCTYPE html>\n<html><head>\n<meta charset=\"utf-8\">\n" +
        "<title>${htmlEscape(msg.subject)}</title>\n" +
        "<style>\nbody{font-family:sans-serif;line-height:1.45;margin:1em;word-wrap:break-word}\n" +
        "img{max-width:100%;height:auto}\np.h{margin:.15em 0}\nhr{border:0;border-top:1px solid #ccc;margin:1em 0}\n" +
        "</style>\n</head><body>\n<h1>${htmlEscape(msg.subject)}</h1>\n$lines<hr>\n$bodyFragment\n</body></html>\n"
}

/**
 * ONE self-contained .html: the headers block and the body, the document's CSS inline, every `cid:`
 * image whose bytes are in [inlineImages] (cid to data: URI) embedded in place. A cid with no bytes is
 * left as it is (the image is simply missing - the sheet says remote images are not included), and
 * nothing is fetched. The body is the reader's sanitised fragment, so no script survives to be inlined.
 */
fun buildSelfContainedHtml(msg: ExportMessage, labels: ExportLabels, inlineImages: Map<String, String>): String {
    var body = msg.bodyHtml
    inlineImages.forEach { (cid, dataUri) ->
        val bare = cid.trim().removePrefix("<").removeSuffix(">")
        body = body.replace("cid:$bare", dataUri).replace("cid:<$bare>", dataUri)
    }
    return exportDocument(msg, labels, body)
}
