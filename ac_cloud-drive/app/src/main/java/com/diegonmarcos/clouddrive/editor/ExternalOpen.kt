package com.diegonmarcos.clouddrive.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * #1537 "Open with Cloud Drive": what another app hands over (ACTION_VIEW / ACTION_EDIT with a file:// or content:// URI) becomes a
 * path the Editor can open. A file:// URI is used where it stands; a content:// URI is copied once into the cache (the Editor works on
 * files), under its own display name so the type router sees the extension, and old copies are swept. The name rule is pure
 * ([safeName]) and tested.
 */
object ExternalOpen {
    private const val DIR = "opened"
    private const val KEEP_MS = 3L * 24 * 60 * 60 * 1000

    /** A display name made safe as one file name: no path, no control or reserved characters, never empty, at most 120 chars (the extension kept). */
    fun safeName(raw: String?): String {
        var n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
        n = n.map { c -> if (c.isISOControl() || c in ":*?\"<>|") '_' else c }.joinToString("")
        n = n.trim('.', ' ')
        if (n.isEmpty()) return "file"
        if (n.length > 120) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 12) n.substring(dot) else ""
            n = n.substring(0, 120 - ext.length) + ext
        }
        return n
    }

    /** The folder name that keeps two different URIs with the same display name apart. */
    fun slot(uri: String): String = uri.hashCode().toUInt().toString(16)

    fun resolve(ctx: Context, uri: Uri): File? = when (uri.scheme) {
        "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile && it.canRead() }
        "content" -> copy(ctx, uri)
        else -> null
    }

    private fun copy(ctx: Context, uri: Uri): File? = runCatching {
        val root = File(ctx.cacheDir, DIR).apply { mkdirs() }
        sweep(root)
        val name = displayName(ctx, uri) ?: uri.lastPathSegment
        val dir = File(root, slot(uri.toString())).apply { mkdirs() }
        val out = File(dir, safeName(name))
        ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
        out
    }.getOrNull()

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sweep(root: File) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        root.listFiles()?.forEach { d -> if (d.lastModified() < cutoff) d.deleteRecursively() }
    }
}
