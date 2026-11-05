package app.cloudnotes.pro.plugin

import android.Manifest
import android.content.pm.PackageManager
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