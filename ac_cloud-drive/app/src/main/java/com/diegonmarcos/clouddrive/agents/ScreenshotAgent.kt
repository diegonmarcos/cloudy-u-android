package com.diegonmarcos.clouddrive.agents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Environment
import android.util.Log
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AGENTS ▸ SCREENSHOT OCR: every screenshot on the phone gets an OCR text layer (lines with boxes,
 * grouped into blocks), image labels, detected objects, dominant colours, any barcode, the app it
 * was taken in and the dates / times / links / mails / phones in its text — all ON DEVICE through
 * the fleet's image engine (libs:ml-l-image → Cloud-Lib-Ml-L-Image-Mlkit.apk: ML Kit text
 * recognition, image labelling, object detection with the full-label classifier, ZXing). Nothing
 * leaves the phone: detect is on-device whatever route the user chose for photos.
 *
 * SIDECARS (#1507): beside every screenshot, <name>.md (OCR as Markdown with tables) and <name>.json
 * (metadata + OCR), written by SidecarWriter through libs:ocr and skipped while their sha256 matches.
 *
 * OUTPUT: the published contract, data/agent-screenshots-contract.json (records/<id>.json,
 * index.jsonl, state.json under <shared_root>/.agents/screenshots/v1), also served by
 * ScreenshotAgentProvider. Cloud Tasks' event agent is the first reader.
 *
 * INCREMENTAL: a screenshot whose contract id (path + size + mtime) already has a record is
 * skipped, so a pass only works on what is new or edited, newest first; a record whose source is
 * gone or was edited is removed (and index.jsonl says so). A pass stopped half way (Stop, or
 * WorkManager's ten-minute window) keeps every record it wrote and the next pass resumes.
 * Blocking; ScreenshotAgentWorker runs it off the main thread.
 */
object ScreenshotAgent {
    private const val TAG = "ScreenshotAgent"

    data class Status(
        val running: Boolean = false,
        val done: Int = 0,
        val todo: Int = 0,
        val failed: Int = 0,
        val current: String = "",
        /** Why the last pass could not run or stopped (engine missing, no storage access), else null. */
        val message: String? = null,
        val finishedAtMs: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status
    private val busy = AtomicBoolean(false)
    private val stop = AtomicBoolean(false)

    fun store(): ScreenshotStore = ScreenshotStore(File(SharedStore.root(), ScreenshotStore.RELATIVE))

    /** Ask a running pass to stop after the screenshot it is on. */
    fun requestStop() { stop.set(true) }

    /** The folders Android writes screenshots to, under shared storage. */
    fun sources(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screenshots"),
            File(ext, "Screenshots"),
        ).filter { it.isDirectory }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
    }

    /** Every screenshot, newest first. */
    fun screenshots(): List<File> = sources().flatMap { d ->
        d.walkTopDown().onEnter { !it.name.startsWith(".") && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in ScreenshotStore.IMAGE_EXTENSIONS }.toList()
    }.sortedByDescending { it.lastModified() }

    /** Screenshots on the phone vs records written, without running anything (the page's counters). */
    fun counts(): Pair<Int, Int> {
        val files = screenshots()
        val have = store().ids()
        return files.size to files.count { ScreenshotRecord.idOf(it.absolutePath, it.length(), it.lastModified()) in have }
    }

    /**
     * One pass. [shouldStop] (the worker's isStopped) is polled per screenshot along with Stop.
     * [retryFailed] first drops every record that carries an error so it is read again.
     */
    fun runPass(ctx: Context, shouldStop: () -> Boolean = { false }, retryFailed: Boolean = false): Status {
        if (!busy.compareAndSet(false, true)) return _status.value
        stop.set(false)
        try {
            if (!Places.hasAllFilesAccess(ctx)) {
                return finish(Status(message = "Cloud Drive needs all-files access to read screenshots (Configs ▸ General ▸ Storage access)"))
            }
            val store = store()
            if (retryFailed) store.failedIds().forEach { store.remove(it) }
            val files = screenshots()
            val wanted = LinkedHashMap<String, File>()
            files.forEach { wanted[ScreenshotRecord.idOf(it.absolutePath, it.length(), it.lastModified())] = it }
            val have = store.ids()
            // Sources that are gone or were edited: their old records go.
            have.filter { it !in wanted.keys }.forEach { store.remove(it) }
            // Screenshot OCR: a screenshot is done when its record AND its <name>.md/.json pair (matching sha256) exist.
            val todo = wanted.filter { (id, f) -> id !in have || !SidecarWriter.isCurrent(f) }
            val before = store.state()
            _status.value = Status(running = true, todo = todo.size)
            if (todo.isEmpty()) {
                store.writeState(before.copy(lastScanMs = System.currentTimeMillis(), total = files.size, pending = 0))
                return finish(Status())
            }
            val engine = ImageScanEngine(ctx)
            engine.check(com.diegonmarcos.superapp.image.BuildConfig.IMAGE_DETECT_CONTRACT)?.let { problem ->
                store.writeState(before.copy(lastScanMs = System.currentTimeMillis(), total = files.size, pending = todo.size))
                return finish(Status(todo = todo.size, message = problem))
            }
            var done = 0; var failed = 0; var lastError: String? = null; var cursor = before.cursorMtimeMs
            for ((id, f) in todo) {
                if (stop.get() || shouldStop()) break
                _status.value = _status.value.copy(current = f.name, done = done, failed = failed)
                val kept = if (id in have) store.read(id) else null
                val r = kept ?: runCatching { process(ctx, engine, id, f) }.getOrElse { e -> failedRecord(id, f, e.message ?: e.toString()) }.also { store.write(it) }
                var err = r.error
                // An OCR that failed writes no sidecar (an empty .md would read as done); it is retried on the next pass.
                if (r.error?.contains("ocr:") != true && !r.failed) runCatching { SidecarWriter.write(ctx, f, r) }.onFailure { err = "sidecar: ${it.message ?: it}" }
                else if (err == null) err = "no sidecar written"
                if (err != null) { failed++; lastError = "${f.name}: $err" }
                done++
                cursor = maxOf(cursor, f.lastModified())
            }
            store.writeState(ScreenshotStore.State(System.currentTimeMillis(), before.processed + done, before.failed + failed, cursor, files.size, todo.size - done))
            Log.i(TAG, "pass: $done read, $failed with errors, ${todo.size - done} left")
            return finish(Status(done = done, todo = todo.size - done, failed = failed, message = if (done < todo.size) "stopped; the next pass resumes" else lastError))
        } finally {
            busy.set(false)
        }
    }

    private fun finish(s: Status): Status { _status.value = s.copy(running = false, finishedAtMs = System.currentTimeMillis()); return _status.value }

    /** Read one screenshot. A stage that fails leaves its part empty and says so in `error`. */
    fun process(ctx: Context, engine: ImageScanEngine, id: String, f: File): ScreenshotRecord {
        val orientation = runCatching { ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.absolutePath, it) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a decodable image" }
        val turned = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
        val w = if (turned) bounds.outHeight else bounds.outWidth
        val h = if (turned) bounds.outWidth else bounds.outHeight
        val errors = ArrayList<String>()

        val lines = runCatching { if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) readTiles(ctx, engine, f, w, h) else readWhole(engine, f, w, h) }
            .getOrElse { errors.add("ocr: ${it.message ?: it}"); emptyList() }
        val blocks = OcrLayout.blocks(lines)
        val text = OcrLayout.text(blocks)

        val labels = engine.detect(f, RecognitionConfig.detectRequest("labels", false)).also { it.error?.let { e -> errors.add("labels: $e") } }
        val objects = engine.detect(f, RecognitionConfig.detectRequest("objects", false)).also { it.error?.let { e -> errors.add("objects: $e") } }
        val barcode = runCatching { engine.decodeBarcode(f) }.getOrNull()
        val colours = runCatching { colours(f) }.getOrElse { errors.add("colours: ${it.message ?: it}"); emptyList() }

        val heuristic = buildList {
            val lum = colours.sumOf { luminance(it.hex) * it.share }
            if (colours.isNotEmpty()) add(ScreenshotRecord.Label(if (lum < 0.4) "Dark UI" else if (lum > 0.6) "Light UI" else "Mixed UI", 1.0, "heuristic"))
            if (text.isNotBlank()) add(ScreenshotRecord.Label(if (text.length > 400) "Text-heavy" else "Has text", 1.0, "heuristic"))
            if (barcode != null) add(ScreenshotRecord.Label(if (barcode.format.contains("QR")) "QR code" else "Barcode", 1.0, "heuristic"))
        }
        val objLabels = objects.boxes.flatMap { b -> listOf(Recognition.Label(b.label, b.p)) + b.alts }.map { ScreenshotRecord.Label(it.label, it.p, "object-detection") }
        val allLabels = (labels.labels.map { ScreenshotRecord.Label(it.label, it.p, "image-labeling") } + objLabels)
            .filter { it.label.isNotBlank() && it.label != "object" }
            .groupBy { it.label.lowercase() }.map { (_, v) -> v.maxBy { it.confidence } }
            .sortedByDescending { it.confidence } + heuristic

        return ScreenshotRecord(
            id = id, sourcePath = f.absolutePath, sourceSize = f.length(), sourceMtimeMs = f.lastModified(),
            takenAtMs = ScreenshotRecord.takenAt(f.name, exifTime(f), f.lastModified()), processedAtMs = System.currentTimeMillis(),
            width = w, height = h, text = text, lang = "und-Latn",
            blocks = blocks,
            labels = allLabels,
            objects = objects.boxes.map { b ->
                ScreenshotRecord.Obj(scaled(b, objects, w, h), (listOf(Recognition.Label(b.label, b.p)) + b.alts).filter { it.label.isNotBlank() }.map { ScreenshotRecord.Label(it.label, it.p, "object-detection") })
            },
            entities = ScreenshotRecord.entities(text),
            engine = mapOf(
                "ocr" to "mlkit-text-latin", "labeling" to (labels.model.ifBlank { "mlkit-image-labeling" }),
                "objects" to (objects.model.ifBlank { "mlkit-object-detection" }), "agent_version" to ScreenshotRecord.AGENT_VERSION.toString(),
            ),
            error = errors.joinToString("; ").ifBlank { null },
            colours = colours,
            barcode = barcode?.let { ScreenshotRecord.Barcode(it.format, it.rawValue) },
            sourceApp = ScreenshotRecord.sourceAppOf(f.name),
        )
    }

    /** OCR band by band (OcrLayout): each band crosses to the engine as a PNG in this app's cache. */
    private fun readTiles(ctx: Context, engine: ImageScanEngine, f: File, w: Int, h: Int): List<OcrLayout.TextLine> {
        val tiles = OcrLayout.tiles(w, h)
        if (tiles.size <= 1) return readWhole(engine, f, w, h)
        @Suppress("DEPRECATION") val decoder = BitmapRegionDecoder.newInstance(f.absolutePath, false)
        val dir = File(ctx.cacheDir, "agents").apply { mkdirs() }
        val out = ArrayList<OcrLayout.TextLine>()
        try {
            for (tile in tiles) {
                val band = decoder.decodeRegion(Rect(0, tile.top, w, tile.bottom), BitmapFactory.Options()) ?: continue
                val png = File(dir, "band.png")
                try {
                    png.outputStream().use { band.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { band.recycle() }
                val r = engine.detect(png, RecognitionConfig.detectRequest("text", false))
                png.delete()
                r.error?.let { error(it) }
                r.boxes.forEach { b ->
                    val box = OcrLayout.toSource(b.x, b.y, b.w, b.h, r.width, r.height, w, tile)
                    if (b.label.isNotBlank() && OcrLayout.owns(tile, box)) out.add(OcrLayout.TextLine(b.label, box, b.p.takeIf { it > 0 && it <= 1 }))
                }
            }
        } finally { decoder.recycle() }
        return out
    }

    private fun readWhole(engine: ImageScanEngine, f: File, w: Int, h: Int): List<OcrLayout.TextLine> {
        val r = engine.detect(f, RecognitionConfig.detectRequest("text", false))
        r.error?.let { error(it) }
        val whole = OcrLayout.Tile(0, h, 0, h)
        return r.boxes.filter { it.label.isNotBlank() }.map { b -> OcrLayout.TextLine(b.label, OcrLayout.toSource(b.x, b.y, b.w, b.h, r.width, r.height, w, whole), b.p.takeIf { it > 0 && it <= 1 }) }
    }

    /** A box from an analysed (downscaled) frame, back in source pixels. */
    private fun scaled(b: Recognition.Box, r: Recognition, w: Int, h: Int): List<Int> =
        OcrLayout.toSource(b.x, b.y, b.w, b.h, r.width, r.height, w, OcrLayout.Tile(0, h, 0, h)).list()

    /** The five biggest colour buckets (4 bits a channel) of a ~64 px thumbnail, as hex and share. */
    private fun colours(f: File): List<ScreenshotRecord.Colour> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.absolutePath, it) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 64) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return emptyList()
        try {
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val buckets = HashMap<Int, LongArray>()
            for (c in px) {
                val key = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
                val a = buckets.getOrPut(key) { LongArray(4) }
                a[0] += ((c shr 16) and 0xFF).toLong(); a[1] += ((c shr 8) and 0xFF).toLong(); a[2] += (c and 0xFF).toLong(); a[3]++
            }
            return buckets.values.sortedByDescending { it[3] }.take(5).map { a ->
                ScreenshotRecord.Colour("#%02x%02x%02x".format(a[0] / a[3], a[1] / a[3], a[2] / a[3]), a[3].toDouble() / px.size)
            }
        } finally { bmp.recycle() }
    }

    private fun luminance(hex: String): Double {
        val v = hex.removePrefix("#").toIntOrNull(16) ?: return 0.5
        return (0.2126 * ((v shr 16) and 0xFF) + 0.7152 * ((v shr 8) and 0xFF) + 0.0722 * (v and 0xFF)) / 255.0
    }

    private fun exifTime(f: File): Long? = runCatching {
        val v = ExifInterface(f.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(v)?.time
    }.getOrNull()

    private fun failedRecord(id: String, f: File, why: String) = ScreenshotRecord(
        id, f.absolutePath, f.length(), f.lastModified(), ScreenshotRecord.takenAt(f.name, null, f.lastModified()), System.currentTimeMillis(),
        0, 0, "", "und", emptyList(), emptyList(), emptyList(), emptyList(), mapOf("agent_version" to ScreenshotRecord.AGENT_VERSION.toString()), why,
        sourceApp = ScreenshotRecord.sourceAppOf(f.name),
    )
}
