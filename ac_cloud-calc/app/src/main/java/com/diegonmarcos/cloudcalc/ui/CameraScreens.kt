package com.diegonmarcos.cloudcalc.ui

import com.diegonmarcos.superapp.perms.rememberPermsMissing
import com.diegonmarcos.superapp.perms.PermsFixChip
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.camera.ArMeasureActivity
import com.diegonmarcos.cloudcalc.camera.CameraDecl
import com.diegonmarcos.cloudcalc.camera.Gravity
import com.diegonmarcos.cloudcalc.camera.Vision
import com.diegonmarcos.cloudcalc.measure.Level
import com.diegonmarcos.cloudcalc.measure.Numbers
import com.diegonmarcos.cloudcalc.measure.Px
import com.diegonmarcos.cloudcalc.measure.Reference
import com.diegonmarcos.superapp.image.mlkit.DecisionModel
import com.diegonmarcos.superapp.image.mlkit.OcrResult
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Engine binds, photo decoding and file copies block: always off the main thread. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/**
 * The camera gate (#1515): a Camera mode that takes a photo composes through it; while CAMERA is missing it shows the
 * compact "Fix in Perms" chip (Configs > Perms asks and grants, nothing is asked here). Level needs no camera.
 */
@Composable
fun CameraGate(content: @Composable () -> Unit) {
    if (rememberPermsMissing(listOf("camera")).isEmpty()) content()
    else Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter)) {
        Text(stringResource(R.string.camera_needed))
        PermsFixChip(listOf("camera"), "camera")
    }
}

/**
 * A photo to work on: the live camera with a shutter, or an image from the phone. Either way it
 * is saved as the last photo (Vision.last, what /api/image/recognize?path=last reads), upright and
 * at the declared size, and handed on as a bitmap.
 */
@Composable
internal fun PhotoSource(onPhoto: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cfg = CameraDecl.config
    var error by remember { mutableStateOf("") }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val preview = remember { PreviewView(ctx) }
    fun use(load: () -> Unit) = scope.launch {
        error = ""
        runCatching { io { load(); Vision.normalise(Vision.last(ctx), cfg.maxSide, cfg.jpegQuality) } }
            .onSuccess { b -> if (b != null) onPhoto(b) else error = ctx.getString(R.string.camera_not_image) }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) use { ctx.contentResolver.openInputStream(uri)?.use { i -> Vision.last(ctx).outputStream().use { i.copyTo(it) } } }
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            runCatching {
                val p = future.get()
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }, capture)
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    Column(Modifier.fillMaxWidth()) {
        AndroidView({ preview }, Modifier.fillMaxWidth().height(CalcMetrics.plotHeight))
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Button(modifier = Modifier.testTag(CameraTags.SHUTTER), onClick = {
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(Vision.last(ctx)).build(), ContextCompat.getMainExecutor(ctx),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { use {} }
                        override fun onError(exception: ImageCaptureException) { error = exception.message ?: exception.javaClass.simpleName }
                    })
            }) { Text(stringResource(R.string.camera_take)) }
            OutlinedButton(onClick = { pick.launch("image/*") }) { Text(stringResource(R.string.camera_pick)) }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

/** A photo fitted to the width, with marks, an optional path through them, boxes, and taps in image pixels. */
@Composable
private fun PhotoView(bmp: Bitmap, marks: List<Px> = emptyList(), path: Boolean = false, boxes: List<Recognition.Box> = emptyList(), onTap: ((Px) -> Unit)? = null) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    val accent = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.onBackground
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).testTag(CameraTags.PHOTO)
            .pointerInput(bmp, onTap) { if (onTap != null) detectTapGestures { o -> val s = bmp.width / size.width.toFloat(); onTap(Px(o.x * s.toDouble(), o.y * s.toDouble())) } },
    ) {
        drawImage(img, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val k = size.width / bmp.width
        boxes.forEach { b -> drawRect(accent, Offset(b.x * k, b.y * k), Size(b.w * k, b.h * k), style = Stroke(CalcMetrics.stroke.toPx())) }
        if (path && marks.size > 1) {
            val p = Path()
            marks.forEachIndexed { i, m -> if (i == 0) p.moveTo(m.x.toFloat() * k, m.y.toFloat() * k) else p.lineTo(m.x.toFloat() * k, m.y.toFloat() * k) }
            drawPath(p, accent, style = Stroke(CalcMetrics.stroke.toPx()))
        }
        marks.forEach { m ->
            drawCircle(ring, CalcMetrics.gap.toPx(), Offset(m.x.toFloat() * k, m.y.toFloat() * k), style = Stroke(CalcMetrics.stroke.toPx()))
            drawCircle(accent, CalcMetrics.small.toPx(), Offset(m.x.toFloat() * k, m.y.toFloat() * k))
        }
    }
}

