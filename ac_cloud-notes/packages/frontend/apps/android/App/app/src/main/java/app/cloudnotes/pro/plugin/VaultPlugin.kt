package app.cloudnotes.pro.plugin

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.diegonmarcos.superapp.perms.PermsGate
import com.diegonmarcos.superapp.perms.PermsLink
import java.io.File

/**
 * The Vault view's folder access (build.json::vault, web half core/src/mobile/vault).
 *
 * A vault root is one of three things, told apart by its first characters:
 *  - `content://...`  a SAF tree the user picked (ACTION_OPEN_DOCUMENT_TREE); the grant is
 *    persisted, so the vault opens again after a restart with no prompt;
 *  - `/storage/emulated/0/...`  a direct path (same All-files access as the file explorer);
 *  - `store:<relative path>`  a folder in the fleet shared store, reached through its
 *    ContentProvider (this app is not on the store's shared uid). Unverified on a device.
 * Entries are addressed by an opaque id the listing returned (SAF document id, path relative
 * to the root), never by a path the web side made up; the web adapter (vault-fs.ts) keeps the
 * path to id map.
 */
@CapacitorPlugin(name = "Vault")
class VaultPlugin : Plugin() {

    companion object {
        /** build.json::vault.open.direct_roots */
        private val DIRECT_ROOTS = listOf("/storage/emulated/0/")
        /** build.json::vault.open.store.authority */
        private const val STORE_AUTHORITY = "cld.termux.sharedstore"
        /** The app that owns the store provider: its grants are this vault's helper grants (build.json::perms.helpers). */
        private const val STORE_OWNER = "cld.termux"
        /** build.json::vault.limits.max_text_bytes */
        private const val MAX_TEXT = 5L * 1024 * 1024
        private const val MAX_BINARY = 25L * 1024 * 1024

        // #1717 build.json::vault.sources.roots: the roots the sidebar's Source section offers (ids as declared).
        private const val ROOT_EMULATED = "emulated"
        private const val ROOT_TERMUX = "termux"
        private const val ROOT_NIX = "nix"
        private const val ROOT_EXTERNAL = "external"
        /** The apps that own the Termux and Nix homes; a folder granted through their documents provider mounts the home. */
        private const val TERMUX_PACKAGE = "cld.termux"
        private const val NIX_PACKAGE = "cld.termux.nix"
        private const val EXTERNAL_DOCUMENTS = "com.android.externalstorage.documents"
        // Why a root cannot be browsed: the names Cloud Drive's EditorRoots.Reason uses.
        private const val NO_STORAGE_GRANT = "NO_STORAGE_GRANT"
        private const val APP_NOT_INSTALLED = "APP_NOT_INSTALLED"
        private const val NOT_READABLE = "NOT_READABLE"
        private const val NO_DRIVE = "NO_DRIVE"
    }

    private class Fail(val code: String, message: String) : Exception(message)

    private interface Backend {
        fun list(id: String): List<JSObject>
        fun open(id: String): java.io.InputStream
        fun sizeOf(id: String): Long
        fun write(id: String, bytes: ByteArray)
        fun create(parentId: String, name: String, isDir: Boolean): String
        fun rename(id: String, name: String): String
        fun remove(id: String)
    }

    private fun entry(name: String, id: String, isDir: Boolean, size: Long, mtime: Long) =
        JSObject().put("name", name).put("id", id).put("isDir", isDir).put("size", size).put("mtime", mtime)

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    // ---------------------------------------------------------------- SAF

