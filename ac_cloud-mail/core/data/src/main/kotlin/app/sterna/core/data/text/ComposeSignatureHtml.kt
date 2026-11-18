package app.sterna.core.data.text

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredSignature

/*
 * #1800: the composer inserts a signature as RICH content, never as its flattened text.
 *
 * A signature can reach the composer with an empty HTML half even though the owner's HTML version
 * exists on the same phone: an older save kept only the flattened text (written before link targets
 * were spelled out, so labelled links became bare words such as `Whatsapp | LinkedIn`), or a manual
 * identity with that text shadows the server identity for the same address, which carries the HTML.
 * The composer then put the bare words into the body.
 *
 * [composeSignatureHtml] decides what goes in, in this order:
 *  1. the signature's own HTML;
 *  2. an HTML twin: the HTML of a signature stored for the SAME address (legacy pair, named
 *     signatures, server identity) whose flattened text is this signature's text;
 *  3. the text itself as HTML with every URL and e-mail address made a live link.
 * The text/plain alternative is still generated from the result at send time.
 */

/** The text of a signature reduced to its words, so two flattenings of one signature compare equal. */
internal fun signatureMatchKey(text: String): String =
    text.replace(Regex("[\\s|·•]+"), " ").trim().lowercase()

/**
 * Every HTML signature stored for [email] on [account]: the manual and the server identities, both
 * the legacy `signatureHtml` field and each named signature. Order: manual first, then server.
 */
fun signatureHtmlCandidates(account: StoredAccount?, email: String): List<String> {
    if (account == null) return emptyList()
    val key = email.trim().lowercase()
    return (account.identities + account.serverIdentities)
        .filter { it.email.trim().lowercase() == key }
        .flatMap { identity ->
            val split = identity.withSplitSignature()
            listOf(split.signatureHtml) + identity.signatures.map { it.html }
        }
        .filter { it.isNotBlank() }
        .distinct()
}

/** The HTML twin of a text-only [text] among [candidates], or null when none flattens to it. */
fun signatureHtmlTwin(text: String, candidates: List<String>): String? {
    val want = signatureMatchKey(text)
    if (want.isEmpty()) return null
    return candidates.firstOrNull { html ->
        signatureMatchKey(htmlToText(html)) == want ||
            signatureMatchKey(htmlToText(html, keepLinkTargets = true)) == want
    }
}

private val LINKABLE = Regex(
    """(?i)\b(https?://[^\s<>"']+[^\s<>"'.,;:!?)\]]|www\.[^\s<>"']+[^\s<>"'.,;:!?)\]]|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})""",
)

/** Plain [text] as HTML: escaped, line breaks kept, every URL and address a live `<a href>`. */
fun linkifiedSignatureHtml(text: String): String {
    val out = StringBuilder()
    var at = 0
    val t = text.trim('\n')
    for (m in LINKABLE.findAll(t)) {
        out.append(htmlEscapeMultiline(t.substring(at, m.range.first)))
        val label = m.value
        val href = when {
            label.contains("://") -> label
            label.startsWith("www.", ignoreCase = true) -> "https://$label"
            else -> "mailto:$label"
        }
        out.append("<a href=\"").append(htmlEscape(href).replace("\"", "&quot;")).append("\">")
            .append(htmlEscape(label)).append("</a>")
        at = m.range.last + 1
    }
    out.append(htmlEscapeMultiline(t.substring(at)))
    return out.toString()
}

/**
 * The HTML the composer inserts for [signature] (#1800): its own HTML, else its HTML twin among
 * [candidates], else its text linkified. Empty for no signature. Never the bare escaped text.
 */
fun composeSignatureHtml(signature: StoredSignature?, candidates: List<String>): String {
    if (signature == null) return ""
    if (signature.html.isNotBlank()) return signature.html
    if (signature.text.isBlank()) return ""
    return signatureHtmlTwin(signature.text, candidates) ?: linkifiedSignatureHtml(signature.text)
}
