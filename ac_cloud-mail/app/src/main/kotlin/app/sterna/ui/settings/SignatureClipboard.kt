package app.sterna.ui.settings

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** What the clipboard carries: Gmail puts an HTML item and a plain-text item for one copy. */
internal data class SignatureClip(val html: String?, val text: String?)

/**
 * The editor source for a clipboard: non-blank HTML wins (it keeps the markup), else the plain text.
 * HTML may be a fragment; it is returned as-is and [importedSource] unwraps pages.
 */
internal fun clipToSignatureSource(htmlText: String?, text: CharSequence?): String? =
    htmlText?.takeIf { it.isNotBlank() } ?: text?.toString()

/** The primary clip's first item as HTML and plain text; null parts when absent. */
internal fun readSignatureClip(cm: ClipboardManager): SignatureClip {
    val clip = runCatching { cm.primaryClip }.getOrNull() ?: return SignatureClip(null, null)
    if (clip.itemCount < 1) return SignatureClip(null, null)
    val item = clip.getItemAt(0)
    val html = if (clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) item.htmlText else null
    return SignatureClip(html, item.text?.toString())
}

internal fun ClipboardManager.signatureSource(): String? =
    readSignatureClip(this).let { clipToSignatureSource(it.html, it.text) }

/**
 * A paste into the source field arrives as a multi-character insertion equal to the clipboard's plain
 * text. When the clipboard also carries HTML, the HTML is inserted at the same selection instead, with
 * the caret after it. Anything else (a typed character, a different insertion) is returned untouched.
 */
internal fun substitutePaste(before: TextFieldValue, after: TextFieldValue, clip: () -> SignatureClip): TextFieldValue {
    val start = before.selection.min
    val inserted = after.text.length - (before.text.length - before.selection.length)
    if (inserted < 2 || !after.selection.collapsed || after.selection.start != start + inserted) return after
    val prefix = before.text.substring(0, start)
    val suffix = before.text.substring(before.selection.max)
    if (!after.text.startsWith(prefix) || !after.text.endsWith(suffix)) return after
    val pasted = after.text.substring(start, start + inserted)
    val c = clip()
    val html = c.html?.takeIf { it.isNotBlank() } ?: return after
    if (c.text != pasted) return after
    return TextFieldValue(prefix + html + suffix, TextRange(start + html.length))
}
