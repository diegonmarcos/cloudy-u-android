package app.sterna.ui.message

import app.sterna.core.data.text.htmlEscape
import app.sterna.core.jmap.model.Email

/**
 * What the reader is about to RENDER for one message. The reader renders HTML, always: there is no
 * plain-text reading mode. A message's own html part is sanitised; a text-only message is its text
 * as escaped, linkified HTML - the same page, the same WebView.
 */
internal data class ReaderBody(
    /** The HTML fragment to insert into the document's body. */
    val fragment: String,
    /** Whether that fragment is the message's own rich HTML (drives the dark-theme invert). */
    val richHtml: Boolean,
)

/**
 * The body fragment for [email]: its html part when it has one, else its text part as HTML, else its
 * preview, else [noContent].
 */
internal fun readerBody(
    email: Email,
    noContent: String,
): ReaderBody {
    // The ONE place a received message's own markup becomes a fragment a renderer will be given,
    // so the ONE place it is reduced to the policy in [sanitiseReceivedHtml]. Both the reader and
    // the print document reach a body through here, which is why the sanitiser is not at either
    // of those call sites: a third renderer added later inherits the policy instead of forgetting
    // it.
    email.htmlContent()?.takeIf { it.isNotBlank() }
        ?.let { return ReaderBody(sanitiseReceivedHtml(it), richHtml = true) }
    email.textContent()?.takeIf { it.isNotBlank() }
        ?.let { return ReaderBody(plainFragment(it), false) }
    val fallback = email.preview?.takeIf { it.isNotBlank() } ?: noContent
    return ReaderBody("<p>${htmlEscape(fallback)}</p>", false)
}

/**
 * Whether remote images may be fetched for the page as it currently stands: when the reader's "Load remote
 * images" setting is on ([autoLoad]; ON by default since #1837, like Gmail), or the reader asked for them on
 * this message, or the sender is on the allowlist. With the setting off they are blocked until one of the other two.
 */
internal fun showRemoteImages(manualShow: Boolean, senderAllowed: Boolean, autoLoad: Boolean): Boolean =
    manualShow || senderAllowed || autoLoad

/** A text-only message as the document carries it: escaped, line breaks kept, web addresses linked. */
private fun plainFragment(text: String): String =
    "<pre class=\"plain\">${linkifyEscaped(htmlEscape(reflowFormatFlowed(text)))}</pre>"

private val BARE_URL = Regex("""(?i)\bhttps?://[^\s<>"']+""")

/**
 * A text-only message is shown as HTML like any other: its (already escaped) text with every bare
 * web address made a real link, so the page behaves the same as one the sender wrote in HTML. The
 * trailing sentence punctuation is not part of the address. [escaped] must already be [htmlEscape]d.
 */
internal fun linkifyEscaped(escaped: String): String = BARE_URL.replace(escaped) { m ->
    var url = m.value
    var tail = ""
    while (url.isNotEmpty() && url.last() in ".,;:!?)") {
        tail = url.last() + tail
        url = url.dropLast(1)
    }
    if (url.length <= "https://".length) m.value else "<a href=\"$url\">$url</a>$tail"
}

/**
 * #1869 the sanitiser runs ONCE per body: the reader, its text tools and the document builder each ask [readerBody] for
 * the same message during one open, and a large newsletter is a long sanitise each time. This file-private name shadows
 * the policy function ([app.sterna.core.data.text.sanitiseReceivedHtml], still the ONE policy) with the memo of
 * [app.sterna.core.data.mail.ReaderFragment] keyed by the exact source html (step 7: a fragment stored with the body
 * seeds it), so [readerBody] stays the one place markup becomes a fragment. Pure in, pure out.
 */
private fun sanitiseReceivedHtml(html: String): String = app.sterna.core.data.mail.ReaderFragment.sanitised(html)
