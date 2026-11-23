package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailPartDao
import app.sterna.core.data.db.EmailPartEntity
import app.sterna.core.data.db.InlineState
import java.io.File
import java.util.Base64

/**
 * #1869 step 6: a message's inline (`cid:`) images as FILES under `offline-attachments/<account>/<message>/`, next to its
 * offline attachments, instead of base64 `data:` URIs inside the body row. Living in the message's directory they count
 * against the ATTACHMENTS cap (`offlineAttachmentBytes` walks that tree) and are evicted with the message's directory.
 *
 * A stored inline-image map ([MessageBody.inlineImages], `email_bodies.inlineImagesJson`) now holds, per cid, either a
 * `data:` URI (a row stored before v34, or an image held only in memory) or a [ref] to the file. The reader keeps
 * `cid:` in its document for a ref and its WebView serves the file through `shouldInterceptRequest` ([open], which is
 * thread-safe: it reads only its arguments and the file). Export and print, which need self-contained output, turn refs
 * back into `data:` URIs ([selfContained]).
 *
 * NOTHING DECRYPTED IS WRITTEN HERE: [MailRepository] stores files only for mail that is not ciphertext.
 */
object InlineImageFiles {
    /** The directory under `filesDir` that holds offline attachments and inline image files. */
    const val DIR_NAME = "offline-attachments"

    fun rootIn(filesDir: File): File = File(filesDir, DIR_NAME)

    /** The prefix of a file ref: `sterna-inline-file:<mime>;<path relative to offline-attachments>`. */
    const val REF_PREFIX = "sterna-inline-file:"

    /** Inline image files are named `cid-<part key>` so they never collide with an attachment's `<part key>` file. */
    const val FILE_PREFIX = "cid-"

    fun safe(v: String): String = v.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The path, relative to the offline-attachments root, of [partKey]'s inline file in [emailId] of [accountId]. */
    fun relativePath(accountId: String, emailId: String, partKey: String): String =
        "${safe(accountId)}/${safe(emailId)}/$FILE_PREFIX${safe(partKey)}"

    fun ref(type: String, relative: String): String = "$REF_PREFIX${safeType(type)};$relative"

    fun isRef(value: String): Boolean = value.startsWith(REF_PREFIX)

    /** (mime type, relative path) of [value], or null when it is not a ref. */
    fun parseRef(value: String): Pair<String, String>? {
        if (!isRef(value)) return null
        val rest = value.removePrefix(REF_PREFIX)
        val semi = rest.indexOf(';')
        if (semi <= 0 || semi == rest.length - 1) return null
        return rest.substring(0, semi) to rest.substring(semi + 1)
    }

    /** Only an image type is served; anything else (or a header-injection attempt) reads as a plain image. */
    private fun safeType(type: String): String =
        type.trim().lowercase().takeIf { Regex("image/[a-z0-9.+-]+").matches(it) } ?: "image/jpeg"

    /**
     * The file a [ref] names, or null when it is not a ref, the file is missing, or the path leaves [root] (a `..`
     * segment from a hostile id). Blocking, no shared state: safe on the WebView's IO threads.
     */
    fun open(root: File, ref: String): Pair<String, File>? {
        val (type, relative) = parseRef(ref) ?: return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(base, relative).canonicalFile }.getOrNull() ?: return null
        if (!file.path.startsWith(base.path + File.separator)) return null
        return if (file.isFile) type to file else null
    }

    /** `data:` URI bytes and type, or null when [uri] is not a base64 `data:` URI. */
    fun decodeDataUri(uri: String): Pair<String, ByteArray>? {
        if (!uri.startsWith("data:")) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        if (!meta.endsWith(";base64")) return null
        val bytes = runCatching { Base64.getDecoder().decode(uri.substring(comma + 1)) }.getOrNull() ?: return null
        return meta.removeSuffix(";base64").ifBlank { "image/jpeg" } to bytes
    }

    fun dataUri(type: String, bytes: ByteArray): String = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)

    /** [images] with every ref whose file is gone dropped, so the caller sees the cid as missing and fetches it again. */
    fun present(root: File?, images: Map<String, String>): Map<String, String> {
        if (images.values.none(::isRef)) return images
        if (root == null) return images.filterValues { !isRef(it) }
        return images.filterValues { !isRef(it) || open(root, it) != null }
    }

    /** [images] self-contained: every ref read back into a `data:` URI (export, print). A ref whose file is gone is dropped. */
    fun selfContained(root: File?, images: Map<String, String>): Map<String, String> = images.mapNotNull { (cid, v) ->
        if (!isRef(v)) return@mapNotNull cid to v
        val (type, file) = root?.let { open(it, v) } ?: return@mapNotNull null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
        cid to dataUri(type, bytes)
    }.toMap()

    /** Write [bytes] atomically (temp file, then rename) and return the ref, or null when nothing could be written. */
    fun write(root: File, accountId: String, emailId: String, partKey: String, type: String, bytes: ByteArray): String? {
        val relative = relativePath(accountId, emailId, partKey)
        val file = File(root, relative)
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return null
            }
            ref(type, relative)
        }.getOrNull()
    }

    /** The `email_parts` row of one stored inline image. */
    fun partRow(accountId: String, emailId: String, partKey: String, cid: String, type: String, name: String?, ref: String, size: Long) =
        EmailPartEntity(
            accountId = accountId, emailId = emailId, partKey = partKey, cid = cid, type = type, name = name,
            size = size, inline = true, file = parseRef(ref)?.second, fileBytes = size,
        )

    /** The [InlineState] a body stored with [images] has: none wanted, all present, or some still to fetch. */
    fun stateOf(wantedCids: Collection<String>, images: Map<String, String>): Int = when {
        wantedCids.isEmpty() -> InlineState.NONE
        wantedCids.all { it in images } -> InlineState.COMPLETE
        else -> InlineState.PENDING
    }

    /**
     * Lazy upgrade of a row stored before v34: every `data:` URI in [images] becomes a file, its value a ref. Returns the
     * new map and the `email_parts` rows, or null when there was nothing to upgrade or a write failed (the row then stays
     * as it is and still renders).
     */
    fun upgrade(
        root: File,
        accountId: String,
        emailId: String,
        images: Map<String, String>,
        partKeyOf: (cid: String) -> String,
    ): Pair<Map<String, String>, List<EmailPartEntity>>? {
        if (images.values.none { it.startsWith("data:") }) return null
        val out = LinkedHashMap<String, String>()
        val rows = ArrayList<EmailPartEntity>()
        for ((cid, v) in images) {
            val decoded = decodeDataUri(v)
            if (decoded == null) {
                out[cid] = v
                continue
            }
            val key = partKeyOf(cid)
            val ref = write(root, accountId, emailId, key, decoded.first, decoded.second) ?: return null
            out[cid] = ref
            rows += partRow(accountId, emailId, key, cid, decoded.first, null, ref, decoded.second.size.toLong())
        }
        return out to rows
    }

    /** Index the files of [rows]; a missing DAO (tests) indexes nothing. */
    suspend fun index(dao: EmailPartDao?, rows: List<EmailPartEntity>) {
        if (dao == null || rows.isEmpty()) return
        runCatching { dao.upsertAll(rows) }
    }
}
