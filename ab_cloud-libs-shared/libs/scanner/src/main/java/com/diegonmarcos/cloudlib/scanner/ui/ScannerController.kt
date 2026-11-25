package com.diegonmarcos.cloudlib.scanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudlib.scanner.*
import com.diegonmarcos.cloudlib.scanner.R
import java.io.File
import java.util.concurrent.Executors

/** What the host app (Cloud Camera) provides: the camera permission (asked through libs:perms, never here), the OCR engine, the way out. */
interface ScannerHost {
    fun cameraGranted(): Boolean
    fun requestCamera()
    fun ocr(): PageOcr
    fun close()
}

enum class Route { HOME, CAPTURE, EDITOR, TOOLS, TOOL }

enum class EditorTab { CROP, FILTER, TEXT }

/** #1908 every tool the Tools hub lists. [onPages] tools work on the scan in hand; the others take files. */
enum class ToolId(val id: String, val onPages: Boolean) {
    CROP("crop", true), PERSPECTIVE("perspective", true), FILTERS("filters", true), PAGE_OPS("page_ops", true), SCAN_PDF("scan_pdf", true),
    OCR("ocr", true), EXPORT("export", true),
    PDF_MERGE("pdf_merge", false), PDF_SPLIT("pdf_split", false), PDF_EXTRACT("pdf_extract", false), PDF_ROTATE("pdf_rotate", false),
    PDF_COMPRESS("pdf_compress", false), PDF_PROTECT("pdf_protect", false), PDF_TO_IMAGES("pdf_to_images", false), PDF_TO_PAGES("pdf_to_pages", false),
    IMG_CONVERT("img_convert", false), IMG_TO_PDF("img_to_pdf", false)
}

/** Which pages an export takes. */
enum class Scope { ALL, CURRENT, FROM_HERE }

class ToolInput(val name: String, val bytes: ByteArray, val pages: Int)

/** The typed parameters of the file tools. */
data class ToolParams(
    val rangesText: String = "",
    val degrees: Int = 90,
    val quality: Int = 60,
    val maxSide: Int = 1600,
    val password: String = "",
    val removePassword: Boolean = false,
    val widthPx: Int = 1240,
    val imageFormat: ImageFormat = ImageFormat.PNG,
    val convert: ImageTools.Convert = ImageTools.Convert(),
    val size: PageSize = PageSize.A4,
)

/**
 * #1908 the Scanner's state and its work, one object the Compose screens read: the pages and their selection, the route, the
 * export options, the busy line, and every action (import, capture, page operations, OCR, export, the file tools). All pixel and
 * PDF work runs on a worker thread; the screens only read state.
 */
class ScannerController(val context: Context, val host: ScannerHost) {
    val pages = mutableStateListOf<ScanPage>()
    var selected by mutableIntStateOf(-1)
    var route by mutableStateOf(Route.HOME)
    var tab by mutableStateOf(EditorTab.CROP)
    var tool by mutableStateOf<ToolId?>(null)
    var options by mutableStateOf(ExportOptions())
    var scope by mutableStateOf(Scope.ALL)
    var showExport by mutableStateOf(false)
    var applyAll by mutableStateOf(false)
    var busy by mutableStateOf("")
    var notice by mutableStateOf("")
    /** Bumped by every change to a page's content, so a screen drawing from it reads it. */
    var rev by mutableIntStateOf(0)
    // capture settings (they stay across visits)
    var flash by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var autoCapture by mutableStateOf(true)
    var batch by mutableStateOf(true)
    // file tools
    var inputs by mutableStateOf<List<ToolInput>>(emptyList())
    var imageUris by mutableStateOf<List<Uri>>(emptyList())
    var params by mutableStateOf(ToolParams())
    var results by mutableStateOf<List<ExportFile>>(emptyList())
    var resultNote by mutableStateOf("")
    /** The second half of a split scan, set aside until it is restored or exported. */
    var parked by mutableStateOf<List<ScanPage>>(emptyList())

