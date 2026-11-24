package com.diegonmarcos.clouddrive.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val input = android.widget.EditText(this).apply { setText(displayName(s[0]) ?: "file"); setSingleLine() }
        android.app.AlertDialog.Builder(this).setTitle(R.string.share_rename).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> importInto(tree, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
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
                android.app.AlertDialog.Builder(this).setTitle(app.getString(R.string.share_done, ok, failed)).setMessage(lines.joinToString("\n"))
                    .setPositiveButton(android.R.string.ok) { _, _ -> done() }.setOnCancelListener { done() }.show()
            }
        }.start()
    }
}
