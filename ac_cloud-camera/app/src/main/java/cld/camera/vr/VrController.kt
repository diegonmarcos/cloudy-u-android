package cld.camera.vr

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import cld.camera.R
import cld.camera.analyzer.ImageContentScanner
import cld.camera.objects.EngineObjectDetector
import cld.camera.ui.activities.MainActivity
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.vision.AnalysisGate
import com.diegonmarcos.superapp.vision.AnalyzerLifecycle
import com.diegonmarcos.superapp.vision.CompositeFrameView
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.vision.Detection
import com.diegonmarcos.superapp.vision.FaceDetector
import com.diegonmarcos.superapp.vision.FaceInfo
import com.diegonmarcos.superapp.vision.FilterCatalog
import com.diegonmarcos.superapp.vision.FilterDecl
import com.diegonmarcos.superapp.vision.FilterKind
import com.diegonmarcos.superapp.vision.MlKitFaceDetector
import com.diegonmarcos.superapp.vision.MlKitPersonSegmenter
import com.diegonmarcos.superapp.vision.PersonMask
import com.diegonmarcos.superapp.vision.PersonSegmenter
import com.diegonmarcos.superapp.vision.VrCompositor
import com.diegonmarcos.superapp.vision.VrScene
import com.diegonmarcos.superapp.vision.scaled
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * #1533 the VR page: AR-style filters on the live camera, all on device. The declared filters (the runtime dataset `vr-filters`,
 * served by Cloud Store through libs:fleet-data-client, #1547; never baked into the APK) are
 * switched on with the chips; each analysed frame goes through ML Kit Face Detection (landmarks, contours) and Selfie
 * Segmentation (both bundled, via libs:ml-l-vision-mlkit) and the fleet engine's object detector (for stickers on objects), is
 * composited by [VrCompositor] (hats, glasses, masks anchored to the head, a blurred or replaced background, a colour LUT,
 * stickers on objects) and shown over the preview. The shutter saves the composited photo (a full-resolution still, composited
 * again at its own size); Record saves the composited video.
 *
 * Battery: the analyzer is installed only while the page is shown ([AnalyzerLifecycle]), the models are released the moment it
 * is hidden, and only the models the switched-on filters need are run. The camera grant is the Configs > Perms row's; this
 * class asks for nothing.
 */