    // the host's activity-result plumbing (set by the root composable)
    var pendingSave: List<ExportFile> = emptyList()
    var onSave: (List<ExportFile>) -> Unit = {}
    var onShare: (List<ExportFile>) -> Unit = {}
    var pickImages: () -> Unit = {}
    var pickPdfs: () -> Unit = {}
    var pickImagesForTool: () -> Unit = {}

    /** Where the next captured or imported page goes. */
    sealed interface Target { object Append : Target; class After(val index: Int) : Target; class Retake(val id: Long) : Target }
    var target: Target = Target.Append

    private var nextId = 1L
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val flatCache = LruCache<String, Raster>(6)
    private val bmpCache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount }

    fun destroy() { worker.shutdownNow() }

    fun current(): ScanPage? = pages.getOrNull(selected)

    fun bump() { rev++ }

    fun say(s: String) { notice = s }

    /** Runs [task] off the main thread with the busy line [label] up; [done] gets its value on the main thread, a failure is said. */
    fun <T> work(label: String, task: () -> T, done: (T) -> Unit = {}) {
        busy = label
        worker.execute {
            val r = runCatching(task)
            main.post {
                busy = ""
                r.onSuccess(done).onFailure { notice = context.getString(R.string.scanner_task_failed, it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    // ── input ────────────────────────────────────────────────────────────────────────────

    fun page(b: Bitmap): ScanPage = ScanPage(nextId++, b).also { it.quad = ScanPipeline.autoQuad(it) }

    /** Pages made on the worker are put where [target] says (a retake replaces the page and keeps its filter). */
    fun place(made: List<ScanPage>) {
        val t = target; target = Target.Append
        var retakeId = (t as? Target.Retake)?.id
        var after = (t as? Target.After)?.index
        for (p in made) {
            val at = if (retakeId != null) pages.indexOfFirst { it.id == retakeId } else -1
            when {
                at >= 0 -> { p.filter = pages[at].filter; pages[at] = p; selected = at; retakeId = null }
                after != null -> { val i = (after + 1).coerceIn(0, pages.size); pages.add(i, p); selected = i; after = i }
                else -> { pages.add(p); selected = pages.lastIndex }
            }
        }
        bump()
    }

    fun importUris(uris: List<Uri>, then: Route? = Route.EDITOR) {
        if (uris.isEmpty()) return
        work("import", {
            val made = uris.mapNotNull { u -> ImageTools.decode(context.contentResolver, u)?.let { page(it) } }
            made to (uris.size - made.size)
        }) { (made, failed) ->
            place(made)
            if (failed > 0) say(context.getString(R.string.scanner_import_failed))
            if (made.isNotEmpty() && then != null) { tab = EditorTab.CROP; route = then }
        }
    }

    fun addCaptured(file: File, goEditor: Boolean) {
        work("import", {
            val b = ImageTools.decode(context.contentResolver, Uri.fromFile(file))
            file.delete()
            b?.let { page(it) }
        }) { p ->
            if (p == null) { say(context.getString(R.string.scanner_import_failed)); return@work }
            place(listOf(p))
            if (goEditor) { tab = EditorTab.CROP; route = Route.EDITOR }
        }
    }

    // ── page operations ──────────────────────────────────────────────────────────────────

    fun select(i: Int) { if (i in pages.indices) { selected = i; bump() } }

    fun movePage(from: Int, to: Int) {
        val moved = PageOps.move(pages.toList(), from, to)
        pages.clear(); pages.addAll(moved); selected = to.coerceIn(0, pages.lastIndex); bump()
    }

    fun deletePage(i: Int) {
        if (i !in pages.indices) return
        pages.removeAt(i); selected = minOf(selected, pages.lastIndex)
        if (pages.isEmpty()) route = Route.HOME
        bump()
    }

    fun duplicatePage(i: Int) {
        val next = PageOps.duplicate(pages.toList(), i) { it.copyAs(nextId++) }
        pages.clear(); pages.addAll(next); selected = i + 1; bump()
    }

    /** Cuts the scan after page [i]: the pages after it are set aside ([parked]). */
    fun splitAfter(i: Int) {
        val (a, b) = PageOps.split(pages.toList(), i + 1)
        if (b.isEmpty()) return
        pages.clear(); pages.addAll(a); parked = b; selected = a.lastIndex; bump()
    }

    /** Puts the set-aside pages back at the end (merge). */
    fun restoreParked() {
        pages.addAll(PageOps.merge(emptyList(), parked)); parked = emptyList(); bump()
    }

    /** The set-aside pages become the scan in hand (the first part is set aside in turn). */
    fun swapParked() {
        val a = pages.toList(); pages.clear(); pages.addAll(parked); parked = a; selected = 0; bump()
    }

    fun beginRetake(i: Int) { pages.getOrNull(i)?.let { target = Target.Retake(it.id); route = Route.CAPTURE } }
    fun beginInsertAfter(i: Int) { target = Target.After(i); route = Route.CAPTURE }

    // ── editing ──────────────────────────────────────────────────────────────────────────

    fun setQuad(p: ScanPage, q: Quad) { p.quad = q; p.touch(); bump() }

    fun autoEdges(p: ScanPage) = work("working", { ScanPipeline.autoQuad(p) }) { setQuad(p, it) }

    fun wholePhoto(p: ScanPage) = setQuad(p, Quad.full(p.source.width, p.source.height))

    fun applyCrop(p: ScanPage, preset: CropPreset) {
        val r = CropMath.ratioOf(preset, p.source.width, p.source.height)
        setQuad(p, if (preset == CropPreset.FREE) p.quad else CropMath.reshaped(p.quad, p.source.width, p.source.height, r))
    }

    fun rotate(p: ScanPage, quarters: Int) { p.quarters += quarters; p.touch(); bump() }

    fun deskew(p: ScanPage, onDone: (Double) -> Unit) = work("working", { ScanPipeline.estimateDeskew(p) }) { d -> p.deskew = d; p.touch(); bump(); onDone(d) }

    fun setFilter(p: ScanPage, f: (FilterSpec) -> FilterSpec) {
        val n = f(p.filter)
        (if (applyAll) pages.toList() else listOf(p)).forEach { it.filter = n; it.touch() }
        bump()
    }

    fun setText(p: ScanPage, t: String) { p.text = t }

    // ── previews (blocking: call off the main thread) ────────────────────────────────────

    private fun flatKey(p: ScanPage, side: Int) = "${p.id}|${p.quad}|${p.quarters}|${p.deskew}|$side"

    private fun flat(p: ScanPage, side: Int): Raster {
        val k = flatKey(p, side)
        return flatCache.get(k) ?: ScanPipeline.flatten(p, side).also { flatCache.put(k, it) }
    }

    /** The page as exported, [side] px on its longer side. */
    fun rendered(p: ScanPage, side: Int): Bitmap {
        val k = "r|${p.id}|${p.rev}|$side"
        return bmpCache.get(k) ?: ScanPipeline.toBitmap(DocFilter.apply(flat(p, side), p.filter)).also { bmpCache.put(k, it) }
    }

    /** The page through [spec] (the filter carousel's live preview). */
    fun filtered(p: ScanPage, spec: FilterSpec, side: Int): Bitmap {
        val k = "f|${p.id}|${p.quad}|${p.quarters}|${p.deskew}|$spec|$side"
        return bmpCache.get(k) ?: ScanPipeline.toBitmap(DocFilter.apply(flat(p, side), spec)).also { bmpCache.put(k, it) }
    }

    // ── OCR ──────────────────────────────────────────────────────────────────────────────

    fun runOcr(targets: List<ScanPage>, then: () -> Unit = {}) {
        work("ocr", {
            var error: String? = null
            for (p in targets) {
                val bmp = ScanPipeline.toBitmap(ScanPipeline.render(p))
                val r = host.ocr().read(bmp); bmp.recycle()
                if (r.error != null) { error = r.error; break }
                p.text = r.text; p.words = r.words; p.ocrDone = true
            }
            error
        }) { err -> if (err != null) say(context.getString(R.string.scanner_ocr_failed, err)); bump(); then() }
    }

    // ── export ───────────────────────────────────────────────────────────────────────────

    fun exportPages(): List<ScanPage> = when (scope) {
        Scope.ALL -> pages.toList()
        Scope.CURRENT -> listOfNotNull(current())
        Scope.FROM_HERE -> if (selected in pages.indices) pages.subList(selected, pages.size).toList() else emptyList()
    }

    fun export(share: Boolean) {
        val list = exportPages()
        if (list.isEmpty()) { say(context.getString(R.string.scanner_empty)); return }
        val o = options
        work("export", {
            // A searchable PDF needs the text: read any page not read yet, once, before writing.
            if (o.format == ExportFormat.PDF) for (p in list) if (!p.ocrDone) {
                val bmp = ScanPipeline.toBitmap(ScanPipeline.render(p)); val r = host.ocr().read(bmp); bmp.recycle()
                if (r.error == null) { p.text = r.text; p.words = r.words; p.ocrDone = true }
            }
            ScannerExport.build(list, o)
        }) { files -> if (share) onShare(files) else onSave(files) }
    }

    // ── file tools ───────────────────────────────────────────────────────────────────────

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: (uri.lastPathSegment ?: "file")

    fun openTool(t: ToolId) {
        inputs = emptyList(); imageUris = emptyList(); results = emptyList(); resultNote = ""
        tool = t
        when (t) {
            ToolId.CROP, ToolId.PERSPECTIVE -> { tab = EditorTab.CROP; route = if (pages.isEmpty()) Route.HOME else Route.EDITOR }
            ToolId.FILTERS -> { tab = EditorTab.FILTER; route = if (pages.isEmpty()) Route.HOME else Route.EDITOR }
            ToolId.OCR -> { tab = EditorTab.TEXT; route = if (pages.isEmpty()) Route.HOME else Route.EDITOR }
            ToolId.PAGE_OPS, ToolId.SCAN_PDF -> route = Route.HOME
            ToolId.EXPORT -> { route = Route.HOME; showExport = pages.isNotEmpty() }
            else -> route = Route.TOOL
        }
    }

    fun loadPdfs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        work("import", {
            uris.map { u ->
                val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: error(displayName(u))
                ToolInput(displayName(u), bytes, PdfTools.pageCount(bytes))
            }
        }) { got -> inputs = if (tool == ToolId.PDF_MERGE) inputs + got else got }
    }

    fun loadToolImages(uris: List<Uri>) { if (uris.isNotEmpty()) imageUris = if (tool == ToolId.IMG_CONVERT || tool == ToolId.IMG_TO_PDF) imageUris + uris else uris }

    private fun stem(n: String) = ScannerExport.safeName(n.substringBeforeLast('.'))

    /** Runs the open file tool on its inputs; the files land in [results]. */
    fun runTool() {
        val t = tool ?: return
        val ins = inputs; val uris = imageUris; val p = params
        work("working", {
            val out = ArrayList<ExportFile>()
            var note = ""
            val pdf = "application/pdf"
            when (t) {
                ToolId.PDF_MERGE -> out.add(ExportFile("merged.pdf", pdf, PdfTools.merge(ins.map { it.bytes })))
                ToolId.PDF_SPLIT -> {
                    val src = ins.first()
                    val ranges = if (p.rangesText.isBlank()) (0 until src.pages).map { it..it } else PdfTools.parseRanges(p.rangesText, src.pages) ?: error("ranges")
                    PdfTools.split(src.bytes, ranges).forEachIndexed { i, b -> out.add(ExportFile("${stem(src.name)}_part${i + 1}.pdf", pdf, b)) }
                }
                ToolId.PDF_EXTRACT -> {
                    val src = ins.first()
                    val ranges = PdfTools.parseRanges(p.rangesText, src.pages) ?: error("ranges")
                    out.add(ExportFile("${stem(src.name)}_extract.pdf", pdf, PdfTools.extract(src.bytes, ranges.flatMap { it.toList() })))
                }
                ToolId.PDF_ROTATE -> {
                    val src = ins.first()
                    val pagesList = if (p.rangesText.isBlank()) emptyList() else (PdfTools.parseRanges(p.rangesText, src.pages) ?: error("ranges")).flatMap { it.toList() }
                    out.add(ExportFile("${stem(src.name)}_rotated.pdf", pdf, PdfTools.rotate(src.bytes, pagesList, p.degrees)))
                }
                ToolId.PDF_COMPRESS -> {
                    val src = ins.first()
                    val b = PdfTools.compress(src.bytes, p.quality, p.maxSide)
                    note = "${src.bytes.size / 1024} KB > ${b.size / 1024} KB"
                    out.add(ExportFile("${stem(src.name)}_compressed.pdf", pdf, b))
                }
                ToolId.PDF_PROTECT -> {
                    val src = ins.first()
                    out.add(if (p.removePassword) ExportFile("${stem(src.name)}_unlocked.pdf", pdf, PdfTools.unprotect(src.bytes, p.password))
                            else ExportFile("${stem(src.name)}_locked.pdf", pdf, PdfTools.protect(src.bytes, p.password)))
                }
                ToolId.PDF_TO_IMAGES -> {
                    val src = ins.first()
                    val bmps = PdfTools.toImages(src.bytes, p.widthPx, context.cacheDir)
                    bmps.forEachIndexed { i, b ->
                        val f = if (p.imageFormat == ImageFormat.PNG) ImageFormat.PNG else p.imageFormat
                        out.add(ExportFile("${stem(src.name)}_p${i + 1}.${f.ext}", f.mime, ImageTools.encode(b, f, 90))); b.recycle()
                    }
                }
                ToolId.IMG_CONVERT -> uris.forEachIndexed { i, u ->
                    val b = ImageTools.decode(context.contentResolver, u, 6000) ?: error(displayName(u))
                    out.add(ExportFile("${stem(displayName(u))}.${p.convert.format.ext}", p.convert.format.mime, ImageTools.convert(b, p.convert))); b.recycle()
                }
                ToolId.IMG_TO_PDF -> {
                    val bmps = uris.mapNotNull { ImageTools.decode(context.contentResolver, it) }
                    out.add(ExportFile("images.pdf", pdf, ImageTools.toPdf(bmps, p.size, 8.0, Orientation.AUTO, 85, "images")))
                    bmps.forEach { it.recycle() }
                }
                ToolId.PDF_TO_PAGES -> {
                    // not a file result: handled in the callback below
                }
                else -> {}
            }
            // a PDF result says how many pages it holds (a locked one cannot be read back without its password)
            if (note.isBlank() && t != ToolId.PDF_PROTECT) {
                val pdfs = out.filter { it.mime == pdf }
                if (pdfs.isNotEmpty()) note = context.getString(R.string.scanner_result_pdf, pdfs.size, pdfs.sumOf { PdfTools.pageCount(it.bytes) })
            }
            out to note
        }) { (files, note) -> results = files; resultNote = note }
    }

    /** "PDF to pages": every page of the first PDF becomes a scan page (merge a PDF into the scan in hand). */
    fun pdfToPages() {
        val src = inputs.firstOrNull() ?: return
        work("import", {
            PdfTools.toImages(src.bytes, 1600, context.cacheDir).map { page(it) }
        }) { made -> target = Target.Append; place(made); if (made.isNotEmpty()) route = Route.HOME }
    }
}
