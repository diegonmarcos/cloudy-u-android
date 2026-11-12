package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox

/**
 * The full information of a message (#1661): what tapping the subject shows, and what "Copy all"
 * copies. Decided here as plain data so a test can execute it; the sheet only draws it.
 *
 * It is a SUPERSET of the sender panel's routing rows ([messageMetadata]) and removes nothing from
 * that panel, which stays where it is. Like that panel, a field the message does not carry
 * produces NO line. Every input may be missing: a null, a blank or a malformed header yields no
 * line and never an exception.
 *
 * The labels are the protocol's own names (header names, JMAP property names) and are not
 * translated, like the raw headers viewer: this block is what gets pasted into a bug report.
 */
class DetailLine(val label: String, val value: String)

/** One titled group of lines. Never empty: [messageDetails] drops a group with no line. */
class DetailSection(val title: String, val lines: List<DetailLine>)

/** The group titles, in the order the groups are drawn. */
object DetailTitles {
    const val MESSAGE = "Message"
    const val TAGS = "Tags"
    const val DATES = "Dates"
    const val SIZE = "Size"
    const val AUTHENTICATION = "Authentication"
    const val IDENTIFIERS = "Identifiers"
    const val HEADERS = "All headers"
}

/** The line labels a test (and the parity check against the sender panel) names. */
object DetailLabels {
    const val SUBJECT = "Subject"
    const val FROM = "From"
    const val SENDER = "Sender"
    const val TO = "To"
    const val CC = "Cc"
    const val BCC = "Bcc"
    const val REPLY_TO = "Reply-To"
    const val RETURN_PATH = "Return-Path"
    const val DELIVERED_TO = "Delivered-To"
    const val ORIGINAL_SENDER = "Original sender"
    const val LIST_ID = "List-Id"
    const val LIST_UNSUBSCRIBE = "List-Unsubscribe"
    const val ATTACHMENTS = "Attachments"
    const val KEYWORDS = "Keywords"
    const val RECEIVED_AT = "Received (server)"
    const val SENT_AT = "Date (sender)"
    const val RESENT_DATE = "Resent-Date"
    const val RECEIVED_HOP = "Received hop"
    const val SIZE = "Size"
    const val SPF = "SPF"
    const val DKIM = "DKIM"
    const val DMARC = "DMARC"
    const val ARC = "ARC"
    const val AUTHENTICATION_RESULTS = "Authentication-Results"
    const val RECEIVED_SPF = "Received-SPF"
    const val DKIM_KEY = "DKIM signing key"
    const val ARC_AUTHENTICATION_RESULTS = "ARC-Authentication-Results"
    const val MESSAGE_ID = "Message-ID"
    const val IN_REPLY_TO = "In-Reply-To"
    const val REFERENCES = "References"
    const val THREAD_ID = "Thread id"
    const val EMAIL_ID = "Email id"
    const val BLOB_ID = "Blob id"
    const val ACCOUNT_ID = "Account id"
    const val MAILBOX_IDS = "Mailbox ids"
}

/**
 * Every group of the full message information.
 *
 * [headers] is the on-demand header fetch (empty until it answers, and on a failure): the groups
 * that need it simply have fewer lines until then. [tags] is every tag of the message, the folder
 * it was opened from included. [mailboxIds] is the server's membership set; when it is empty the
 * message's own `mailboxIds` / `mailboxId` are used instead.
 */
