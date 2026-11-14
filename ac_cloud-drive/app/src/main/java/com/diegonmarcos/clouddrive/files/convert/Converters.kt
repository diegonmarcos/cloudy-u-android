package com.diegonmarcos.clouddrive.files.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import com.diegonmarcos.clouddrive.files.FileOps
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The converters that ship, in the order the dialog lists them. A new one is one line here. */
object Converters {

    val ALL: List<Converter> = listOf(
        MhtmlToHtml,
        MarkdownToHtml,
        JsonPretty,
        TextToPdf,
        ImageToPdf,
        ImageConverter("to-jpg", "JPG image", setOf("png", "webp"), "jpg"),
        ImageConverter("to-png", "PNG image", setOf("jpg", "jpeg", "webp"), "png"),
        ImageConverter("to-webp", "WebP image", setOf("png", "jpg", "jpeg"), "webp"),
    )

    /** `<stem><suffix>.<ext>` in [dir], made unique so a conversion never overwrites a file. */
    internal fun output(source: File, dir: File, suffix: String, ext: String): File =
        FileOps.uniqueIn(dir, source.nameWithoutExtension + suffix + "." + ext)

    private const val TEXT_CEILING = 16L * 1024 * 1024

    internal fun readText(source: File): String {
        if (source.length() > TEXT_CEILING) throw IOException("${source.name} is larger than ${TEXT_CEILING / (1024 * 1024)} MB; it cannot be converted on the phone")
        return source.readText(Charsets.UTF_8)
    }
}

// ── MHTML / MHT → HTML + assets folder ───────────────────────────────────────

object MhtmlToHtml : Converter {
    override val id = "mhtml-html"
    override val label = "HTML + assets folder"
    override fun accepts(extension: String) = extension == "mhtml" || extension == "mht"

    override fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> {
        if (source.length() > Mhtml.MAX_BYTES) throw IOException("${source.name} is larger than ${Mhtml.MAX_BYTES / (1024 * 1024)} MB; it cannot be converted on the phone")
        val html = Converters.output(source, destDir, "", "html")
        val assets = FileOps.uniqueIn(destDir, html.nameWithoutExtension + "_files")
        progress.report(0L, 1L, source.name)
        try {
            val out = Mhtml.convert(source.readBytes(), html, assets)
            progress.report(1L, 1L, html.name)
            return listOfNotNull(out.html, out.assetsDir)
        } catch (e: Exception) {
            html.delete(); assets.deleteRecursively()
            throw if (e is IOException) e else IOException(e.message ?: "the MHTML could not be converted")
        }
    }
}

// ── Markdown → HTML ──────────────────────────────────────────────────────────

object MarkdownToHtml : Converter {
    override val id = "md-html"
    override val label = "HTML page"
    override fun accepts(extension: String) = extension == "md" || extension == "markdown"

    override fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> {
        val out = Converters.output(source, destDir, "", "html")
        out.writeText(Markdown.toHtml(Converters.readText(source), source.nameWithoutExtension), Charsets.UTF_8)
        progress.report(1L, 1L, out.name)
        return listOf(out)
    }
}

// ── JSON → pretty-printed JSON ───────────────────────────────────────────────

object JsonPretty : Converter {
    override val id = "json-pretty"
    override val label = "Pretty-printed JSON"
    override fun accepts(extension: String) = extension == "json"

    @OptIn(ExperimentalSerializationApi::class)
    private val printer = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** [text] re-indented with two spaces; key order and number literals are kept as written. */
    fun pretty(text: String): String {
        val element = try { Json.parseToJsonElement(text) } catch (e: Exception) { throw IOException("not valid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: e}") }
        return printer.encodeToString(JsonElement.serializer(), element) + "\n"
    }

    override fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> {
        val pretty = pretty(Converters.readText(source).removePrefix("﻿"))
        val out = Converters.output(source, destDir, ".pretty", "json")
        out.writeText(pretty, Charsets.UTF_8)
        progress.report(1L, 1L, out.name)
        return listOf(out)
    }
}

// ── images between png, jpg and webp ─────────────────────────────────────────

class ImageConverter(override val id: String, override val label: String, private val from: Set<String>, private val toExt: String) : Converter {
    override fun accepts(extension: String) = extension in from

