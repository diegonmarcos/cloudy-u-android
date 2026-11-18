package com.diegonmarcos.cloudstore

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.diegonmarcos.superapp.torrent.TorrentStatus
import java.io.File

/**
 * #1790 opening a finished download. A torrent lands in `<savePath>/<name>` (a file for a single-file torrent, a folder for a
 * multi-file one). [files] lists what is on disk there; [main] is the file Open starts (the biggest media file, else the biggest
 * file); [open] / [openFile] hand it to the phone's viewer through this app's FileProvider ([AUTHORITY_SUFFIX]) with a read grant,
 * and answer one sentence (never a crash when no app can open it). Pure parts ([files], [main], [mime]) are JVM-tested.
 */
object TorrentOpen {
    const val AUTHORITY_SUFFIX = ".torrentfiles"

    class OnDisk(val file: File, val relative: String)

    private val MEDIA = setOf("mp3", "ogg", "oga", "opus", "flac", "wav", "m4a", "aac", "mp4", "m4v", "mkv", "webm", "avi", "mov", "pdf", "epub", "iso", "apk")

    /** The torrent's files under its save folder, sorted by path. Empty when nothing is there (moved or deleted). */
    fun files(t: TorrentStatus): List<OnDisk> = files(File(t.savePath), t.name)

    fun files(saveDir: File, name: String): List<OnDisk> {
        val root = File(saveDir, name)
        return when {
            root.isFile -> listOf(OnDisk(root, root.name))
            root.isDirectory -> root.walkTopDown().filter { it.isFile && !it.name.endsWith(".parts") }
                .map { OnDisk(it, it.relativeTo(root).path) }.sortedBy { it.relative }.toList()
            else -> emptyList()
        }
    }

    /** What Open starts: the biggest media file, else the biggest file. */
    fun main(files: List<OnDisk>): OnDisk? =
        files.filter { it.file.extension.lowercase() in MEDIA }.maxByOrNull { it.file.length() } ?: files.maxByOrNull { it.file.length() }

    fun mime(f: File): String =
        MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(f.extension.lowercase()) ?: when (f.extension.lowercase()) {
            "mp3" -> "audio/mpeg"; "ogg", "oga" -> "audio/ogg"; "flac" -> "audio/flac"; "pdf" -> "application/pdf"
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }

    fun open(ctx: Context, t: TorrentStatus): String {
        val m = main(files(t)) ?: return "${t.name}: the downloaded files are not in ${t.savePath} any more."
        return openFile(ctx, m.file)
    }

    fun openFile(ctx: Context, f: File): String {
        if (!f.isFile) return "${f.name} is not on the phone any more."
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, f)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(view, "Open ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            "Opening ${f.name}."
        }.getOrElse { e -> if (e is ActivityNotFoundException) "No app on this phone opens ${f.name}." else "Cannot open ${f.name}: ${e.message}" }
    }
}