internal fun mm(v: Double): String = when {
    v >= 1000 -> "%.3f m".format(v / 1000)
    v >= 10 -> "%.1f cm".format(v / 10)
    else -> "%.1f mm".format(v)
}

// ── Measure ─────────────────────────────────────────────────────────────────────────────────

@Composable
fun CameraMeasureMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cfg = CameraDecl.config
    // Offered whenever declared: ArCoreApk.checkAvailability is an async Play Services query that
    // can throw later on the main looper, so it is never run on screen open. ArMeasureActivity asks
    // for ARCore (and its install) only when the user taps AR, and says so when the phone cannot.
    val ar = cfg.arEnabled
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        if (ar) {
            Button(onClick = { ctx.startActivity(Intent(ctx, ArMeasureActivity::class.java)) }, modifier = Modifier.testTag(CameraTags.AR)) { Text(stringResource(R.string.camera_ar)) }
            Text(stringResource(R.string.camera_ar_doc), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = CalcMetrics.gap))
        } else Text(stringResource(R.string.camera_no_ar), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CameraGate { ReferenceMeasure(mode) }
    }
}

@Composable
private fun ReferenceMeasure(mode: Declarations.Mode) {
    val cfg = CameraDecl.config
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var ref by rememberSaveable { mutableStateOf(cfg.references.first().id) }
    var custom by rememberSaveable { mutableStateOf("") }
    var refMarks by remember { mutableStateOf(listOf<Px>()) }
    var marks by remember { mutableStateOf(listOf<Px>()) }
    val refMm = custom.toDoubleOrNull()?.takeIf { it > 0 } ?: cfg.references.first { it.id == ref }.mm
    PhotoSource { photo = it; refMarks = emptyList(); marks = emptyList() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        items(cfg.references, key = { it.id }) { r -> FilterChip(selected = ref == r.id && custom.isBlank(), onClick = { ref = r.id; custom = "" }, label = { Text("${r.label} · ${mm(r.mm)}") }) }
    }
    OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.camera_known_length)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    val p = photo ?: return
    Text(stringResource(if (refMarks.size < 2) R.string.camera_mark_reference else R.string.camera_mark_points), style = MaterialTheme.typography.bodyMedium)
    PhotoView(p, refMarks + marks, path = refMarks.size >= 2) { t -> if (refMarks.size < 2) refMarks = refMarks + t else marks = marks + t }
    Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
        OutlinedButton(onClick = { if (marks.isNotEmpty()) marks = marks.dropLast(1) else refMarks = refMarks.dropLast(1) }) { Text(stringResource(R.string.ar_undo)) }
        OutlinedButton(onClick = { refMarks = emptyList(); marks = emptyList() }) { Text(stringResource(R.string.ar_clear)) }
    }
    if (refMarks.size == 2 && marks.size >= 2) {
        val r = runCatching { Reference.measure(marks, Reference.mmPerPx(refMarks[0], refMarks[1], refMm)) }.getOrNull() ?: return
        val line = stringResource(R.string.camera_measured, mm(r.lengthMm), mm(r.pathMm)) + (r.areaMm2?.let { " · " + "%.1f cm²".format(it / 100) } ?: "")
        Text(line, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(CalcTags.RESULT))
        Text(stringResource(R.string.camera_plane_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AskAboutResult(mode.id, stringResource(R.string.camera_measure_calc, mm(refMm)), line)
    }
}

// ── Level ───────────────────────────────────────────────────────────────────────────────────

@Composable
fun CameraLevelMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val cfg = CameraDecl.config
    var raw by remember { mutableStateOf<Level.Tilt?>(null) }
    var zero by remember { mutableStateOf(Gravity.zero(ctx)) }
    DisposableEffect(Unit) {
        val stop = Gravity.listen(ctx, cfg.levelSampleMs) { x, y, z -> Level.tilt(x, y, z)?.let { raw = it } }
        onDispose { stop() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        if (Gravity.sensor(ctx) == null) { Text(stringResource(R.string.level_no_sensor), color = MaterialTheme.colorScheme.error); return@Column }
        val t = raw?.let { Level.relative(it, zero) }
        val level = t != null && Level.isLevel(t, cfg.levelToleranceDeg)
        Text(t?.let { "%.1f°".format(it.tiltDeg) } ?: "—", style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag(CalcTags.RESULT))
        if (t != null) {
            Fact(stringResource(R.string.level_pitch), "%.1f°".format(t.pitchDeg))
            Fact(stringResource(R.string.level_roll), "%.1f°".format(t.rollDeg))
            Fact(stringResource(R.string.level_edge), "%.1f°".format(t.edgeDeg))
        }
        val good = MaterialTheme.colorScheme.primary
        val bad = MaterialTheme.colorScheme.error
        val ringColour = MaterialTheme.colorScheme.outline
        Canvas(Modifier.fillMaxWidth().height(CalcMetrics.plotHeight)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2.2f
            drawCircle(ringColour, r, c, style = Stroke(CalcMetrics.stroke.toPx()))
            drawCircle(ringColour, r / 8, c, style = Stroke(CalcMetrics.hairline.toPx()))
            if (t != null) {
                val dx = (t.rollDeg / 45.0).coerceIn(-1.0, 1.0).toFloat() * r
                val dy = (t.pitchDeg / 45.0).coerceIn(-1.0, 1.0).toFloat() * r
                drawCircle(if (level) good else bad, r / 10, Offset(c.x - dx, c.y + dy))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Button(onClick = { raw?.let { Gravity.setZero(ctx, it); zero = it } }) { Text(stringResource(R.string.level_set_zero)) }
            OutlinedButton(onClick = { val z = Level.Tilt(0.0, 0.0, 0.0, 0.0); Gravity.setZero(ctx, z); zero = z }) { Text(stringResource(R.string.level_clear_zero)) }
        }
        if (t != null) AskAboutResult(mode.id, stringResource(R.string.level_calc), "tilt %.1f°, pitch %.1f°, roll %.1f°".format(t.tiltDeg, t.pitchDeg, t.rollDeg))
    }
}

// ── Identify ────────────────────────────────────────────────────────────────────────────────

@Composable
fun CameraIdentifyMode(mode: Declarations.Mode) = CameraGate {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Recognition?>(null) }
    var busy by remember { mutableStateOf(false) }
    var context by rememberSaveable { mutableStateOf("") }
    var extra by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        Text(stringResource(R.string.identify_route, RecognitionConfig.routes()[RecognitionPrefs.route(ctx)] ?: RecognitionPrefs.route(ctx)), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(context, { context = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.jev_context)) }, singleLine = true)
        PhotoSource { b ->
            photo = b; result = null; extra = 0
            scope.launch { busy = true; result = io { Vision.recognize(ctx, Vision.last(ctx), context = context) }; busy = false }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val p = photo
        val r = result
        if (p != null) PhotoView(p, boxes = r?.boxes.orEmpty())
        if (r != null) RecognitionView(r, extra) { extra += it }
        if (r != null && r.ok) {
            val top = r.labels.firstOrNull()
            AskAboutResult(mode.id, stringResource(R.string.identify_calc), (top?.let { "${it.label} ${Math.round(it.p * 100)}%" } ?: "") + " · " + stringResource(R.string.identify_count, (r.boxes.size + extra).toString()))
        }
    }
}

