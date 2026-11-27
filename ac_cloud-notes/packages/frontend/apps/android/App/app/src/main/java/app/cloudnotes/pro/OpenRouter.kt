package app.cloudnotes.pro

/**
 * #1866 where an incoming VIEW/EDIT/SEND intent goes. Pure (no Android types) so a JVM test runs it.
 * [route] never throws: an unknown, empty or malformed type falls back to [OpenRoute.BINARY] (the file view),
 * after one more look at the file name's extension when the type is generic (octet-stream, empty, any-type).
 */
enum class OpenRoute { TEXT, IMAGE, PDF, AUDIO, VIDEO, BINARY }

object OpenRouter {
    private const val GRANT_WRITE = 2 // Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** #1642 text names (kept identical to the editor import's list). */
    val TEXT_EXT = setOf(
        "md", "markdown", "txt", "text", "json", "yaml", "yml", "html", "htm", "csv",
        "log", "conf", "cfg", "ini", "xml", "tsv", "toml", "properties",
    )
    val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
    val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr")
    val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi")
    private val TEXT_MIME = setOf("application/json", "application/xml", "application/x-yaml", "application/yaml")

    fun normalizeMime(mime: String?): String = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    fun isGenericMime(m: String): Boolean =
        m.isEmpty() || m == "application/octet-stream" || m == "*/*" || m == "application/*" || !m.contains('/')

    fun isTextMime(m: String): Boolean =
        m.startsWith("text/") || m in TEXT_MIME || m.endsWith("+json") || m.endsWith("+xml")

    fun extOf(name: String?): String = name?.substringAfterLast('.', "")?.trim()?.lowercase().orEmpty()

    fun route(mime: String?, name: String?): OpenRoute {
        val m = normalizeMime(mime)
        if (isTextMime(m)) return OpenRoute.TEXT
        if (m.startsWith("image/")) return OpenRoute.IMAGE
        if (m == "application/pdf") return OpenRoute.PDF
        if (m.startsWith("audio/")) return OpenRoute.AUDIO
        if (m.startsWith("video/")) return OpenRoute.VIDEO
        if (!isGenericMime(m)) return OpenRoute.BINARY
        val e = extOf(name)
        return when {
            e in TEXT_EXT -> OpenRoute.TEXT
            e in IMAGE_EXT -> OpenRoute.IMAGE
            e == "pdf" -> OpenRoute.PDF
            e in AUDIO_EXT -> OpenRoute.AUDIO
            e in VIDEO_EXT -> OpenRoute.VIDEO
            else -> OpenRoute.BINARY
        }
    }

    /** The first bytes of a PDF: "%PDF-". A file with no name and a generic type is still routed to the reader when it starts so. */
    fun isPdfMagic(head: ByteArray): Boolean =
        head.size >= 5 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() && head[4] == '-'.code.toByte()

    /** Up to [n] leading bytes (InputStream.readNBytes needs API 33; minSdk is 26). */
    fun readHead(i: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var got = 0
        while (got < n) { val r = i.read(b, got, n - got); if (r < 0) break; got += r }
        return b.copyOf(got)
    }

    /** An EDIT of a content:// URI whose sender granted write access saves back to the source. */
    fun canWriteBack(action: String?, scheme: String?, flags: Int): Boolean =
        action == "android.intent.action.EDIT" && scheme == "content" && (flags and GRANT_WRITE) != 0

    /** Human size: 0 B, 1.5 KiB, 3.0 MiB. Negative or unknown sizes read "unknown". */
    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "unknown"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
    }

    /** hexdump rows: offset, 16 hex bytes, printable ASCII. Empty input gives an empty string. */
    fun hexPreview(b: ByteArray, max: Int = 512): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder()
        var o = 0
        while (o < n) {
            val end = minOf(o + 16, n)
            sb.append(String.format(java.util.Locale.ROOT, "%08x  ", o))
            for (i in o until o + 16) sb.append(if (i < end) String.format(java.util.Locale.ROOT, "%02x ", b[i].toInt() and 0xff) else "   ")
            sb.append(" ")
            for (i in o until end) { val c = b[i].toInt() and 0xff; sb.append(if (c in 32..126) c.toChar() else '.') }
            sb.append('\n')
            o = end
        }
        return sb.toString()
    }
}

/** #1888 pure share-intent routing (JVM tested): what a SEND / SEND_MULTIPLE becomes. */
enum class ShareRoute { NOTE_FROM_TEXT, STREAMS, NONE }

object ShareRouter {
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /** Streams win over EXTRA_TEXT (a share with both is a file with a caption); text alone is a new note; nothing is nothing. */
    fun route(action: String?, streamCount: Int, text: String?): ShareRoute = when {
        action != SEND && action != SEND_MULTIPLE -> ShareRoute.NONE
        streamCount > 0 -> ShareRoute.STREAMS
        !text.isNullOrBlank() -> ShareRoute.NOTE_FROM_TEXT
        else -> ShareRoute.NONE
    }

    /** Each stream follows the VIEW table: text to the editor, everything else to the file view. */
    fun streamRoute(mime: String?, name: String?): OpenRoute = OpenRouter.route(mime, name)

    /** Note name: the subject, else the first non-blank line of the text (at most 60 chars, no path characters), plus .md. */
    fun noteName(subject: String?, text: String): String {
        val base = (subject?.takeIf { it.isNotBlank() } ?: text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Shared note").trim()
        val clean = base.map { if (it.isISOControl() || it in "/\\:*?\"<>|") ' ' else it }.joinToString("").trim().take(60).trim().ifEmpty { "Shared note" }
        return "$clean.md"
    }
}
