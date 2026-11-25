package com.diegonmarcos.cloudlib.scanner.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.cloudlib.scanner.EdgeDetector
import com.diegonmarcos.cloudlib.scanner.P
import com.diegonmarcos.cloudlib.scanner.Quad
import com.diegonmarcos.cloudlib.scanner.R
import com.diegonmarcos.cloudlib.scanner.Raster
import com.diegonmarcos.cloudlib.scanner.ScannerExport
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

/**
 * #1908 THE capture screen: the live viewfinder with the detected document outlined on top of it (edge detection on the analysis
 * frames, the outline turns green and the shot fires by itself once the page has held still: auto-capture), flash, grid, batch
 * mode (stay here and keep shooting) and the review strip of what is already scanned.
 */
@Composable
fun CaptureScreen(c: ScannerController) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val k = LocalKitPalette.current
    var granted by remember { mutableStateOf(c.host.cameraGranted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = c.host.cameraGranted() }

    if (!granted) {
        Column(Modifier.fillMaxSize().background(Color.Black).testTag(ScannerTags.CAPTURE).padding(KitDensity.large), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.scanner_camera_needed), color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = KitDensity.large), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                KitButton({ c.host.requestCamera() }) { Text(stringResource(R.string.scanner_allow_camera)) }
                KitOutlinedButton({ c.pickImages() }) { Text(stringResource(R.string.scanner_import)) }
                KitOutlinedButton({ c.route = Route.HOME }) { Text(stringResource(R.string.scanner_back)) }
            }
        }
        return
    }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val analysis = remember { ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() }
    val analysisThread = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var live by remember { mutableStateOf<Quad?>(null) }          // fractions of the upright frame
    var stable by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(0.75f) }          // upright frame width / height
    var shooting by remember { mutableStateOf(false) }
    var shots by remember { mutableStateOf(0) }
    val auto by rememberUpdatedState(c.autoCapture)
    val batchNow by rememberUpdatedState(c.batch)

    fun shoot() {
        if (shooting) return
        shooting = true
        val file = File(ScannerExport.dir(ctx), "capture_${System.nanoTime()}.jpg")
        val retake = c.target is ScannerController.Target.Retake || c.target is ScannerController.Target.After
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                shooting = false; shots++
                c.addCaptured(file, goEditor = !batchNow || retake)
            }
            override fun onError(exception: ImageCaptureException) { shooting = false; c.say(exception.message ?: "camera") }
        })
    }
    val shootNow by rememberUpdatedState(::shoot)

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var prev: Quad? = null; var steady = 0; var lastShot = 0L; var last = 0L
        analysis.setAnalyzer(analysisThread) { proxy ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - last < 150) return@setAnalyzer
                last = now
                val r = lumaRaster(proxy)
                val q = EdgeDetector.findQuadOrNull(r)?.scaled(1.0 / r.w, 1.0 / r.h)
                val still = q != null && prev != null && quadShift(prev!!, q) < 0.025
                steady = if (still) steady + 1 else 0
                prev = q
                val ready = steady >= 7
                main.post { live = q; stable = ready; aspect = r.w.toFloat() / r.h }
                if (ready && auto && now - lastShot > 2500) { lastShot = now; steady = 0; main.post { shootNow() } }
            } finally { proxy.close() }
        }
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { analysis.clearAnalyzer(); runCatching { future.get().unbindAll() }; analysisThread.shutdown() }
    }
    LaunchedEffect(c.flash, camera) { camera?.cameraControl?.enableTorch(c.flash) }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag(ScannerTags.CAPTURE)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // the live edge overlay and the grid, drawn over the frame's own rectangle inside the view
        Canvas(Modifier.fillMaxSize().testTag("scanner:overlay")) {
            val fa = aspect
            val dw: Float; val dh: Float
            if (size.width / size.height > fa) { dh = size.height; dw = dh * fa } else { dw = size.width; dh = dw / fa }
            val ox = (size.width - dw) / 2; val oy = (size.height - dh) / 2
            if (c.grid) {
                for (i in 1..2) {
                    drawLine(Color(0x55FFFFFF), Offset(ox + dw * i / 3, oy), Offset(ox + dw * i / 3, oy + dh), strokeWidth = 1.dp.toPx())
                    drawLine(Color(0x55FFFFFF), Offset(ox, oy + dh * i / 3), Offset(ox + dw, oy + dh * i / 3), strokeWidth = 1.dp.toPx())
                }
            }
            val q = live
            if (q != null) {
                val col = if (stable) Color(0xFF4CD964) else k.accent
                val pts = q.points.map { Offset(ox + (it.x * dw).toFloat(), oy + (it.y * dh).toFloat()) }
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1..3) lineTo(pts[i].x, pts[i].y); close() }
                drawPath(path, col.copy(alpha = 0.18f))
                drawPath(path, col, style = Stroke(width = 3.dp.toPx()))
                for (o in pts) drawCircle(col, radius = 6.dp.toPx(), center = o)
            }
        }
        // top controls
        Row(Modifier.fillMaxWidth().padding(KitDensity.small), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RoundTool(Icons.Filled.Close, stringResource(R.string.scanner_back), false, "scanner:back") { c.route = Route.HOME }
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                RoundTool(if (c.flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, stringResource(R.string.scanner_flash), c.flash, "scanner:flash") { c.flash = !c.flash }
                RoundTool(if (c.grid) Icons.Filled.GridOn else Icons.Filled.GridOff, stringResource(R.string.scanner_grid), c.grid, "scanner:grid") { c.grid = !c.grid }
                RoundTool(Icons.Filled.AutoAwesome, stringResource(R.string.scanner_auto_capture), c.autoCapture, "scanner:auto") { c.autoCapture = !c.autoCapture }
                RoundTool(Icons.Filled.Collections, stringResource(R.string.scanner_batch), c.batch, "scanner:batch") { c.batch = !c.batch }
            }
        }
        // the hint
        Text(
            when { live == null -> stringResource(R.string.scanner_looking); stable && c.autoCapture -> stringResource(R.string.scanner_hold_steady); else -> stringResource(R.string.scanner_edges_found) },
            color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp).clip(RoundedCornerShape(KitDensity.corner)).background(Color(0x99000000)).padding(horizontal = KitDensity.medium, vertical = KitDensity.small / 2),
        )
        // bottom: the review strip, then import / shutter / done
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(bottom = KitDensity.small)) {
            if (c.pages.isNotEmpty()) {
                LazyRow(Modifier.testTag(ScannerTags.STRIP).fillMaxWidth().padding(vertical = KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = KitDensity.medium)) {
                    itemsIndexed(c.pages) { i, p -> PageThumb(c, p, i, i == c.selected, { c.select(i) }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.large), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundTool(Icons.Filled.PhotoLibrary, stringResource(R.string.scanner_import), false, "scanner:import") { c.pickImages() }
                Box(
                    Modifier.testTag("scanner:shutter").size(72.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape)
                        .background(if (shooting) k.accent else Color.White).clickable { shoot() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.scanner_shutter), tint = Color.Black) }
                Box(contentAlignment = Alignment.Center) {
                    RoundTool(Icons.Filled.Check, stringResource(R.string.scanner_done), c.pages.isNotEmpty(), "scanner:finish") { c.route = Route.HOME }
                    if (shots > 0) Text("$shots", color = k.tileInk, fontSize = KitDensity.caption, modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(k.accent).padding(horizontal = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val k = LocalKitPalette.current
    Box(
        Modifier.testTag(tag).size(44.dp).clip(CircleShape).background(if (on) k.accent else Color(0x99000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (on) k.tileInk else Color.White, modifier = Modifier.size(22.dp)) }
}

/** The largest corner movement between two frames, as a fraction of the frame. */
internal fun quadShift(a: Quad, b: Quad): Double = a.points.zip(b.points).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) }

/** The analysis frame's luma as a small upright grayscale raster (about 240 px on its longer side), ready for edge detection. */
internal fun lumaRaster(proxy: ImageProxy): Raster {
    val plane = proxy.planes[0]
    val buf = plane.buffer
    val rs = plane.rowStride; val ps = plane.pixelStride
    val w = proxy.width; val h = proxy.height
    val step = max(1, max(w, h) / 240)
    val sw = max(1, w / step); val sh = max(1, h / step)
    val px = IntArray(sw * sh)
    for (y in 0 until sh) for (x in 0 until sw) {
        val v = buf.get((y * step) * rs + (x * step) * ps).toInt() and 255
        px[y * sw + x] = Raster.rgb(v, v, v)
    }
    return Raster(sw, sh, px).rotateQuarters(proxy.imageInfo.rotationDegrees / 90)
}