    private inner class Saf(root: String) : Backend {
        private val tree = Uri.parse(root)
        private fun doc(id: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(tree, id.ifEmpty { DocumentsContract.getTreeDocumentId(tree) })

        override fun list(id: String): List<JSObject> {
            val docId = id.ifEmpty { DocumentsContract.getTreeDocumentId(tree) }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val out = ArrayList<JSObject>()
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        entry(
                            c.getString(1) ?: continue,
                            c.getString(0),
                            c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            if (c.isNull(3)) 0L else c.getLong(3),
                            if (c.isNull(4)) 0L else c.getLong(4),
                        )
                    )
                }
            }
            return out
        }

        override fun open(id: String) =
            context.contentResolver.openInputStream(doc(id)) ?: throw Fail("READ_ERROR", "Cannot open the document.")

        override fun sizeOf(id: String): Long {
            context.contentResolver.query(doc(id), arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
            return 0L
        }

        override fun write(id: String, bytes: ByteArray) {
            val out = context.contentResolver.openOutputStream(doc(id), "wt")
                ?: throw Fail("WRITE_ERROR", "Cannot open the document for writing.")
            out.use { it.write(bytes) }
        }

        override fun create(parentId: String, name: String, isDir: Boolean): String {
            val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else mimeFor(name)
            val made = DocumentsContract.createDocument(context.contentResolver, doc(parentId), mime, name)
                ?: throw Fail("WRITE_ERROR", "The folder does not accept new files.")
            return DocumentsContract.getDocumentId(made)
        }

        override fun rename(id: String, name: String): String {
            val renamed = DocumentsContract.renameDocument(context.contentResolver, doc(id), name)
                ?: throw Fail("WRITE_ERROR", "Rename is not supported here.")
            return DocumentsContract.getDocumentId(renamed)
        }

        override fun remove(id: String) {
            if (!DocumentsContract.deleteDocument(context.contentResolver, doc(id))) {
                throw Fail("WRITE_ERROR", "Delete failed.")
            }
        }
    }

    // ---------------------------------------------------------------- direct path

    private inner class Direct(private val root: String) : Backend {
        private fun file(id: String): File {
            val base = File(root).canonicalFile
            val f = if (id.isEmpty()) base else File(base, id).canonicalFile
            if (f != base && !f.path.startsWith(base.path + File.separator)) {
                throw Fail("OUTSIDE_VAULT", "That path leaves the vault.")
            }
            return f
        }

        override fun list(id: String): List<JSObject> {
            val base = file(id)
            if (!base.isDirectory) throw Fail("NOT_A_DIRECTORY", "Not a folder.")
            return (base.listFiles() ?: throw Fail("READ_ERROR", "Cannot list the folder.")).map {
                entry(it.name, if (id.isEmpty()) it.name else "$id/${it.name}", it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified())
            }
        }

        override fun open(id: String) = file(id).inputStream()
        override fun sizeOf(id: String) = file(id).length()
        override fun write(id: String, bytes: ByteArray) = file(id).writeBytes(bytes)

        override fun create(parentId: String, name: String, isDir: Boolean): String {
            if (name.contains('/') || name == ".." || name == ".") throw Fail("BAD_NAME", "Bad file name.")
            val id = if (parentId.isEmpty()) name else "$parentId/$name"
            val f = file(id)
            if (f.exists()) throw Fail("EXISTS", "$name already exists.")
            if (isDir) f.mkdirs() else f.createNewFile()
            return id
        }

        override fun rename(id: String, name: String): String {
            if (name.contains('/')) throw Fail("BAD_NAME", "Bad file name.")
            val from = file(id)
            val newId = id.substringBeforeLast('/', "").let { if (it.isEmpty()) name else "$it/$name" }
            if (!from.renameTo(file(newId))) throw Fail("WRITE_ERROR", "Rename failed.")
            return newId
        }

        override fun remove(id: String) {
            if (id.isEmpty() || !file(id).deleteRecursively()) throw Fail("WRITE_ERROR", "Delete failed.")
        }
    }

    // ---------------------------------------------------------------- shared store (provider)

    private inner class Store(root: String) : Backend {
        private val base = root.removePrefix("store:").trim('/')
        private fun rel(id: String) = listOf(base, id).filter { it.isNotEmpty() }.joinToString("/")
        private fun uri(id: String): Uri = Uri.parse("content://$STORE_AUTHORITY/tree/" + Uri.encode(rel(id), "/"))

        override fun list(id: String): List<JSObject> {
            val out = ArrayList<JSObject>()
            context.contentResolver.query(uri(id), null, null, null, null)?.use { c ->
                val iName = c.getColumnIndex("name")
                val iDir = c.getColumnIndex("is_dir")
                val iSize = c.getColumnIndex("size")
                val iMod = c.getColumnIndex("last_modified")
                if (iName < 0) throw Fail("READ_ERROR", "The store did not answer with a listing.")
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    out.add(
                        entry(
                            name,
                            if (id.isEmpty()) name else "$id/$name",
                            iDir >= 0 && c.getInt(iDir) != 0,
                            if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L,
                            if (iMod >= 0 && !c.isNull(iMod)) c.getLong(iMod) else 0L,
                        )
                    )
                }
            } ?: throw Fail("STORE_UNAVAILABLE", "The shared store is not reachable. Install Cloud Terminal and open it once.")
            return out
        }

        override fun open(id: String) =
            context.contentResolver.openInputStream(uri(id)) ?: throw Fail("READ_ERROR", "Cannot open the file.")

        override fun sizeOf(id: String): Long {
            context.contentResolver.query(uri(id), null, null, null, null)?.use { c ->
                val i = c.getColumnIndex("size")
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) return c.getLong(i)
            }
            return 0L
        }

        override fun write(id: String, bytes: ByteArray) {
            val out = context.contentResolver.openOutputStream(uri(id), "wt")
                ?: throw Fail("WRITE_ERROR", "Cannot open the file for writing.")
            out.use { it.write(bytes) }
        }

        override fun create(parentId: String, name: String, isDir: Boolean): String {
            if (name.contains('/') || name == ".." || name == ".") throw Fail("BAD_NAME", "Bad file name.")
            val id = if (parentId.isEmpty()) name else "$parentId/$name"
            if (isDir) {
                val r = context.contentResolver.call(Uri.parse("content://$STORE_AUTHORITY"), "mkdir", rel(id), null)
                if (r == null) throw Fail("WRITE_ERROR", "The store did not create the folder.")
            } else {
                write(id, ByteArray(0))
            }
            return id
        }

        override fun rename(id: String, name: String): String =
            throw Fail("NOT_SUPPORTED", "Rename is not available on the store route yet.")

        override fun remove(id: String) {
            if (id.isEmpty() || context.contentResolver.delete(uri(id), null, null) < 1) throw Fail("WRITE_ERROR", "Delete failed.")
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun backend(root: String): Backend = when {
        root.startsWith("content://") -> Saf(root)
        root.startsWith("store:") -> Store(root)
        else -> {
            val canon = try { File(root).canonicalPath } catch (e: Exception) { throw Fail("READ_ERROR", "Cannot resolve $root") }
            // #1717 a mounted removable volume (Source > External drive) is a direct root too
            val allowed = DIRECT_ROOTS + externalVolumes().map { it.second.path }
            if (allowed.none { "$canon/".startsWith(runCatching { File(it).canonicalPath }.getOrDefault(it).trimEnd('/') + "/") }) {
                throw Fail("OUTSIDE_EMULATED_STORAGE", "A vault path must be inside /storage/emulated/0/ or on a connected external drive.")
            }
            if (!hasAllFilesAccess()) {
                throw Fail("PERMISSION_DENIED", "All-files access is not granted. Open Settings, Apps, Cloud Notes, All files access, Allow.")
            }
            Direct(canon)
        }
    }

    /** Runs [body] for a call and turns every failure into a reject with a code and a message. */
    private fun guarded(call: PluginCall, body: (root: String, b: Backend) -> Unit) {
        val root = call.getString("root")?.trim().orEmpty()
        if (root.isEmpty()) return call.reject("No vault was given.", "NO_ROOT")
        try {
            body(root, backend(root))
        } catch (e: Exception) {
            // #1360 a missing grant is named, never a generic error: the store route runs in cld.termux (its All files
            // access), a plain path needs this app's own. The Perms page opens on the missing rows (the in-place prompt).
            val missing = runCatching {
                if (root.startsWith("store:")) PermsGate.prompt(context, emptyList(), STORE_OWNER, "vault")
                else if (!root.startsWith("content:")) PermsGate.prompt(context, listOf("all_files"), null, "vault")
                else null
            }.getOrNull()
            when {
                missing != null -> call.reject(missing, "MISSING_PERMISSION")
                e is Fail -> call.reject(e.message ?: e.code, e.code)
                e is SecurityException -> call.reject("Access to this folder was revoked. Open the vault again.", "PERMISSION_DENIED")
                else -> call.reject("${e.message ?: "unknown error"}", "IO_ERROR")
            }
        }
    }

    private fun treeName(tree: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: docId
        }
        return docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { docId }
    }

    // ---------------------------------------------------------------- roots (#1717 Source section)

    /** Mounted removable volumes as (label, folder). Empty on any failure: the root then answers NO_DRIVE. */
    private fun externalVolumes(): List<Pair<String, File>> = try {
        val sm = context.getSystemService(StorageManager::class.java)
        sm?.storageVolumes.orEmpty().mapNotNull { v ->
            if (!v.isRemovable || v.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            val dir: File = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory else v.uuid?.let { File("/storage/$it") })
                ?: return@mapNotNull null
            Pair(v.getDescription(context) ?: dir.name, dir)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun place(label: String, root: String) = JSObject().put("label", label).put("root", root)

    /** Folders the user granted in the system picker whose provider is [authority] (a persisted tree). */
    private fun grantedTrees(authority: String, keep: (Uri) -> Boolean = { true }): List<JSObject> {
        val out = ArrayList<JSObject>()
        for (p in context.contentResolver.persistedUriPermissions) {
            try {
                if (!p.isReadPermission || !DocumentsContract.isTreeUri(p.uri) || p.uri.authority != authority || !keep(p.uri)) continue
                out.add(place(treeName(p.uri), p.uri.toString()))
            } catch (e: Exception) {
                // a tree whose provider is gone is skipped, not fatal
            }
        }
        return out
    }

    private fun rootAnswer(id: String, places: List<JSObject>, reason: String?): JSObject {
        val arr = JSArray()
        places.forEach { arr.put(it) }
        return JSObject().put("id", id).put("places", arr).put("reason", if (places.isEmpty()) reason else null)
    }

    /** A home that belongs to another app: the folders granted through its documents provider, else why not. */
    private fun appHome(id: String, pkg: String, extra: List<JSObject> = emptyList()): JSObject {
        val places = grantedTrees("$pkg.documents") + extra
        return rootAnswer(id, places, if (installed(pkg)) NOT_READABLE else APP_NOT_INSTALLED)
    }

    /**
     * The declared roots as this phone sees them: each answers the folder(s) it opens at, or the
     * reason it cannot be browsed. Never rejects: a root that fails to probe answers NOT_READABLE.
     */
    @PluginMethod
    fun roots(call: PluginCall) {
        val roots = JSArray()
        fun add(id: String, body: () -> JSObject) {
            roots.put(try { body() } catch (e: Exception) { rootAnswer(id, emptyList(), NOT_READABLE) })
        }
        add(ROOT_EMULATED) {
            val dir = Environment.getExternalStorageDirectory()
            when {
                !hasAllFilesAccess() -> rootAnswer(ROOT_EMULATED, emptyList(), NO_STORAGE_GRANT)
                dir.listFiles() == null -> rootAnswer(ROOT_EMULATED, emptyList(), NOT_READABLE)
                else -> rootAnswer(ROOT_EMULATED, listOf(place("Emulated", dir.path)), null)
            }
        }
        add(ROOT_TERMUX) {
            // the fleet shared store lives in the Termux home and is reachable through its provider
            val store = try {
                context.contentResolver.query(Uri.parse("content://$STORE_AUTHORITY/tree/"), null, null, null, null)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
            appHome(ROOT_TERMUX, TERMUX_PACKAGE, if (store) listOf(place("Shared store", "store:")) else emptyList())
        }
        add(ROOT_NIX) { appHome(ROOT_NIX, NIX_PACKAGE) }
        add(ROOT_EXTERNAL) {
            val drives = externalVolumes()
            val granted = grantedTrees(EXTERNAL_DOCUMENTS) { !DocumentsContract.getTreeDocumentId(it).startsWith("primary:") }
            when {
                drives.isEmpty() && granted.isEmpty() -> rootAnswer(ROOT_EXTERNAL, emptyList(), NO_DRIVE)
                else -> {
                    val direct = if (hasAllFilesAccess()) drives.filter { it.second.listFiles() != null }.map { place(it.first, it.second.path) } else emptyList()
                    rootAnswer(ROOT_EXTERNAL, direct + granted, if (hasAllFilesAccess()) NOT_READABLE else NO_STORAGE_GRANT)
                }
            }
        }
        call.resolve(JSObject().put("roots", roots))
    }

    /** The open_app grant action: launch the app that owns a root (its first run creates its home). */
    @PluginMethod
    fun openApp(call: PluginCall) {
        val pkg = when (call.getString("id")) {
            ROOT_TERMUX -> TERMUX_PACKAGE
            ROOT_NIX -> NIX_PACKAGE
            else -> return call.reject("This root has no app to open.", "NO_APP")
        }
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return call.reject("The app is not installed.", APP_NOT_INSTALLED)
            (activity ?: context).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            call.resolve()
        } catch (e: Exception) {
            call.reject("The app could not be opened (${e.message ?: "unknown error"}).", "OPEN_ERROR")
        }
    }

    // ---------------------------------------------------------------- methods

    @PluginMethod
    fun pickVault(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        startActivityForResult(call, intent, "pickVaultResult")
    }

    @ActivityCallback
    private fun pickVaultResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("No folder was chosen.", "CANCELLED")
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            call.resolve(JSObject().put("root", uri.toString()).put("name", treeName(uri)))
        } catch (e: Exception) {
            call.reject("The folder could not be kept open (${e.message ?: "unknown error"}).", "GRANT_ERROR")
        }
    }

    @PluginMethod
    fun persisted(call: PluginCall) {
        val vaults = JSArray()
        for (p in context.contentResolver.persistedUriPermissions) {
            if (!p.isReadPermission || !DocumentsContract.isTreeUri(p.uri)) continue
            try {
                vaults.put(JSObject().put("root", p.uri.toString()).put("name", treeName(p.uri)))
            } catch (e: Exception) {
                // a tree whose provider is gone is skipped, not fatal
            }
        }
        call.resolve(JSObject().put("vaults", vaults))
    }

    @PluginMethod
    fun releaseVault(call: PluginCall) {
        val root = call.getString("root")?.trim().orEmpty()
        try {
            if (root.startsWith("content://")) {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(root),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("${e.message ?: "unknown error"}", "GRANT_ERROR")
        }
    }

    /** A direct path or store address typed or remembered: validates it and answers the vault root. */
    @PluginMethod
    fun openPath(call: PluginCall) {
        val path = call.getString("path")?.trim().orEmpty()
        if (path.isEmpty()) return call.reject("No path was given.", "NO_PATH")
        try {
            val b = backend(path)
            b.list("")
            val name = path.removePrefix("store:").trimEnd('/').substringAfterLast('/').ifEmpty { path }
            call.resolve(JSObject().put("root", if (path.startsWith("store:")) path else File(path).canonicalPath).put("name", name))
        } catch (e: Fail) {
            call.reject(e.message ?: e.code, e.code)
        } catch (e: Exception) {
            call.reject("${e.message ?: "unknown error"}", "IO_ERROR")
        }
    }

    @PluginMethod
    fun list(call: PluginCall) = guarded(call) { _, b ->
        val entries = JSArray()
        b.list(call.getString("id").orEmpty()).forEach { entries.put(it) }
        call.resolve(JSObject().put("entries", entries))
    }

    @PluginMethod
    fun readText(call: PluginCall) = guarded(call) { _, b ->
        val id = call.getString("id").orEmpty()
        val size = b.sizeOf(id)
        if (size > MAX_TEXT) throw Fail("TOO_LARGE", "The file is larger than the 5 MiB text limit.")
        val bytes = b.open(id).use { it.readBytes() }
        call.resolve(JSObject().put("text", String(bytes, Charsets.UTF_8)).put("size", bytes.size))
    }

    @PluginMethod
    fun readBinary(call: PluginCall) = guarded(call) { _, b ->
        val id = call.getString("id").orEmpty()
        if (b.sizeOf(id) > MAX_BINARY) throw Fail("TOO_LARGE", "The file is larger than the 25 MiB limit.")
        val bytes = b.open(id).use { it.readBytes() }
        call.resolve(JSObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("size", bytes.size))
    }

    @PluginMethod
    fun writeText(call: PluginCall) = guarded(call) { _, b ->
        val id = call.getString("id").orEmpty()
        val text = call.getString("text") ?: throw Fail("NO_TEXT", "No text was given.")
        if (id.isEmpty()) throw Fail("IS_DIRECTORY", "The vault root is a folder.")
        b.write(id, text.toByteArray(Charsets.UTF_8))
        call.resolve()
    }

    @PluginMethod
    fun create(call: PluginCall) = guarded(call) { _, b ->
        val name = call.getString("name")?.trim().orEmpty()
        if (name.isEmpty()) throw Fail("BAD_NAME", "Give the new file a name.")
        val id = b.create(call.getString("parentId").orEmpty(), name, call.getBoolean("isDir") ?: false)
        call.resolve(JSObject().put("id", id))
    }

    @PluginMethod
    fun rename(call: PluginCall) = guarded(call) { _, b ->
        val name = call.getString("name")?.trim().orEmpty()
        if (name.isEmpty()) throw Fail("BAD_NAME", "Give the file a name.")
        call.resolve(JSObject().put("id", b.rename(call.getString("id").orEmpty(), name)))
    }

    @PluginMethod
    fun remove(call: PluginCall) = guarded(call) { _, b ->
        b.remove(call.getString("id").orEmpty())
        call.resolve()
    }

    /** #1515 All files access is granted in Configs > Perms (libs:perms): the page opens on that row. */
    @PluginMethod
    fun openSettings(call: PluginCall) {
        val ctx = activity ?: context
        try {
            PermsLink.open(ctx, "vault", listOf("all_files"))
            call.resolve()
        } catch (e: Exception) {
            call.reject("Cannot open the permissions page. Grant the storage permission in Android Settings.", "SETTINGS_ERROR")
        }
    }
}
