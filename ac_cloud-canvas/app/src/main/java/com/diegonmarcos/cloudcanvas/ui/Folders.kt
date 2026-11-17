package com.diegonmarcos.cloudcanvas.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/** The export folder the user chose through the Storage Access Framework (one per kind), and the writes into it. No storage permission is declared. */
object Folders {
    private const val PREFS = "canvas_folders"

    fun saved(ctx: Context, kind: String): Uri? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind, null)?.let(Uri::parse)

    /** Keeps the grant of a tree picked with ACTION_OPEN_DOCUMENT_TREE across restarts. */
    fun remember(ctx: Context, kind: String, tree: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kind, tree.toString()).apply()
    }

    /** Writes [bytes] as [name] into the chosen folder; the file's Uri, or null when the folder is gone or refuses. */
    fun write(ctx: Context, tree: Uri, name: String, mime: String, bytes: ByteArray): Uri? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name) ?: return null
        ctx.contentResolver.openOutputStream(doc)?.use { it.write(bytes) } ?: return null
        doc
    }.getOrNull()

    /** The share sheet for a PNG: the bytes go to the cache, a FileProvider Uri carries them. */
    fun share(ctx: Context, bytes: ByteArray, name: String, mime: String): Intent? = runCatching {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        )
    }.getOrNull()
}
