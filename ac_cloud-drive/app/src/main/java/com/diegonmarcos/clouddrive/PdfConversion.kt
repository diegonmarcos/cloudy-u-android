package com.diegonmarcos.clouddrive

import android.content.Context
import android.net.Uri
import com.diegonmarcos.clouddrive.files.FileOps
import java.io.File
import java.io.IOException

/**
 * PDF → txt / md / html / csv, written NEXT TO the source (#458, on the #577 pdfium engine).
 * The ONE conversion path: the reader's Convert menu and the Files tab's row action both call
 * this, so there is no second, looser writer. The text comes from the same engine the reader
 * draws with ([PdfEngine.pageText]); the file goes out through [FileOps.writeText], the atomic
 * save path. A scan (no text layer) is refused loudly instead of producing an empty file that
 * looks like success. docx / xlsx / odt are not offered — layout reconstruction needs the fleet
 * converter service. Blocking; callers run it off the UI thread.
 */
object PdfConversion {

    const val PDF_MIME = "application/pdf"

    /** Reads one rendered page image; the default is the fleet's image-scan engine (on-device ML Kit, reached by binder). */
    fun interface PageOcr { fun read(page: android.graphics.Bitmap): Result<String> }

    /** libs:ml-l-image's ImageScanEngine: the handshake first, so a missing engine is a sentence, never a crash. */
    fun engineOcr(ctx: Context): PageOcr {
        val engine = com.diegonmarcos.superapp.image.mlkit.ImageScanEngine(ctx)
        return PageOcr { bmp ->
            engine.check()?.let { return@PageOcr Result.failure(IOException(it)) }
            val r = engine.recognizeText(bmp)
            if (r.error != null && r.text.isBlank()) Result.failure(IOException(r.error)) else Result.success(r.text)
        }
    }

    private const val OCR_WIDTH = 1700
    private const val OCR_MAX_PAGES = 100

    /** Pages of OCR text: each page is rendered by the ONE engine (pdfium) to a bitmap and read by [ocr]. */
    internal fun ocrPages(engine: PdfEngine, ocr: PageOcr, progress: FileOps.Progress?, cancelled: () -> Boolean, name: String): List<String> {
        if (engine.pageCount > OCR_MAX_PAGES) throw IOException("$name has ${engine.pageCount} pages; OCR on the phone reads up to $OCR_MAX_PAGES")
        return (0 until engine.pageCount).map { i ->
            if (cancelled()) throw IOException("cancelled")
            progress?.report(i.toLong(), engine.pageCount.toLong(), "OCR page ${i + 1}/${engine.pageCount}")
            val size = engine.pageSize(i) ?: throw IOException("page ${i + 1} of $name cannot be read")
            val w = OCR_WIDTH; val h = (w * size[1] / size[0]).toInt().coerceIn(1, 4000)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(android.graphics.Color.WHITE)
                if (!engine.render(i, bmp, 0, 0, w, h)) throw IOException("page ${i + 1} of $name cannot be drawn")
                PdfConvert.cleanPage(ocr.read(bmp).getOrElse { throw IOException("OCR unavailable: " + (it.message ?: it.javaClass.simpleName)) })
            } finally { bmp.recycle() }
        }
    }

    /**
     * The written file, or the reason. Text-layer pages first; a PDF without a text layer (a scan) falls back
     * to OCR, and target `ocr` always reads the rendered pages. md / html get heading, paragraph and list
     * structure ([PdfStructure]). [progress] and [cancelled] serve the job card.
     */
    fun convertPdf(
        ctx: Context, source: File, target: String,
        progress: FileOps.Progress? = null, cancelled: () -> Boolean = { false },
        ocr: PageOcr? = null, destDir: File? = null,
    ): Result<File> {
        if (target !in PdfConvert.DIALOG_TARGETS) return Result.failure(IOException("unknown conversion target: $target"))
        if (!source.isFile) return Result.failure(IOException("not a file: ${source.name}"))
        val engine = try {
            PdfEngine.open(ctx, Uri.fromFile(source), null)
        } catch (locked: PdfEngine.PasswordRequired) {
            return Result.failure(IOException(source.name + " is password protected — open it in the reader and enter the password first"))
        } catch (error: Throwable) {
            return Result.failure(IOException("cannot convert this PDF: " + (error.message ?: error.javaClass.simpleName)))
        }
        return try {
            val reader = ocr ?: engineOcr(ctx)
            val pages = if (target == PdfConvert.OCR) ocrPages(engine, reader, progress, cancelled, source.name) else {
                progress?.report(0L, 1L, source.name)
                val text = (0 until engine.pageCount).map { PdfConvert.cleanPage(engine.pageText(it)) }
                if (PdfConvert.hasNoText(text)) ocrPages(engine, reader, progress, cancelled, source.name) else text
            }
            if (PdfConvert.hasNoText(pages)) return Result.failure(IOException(PdfConvert.NO_TEXT_LAYER_MESSAGE))
            val directory = destDir ?: source.parentFile ?: return Result.failure(IOException("no folder to save next to"))
            val name = PdfConvert.freeName(directory.list()?.toSet() ?: emptySet(), PdfConvert.preferredName(source.name, target))
            val out = File(directory, name)
            val body = when (target) {
                "md" -> PdfStructure.toMarkdown(pages)
                "html" -> PdfStructure.toHtml(pages, source.name)
                PdfConvert.OCR -> PdfConvert.build(pages, "txt", source.name)
                else -> PdfConvert.build(pages, target, source.name)
            }
            FileOps.writeText(File(directory, name), body)
            Result.success(out)
        } catch (error: Exception) {
            Result.failure(IOException(if (error.message == "cancelled") "cancelled" else "cannot convert this PDF: " + (error.message ?: error.javaClass.simpleName)))
        } finally {
            engine.close()
        }
    }
}
