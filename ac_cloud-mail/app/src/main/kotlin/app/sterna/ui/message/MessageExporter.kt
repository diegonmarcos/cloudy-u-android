package app.sterna.ui.message

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.sterna.R
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.ui.attachment.AttachmentOpen
import app.sterna.export.ExportCancelled
import app.sterna.export.ExportFormat
import app.sterna.export.ExportLabels
import app.sterna.export.ExportProgress
import app.sterna.export.buildMarkdown
import app.sterna.export.buildSelfContainedHtml
import app.sterna.export.exportFileName
import app.sterna.export.mboxFromLine
import app.sterna.export.writeAttachmentsZip
import app.sterna.export.writeEml
import app.sterna.export.writeMbox
import app.sterna.export.writeMhtml
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export...": one job at a time, owned by a reader page. [start] writes [format] of the message into a
 * SAF document the user picked (any provider), or - [uri] null - into the app's export cache and then
 * the share sheet. Progress is [state]; [cancel] stops it, and a cancelled or failed job leaves no
 * empty document or cache file behind. Builders stream: nothing here holds an attachment past its turn.
 */
internal class MessageExporter(private val app: Application, private val repo: MailRepository) {
    private val _state = MutableStateFlow<ExportUiState?>(null)
    val state: StateFlow<ExportUiState?> = _state.asStateFlow()
    private var job: Job? = null

    fun cancel() { job?.cancel() }

    /** [say] receives the one-line outcome (saved / cancelled / the clear failure sentence). */
    fun start(
        scope: CoroutineScope,
        format: ExportFormat,
        uri: Uri?,
        msg: ThreadMessage?,
        credentials: AccountCredentials?,
        say: (String) -> Unit,
    ) {
        if (job?.isActive == true) return
        job = scope.launch {
            var shareFile: File? = null
            suspend fun cleanUp() = withContext(NonCancellable + Dispatchers.IO) {
                shareFile?.delete()
                if (uri != null) runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
            }
            try {
                if (msg == null) {
                    cleanUp()
                    say(app.getString(R.string.status_action_failed))
                    return@launch
                }
                val creds = credentials ?: error(app.getString(R.string.status_no_saved_account))
                val email = msg.body ?: msg.header
                val subject = (email.subject ?: msg.header.subject)?.takeIf { it.isNotBlank() }
                    ?: app.getString(R.string.message_no_subject)
                val name = exportFileName(msg.header.receivedAt, subject, format.ext)
                _state.value = ExportUiState(format)
                if (uri == null) {
                    val dir = File(app.cacheDir, "exports").also { it.mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    shareFile = File(dir, name)
                }
                // #1869 step 6: inline images stored as files are read back into data: URIs, so the export stays self-contained.
                val selfContained = msg.copy(inlineImages = repo.selfContainedInlineImages(msg.inlineImages))
                val exportMsg = exportMessageOf(selfContained, email, subject, app.getString(R.string.message_no_content), repo, creds)
                val labels = ExportLabels(
                    from = app.getString(R.string.participants_from),
                    to = app.getString(R.string.participants_to),
                    cc = app.getString(R.string.participants_cc),
                    date = app.getString(R.string.compose_forward_date),
                    attachments = app.getString(R.string.export_md_attachments),
                )
                val raw = if (format == ExportFormat.EML || format == ExportFormat.MBOX) repo.rawSource(creds, msg.id) else null
                val running = currentCoroutineContext()[Job]
                val cancelled = { running?.isActive == false }
                val progress = ExportProgress { d, t -> _state.value = ExportUiState(format, d, t) }
                withContext(Dispatchers.IO) {
                    val out = if (uri != null) {
                        app.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
                    } else {
                        FileOutputStream(shareFile!!)
                    }
                    out.use { o ->
                        when (format) {
                            ExportFormat.MHTML -> writeMhtml(o, exportMsg, labels, progress = progress, isCancelled = cancelled)
                            ExportFormat.ATTACHMENTS_ZIP ->
                                writeAttachmentsZip(o, exportMsg.parts.filter { !it.inline }, progress, cancelled)
                            ExportFormat.HTML -> o.write(
                                buildSelfContainedHtml(exportMsg, labels, selfContained.inlineImages).toByteArray(Charsets.UTF_8),
                            )
                            ExportFormat.EML -> writeEml(o, raw!!)
                            ExportFormat.MBOX -> writeMbox(o, listOf(mboxFromLine(msg.header.receivedAt) to raw!!), cancelled)
                            ExportFormat.MARKDOWN -> o.write(buildMarkdown(exportMsg, labels).toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                if (uri != null) {
                    say(app.getString(R.string.message_export_saved, displayNameOf(uri, name)))
                } else {
                    AttachmentOpen.shareFile(app, shareFile!!, format.mime, subject, app.getString(R.string.message_export_share))
                }
            } catch (c: CancellationException) {
                cleanUp()
                say(app.getString(R.string.message_export_cancelled))
                throw c
            } catch (e: ExportCancelled) {
                cleanUp()
                say(app.getString(R.string.message_export_cancelled))
            } catch (e: ContentTooLargeException) {
                cleanUp()
                say(app.getString(R.string.status_attachment_too_large))
            } catch (t: Throwable) {
                cleanUp()
                say(readFailureStringRes(t)?.let { app.getString(it) } ?: app.getString(R.string.message_export_failed, t.message ?: "error"))
            } finally {
                _state.value = null
            }
        }
    }

    /** The document's name as its provider reports it (it may have been renamed in the picker), else [fallback]. */
    private suspend fun displayNameOf(uri: Uri, fallback: String): String = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }
}