/** The uniform result, whichever route answered: labels, count, colours, text, answers. */
@Composable
internal fun RecognitionView(r: Recognition, extra: Int, onCount: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(CameraTags.RECOGNITION)) {
        if (!r.ok) { Text(stringResource(R.string.identify_failed, r.error.orEmpty()), color = MaterialTheme.colorScheme.error); return }
        if (r.fellBack) Text(stringResource(R.string.identify_fell_back, r.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.identify_answered_by, r.route, r.model.ifBlank { "—" }, r.latencyMs.toString()), style = MaterialTheme.typography.bodySmall)
        Bars(r.labels)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Text(stringResource(R.string.identify_count, (r.boxes.size + extra).toString()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(CameraTags.COUNT))
            OutlinedButton(onClick = { onCount(-1) }) { Text("−1") }
            OutlinedButton(onClick = { onCount(1) }) { Text("+1") }
        }
        if (r.colours.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            r.colours.forEach { c ->
                val col = Color((0xFF000000 or (c.hex.removePrefix("#").toLongOrNull(16) ?: 0L)).toInt())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(CalcMetrics.keyHeight).background(col))
                    Text("${c.name} ${Math.round(c.share * 100)}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (r.text.isNotBlank()) SelectionContainer { Text(r.text, style = MaterialTheme.typography.bodyMedium) }
        r.barcode?.let { Text("${it.format}: ${it.rawValue}", style = MaterialTheme.typography.bodyMedium) }
        r.answers.forEach { (q, opts) -> Text(q, style = MaterialTheme.typography.titleSmall); Bars(opts) }
    }
}

@Composable
private fun Bars(labels: List<Recognition.Label>) {
    labels.take(8).forEach { l ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
            Text(l.label, Modifier.weight(1f))
            LinearProgressIndicator(progress = { l.p.toFloat() }, modifier = Modifier.weight(1f).padding(vertical = CalcMetrics.gap))
            Text("${Math.round(l.p * 100)}%")
        }
    }
}