fun messageDetails(
    email: Email,
    headers: List<EmailHeader>,
    tags: List<MessageTag>,
    mailboxIds: Set<String>,
    mailboxNames: Map<String, String>,
    projectLabel: String,
    metaLabel: String,
    formatTime: (Long) -> String,
    formatSize: (Long) -> String,
): List<DetailSection> {
    val sections = mutableListOf<DetailSection>()

    /** One group, built in isolation: a group that fails is absent, the others still show. */
    fun section(title: String, build: (add: (String, String?) -> Unit) -> Unit) {
        val lines = mutableListOf<DetailLine>()
        runCatching {
            build { label, value ->
                value?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += DetailLine(label, it) }
            }
        }
        if (lines.isNotEmpty()) sections += DetailSection(title, lines)
    }

    section(DetailTitles.MESSAGE) { add ->
        add(DetailLabels.SUBJECT, email.subject)
        add(DetailLabels.FROM, addresses(email.from))
        add(DetailLabels.SENDER, headers.lastValue("sender"))
        add(DetailLabels.TO, addresses(email.to))
        add(DetailLabels.CC, addresses(email.cc))
        add(DetailLabels.BCC, addresses(email.bcc))
        add(DetailLabels.REPLY_TO, addresses(email.replyTo))
        add(DetailLabels.RETURN_PATH, headers.lastValue("return-path"))
        add(DetailLabels.DELIVERED_TO, headers.allValues("delivered-to").distinct().joinToString(", "))
        add(DetailLabels.ORIGINAL_SENDER, email.originalSender)
        add(DetailLabels.LIST_ID, headers.lastValue("list-id"))
        add(DetailLabels.LIST_UNSUBSCRIBE, email.listUnsubscribe ?: headers.lastValue("list-unsubscribe"))
        add(
            DetailLabels.ATTACHMENTS,
            email.attachments.joinToString(", ") { part ->
                val name = part.name?.trim().orEmpty().ifEmpty { part.type?.trim().orEmpty().ifEmpty { "?" } }
                val size = if (part.size > 0) formatSize(part.size) else ""
                if (size.isEmpty()) name else "$name ($size)"
            },
        )
    }

    section(DetailTitles.TAGS) { add ->
        for (tagSection in tagSections(tags)) {
            val label = when (tagSection.kind) {
                TagSectionKind.PROJECT -> projectLabel
                TagSectionKind.META -> metaLabel
            }
            add(label, tagSection.tags.joinToString(", ") { it.label })
        }
        add(DetailLabels.KEYWORDS, email.keywords.filterValues { it }.keys.sorted().joinToString(", "))
    }

    section(DetailTitles.DATES) { add ->
        val receivedAt = email.receivedAt?.trim().orEmpty()
        if (receivedAt.isNotEmpty()) {
            val formatted = parseIsoMillis(receivedAt)?.let(formatTime)
            add(DetailLabels.RECEIVED_AT, if (formatted.isNullOrBlank()) receivedAt else "$formatted [$receivedAt]")
        }
        val sent = headers.lastValue("date")?.trim().orEmpty()
        if (sent.isNotEmpty()) {
            val formatted = parseHeaderDateMillis(sent)?.let(formatTime)
            add(DetailLabels.SENT_AT, if (formatted.isNullOrBlank()) sent else "$formatted [$sent]")
        }
        add(DetailLabels.RESENT_DATE, headers.lastValue("resent-date"))
        // Each relay stamps the time it took the message: the part after the last ';'.
        headers.allValues("received").forEachIndexed { index, value ->
            add("${DetailLabels.RECEIVED_HOP} ${index + 1}", value.substringAfterLast(';', ""))
        }
    }

    section(DetailTitles.SIZE) { add ->
        val bytes = messageSizeBytes(email)
        if (bytes > 0) {
            val formatted = formatSize(bytes)
            add(DetailLabels.SIZE, if (formatted.isBlank()) "$bytes bytes" else "$formatted ($bytes bytes)")
        }
    }

    section(DetailTitles.AUTHENTICATION) { add ->
        // The verdicts of the reader's OWN server: the last Authentication-Results, like the sender panel.
        val verdicts = authVerdicts(headers.lastValue("authentication-results"))
        add(DetailLabels.SPF, verdicts["spf"]?.joinToString(" | "))
        add(DetailLabels.DKIM, verdicts["dkim"]?.joinToString(" | "))
        add(DetailLabels.DMARC, verdicts["dmarc"]?.joinToString(" | "))
        add(DetailLabels.ARC, verdicts["arc"]?.joinToString(" | "))
        headers.allValues("dkim-signature").forEach { add(DetailLabels.DKIM_KEY, dkimSigningKey(it)) }
        headers.allValues("authentication-results").forEach { add(DetailLabels.AUTHENTICATION_RESULTS, it) }
        headers.allValues("received-spf").forEach { add(DetailLabels.RECEIVED_SPF, it) }
        headers.allValues("arc-authentication-results").forEach { add(DetailLabels.ARC_AUTHENTICATION_RESULTS, it) }
    }

    section(DetailTitles.IDENTIFIERS) { add ->
        add(DetailLabels.MESSAGE_ID, email.messageId.joinToString(" ") { "<$it>" })
        add(DetailLabels.IN_REPLY_TO, email.inReplyTo.joinToString(" ") { "<$it>" })
        add(DetailLabels.REFERENCES, email.references.joinToString(" ") { "<$it>" })
        add(DetailLabels.THREAD_ID, email.threadId)
        add(DetailLabels.EMAIL_ID, email.id)
        add(DetailLabels.BLOB_ID, email.blobId)
        add(DetailLabels.ACCOUNT_ID, email.accountId)
        val ids = mailboxIds.ifEmpty {
            (email.mailboxIds.filterValues { it }.keys + listOfNotNull(email.mailboxId)).toSet()
        }
        add(
            DetailLabels.MAILBOX_IDS,
            ids.sorted().joinToString(", ") { id ->
                val name = mailboxNames[id]?.trim().orEmpty()
                if (name.isEmpty()) id else "$id ($name)"
            },
        )
    }

    section(DetailTitles.HEADERS) { add ->
        for (header in headers) {
            val name = header.name.trim()
            if (name.isNotEmpty()) add(name, header.value)
        }
    }

    return sections
}