class VrController(
    private val activity: MainActivity,
    private val view: CompositeFrameView,
    private val panel: View,
    private val status: TextView,
    private val filtersRow: LinearLayout,
    private val recordButton: Button,
) {
    // #1547 the VR filter declaration is read at RUNTIME from Cloud Store's provider (libs:fleet-data-client), never baked into the APK.
    // `null` until the Store has served `vr-filters` (first run, provider absent, nothing synced yet); the client keeps the last-known-good copy
    // and never throws, so the page shows a loading/failed status instead of crashing and fills itself when the dataset arrives (reloadCatalog).
    private fun readDataset(): String? = runCatching { FleetData.get(activity, DATASET, own = true) { it } }.getOrNull()
    private fun parseCatalog(raw: String?): Result<List<FilterDecl>> =
        if (raw == null) Result.failure(IllegalStateException("vr-filters not available yet")) else runCatching { FilterCatalog.parse(raw) }

    private val rawJson: String? = readDataset()
    private val settings: JSONObject = runCatching {
        rawJson?.let { JSONObject(it).optJSONObject("analysis") }
    }.getOrNull() ?: JSONObject()
    private var catalogResult: Result<List<FilterDecl>> = parseCatalog(rawJson)
    private var catalog: List<FilterDecl> = catalogResult.getOrDefault(emptyList())

    private val mlSide = settings.optInt("ml_side", 480)
    private val objectEvery = settings.optInt("object_every_n_frames", 3).coerceAtLeast(1)
    private val frameTarget = settings.optJSONArray("frame_target")?.let { Size(it.optInt(0, 1280), it.optInt(1, 960)) } ?: Size(1280, 960)

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val gate = AnalysisGate(settings.optLong("interval_ms", 40), SystemClock::elapsedRealtime)
    private var analysis: ImageAnalysis? = null
    private val lifecycle = AnalyzerLifecycle(object : AnalyzerLifecycle.Host {
        override fun attach() { analysis?.setAnalyzer(analysisExecutor) { analyze(it) } }
        override fun detach() { analysis?.clearAnalyzer() }
    }, gate)

    private val liveCompositor by lazy { VrCompositor(activity.assets) }
    private val scanner by lazy { ImageContentScanner(activity) }
    private val objectDetector by lazy { EngineObjectDetector(scanner, RecognitionConfig.defaultDetectMode()) }
    private val recorder by lazy { VrRecorder(activity) }
    private var faceDetector: FaceDetector? = null
    private var segmenter: PersonSegmenter? = null

    @Volatile private var active: List<FilterDecl> = emptyList()
    @Volatile private var mirrored = false
    @Volatile private var latest: Bitmap? = null
    @Volatile private var lastObjects: List<Detection> = emptyList()
    private var frameCount = 0
    private var lastStatus = ""
    private val chips = LinkedHashMap<String, Button>()

    init {
        buildChips()
        recordButton.setOnClickListener { toggleRecord() }
    }

    /** (Re)build the filter chips from the current [catalog]; safe to call again after the dataset is served. */
    private fun buildChips() {
        chips.clear()
        filtersRow.removeAllViews()
        catalog.forEach { f ->
            val chip = Button(activity).apply {
                text = f.label; isAllCaps = false; textSize = 12f; alpha = INACTIVE
                setOnClickListener { toggle(f) }
            }
            chips[f.id] = chip
            filtersRow.addView(chip)
        }
    }

    /** First run / provider still syncing: read the dataset again so the page fills once Cloud Store has served `vr-filters`. */
    private fun reloadCatalog() {
        val raw = readDataset() ?: return
        catalogResult = parseCatalog(raw)
        catalog = catalogResult.getOrDefault(emptyList())
        buildChips()
    }

    private fun toggle(f: FilterDecl) {
        // One filter per kind: picking a second hat replaces the first; picking the active one switches it off.
        active = if (f in active) active - f else active.filter { it.kind != f.kind } + f
        chips.forEach { (id, b) -> b.alpha = if (active.any { it.id == id }) 1f else INACTIVE }
        say(if (active.isEmpty()) activity.getString(R.string.vr_pick) else active.joinToString(", ") { it.label })
    }

    /** A fresh analysis use case for the camera about to be bound. */
    fun analysisUseCase(aspectRatio: Int): ImageAnalysis {
        stopRecording()
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(frameTarget, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val ia = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .build()
        analysis = ia
        mirrored = activity.camConfig.lensFacing == CameraSelector.LENS_FACING_FRONT
        lifecycle.onHidden()
        lifecycle.onShown()
        return ia
    }

    /** The VR page was picked. */
    fun enter() {
        panel.visibility = View.VISIBLE
        view.visibility = View.VISIBLE
        if (catalog.isEmpty()) reloadCatalog()
        val err = catalogResult.exceptionOrNull()
        say(if (err != null) activity.getString(R.string.vr_catalog_failed, err.message ?: err.toString()) else activity.getString(R.string.vr_pick))
    }

    /** The page was left: analysis, recording and the models stop at once. */
    fun leave() {
        lifecycle.onHidden()
        stopRecording()
        releaseModels()
        view.show(null, false)
        view.visibility = View.GONE
        panel.visibility = View.GONE
        latest = null
    }

    /** The activity left the screen. */
    fun onActivityPaused() {
        lifecycle.onHidden()
        stopRecording()
        releaseModels()
    }

    private fun models(): Pair<FaceDetector, PersonSegmenter> {
        val f = faceDetector ?: MlKitFaceDetector().also { faceDetector = it }
        val s = segmenter ?: MlKitPersonSegmenter().also { segmenter = it }
        return f to s
    }

    @Synchronized private fun releaseModels() {
        runCatching { faceDetector?.close() }
        runCatching { segmenter?.close() }
        faceDetector = null
        segmenter = null
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!gate.admits()) return
            val frame = image.toBitmap()
            val filters = active
            val sc = try { scene(frame, filters, cached = true) } catch (e: Exception) { say(e.message ?: e.toString()); VrScene(emptyList(), emptyList(), null, filters) }
            val out = liveCompositor.composite(frame, sc)
            frame.recycle()
            latest = out
            view.show(out, mirrored)
            if (recorder.recording) recorder.add(out)
            if (filters.any { it.kind == FilterKind.HAT || it.kind == FilterKind.GLASSES || it.kind == FilterKind.MASK }) {
                val msg = if (sc.faces.isEmpty()) activity.getString(R.string.vr_no_face) else filters.joinToString(", ") { it.label }
                if (msg != lastStatus) say(msg)
            }
        } finally {
            image.close()
        }
    }

    /** What [filters] need of [frame]: faces, the person mask, objects; only the models a switched-on filter uses are run. */
    @Synchronized private fun scene(frame: Bitmap, filters: List<FilterDecl>, cached: Boolean): VrScene {
        val needFaces = filters.any { it.kind == FilterKind.HAT || it.kind == FilterKind.GLASSES || it.kind == FilterKind.MASK }
        val needMask = filters.any { it.kind == FilterKind.BACKGROUND }
        val needObjects = filters.any { it.kind == FilterKind.STICKER }
        var faces: List<FaceInfo> = emptyList()
        var mask: PersonMask? = null
        if (needFaces || needMask) {
            val (fd, seg) = models()
            val k = minOf(1f, mlSide / maxOf(frame.width, frame.height).toFloat())
            val small = if (k < 1f) Bitmap.createScaledBitmap(frame, (frame.width * k).toInt().coerceAtLeast(1), (frame.height * k).toInt().coerceAtLeast(1), true) else frame
            try {
                if (needFaces) faces = fd.detect(small).map { if (k < 1f) it.scaled(1f / k) else it }
                if (needMask) mask = seg.segment(small)
            } finally {
                if (small !== frame) small.recycle()
            }
        }
        val objects = when {
            !needObjects -> emptyList()
            !cached || frameCount++ % objectEvery == 0 -> objectDetector.detect(frame).detections.also { lastObjects = it }
            else -> lastObjects
        }
        return VrScene(faces, objects, mask, filters)
    }

    private fun say(msg: String) {
        lastStatus = msg
        activity.runOnUiThread { status.text = msg }
    }

    /** The shutter: a full-resolution still, composited at its own size and filed in DCIM/Camera. */
    fun snapshot() {
        val shot: ImageCapture = activity.camConfig.imageCapture ?: return
        val filters = active
        val tmp = File.createTempFile("vr", ".jpg", activity.cacheDir)
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(tmp).build(), io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val saved = runCatching { savePhoto(tmp, filters) }
                tmp.delete()
                toast(saved.fold({ activity.getString(R.string.vr_saved_photo) }, { activity.getString(R.string.vr_save_failed, it.message ?: it.toString()) }))
            }

            override fun onError(e: ImageCaptureException) {
                tmp.delete()
                toast(activity.getString(R.string.vr_save_failed, e.message ?: e.toString()))
            }
        })
    }

    private fun savePhoto(jpeg: File, filters: List<FilterDecl>) {
        val photo = ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpeg)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (info.size.width.toLong() * info.size.height > MAX_STILL_PIXELS) decoder.setTargetSampleSize(2)
        }
        val out = VrCompositor(activity.assets).composite(photo, scene(photo, filters, cached = false))
        photo.recycle()
        try { VrSnapshot.save(activity, out) } finally { out.recycle() }
    }

    private fun toggleRecord() {
        if (recorder.recording) {
            stopRecording(announce = true)
            return
        }
        val first = latest ?: return
        if (recorder.start(first, mirrored)) recordButton.setText(R.string.vr_stop)
        else toast(activity.getString(R.string.vr_save_failed, "the recorder could not start"))
    }

    private fun stopRecording(announce: Boolean = false) {
        if (!recorder.recording) return
        val uri = recorder.stop()
        activity.runOnUiThread { recordButton.setText(R.string.vr_record) }
        if (announce) toast(if (uri != null) activity.getString(R.string.vr_saved_video) else activity.getString(R.string.vr_save_failed, "nothing was recorded"))
    }

    private fun toast(msg: String) = activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }

    companion object {
        private const val DATASET = "vr-filters"
        private const val INACTIVE = 0.55f
        private const val MAX_STILL_PIXELS = 9_000_000L
    }
}
