package app.cloudnotes.pro.plugin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import android.util.Base64
import java.io.File
import java.io.FileInputStream

/**
 * Emulated-storage file opener (ticket #469 — the ONE feature this clone adds
 * over upstream).
 *
 * Scoped storage mechanism, stated plainly: since Android 11 the OS does not
 * let an app read /storage/emulated/0/... by raw path without a grant, and a
 * file-manager-style path opener needs exactly that granter. This plugin uses
 * MANAGE_EXTERNAL_STORAGE ("All files access", `Environment
 * .isExternalStorageManager()`), the mechanism file managers use, and guides
 * the user to Settings when it is missing. On Android 10 and older it falls
 * back to the READ_EXTERNAL_STORAGE runtime permission. No SAF picker is
 * involved because Diego wants to type a path into his vault, not navigate a
 * system dialog; every failure mode returns its OWN visible message.
 */
@CapacitorPlugin(
    name = "ExternalFile",
    permissions = [Permission(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))]
)
class ExternalFilePlugin : Plugin() {

    companion object {
        private const val ROOT = "/storage/emulated/0/"
        private const val MAX_BYTES = 10L * 1024 * 1024 // 10 MiB import cap (markdown vaults)
        private val SUPPORTED = setOf(
            "md", "markdown", "txt", "text", "json", "yaml", "yml", "html", "htm", "csv"
        )

        /** #1642 text types / names the manifest's VIEW+EDIT filters admit; binary types are never taken. */
        private val OPEN_EXT = SUPPORTED + setOf("log", "conf", "cfg", "ini", "xml", "tsv", "toml", "properties")
        private val OPEN_MIME = setOf(
            "application/json", "application/xml", "application/x-yaml", "application/yaml"
        )

        /** True for a type or file name that is text (never octet-stream, images, archives, pdf). */
        fun isTextOpen(mime: String?, name: String?): Boolean {
            val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (m.startsWith("text/") || m in OPEN_MIME || m.endsWith("+json") || m.endsWith("+xml")) return true
            if (m.isNotEmpty() && m != "application/octet-stream" && m != "*/*") return false
            return name?.substringAfterLast('.', "")?.lowercase().orEmpty() in OPEN_EXT
        }

        /** The text file another app handed this app (VIEW/EDIT), read once, taken by [takePendingOpen]. */
        class PendingOpen(val name: String, val content: String?, val size: Int, val error: String?)

        @Volatile private var pending: PendingOpen? = null

        /**
         * #1642 reads the content:// / file:// text file of a VIEW/EDIT [intent] into [pending]. True when the
         * intent was a text file open (read or failed with a message); false when it is not ours. Never throws.
         */
        fun capture(ctx: Context, intent: Intent?): Boolean {
            val i = intent ?: return false
            if (i.action != Intent.ACTION_VIEW && i.action != Intent.ACTION_EDIT) return false
            val uri: Uri = i.data ?: return false
            if (uri.scheme != "content" && uri.scheme != "file") return false
            return try {
                var name: String? = null
                if (uri.scheme == "content") {
                    try {
                        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) name = c.getString(0)?.takeIf { it.isNotBlank() }
                        }
                    } catch (_: Exception) {}
                }
                val shown = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "note.txt"
                val mime = i.type ?: if (uri.scheme == "content") ctx.contentResolver.getType(uri) else null
                if (!isTextOpen(mime, shown)) return false
                val buf = java.io.ByteArrayOutputStream()
                val stream = ctx.contentResolver.openInputStream(uri)
                if (stream == null) {
                    pending = PendingOpen(shown, null, 0, "Cannot read $shown.")
                    return true
                }
                stream.use { inp ->
                    val b = ByteArray(8192)
                    while (buf.size() <= MAX_BYTES) {
                        val n = inp.read(b)
                        if (n < 0) break
                        buf.write(b, 0, n)
                    }
                }
                pending = if (buf.size() > MAX_BYTES) {
                    PendingOpen(shown, null, buf.size(), "File is larger than the 10 MiB import limit.")
                } else {
                    PendingOpen(shown, buf.toByteArray().toString(Charsets.UTF_8), buf.size(), null)
                }
                true
            } catch (e: Exception) {
                pending = PendingOpen("file", null, 0, "Cannot read the file (${e.message ?: "unknown error"}).")
                true
            }
        }
    }

    private fun hasExternalStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Shared gate for readFile and listDir: rejects the call (returning null)
     * unless [path] is inside the owner's visible storage AND access is
     * granted. The prefix test alone is not enough — "/storage/emulated/0/../"
     * passes it and walks out — so the canonical path is checked as well.
     */
    private fun guardedFile(call: PluginCall, path: String): File? {
        if (path.isEmpty()) {
            call.reject("No path was given.", "NO_PATH")
            return null
        }
        // Only the owner's visible storage is in scope; anything else is a
        // prompt the app cannot explain, so it fails with its own message.
        if (!path.startsWith(ROOT)) {
            call.reject(
                "Path must start with /storage/emulated/0/ (your phone's visible storage).",
                "OUTSIDE_EMULATED_STORAGE"
            )
            return null
        }
        val file = File(path)
        // Both sides canonicalised: on ROMs where /storage/emulated/0 is itself
        // a symlink, comparing against the literal prefix would reject every path.
        val canonical = try {
            file.canonicalPath
        } catch (e: Exception) {
            call.reject("Cannot resolve $path (${e.message ?: "unknown error"})", "READ_ERROR")
            return null
        }
        val canonicalRoot = File(ROOT).canonicalPath.trimEnd('/') + "/"
        if (!"$canonical/".startsWith(canonicalRoot)) {
            call.reject(
                "Path must stay inside /storage/emulated/0/ (your phone's visible storage).",
                "OUTSIDE_EMULATED_STORAGE"
            )
            return null
        }
        if (!hasExternalStorageAccess()) {
            call.reject(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "All-files access is not granted. Open Settings → Apps → " +
                        "Cloud Notes → All files access → Allow, then try again."
                } else {
                    "Storage permission is not granted. Open Settings → Apps → " +
                        "Cloud Notes → Permissions → Files and media → Allow, then try again."
                },
                "PERMISSION_DENIED"
            )
            return null
        }
        return file
    }

    @PluginMethod
    fun readFile(call: PluginCall) {
        val path = call.getString("path")?.trim().orEmpty()
        val file = guardedFile(call, path) ?: return
        if (!file.exists()) {
            call.reject("File not found at $path", "NOT_FOUND")
            return
        }
        if (file.isDirectory) {
            call.reject("This is a folder, not a file: $path", "IS_DIRECTORY")
            return
        }
        val ext = file.extension.lowercase()
        if (ext !in SUPPORTED) {
            call.reject(
                "Unsupported file type \".$ext\". Supported: " +
                    SUPPORTED.sorted().joinToString(", "),
                "UNSUPPORTED_EXTENSION"
            )
            return
        }
        if (file.length() > MAX_BYTES) {
            call.reject("File is larger than the 10 MiB import limit.", "TOO_LARGE")
            return
        }

        try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            val result = JSObject()
                .put("content", bytes.toString(Charsets.UTF_8))
                .put("name", file.name)
                .put("size", bytes.size)
                .put("path", path)
            call.resolve(result)
        } catch (e: Exception) {
            call.reject(
                "Cannot read $path (${e.message ?: "unknown error"})",
                "READ_ERROR"
            )
        }
    }

    /**
     * One folder level for the in-app file explorer (#547): sub-folders plus
     * the files readFile would accept (same SUPPORTED set, so the explorer can
     * never offer a file the opener then refuses). Dot-entries are hidden;
     * folders first, then files, each A→Z case-insensitively.
     */
    @PluginMethod
    fun listDir(call: PluginCall) {
        val path = call.getString("path")?.trim().orEmpty()
        val dir = guardedFile(call, path) ?: return
        if (!dir.exists()) {
            call.reject("Folder not found at $path", "NOT_FOUND")
            return
        }
        if (!dir.isDirectory) {
            call.reject("This is a file, not a folder: $path", "NOT_A_DIRECTORY")
            return
        }
        val children = dir.listFiles()
        if (children == null) {
            call.reject("Cannot read the folder $path", "READ_ERROR")
            return
        }
        val entries = JSArray()
        children
            .filter { !it.name.startsWith(".") && (it.isDirectory || it.extension.lowercase() in SUPPORTED) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach {
                entries.put(
                    JSObject()
                        .put("name", it.name)
                        .put("isDir", it.isDirectory)
                        .put("size", if (it.isDirectory) 0L else it.length())
                )
            }
        // Report the path in the /storage/emulated/0/ namespace the caller
        // typed, not the canonical one, so navigation stays in that namespace.
        val shown = path.trimEnd('/') + "/"
        val atRoot = dir.canonicalPath.trimEnd('/') == File(ROOT).canonicalPath.trimEnd('/')
        call.resolve(
            JSObject()
                .put("path", shown)
                .put("parent", if (atRoot) null else File(shown).parentFile?.path?.plus("/"))
                .put("entries", entries)
        )
    }

    /**
     * Vault import (Phase 1): every non-hidden FILE of one folder level, whatever its type, so
     * attachments (images, pdf) can be found. Deliberately separate from [listDir]: the explorer
     * keeps offering only what [readFile] accepts.
     */
    @PluginMethod
    fun listFiles(call: PluginCall) {
        val path = call.getString("path")?.trim().orEmpty()
        val dir = guardedFile(call, path) ?: return
        if (!dir.exists()) {
            call.reject("Folder not found at $path", "NOT_FOUND")
            return
        }
        if (!dir.isDirectory) {
            call.reject("This is a file, not a folder: $path", "NOT_A_DIRECTORY")
            return
        }
        val children = dir.listFiles()
        if (children == null) {
            call.reject("Cannot read the folder $path", "READ_ERROR")
            return
        }
        val entries = JSArray()
        children
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach {
                entries.put(
                    JSObject()
                        .put("name", it.name)
                        .put("isDir", it.isDirectory)
                        .put("size", if (it.isDirectory) 0L else it.length())
                )
            }
        call.resolve(JSObject().put("path", path.trimEnd('/') + "/").put("entries", entries))
    }

    /** Vault import: one file's bytes as base64, any type, same guard and size cap as [readFile]. */
    @PluginMethod
    fun readBinary(call: PluginCall) {
        val path = call.getString("path")?.trim().orEmpty()
        val file = guardedFile(call, path) ?: return
        if (!file.exists()) {
            call.reject("File not found at $path", "NOT_FOUND")
            return
        }
        if (file.isDirectory) {
            call.reject("This is a folder, not a file: $path", "IS_DIRECTORY")
            return
        }
        if (file.length() > MAX_BYTES) {
            call.reject("File is larger than the 10 MiB import limit.", "TOO_LARGE")
            return
        }
        try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            call.resolve(
                JSObject()
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("name", file.name)
                    .put("size", bytes.size)
                    .put("path", path)
            )
        } catch (e: Exception) {
            call.reject("Cannot read $path (${e.message ?: "unknown error"})", "READ_ERROR")
        }
    }

    /** #1642 the text file another app opened with this app, once; present=false when there is none. */
    @PluginMethod
    fun takePendingOpen(call: PluginCall) {
        val p = pending
        pending = null
        if (p == null) {
            call.resolve(JSObject().put("present", false))
            return
        }
        val r = JSObject().put("present", true).put("name", p.name).put("size", p.size)
        if (p.error != null) r.put("error", p.error) else r.put("content", p.content)
        call.resolve(r)
    }

    /** #1515 All files access is granted in Configs > Perms (libs:perms): the page opens on that row. */
    @PluginMethod
    fun openSettings(call: PluginCall) {
        val ctx = activity ?: context
        try {
            com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "vault", listOf("all_files"))
            call.resolve()
        } catch (e: Exception) {
            call.reject(
                "Cannot open the permissions page (${e.message ?: "unknown error"}). " +
                    "Grant the storage permission manually in Android Settings.",
                "SETTINGS_ERROR"
            )
        }
    }
}