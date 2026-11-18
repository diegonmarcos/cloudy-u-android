package cld.camera.objects

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import cld.camera.R
import cld.camera.analyzer.ImageContentScanner
import cld.camera.ui.activities.MainActivity
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.Scene
import com.diegonmarcos.superapp.vision.AnalysisGate
import com.diegonmarcos.superapp.vision.AnalyzerLifecycle
import com.diegonmarcos.superapp.vision.Detection
import com.diegonmarcos.superapp.vision.FrameResult
import com.diegonmarcos.superapp.vision.OcrCache
import com.diegonmarcos.superapp.vision.SceneOverlayView
import com.diegonmarcos.superapp.vision.TextLayout
import com.diegonmarcos.superapp.vision.TextSelection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * #1533 the Objects page: live, on-device object detection over the CameraX preview. Boxes, labels, confidence and
 * tracking ids come from the fleet's ML engine, drawn by libs:ml-l-vision-mlkit's overlay; a slider sets the minimum
 * confidence, Pause freezes the analysis and the boxes, and the shutter saves the frame with its JSON.
 *
 * #1805 Objects is a scene: on an engine with contract 4 every frame goes through the engine's `scene` method —
 * objects from the chosen detector (EfficientDet-Lite0 on MediaPipe or YOLO11n on LiteRT; Detector button), and TEXT
 * as a class of its own: every text region framed and labelled "Text". While text is in view the whole frame is read
 * (ML Kit v2, five scripts) on a second thread at the declared OCR cadence, and its layout — blocks, lines, words with
 * boxes — is CACHED with the frame it read ([OcrCache]). Tapping a word selects and copies it (again: its line, then
 * its block); the handles extend the selection; Copy all and Share take the whole text or the selection. Pause
 * freezes the newest read frame with its cached text (read once if it had none), Open reads a picked image the same
 * way, and either can be selected without running anything again. An older engine keeps the #1533 detect path.
 *
 * Battery: the analyzer is an ImageAnalysis with STRATEGY_KEEP_ONLY_LATEST on a background executor, spaced by the
 * declared interval (recognition.json scene.min_frame_interval_ms, or detect.interval_ms on the old path), and it is
 * installed ONLY while the page is shown and not paused ([AnalyzerLifecycle]): leaving the page, pausing the activity
 * or pressing Pause removes it.
 */
