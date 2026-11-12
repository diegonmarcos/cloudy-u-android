package com.diegonmarcos.hardtechcad

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateOf
import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelCache
import java.io.File

/**
 * #1695 a STEP / STP file opened from Drive, Files or any app (ACTION_VIEW, the intent filter in the manifest): copied into the app's model
 * cache under its sha256 (the same store [ModelCache] serves the viewer from), then shown with the B-rep viewer (libs:cad-brep, occt-import-js)
 * like a declared model. Nothing is uploaded. Every failure is a reason on screen, never a crash.
 */
object OpenedFile {
    /** The MIME types the intent filter accepts for STEP (ISO 10303-21). */
    val STEP_MIMES = listOf("model/step", "model/x-step", "application/step", "application/x-step", "application/stp", "model/stp")

    /** The URI the activity was asked to show; the shell draws it over the current section until it is closed. */
    val pending = mutableStateOf<Uri?>(null)

    fun accept(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { pending.value = it }
    }

    /** The format family of an opened file, from its name, else its MIME type; null when it is not one the viewer can draw. */
    fun formatOf(name: String, mime: String?): String? {
        val ext = name.substringAfterLast('.', "").let { CadFormats.normalize(it) }
        if (ext.isNotEmpty() && CadFormats.route(ext) is CadFormats.Route.Viewer) return ext
        val m = mime?.lowercase().orEmpty()
        if (m in STEP_MIMES) return "step"
        return null
    }

    /** The declared-model shape of an opened file, so the model page and its viewer need nothing special. */
    fun model(name: String, format: String, size: Long, sha256: String): Model = Model(
        id = "opened-" + sha256.take(12), name = name, library = "", format = format, path = ModelCache.cachedName(sha256, format),
        source = "Opened from another app (not in the library)", licence = "", size = size, sha256 = sha256, view = null, conversion = "",
        software = "", updated = "",
    )

    /** Blocking (call on IO): copy [uri] into [dir] as `<sha256>.<format>` and describe it. Throws with a readable message. */
    fun import(ctx: Context, uri: Uri, dir: File): Model {
        val cr = ctx.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model"
        val format = formatOf(name, runCatching { cr.getType(uri) }.getOrNull())
            ?: throw IllegalArgumentException("$name is not a file this viewer can draw (expected .step or .stp)")
        dir.mkdirs()
        val tmp = File(dir, "opened.part")
        val input = cr.openInputStream(uri) ?: throw IllegalStateException("$name could not be read (the other app gave no data)")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        if (tmp.length() == 0L) { tmp.delete(); throw IllegalStateException("$name is empty") }
        val sha = ModelCache.sha256(tmp)
        val f = File(dir, ModelCache.cachedName(sha, format))
        if (f.isFile) tmp.delete() else if (!tmp.renameTo(f)) throw IllegalStateException("$name could not be stored in the app cache")
        return model(name, format, f.length(), sha)
    }
}