/**
 * EVERY tag of the message for the details block: the same [messageTags] that decides the strip
 * under the sender and the label sheet, asked with no folder left out. The strip omits the folder
 * the message was opened from; the details name the complete membership, the Inbox included.
 */
fun everyTagOf(
    email: Email,
    mailboxIds: Set<String>,
    mailboxes: List<Mailbox>,
    nameOf: (Mailbox) -> String,
): List<MessageTag> = messageTags(
    mailboxIds = mailboxIds,
    mailboxes = mailboxes,
    keywords = email.keywords,
    currentMailboxId = null,
    nameOf = nameOf,
)

/** The whole block as plain text: what "Copy all" puts on the clipboard. */
fun detailsText(sections: List<DetailSection>): String =
    sections.joinToString("\n\n") { section ->
        (listOf("== ${section.title} ==") + section.lines.map { "${it.label}: ${it.value}" }).joinToString("\n")
    }

/**
 * The verdict clauses of one `Authentication-Results` value, by method, verbatim (RFC 8601):
 * `mx.example; spf=pass smtp.mailfrom=a@b; dkim=pass header.d=b` gives `spf` and `dkim`.
 * A method stated twice (two DKIM signatures) keeps both clauses.
 */
fun authVerdicts(value: String?): Map<String, List<String>> {
    val out = linkedMapOf<String, MutableList<String>>()
    if (value.isNullOrBlank()) return out
    for (clause in value.split(';')) {
        val text = clause.trim().replace(Regex("\\s+"), " ")
        val method = text.substringBefore('=', "").trim().lowercase()
        if (method.isEmpty() || method.any { !it.isLetterOrDigit() && it != '-' }) continue
        out.getOrPut(method) { mutableListOf() } += text
    }
    return out
}

/**
 * The key a `DKIM-Signature` names (RFC 6376): signing domain `d`, selector `s`, algorithm `a`.
 * Null when the header names neither a domain nor a selector.
 */
fun dkimSigningKey(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val tags = linkedMapOf<String, String>()
    for (part in value.split(';')) {
        val name = part.substringBefore('=', "").trim().lowercase()
        if (name.isEmpty()) continue
        tags.getOrPut(name) { part.substringAfter('=').replace(Regex("\\s+"), "") }
    }
    val domain = tags["d"].orEmpty()
    val selector = tags["s"].orEmpty()
    if (domain.isEmpty() && selector.isEmpty()) return null
    return listOfNotNull(
        domain.takeIf { it.isNotEmpty() }?.let { "d=$it" },
        selector.takeIf { it.isNotEmpty() }?.let { "s=$it" },
        tags["a"]?.takeIf { it.isNotEmpty() }?.let { "a=$it" },
    ).joinToString("; ")
}

/**
 * The size of the message as its parts state it: every body part and attachment once. The model
 * carries no whole-message size, so this is the sum the server reported per part; 0 when no part
 * states one.
 */
fun messageSizeBytes(email: Email): Long {
    val seen = mutableSetOf<String>()
    var total = 0L
    val parts: List<EmailBodyPart> = email.htmlBody + email.textBody + email.attachments
    parts.forEachIndexed { index, part ->
        val key = part.partId ?: part.blobId ?: "#$index"
        if (seen.add(key) && part.size > 0) total += part.size
    }
    return total
}

private fun addresses(list: List<EmailAddress>): String = list.joinToString(", ") { formatAddress(it) }

private fun List<EmailHeader>.allValues(name: String): List<String> =
    filter { it.name.trim().lowercase() == name }.map { it.value.trim() }.filter { it.isNotEmpty() }

private fun List<EmailHeader>.lastValue(name: String): String? = allValues(name).lastOrNull()
