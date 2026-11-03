package com.diegonmarcos.superapp.appstore.ventoy

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * #1405 [DriveFs] over the per-volume SAF grant (StorageVolume.createOpenDocumentTreeIntent, libs:usb-drive
 * StorageVolumes.grantIntent): no all-files access, and only the ROOT of a removable volume is accepted
 * ([VentoyTarget.isDriveTree]), so this class cannot be pointed at the phone's own storage.
 */
class SafDriveFs(private val cr: ContentResolver, private val tree: Uri) : DriveFs {

    init {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        if (!VentoyTarget.isDriveTree(tree.authority, treeId)) throw SecurityException("not the root of a removable drive: $tree")
    }

    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }

    private fun child(parentId: String, name: String): Pair<String, Boolean>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0) to (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
        }
        return null
    }

    private fun resolve(path: String): Pair<String, Boolean>? {
        var id = rootId; var dir = true
        for (s in segments(path)) { if (!dir) return null; val (cid, cdir) = child(id, s) ?: return null; id = cid; dir = cdir }
        return id to dir
    }

    private fun doc(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun exists(path: String) = resolve(path) != null

    override fun list(dir: String): List<String> {
        val (id, isDir) = resolve(dir) ?: return emptyList()
        if (!isDir) return emptyList()
        val out = ArrayList<String>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) out += c.getString(0) + if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) "/" else ""
        }
        return out
    }

    override fun mkdirs(dir: String) {
        var id = rootId
        for (s in segments(dir)) {
            id = child(id, s)?.let { (cid, isDir) -> if (!isDir) throw FileNotFoundException("$s is a file"); cid }
                ?: DocumentsContract.createDocument(cr, doc(id), DocumentsContract.Document.MIME_TYPE_DIR, s)?.let { DocumentsContract.getDocumentId(it) }
                ?: throw FileNotFoundException("cannot create $dir")
        }
    }

    override fun write(path: String, body: (OutputStream) -> Unit) {
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        mkdirs(parent)
        val (pid, _) = resolve(parent) ?: throw FileNotFoundException(parent)
        val uri = child(pid, name)?.let { doc(it.first) }
            ?: DocumentsContract.createDocument(cr, doc(pid), "application/octet-stream", name)
            ?: throw FileNotFoundException("cannot create $path")
        // A provider may rename on create ("x (1).iso"); the file we write must carry the name we asked for.
        val got = cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        if (got != null && got != name) { DocumentsContract.deleteDocument(cr, uri); throw FileNotFoundException("the drive renamed $name to $got") }
        (cr.openOutputStream(uri, "wt") ?: throw FileNotFoundException(path)).use { body(it); it.flush() }
    }

    override fun readText(path: String): String? {
        val (id, isDir) = resolve(path) ?: return null
        if (isDir) return null
        return cr.openInputStream(doc(id))?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun rename(from: String, toName: String) {
        val (id, _) = resolve(from) ?: throw FileNotFoundException(from)
        DocumentsContract.renameDocument(cr, doc(id), toName) ?: throw FileNotFoundException("cannot rename $from")
    }

    override fun delete(path: String): Boolean {
        if (segments(path).isEmpty()) return false
        val (id, _) = resolve(path) ?: return false
        return runCatching { DocumentsContract.deleteDocument(cr, doc(id)) }.getOrDefault(false)
    }
}