class ObjectsController(
    private val activity: MainActivity,
    private val overlay: SceneOverlayView,
    private val panel: View,
    private val status: TextView,
    private val pauseButton: Button,
    private val threshold: SeekBar,
    private val thresholdValue: TextView,
    private val still: ImageView,
    private val detectorButton: Button,
    private val openButton: Button,
    private val copyAllButton: Button,
    private val shareButton: Button,
    private val textStatus: TextView,
) {
    private val scanner by lazy { ImageContentScanner(activity) }
    private val detector by lazy { EngineObjectDetector(scanner, RecognitionConfig.defaultDetectMode()) }
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val sceneDecl: JSONObject = Scene.decl()
    private val gate = AnalysisGate(
        if (sceneDecl.has("min_frame_interval_ms")) sceneDecl.optLong("min_frame_interval_ms", 0)
        else RecognitionConfig.json.getJSONObject("detect").optLong("interval_ms", 150),
        SystemClock::elapsedRealtime,
    )
    private var analysis: ImageAnalysis? = null

    private val lifecycle = AnalyzerLifecycle(object : AnalyzerLifecycle.Host {
        override fun attach() { analysis?.setAnalyzer(analysisExecutor) { analyze(it) } }
        override fun detach() { analysis?.clearAnalyzer() }
    }, gate)

    @Volatile private var last: FrameResult? = null
    @Volatile private var lastText: List<Detection> = emptyList()
    @Volatile private var mirrored = false
    private var paused = false

    /** #1805 the scene path's state: whether the engine answers scene, the frame counter, cadence, cache and fps. */
    @Volatile private var sceneReady: Boolean? = null
    @Volatile private var sceneError: String? = null
    private val frames = AtomicLong(0)
    private val throttle = SceneThrottle(sceneDecl.optLong("text_interval_ms", 300), sceneDecl.optLong("ocr_interval_ms", 1200))
    private val fps = FpsMeter()
    /** Pixels are never recycled by hand here: an evicted frame may still be on screen or in an OCR call; the GC frees it. */
    private val cache = OcrCache<Bitmap>(CACHE)
    @Volatile private var layout: TextLayout? = null
    @Volatile private var layoutGeo: SceneOverlayView.Geometry? = null
    @Volatile private var lastFrameStill: Pair<Long, Bitmap>? = null
    @Volatile private var lastLatencyMs = 0L

    /** What is frozen on screen instead of the live stream: a paused frame or an opened image; null while live. */
    private var stillBitmap: Bitmap? = null
    private var stillLabel: String? = null

    /** The minimum confidence the slider sets, 0..1. */
    var minConfidence: Float = activity.camConfig.commonPref.getInt(KEY_MIN_PERCENT, DEFAULT_PERCENT) / 100f
        private set

    /** #1805 the object detector the scene runs (recognition.json scene.detectors; the declared default until picked). */
    var detectorId: String = activity.camConfig.commonPref.getString(KEY_DETECTOR, null)?.takeIf { it in Scene.detectors() } ?: Scene.defaultDetector()
        private set

    init {
        threshold.progress = Math.round(minConfidence * 100f)
        thresholdValue.text = percent()
        threshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                minConfidence = progress / 100f
                thresholdValue.text = percent()
                if (fromUser) activity.camConfig.commonPref.edit().putInt(KEY_MIN_PERCENT, progress).apply()
                render()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        pauseButton.setOnClickListener { setPaused(!paused) }
        detectorButton.setOnClickListener { nextDetector() }
        openButton.setOnClickListener { activity.objectsImagePicker.launch("image/*") }
        copyAllButton.setOnClickListener { copyAll() }
        shareButton.setOnClickListener { share() }
        detectorButton.text = detectorLabel()
        overlay.listener = object : SceneOverlayView.Listener {
            override fun onSelected(text: String, selection: TextSelection) = copy(text, selection)
            override fun onCleared() { textStatus.text = textSummary() }
        }
    }

    private fun percent() = "${Math.round(minConfidence * 100f)}%"

    private fun detectorLabel() = activity.getString(R.string.objects_detector, Scene.detectors()[detectorId] ?: detectorId)

    /** A fresh analysis use case for the camera about to be bound (the previous one is gone with the old binding). */
    fun analysisUseCase(aspectRatio: Int): ImageAnalysis {
        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)).build()
        val ia = ImageAnalysis.Builder()
            .setResolutionSelector(ratio)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .build()
        analysis = ia
        mirrored = activity.camConfig.lensFacing == CameraSelector.LENS_FACING_FRONT
        // The page is shown: (re)install the analyzer on the new use case unless the user paused it.
        lifecycle.onHidden()
        lifecycle.onShown()
        return ia
    }

    /** The Objects page was picked: its controls and boxes appear. */
    fun enter() {
        panel.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        status.setText(R.string.objects_looking)
        textStatus.text = ""
        last = null
        overlay.clear()
    }

    /** The page was left: analysis stops at once and its controls go. */
    fun leave() {
        lifecycle.onHidden()
        panel.visibility = View.GONE
        overlay.visibility = View.GONE
        overlay.clear()
        last = null
        lastText = emptyList()
        paused = false
        pauseButton.setText(R.string.objects_pause)
        gate.paused = false
        unfreeze()
        throttle.reset()
        fps.reset()
    }

    /** The activity left the screen: stop analysing (the camera unbinds with it); the next bind re-installs the analyzer. */
    fun onActivityPaused() = lifecycle.onHidden()

    private fun setPaused(p: Boolean) {
        paused = p
        pauseButton.setText(if (p) R.string.objects_resume else R.string.objects_pause)
        lifecycle.setPaused(p)
        if (p) freeze() else unfreeze()
    }

    private fun nextDetector() {
        val ids = Scene.detectors().keys.toList()
        detectorId = ids[(ids.indexOf(detectorId) + 1).mod(ids.size)]
        activity.camConfig.commonPref.edit().putString(KEY_DETECTOR, detectorId).apply()
        detectorButton.text = detectorLabel()
        fps.reset()
        // A frozen frame or an opened image is detected again with the new detector (its cached text stays).
        stillBitmap?.let { b -> io.execute { detectStill(b, keepText = true) } }
    }

    private fun sceneAvailable(): Boolean {
        sceneReady?.let { return it }
        val why = runCatching { scanner.sceneStatus() }.getOrElse { it.message }
        sceneError = why
        return (why == null).also { sceneReady = it }
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!gate.admits()) return
            val frame = image.toBitmap()
            try {
                if (sceneAvailable()) analyzeScene(frame) else {
                    val r = detector.detect(frame)
                    if (detector.error == null) last = r
                }
            } finally {
                frame.recycle()
            }
            activity.runOnUiThread { render() }
        } finally {
            image.close()
        }
    }

    /** #1805 one live frame: objects (+ text regions at the text cadence) now; the OCR beside it when text is in view. */
    private fun analyzeScene(frame: Bitmap) {
        val id = frames.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val live = fit(frame, sceneDecl.optInt("live_side", 480))
        val wantText = throttle.wantText(now)
        val s = try {
            scanner.scene(live, Scene.request(detectorId, objects = true, text = wantText, ocr = false, frameId = id))
        } finally {
            if (live !== frame) live.recycle()
        }
        if (!s.ok) { sceneError = s.error; return }
        sceneError = null
        fps.tick(SystemClock.elapsedRealtime())
        lastLatencyMs = s.latencyMs
        last = SceneFrames.objects(s)
        if (wantText) {
            lastText = SceneFrames.textRegions(s)
            throttle.sawText(s.textPresent)
        }
        // The frame a freeze would show, at the OCR's size (the live copy is too small to read from).
        val big = fit(frame, sceneDecl.optInt("ocr_side", 1280)).let { if (it === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else it }
        lastFrameStill = id to big
        if (throttle.startOcr(now)) {
            val geo = SceneOverlayView.Geometry(big.width, big.height, 0, mirrored, cover = true)
            ocrExecutor.execute { readText(id, big, geo, live = true) }
        }
    }

    /** The OCR of one frame ([pixels], already the OCR's size), into the cache; shown at once when [live] and still live. */
    private fun readText(id: Long, pixels: Bitmap, geo: SceneOverlayView.Geometry, live: Boolean) {
        try {
            val req = Scene.request(detectorId, objects = false, text = false, ocr = true, frameId = id)
            req.getJSONObject("scene").put("live_side", maxOf(pixels.width, pixels.height)).put("ocr_side", maxOf(pixels.width, pixels.height))
            val s = scanner.scene(pixels, req)
            val l = if (s.ok) SceneFrames.layout(s) else null
            if (l != null) {
                cache.put(id, l, pixels, SystemClock.elapsedRealtime())
                if (!live || stillBitmap == null) activity.runOnUiThread { showText(l, geo) }
            } else if (!s.ok) sceneError = s.error
        } finally {
            if (live) throttle.ocrDone()
        }
    }

    private fun showText(l: TextLayout, geo: SceneOverlayView.Geometry) {
        layout = l
        layoutGeo = geo
        overlay.showText(l, geo)
        textStatus.text = textSummary()
        logWords()
    }

    private fun textSummary(): String {
        val l = layout ?: return ""
        return if (l.isEmpty) activity.getString(R.string.objects_text_none)
        else activity.getString(R.string.objects_text_read, l.blocks.size, l.lines.size, l.words.size)
    }

    /** Pause: the newest frame whose text is read stays on screen with that text; with none recent, the last frame is read once. */
    private fun freeze() {
        if (stillBitmap != null) return
        val fresh = cache.freshSince(SystemClock.elapsedRealtime() - FREEZE_REUSE_MS)
        if (fresh != null) {
            showStill(fresh.pixels, cover = true, label = null)
            showText(fresh.layout, SceneOverlayView.Geometry(fresh.pixels.width, fresh.pixels.height, 0, mirrored, true))
            Log.i(TAG, "freeze: frame ${fresh.frameId} reuses its cached text (${fresh.layout.words.size} words)")
            return
        }
        val (id, pixels) = lastFrameStill ?: return
        showStill(pixels, cover = true, label = null)
        if (sceneReady == true) {
            val geo = SceneOverlayView.Geometry(pixels.width, pixels.height, 0, mirrored, true)
            ocrExecutor.execute { readText(id, pixels, geo, live = false) }
        }
    }

    private fun showStill(b: Bitmap, cover: Boolean, label: String?) {
        stillBitmap = b
        stillLabel = label
        still.scaleType = if (cover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        // The analysis frame is not mirrored; a front-camera freeze shows it as the preview did.
        still.scaleX = if (cover && mirrored) -1f else 1f
        still.setImageBitmap(b)
        still.visibility = View.VISIBLE
    }

    private fun unfreeze() {
        still.setImageDrawable(null)
        still.visibility = View.GONE
        still.scaleX = 1f
        stillBitmap = null
        stillLabel = null
        layout = null
        layoutGeo = null
        overlay.showText(null, null)
        textStatus.text = ""
    }

    /** #1805 an opened image ([uri] from the picker) or a file (the emulator check): detected and read once, then selectable. */
    fun openUri(uri: Uri) = open { decode(uri) }

    fun openFile(file: File) = open { decode(Uri.fromFile(file)) }

    private fun open(load: () -> Bitmap?) {
        // The live analysis stops (Resume goes back to it); nothing of the last live frame is read.
        if (!paused) {
            paused = true
            pauseButton.setText(R.string.objects_resume)
            lifecycle.setPaused(true)
        }
        unfreeze()
        status.setText(R.string.objects_reading)
        io.execute {
            val b = runCatching { load() }.getOrNull()
            if (b == null) { activity.runOnUiThread { status.setText(R.string.objects_open_failed) }; return@execute }
            activity.runOnUiThread { showStill(b, cover = false, label = "image") }
            detectStill(b, keepText = false)
        }
    }

    /** Objects, text regions and (unless [keepText] and it is cached) the OCR of the still [b], in one engine call. */
    private fun detectStill(b: Bitmap, keepText: Boolean) {
        if (!sceneAvailable()) { activity.runOnUiThread { status.text = sceneError ?: activity.getString(R.string.objects_open_failed) }; return }
        val side = maxOf(b.width, b.height)
        val id = -(frames.incrementAndGet())
        val wantOcr = !(keepText && layout != null)
        val req = Scene.request(detectorId, objects = true, text = true, ocr = wantOcr, frameId = id)
        req.getJSONObject("scene").put("live_side", side).put("ocr_side", side)
        val s = scanner.scene(b, req)
        if (!s.ok) { sceneError = s.error; activity.runOnUiThread { render() }; return }
        sceneError = null
        last = SceneFrames.objects(s)
        lastText = SceneFrames.textRegions(s)
        lastLatencyMs = s.latencyMs
        val l = SceneFrames.layout(s)
        val cover = stillLabel == null
        if (l != null) cache.put(id, l, b, SystemClock.elapsedRealtime())
        activity.runOnUiThread {
            if (l != null) showText(l, SceneOverlayView.Geometry(s.width, s.height, 0, cover && mirrored, cover))
            render()
        }
    }

    private fun decode(uri: Uri): Bitmap? = ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { dec, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        val max = sceneDecl.optInt("ocr_side", 1280).coerceAtLeast(OPEN_MIN_SIDE)
        if (longest > max) {
            val s = max.toFloat() / longest
            dec.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
        }
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }

    private fun render() {
        val err = (if (sceneReady == true) sceneError else detector.takeIf { last == null }?.error)?.takeIf { last == null }
        val r = last
        if (r == null) {
            overlay.show(emptyList(), emptyList(), SceneOverlayView.Geometry(0, 0))
            if (err != null) status.text = err
            return
        }
        val shown = r.atLeast(minConfidence)
        val still = stillBitmap != null
        val cover = !still || stillLabel == null
        val geo = SceneOverlayView.Geometry(r.frameWidth, r.frameHeight, 0, mirrored && cover, cover)
        // Text regions are a class of their own: every one is framed, whatever the objects' slider says.
        overlay.show(shown, lastText, geo)
        val head = if (shown.isEmpty()) activity.getString(R.string.objects_nothing)
        else activity.getString(R.string.objects_count, shown.size) + ": " + shown.joinToString(", ") { it.caption() }
        val text = if (lastText.isNotEmpty()) " · " + activity.getString(R.string.objects_text_regions, lastText.size) else ""
        val speed = if (sceneReady == true && !still) " · %.1f fps · %d ms".format(fps.fps(), lastLatencyMs) else ""
        status.text = head + text + speed
    }

    // ── text actions ──────────────────────────────────────────────────────────

    private fun copy(text: String, selection: TextSelection?) {
        if (text.isBlank()) return
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.objects_text_clip), text))
        textStatus.text = activity.getString(R.string.objects_text_copied, text.length) + "\n" + text.take(PREVIEW_CHARS)
        Log.i(TAG, "copied ${JSONObject.quote(text)} selection=${selection?.let { "${it.start}..${it.end}" } ?: "all"}")
    }

    private fun copyAll() {
        val l = layout ?: run { toast(activity.getString(R.string.objects_text_none)); return }
        val sel = overlay.selectAll() ?: run { toast(activity.getString(R.string.objects_text_none)); return }
        copy(sel.text(l), null)
    }

    private fun share() {
        val text = overlay.selectedText.ifBlank { layout?.allText.orEmpty() }
        if (text.isBlank()) { toast(activity.getString(R.string.objects_text_none)); return }
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), activity.getString(R.string.objects_share)))
    }

    /** For an on-device or emulator check: every word's centre on screen, once a layout is shown. */
    private fun logWords() {
        overlay.post {
            val at = IntArray(2).also { overlay.getLocationOnScreen(it) }
            val words = JSONArray()
            overlay.wordBoxes().take(LOG_WORDS).forEach { (t, b) ->
                words.put(JSONObject().put("text", t).put("x", Math.round(at[0] + b.centerX)).put("y", Math.round(at[1] + b.centerY))
                    .put("l", Math.round(at[0] + b.left)).put("t", Math.round(at[1] + b.top)).put("r", Math.round(at[0] + b.right)).put("b", Math.round(at[1] + b.bottom)))
            }
            Log.i(TAG, "words ${words}")
        }
    }

    // ── benchmark (the emulator check) ────────────────────────────────────────

    /**
     * #1805 the benchmark over one image: the engine's own timing of each detector and the OCR (scene_bench), then the
     * app's end-to-end frame loop (frame → JPEG → binder → detect → answer) per detector, as a live viewfinder runs it.
     * The JSON is logged (tag ObjectsScene, line "bench ") and written to files/objects-bench.json.
     */
    fun bench(file: File, frames: Int = BENCH_FRAMES) {
        status.setText(R.string.objects_bench_running)
        io.execute {
            val out = JSONObject().put("image", file.name)
            out.put("engine", runCatching { scanner.sceneBench(file, Scene.benchRequest()) }.getOrElse { JSONObject().put("error", it.message) })
            val bmp = runCatching { decode(Uri.fromFile(file)) }.getOrNull()
            val e2e = JSONObject()
            if (bmp != null) {
                val live = fit(bmp, sceneDecl.optInt("live_side", 480))
                for (d in Scene.detectors().keys) {
                    val ms = ArrayList<Long>()
                    var lastScene: Scene? = null
                    repeat(BENCH_WARMUP) { scanner.scene(live, Scene.request(d, objects = true, text = false, ocr = false)) }
                    val throttleB = SceneThrottle(sceneDecl.optLong("text_interval_ms", 300), Long.MAX_VALUE)
                    repeat(frames) {
                        val a = SystemClock.elapsedRealtime()
                        lastScene = scanner.scene(live, Scene.request(d, objects = true, text = throttleB.wantText(a), ocr = false))
                        ms += SystemClock.elapsedRealtime() - a
                    }
                    val mean = ms.average()
                    e2e.put(d, JSONObject().put("frames", frames).put("mean_ms", mean).put("p50_ms", ms.sorted()[ms.size / 2]).put("p90_ms", ms.sorted()[(ms.size * 9 / 10).coerceAtMost(ms.size - 1)])
                        .put("fps", if (mean > 0) 1000.0 / mean else 0.0).put("ok", lastScene?.ok == true).put("error", lastScene?.error ?: JSONObject.NULL)
                        .put("objects", JSONArray().apply { lastScene?.objects?.forEach { put(JSONObject().put("cls", it.cls).put("label", it.label).put("p", it.p)) } }))
                }
                if (live !== bmp) live.recycle()
                bmp.recycle()
            }
            out.put("end_to_end", e2e).put("target_fps", sceneDecl.optInt("target_fps", 10)).put("default_detector", Scene.defaultDetector())
            runCatching { File(activity.filesDir, "objects-bench.json").writeText(out.toString(2)) }
            // Also where adb can pull it without run-as (a release build is not debuggable).
            runCatching { activity.getExternalFilesDir(null)?.let { File(it, "objects-bench.json").writeText(out.toString(2)) } }
            Log.i(TAG, "bench $out")
            activity.runOnUiThread { status.text = activity.getString(R.string.objects_bench_done) }
        }
    }

    // ── snapshot ──────────────────────────────────────────────────────────────

    /** The shutter: a full photo saved with the detections the screen shows (and #1805 the text read on screen). */
    fun snapshot() {
        val shot: ImageCapture = activity.camConfig.imageCapture ?: return
        val atShot = last
        val min = minConfidence
        val text = layout
        val tmp = File.createTempFile("objects", ".jpg", activity.cacheDir)
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(tmp).build(), io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val saved = runCatching { ObjectsSnapshot.save(activity, tmp, atShot, min, text, lastText) }
                tmp.delete()
                toast(saved.fold({ activity.getString(R.string.objects_saved) }, { activity.getString(R.string.objects_save_failed, it.message ?: it.toString()) }))
            }

            override fun onError(e: ImageCaptureException) {
                tmp.delete()
                toast(activity.getString(R.string.objects_save_failed, e.message ?: e.toString()))
            }
        })
    }

    private fun toast(msg: String) = activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }

    private fun fit(b: Bitmap, side: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (side <= 0 || longest <= side) return b
        val s = side.toFloat() / longest
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    companion object {
        private const val TAG = "ObjectsScene"
        private const val KEY_MIN_PERCENT = "objects_min_confidence_percent"
        private const val KEY_DETECTOR = "objects_scene_detector"
        private const val DEFAULT_PERCENT = 40
        /** Frames whose read text is kept (each with its OCR-sized pixels). */
        private const val CACHE = 4
        /** A freeze reuses a read at most this old instead of reading again. */
        private const val FREEZE_REUSE_MS = 2500L
        private const val OPEN_MIN_SIDE = 1024
        private const val PREVIEW_CHARS = 160
        private const val BENCH_FRAMES = 40
        private const val BENCH_WARMUP = 5
        /** Words logged per layout for a check to tap (a logcat line holds about 4 KB). */
        private const val LOG_WORDS = 24
    }
}
