package app.sterna.ui.message

import app.sterna.core.data.mail.InlineImageFiles
import java.io.File
import java.io.InputStream

/**
 * #1869 step 6: the reader WebView's `cid:` interception, kept free of Android types so a JVM test drives it. Called on the
 * WebView's IO threads: it reads only its arguments (an immutable map, a root directory) and the file, so it is
 * thread-safe. Only a file ref is served; a `data:` value was already embedded in the document, anything else is not ours.
 */
internal object InlineCid {
    /** (mime type, stream) for `cid:[cid]`, or null when [images] holds no stored file for it. */
    fun respond(root: File?, images: Map<String, String>, cid: String?): Pair<String, InputStream>? {
        val key = cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() } ?: return null
        val ref = images[key]?.takeIf(InlineImageFiles::isRef) ?: return null
        val (type, file) = InlineImageFiles.open(root ?: return null, ref) ?: return null
        return runCatching { type to (file.inputStream() as InputStream) }.getOrNull()
    }
}
