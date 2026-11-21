package cld.camera.scanner

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

enum class ExportFormat(val ext: String, val mime: String) {
    PDF("pdf", "application/pdf"), JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), TXT("txt", "text/plain")
}

/** What the Export step asks for: the format, the base file name, the JPEG quality and the PDF sheet. */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val name: String = "scan",
    val quality: Int = 90,
    val size: PageSize = PageSize.A4,
    val marginMm: Double = 8.0,
    val orientation: Orientation = Orientation.AUTO,
)

/** One file to write: its name and its bytes. */
class ExportFile(val name: String, val mime: String, val bytes: ByteArray)

/** #1839 the export, off the pure parts: multi-page searchable PDF, JPEG / PNG per page, the text file. */
object ScannerExport {
    fun safeName(raw: String): String = raw.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_', '.').ifEmpty { "scan" }

    fun jpeg(b: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), it) }.toByteArray()
    fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** The whole text, one section per page when there are several. */
    fun fullText(pages: List<ScanPage>): String =
        if (pages.size == 1) pages[0].text else pages.mapIndexed { i, p -> "--- ${i + 1} ---\n${p.text}" }.joinToString("\n\n")

    /** Every file the [o] export produces for [pages]; renders each page once. Blocking. */
    fun build(pages: List<ScanPage>, o: ExportOptions): List<ExportFile> {
        val base = safeName(o.name)
        return when (o.format) {
            ExportFormat.TXT -> listOf(ExportFile("$base.txt", o.format.mime, fullText(pages).toByteArray(Charsets.UTF_8)))
            ExportFormat.PDF -> {
                val data = ArrayList<PdfPageData>(); val plan = ArrayList<PlanPage>(); val jpegs = ArrayList<Triple<ByteArray, Int, Int>>()
                for (p in pages) {
                    val bmp = ScanPipeline.toBitmap(ScanPipeline.render(p))
                    jpegs.add(Triple(jpeg(bmp, o.quality), bmp.width, bmp.height))
                    plan.add(PlanPage(bmp.width, bmp.height, p.words))
                    bmp.recycle()
                }
                PdfPlan.build(plan, o.size, o.marginMm, o.orientation).forEachIndexed { i, pl -> data.add(PdfPageData(pl, jpegs[i].first, jpegs[i].second, jpegs[i].third)) }
                val out = ByteArrayOutputStream()
                PdfWriter.write(out, data, base)
                listOf(ExportFile("$base.pdf", o.format.mime, out.toByteArray()))
            }
            ExportFormat.JPEG, ExportFormat.PNG -> pages.mapIndexed { i, p ->
                val bmp = ScanPipeline.toBitmap(ScanPipeline.render(p))
                val bytes = if (o.format == ExportFormat.PNG) png(bmp) else jpeg(bmp, o.quality)
                bmp.recycle()
                ExportFile(if (pages.size == 1) "$base.${o.format.ext}" else "${base}_${i + 1}.${o.format.ext}", o.format.mime, bytes)
            }
        }
    }

    const val AUTHORITY_SUFFIX = ".scanshare"

    /** The app's private scan folder (cache; the manifest's FileProvider serves it). */
    fun dir(ctx: Context): File = File(ctx.cacheDir, "scans").also { it.mkdirs() }

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + AUTHORITY_SUFFIX, f)

    /** Writes [files] into the private folder and returns their share Uris. */
    fun stage(ctx: Context, files: List<ExportFile>): List<Uri> = files.map { f -> uriFor(ctx, File(dir(ctx), f.name).also { it.writeBytes(f.bytes) }) }
}
