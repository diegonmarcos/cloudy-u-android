package com.diegonmarcos.cloudkeyboard.grabber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.mlocr.MlKitOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * #1754 the text-selection menu entry. Android lists every activity that filters ACTION_PROCESS_TEXT in the selection toolbar of
 * every app; picking this one opens the Grabber sheet over that app, with the selected text. When the field is editable
 * (EXTRA_PROCESS_TEXT_READONLY false) an action that transforms the text answers RESULT_OK + EXTRA_PROCESS_TEXT, which the other
 * app puts in place of the selection (ProcessTextReply.decide); anything else leaves the field untouched.
 */
class GrabberActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT))
        GrabberDiag.openedAt = android.os.SystemClock.uptimeMillis() // #1825 the launch check of Grabber on selection
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        // #1793 debug builds only: the emulator walk's fixed position for the Place card's distances (labelled MOCK on the card)
        if (com.diegonmarcos.cloudkeyboard.BuildConfig.DEBUG && intent.hasExtra(EXTRA_MOCK_LAT) && intent.hasExtra(EXTRA_MOCK_LON)) {
            GrabberLocation.mock = intent.getStringExtra(EXTRA_MOCK_LAT)?.toDoubleOrNull()?.let { la -> intent.getStringExtra(EXTRA_MOCK_LON)?.toDoubleOrNull()?.let { lo -> la to lo } }
        }
        // #1793 who opened the panel: the selection menu (default), the toolbar key, or Grabber on selection (accessibility / keyboard)
        val source = intent.getStringExtra(EXTRA_SOURCE)?.takeIf { it in SOURCES } ?: "menu"
        val model = GrabberModel(applicationContext, scope, source)
        model.readOnly = readOnly
        model.setText(selected, selectAll = selected.isNotEmpty())
        model.grab()
        setContent {
            GrabberTheme {
                GrabberSheet(model, onReply = { deliver(it) }, onClose = { finish() })
            }
        }
    }

    /** Hand a result back to the field the text was selected in; only a reply that replaces the selection ends the activity. */
    private fun deliver(reply: ProcessTextReply) {
        if (!reply.replacesSelection) return
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, reply.text))
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE = "grabber.source"
        const val EXTRA_MOCK_LAT = "grabber.mock_lat"
        const val EXTRA_MOCK_LON = "grabber.mock_lon"
        val SOURCES = setOf("menu", "toolbar", "accessibility", "keyboard")

        /** #1793 the GRABBER toolbar key (GrabberLink.opener): the panel over the app being typed in, on the field's selection. */
        fun openFromKeyboard(ctx: Context, text: String) {
            ctx.startActivity(Intent(ctx, GrabberActivity::class.java)
                .setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .putExtra(EXTRA_SOURCE, "toolbar")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}

/** The bitmap hand-off from the capture service to the activity that asked for it (same process). */
object GrabberShots {
    @Volatile var listener: ((Bitmap?, String?) -> Unit)? = null

    fun post(bitmap: Bitmap?, error: String?) {
        Handler(Looper.getMainLooper()).post {
            val l = listener
            if (l != null) l(bitmap, error) else bitmap?.recycle()
        }
    }
}

/**
 * #1754 Screen Selection: asks for the screen-capture consent, has [GrabberCaptureService] grab one frame, lets the user drag
 * around the text, reads it on the device (ML Kit, libs:ml-l-image-ocr) and opens the same three-box panel with the text. The
 * activity stays on top, transparent, while the frame is taken (an app with a window on screen may start the picker; a
 * background service may not), so what is captured is whatever is under it.
 */
class GrabberScreenActivity : ComponentActivity() {
    private enum class Phase { ASKING, CAPTURING, PICKING, READING, RESULT, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var phase by mutableStateOf(Phase.ASKING)
    private var shot by mutableStateOf<Bitmap?>(null)
    private var message by mutableStateOf("")
    private var model by mutableStateOf<GrabberModel?>(null)

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode != RESULT_OK || data == null) fail("Screen capture was not allowed.")
        else {
            phase = Phase.CAPTURING
            val i = Intent(this, GrabberCaptureService::class.java)
                .putExtra(GrabberCaptureService.EX_CODE, r.resultCode).putExtra(GrabberCaptureService.EX_DATA, data)
                .putExtra(GrabberCaptureService.EX_DELAY, intent.getLongExtra(EXTRA_DELAY, 600L))
            try { startForegroundService(i) } catch (e: Exception) { fail("The capture service could not start: ${e.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT))
        val path = intent.getStringExtra(EXTRA_PATH)
        if (savedInstanceState == null) {
            if (path != null) {
                val b = android.graphics.BitmapFactory.decodeFile(path)
                if (b != null) { shot = b; phase = Phase.PICKING } else fail("The last capture is gone. Capture again.")
            } else {
                GrabberShots.listener = { bmp, err -> onShot(bmp, err) }
                consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
        }
        setContent { GrabberTheme { Flow() } }
    }

    private fun fail(why: String) { message = why; phase = Phase.FAILED }

    private fun onShot(bmp: Bitmap?, err: String?) {
        if (bmp == null) { fail(err ?: "No frame was captured."); return }
        shot = bmp
        phase = Phase.PICKING
        scope.launch(Dispatchers.IO) {
            val f = File(File(cacheDir, "grabber").also { it.mkdirs() }, "shot.jpg")
            runCatching { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                .onSuccess { GrabberBus.lastShot = ShotInfo(f.absolutePath, bmp.width, bmp.height, System.currentTimeMillis()) }
        }
    }

    /** Read [region] (view pixels in [view]) or the whole screen; the result opens the panel, an empty read says so. */
    private fun read(region: android.graphics.Rect?, viewW: Int, viewH: Int) {
        val b = shot ?: return
        phase = Phase.READING
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                runCatching {
                    val crop = if (region == null) b else {
                        val sx = b.width / viewW.coerceAtLeast(1).toFloat(); val sy = b.height / viewH.coerceAtLeast(1).toFloat()
                        val l = (region.left * sx).toInt().coerceIn(0, b.width - 1); val t = (region.top * sy).toInt().coerceIn(0, b.height - 1)
                        val r = (region.right * sx).toInt().coerceIn(l + 1, b.width); val bt = (region.bottom * sy).toInt().coerceIn(t + 1, b.height)
                        Bitmap.createBitmap(b, l, t, r - l, bt - t)
                    }
                    MlKitOcr.read(crop)
                }
            }
            val r = out.getOrNull()
            val text = r?.text?.trim().orEmpty()
            when {
                out.isFailure -> fail("The text reader failed: ${out.exceptionOrNull()?.message}")
                text.isEmpty() -> { message = r?.error ?: "No text found there. Try a bigger area or a clearer part of the screen."; phase = Phase.FAILED }
                else -> {
                    val m = GrabberModel(applicationContext, scope, "screen")
                    m.readOnly = true
                    m.setText(text)
                    m.grab()
                    model = m
                    phase = Phase.RESULT
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Flow() {
        BackHandler(enabled = phase != Phase.RESULT) { finish() }
        Box(Modifier.fillMaxSize()) {
            val s = shot
            if (s != null && (phase == Phase.PICKING || phase == Phase.READING || phase == Phase.RESULT)) {
                RegionPicker(s, reading = phase == Phase.READING,
                    onRegion = { rect, view -> read(rect, view.width, view.height) },
                    onWhole = { read(null, s.width, s.height) },
                    onCancel = { finish() })
            }
            val m = model
            if (phase == Phase.RESULT && m != null) GrabberSheet(m, onReply = {}, onClose = { phase = Phase.PICKING }, heading = "GRABBER · SCREEN SELECTION")
            if (phase == Phase.FAILED) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    val shape = RoundedCornerShape(12.dp)
                    Column(Modifier.width(300.dp).background(GC.sheet, shape).border(1.dp, GC.hairline, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("SCREEN SELECTION", color = GC.bad, fontFamily = Mono, fontSize = 10.sp)
                        Text(message, color = GC.text, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (shot != null) KeyChip("Try again", accent = true) { phase = Phase.PICKING }
                            KeyChip("Close") { finish() }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        GrabberShots.listener = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DELAY = "grabber.delay_ms"
        const val EXTRA_PATH = "grabber.shot_path"
    }
}

/**
 * Takes ONE frame of the screen with MediaProjection and hands it to [GrabberShots]. A foreground service of type mediaProjection
 * (Android 14 refuses the projection without one); it lives for about a second and removes its notice when done.
 */
class GrabberCaptureService : Service() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Bitmap? = null
    private var delivered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra(EX_CODE, 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>(EX_DATA)
        val delay = intent?.getLongExtra(EX_DELAY, 600L) ?: 600L
        if (data == null) { finish(null, "No consent came with the capture."); return START_NOT_STICKY }
        val t = HandlerThread("grabber-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        try { begin(code, data, delay, h) } catch (e: Exception) { finish(null, "Screen capture failed: ${e.message}") }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Grabber screen capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setContentTitle("Grabber").setContentText("Capturing the screen").setSmallIcon(android.R.drawable.ic_menu_crop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTICE, n)
    }

    private fun begin(code: Int, data: Intent, delay: Long, h: Handler) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
        projection = p
        val (w, ht, dpi) = metrics()
        // Android 14: a callback must be registered before the virtual display exists
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { h.post { release() } } }, h)
        val r = ImageReader.newInstance(w, ht, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage()
            if (img != null) try { val b = toBitmap(img); latest?.recycle(); latest = b } finally { img.close() }
        }, h)
        display = p.createVirtualDisplay("cloud-grabber", w, ht, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h)
        h.postDelayed({ deliver(h) }, delay)
        h.postDelayed({ if (!delivered) finish(null, "No frame arrived from the screen.") }, delay + 7000)
    }

    /** The newest frame so far; a still screen sends one frame only, so the last one kept is the answer. */
    private fun deliver(h: Handler) {
        if (delivered) return
        val b = latest
        if (b == null) { h.postDelayed({ deliver(h) }, 250); return }
        latest = null
        finish(b, null)
    }

    private fun finish(bitmap: Bitmap?, error: String?) {
        if (delivered) return
        delivered = true
        GrabberShots.post(bitmap, error)
        release()
    }

    private fun release() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null
        runCatching { latest?.recycle() }
        latest = null
        thread?.quitSafely(); thread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { thread?.quitSafely() }
        super.onDestroy()
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixel = plane.pixelStride
        val rowPad = plane.rowStride - pixel * img.width
        val wide = Bitmap.createBitmap(img.width + rowPad / pixel, img.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        if (rowPad == 0) return wide
        val cropped = Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
        wide.recycle()
        return cropped
    }

    private fun metrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.configuration.densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    companion object {
        const val EX_CODE = "grabber.code"
        const val EX_DATA = "grabber.data"
        const val EX_DELAY = "grabber.delay"
        private const val CHANNEL = "grabber_capture"
        private const val NOTICE = 17541
    }
}

/** The Quick Settings tile: one tap collapses the shade and starts Screen Selection over whatever app is open. */
class GrabberTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Grabber"; updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, GrabberScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(GrabberScreenActivity.EXTRA_DELAY, 700L)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
