package com.diegonmarcos.clouddrive.files.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.content.Context
import com.diegonmarcos.clouddrive.files.FileOps
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Convert ▸ "Convert to PDF…" for ANY file: which way a type is written, or the reason it is not.
 * Writing is [PdfWriter] (plain bytes: images embedded as JPEG, text set in Courier), never a
 * platform PDF class: the fleet keeps ONE in-process PDF engine, the pdfium reader (one-pdf-engine guard).
 * Pure [kindOf] / [refusal] are what ToPdfTest pins; nothing here ever throws to the UI, a type
 * that cannot be converted gets a sentence instead.
 */
object ToPdf {

    enum class Kind { IMAGE, TEXT, HTML, OFFICE, PDF, OTHER }

    val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
    val TEXT_EXT = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "conf", "cfg", "toml", "properties",
        "sh", "bash", "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "rs", "go", "c", "h", "cpp", "hpp", "cs", "css", "scss",
        "sql", "gradle", "rb", "php", "lua", "tex", "rst", "nix", "diff", "patch", "env", "gitignore",
    )
    val HTML_EXT = setOf("html", "htm", "xhtml")
    val OFFICE_EXT = setOf("doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "rtf")

    fun kindOf(name: String): Kind {
        val ext = FileOps.extensionOf(name)
        return when {
            ext in IMAGE_EXT -> Kind.IMAGE
            ext in TEXT_EXT -> Kind.TEXT
            ext in HTML_EXT -> Kind.HTML
            ext in OFFICE_EXT -> Kind.OFFICE
            ext == "pdf" -> Kind.PDF
            else -> Kind.OTHER
        }
    }

    /** Null when [name] can be converted; else the sentence the snackbar shows. */
    fun refusal(name: String): String? = when (kindOf(name)) {
        Kind.IMAGE, Kind.TEXT, Kind.HTML -> null
        Kind.PDF -> "$name is already a PDF"
        Kind.OFFICE -> "$name is an office document: the phone has no office renderer (the fleet keeps one PDF engine, the reader). Open it in Cloud Office and export or print it to PDF there"
        Kind.OTHER -> "$name is a type that has no PDF form on the phone (supported: images, text, markdown, code, HTML)"
    }

    private const val MAX_PIXELS = 24_000_000L
    private const val JPEG_QUALITY = 88

    /** Image → one-page PDF (JPEG embedded; transparency flattened onto white; EXIF rotation baked in). */
    fun imageToPdf(source: File, out: File, cancelled: () -> Boolean) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("${source.name} is not an image the phone can decode")
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("${source.name} could not be decoded")
        val upright = uprightBitmap(decoded, source)
        val flat = flatten(upright)
        try {
            if (cancelled()) throw IOException("cancelled")
            val page = PdfWriter.ImagePage(jpeg(flat), flat.width, flat.height)
            BufferedOutputStream(FileOutputStream(out)).use { PdfWriter.writeImages(listOf(page), it, cancelled) }
        } finally {
            if (flat !== upright) flat.recycle()
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    internal fun flatten(b: Bitmap): Bitmap = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { n ->
        Canvas(n).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
    }

    internal fun jpeg(b: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        if (!b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) throw IOException("the phone could not encode the page image")
        return bos.toByteArray()
    }

    /** Everything [ToPdf] writes goes through here, beside [source] or into [destDir]; never overwrites. Blocking: call off the main thread. */
    fun convert(ctx: Context, source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> {
        refusal(source.name)?.let { throw IOException(it) }
        val out = FileOps.uniqueIn(destDir, source.nameWithoutExtension + ".pdf")
        progress.report(0L, 1L, source.name)
        try {
            when (kindOf(source.name)) {
                Kind.IMAGE -> imageToPdf(source, out, cancelled)
                Kind.TEXT -> return TextToPdf.convert(source, destDir, progress, cancelled)
                Kind.HTML -> HtmlPdf.render(ctx, source, out, progress, cancelled)
                else -> throw IOException(refusal(source.name) ?: "unsupported")
            }
        } catch (e: Throwable) {
            out.delete()
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName)
        }
        progress.report(1L, 1L, out.name)
        return listOf(out)
    }
}

/** Convert… registry entry: image -> one-page PDF beside the source. */
object ImageToPdf : Converter {
    override val id = "image-pdf"
    override val label = "PDF document"
    override fun accepts(extension: String) = extension in ToPdf.IMAGE_EXT

    override fun convert(source: File, destDir: File, progress: FileOps.Progress, cancelled: () -> Boolean): List<File> {
        val out = Converters.output(source, destDir, "", "pdf")
        progress.report(0L, 1L, source.name)
        try { ToPdf.imageToPdf(source, out, cancelled) } catch (e: Throwable) { out.delete(); throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName) }
        progress.report(1L, 1L, out.name)
        return listOf(out)
    }
}
