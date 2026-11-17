package com.diegonmarcos.cloudsuperintel

/**
 * "Ask AI · Cloud SI": the pure part of the entry that lists SI in the open-with chooser (ACTION_VIEW), the share sheet
 * (ACTION_SEND, ACTION_SEND_MULTIPLE) and the text selection toolbar (ACTION_PROCESS_TEXT). No Android class here, so the
 * parsing, the mime routing and the size cap are plain JVM tests. [AskAiActivity] reads the intent into [parse]'s arguments,
 * copies each URI into the cache (the source app's grant ends with its task) and hands the result to a new SI chat.
 */
object AskAi {
    /** One file may be at most this large; the same cap as the chat's (+) Files menu (AiAttachUi.maxBytes). */
    const val MAX_BYTES = 12_000_000L
    /** All files of one share together; a share of many files cannot fill the cache. */
    const val MAX_TOTAL_BYTES = 36_000_000L
    const val MAX_FILES = 10
    /** A text taken from a text/plain share or the selection toolbar is cut here (same bound as AiAttachWire.MAX_TEXT). */
    const val MAX_TEXT = 60_000
    const val DEFAULT_PROMPT = "Explain this"

    const val VIEW = "android.intent.action.VIEW"
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /** What an incoming intent asks for: files by URI string, and/or a text. [uris] holds content:// and file:// only. */
    data class Request(val uris: List<String>, val text: String?, val mime: String?)

    /**
     * Pure: the request an intent's parts name, or null when it carries nothing to ask about. [data] is the VIEW URI,
     * [stream] the SEND stream or the SEND_MULTIPLE streams, [text] EXTRA_TEXT, [processText] EXTRA_PROCESS_TEXT.
     */
    fun parse(action: String?, type: String?, data: String?, stream: List<String>, text: CharSequence?, processText: CharSequence?): Request? {
        val ok = { u: String -> u.startsWith("content://") || u.startsWith("file://") }
        val t = { s: CharSequence? -> s?.toString()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) }
        val r = when (action) {
            VIEW -> Request(listOfNotNull(data).filter(ok), null, type)
            SEND, SEND_MULTIPLE -> Request(stream.filter(ok).distinct().take(MAX_FILES), t(text), type)
            PROCESS_TEXT -> Request(emptyList(), t(processText), type ?: "text/plain")
            else -> return null
        }
        return if (r.uris.isEmpty() && r.text == null) null else r
    }

    /** The extractor a file goes to. */
    enum class Kind { IMAGE, PDF, TEXT, DOC, AUDIO, VIDEO, OTHER }

    private val DOC_MIMES = setOf(
        "application/msword", "application/rtf", "application/epub+zip", "application/vnd.oasis.opendocument.text",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
    private val TEXT_MIMES = setOf("application/json", "application/xml", "application/x-yaml", "application/javascript", "application/x-sh", "application/sql", "application/toml")

    /** Pure: the route of a mime (the lower-cased type, parameters dropped). Images are vision input, a PDF goes to the fleet PDF extractor, text is read as is. */
    fun route(mime: String?): Kind {
        val m = mime.orEmpty().substringBefore(';').trim().lowercase()
        return when {
            m.startsWith("image/") -> Kind.IMAGE
            m == "application/pdf" -> Kind.PDF
            m.startsWith("text/") || m in TEXT_MIMES -> Kind.TEXT
            m in DOC_MIMES -> Kind.DOC
            m.startsWith("audio/") -> Kind.AUDIO
            m.startsWith("video/") -> Kind.VIDEO
            else -> Kind.OTHER
        }
    }

    /** Pure: null when [bytes] fits under [max], else the message the user reads. */
    fun capError(name: String, bytes: Long, max: Long = MAX_BYTES): String? =
        if (bytes > max) "$name is ${mb(bytes)} MB; Ask AI takes files up to ${mb(max)} MB. Nothing was attached." else null

    /** Pure: null when the running [total] plus [next] fits [MAX_TOTAL_BYTES]. */
    fun totalError(total: Long, next: Long): String? =
        if (total + next > MAX_TOTAL_BYTES) "These files together are over ${mb(MAX_TOTAL_BYTES)} MB; Ask AI takes up to ${mb(MAX_TOTAL_BYTES)} MB per share." else null

    private fun mb(b: Long) = String.format(java.util.Locale.ROOT, "%.1f", b / 1_000_000.0)

    /**
     * Pure: the note that rides the prompt when a file has no extractor in the fleet. Audio and video would need the
     * transcription engine, which only Cloud Writer holds (its dictation key path); SI says so instead of guessing.
     */
    fun note(kind: Kind, name: String, mime: String, bytes: Long): String? = when (kind) {
        Kind.AUDIO, Kind.VIDEO -> "[attached: $name ($mime, $bytes bytes); transcription is unavailable in SI, only the file's name, size and type are known]"
        Kind.DOC -> "[attached: $name ($mime, $bytes bytes); text extraction for this document type is unavailable, only its name, size and type are known]"
        Kind.OTHER -> "[attached: $name ($mime, $bytes bytes)]"
        else -> null
    }

    /** The quick actions: label to prompt, shown as chips under the composer. */
    data class Quick(val label: String, val prompt: String)

    fun quick(): List<Quick> = listOf(
        Quick("Summarize", "Summarize this"),
        Quick("Explain", DEFAULT_PROMPT),
        Quick("Translate", "Translate this to English"),
        Quick("Extract text", "Extract the text of this"),
        Quick("Describe image", "Describe this image"),
    )

    /** Pure: the composer's text: the prompt, then the extractor notes, then a shared or selected text under a rule. Nothing is sent until the user does. */
    fun compose(prompt: String, notes: List<String>, text: String?): String = buildString {
        append(prompt.trim().ifEmpty { DEFAULT_PROMPT })
        if (notes.isNotEmpty()) append("\n\n").append(notes.joinToString("\n"))
        if (!text.isNullOrBlank()) append("\n\n---\n").append(text)
    }
}
