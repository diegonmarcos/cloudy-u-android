package app.sterna.ui.compose

import app.sterna.core.data.text.plainTextToHtml
import app.sterna.core.imap.htmlToPlainText

/**
 * The two alternatives a send builds from the editor's wire html (#1800): `text/plain` generated from
 * the html, and `text/html`. Rich (the default) sends the editor's html as is; plain-text mode sends
 * the generated text wrapped as html, so the message still leaves as multipart/alternative but with
 * no formatting. The html is never null.
 */
internal fun composeSendBodies(wireHtml: String, plainTextMode: Boolean): Pair<String, String> {
    val text = htmlToPlainText(wireHtml)
    return text to if (plainTextMode) plainTextToHtml(text) else wireHtml
}
