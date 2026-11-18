package app.sterna.ui.compose

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import java.net.URLDecoder

/**
 * #1800: the To field's camera button. A QR code is photographed through the fleet's camera
 * (ACTION_IMAGE_CAPTURE, which Cloud Camera declares), decoded by the fleet's ONE image-scan engine
 * (libs:ml-l-image, the same engine the reader's attachment scan uses), and its addresses become
 * recipients. [parseQrRecipients] is the pure half, tested on the JVM for every format.
 */
data class QrRecipients(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null,
) {
    val count: Int get() = to.size + cc.size + bcc.size
}

private val ADDRESS = Regex("""[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

private fun addressesIn(s: String?): List<String> =
    if (s.isNullOrBlank()) emptyList() else ADDRESS.findAll(s).map { it.value }.toList()

private fun distinctFolded(list: List<String>): List<String> = list.distinctBy { it.lowercase() }

private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

/** `key:value;` fields of a MATMSG / MECARD body (backslash escapes `;`, `:`, `,` and `\`). */
private fun semicolonFields(body: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val cur = StringBuilder()
    var i = 0
    fun flush() {
        val f = cur.toString(); cur.clear()
        val colon = f.indexOf(':')
        if (colon > 0) out += f.substring(0, colon).trim().uppercase() to f.substring(colon + 1)
    }
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length -> { cur.append(body[i + 1]); i++ }
            c == ';' -> flush()
            else -> cur.append(c)
        }
        i++
    }
    flush()
    return out
}

/**
 * The recipients a scanned QR code carries, or null when it names no address. Formats:
 * `mailto:` URIs (to, cc, bcc, subject, body), MATMSG, MECARD and vCard EMAIL entries, and any plain
 * text with addresses in it. Several addresses become several recipients; duplicates are dropped.
 */
fun parseQrRecipients(raw: String): QrRecipients? {
    val v = raw.trim()
    if (v.isEmpty()) return null
    val result = when {
        v.startsWith("mailto:", ignoreCase = true) -> {
            val rest = v.substring(7)
            val path = rest.substringBefore('?')
            val query = if ('?' in rest) rest.substringAfter('?') else ""
            val to = addressesIn(urlDecode(path)).toMutableList()
            val cc = mutableListOf<String>(); val bcc = mutableListOf<String>()
            var subject: String? = null; var body: String? = null
            for (pair in query.split('&').filter { it.isNotEmpty() }) {
                val key = pair.substringBefore('=').lowercase()
                val value = urlDecode(pair.substringAfter('=', ""))
                when (key) {
                    "to" -> to += addressesIn(value)
                    "cc" -> cc += addressesIn(value)
                    "bcc" -> bcc += addressesIn(value)
                    "subject" -> subject = value
                    "body" -> body = value
                }
            }
            QrRecipients(distinctFolded(to), distinctFolded(cc), distinctFolded(bcc), subject?.ifBlank { null }, body?.ifBlank { null })
        }
        v.startsWith("MATMSG:", ignoreCase = true) -> {
            val f = semicolonFields(v.substring(7))
            QrRecipients(
                to = distinctFolded(f.filter { it.first == "TO" }.flatMap { addressesIn(it.second) }),
                subject = f.firstOrNull { it.first == "SUB" }?.second?.ifBlank { null },
                body = f.firstOrNull { it.first == "BODY" }?.second?.ifBlank { null },
            )
        }
        v.startsWith("MECARD:", ignoreCase = true) ->
            QrRecipients(distinctFolded(semicolonFields(v.substring(7)).filter { it.first == "EMAIL" }.flatMap { addressesIn(it.second) }))
        v.startsWith("BEGIN:VCARD", ignoreCase = true) -> {
            // Unfold continuation lines (RFC 6350 §3.2), then every EMAIL property, parameters or not.
            val unfolded = v.replace(Regex("\r?\n[ \t]"), "")
            QrRecipients(
                distinctFolded(
                    unfolded.lines()
                        .filter { line -> line.substringBefore(':').substringBefore(';').trim().substringAfterLast('.').equals("EMAIL", ignoreCase = true) }
                        .flatMap { addressesIn(it.substringAfter(':')) },
                ),
            )
        }
        else -> QrRecipients(distinctFolded(addressesIn(v)))
    }
    return result.takeIf { it.count > 0 }
}

/** What one scan came to, for the message the composer shows. */
sealed interface QrScanOutcome {
    data class Found(val recipients: QrRecipients) : QrScanOutcome
    /** No QR code could be read in the picture. */
    data object Unreadable : QrScanOutcome
    /** A code was read but it names no address; [raw] is what it held. */
    data class NoAddress(val raw: String) : QrScanOutcome
    /** The fleet's image-scan engine cannot answer; [reason] is its own explanation. */
    data class EngineUnavailable(val reason: String) : QrScanOutcome
}

/** Decode the QR code in the photo at [uri] with the fleet's image-scan engine. Blocking: call off the main thread. */
fun scanQrRecipients(context: Context, uri: Uri): QrScanOutcome {
    val engine = ImageScanEngine(context)
    engine.check()?.let { return QrScanOutcome.EngineUnavailable(it) }
    val scan = engine.decodeBarcode(uri) ?: return QrScanOutcome.Unreadable
    return parseQrRecipients(scan.rawValue)?.let { QrScanOutcome.Found(it) } ?: QrScanOutcome.NoAddress(scan.rawValue)
}
