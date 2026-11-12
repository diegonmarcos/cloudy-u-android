package cld.camera.identify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import cld.camera.R
import cld.camera.analyzer.ImageContentScanner
import cld.camera.analyzer.SoundIdentifier
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.sound.SoundConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * #798 Identify: a live viewfinder that names what it sees, fully on device. Every analysis frame
 * (no closer together than recognition.json's detect.interval_ms) goes to the shared image engine's
 * detect in stream mode — objects boxed and named with the full-label classifier and kept by tracking
 * id, or the frame's labels, or its text lines — and the boxes are drawn over the preview; a tap on a
 * box shows its labels. Sound mode listens instead (the shared sound engine, YAMNet) and names what it
 * hears, over and over. The shutter saves a full-resolution photo carrying all of it (IdentifySnapshot),
 * identified once more on the user's route. Works offline; the engines are Store libs (Cloud ▸ Libs).
 */
class IdentifyActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var status: TextView
    private val buttons = LinkedHashMap<String, Button>()

    private val scanner by lazy { ImageContentScanner(this) }
    private val sound by lazy { SoundIdentifier(this) }
    private val analysis: ExecutorService = Executors.newSingleThreadExecutor()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val interval = RecognitionConfig.json.getJSONObject("detect").optLong("interval_ms", 150)

    @Volatile private var mode = RecognitionConfig.defaultDetectMode()
    @Volatile private var lastFrameAt = 0L
    @Volatile private var live: Recognition? = null
    @Volatile private var heard: Recognition? = null
    @Volatile private var heardAt = 0L
    @Volatile private var listening = false
    private var capture: ImageCapture? = null

    /** #1515 the camera / microphone grants live in Configs > Perms: a missing one opens the page on its row once, and
     *  [onResume] starts what was waiting when the grant came back (or says it is still missing). */
    private var askedCamera = false
    private var cameraStarted = false
    private var askedMic = false

    private fun openPerms(feature: String, id: String) {
        runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(this, feature, listOf(id)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.identify_title)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = DetectionOverlay(this) { showBox(it) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt())
            val p = (12 * resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, p / 2)
            setText(R.string.identify_starting)
        }
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        (RecognitionConfig.detectModes() + SOUND).forEach { m ->
            val b = Button(this).apply { text = label(m); isAllCaps = false; setOnClickListener { select(m) } }
            buttons[m] = b
            modes.addView(b)
        }
        val shutter = Button(this).apply { setText(R.string.identify_snapshot); setOnClickListener { snapshot() } }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(modes)
            addView(shutter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = FrameLayout(this)
        val full = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(preview, full())
        root.addView(overlay, full())
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        select(mode)

        if (granted(Manifest.permission.CAMERA)) { cameraStarted = true; startCamera() }
        else { askedCamera = true; status.setText(R.string.identify_camera_denied); openPerms("Identify: camera", "camera") }
        // The handshake costs a package lookup and a bind; say what is missing before the first frame does.
        io.execute { val why = scanner.detectStatus(); if (why != null) runOnUiThread { status.text = why } }
    }

    override fun onResume() {
        super.onResume()
        if (askedCamera && !cameraStarted && granted(Manifest.permission.CAMERA)) { cameraStarted = true; startCamera() }
        if (askedMic && !granted(Manifest.permission.RECORD_AUDIO)) { askedMic = false; status.setText(R.string.identify_mic_denied); return }
        if (mode == SOUND) startListening()
    }

    override fun onPause() {
        listening = false
        super.onPause()
    }

    override fun onDestroy() {
        listening = false
        analysis.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun label(m: String): String =
        if (m == SOUND) getString(R.string.identify_mode_sound) else m.replaceFirstChar { it.uppercase() }

    private fun select(m: String) {
        mode = m
        live = null
        overlay.clear()
        buttons.forEach { (k, b) -> b.alpha = if (k == m) 1f else 0.55f }
        if (m == SOUND) startListening() else { listening = false; status.setText(R.string.identify_looking) }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // One 4:3 strategy for all three, so the analysed frame is the field of view the preview shows.
            val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val pv = Preview.Builder().setResolutionSelector(ratio).build().also { it.surfaceProvider = preview.surfaceProvider }
            val frames = ImageAnalysis.Builder()
                .setResolutionSelector(ratio)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .build()
            frames.setAnalyzer(analysis) { analyze(it) }
            val shot = ImageCapture.Builder().setResolutionSelector(ratio).build()
            capture = shot
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, frames, shot)
            }.onFailure { status.text = getString(R.string.identify_camera_failed, it.message ?: it.toString()) }
        }, ContextCompat.getMainExecutor(this))
    }

    /** One frame: upright (the analysis output is rotated), to the engine, its boxes onto the overlay. */
    private fun analyze(image: ImageProxy) {
        try {
            val m = mode
            val now = SystemClock.elapsedRealtime()
            if (m == SOUND || now - lastFrameAt < interval) return
            lastFrameAt = now
            val frame = image.toBitmap()
            val r = try { scanner.detectLive(frame, m) } finally { frame.recycle() }
            if (m != mode) return
            live = r
            runOnUiThread { if (m == mode) show(r) }
        } finally {
            image.close()
        }
    }

    private fun show(r: Recognition) {
        if (!r.ok) {
            overlay.clear()
            status.text = r.error ?: getString(R.string.identify_failed)
            return
        }
        overlay.show(r)
        status.text = when {
            r.mode == TEXT -> r.text.ifBlank { getString(R.string.identify_nothing) }
            r.labels.isEmpty() -> getString(R.string.identify_nothing)
            else -> ImageContentScanner.labels(r)
        }
    }

    /** Sound mode: listen for the declared capture length, name what was heard, again, until the mode changes. */
    private fun startListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            if (!askedMic) { askedMic = true; openPerms("Identify: sound", "microphone") } else status.setText(R.string.identify_mic_denied)
            return
        }
        askedMic = false
        if (listening) return
        listening = true
        status.setText(R.string.identify_listening)
        thread(name = "identify-sound", isDaemon = true) {
            while (listening && mode == SOUND) {
                val r = runCatching { sound.listen().first }.getOrElse { Recognition.failed(SoundConfig.ML, it.message ?: it.toString()) }
                if (r.ok) { heard = r; heardAt = SystemClock.elapsedRealtime() }
                runOnUiThread {
                    if (mode == SOUND) status.text = if (!r.ok) r.error.orEmpty() else r.labels.joinToString(", ") { "${it.label} ${Math.round(it.p * 100)}%" }
                        .ifBlank { getString(R.string.identify_nothing) } + "\n" + RecognitionRoutes.answeredBy(r, SoundConfig.routes())
                }
                // A missing engine or a refused microphone does not get better by asking again at once.
                if (!r.ok) break
            }
            listening = false
        }
    }

    private fun showBox(b: Recognition.Box) {
        val lines = b.alts.ifEmpty { listOf(Recognition.Label(b.label, b.p)) }.map { "${it.label}  ${Math.round(it.p * 100)}%" }.toMutableList()
        b.id?.let { lines += getString(R.string.identify_tracking, it) }
        live?.model?.takeIf { it.isNotBlank() }?.let { lines += getString(R.string.identify_model, it) }
        AlertDialog.Builder(this).setTitle(b.label).setMessage(lines.joinToString("\n")).setPositiveButton(android.R.string.ok, null).show()
    }

    /** The shutter: a full-resolution photo, identified on the user's route, saved with everything identified. */
    private fun snapshot() {
        val shot = capture ?: return
        status.setText(R.string.identify_saving)
        val tmp = File.createTempFile("identify", ".jpg", cacheDir)
        val atShot = live
        val ambient = heard?.takeIf { SystemClock.elapsedRealtime() - heardAt <= AMBIENT_FRESH_MS }
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(tmp).build(), io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val saved = runCatching { IdentifySnapshot.save(this@IdentifyActivity, tmp, atShot, ambient, scanner) }
                tmp.delete()
                runOnUiThread {
                    Toast.makeText(this@IdentifyActivity,
                        saved.fold({ getString(R.string.identify_saved) }, { getString(R.string.identify_save_failed, it.message ?: it.toString()) }),
                        Toast.LENGTH_LONG).show()
                }
            }

            override fun onError(e: ImageCaptureException) {
                tmp.delete()
                runOnUiThread { Toast.makeText(this@IdentifyActivity, getString(R.string.identify_save_failed, e.message ?: e.toString()), Toast.LENGTH_LONG).show() }
            }
        })
    }

    companion object {
        const val SOUND = "sound"
        private const val TEXT = "text"

        /** Ambient sound older than this is not tagged onto a photo: it described another moment. */
        private const val AMBIENT_FRESH_MS = 30_000L

        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, IdentifyActivity::class.java))
    }
}
