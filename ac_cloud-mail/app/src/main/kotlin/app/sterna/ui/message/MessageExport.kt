package app.sterna.ui.message

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.model.Email
import app.sterna.export.ExportFormat
import app.sterna.export.ExportMessage
import app.sterna.export.ExportPart
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64

/** What the export sheet shows while a job runs. [total] is 0 while unknown. */
data class ExportUiState(val format: ExportFormat, val done: Long = 0, val total: Long = 0)

/** The formats the sheet lists, in its order; MHTML first because it is the default. */
internal val EXPORT_FORMATS: List<ExportFormat> = listOf(
    ExportFormat.MHTML, ExportFormat.ATTACHMENTS_ZIP, ExportFormat.HTML, ExportFormat.EML, ExportFormat.MBOX, ExportFormat.MARKDOWN,
)

/** Attachments-only has nothing to export for a message without files. */
internal fun exportFormatEnabled(format: ExportFormat, email: Email): Boolean =
    format != ExportFormat.ATTACHMENTS_ZIP || email.fileAttachmentParts().isNotEmpty()

/** `data:image/png;base64,AAAA` to its bytes; null for anything else (a data URI that is not base64). */
internal fun dataUriBytes(uri: String): ByteArray? {
    val comma = uri.indexOf(',')
    if (!uri.startsWith("data:") || comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
    return runCatching { Base64.getMimeDecoder().decode(uri.substring(comma + 1)) }.getOrNull()
}

/**
 * The [ExportMessage] for the page: the body is the READER's fragment (its html part first, sanitised),
 * the inline images are the bytes the reader already loaded (an oversized or failed one is simply
 * absent, as on screen), and each file attachment is fetched ONLY when the builder opens it, one at a
 * time, so at most one attachment is in memory at once.
 */
internal fun exportMessageOf(
    msg: ThreadMessage,
    email: Email,
    subject: String,
    noContent: String,
    repo: MailRepository,
    credentials: AccountCredentials,
): ExportMessage {
    val inline = msg.inlineImages.mapNotNull { (cid, uri) ->
        val bytes = dataUriBytes(uri) ?: return@mapNotNull null
        val type = uri.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
        ExportPart(
            name = cid.substringBefore('@').ifBlank { "image" } + "." + type.substringAfter('/').substringBefore('+'),
            type = type, cid = cid, inline = true, size = bytes.size.toLong(),
        ) { ByteArrayInputStream(bytes) }
    }
    val files = email.fileAttachmentParts().map { part ->
        ExportPart(
            name = part.name, type = part.type ?: "application/octet-stream", cid = null, inline = false,
            size = part.size,
        ) {
            runBlocking { repo.downloadAttachment(credentials, part, msg.id) }.let { ByteArrayInputStream(it) as InputStream }
        }
    }
    return ExportMessage(
        subject = subject,
        from = printAddresses(email.from),
        to = printAddresses(email.to),
        cc = printAddresses(email.cc),
        dateIso = msg.header.receivedAt,
        messageId = email.messageId.firstOrNull(),
        bodyHtml = readerBody(email, noContent).fragment,
        parts = inline + files,
        // A text-only message keeps its own text in the Markdown export (#1870), not the escaped HTML the reader paints.
        bodyText = email.textContent()?.takeIf { email.htmlContent().isNullOrBlank() && it.isNotBlank() },
    )
}