// ── Colour ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun CameraColourMode(mode: Declarations.Mode) = CameraGate {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var at by remember { mutableStateOf<Px?>(null) }
    var argb by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        PhotoSource { photo = it; at = null; argb = null; name = null }
        val p = photo ?: return@Column
        Text(stringResource(R.string.colour_tap), style = MaterialTheme.typography.bodyMedium)
        PhotoView(p, listOfNotNull(at)) { t ->
            at = t
            val c = Vision.average(p, t.x.toInt(), t.y.toInt(), CameraDecl.config.colourSamplePx)
            argb = c; name = null
            scope.launch { name = io { Vision.colourName(ctx, c) } }
        }
        val c = argb ?: return@Column
        val (h, s, l) = Vision.hsl(c)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gutter)) {
            Box(Modifier.size(CalcMetrics.keyHeight * 2).background(Color(c)))
            Column {
                Text(Vision.hex(c), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(CalcTags.RESULT))
                Text("RGB ${(c shr 16) and 0xff}, ${(c shr 8) and 0xff}, ${c and 0xff}")
                Text("HSL $h°, $s%, $l%")
                Text(name ?: stringResource(R.string.colour_naming), style = MaterialTheme.typography.titleMedium)
            }
        }
        AskAboutResult(mode.id, stringResource(R.string.colour_calc), "${Vision.hex(c)} ${name.orEmpty()}")
    }
}

// ── Text → calculator ───────────────────────────────────────────────────────────────────────

@Composable
fun CameraTextMode(mode: Declarations.Mode) = CameraGate {
    val ctx = LocalContext.current
    val state = LocalCalcState.current
    val scope = rememberCoroutineScope()
    var ocr by remember { mutableStateOf<OcrResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        PhotoSource { scope.launch { busy = true; ocr = io { Vision.ocr(ctx, Vision.last(ctx)) }; busy = false } }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val o = ocr ?: return@Column
        o.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val numbers = remember(o) { Numbers.of(o.text) }
        if (numbers.isNotEmpty()) {
            Text(stringResource(R.string.text_numbers), style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap), modifier = Modifier.testTag(CameraTags.NUMBERS)) {
                items(numbers) { n -> AssistChip(onClick = { state.send(CameraDecl.config.ocrSendTo, n) }, label = { Text(n) }) }
            }
            if (numbers.size > 1) Button(onClick = { state.send(CameraDecl.config.ocrSendTo, Numbers.sum(numbers)) }) { Text(stringResource(R.string.text_sum, numbers.size.toString())) }
        }
        SelectionContainer { Text(o.text.ifBlank { stringResource(R.string.text_none) }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(CalcTags.RESULT)) }
    }
}

// ── Configs ▸ Image recognition route ───────────────────────────────────────────────────────

@Composable
fun ImageRouteMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(RecognitionPrefs.route(ctx)) }
    var model by remember { mutableStateOf(RecognitionPrefs.model(ctx)) }
    var models by remember { mutableStateOf(listOf<DecisionModel>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        status = io { Vision.status(ctx) }
        if (status == null) models = io { Vision.models(ctx) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CalcMetrics.gutter)) {
        Text(stringResource(R.string.image_route_doc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status ?: stringResource(R.string.image_engine_ready), color = if (status == null) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CameraTags.ENGINE))
        RecognitionConfig.routes().forEach { (id, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = route == id, role = Role.RadioButton, onClick = { route = id }).testTag(CameraTags.route(id)),
            ) {
                RadioButton(selected = route == id, onClick = null)
                Text(label)
            }
        }
        if (route == RecognitionConfig.OPENROUTER) {
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.image_model)) }, singleLine = true)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                items(models, key = { it.slug }) { m ->
                    FilterChip(selected = model == m.slug, onClick = { model = m.slug }, label = { Text(m.slug + if (m.images) " · " + ctx.getString(R.string.image_sees) else "") })
                }
            }
            if (models.isEmpty()) Text(stringResource(R.string.image_no_catalogue, RecognitionConfig.defaultModel()), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            scope.launch {
                note = runCatching { RecognitionPrefs.set(ctx, route, model); ctx.getString(R.string.image_route_saved) }.getOrElse { it.message.orEmpty() }
            }
        }, modifier = Modifier.testTag(CameraTags.SAVE)) { Text(stringResource(R.string.jev_save)) }
        if (note.isNotBlank()) Text(note)
    }
}

/** The tags the Robolectric suites drive the Camera screens by. */
object CameraTags {
    const val SHUTTER = "camera_shutter"
    const val PHOTO = "camera_photo"
    const val AR = "camera_ar"
    const val RECOGNITION = "camera_recognition"
    const val COUNT = "camera_count"
    const val NUMBERS = "camera_numbers"
    const val ENGINE = "camera_engine"
    const val SAVE = "camera_save"
    fun route(id: String) = "camera_route_$id"
}
