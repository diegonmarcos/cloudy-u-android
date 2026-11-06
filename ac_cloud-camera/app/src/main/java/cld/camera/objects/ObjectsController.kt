package cld.camera.objects

import android.os.SystemClock
import android.view.View
import android.widget.Button
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
import com.diegonmarcos.superapp.vision.AnalysisGate
import com.diegonmarcos.superapp.vision.AnalyzerLifecycle
import com.diegonmarcos.superapp.vision.DetectionOverlayView
import com.diegonmarcos.superapp.vision.FrameResult
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * #1533 the Objects page: live, on-device object detection over the CameraX preview. Boxes, labels, confidence and
 * tracking ids come from [EngineObjectDetector] (the fleet's ML engine), drawn by libs:ml-vision's overlay; a slider sets
 * the minimum confidence, Pause freezes the analysis and the boxes, and the shutter saves the frame with its JSON.
 *
 * Battery: the analyzer is an ImageAnalysis with STRATEGY_KEEP_ONLY_LATEST, spaced by recognition.json's
 * detect.interval_ms, and it is installed ONLY while the page is shown and not paused ([AnalyzerLifecycle]): leaving
 * the page, pausing the activity or pressing Pause removes it.
 */
class ObjectsController(
    private val activity: MainActivity,
    private val overlay: DetectionOverlayView,
    private val panel: View,
    private val status: TextView,
    private val pauseButton: Button,
    private val threshold: SeekBar,
    private val thresholdValue: TextView,
) {
    private val scanner by lazy { ImageContentScanner(activity) }
    private val detector by lazy { EngineObjectDetector(scanner, RecognitionConfig.defaultDetectMode()) }
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val gate = AnalysisGate(RecognitionConfig.json.getJSONObject("detect").optLong("interval_ms", 150), SystemClock::elapsedRealtime)
    private var analysis: ImageAnalysis? = null

    private val lifecycle = AnalyzerLifecycle(object : AnalyzerLifecycle.Host {
        override fun attach() { analysis?.setAnalyzer(analysisExecutor) { analyze(it) } }
        override fun detach() { analysis?.clearAnalyzer() }
    }, gate)

    @Volatile private var last: FrameResult? = null
    @Volatile private var mirrored = false
    private var paused = false

    /** The minimum confidence the slider sets, 0..1. */
    var minConfidence: Float = activity.camConfig.commonPref.getInt(KEY_MIN_PERCENT, DEFAULT_PERCENT) / 100f
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
    }

    private fun percent() = "${Math.round(minConfidence * 100f)}%"

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
        paused = false
        pauseButton.setText(R.string.objects_pause)
        gate.paused = false
    }

    /** The activity left the screen: stop analysing (the camera unbinds with it); the next bind re-installs the analyzer. */
    fun onActivityPaused() = lifecycle.onHidden()

    private fun setPaused(p: Boolean) {
        paused = p
        pauseButton.setText(if (p) R.string.objects_resume else R.string.objects_pause)
        lifecycle.setPaused(p)
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!gate.admits()) return
            val frame = image.toBitmap()
            val r = try { detector.detect(frame) } finally { frame.recycle() }
            if (detector.error == null) last = r
            activity.runOnUiThread { render() }
        } finally {
            image.close()
        }
    }

    private fun render() {
        val err = detector.takeIf { last == null }?.error
        val r = last
        if (r == null) {
            overlay.clear()
            if (err != null) status.text = err
            return
        }
        val shown = r.atLeast(minConfidence)
        overlay.show(shown, r.frameWidth, r.frameHeight, 0, mirrored)
        status.text = if (shown.isEmpty()) activity.getString(R.string.objects_nothing)
        else activity.getString(R.string.objects_count, shown.size) + ": " + shown.joinToString(", ") { it.caption() }
    }

    /** The shutter: a full photo saved with the detections the screen shows. */
    fun snapshot() {
        val shot: ImageCapture = activity.camConfig.imageCapture ?: return
        val atShot = last
        val min = minConfidence
        val tmp = File.createTempFile("objects", ".jpg", activity.cacheDir)
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(tmp).build(), io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val saved = runCatching { ObjectsSnapshot.save(activity, tmp, atShot, min) }
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

    companion object {
        private const val KEY_MIN_PERCENT = "objects_min_confidence_percent"
        private const val DEFAULT_PERCENT = 40
    }
}