    override fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("${source.name} is not an image the phone can decode")
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("${source.name} could not be decoded")
        if (cancelled()) { decoded.recycle(); throw IOException("cancelled") }
        val upright = uprightBitmap(decoded, source)
        // JPEG has no alpha: flatten onto white rather than let transparency turn black.
        val flat = if (toExt == "jpg") {
            Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).apply { drawColor(Color.WHITE); drawBitmap(upright, 0f, 0f, null) }
            }
        } else upright
        val out = Converters.output(source, destDir, "", toExt)
        try {
            BufferedOutputStream(FileOutputStream(out)).use { os ->
                if (!flat.compress(format(), QUALITY, os)) throw IOException("the phone could not encode ${out.name}")
            }
        } catch (e: Exception) {
            out.delete(); throw if (e is IOException) e else IOException(e.message ?: "encoding failed")
        } finally {
            if (flat !== upright) flat.recycle()
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
        progress.report(1L, 1L, out.name)
        return listOf(out)
    }

    @Suppress("DEPRECATION")
    private fun format(): Bitmap.CompressFormat = when (toExt) {
        "png" -> Bitmap.CompressFormat.PNG
        "jpg" -> Bitmap.CompressFormat.JPEG
        else -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }

    private companion object {
        const val QUALITY = 92
        const val MAX_PIXELS = 36_000_000L
    }
}

/** A JPEG camera shot carries its rotation in EXIF; the converted file has none, so bake it in. */
internal fun uprightBitmap(bitmap: Bitmap, source: File): Bitmap {
        val degrees = try {
            when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }


// ── txt / md → PDF (PdfWriter: a plain writer, so the fleet keeps its ONE PDF engine, the pdfium reader) ──

object TextToPdf : Converter {
    override val id = "text-pdf"
    override val label = "PDF document"
    override fun accepts(extension: String) = extension in ToPdf.TEXT_EXT

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")

    /** txt is set as written; markdown becomes sized bold headings, bullets and indented code and quotes. */
    internal fun lines(text: String, markdown: Boolean): List<PdfWriter.Line> {
        val src = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n")
        if (!markdown) return src.map { PdfWriter.Line(it, size = 9.5f) }
        val out = ArrayList<PdfWriter.Line>()
        var fenced = false
        for (line in src) {
            val t = line.trimStart()
            if (t.startsWith("```") || t.startsWith("~~~")) { fenced = !fenced; continue }
            if (fenced) { out += PdfWriter.Line(line, size = 9f, indent = 12f); continue }
            val h = HEADING.matchEntire(line)
            val item = ITEM.matchEntire(line)
            when {
                h != null -> {
                    out += PdfWriter.Line("")
                    out += PdfWriter.Line(plain(h.groupValues[2]), size = when (h.groupValues[1].length) { 1 -> 18f; 2 -> 15f; 3 -> 13f; else -> 11f }, bold = true)
                }
                item != null -> out += PdfWriter.Line((if (item.groupValues[2][0].isDigit()) item.groupValues[2] else "•") + " " + plain(item.groupValues[3]), size = 10f, indent = 8f + item.groupValues[1].length * 5f)
                t.startsWith(">") -> out += PdfWriter.Line(plain(t.removePrefix(">").trim()), size = 10f, indent = 14f)
                else -> out += PdfWriter.Line(plain(line), size = 10f)
            }
        }
        return out
    }

    /** Inline markup reduced to what reads right on paper: **bold**, `code`, [text](url) becomes text (url). */
    private fun plain(s: String): String = s
        .replace(Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1 ($2)")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        .replace(Regex("`([^`]+)`"), "$1")

    override fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> {
        val markdown = FileOps.extensionOf(source.name).let { it == "md" || it == "markdown" }
        val doc = lines(Converters.readText(source).removePrefix("\uFEFF"), markdown)
        val out = Converters.output(source, destDir, "", "pdf")
        progress.report(0L, 1L, out.name)
        try {
            BufferedOutputStream(FileOutputStream(out)).use { PdfWriter.write(doc, it, cancelled) }
        } catch (e: Exception) {
            out.delete(); throw if (e is IOException) e else IOException(e.message ?: "the PDF could not be written")
        }
        progress.report(1L, 1L, out.name)
        return listOf(out)
    }
}
