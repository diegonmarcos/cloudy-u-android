package app.cloudnotes.pro

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.diegonmarcos.cloudlib.pdf.PdfEngine
import com.diegonmarcos.cloudlib.pdf.PdfReaderActivityBase
import com.diegonmarcos.cloudlib.pdf.ReaderAction
import java.security.MessageDigest

/**
 * #1949 Cloud Notes' PDF reader and editor: the SAME full reader as Cloud Drive (libs:pdf PdfReaderActivityBase: continuous and paged
 * scrolling, pinch zoom, go to page, search, outline, night mode, share, print, and the Edit toggle that opens the PdfBox editor), plus
 * Notes' own More-menu entries: Open with, Open in Cloud Drive and Attach to note. The incoming stream is opened from its URI on a
 * worker thread; the editor works on a private copy, so a temporary read grant (Gmail, any FileProvider) cannot lapse under it. Save
 * writes back to the source URI when it is writable, else saves a copy to Downloads. An unreadable or non-PDF file shows the reason.
 */
class PdfViewActivity : PdfReaderActivityBase() {

    override fun Modifier.screenInsets(): Modifier = this.systemBarsPadding().displayCutoutPadding()

    override val prefsName: String get() = "cloud-notes-pdf-reader"

    override fun extraActions(): List<ReaderAction> = listOf(
        ReaderAction("Open with", "pdf_open_with") { openWithOther() },
        ReaderAction("Open in Cloud Drive", "pdf_open_in_drive") { openInDrive() },
        ReaderAction("Attach to note", "pdf_attach") { attachToNote() },
    )

    private fun copyUri(): android.net.Uri? = try {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", PdfEngine.spool(this, source))
    } catch (_: Exception) { source }

    private fun openWithOther() {
        Thread {
            val u = copyUri() ?: return@Thread
            runOnUiThread {
                try {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val own = packageManager.queryIntentActivities(view, 0)
                        .filter { it.activityInfo.packageName == packageName }
                        .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                    val chooser = Intent.createChooser(view, "Open with")
                    if (own.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
                    startActivity(chooser)
                } catch (e: Exception) { toast("No app can open this file.") }
            }
        }.start()
    }

    private fun openInDrive() {
        Thread {
            val u = copyUri() ?: return@Thread
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf")
                        .setPackage("com.diegonmarcos.clouddrive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) { toast("Cloud Drive is not installed.") }
            }
        }.start()
    }

    /** A new note naming the PDF, with the file inlined as a data: link up to 512 KiB (same shape as the file view's attach). */
    private fun attachToNote() {
        Thread {
            try {
                val bytes = PdfEngine.spool(this, source).readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val sb = StringBuilder("# $displayName\n\n- Type: application/pdf\n- Size: ${OpenRouter.humanSize(bytes.size.toLong())}\n- SHA-256: $hash\n\n")
                if (bytes.size <= 512 * 1024) sb.append("[$displayName](data:application/pdf;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)})\n")
                else sb.append("The file is larger than 512 KiB and is not embedded.\n")
                app.cloudnotes.pro.plugin.ExternalFilePlugin.offerNote("$displayName.md", sb.toString())
                runOnUiThread {
                    startActivity(Intent(app.cloudnotes.pro.plugin.ExternalFilePlugin.ACTION_NEW_NOTE).setClass(this, MainActivity::class.java))
                    finish()
                }
            } catch (e: Exception) { toast("Cannot attach (${e.message ?: "unreadable"}).") }
        }.start()
    }

    // #1949 trace
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) { LifecycleProbe.call(this, "startActivity", intent); super.startActivityForResult(intent, requestCode, options) }
    override fun navigateUpTo(upIntent: Intent): Boolean { LifecycleProbe.call(this, "navigateUpTo", upIntent); return super.navigateUpTo(upIntent) }
    override fun finish() { LifecycleProbe.call(this, "finish"); super.finish() }
}
