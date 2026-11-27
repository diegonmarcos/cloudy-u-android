package app.cloudnotes.pro

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.diegonmarcos.cloudlib.pdf.PdfViewer
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * #1949 Cloud Notes' own PDF reader and editor (libs:pdf: view, draw, note, text, rotate, move, delete, form fill). The incoming
 * stream is copied into the app cache FIRST, so a temporary read grant (Gmail, any FileProvider) can lapse without breaking the
 * screen. Save writes back to the source URI when it is writable, else saves a copy to Downloads. An unreadable or non-PDF file
 * shows the reason and an Open with action, never a blank screen.
 */
class PdfViewActivity : ComponentActivity() {
    private sealed interface PdfState {
        data object Loading : PdfState
        data class Ready(val file: File) : PdfState
        data class Failed(val reason: String) : PdfState
    }

    private var state by mutableStateOf<PdfState>(PdfState.Loading)
    private var source: Uri? = null
    private var name = "document.pdf"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent?.data
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
        val u = source
        if (u == null) { state = PdfState.Failed("No file was given."); return }
        name = queryName(u)
        title = name
        Thread {
            state = try { PdfState.Ready(spool(u)) } catch (e: Throwable) {
                PdfState.Failed("Cannot read this PDF (${e.message ?: e.javaClass.simpleName}).")
            }
        }.start()
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(name, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("pdf_title"), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val ready = state is PdfState.Ready
                TextButton(onClick = { save() }, enabled = ready, modifier = Modifier.testTag("pdf_save")) { Text("Save") }
                TextButton(onClick = { openWithOther() }, modifier = Modifier.testTag("pdf_open_with")) { Text("Open with") }
                TextButton(onClick = { openInDrive() }) { Text("Open in Cloud Drive") }
                TextButton(onClick = { attachToNote() }, enabled = ready) { Text("Attach to note") }
            }
            when (val s = state) {
                PdfState.Loading -> Text("Opening…", Modifier.padding(12.dp))
                is PdfState.Failed -> Text(s.reason, Modifier.padding(12.dp).testTag("pdf_error_reason"))
                is PdfState.Ready -> PdfViewer(s.file, editing = true, modifier = Modifier.weight(1f))
            }
        }
    }

    private fun queryName(u: Uri): String {
        var n: String? = null
        if (u.scheme == "content") {
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) n = c.getString(0)?.takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {}
        }
        val base = n ?: u.lastPathSegment?.substringAfterLast('/') ?: "document"
        return if (base.lowercase().endsWith(".pdf")) base else "$base.pdf"
    }

    /** The grant-independent copy the viewer and the edits work on. */
    private fun spool(u: Uri): File {
        val dir = File(cacheDir, "pdf-view").apply { mkdirs() }
        val out = File(dir, System.nanoTime().toString() + "-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        (contentResolver.openInputStream(u) ?: throw IOException("the provider returned no stream")).use { i ->
            out.outputStream().use { o -> i.copyTo(o) }
        }
        val head = out.inputStream().use { OpenRouter.readHead(it, 5) }
        if (!OpenRouter.isPdfMagic(head)) { out.delete(); throw IOException("it is not a PDF file") }
        return out
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    /** Back to the source when writable, otherwise a copy in Downloads. */
    private fun save() {
        val f = (state as? PdfState.Ready)?.file ?: return
        val u = source ?: return
        Thread {
            try {
                val bytes = f.readBytes()
                val wrote = try {
                    if (u.scheme == "file") { File(u.path ?: "").writeBytes(bytes); true }
                    else contentResolver.openOutputStream(u, "wt")?.use { it.write(bytes); true } ?: false
                } catch (_: Exception) { false }
                if (wrote) toast("Saved to the original file.") else toast("Saved a copy: " + saveCopy(bytes))
            } catch (e: Exception) { toast("Cannot save (${e.message ?: "error"}).") }
        }.start()
    }

    private fun saveCopy(bytes: ByteArray): String {
        val base = name.removeSuffix(".pdf") + "-edited.pdf"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, base)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no Downloads")
            contentResolver.openOutputStream(dest)?.use { it.write(bytes) } ?: throw IOException("no stream")
            return "Downloads/$base"
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        File(dir, base).writeBytes(bytes)
        return File(dir, base).path
    }

    private fun shareUri(): Uri? {
        val f = (state as? PdfState.Ready)?.file
        return if (f != null) FileProvider.getUriForFile(this, "$packageName.fileprovider", f) else source
    }

    private fun openWithOther() {
        val u = try { shareUri() } catch (_: Exception) { source } ?: return
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

    private fun openInDrive() {
        val u = try { shareUri() } catch (_: Exception) { source } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf")
                .setPackage("com.diegonmarcos.clouddrive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Cloud Drive is not installed.") }
    }

    /** A new note naming the PDF, with the file inlined as a data: link up to 512 KiB (same shape as the file view's attach). */
    private fun attachToNote() {
        val f = (state as? PdfState.Ready)?.file ?: return
        Thread {
            try {
                val bytes = f.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val sb = StringBuilder("# $name\n\n- Type: application/pdf\n- Size: ${OpenRouter.humanSize(bytes.size.toLong())}\n- SHA-256: $hash\n\n")
                if (bytes.size <= 512 * 1024) sb.append("[$name](data:application/pdf;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)})\n")
                else sb.append("The file is larger than 512 KiB and is not embedded.\n")
                app.cloudnotes.pro.plugin.ExternalFilePlugin.offerNote("$name.md", sb.toString())
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
