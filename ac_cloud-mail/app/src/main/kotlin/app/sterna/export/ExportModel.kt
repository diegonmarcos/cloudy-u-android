package app.sterna.export

import java.io.InputStream

/**
 * One file-like piece of a message to export. [open] hands back a FRESH stream each call and the
 * builders copy it in chunks straight to their output, so a big attachment is never a second copy in
 * memory beyond whatever the source itself holds.
 */
class ExportPart(
    val name: String?,
    val type: String,
    val cid: String?,
    /** True for an image the body references by `cid:`; false for a file attachment. */
    val inline: Boolean,
    val size: Long,
    val open: () -> InputStream,
)

/** Everything an export needs, already resolved by the caller. Pure data: no Android types. */
class ExportMessage(
    val subject: String,
    val from: String,
    val to: String,
    val cc: String,
    /** ISO-8601 instant as the server gave it (`receivedAt`), or null. */
    val dateIso: String?,
    val messageId: String?,
    /** The body as an HTML FRAGMENT: the message's sanitised html part when it has one (see readerBody). */
    val bodyHtml: String,
    val parts: List<ExportPart>,
    /** #1870 the message's own text when it has NO html part: the Markdown export keeps it as it is. */
    val bodyText: String? = null,
)

/** Thrown by a builder when the user cancels; the caller deletes the half-written document. */
class ExportCancelled : RuntimeException("Export cancelled")

/** Progress callback: bytes written so far of an estimated [total] (0 when unknown). */
fun interface ExportProgress {
    fun update(done: Long, total: Long)

    companion object { val NONE = ExportProgress { _, _ -> } }
}

internal fun copyChunked(
    input: InputStream,
    chunk: ByteArray,
    isCancelled: () -> Boolean,
    sink: (ByteArray, Int) -> Unit,
) {
    while (true) {
        if (isCancelled()) throw ExportCancelled()
        val n = input.read(chunk)
        if (n < 0) return
        if (n > 0) sink(chunk, n)
    }
}
