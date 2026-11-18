package app.sterna.ui.attachment

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.sterna.R
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.requireAttachmentBytes
import app.sterna.core.data.storage.AttachmentMime
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.jmap.model.EmailBodyPart

/**
 * The ONE path from "the user asked for this file" to "another application has it".
 *
 * It exists because a second surface now offers attachments. The reader has opened them since
 * [app.sterna.ui.message.MessageViewModel.openAttachment], and the message LIST now offers the same
 * files a screen earlier. Copying that method would be copying a FileProvider authority, a grant
 * flag, a filename policy and a MIME decision -- and the copy that drifts is the one that stops
 * granting read permission, or the one that keeps trusting the sender's Content-Type after the other
 * stopped. There is one, and both view models call it.
 *
 * This is not a new escape hatch. This repository moved away from ad-hoc `startActivity(ACTION_VIEW)`
 * on a file, and what it moved TO is exactly the shape below: a `content://` URI from the app's own
 * FileProvider, a read grant scoped to the receiving app, and a CHOOSER rather than an implicit
 * launch. No file path ever leaves this process, and no app is picked on the sender's behalf.
 */
object AttachmentOpen {

        /**
         * Download [part], write it into the attachment cache and hand it to a chooser. Suspends until
         * the chooser is up. THROWS on any failure -- the caller owns how a failure is said, because
         * the reader and the list say it in different places, and a swallowed failure here would be
         * the silent no-op a tap must never be.
         */
    suspend fun openExternally(
        app: Application,
        repo: MailRepository,
        storage: StorageRepository,
        credentials: AccountCredentials,
        part: EmailBodyPart,
        ownerId: String,
    ) {
        val bytes = repo.downloadAttachment(credentials, part, ownerId)
        // The name is the sender's and is reduced to a safe one INSIDE cacheAttachment; the file
        // that comes back is the authority on what was actually written.
        val file = storage.cacheAttachment(part.name, bytes)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW)
            // The type comes from the name THIS APP wrote, through the platform's extension map,
            // and falls back to the sender's claim only when the file says nothing. See
            // [AttachmentMime] for why the claim cannot be the first answer.
            .setDataAndType(uri, AttachmentMime.of(part.type, mimeFromName(file.name)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // unguarded: this hand-off is held back by the caller, not by leaveOnce. Both call
        // sites refuse a second tap while one is in flight (MessageViewModel's
        // `openingAttachment`, InboxViewModel's `_openingAttachment`), and the guard has to
        // sit THERE: it must also cover the download this function suspends on, which starts
        // long before there is an activity to leave for.
        app.startActivity(
            Intent.createChooser(view, app.getString(R.string.status_open_attachment))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

        /**
         * Hand an export [file] (already in the app's cache, under the FileProvider's `exports/`) to the
         * share sheet. The ONE other way out with a file next to [openExternally]: a content:// URI with
         * a read grant, through a chooser.
         */
    fun shareFile(app: Application, file: java.io.File, mime: String, subject: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // unguarded: this hand-off is held back by the caller, not by leaveOnce. The only caller is
        // MessageExporter.start, which refuses a second export while one is in flight
        // (`if (job?.isActive == true) return`), and that latch has to sit THERE: it also covers the
        // file being built, which runs long before there is a share sheet to leave for.
        app.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

        /** The platform's own extension→type map, asked about the name on disk. Lowercased because
         *  the map is case sensitive and `REPORT.PDF` is a pdf. */
    private fun mimeFromName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

        /**
         * Whether this device's active network charges for what it carries. FAIL-SAFE: an unknown
         * network, a null service and any exception all count as metered, which is the same rule the
         * fleet's auto-update gate chose -- being wrong towards "ask first" costs a dialog, being
         * wrong the other way costs the owner's data allowance.
         */
    /**
     * #1800 the content:// target the fleet camera writes a QR photo into (ACTION_IMAGE_CAPTURE), from
     * this app's own cache: the one FileProvider door, used here for a file coming IN.
     */
    fun cameraCaptureUri(context: Context, name: String): android.net.Uri {
        val dir = java.io.File(context.cacheDir, "attachments").apply { mkdirs() }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(dir, name))
    }

    fun isMetered(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        // `isActiveNetworkMetered` and not a capability check: it already honours the user's own
        // "this wi-fi is metered" marking, which is the answer they actually want respected.
        manager.isActiveNetworkMetered
    }.getOrDefault(true)

    /**
     * Task #461 — the bytes of an image attachment, brought to the cache by the ONE
     * cache path and returned as a [java.io.File] the caller can read from.
     *
     * The scan feature needs the part's bytes as a FILE only because the shared
     * image-scan engine reads a file/bitmap, and the cache write must not grow a
     * second home: SaveAttachmentCallSiteTest pins "MessageViewModel must not
     * cache an attachment at all" and the open path owns that write. Same
     * download, same SafeFileName, same cap as [openExternally]; nothing is
     * launched and no URI is exposed. THROWS on any failure -- the caller owns
     * how a failure is said.
     */
    suspend fun cacheForScan(
        app: Application,
        repo: MailRepository,
        storage: StorageRepository,
        credentials: AccountCredentials,
        part: EmailBodyPart,
        ownerId: String,
    ): java.io.File {
        val bytes = repo.downloadAttachment(credentials, part, ownerId)
        requireAttachmentBytes(ownerId, part.partId ?: part.blobId ?: "", bytes)
        return storage.cacheAttachment(part.name, bytes)
    }
}
