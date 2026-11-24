package com.diegonmarcos.clouddrive.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.MainActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.usb.UsbDriveActivity
import java.io.File

/**
 * #1888 "Save to..." in the share sheet: ACTION_SEND / SEND_MULTIPLE from another app. No screen of its own: it opens the system folder picker
 * (the same OpenDocumentTree flow Drive already uses for volume grants), copies every shared stream (or the shared text) into the
 * chosen folder, then opens Drive's Files tab on it (MainActivity + EXTRA_OPEN_PATH, as the USB drive hand-off does).
 */
class ShareReceiverActivity : ComponentActivity() {

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) finish() else askName(tree)
    }

    /** The rename ask (one shared file) and the per-item result, both Compose dialogs. */
    private var renameFor by mutableStateOf<Uri?>(null)
    private var renameText by mutableStateOf("")
    private var result by mutableStateOf<Triple<String, String, () -> Unit>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Dialogs() } }
        if (savedInstanceState != null) return
        if (ShareImport.plan(intent.action, streams().map { it.toString() }, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()).isEmpty()) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, R.string.share_pick_folder, Toast.LENGTH_SHORT).show()
        // default to the last folder used; the system picker lists every root (local storage, the terminals' and Drive's providers, cloud remotes)
        val last = ShareImport.initialTree(getSharedPreferences("share_save_to", MODE_PRIVATE).getString("last_tree", null))
        pickFolder.launch(last?.let { Uri.parse(it) })
    }

    /** One shared file: the user may rename it before it is saved; everything else goes straight to the import. */
    private fun askName(tree: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        getSharedPreferences("share_save_to", MODE_PRIVATE).edit().putString("last_tree", tree.toString()).apply()
        val s = streams()
        if (!ShareImport.canRename(s.size)) { importInto(tree, null); return }
        renameText = displayName(s[0]) ?: "file"
        renameFor = tree
    }

    @Suppress("DEPRECATION")
    private fun streams(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else -> emptyList()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    private fun importInto(tree: Uri, rename: String?) {
        val src = intent
        val streams = streams()
        val text = src.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = src.getStringExtra(Intent.EXTRA_SUBJECT)
        val app = applicationContext
        Thread {
            val treeDocId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
            val taken = HashSet<String>()
            runCatching {
                contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) taken.add(c.getString(0))
                }
            }
            val folder = ShareImport.docIdToPath(treeDocId)
            var first: String? = null
            var ok = 0
            var failed = 0
            val lines = ArrayList<String>()
            fun create(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
                val unique = ShareImport.uniqueName(ShareImport.finalName(name, rename), taken)
                val doc = runCatching { DocumentsContract.createDocument(contentResolver, parent, mime, unique) }.getOrNull()
                val done = doc != null && runCatching { contentResolver.openOutputStream(doc)?.use { write(it) } != null }.getOrDefault(false)
                lines.add(ShareImport.resultLine(unique, done))
                if (done) { ok++; taken.add(unique); if (first == null && folder != null) first = File(folder, unique).absolutePath } else failed++
            }
            if (streams.isNotEmpty()) {
                for (u in streams) {
                    val mime = contentResolver.getType(u) ?: "application/octet-stream"
                    create(displayName(u) ?: "file", mime) { out -> contentResolver.openInputStream(u)?.use { it.copyTo(out) } ?: error("unreadable") }
                }
            } else if (!text.isNullOrBlank()) {
                create(ShareImport.textName(subject, text), "text/plain") { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            runOnUiThread {
                val open = first ?: folder
                val done = {
                    if (open != null) startActivity(Intent(this, MainActivity::class.java).putExtra(UsbDriveActivity.EXTRA_OPEN_PATH, open).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }
                result = Triple(app.getString(R.string.share_done, ok, failed), lines.joinToString("\n"), done)
            }
        }.start()
    }

    @androidx.compose.runtime.Composable
    private fun Dialogs() {
        val tree = renameFor
        if (tree != null) AlertDialog(
            onDismissRequest = { renameFor = null; finish() },
            title = { Text(stringResource(R.string.share_rename)) },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renameFor = null; importInto(tree, renameText) }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renameFor = null; finish() }) { Text(stringResource(android.R.string.cancel)) } },
        )
        val r = result
        if (r != null) AlertDialog(
            onDismissRequest = { result = null; r.third() },
            title = { Text(r.first) },
            text = { Column { Text(r.second) } },
            confirmButton = { TextButton(onClick = { result = null; r.third() }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
