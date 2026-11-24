package com.diegonmarcos.clouddrive.share

/**
 * #1888 the share sheet: what another app hands over with ACTION_SEND / SEND_MULTIPLE is imported into a folder the user picks.
 * The pure rules live here (JVM tested); [ShareReceiverActivity] does the Android half.
 */
object ShareImport {
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /** One thing to import: a stream URI (with its display name and type) or a piece of shared text. */
    sealed class Item {
        data class Stream(val uri: String, val name: String?, val mime: String?) : Item()
        data class Text(val text: String) : Item()
    }

    /** The items of a share: every stream, else the EXTRA_TEXT as one text file; nothing for any other action. */
    fun plan(action: String?, streams: List<String>, text: String?): List<String> = when {
        action != SEND && action != SEND_MULTIPLE -> emptyList()
        streams.isNotEmpty() -> streams
        !text.isNullOrBlank() -> listOf(TEXT_MARK)
        else -> emptyList()
    }

    const val TEXT_MARK = "text:"

    /** A display name made safe as one file name (same rule as the editor's ExternalOpen.safeName). */
    fun safeName(raw: String?): String {
        var n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
        n = n.map { c -> if (c.isISOControl() || c in ":*?\"<>|") '_' else c }.joinToString("")
        n = n.trim('.', ' ')
        return if (n.isEmpty()) "file" else if (n.length > 120) n.take(120) else n
    }

    /** The name for shared text: the subject or first line, as .txt. */
    fun textName(subject: String?, text: String): String {
        val base = (subject?.takeIf { it.isNotBlank() } ?: text.lineSequence().firstOrNull { it.isNotBlank() } ?: "shared").trim()
        return safeName(base.take(60)).removeSuffix(".txt") + ".txt"
    }

    /** A name not in [taken] (case-insensitive): "a.md", "a (1).md", "a (2).md". */
    fun uniqueName(name: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$stem ($i)$ext".lowercase() in lower) i++
        return "$stem ($i)$ext"
    }

    enum class OpenTarget { PDF_READER, EDITOR }

    /** "Open with" from the share sheet: a PDF goes to the fleet's PDF reader, everything else to the Editor/viewer (same split as VIEW). */
    fun openTarget(mime: String?, name: String?): OpenTarget =
        if (mime?.substringBefore(';')?.trim()?.lowercase() == "application/pdf" || name?.lowercase()?.endsWith(".pdf") == true) OpenTarget.PDF_READER else OpenTarget.EDITOR

    /** A rename is offered only for a single shared item. */
    fun canRename(itemCount: Int): Boolean = itemCount == 1

    /** The name a single item is saved under: the user's rename when given and non-blank (made safe), else the original. */
    fun finalName(original: String, rename: String?): String = if (rename.isNullOrBlank()) safeName(original) else safeName(rename)

    /** One line per item for the result list: "saved name" or "failed name". */
    fun resultLine(name: String, ok: Boolean): String = (if (ok) "saved " else "failed ") + name

    /** The folder to start the picker in: the last tree used when there is one, else none (the picker's own default). */
    fun initialTree(last: String?): String? = last?.takeIf { it.isNotBlank() }

    /** A SAF document id of the external-storage provider as a path: "primary:Download/x" -> <primaryRoot>/Download/x (the caller passes Environment.getExternalStorageDirectory()). Null for any other id. */
    fun docIdToPath(docId: String, primaryRoot: String): String? {
        val vol = docId.substringBefore(':', "")
        if (vol.isEmpty() || !docId.contains(':')) return null
        val rest = docId.substringAfter(':')
        val root = if (vol == "primary") primaryRoot.trimEnd('/') else "/storage/$vol"
        return if (rest.isEmpty()) root else "$root/$rest"
    }
}
